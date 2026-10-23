package dev.swordai.client;

import dev.swordai.core.env.StepFacts;
import dev.swordai.core.report.UpdateRecord;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.vision.EyeCheck;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.PlayerPose;
import dev.swordai.core.vision.SyntheticEye;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.CombatLog;
import dev.swordai.server.Truth;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Training on the screen: the agent plays the user's own character and sees the frames the game
 * draws. One run, from {@code /swordai train} (or play / selftest) until it stops, driven once per
 * client tick. What is trained, and how, is {@link Trainer}'s; this class is the body.
 */
public final class TrainingSession extends Trainer {
	/**
	 * This many decisions in a row on the same picture means the game has stopped drawing. One hitch
	 * makes the game catch up with at most ten ticks at once, so a second is beyond any single stall.
	 */
	private static final int FROZEN_FRAME_TICKS = 20;
	/** New frames to see before trusting that drawing is back. */
	private static final int FRAMES_TO_RESUME = 3;
	/** The drawn picture is compared with the real one on every this many decisions. */
	private static final int EYE_CHECK_EVERY = 4;
	/** A frame older than this was not a normal one (a hitch); it is left out of the average. */
	private static final double MAX_FRAME_AGE_MILLIS = 150.0;
	/** Faster than real time: more than this share of decisions on a used frame means the game cannot draw fast enough. */
	private static final float TOO_MANY_STALE = 0.10f;
	/** Faster than real time: the server may use this much of a tick's time before it counts as not keeping up. */
	private static final float SERVER_BUDGET = 0.9f;
	/** Each time it cannot keep up, the speed comes down to this much of what it was. */
	private static final float SLOW_DOWN = 0.75f;

	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	/** The turn made at the start of the current tick, which the picture glides through until the next. */
	private float turnYawThisTick;
	private float turnPitchThisTick;

	// The real picture, measured: how old a frame is when it is decided on, and how well the picture
	// drawn for a fake player matches it. Neither changes what the agent sees or does.
	private final SyntheticEye checkEye = new SyntheticEye();
	private final MaskFrame drawnFrame = new MaskFrame();
	private final PlayerPose checkPose = new PlayerPose();
	private final EyeCheck eyeCheck = new EyeCheck();
	private double frameAgeSum;
	private long frameAgeCount;
	/** For {@link #timingLine}: the same since its last call, and the opponent's moves held back by then. */
	private double lineAgeSum;
	private long lineAgeCount;
	private long lineHeldBack;
	private long lastTickEndNanos;
	private int sinceEyeCheck;

	private Player currentBot;
	private boolean clickResolved;
	private boolean clickOnTarget;
	/** Set when an action was applied at the start of the current tick; see {@link #beforePlayerMoves}. */
	private boolean actedThisTick;
	private int frozenTicks;
	private long resumeAtSequence;
	private int frameHolds;

	/** Notices when the picture has lost the opponent; acted on while the frame is drawn twice. */
	private final BlindGuard blind = new BlindGuard();

	private SelfTest selfTest;
	/** The vision check runs before the first episode of every training run, and again if it was interrupted. */
	private boolean selfTestPending;
	/** The user's clicks of the attack key since the last tick, while they play and are recorded. */
	private int heldClicks;
	private String selfTestSummary;
	private boolean debugRequested;

	/**
	 * Loads config, checkpoint and curriculum state and asks the server to build the arena.
	 *
	 * @throws IOException when the saved state cannot be read
	 */
	public TrainingSession(Minecraft mc, Mode mode) throws IOException {
		this(mc, mode, null);
	}

