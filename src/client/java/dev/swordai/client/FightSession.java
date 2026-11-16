package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.report.ReportWriter;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.CritBackRead;
import dev.swordai.core.rl.Fighter;
import dev.swordai.core.school.AbPool;
import dev.swordai.core.school.ComboSpacing;
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
	/** Run 101: the opponent read for crit spammers (CritBackRead); in the real game it is on as in training (CritBackRead.REAL_GAME). */
	private final CritBackRead critBack = new CritBackRead();
	/** Run 102: the late-jump guard in the real game, as in training (Fighter.lateJumpGuard); false = the old behaviour. */
	static final boolean LATE_JUMP_GUARD = true;
	/** Run 108 (SchoolFighter#ownJumpResetGate): the network's own T9a/T9b/T9c jumps are let go of in the user's duels. */
	static final boolean OWN_JUMP_RESET_GATE = true;
	/** Run 109: the 23 Oct model (no school network playing) with the no-swing-at-a-faller guard (K2, jump-in from ground tick 2). */
	static final boolean OLD_NET_FALLER_GUARD = true;
	/** Run 109: swings the guard took back from the 23 Oct model. */
	private long oldNetFallerHeld;
	/**
	 * Run 122 (SchoolFighter#OWN_HIT_WINDOW; the user, 16 Nov 22:05: "always get a sprint hit stun and then jump for no reason"): after its
	 * own hit, no hit taken since, up to 20 ticks, on the ground, within 3.5 blocks and its sword under 0.9, no take-off of the network's
	 * own; a jump a rule pressed (the jump reset r on their hit, J) stays. The school's network (SchoolFighter) and the 23 Oct model's
	 * network when no school network is installed (the else branch, its rules' jump kept the same way). false = as before.
	 */
	static final boolean OWN_HIT_JUMP_GUARD = true;
	/** Run 122: the 23 Oct model's own-hit jumps let go of and tried (the school's are counted in SchoolFighter). */
	private long oldNetOwnHitStripped, oldNetOwnHitTried;
	/** Run 122: ticks since its own hit (told when the swing is made, as the networks are) and since a hit taken, for the old model's guard. */
	private int sinceOwnHit = 1000, sinceTakenHit = 1000;
	/**
	 * Run 123 (ComboSpacing; the user, 16 Nov 22:40: "hold combo over 10 hits easily"): in its own combo (from its hit until theirs), a rule
	 * over the network's keys and the other rules' on W/S, the sprint, the jump (never) and the swing: S back to the edge of reach while its
	 * sword comes back, W again so that the hit is a sprint hit, the swing at the edge (2.6-3.0) the tick the sword is at the rules' line,
	 * the game's pick on them, not on one falling (K2). With the rules on (an arm without them gets none), through run 124's outside-rule
	 * slot (SchoolFighter#outsideRule; over CounterSpacing's on the ticks both would play); the 23 Oct model too. The round file's rules show
	 * it as 'H'. false = as before.
	 */
	static final boolean COMBO_SPACING = ComboSpacing.ON;
	private final ComboSpacing spacing = new ComboSpacing();
	/** Run 123: the swing's charge line for the spacing rule (the rules' own: Config.ruleSwingCharge, online ruleSwingChargeOnline). */
	private final float spacingReady;
	/** Run 123: the spacing rule held the keys this tick; its keys for the outside-rule slot. */
	private boolean spacingNow;
	private final int[] spacingKeys = new int[ActionSpace.HEADS];
	/**
	 * Run 124 (dev.swordai.core.school.CounterSpacing): in their combo and as they close in, with the rules on over the school's network, a
	 * rule of its own ('C' in the round files): its sword ready, the sprint in and the hit at the edge as they enter reach; not ready, S taps
	 * just outside their reach and a strafe; no long S, no jump; it stands aside for the jump reset, the punish crit, K2 and jump-ins.
	 * false = as before.
	 */
	static final boolean COUNTER_SPACING = true;
	private final dev.swordai.core.school.CounterSpacing counter = new dev.swordai.core.school.CounterSpacing();
	private final dev.swordai.core.school.CounterSpacing.Input counterIn = new dev.swordai.core.school.CounterSpacing.Input();
	private final int[] counterKeys = new int[ActionSpace.HEADS];
	/** Run 124: the least charge for its swing here (the rules' swing charge on this server or world). */
	private final float counterReady;
	/** Run 124: its ticks pressed, by decision (CounterSpacing.WHY_NAMES). */
	private final long[] counterBy = new long[dev.swordai.core.school.CounterSpacing.WHY_NAMES.length];
	/** Run 124: its keys were pressed on this tick (the round file's 'C'). */
	private boolean counterRule;
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
	/** Its health on the tick before (-1 before the first); see LOST_ROUND_HP. */
	private float lastHealth = -1f;
	/** Health at or under this, then full on the next tick: the server ended a round it lost (it never kills). */
	private static final float LOST_ROUND_HP = 4f;
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
		// Run 124: CounterSpacing swings no earlier than a rule's swing (Fighter's ruleMinCharge): 1.0 on a server.
		this.counterReady = mc.hasSingleplayerServer() ? Math.max(loaded.cfg.swingMinCharge, loaded.cfg.ruleSwingCharge)
				: Math.max(loaded.cfg.swingMinChargeOnline, loaded.cfg.ruleSwingChargeOnline);
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
			// 13 Nov 21:10: an "airyield" arm plays what the benchmark's "airyield" played: the combo swing off at opponents not
			// falling (run 88c, comboProvenOff below) and run 88's air yield only while SchoolSession.AIR_KEEP_YIELD is on (off since
			// 12:25; the benchmark gates it the same way). Before, an airyield arm also turned run 88's yield on.
			school.airKeepYield(arm != null && arm.airYield && SchoolSession.AIR_KEEP_YIELD);
			// Run 108: no jump reset of the network's own in the user's duels (under the user's 95%); the rules' reactive one stays.
			school.ownJumpResetGate(OWN_JUMP_RESET_GATE);
			// Run 122: no take-off of the network's own right after its own hit while close with its sword not back.
			school.ownHitJumpGuard(OWN_HIT_JUMP_GUARD);
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
		// Run 123: the spacing rule's swing waits for the rules' line (Fighter#ruleMinCharge's parts).
		this.spacingReady = mc.hasSingleplayerServer() ? Math.max(loaded.cfg.swingMinCharge, loaded.cfg.ruleSwingCharge)
				: Math.max(loaded.cfg.swingMinChargeOnline, loaded.cfg.ruleSwingChargeOnline);
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
				+ (arm != null && arm.airYield ? " [air yield]" : "")
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
		// 9 Nov 21:50, the user's duels: the server never kills the loser and it is not a spectator either. Its health stays
		// just above 0 (shown as 0) for about 1.7 s and then comes back full: every lost round stayed open, deaths 0. A health
		// that jumps from LOST_ROUND_HP or less to full (natural healing never does) is a lost round; a won round has been
		// closed by then (the opponent's death or spectator spell), so it does not count twice.
		float hpNow = me.getHealth();
		if (lastHealth >= 0f && lastHealth <= LOST_ROUND_HP && hpNow >= me.getMaxHealth() - 0.5f) {
			deaths++;
			closeStyle(true);
			closeRound("lost");
		}
		lastHealth = hpNow;

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
		// Run 122: the old model's guard clocks (SchoolFighter keeps its own for the school's network).
		sinceOwnHit = Math.min(sinceOwnHit + 1, 1000);
		sinceTakenHit = takenNow ? 0 : Math.min(sinceTakenHit + 1, 1000);
		if (takenNow) {
			if (school != null) {
				school.hitTaken();
			}
			fighter.hitTaken();
			hitsTaken++;
			// Run 123: their hit ends its combo (the spacing rule lets go).
			spacing.hitTaken();
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
				// Runs 88b/88c: an "airyield" arm's rules swing in their combo (not at a falling opponent with 88c).
				critBack.tick(reader.events.iHit == dev.swordai.core.pvp.Events.CRIT && liftedFor >= 0, reader.events.theyHit == dev.swordai.core.pvp.Events.CRIT);
				fighter.critBackRead(CritBackRead.REAL_GAME && critBack.on() && liftedFor >= 0 && !me.onGround());
				// Run 102: no late jump of its own after a ground hit (the user, 15 Nov: no jump reset unless 95%+ certain).
				fighter.lateJumpGuard(LATE_JUMP_GUARD);
				fighter.comboProvenOff(arm != null && arm.airYield && SchoolSession.comboProvenOffNow(reader.duel.them));
				// Run 92: the rules' run at a rising jumper (m) not into a jump-in crit coming nor a ready sword (real rounds: hit first 10%).
				fighter.meetNotIncoming(SchoolSession.MEET_NOT_INCOMING);
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
			boolean jumpInHold = SchoolSession.JUMP_IN_HOLD_THROUGH_HIT && SchoolSession.JUMP_IN_GUARD && jumpInAir
					&& SchoolSession.jumpInTick(them) > SchoolSession.JUMP_IN_SWING_TICKS && !SchoolSession.critSwingNow(me);
			boolean swingHold = !SchoolSession.JUMP_IN_HOLD_THROUGH_HIT && SchoolSession.JUMP_IN_GUARD && jumpInAir
					&& SchoolSession.jumpInTick(them) > SchoolSession.JUMP_IN_SWING_TICKS
					&& !(SchoolSession.JUMP_IN_LET_CRIT && SchoolSession.critSwingNow(me)) || SchoolSession.K2_GUARD && me.isSprinting()
					&& dev.swordai.core.pvp.PvpPlayer.fallingByTheHit(them, ownPingTicks() + SchoolSession.K2_MARGIN);
			school.holdJumpInSwing(jumpInHold);
			school.holdSwing(swingHold);
			double reachNow = Math.sqrt(opp.getBoundingBox().distanceToSqr(me.getEyePosition()));
			school.rulesLetGo(SchoolSession.RULES_LET_GO_IN_LIFT && liftedFor >= 0 && !me.onGround() && reachNow <= 3.5
					&& me.getAttackStrengthScale(0f) > 0.5f
					|| SchoolSession.RULES_LET_GO_AT_CRIT && liftedFor >= 0 && !me.onGround() && me.getY() < me.yo
					&& reachNow <= SchoolSession.LET_GO_AT_CRIT_REACH && me.getAttackStrengthScale(0f) >= SchoolSession.LET_GO_AT_CRIT_CHARGE);
			school.inLift(liftedFor >= 0 && !me.onGround());
			// Run 122: eye to their hitbox, for the own-hit jump guard (SchoolFighter#ownHitJumpGuard).
			school.reachNow(reachNow);
			// Run 124: CounterSpacing's keys as a rule over the network (with the rules on), standing aside where they come first.
			dev.swordai.core.school.CounterSpacing.Plan counterPlan = null;
			if (COUNTER_SPACING && rulesOn && dev.swordai.core.school.CounterSpacing.ON) {
				boolean k2Now = SchoolSession.K2_GUARD && dev.swordai.core.pvp.PvpPlayer.fallingByTheHit(them, ownPingTicks() + SchoolSession.K2_MARGIN);
				counterIn.duel = reader.duel;
				counterIn.charge = me.getAttackStrengthScale(0f);
				counterIn.readyCharge = counterReady;
				counterIn.pick = onOpponent();
				counterIn.sinceTaken = sinceTakenHit;
				counterIn.sinceDealt = sinceOwnHit;
				counterIn.jumpIn = jumpInAir;
				counterIn.themFalling = k2Now;
				counterIn.swingHeld = jumpInAir || k2Now;
				counterIn.yield = (fighter.rules() & dev.swordai.core.school.CounterSpacing.RULES_FIRST) != 0
						|| dev.swordai.core.school.CounterSpacing.keeps(schoolLearner.told());
				counterPlan = counter.plan(counterIn);
				school.outsideRule(counterPlan.heads, counterPlan.keys(counterKeys));
				counterBy[counterPlan.why] += counterPlan.active() ? 1 : 0;
			}
			// Run 123: in its own combo the spacing rule's W/S, sprint and swing through the same slot, over CounterSpacing's (with the rules
			// on); the guards as SchoolFighter applies them. Its "no jump" after the decision (the slot never presses the jump).
			if (spacingTick(rulesOn, swingHold && spacing.sinceHit() > SchoolFighter.K2_COMBO_TICKS || jumpInHold)) {
				spacing.press(spacingKeys);
				school.outsideRule(ComboSpacing.HEADS & ~ActionSpace.mask(ActionSpace.JUMP), spacingKeys);
			}
			action = school.decide(observer.frame, body, reader.memory());
			spacingNoJump(action);
			counter.pressed(action[ActionSpace.FORWARD], action[ActionSpace.STRAFE]);
			counterRule = counterPlan != null && counterPlan.active() && !spacingNow;
			AgentController.apply(mc, action, school.yawRate(), school.pitchRate());
			chargeAtClick = me.getAttackStrengthScale(0f);
			sprintAtClick = me.isSprinting();
			critAtClick = !me.onGround() && me.getY() < me.yo && !sprintAtClick && chargeAtClick > 0.9f;
		} else {
			// What the crosshair is on, for the rule about answering a hit; see AnswerRule.
			fighter.crosshairOnOpponent(onOpponent());
			action = fighter.decide(observer.frame, body);
			if (OLD_NET_FALLER_GUARD) {
				// Run 109 (the user, 15 Nov 22:15, after the newest network lost every round to them: "why not just make it counter falling
				// player no sprint hit and keep all the ability it has before"): the 23 Oct model as it was, but no knockback swing at one
				// jumping in at it after their ground tick 1 (the user's 5 Nov rule) or falling by the time a sprint hit lands (K2), as the
				// school's network has it above. Its own crit (falling, not sprinting) is let through. Everything else is the old network's.
				reader.tick(me, opp, clicked, chargeAtClick, sprintAtClick, critAtClick);
				dev.swordai.core.pvp.Body them = reader.duel.them;
				if (them.onGround || !SchoolSession.JUMP_IN_HOLD_THROUGH_HIT && takenNow) {
					jumpInAir = false;
				} else if (SchoolSession.jumpInTookOff(them) && SchoolSession.jumpInWatched(reader.duel)) {
					jumpInAir = true;
				}
				boolean crit = SchoolSession.critSwingNow(me);
				boolean hold = SchoolSession.JUMP_IN_GUARD && jumpInAir && SchoolSession.jumpInTick(them) > SchoolSession.JUMP_IN_SWING_TICKS && !crit
						|| SchoolSession.K2_GUARD && me.isSprinting() && dev.swordai.core.pvp.PvpPlayer.fallingByTheHit(them, ownPingTicks() + SchoolSession.K2_MARGIN);
				if (hold && action[ActionSpace.ATTACK] == 1) {
					action[ActionSpace.ATTACK] = 0;
					oldNetFallerHeld++;
				}
			}
			if (OWN_HIT_JUMP_GUARD && action[ActionSpace.JUMP] == 1 && (fighter.ruledHeads() & ActionSpace.mask(ActionSpace.JUMP)) == 0
					&& me.onGround() && SchoolFighter.ownHitWindow(sinceOwnHit, sinceTakenHit)) {
				// Run 122: the old model's own take-off right after its own hit, close with its sword not back (its rules' jump stays).
				oldNetOwnHitTried++;
				double reach = Math.sqrt(opp.getBoundingBox().distanceToSqr(me.getEyePosition()));
				if (SchoolFighter.ownHitJumpGuard(sinceOwnHit, sinceTakenHit, true, reach, me.getAttackStrengthScale(0f))) {
					action[ActionSpace.JUMP] = 0;
					oldNetOwnHitStripped++;
				}
			}
			// Run 123: in its own combo the spacing rule's keys over the 23 Oct model's (its view of them read above with the faller guard).
			applySpacing(action, OLD_NET_FALLER_GUARD, false);
			AgentController.apply(mc, action, fighter.yawRate(), fighter.pitchRate());
			chargeAtClick = me.getAttackStrengthScale(0f);
			sprintAtClick = me.isSprinting();
			critAtClick = !me.onGround() && me.getY() < me.yo && !sprintAtClick && chargeAtClick > 0.9f;
		}
		turnYawThisTick = me.getYRot() - yawBefore;
		turnPitchThisTick = me.getXRot() - pitchBefore;
		actedThisTick = true;
		if (loaded.cfg.recordRounds) {
			if (!rounds.open()) {
				rounds.start(opponentName, arm != null ? arm.tag : null);
			}
			// With the school's network under the installed model's rules, the rules that pressed keys are recorded too (1 Nov: the timelines could not tell them from the network).
			rounds.tick(me, opp, body, action, (school != null && !rulesOn ? 0 : fighter.rules())
					| (school != null && counterRule ? dev.swordai.core.school.CounterSpacing.RULE_BIT : 0) | (spacingNow ? ComboSpacing.RULE_BIT : 0),
					dealtNow, takenNow);
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
			// Run 123: its hit, told when the swing is made, as the networks are: its combo goes on (the spacing rule holds the keys).
			spacing.hitDealt();
			sinceOwnHit = 0;
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

	/**
	 * Run 123 (COMBO_SPACING): the spacing controller's tick, before the keys are chosen (its swing only with the game's pick on them).
	 *
	 * @param on        the rule may play (the rules over the network are on)
	 * @param swingHeld the session's guards hold the swing this tick
	 * @return whether it holds its own combo's keys this tick
	 */
	private boolean spacingTick(boolean on, boolean swingHeld) {
		spacingNow = COMBO_SPACING && on && spacing.tick(reader.duel, spacingReady, onOpponent(), swingHeld,
				ownPingTicks() + SchoolSession.K2_MARGIN, ownPingTicks());
		return spacingNow;
	}

	/** Run 123: on a tick it holds, no jump at all (the network's or a rule's), and the rules' fighter told the keys really pressed. */
	private void spacingNoJump(int[] action) {
		if (spacingNow) {
			action[ActionSpace.JUMP] = 0;
			int[] ruleKeys = fighter.action();
			if (ruleKeys != action) {
				System.arraycopy(action, 0, ruleKeys, 0, ActionSpace.HEADS);
			}
		}
	}

	/** Run 123, the 23 Oct model (no outside-rule slot there): the spacing rule's W/S, sprint, jump (none) and swing over {@code action}. */
	private void applySpacing(int[] action, boolean on, boolean swingHeld) {
		if (spacingTick(on, swingHeld)) {
			spacing.press(action);
			spacingNoJump(action);
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
		critBack.reset();
		spacing.reset();
		sinceOwnHit = 1000;
		sinceTakenHit = 1000;
		counter.reset();
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
				connection, ownEarly, ownCrit, ownSprint, ownSweep, ownPlain, loaded.skillLine())
				// Run 122: the network's own take-offs in the window after its own hit, and those the guard let go of.
				+ String.format(Locale.ROOT, "; run 122 (%s): its own take-offs right after its own hit (no hit taken since, %d ticks) %d, let go of"
						+ " (within %.1f, sword under %.1f) %d", OWN_HIT_JUMP_GUARD ? "on" : "off", SchoolFighter.OWN_HIT_WINDOW,
						school != null ? school.ownHitJumpsTried : oldNetOwnHitTried, SchoolFighter.OWN_HIT_GUARD_REACH,
						SchoolFighter.OWN_HIT_GUARD_CHARGE, school != null ? school.ownHitJumpsStripped : oldNetOwnHitStripped)
				+ counterLine()
				// Run 123: the spacing rule's ticks in its own combo by what it did, and the combos it held.
				+ String.format(Locale.ROOT, "; run 123 combo spacing (%s, swing at %.2f): %s", COMBO_SPACING ? "on" : "off", spacingReady, spacing.counts());
	}

	/** Run 124: CounterSpacing's ticks pressed as a rule, by decision (the slot's count includes run 123's ticks). */
	private String counterLine() {
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "; run 124 counter spacing (%s): ticks pressed (the slot, run 123's too) %d",
				COUNTER_SPACING && rulesOn ? "on" : "off", school != null ? school.outsideTicks : 0L));
		for (int w = dev.swordai.core.school.CounterSpacing.RUN_IN; w < counterBy.length; w++) {
			b.append(String.format(Locale.ROOT, ", %s %d", dev.swordai.core.school.CounterSpacing.WHY_NAMES[w], counterBy[w]));
		}
		return b.toString();
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
