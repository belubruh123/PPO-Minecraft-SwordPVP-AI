package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.report.ReportWriter;
import dev.swordai.core.report.UpdateRecord;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.SafeLearn;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * {@code /swordai fight} that the network also learns from: it plays the user's own character
 * against another player, in whatever world or server the user is in, and every fight is gathered,
 * scored with the rewards of the stage it is in, and learned from. What it sees and presses is
 * exactly what {@link FightSession} sees and presses.
 *
 * <p>There is no arena here and no server to ask who hit whom, so everything the rewards need is
 * taken from what this client can see: a flinch tells who was hit, the game client's own reading
 * of its swing tells what kind of hit it was, and the health bars tell the damage. That is a tick
 * or more late and less exact than in training, which is the price of learning from a real fight.
 *
 * <p>These fights never count towards passing a stage. And the fight does not wait while the
 * network learns from a batch: a copy of it plays on meanwhile (see {@link Trainer#playsOnWhileLearning}).
 */
final class FightLearnSession extends Trainer {
	/** This many decisions in a row on the same picture means the game has stopped drawing. */
	private static final int FROZEN_FRAME_TICKS = 20;
	private static final int RESPAWN_TICKS = 30;
	/** The opponent flinching this soon after a swing that had it under the crosshair counts as that swing landing. */
	private static final int HIT_CREDIT_TICKS = 12;
	/**
	 * Learning safely, a turn of one of the two networks ends at a knockout, and also once the opponent
	 * is gone after this many ticks of fighting (where a death never reaches this game: some servers
	 * put a beaten player straight back, a Carpet fake player leaves at once), ...
	 */
	private static final int SHORTEST_TURN_TICKS = 100;
	/** ... or after this many ticks of fighting without one, so that the two always take turns. */
	private static final int LONGEST_TURN_TICKS = 1200;
	/** The opponent's health dropping this soon after such a hit is that hit's damage. */
	private static final int DAMAGE_CREDIT_TICKS = 5;
	/** Vanilla turns a sprinting swing into a knockback hit only above this charge. */
	private static final float KNOCKBACK_CHARGE = 0.9f;
	/** Either fighter moving further than this in one tick (squared, blocks) was put there, not knocked or walked. */
	private static final double PUT_ELSEWHERE_SQR = 64.0;
	/** A hit taken this soon after the opponent was seen falling is counted as a critical hit. */
	private static final int CRIT_SEEN_TICKS = 6;
	/** A sword's recharge time, for an opponent whose own is not known to this client. */
	private static final float SWORD_RECHARGE_TICKS = 12.5f;
	/** Against people the actor learns from a batch only if the critic explains at least this share of what it was worth. */
	private static final float LEARN_FROM_EXPLAINED = 0.2f;
	/**
	 * Learning in safety ({@code Config.safeLearn}): each update moves the learning copy at most this
	 * share of the way a training update may (learning rate), over at most {@link #SAFE_EPOCHS} passes,
	 * and stops early at {@link #SAFE_KL_STOP}. Up to 0.13.3 an update from a fight moved the network
	 * three to seven times as far as a training update.
	 */
	private static final float SAFE_LR_SHARE = 0.25f;
	private static final int SAFE_EPOCHS = 4;
	private static final float SAFE_KL_TARGET = 0.005f;
	private static final float SAFE_KL_STOP = 0.01f;

	/** Null: fight whoever is there. */
	private final String wantedName;
	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	/** Notices when the picture has lost the opponent; acted on while the frame is drawn twice. */
	private final BlindGuard blind = new BlindGuard();
	private final long startedMillis = System.currentTimeMillis();
	private final long stepsAtStart;
	private final long updatesAtStart;
	/** A copy of the network from before it learned from people was kept when this began. */
	private final boolean keptCopy;
	/** Learning in safety: the proven network and a learning copy take turns; see SafeLearn. */
	private final boolean safe;
	private final SafeLearn score = new SafeLearn();

	/** The player has the controls: a menu is open, the character is dead, or nobody is there to fight. */
	private boolean handedBack = true;
	private String waitingFor = "an opponent";
	/** The fight being fought has been decided; the step being scored is its last. */
	private boolean fightOver;
	/** Ticks the network now playing has fought since its turn began; see SHORTEST_TURN_TICKS. */
	private int turnTicks;
	private boolean actedThisTick;
	private boolean clickResolved;
	private boolean clickOnTarget;
	private float turnYawThisTick;
	private float turnPitchThisTick;
	private boolean debugRequested;

	private UUID opponentId;
	private Player opponent;
	private String opponentName = "?";
	private double groundY;
	private int frozenTicks;
	private int deadTicks;
	private boolean deathCounted;
	private boolean killCounted;

	// ---- what this client has seen of the fight since the last step ------------------------------
	private int pendHitsDealt;
	private float pendHitCharge;
	private boolean pendSprintHit;
	private boolean pendCrit;
	private boolean pendRising;
	private boolean pendSweep;
	private float pendDamageDealt;
	private int pendHitsTaken;
	private int pendCritsTaken;
	private float pendDamageTaken;
	private boolean pendKoDealt;
	private boolean pendKoTaken;

	// ---- and what it goes by ---------------------------------------------------------------------
	private int lastOwnHurt;
	private int lastOpponentHurt;
	private float lastOwnHealth;
	private float lastOpponentHealth;
	private Vec3 lastOwnPos = Vec3.ZERO;
	private Vec3 lastOpponentPos = Vec3.ZERO;
	private int ticksSinceConnect = 1000;
	private int ticksSinceCredited = 1000;
	private int ticksSinceOpponentFell = 1000;
	private int opponentSinceSwing = 1000;
	private boolean lastOpponentSwinging;
	private int lastOpponentSwingTime;
	/** Counts up whenever either fighter was put somewhere else: nothing is compared across that. */
	private int hops;
	/** The last swing that had the opponent under the crosshair, as the game client made it out. */
	private float swingCharge;
	private boolean swingSprint;
	private boolean swingCrit;
	private boolean swingRising;
	private boolean swingSweep;
	/** {@link OwnSwing#count} when this tick's action was applied, and what the swing would be by this class's own reckoning. */
	private int swingsBefore;
	private float guessCharge;
	private boolean guessSprint;

	// ---- for the summary -------------------------------------------------------------------------
	private long ticks;
	private long visibleTicks;
	private long onTargetTicks;
	/** How far the crosshair was off the opponent's body while it was in view (nothing while on it), summed over those ticks: sideways, and up or down. */
	private double offSideways;
	private double offUpDown;
	private long clicks;
	private long clicksOnTarget;
	private long hitsDealt;
	private long hitsTaken;
	private int kills;
	private int deaths;

	/**
	 * @param opponentName the player to fight, or null for whoever is nearest
	 * @throws IOException when there is no trained network to play with, or it cannot be read
	 */
	FightLearnSession(Minecraft mc, String opponentName) throws IOException {
		super(mc, Mode.TRAIN, true);
		this.wantedName = opponentName;
		this.stepsAtStart = learner.totalSteps;
		this.updatesAtStart = learner.updates;
		this.keptCopy = SwordFiles.keepBeforePeople(history, this::say);
		this.safe = cfg.safeLearn;
		if (safe) {
			startSafely();
		}
		try {
			this.groundY = mc.player.getY();
			// Until the duel it has never seen its health stay down; it is shown it as its stage shows it.
			observer.showHealthPatchedUp(curriculum.current().bot.agentImmortal);
			grabber.setCrop(ViewGeometry.cropFor(Math.min(cfg.visionFov, cfg.fov), cfg.fov));
			OptionsGuard.apply(mc, cfg.fov);
			KeyedSkin.setTarget(null);
			// Nobody is recoloured until the opponent is known; then only the opponent is.
			grabber.setEnabled(true);
		} catch (RuntimeException e) {
			closeLearning();
			throw e;
		}
		// Running from here on; who has the controls is another matter.
		phase = Phase.RUN;
	}

	/** What it plays with and learns towards, for the message shown when the fight starts. */
	String skillLine() {
		String stageName = "stage " + (curriculum.stageIndex() + 1) + "/" + curriculum.stageCount() + " (" + curriculum.current().name + ")";
		if (!ActionSpace.unlocked(curriculum.current().headMask, ActionSpace.ATTACK)) {
			return "It has only reached " + stageName + " and has not learned to hit yet, so it will aim and move but not swing, and learn only that from this.";
		}
		return "It plays with what it knows at " + stageName + " and learns from this with that stage's rewards; fights against players never count towards passing a stage.";
	}

	boolean keptCopy() {
		return keptCopy;
	}

	boolean safe() {
		return safe;
	}

	/** For the message shown when the fight starts. */
	String safeLine() {
		return score.fights(true) + score.fights(false) == 0 ? "No fights counted yet." : "So far: " + score.line() + ".";
	}

	// ---- learning in safety ---------------------------------------------------------------------

	/**
	 * The network as it was trained (or as it last took over) is the proven one and is kept as it is;
	 * the learning copy carries on from where the last session left it, if that was a copy of this
	 * same proven network.
	 */
	private void startSafely() {
		learner.keepBase();
		cfg.epochs = Math.min(cfg.epochs, SAFE_EPOCHS);
		cfg.klTarget = Math.min(cfg.klTarget, SAFE_KL_TARGET);
		cfg.klStop = cfg.klStop > 0f ? Math.min(cfg.klStop, SAFE_KL_STOP) : SAFE_KL_STOP;
		cfg.maxActorLr = Math.min(cfg.maxActorLr, cfg.actorLr * SAFE_LR_SHARE);
		try {
			Properties p = new Properties();
			if (Files.exists(SwordFiles.learningScore())) {
				try (Reader in = Files.newBufferedReader(SwordFiles.learningScore())) {
					p.load(in);
				}
			}
			if (Files.exists(SwordFiles.learning()) && provenStamp().equals(p.getProperty("proven"))) {
				long steps = learner.totalSteps;
				long updates = learner.updates;
				learner.load(SwordFiles.learning());
				learner.totalSteps = Math.max(steps, learner.totalSteps);
				learner.updates = Math.max(updates, learner.updates);
				score.load(p);
			} else {
				// No copy yet, or one of another network: the copy starts as the proven network.
				score.restart();
				score.promotions = Integer.parseInt(p.getProperty("promotions", "0"));
				score.rollbacks = Integer.parseInt(p.getProperty("rollbacks", "0"));
			}
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not read the learning copy", e);
			say("The learning copy could not be read (" + e.getMessage() + "); it starts again from the proven network.");
			learner.restoreBase();
			score.restart();
		}
		learner.capActorLr(cfg.actorLr * SAFE_LR_SHARE);
		baseActs = score.nextIsBase();
	}

	/** Which proven network the learning copy belongs to: the checkpoint as it is on disk. */
	private static String provenStamp() throws IOException {
		Path c = SwordFiles.checkpoint();
		return Files.getLastModifiedTime(c).toMillis() + ":" + Files.size(c);
	}

	private void saveScore() {
		try {
			Properties p = new Properties();
			score.save(p);
			p.setProperty("proven", provenStamp());
			try (Writer out = Files.newBufferedWriter(SwordFiles.learningScore())) {
				p.store(out, "SwordAI: the proven network and the learning copy in their fights (see SafeLearn)");
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not save how the learning copy is doing", e);
		}
	}

	/**
	 * A fight is over: counted for whichever played it, and the other plays the next. Acted on here,
	 * between fights, and only with no update running: the copy takes over, or is thrown away.
	 */
	private void safeFightOver() {
		turnTicks = 0;
		score.fightOver(byBase);
		SafeLearn.Verdict verdict = pendingUpdate == null ? score.verdict() : SafeLearn.Verdict.NONE;
		if (verdict == SafeLearn.Verdict.PROMOTE) {
			String line = score.line();
			try {
				SwordFiles.keepBeforePromotion();
				learner.save(SwordFiles.checkpoint());
				learner.save(SwordFiles.learning());
				learner.keepBase();
				score.promotions++;
				score.restart();
				say("The learning copy did better than the proven network (" + line + "), so it takes its place and is the proven network now. /swordai undo brings back the one it replaced.");
			} catch (IOException e) {
				say("The learning copy did better (" + line + ") but could not be saved (" + e.getMessage() + "); the proven network stays.");
			}
		} else if (verdict == SafeLearn.Verdict.ROLLBACK) {
			String line = score.line();
			learner.restoreBase();
			score.rollbacks++;
			score.restart();
			try {
				learner.save(SwordFiles.learning());
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not save the learning copy", e);
			}
			say("The learning copy did no better than the proven network (" + line + "): what it learned is thrown away and it starts again from the proven network, which was never changed.");
		} else {
			say("Learning safely: " + score.line() + ". Next fight: " + (score.nextIsBase() ? "the proven network." : "the learning copy."));
		}
		baseActs = score.nextIsBase();
		saveScore();
	}

	@Override
	Path learnedFile() {
		return safe ? SwordFiles.learning() : SwordFiles.checkpoint();
	}

	// ---- AgentSession ---------------------------------------------------------------------------

	@Override
	public boolean inControl() {
		return phase != Phase.STOPPED && !handedBack;
	}

	@Override
	public void worldDrawn() {
		grabber.worldDrawn(mc);
	}

	@Override
	public void tick() {
		actedThisTick = false;
		turnYawThisTick = 0f;
		turnPitchThisTick = 0f;
		if (phase == Phase.STOPPED) {
			return;
		}
		try {
			tickUnsafe();
		} catch (RuntimeException | Error e) {
			fail("internal error: " + e, e);
		}
	}

	private void tickUnsafe() {
		LocalPlayer me = mc.player;
		if (me == null || mc.level == null) {
			stop("left the world");
			return;
		}
		if (grabber.failure() != null) {
			fail(grabber.failure(), null);
			return;
		}
		if (!updateFinished()) {
			return;
		}
		mc.options.pauseOnLostFocus = false;
		OptionsGuard.hold(mc);

		if (me.isDeadOrDying()) {
			if (!deathCounted) {
				deathCounted = true;
				deaths++;
				endFight(false, true);
			}
			handBack("the respawn");
			if (cfg.fightAutoRespawn && ++deadTicks == RESPAWN_TICKS) {
				me.respawn();
			}
			return;
		}
		deadTicks = 0;
		deathCounted = false;

		// Any menu hands the game back to the player; the chat does not.
		Screen screen = mc.screen;
		if (mc.isPaused() || (screen != null && !(screen instanceof ChatScreen))) {
			handBack("the menu to close");
			return;
		}

		Player opp = Opponents.find(mc, me, wantedName, opponentId);
		if (opp == null) {
			endTurn();
			handBack(wantedName == null ? "an opponent" : wantedName);
			return;
		}
		if (opp != opponent) {
			endTurn();
			// A new opponent, or the same one in a new body after respawning: whatever was being
			// gathered against the old one ends here.
			handBack("the opponent");
			opponent = opp;
			if (!opp.getUUID().equals(opponentId)) {
				say("Fighting " + opp.getScoreboardName() + ".");
			}
			opponentId = opp.getUUID();
			opponentName = opp.getScoreboardName();
			KeyedSkin.setTarget(opponentId);
			KeyedSkin.setTwoPass(cfg.showOpponentSkin);
			KeyedSkin.setActive(true);
		}
		if (opp.isDeadOrDying()) {
			if (!killCounted) {
				killCounted = true;
				kills++;
				endFight(true, false);
			}
			handBack(opponentName + " to respawn");
			return;
		}
		killCounted = false;
		if (safe && turnTicks >= LONGEST_TURN_TICKS) {
			// A long fight with no knockout: the other network's turn, as if one had been.
			endTurn();
			turnTicks = 0;
			return;
		}

		if (!observer.capture(grabber)) {
			AgentController.release(mc);
			return;
		}
		if (!observer.stale()) {
			frozenTicks = 0;
		} else if (++frozenTicks >= FROZEN_FRAME_TICKS) {
			// The game is not drawing; it cannot play blind.
			handBack("the game to draw frames again");
			return;
		}
		if (handedBack) {
			// Taking over (again): from rest, on the ground the character stands on, with nothing owed.
			handedBack = false;
			groundY = me.getY();
			takeBearings(me, opp);
			clearPending();
			beginAgainstPerson();
		}
		Opponents.keepView(mc, me);
		if (debugRequested) {
			debugRequested = false;
			try {
				say("Saved what it sees to " + DebugDump.dump(grabber, observer.frame));
			} catch (IOException | RuntimeException e) {
				say("Could not save the debug images: " + e);
			}
		}

		watchFight(me, opp);
		if (me.onGround()) {
			groundY = me.getY();
		}
		decisions++;
		if (observer.stale()) {
			staleDecisions++;
		}
		step(Truth.INACTIVE);
		turnTicks++;
		if (KeyedSkin.twoPass() && blind.note(facts)) {
			SwordAiClient.skinBlindsTheAgent(grabber.grabCounts());
			// What it did blind is not a fight to learn from: it is cut here and taken up again at once.
			handBack("its sight to come back");
		}

		// For the summary; the step has just measured both.
		ticks++;
		if (facts.enemyVisible) {
			visibleTicks++;
			offSideways += facts.yawMissDeg;
			offUpDown += Math.sqrt(Math.max(0f, facts.missDeg * facts.missDeg - facts.yawMissDeg * facts.yawMissDeg));
		}
		if (facts.onBody) {
			onTargetTicks++;
		}
	}

	/** End of every client tick: by now vanilla has processed this tick's click. */
	@Override
	public void endTick() {
		if (phase == Phase.STOPPED || handedBack || !clicked || clickResolved) {
			return;
		}
		clickResolved = true;
		clicks++;
		clickOnTarget = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == opponent;
		if (!clickOnTarget) {
			return;
		}
		clicksOnTarget++;
		boolean byGame = OwnSwing.count() != swingsBefore;
		float charge = byGame ? OwnSwing.charge() : guessCharge;
		// The opponent flinches a round trip to the server later, and by then there may have been
		// another swing. The flinch belongs to the swing that did the damage, which is the charged one:
		// a swing made right after it, with the weapon empty, lands while the opponent cannot be hurt
		// and does nothing. So while a swing is still waiting to be seen to land, a weaker one after it
		// does not take its place. (Up to 0.13.2 the last swing before the flinch was taken for the hit,
		// and a charged sprint hit followed by one more click went down as a weak hit.)
		boolean waiting = ticksSinceConnect <= HIT_CREDIT_TICKS;
		if (waiting && charge <= swingCharge) {
			return;
		}
		ticksSinceConnect = waiting ? ticksSinceConnect : 0;
		swingCharge = charge;
		if (byGame) {
			// The game client made the attack and worked out for itself what kind of hit it is.
			swingSprint = OwnSwing.sprinting();
			swingCrit = OwnSwing.crit();
			swingRising = OwnSwing.rising();
			swingSweep = OwnSwing.sweep();
		} else {
			swingSprint = guessSprint;
			swingCrit = false;
			swingRising = false;
			swingSweep = false;
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || !inControl()) {
			return false;
		}
		actedThisTick = false;
		AgentController.hold(mc, action);
		if (clicked && answerAtAction && !crosshairOnOpponent()) {
			// The rule's swing is only made at an opponent that is under the crosshair now that the camera has turned.
			answerCancelled();
		}
		return clicked;
	}

	@Override
	boolean crosshairOnOpponent() {
		return opponent != null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == opponent;
	}

	@Override
	public float viewYawLag(float partialTick) {
		return -(1f - Math.max(0f, Math.min(1f, partialTick))) * turnYawThisTick;
	}

	@Override
	public float viewPitchLag(float partialTick) {
		return -(1f - Math.max(0f, Math.min(1f, partialTick))) * turnPitchThisTick;
	}

	@Override
	public boolean requestDebugDump() {
		debugRequested = true;
		return true;
	}

	@Override
	public String describe() {
		return "FIGHT vs " + opponentName + ", learning from it, " + (handedBack ? "waiting for " + waitingFor : "fighting");
	}

	@Override
	String paceLine() {
		return null;
	}

	// ---- the fight, as this client sees it ----------------------------------------------------------

	/** Where everything stands as the network takes over: nothing before this is counted as part of the fight. */
	private void takeBearings(LocalPlayer me, Player opp) {
		lastOwnHurt = me.hurtTime;
		lastOpponentHurt = opp.hurtTime;
		lastOwnHealth = me.getHealth() + me.getAbsorptionAmount();
		lastOpponentHealth = opp.getHealth();
		lastOwnPos = me.position();
		lastOpponentPos = opp.position();
		lastOpponentSwinging = opp.swinging;
		lastOpponentSwingTime = opp.swingTime;
		ticksSinceConnect = 1000;
		ticksSinceCredited = 1000;
		ticksSinceOpponentFell = 1000;
		opponentSinceSwing = 1000;
	}

	private void clearPending() {
		pendHitsDealt = 0;
		pendHitCharge = 0f;
		pendSprintHit = false;
		pendCrit = false;
		pendRising = false;
		pendSweep = false;
		pendDamageDealt = 0f;
		pendHitsTaken = 0;
		pendCritsTaken = 0;
		pendDamageTaken = 0f;
		pendKoDealt = false;
		pendKoTaken = false;
	}

	/** Who hit whom on this tick, and how hard, from what this client can see. */
	private void watchFight(LocalPlayer me, Player opp) {
		if (ticksSinceConnect < 1000) {
			ticksSinceConnect++;
		}
		if (ticksSinceCredited < 1000) {
			ticksSinceCredited++;
		}
		// A critical hit is made while falling and not sprinting; that much of the opponent is visible.
		boolean falling = !opp.onGround() && opp.getY() < opp.yo - 0.01 && !opp.isSprinting();
		ticksSinceOpponentFell = falling ? 0 : Math.min(1000, ticksSinceOpponentFell + 1);

		if (me.hurtTime > lastOwnHurt) {
			pendHitsTaken++;
			hitsTaken++;
			if (ticksSinceOpponentFell <= CRIT_SEEN_TICKS) {
				pendCritsTaken++;
			}
		}
		lastOwnHurt = me.hurtTime;
		if (opp.hurtTime > lastOpponentHurt && ticksSinceConnect <= HIT_CREDIT_TICKS) {
			pendHitsDealt++;
			hitsDealt++;
			boolean knockback = swingSprint && swingCharge > KNOCKBACK_CHARGE;
			pendHitCharge = swingCharge;
			pendSprintHit |= knockback;
			pendCrit |= swingCrit;
			pendRising |= swingRising && knockback;
			pendSweep |= swingSweep;
			ticksSinceConnect = 1000;
			ticksSinceCredited = 0;
		}
		lastOpponentHurt = opp.hurtTime;

		float own = me.getHealth() + me.getAbsorptionAmount();
		if (own < lastOwnHealth) {
			pendDamageTaken += lastOwnHealth - own;
		}
		lastOwnHealth = own;
		// Not every server tells a client how much health another player has; where it does not, this stays zero.
		float theirs = opp.getHealth();
		if (theirs < lastOpponentHealth && ticksSinceCredited <= DAMAGE_CREDIT_TICKS) {
			pendDamageDealt += lastOpponentHealth - theirs;
		}
		lastOpponentHealth = theirs;

		boolean newSwing = opp.swinging && (!lastOpponentSwinging || opp.swingTime < lastOpponentSwingTime);
		opponentSinceSwing = newSwing ? 0 : Math.min(1000, opponentSinceSwing + 1);
		lastOpponentSwinging = opp.swinging;
		lastOpponentSwingTime = opp.swingTime;

		Vec3 ownPos = me.position();
		Vec3 oppPos = opp.position();
		if (ownPos.distanceToSqr(lastOwnPos) > PUT_ELSEWHERE_SQR || oppPos.distanceToSqr(lastOpponentPos) > PUT_ELSEWHERE_SQR) {
			hops++;
		}
		lastOwnPos = ownPos;
		lastOpponentPos = oppPos;
	}

	/** How far the opponent's weapon has recharged, going by when it was last seen to swing. For the critic only. */
	private float opponentCharge() {
		float recharge = opponent.getCurrentItemAttackStrengthDelay();
		if (!(recharge >= 1f && recharge <= 100f)) {
			recharge = SWORD_RECHARGE_TICKS;
		}
		return Mth.clamp(opponentSinceSwing / recharge, 0f, 1f);
	}

	/**
	 * One of the two is down. The step in progress is scored as the fight's last, with the knockout.
	 * If nothing was being gathered (the player had the controls, or a copy was playing while the
	 * network learned), there is nothing to score.
	 */
	private void endFight(boolean won, boolean lost) {
		if (handedBack || !hasLastAction || opponent == null) {
			return;
		}
		if (pendingUpdate == null) {
			pendKoDealt = won;
			pendKoTaken = lost;
			fightOver = true;
			cutNow = true;
			try {
				step(Truth.INACTIVE);
			} finally {
				cutNow = false;
				fightOver = false;
			}
		}
		if (safe) {
			if (byBase && pendingUpdate == null) {
				// The proven network played it: nothing was scored, so the fight is closed here.
				releaseKeys();
				beginEpisode(Truth.INACTIVE);
			}
			safeFightOver();
		}
	}

	/**
	 * Learning safely: the turn of the network playing ends without a knockout (the opponent is gone,
	 * or the fight has gone on long), counted like a fight; a turn too short to say anything goes on.
	 */
	private void endTurn() {
		if (safe && turnTicks >= SHORTEST_TURN_TICKS) {
			endFight(false, false);
		}
	}

	/** How many steps of a fight are learned from at a time; see {@code Config.fightRolloutSteps}. */
	private int fightRollout() {
		if (safe) {
			// Learning in safety: from as much at a time as a training update, not from a minute or less.
			return Math.max(64, curriculum.current().rolloutSteps);
		}
		return Math.max(64, Math.min(curriculum.current().rolloutSteps, cfg.fightRolloutSteps));
	}

	/** Lets go of the keys and gives the mouse and keyboard back until the fight can go on. */
	private void handBack(String why) {
		waitingFor = why;
		clicked = false;
		if (handedBack) {
			return;
		}
		handedBack = true;
		if (hasLastAction) {
			// An episode was still open: it is cut here, and what follows it is not its business.
			suspendEpisode();
		} else {
			AgentController.release(mc);
			restHand();
		}
		if (pendingUpdate == null && learner.rolloutReady(fightRollout())) {
			startUpdate();
		}
	}

	// ---- stopping -------------------------------------------------------------------------------

	@Override
	public void stop(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		Phase was = phase;
		try {
			handBack("the stop");
			AgentController.release(mc);
			learnFromTheRest();
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("could not learn from the rest of the fight", e);
		}
		phase = Phase.STOPPED;
		try {
			saveProgress(was);
			if (safe) {
				saveScore();
			}
		} finally {
			closeLearning();
			grabber.close();
			KeyedSkin.setActive(false);
			KeyedSkin.setTarget(null);
			OptionsGuard.restore(mc);
		}
		String summary = summary();
		SwordFiles.writeLastFight(summary);
		say("Stopped (" + reason + "). " + summary);
	}

	// ---- text -----------------------------------------------------------------------------------

	private String learnedLine() {
		return String.format(Locale.ROOT, "learned from %,d steps of it in %d updates (one about every %d s of fighting; it fights on while it learns)%s",
				learner.totalSteps - stepsAtStart - learner.rolloutSize(), learner.updates - updatesAtStart, wantedRollout() / 20,
				pendingUpdate != null ? "; learning right now" : "");
	}

	/** One line with everything worth knowing about the fight; also what the report shows afterwards. */
	/** How far both fighters were from the server, as last known while the fight was on. */
	private String connection = "";

	private String summary() {
		long seconds = (System.currentTimeMillis() - startedMillis) / 1000;
		String now = Opponents.connectionLine(mc, opponentId);
		if (!now.isEmpty()) {
			connection = now;
		}
		return String.format(Locale.ROOT,
				"vs %s, %s in control of %s: hits %d dealt / %d taken, kills %d, deaths %d, swings %d (%.0f%% with the opponent under the crosshair), "
						+ "on target %.0f%%, opponent in view %.0f%%, crosshair off it by %.1f deg sideways and %.1f deg up or down on average (0 while on it)%s; %s",
				opponentName, ReportWriter.duration(ticks / 20), ReportWriter.duration(seconds), hitsDealt, hitsTaken, kills, deaths,
				clicks, percent(clicksOnTarget, clicks), percent(onTargetTicks, ticks), percent(visibleTicks, ticks),
				offSideways / Math.max(1, visibleTicks), offUpDown / Math.max(1, visibleTicks),
				connection, learnedLine());
	}

	private static float percent(long part, long whole) {
		return whole <= 0 ? 0f : 100f * part / whole;
	}

	@Override
	public List<String> statusLines() {
		List<String> out = new ArrayList<>();
		out.add(describe() + (handedBack ? " (you have the controls)." : "."));
		out.add(summary());
		out.add(skillLine());
		return out;
	}

	// ---- the body, as the trainer asks for it -------------------------------------------------------

	@Override
	void releaseKeys() {
		AgentController.release(mc);
	}

	@Override
	void prepareBody() {
	}

	@Override
	void resetSight() {
		observer.resetEpisode();
	}

	@Override
	float bodyCharge() {
		return mc.player.getAttackStrengthScale(0f);
	}

	@Override
	float swingMinCharge() {
		// A tick in hand on someone's server; see Config.swingMinChargeOnline.
		return mc.hasSingleplayerServer() ? cfg.swingMinCharge : Math.max(cfg.swingMinCharge, cfg.swingMinChargeOnline);
	}

	@Override
	float ruleSwingCharge() {
		return mc.hasSingleplayerServer() ? super.ruleSwingCharge() : Math.max(super.ruleSwingCharge(), cfg.ruleSwingChargeOnline);
	}

	@Override
	boolean bodyOnGround() {
		return mc.player.onGround();
	}

	@Override
	float bodyPitch() {
		return mc.player.getXRot();
	}

	/** There is no server to ask; the client's own word is what it last told the server. */
	@Override
	boolean serverSprinting(Truth truth) {
		return mc.player.isSprinting();
	}

	@Override
	void fillGeometry(StepFacts facts) {
		observer.fillGeometry(facts, mc.player, opponent);
	}

	@Override
	void fillObs(Obs obs, Truth truth, int[] lastActionOrNull) {
		// In a stage whose opponent cannot lose health the critic has only ever been told it is full.
		float health = stage.bot.botImmortal ? opponent.getMaxHealth() : opponent.getHealth();
		observer.fillObs(obs, mc.player, opponent, groundY, health, opponentCharge(), opponent.isSprinting(),
				opponent.onGround(), facts, lastActionOrNull, stage.headMask, episodeStageIndex, clock, motor, idea);
	}

	@Override
	boolean frameLate() {
		return observer.late();
	}

	@Override
	boolean clickWasOnTarget() {
		return clickOnTarget;
	}

	@Override
	void applyAction(int[] action, float yawRate, float pitchRate) {
		LocalPlayer me = mc.player;
		float yawBefore = me.getYRot();
		float pitchBefore = me.getXRot();
		AgentController.apply(mc, action, yawRate, pitchRate);
		lastYawTurn = me.getYRot() - yawBefore;
		// Less than asked when the view is already straight up or down.
		lastPitchTurn = me.getXRot() - pitchBefore;
		turnYawThisTick = lastYawTurn;
		turnPitchThisTick = lastPitchTurn;
		actedThisTick = true;
		clickResolved = false;
		clickOnTarget = false;
		// What a swing on this tick is made with, should the game client not say so itself.
		swingsBefore = OwnSwing.count();
		guessCharge = me.getAttackStrengthScale(0.5f);
		guessSprint = me.isSprinting();
	}

	@Override
	float frameAgeTicks() {
		return SwordFiles.readFrameAgeMillis() / 50f;
	}

	@Override
	float captureMillis() {
		return grabber.takeMeanLatencyMillis();
	}

	@Override
	float wantedSpeed() {
		return 1f;
	}

	// ---- what the trainer asks about the opponent -------------------------------------------------------

	/**
	 * On someone else's server the opponent is seen to flinch a round trip after the swing: four or
	 * five ticks at 200 ms. In the arena the network is told of its hit on the tick after the swing,
	 * and everything it does next hangs on that: it lets go of W, and it waits for its weapon. Left to
	 * wait for the flinch here, it takes every swing for a miss for those ticks and does what it does
	 * after a miss, which is to swing again at once (0.13.2, 190 ms: six to seven swings in ten made
	 * before the weapon was 60% back, against one in ten in training).
	 *
	 * <p>So here it is told when its own game sent the attack: the swing had the opponent under the
	 * crosshair and in reach as its game saw it. That is the same moment it is told in training, and
	 * as good as the same thing there. What a hit is worth is still counted from the flinch.
	 */
	@Override
	int hitsDealtAsShown(StepFacts facts) {
		return facts.clickOnTarget ? 1 : 0;
	}

	@Override
	float actorNeedsExplained() {
		return LEARN_FROM_EXPLAINED;
	}

	@Override
	void drainCombat(StepFacts f) {
		f.hitsDealt = pendHitsDealt;
		f.hitCharge = pendHitCharge;
		f.sprintHit = pendSprintHit;
		f.critDealt = pendCrit;
		// Whether the rise came from a jump of its own is for the trainer to say.
		f.uppercut = pendRising;
		f.sweep = pendSweep;
		f.damageDealt = pendDamageDealt;
		f.hitsTaken = pendHitsTaken;
		f.critsTaken = pendCritsTaken;
		f.damageTaken = pendDamageTaken;
		f.koDealt = pendKoDealt;
		f.koTaken = pendKoTaken;
		clearPending();
		if (safe && !handedBack) {
			// What this step came to, for whichever of the two chose it.
			score.tick(byBase, f.damageDealt, f.damageTaken);
		}
	}

	@Override
	int hopId(Truth truth) {
		return hops;
	}

	/** The fight is over, or the player has the controls: nothing is played until the network takes over again. */
	@Override
	void beginEpisode(Truth truth) {
		hasLastAction = false;
		clicked = false;
	}

	/** A fight lasts until someone is down, however long that takes. */
	@Override
	int episodeLimit() {
		return Integer.MAX_VALUE;
	}

	@Override
	boolean stageFights() {
		return false;
	}

	@Override
	int opponentKind() {
		return UpdateRecord.OPPONENT_FIGHT;
	}

	@Override
	boolean fightGoesOn() {
		return !handedBack && !fightOver;
	}

	@Override
	boolean playsOnWhileLearning() {
		return true;
	}
}
