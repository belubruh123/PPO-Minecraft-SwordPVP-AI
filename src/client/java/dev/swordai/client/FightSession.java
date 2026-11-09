package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.report.ReportWriter;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Fighter;
import dev.swordai.core.school.AbPool;
import dev.swordai.core.school.SchoolFighter;
import dev.swordai.core.school.SchoolLearner;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The trained network plays the user's own character against another player, in whatever world
 * or server the user is in. Nothing is learned and nothing on the server is touched: it sees the
 * opponent through the same colour-keyed picture as in training and presses the same keys.
 *
 * <p>There is no arena and no ground truth here, so what training got from the server is taken
 * from what this client can see: a flinch tells who was hit, the last ground stood on is the floor.
 */
final class FightSession implements AgentSession {
	/** This many decisions in a row on the same picture means the game has stopped drawing. */
	private static final int FROZEN_FRAME_TICKS = 20;
	private static final int RESPAWN_TICKS = 30;
	/** The opponent flinching this soon after a swing that had it under the crosshair counts as that swing landing. */
	private static final int HIT_CREDIT_TICKS = 12;

	private final Minecraft mc;
	private final LoadedFighter loaded;
	private final Fighter fighter;
	/**
	 * The technique school's network, where one is installed ({@code swordai/school/checkpoint.bin}): it plays
	 * instead of {@link #fighter}, with no rule pressing anything for it. Null otherwise.
	 */
	private final SchoolLearner schoolLearner;
	private final SchoolFighter school;
	/** What the school's network remembers of this opponent; see {@link DuelReader}. */
	private final DuelReader reader = new DuelReader();
	private float chargeAtClick;
	private boolean sprintAtClick;
	private boolean critAtClick;
	/** Null: fight whoever is there. */
	private final String wantedName;
	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	private final BodyState body = new BodyState();
	/** The installed model's own view of its body, for its fight-time rules over the school's network. */
	private final BodyState rulesBody = new BodyState();
	private final StepFacts facts = new StepFacts();
	/** Notices when the picture has lost the opponent; acted on while the opponent's own skin is shown. */
	private final BlindGuard blind = new BlindGuard();
	private final long startedMillis = System.currentTimeMillis();

	private boolean stopped;
	/** The player has the controls: a menu is open, the character is dead, or nobody is there to fight. */
	private boolean handedBack;
	private String waitingFor = "an opponent";
	private boolean actedThisTick;
	private boolean clicked;
	private boolean clickResolved;
	private float turnYawThisTick;
	private float turnPitchThisTick;

	private UUID opponentId;
	private Player opponent;
	private String opponentName = "?";
	private double groundY;
	private int lastOwnHurt;
	/** Ticks since a hit taken on the ground lifted it, -1 when not lifted (the rules leave W to the network in its fall). */
	private int liftedFor = -1;
	private boolean groundBefore = true;
	private int lastOpponentHurt;
	private int ticksSinceConnect = 1000;
	private int frozenTicks;
	private int deadTicks;
	private boolean deathCounted;
	private boolean killCounted;
	/** The opponent is watching as a spectator (it died on the user's server) and that kill is counted. */
	private boolean oppSpectatorCounted;
	/** In the air now on a jump that started at it from close, not yet hit by it (SchoolSession.JUMP_IN_GUARD). */
	private boolean jumpInAir;
	/** How the opponent fights, counted for training; see {@link StyleWatch}. */
	private final StyleWatch style = new StyleWatch();
	/** A round against the current opponent is being counted. */
	private boolean styleOpen;
	/** The round, tick by tick, for afterwards; see {@link RoundRecorder}. */
	private final RoundRecorder rounds = new RoundRecorder();
	private boolean debugRequested;
	/** Whether the installed model's fight-time rules press keys over the network: not with the file {@link SchoolSession#NO_RULES_FILE} in the school folder (run 30). */
	private final boolean rulesOn;
	/** The arm of the live A/B pool ({@code swordai/school/ab/pool.txt}, see {@link AbPool}) that plays this duel; null with no pool. */
	private final AbPool.Arm arm;
	private String lastError;
	private String connection = "";