	/** @param demo the user plays the fights and is recorded (see DemoPlan); null for the network */
	TrainingSession(Minecraft mc, Mode mode, DemoPlan demo) throws IOException {
		super(mc, mode, false, demo);
		// The vision check makes the network look about; the user's view stays theirs.
		this.selfTestPending = mode != Mode.PLAY && demo == null;
		int pictureFov = Math.min(cfg.visionFov, cfg.fov);
		float crop = ViewGeometry.cropFor(pictureFov, cfg.fov);
		grabber.setCrop(crop);
		boolean fast = mode == Mode.TRAIN && cfg.trainSpeed > 1f;
		OptionsGuard.apply(mc, cfg.fov, fast);
		if (fast) {
			// The picture is made from 270 rows of the frame however large the window is, so a window
			// that small draws several times as many frames and loses nothing.
			WindowGuard.shrink(mc, crop, cfg.speedWindowRows);
		}
		// The vision check is made on one picture per frame; the player's own picture comes after it.
		KeyedSkin.setTwoPass(!selfTestPending && cfg.showOpponentSkin);
		KeyedSkin.setActive(true);
		grabber.setEnabled(true);
		IntegratedServer server = mc.getSingleplayerServer();
		UUID me = mc.player.getUUID();
		server.execute(() -> ArenaServer.start(server, me, cfg));
	}

	// ---- queries used by commands, mixins and the report ----------------------------------------

	/** True while the agent, not the player, is the one playing. */
	@Override
	public boolean inControl() {
		return demo == null && phase != Phase.STOPPED && phase != Phase.PAUSED;
	}

	/** Also while the user plays and is recorded: the network's pictures are still taken. */
	@Override
	public boolean wantsPictures() {
		return phase != Phase.STOPPED && phase != Phase.PAUSED;
	}

	/** The user plays and is recorded: their swing waits for the moment the network's would be made; see {@link #beforePlayerMoves}. */
	@Override
	public boolean takesClicks() {
		return demo != null && phase == Phase.RUN;
	}

	@Override
	public void heldClick() {
		heldClicks++;
	}

	@Override
	public boolean personPlays() {
		return demo != null && phase != Phase.STOPPED;
	}

	/** How often this session had to wait because the game stopped drawing frames. */
	public int frameHolds() {
		return frameHolds;
	}

	@Override
	public String selfTestSummary() {
		return selfTestSummary;
	}

	@Override
	public boolean requestDebugDump() {
		debugRequested = true;
		return true;
	}

	@Override
	public String describe() {
		return super.describe() + (frameHolds == 0 ? "" : ", waited for frames " + frameHolds + "x");
	}

	@Override
	public void worldDrawn() {
		grabber.worldDrawn(mc);
	}

	// ---- the per-tick state machine -------------------------------------------------------------