	private long ticks;
	private long visibleTicks;
	private long onTargetTicks;
	/** How far the crosshair was off the opponent's body while it was in view (nothing while on it), summed over those ticks: sideways, and up or down. */
	private double offSideways;
	private double offUpDown;
	private long clicks;
	private long clicksOnTarget;
	/** Its attacks as this game made them out when they were made (see {@link OwnSwing}): by kind. */
	private int ownSwingsSeen = OwnSwing.count();
	private long ownEarly;
	private long ownCrit;
	private long ownSprint;
	private long ownSweep;
	private long ownPlain;
	private long hitsDealt;
	private long hitsTaken;
	private int kills;
	private int deaths;

	/**
	 * @param opponentName the player to fight, or null for whoever is nearest
	 * @throws IOException when there is no trained network to play with
	 */
	FightSession(Minecraft mc, String opponentName) throws IOException {
		this.mc = mc;
		this.wantedName = opponentName;
		this.loaded = LoadedFighter.load();
		this.fighter = loaded.fighter;
		this.arm = drawArm();
		this.rulesOn = SchoolSession.RULE_OVERLAY && (arm != null ? arm.rules
				: !Files.exists(SwordFiles.schoolDir().resolve(SchoolSession.NO_RULES_FILE)));
		if (!mc.hasSingleplayerServer()) {
			// A tick in hand on someone's server, so that a swing is never an early one as the server counts it.
			fighter.swingNoEarlierThan(loaded.cfg.swingMinChargeOnline, loaded.cfg.swingExtraTicksOnline);
			fighter.rulesNoEarlierThan(loaded.cfg.ruleSwingChargeOnline);
		}
		Path network = arm != null ? arm.network : SwordFiles.schoolCheckpoint();
		if (Files.exists(network)) {
			SchoolLearner l = new SchoolLearner(System.nanoTime(), 1, 0.99f, 0.95f, 1);
			try {
				l.load(network);
				// The checks' test-time sharpening (SchoolSession.PLAY_TEMPERATURE_FILE), where the school folder has one; an arm of
				// the A/B pool names its own (or none).
				Path temps = arm != null ? arm.temperatures : SwordFiles.schoolDir().resolve(SchoolSession.PLAY_TEMPERATURE_FILE);
				if (temps != null && Files.exists(temps)) {
					l.playTemperatures(SchoolSession.readHeadTemperatures(temps));
				}
			} catch (IOException | RuntimeException e) {
				l.close();
				loaded.close();
				throw e;
			}
			schoolLearner = l;
			school = new SchoolFighter(l, loaded.cfg.aimResponse,
					mc.hasSingleplayerServer() ? loaded.cfg.swingMinCharge : loaded.cfg.swingMinChargeOnline, loaded.cfg.playTemperature);
			school.whiffGuard(loaded.cfg.whiffGuard);
			if (rulesOn) {
				school.rulesFrom(fighter);
				rulesBody.patchedUp = loaded.patchedUp;
				// Hit and out over the network: the arm of the A/B pool says, else the file in the school folder or the config.
				fighter.hitAndOut(arm != null ? arm.hitAndOut
						: loaded.cfg.hitAndOutRule || Files.exists(SwordFiles.schoolDir().resolve(SchoolSession.HIT_AND_OUT_FILE)));
				if (arm != null && arm.noBack) {
					// The back-out rule (S for 12 ticks after the second hit of a combo it is taking) off for this arm.
					fighter.backOutRule(false);
				}
			}
		} else {
			schoolLearner = null;
			school = null;
		}
		this.groundY = mc.player.getY();
		this.body.patchedUp = school == null && loaded.patchedUp;
		SwordFiles.clearLastError();
		grabber.setCrop(ViewGeometry.cropFor(ViewGeometry.fov(), loaded.cfg.fov));
		OptionsGuard.apply(mc, loaded.cfg.fov);
		KeyedSkin.setTarget(null);
		// Nobody is recoloured until the opponent is known; then only the opponent is.
		grabber.setEnabled(true);
	}

	String skillLine() {
		return school != null ? "Playing with the technique school's network (" + schoolLearner.totalSteps + " steps of training)"
				+ (arm != null ? " [A/B arm " + arm.tag + "]" : "")
				+ (rulesOn && school != null && fighter.hitAndOut() ? " [hit and out]" : "")
				+ (arm != null && arm.noBack ? " [no back-out]" : "")
				+ (rulesOn ? ", the installed model's fight-time rules over it." : "; no rule presses anything for it.")
				: loaded.skillLine();
	}

	/** One arm of the live A/B pool for this duel, or null when the school folder has none (or it cannot be read). */
	private static AbPool.Arm drawArm() {
		try {
			return AbPool.draw(AbPool.read(SwordFiles.schoolDir()), new java.util.Random());
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- AgentSession ---------------------------------------------------------------------------

	@Override
	public boolean stopped() {
		return stopped;
	}

	@Override
	public boolean inControl() {
		return !stopped && !handedBack;
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
		if (stopped) {
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
		mc.options.pauseOnLostFocus = false;
		OptionsGuard.hold(mc);

		if (me.isDeadOrDying()) {
			handBack("the respawn");
			if (!deathCounted) {
				deathCounted = true;
				deaths++;
				closeStyle(true);
				closeRound("lost");
			}
			if (loaded.cfg.fightAutoRespawn && ++deadTicks == RESPAWN_TICKS) {
				me.respawn();
			}
			return;
		}
		// The user's server does not kill: the one who dies watches the rest as a spectator (5 Nov), so a round lost that
		// way showed as never closed, and the stats then had rounds lost with more dealt than taken.
		// 9 Nov: counted once per spectating spell, whether or not a round file is open (deaths stayed 0 in the user's duels).
		if (me.isSpectator()) {
			if (!deathCounted) {
				deathCounted = true;
				deaths++;
				closeStyle(true);
				closeRound("lost");
			}
			handBack("the next round");
			return;
		}
		deadTicks = 0;
		deathCounted = false;
		boolean oppSpectating = opponent != null && spectating(opponentId);
		if (oppSpectating && !oppSpectatorCounted) {
			oppSpectatorCounted = true;
			kills++;
			closeStyle(false);
			closeRound("won");
		}
		if (!oppSpectating) {
			oppSpectatorCounted = false;
		}

		// Any menu hands the game back to the player; the chat does not.
		Screen screen = mc.screen;
		if (mc.isPaused() || (screen != null && !(screen instanceof ChatScreen))) {
			handBack("the menu to close");
			return;
		}

		Player opp = Opponents.find(mc, me, wantedName, opponentId);
		if (opp == null) {
			handBack(wantedName == null ? "an opponent" : wantedName);
			return;
		}
		if (opp != opponent) {
			// A new opponent, or the same one in a new body after respawning.
			closeStyle(false);
			closeRound("open");
			opponent = opp;
			if (!opp.getUUID().equals(opponentId)) {
				say("Fighting " + opp.getScoreboardName() + ".");
			}
			opponentId = opp.getUUID();
			opponentName = opp.getScoreboardName();
			KeyedSkin.setTarget(opponentId);
			KeyedSkin.setShowReal(loaded.cfg.showOpponentSkin);
			KeyedSkin.setActive(true);
			lastOpponentHurt = opp.hurtTime;
			resetBrain(true);
			observer.resetEpisode();
			body.resetOpponentSwing();
		}
		if (opp.isDeadOrDying()) {
			if (!killCounted) {
				killCounted = true;
				kills++;
				closeStyle(false);
				closeRound("won");
			}
			handBack(opponentName + " to respawn");
			return;
		}
		killCounted = false;

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
			// Taking over (again): start from rest, on the ground the character stands on.
			handedBack = false;
			resetBrain(false);
			observer.resetEpisode();
			body.resetOpponentSwing();
			groundY = me.getY();
			lastOwnHurt = me.hurtTime;
			lastOpponentHurt = opp.hurtTime;
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

		// ---- who hit whom, from what this client can see -------------------------------------
		if (ticksSinceConnect < 1000) {
			ticksSinceConnect++;
		}
		boolean takenNow = me.hurtTime > lastOwnHurt;
		// Lifted by their hit (taken on the ground), counted until it is down again (as SchoolSession's liftedFor).
		liftedFor = takenNow && groundBefore ? 0 : liftedFor < 0 ? -1 : liftedFor + 1;
		if (liftedFor > 3 && me.onGround() || liftedFor > SchoolSession.PUNISH_WINDOW) {
			liftedFor = -1;
		}
		groundBefore = me.onGround();
		if (takenNow) {
			if (school != null) {
				school.hitTaken();
			}
			fighter.hitTaken();
			hitsTaken++;
		}
		lastOwnHurt = me.hurtTime;
		boolean dealtNow = opp.hurtTime > lastOpponentHurt && ticksSinceConnect <= HIT_CREDIT_TICKS;
		if (dealtNow) {
			// Counted for the summary here, when the opponent is seen to flinch. The network was told of
			// the hit when the swing was made: see endTick.
			hitsDealt++;
			ticksSinceConnect = 1000;
		}
		lastOpponentHurt = opp.hurtTime;
		if (loaded.cfg.watchStyles) {
			if (!styleOpen) {
				style.start(opponentName);
				styleOpen = true;
			}
			style.tick(me, opp, dealtNow, takenNow);
		}

		// ---- numbers for the summary; none of this reaches the network -----------------------
		facts.clear();
		observer.fillGeometry(facts, me, opp);
		if (KeyedSkin.showReal() && blind.note(facts)) {
			SwordAiClient.skinBlindsTheAgent(grabber.grabCounts());
		}
		ticks++;
		if (facts.enemyVisible) {
			visibleTicks++;
			offSideways += facts.yawMissDeg;
			offUpDown += Math.sqrt(Math.max(0f, facts.missDeg * facts.missDeg - facts.yawMissDeg * facts.yawMissDeg));
		}
		if (facts.onBody) {
			onTargetTicks++;
		}

		// ---- decide and act ------------------------------------------------------------------
		if (me.onGround()) {
			groundY = me.getY();
		}
		observer.fillBody(body, me, opp, groundY);
		int[] action;
		float yawBefore = me.getYRot();
		float pitchBefore = me.getXRot();
		if (school != null) {
			reader.tick(me, opp, clicked, chargeAtClick, sprintAtClick, critAtClick);
			school.crosshairOnOpponent(onOpponent());
			if (rulesOn) {
				observer.fillBody(rulesBody, me, opp, groundY);
				fighter.crosshairOnOpponent(onOpponent());
				fighter.decide(observer.frame, rulesBody);
			}
			// The golden rule (K2): what is seen of them is a round trip behind the server by the time a hit gets there.
			dev.swordai.core.pvp.Body them = reader.duel.them;
			if (them.onGround || !SchoolSession.JUMP_IN_HOLD_THROUGH_HIT && takenNow) {
				jumpInAir = false;
			} else if (SchoolSession.jumpInTookOff(them) && SchoolSession.jumpInWatched(reader.duel)) {
				jumpInAir = true;
			}
			// Run 49: the jump's ticks from their ground flag (the user's 5 Nov numbers), not from the rise drawn a tick later.
			school.holdJumpInSwing(SchoolSession.JUMP_IN_HOLD_THROUGH_HIT && SchoolSession.JUMP_IN_GUARD && jumpInAir
					&& SchoolSession.jumpInTick(them) > SchoolSession.JUMP_IN_SWING_TICKS && !SchoolSession.critSwingNow(me));
			school.holdSwing(!SchoolSession.JUMP_IN_HOLD_THROUGH_HIT && SchoolSession.JUMP_IN_GUARD && jumpInAir
					&& SchoolSession.jumpInTick(them) > SchoolSession.JUMP_IN_SWING_TICKS
					&& !(SchoolSession.JUMP_IN_LET_CRIT && SchoolSession.critSwingNow(me)) || SchoolSession.K2_GUARD && me.isSprinting()
					&& dev.swordai.core.pvp.PvpPlayer.fallingByTheHit(them, ownPingTicks() + SchoolSession.K2_MARGIN));
			double reachNow = Math.sqrt(opp.getBoundingBox().distanceToSqr(me.getEyePosition()));
			school.rulesLetGo(SchoolSession.RULES_LET_GO_IN_LIFT && liftedFor >= 0 && !me.onGround() && reachNow <= 3.5
					&& me.getAttackStrengthScale(0f) > 0.5f
					|| SchoolSession.RULES_LET_GO_AT_CRIT && liftedFor >= 0 && !me.onGround() && me.getY() < me.yo
					&& reachNow <= SchoolSession.LET_GO_AT_CRIT_REACH && me.getAttackStrengthScale(0f) >= SchoolSession.LET_GO_AT_CRIT_CHARGE);
			school.inLift(liftedFor >= 0 && !me.onGround());
			action = school.decide(observer.frame, body, reader.memory());
			AgentController.apply(mc, action, school.yawRate(), school.pitchRate());
			chargeAtClick = me.getAttackStrengthScale(0f);
			sprintAtClick = me.isSprinting();
			critAtClick = !me.onGround() && me.getY() < me.yo && !sprintAtClick && chargeAtClick > 0.9f;
		} else {
			// What the crosshair is on, for the rule about answering a hit; see AnswerRule.
			fighter.crosshairOnOpponent(onOpponent());
			action = fighter.decide(observer.frame, body);
			AgentController.apply(mc, action, fighter.yawRate(), fighter.pitchRate());
		}
		turnYawThisTick = me.getYRot() - yawBefore;
		turnPitchThisTick = me.getXRot() - pitchBefore;
		actedThisTick = true;
		if (loaded.cfg.recordRounds) {
			if (!rounds.open()) {
				rounds.start(opponentName);
			}
			// With the school's network under the installed model's rules, the rules that pressed keys are recorded too (1 Nov: the timelines could not tell them from the network).
			rounds.tick(me, opp, body, action, school != null && !rulesOn ? 0 : fighter.rules(), dealtNow, takenNow);
		}
		clicked = action[ActionSpace.ATTACK] == 1;
		clickResolved = false;
		if (clicked) {
			clicks++;
		}
	}

	@Override
	public void endTick() {
		if (stopped || !clicked || clickResolved) {
			return;
		}
		clickResolved = true;
		int made = OwnSwing.count();
		String kind = "miss";
		if (made != ownSwingsSeen) {
			// The swing reached someone. What it was, by the game's own rules, as this game saw it.
			ownSwingsSeen = made;
			if (OwnSwing.charge() <= 0.9f) {
				ownEarly++;
				kind = "early";
			} else if (OwnSwing.crit()) {
				ownCrit++;
				kind = "crit";
			} else if (OwnSwing.sprinting()) {
				ownSprint++;
				kind = "sprint";
			} else if (OwnSwing.sweep()) {
				ownSweep++;
				kind = "sweep";
			} else {
				ownPlain++;
				kind = "plain";
			}
		}
		rounds.swing(kind);
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == opponent) {
			clicksOnTarget++;
			ticksSinceConnect = 0;
			// The network is told of its hit now, as in training, and not when the opponent is seen to
			// flinch a round trip to the server later: for those ticks it would take the swing for a miss
			// and swing again at once. See FightLearnSession.hitsDealtAsShown.
			if (school != null) {
				school.hitDealt();
			}
			fighter.hitDealt();
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || !inControl()) {
			return false;
		}
		actedThisTick = false;
		if (school != null) {
			AgentController.hold(mc, school.action());
			return clicked;
		}
		AgentController.hold(mc, fighter.action());
		if (clicked && fighter.answerForced() && !onOpponent()) {
			// The rule's swing is only made at an opponent that is under the crosshair now that the camera has turned.
			fighter.answerCancelled();
			clicked = false;
			clicks--;
		}
		return clicked;
	}

	/** The round against the opponent is over: what was counted of how it fights is saved. */
	private void closeStyle(boolean lost) {
		if (!styleOpen) {
			return;
		}
		styleOpen = false;
		style.finish(lost, opponentPing());
	}

	/** The round is over ({@code won}, {@code lost}, or {@code open}): it is written out, and a lost or won one is told in the chat. */
	private void closeRound(String result) {
		if (!rounds.open()) {
			return;
		}
		String line = rounds.finish(result, opponentPing());
		if (line != null) {
			SwordAi.LOGGER.info("round: {}", line);
			if (!"open".equals(result)) {
				say(line);
			}
		}
	}

	/** Its own round trip to the server, in ticks (0 in a world of its own). */
	private int ownPingTicks() {
		if (mc.getConnection() != null && mc.player != null && mc.getConnection().getPlayerInfo(mc.player.getUUID()) != null) {
			return (mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency() + 49) / 50;
		}
		return 0;
	}

	/** Whether that player is in spectator mode, by the server's player list (where the user's server puts one who dies). */
	private boolean spectating(java.util.UUID id) {
		if (id == null || mc.getConnection() == null || mc.getConnection().getPlayerInfo(id) == null) {
			return false;
		}
		return mc.getConnection().getPlayerInfo(id).getGameMode() == net.minecraft.world.level.GameType.SPECTATOR;
	}

	private int opponentPing() {
		if (opponentId != null && mc.getConnection() != null && mc.getConnection().getPlayerInfo(opponentId) != null) {
			return mc.getConnection().getPlayerInfo(opponentId).getLatency();
		}
		return 0;
	}

	/** The game's own pick: the opponent is under the crosshair and within reach. */
	private boolean onOpponent() {
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
		return "FIGHT vs " + opponentName + (handedBack ? ", waiting for " + waitingFor : ", fighting");
	}

	String lastError() {
		return lastError;
	}

	// ---- helpers --------------------------------------------------------------------------------

	/** Lets go of the keys and gives the mouse and keyboard back until the fight can go on. */
	private void handBack(String why) {
		waitingFor = why;
		clicked = false;
		if (!handedBack) {
			handedBack = true;
			AgentController.release(mc);
			fighter.restHand();
			if (school != null) {
				school.restHand();
			}
		}
	}

	/** A new opponent ({@code forget}: its memory goes too), or taking over again after a pause. */
	private void resetBrain(boolean forget) {
		fighter.reset();
		if (school != null) {
			school.reset();
			if (forget) {
				reader.reset();
			}
		}
	}

	// ---- stopping -------------------------------------------------------------------------------

	@Override
	public void stop(String reason) {
		if (stopped) {
			return;
		}
		stopped = true;
		closeStyle(false);
		closeRound("open");
		try {
			AgentController.release(mc);
		} finally {
			grabber.close();
			KeyedSkin.setActive(false);
			KeyedSkin.setTarget(null);
			OptionsGuard.restore(mc);
			loaded.close();
			if (schoolLearner != null) {
				schoolLearner.close();
			}
		}
		String summary = summary();
		SwordFiles.writeLastFight(summary);
		say("Stopped (" + reason + "). " + summary);
	}

	private void fail(String message, Throwable error) {
		lastError = message;
		SwordFiles.writeError(message, error);
		if (error != null) {
			SwordAi.LOGGER.error("SwordAI stopped: " + message, error);
		}
		say("Stopped because of a problem: " + message);
		say("Run /swordai report and paste the result.");
		stop("error");
	}

	// ---- text -----------------------------------------------------------------------------------

	private void say(String message) {
		SwordAi.LOGGER.info(message);
		if (mc.gui != null) {
			mc.gui.getChat().addMessage(Component.literal("[SwordAI] " + message));
		}
	}

	/** One line with everything worth knowing about the fight; also what the report shows afterwards. */
	private String summary() {
		long seconds = (System.currentTimeMillis() - startedMillis) / 1000;
		String now = Opponents.connectionLine(mc, opponentId);
		if (!now.isEmpty()) {
			// Kept from while the fight was on: after leaving the server there is nothing to ask.
			connection = now;
		}
		return String.format(Locale.ROOT,
				"vs %s, %s in control of %s: hits %d dealt / %d taken, kills %d, deaths %d, swings %d (%.0f%% with the opponent under the crosshair), "
						+ "on target %.0f%%, opponent in view %.0f%%, crosshair off it by %.1f deg sideways and %.1f deg up or down on average (0 while on it)%s; "
						+ "the swings that reached, as this game made them out when it made them: %d before the sword was back, %d critical, %d with the sprint behind them "
						+ "(the server gives those knockback only if it has you sprinting too), %d sweeps, %d plain; %s",
				opponentName, ReportWriter.duration(ticks / 20), ReportWriter.duration(seconds), hitsDealt, hitsTaken, kills, deaths,
				clicks, percent(clicksOnTarget, clicks), percent(onTargetTicks, ticks), percent(visibleTicks, ticks),
				offSideways / Math.max(1, visibleTicks), offUpDown / Math.max(1, visibleTicks),
				connection, ownEarly, ownCrit, ownSprint, ownSweep, ownPlain, loaded.skillLine());
	}

	private static float percent(long part, long whole) {
		return whole <= 0 ? 0f : 100f * part / whole;
	}

	@Override
	public List<String> statusLines() {
		List<String> out = new ArrayList<>();
		out.add(describe() + (handedBack ? " (you have the controls)." : "."));
		out.add(summary());
		return out;
	}
}