	/** Start of every client tick. */
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
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
			stop("left the world");
			return;
		}
		Truth truth = ArenaServer.truth();
		if (truth.error() != null) {
			fail(truth.error(), null);
			return;
		}
		if (grabber.failure() != null) {
			fail(grabber.failure(), null);
			return;
		}
		// Whether this game has just learned of a hit on its player: as a fight on a server sees it (FightSession).
		int hurt = mc.player.hurtTime;
		hurtNow = hurt > lastHurt;
		lastHurt = hurt;
		// While a fight is on, the opponent reaches this screen as late as the fight's lag says.
		SeenLag.follow(truth.botUuid());
		SeenLag.tick(phase == Phase.RUN || phase == Phase.OPENER);

		// The pause menu (or any other menu) hands the game back to the player. The chat does not:
		// commands can be typed while the agent plays, and so can anything in another window.
		Screen screen = mc.screen;
		boolean interrupted = mc.isPaused() || (screen != null && !(screen instanceof ChatScreen));
		if (interrupted) {
			// A running update is left to finish.
			if (phase != Phase.UPDATING) {
				if (phase != Phase.PAUSED && phase != Phase.WAIT_ARENA) {
					pause();
				}
				return;
			}
		} else if (phase == Phase.PAUSED) {
			if (selfTestPending) {
				selfTest = new SelfTest();
				phase = Phase.SELFTEST;
			} else {
				beginEpisode(truth);
			}
			return;
		}

		// Switching to another window must never open the pause menu, also not after F3+P.
		mc.options.pauseOnLostFocus = false;
		OptionsGuard.hold(mc);

		switch (phase) {
			case WAIT_ARENA -> {
				if (truth.active() && truth.botReady()) {
					if (selfTestPending) {
						selfTest = new SelfTest();
						phase = Phase.SELFTEST;
					} else {
						beginEpisode(truth);
					}
				} else if (++waitTicks > ARENA_TIMEOUT_TICKS) {
					fail("the arena did not come up within 30 seconds", null);
				}
			}
			case SELFTEST -> {
				keepView();
				AgentController.release(mc);
				if (selfTest.tick(mc, truth, grabber, observer)) {
					finishSelfTest(truth);
				}
			}
			case WAIT_RESET -> {
				AgentController.release(mc);
				if (truth.episodeId() >= requestedEpisodeId) {
					phase = Phase.SETTLE;
					settleTicks = SETTLE_TICKS;
				} else if (++waitTicks > RESET_TIMEOUT_TICKS) {
					fail("the server did not start the next episode", null);
				}
			}
			case SETTLE -> {
				keepView();
				AgentController.release(mc);
				boolean landed = headStart() && openerLanded();
				if (--settleTicks <= 0) {
					if (headStart() && !landed) {
						waitTicks = 0;
						phase = Phase.OPENER;
					} else {
						startRun(truth);
					}
				}
			}
			case OPENER -> {
				// A combo drill: the agent stays held until the copy has landed the hits it was given.
				keepView();
				AgentController.release(mc);
				if (openerLanded() || ++waitTicks > OPENER_TIMEOUT_TICKS) {
					startRun(truth);
				}
			}
			case RUN -> {
				keepView();
				runTick(truth);
			}
			case UPDATING -> {
				AgentController.release(mc);
				if (pendingUpdate.isDone()) {
					finishUpdate();
					if (phase == Phase.UPDATING) {
						if (interrupted) {
							phase = Phase.PAUSED;
						} else {
							beginEpisode(truth);
						}
					}
				}
			}
			case WAIT_FRAMES -> {
				AgentController.release(mc);
				if (grabber.sequence() >= resumeAtSequence) {
					beginEpisode(truth);
				}
			}
			default -> {
				// PAUSED and STOPPED do nothing per tick.
			}
		}
	}

	/** End of every client tick: by now vanilla has processed this tick's click. */
	@Override
	public void endTick() {
		lastTickEndNanos = System.nanoTime();
		if (phase != Phase.RUN || !clicked || clickResolved) {
			return;
		}
		clickResolved = true;
		clickOnTarget = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == currentBot;
	}

	/**
	 * Middle of the client tick: vanilla has handled the keys (and may just have opened the chat,
	 * which lets go of all of them) and the player has not moved yet. Presses the agent's keys again
	 * and returns whether it swings on this tick.
	 */
	@Override
	public boolean beforePlayerMoves() {
		if (demo != null) {
			// The user plays: their keys as they hold them now, and the click they made since the last tick.
			boolean attack = heldClicks > 0;
			heldClicks = 0;
			if (phase != Phase.RUN) {
				return false;
			}
			Options o = mc.options;
			boolean swing = demoKeys(o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(),
					o.keySprint.isDown() || mc.player.isSprinting(), o.keyJump.isDown(), attack);
			clickResolved = false;
			clickOnTarget = false;
			return swing;
		}
		if (!actedThisTick || phase != Phase.RUN) {
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
		return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == currentBot;
	}

	/**
	 * The player's rotation jumps once a tick; the picture should not. While a frame is drawn part-way
	 * through a tick, the view is held back by the part of this tick's turn that is still to come, so
	 * on screen the camera sweeps at an even speed and arrives exactly as the next tick begins.
	 */
	@Override
	public float viewYawLag(float partialTick) {
		return -(1f - Math.max(0f, Math.min(1f, partialTick))) * turnYawThisTick;
	}

	@Override
	public float viewPitchLag(float partialTick) {
		return -(1f - Math.max(0f, Math.min(1f, partialTick))) * turnPitchThisTick;
	}

	/** The view stays first-person and the sword stays in hand whatever the player presses or scrolls. */
	private void keepView() {
		if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		mc.player.getInventory().setSelectedSlot(0);
	}

	private void runTick(Truth truth) {
		Player bot = truth.botUuid() == null ? null : mc.level.getPlayerByUUID(truth.botUuid());
		if (bot == null) {
			// The bot is not being tracked by this client (yet); nothing can be scored.
			AgentController.release(mc);
			if (++missingBotTicks > MISSING_BOT_TICKS) {
				missingBotTicks = 0;
				learner.interrupt();
				beginEpisode(truth);
			}
			return;
		}
		missingBotTicks = 0;
		currentBot = bot;
		if (!observer.capture(grabber)) {
			AgentController.release(mc);
			return;
		}
		decisions++;
		if (!observer.stale()) {
			frozenTicks = 0;
			measurePicture(bot);
		} else {
			staleDecisions++;
			if (++frozenTicks >= FROZEN_FRAME_TICKS) {
				waitForFrames();
				return;
			}
		}
		if (debugRequested) {
			debugRequested = false;
			try {
				say("Saved what the agent sees to " + DebugDump.dump(grabber, observer.frame));
			} catch (IOException | RuntimeException e) {
				say("Could not save the debug images: " + e);
			}
		}

		step(truth);
		if (KeyedSkin.twoPass() && blind.note(facts)) {
			SwordAiClient.skinBlindsTheAgent(grabber.grabCounts());
			if (phase == Phase.RUN) {
				// What it did blind is not a fight to learn from: this one is cut here and the next begins.
				suspendEpisode();
				beginEpisode(truth);
			}
		}
	}

	/**
	 * Notes how old the frame being decided on is, and now and then draws the same moment the way a
	 * fake player's picture is drawn and compares the two. The opponent is still exactly where that
	 * frame showed it as long as the frame belongs to the tick that has just ended.
	 */
	private void measurePicture(Player bot) {
		MaskFrame real = observer.frame;
		// In game time: at a higher speed a tick is shorter, and the same real delay is more of one.
		double ageMillis = (System.nanoTime() - real.requestedNanos) / 1_000_000.0 * speed;
		if (ageMillis >= 0.0 && ageMillis <= MAX_FRAME_AGE_MILLIS) {
			frameAgeSum += ageMillis;
			frameAgeCount++;
			lineAgeSum += ageMillis;
			lineAgeCount++;
		}
		if (real.requestedNanos <= lastTickEndNanos || ++sinceEyeCheck < EYE_CHECK_EVERY) {
			return;
		}
		sinceEyeCheck = 0;
		float p = real.partialTick;
		checkPose.x = Mth.lerp(p, bot.xOld, bot.getX());
		checkPose.y = Mth.lerp(p, bot.yOld, bot.getY());
		checkPose.z = Mth.lerp(p, bot.zOld, bot.getZ());
		checkPose.bodyYaw = Mth.rotLerp(p, bot.yBodyRotO, bot.yBodyRot);
		checkPose.headYaw = Mth.rotLerp(p, bot.yHeadRotO, bot.yHeadRot);
		checkPose.headPitch = bot.getXRot(p);
		checkPose.walkPos = bot.walkAnimation.position(p);
		checkPose.walkSpeed = bot.walkAnimation.speed(p);
		checkPose.attackTime = bot.getAttackAnim(p);
		checkPose.age = bot.tickCount + p;
		checkPose.crouching = bot.isCrouching();
		checkPose.holdsItem = !bot.getMainHandItem().isEmpty();
		checkPose.rightHanded = bot.getMainArm() == HumanoidArm.RIGHT;
		checkEye.look(real.viewX, real.viewY, real.viewZ, real.viewYaw, real.viewPitch, checkPose, drawnFrame);
		eyeCheck.add(real, drawnFrame);
	}

	/** How old a frame is when the agent decides on it, in milliseconds. */
	private float frameAgeMillis() {
		return frameAgeCount < 100 ? SwordFiles.readFrameAgeMillis() : (float) (frameAgeSum / frameAgeCount);
	}

	private void saveEye() {
		if (frameAgeCount >= 100) {
			SwordFiles.writeEye(frameAgeMillis(), eyeCheck.summary());
		}
	}

	private void pause() {
		suspendEpisode();
		phase = Phase.PAUSED;
	}

	/**
	 * The agent cannot play blind. If the game stops delivering new frames (which a hidden window
	 * should not cause, but a driver or the system might), the episode is cut and the session waits
	 * here until frames flow again, instead of learning from a frozen picture.
	 */
	private void waitForFrames() {
		suspendEpisode();
		frozenTicks = 0;
		resumeAtSequence = grabber.sequence() + FRAMES_TO_RESUME;
		phase = Phase.WAIT_FRAMES;
		if (frameHolds++ == 0) {
			say("The game stopped drawing new frames, so the agent is waiting. It carries on by itself when they come back.");
		}
	}

	private void finishSelfTest(Truth truth) {
		selfTestSummary = selfTest.summary();
		say("Vision self-test: " + selfTest.verdict());
		for (String line : selfTest.lines()) {
			say("  " + line);
		}
		boolean fatal = selfTest.fatal();
		selfTest = null;
		selfTestPending = false;
		if (!fatal && mode != Mode.SELFTEST) {
			KeyedSkin.setTwoPass(SwordFiles.loadConfig().showOpponentSkin);
		}
		if (fatal) {
			fail("vision self-test failed, so training was not started", null);
		} else if (mode == Mode.SELFTEST) {
			stop("self-test finished");
		} else {
			beginEpisode(truth);
		}
	}

	// ---- the body, as the trainer asks for it -------------------------------------------------------

	@Override
	void releaseKeys() {
		AgentController.release(mc);
	}

	@Override
	void prepareBody() {
		mc.player.getInventory().setSelectedSlot(0);
		frozenTicks = 0;
	}

	@Override
	void setAgentLag(int ticks, float stallChance, int stallTicks) {
		SeenLag.set(ticks, stallChance, stallTicks);
	}

	/**
	 * On the screen no fight against a copy is on equal terms. The user's character sees the copy as
	 * a player sees another, a tick or more late, and its clicks reach the server a tick late; the
	 * copy is a fake player on the server and has neither delay. After two hours of it the fighter
	 * won one such fight in five against a copy it then beat four times in five without the screen.
	 * Counted, those fights would never let it take the copy's place, and at one win in ten
	 * self-play would throw away what it had learned on the screen and start again from the copy.
	 */
	@Override
	boolean judgesCopyFights() {
		return false;
	}

	@Override
	int copyLagTicks() {
		return Math.max(0, cfg.screenCopyLagTicks);
	}

	@Override
	void resetSight() {
		observer.resetEpisode();
	}

	private int lastHurt;
	private boolean hurtNow;

	@Override
	boolean bodyHitNow() {
		return hurtNow;
	}

	@Override
	float bodyCharge() {
		return mc.player.getAttackStrengthScale(0f);
	}

	@Override
	boolean bodyOnGround() {
		return mc.player.onGround();
	}

	@Override
	float bodyPitch() {
		return mc.player.getXRot();
	}

	@Override
	float personYaw() {
		return mc.player.getYRot();
	}

	@Override
	boolean serverSprinting(Truth truth) {
		return truth.agentSprinting();
	}

	@Override
	void fillGeometry(StepFacts facts) {
		observer.fillGeometry(facts, mc.player, currentBot);
	}

	@Override
	void fillObs(Obs obs, Truth truth, int[] lastActionOrNull) {
		observer.fillObs(obs, mc, mc.player, currentBot, truth, facts, lastActionOrNull, stage.headMask, episodeStageIndex, clock, motor, idea);
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
	}

	@Override
	float frameAgeTicks() {
		return frameAgeMillis() / 50f;
	}

	@Override
	float captureMillis() {
		return grabber.takeMeanLatencyMillis();
	}

	@Override
	float wantedSpeed() {
		return cfg.trainSpeed;
	}

	/** The client's own ticks and its frame read-back have to keep pace with the server's. */
	@Override
	void onSpeed(float speed) {
		SwordAiClient.setTickMillis(speed > 1f ? 50f / speed : 0f);
		grabber.setSpeed(speed);
	}

	@Override
	void afterUpdate() {
		saveEye();
		slowDownIfBehind();
	}

	/**
	 * Faster than real time only works while every decision still gets a fresh frame and the server
	 * still fits a tick into its shorter time. If either fails, the fights the agent learns from are
	 * no longer the game's, so the speed comes down a step, by itself.
	 */
	private void slowDownIfBehind() {
		UpdateRecord last = lastRecord;
		if (speed <= 1f || last == null) {
			return;
		}
		IntegratedServer server = mc.getSingleplayerServer();
		float tickMillis = 50f / speed;
		float serverMillis = server == null ? 0f : server.getAverageTickTimeNanos() / 1_000_000f;
		boolean drawingBehind = last.staleFrames > TOO_MANY_STALE;
		boolean serverBehind = serverMillis > tickMillis * SERVER_BUDGET;
		if (!drawingBehind && !serverBehind) {
			return;
		}
		float slower = Math.max(1f, speed * SLOW_DOWN);
		if (slower < 1.1f) {
			slower = 1f;
		}
		say(String.format(Locale.ROOT, "x%.1f is more than this computer keeps up with (%s), so the speed is now x%.1f.", speed,
				drawingBehind ? String.format(Locale.ROOT, "%.0f%% of decisions were made on a frame already used", last.staleFrames * 100f)
						: String.format(Locale.ROOT, "a server tick takes %.1f ms of the %.1f ms it has", serverMillis, tickMillis),
				slower));
		applySpeed(slower);
	}

	@Override
	String pictureLine() {
		String eyes = eyeCheck.summary();
		return eyes == null ? null
				: String.format(Locale.ROOT, "picture: frames are %.0f ms old when decided on; drawn vs real, %s", frameAgeMillis(), eyes);
	}

	// ---- stopping -------------------------------------------------------------------------------

	/** Stops cleanly: saves progress, gives the world and the options back. Safe to call more than once. */
	@Override
	public void stop(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		Phase was = phase;
		phase = Phase.STOPPED;
		try {
			AgentController.release(mc);
			saveProgress(was);
		} finally {
			saveEye();
			closeLearning();
			grabber.close();
			KeyedSkin.setActive(false);
			SeenLag.off();
			SwordAiClient.setTickMillis(0f);
			WindowGuard.restore(mc);
			OptionsGuard.restore(mc);
			releaseArena();
		}
		say("Stopped (" + reason + ").");
		if (demo != null) {
			finishDemo();
			SwordAiClient.demoEnded(demo, demoFights());
		}
	}

	@Override
	String timingLine() {
		long held = SeenLag.heldBack();
		String line = String.format(java.util.Locale.ROOT, "frame age %.1f ms of game time over %d decisions, opponent's moves held back %d",
				lineAgeCount == 0 ? Double.NaN : lineAgeSum / lineAgeCount, lineAgeCount, held - lineHeldBack);
		lineAgeSum = 0;
		lineAgeCount = 0;
		lineHeldBack = held;
		return line;
	}
}
