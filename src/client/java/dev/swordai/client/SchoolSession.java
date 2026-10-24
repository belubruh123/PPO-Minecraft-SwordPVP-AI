package dev.swordai.client;

import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.pvp.Habits;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.MixIdea;
import dev.swordai.core.rl.SwingGate;
import dev.swordai.core.school.Performance;
import dev.swordai.core.school.SchoolLearner;
import dev.swordai.core.school.SchoolPpo;
import dev.swordai.core.school.SchoolRollout;
import dev.swordai.core.school.SchoolStages;
import dev.swordai.core.school.Teacher;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.CombatLog;
import dev.swordai.server.EpisodePlan;
import dev.swordai.server.Truth;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The technique school on the screen ({@code /swordai school}): the network plays the user's own
 * character, first person, against the technique bots ({@code TechniqueBot}), and learns by PPO from
 * the damage traded, while the technique teachers and the installed network pull on its choices with a
 * pull that fades ({@link SchoolStages}, {@link Teacher}). Nothing presses a key for it but the game's
 * own limit on swinging before the sword is back.
 *
 * <p>{@code /swordai school check} plays the same fights with this network and with the model installed
 * in the user's game, as that one plays a real fight (its rules and all), pair by pair from the same
 * seeds, and learns nothing: the paired check that decides an install.
 */
public final class SchoolSession implements AgentSession {
	enum Mode {
		TRAIN, CHECK
	}

	private enum Phase {
		WAIT_ARENA, SELFTEST, WAIT_RESET, SETTLE, RUN, UPDATING, PAUSED, WAIT_FRAMES, STOPPED
	}

	private static final int ARENA_TIMEOUT_TICKS = 600;
	private static final int RESET_TIMEOUT_TICKS = 200;
	private static final int SETTLE_TICKS = 4;
	private static final int FROZEN_FRAME_TICKS = 20;
	private static final int FRAMES_TO_RESUME = 3;
	private static final long STALL_TICKS = 20;
	private static final int MISSING_BOT_TICKS = 60;
	/** A fight lasts until a knockout or this long (30 seconds), then it is cut and valued by the critic. */
	static final int FIGHT_TICKS = 600;
	static final int ROLLOUT = 4096;
	/** The reward: damage traded, a tenth a point of health, and a knockout. */
	static final float PER_HEALTH = 0.1f;
	static final float KNOCKOUT = 1f;
	/**
	 * A real punish crit (B1): a crit the game confirms, landed within this many ticks of being hit (as {@code Habits}
	 * counts it). Its damage alone paid it about a third of a point more than a plain hit, too little to outweigh letting
	 * go of W while falling (the user, 24 Oct: "the reward you give isnt big"). It pays half a knockout on top.
	 */
	static final int PUNISH_WINDOW = 18;
	static final float PUNISH_CRIT = 0.5f;
	/**
	 * Since 24 Oct 15:45 the punish crit is paid only as the game counts it (the user's table, "punish crit 0 of 89"):
	 * thrown up from the ground by their hit, the crit before it lands. Any crit within the window was paid before, 4 or 5
	 * an update against the game's 0 or 1. A crit that is not a punish crit pays this.
	 */
	static final float OTHER_CRIT = 0.2f;
	/**
	 * The technique reward (the user, 24 Oct: "it is quite easy for me to code a PPO bot to do these techniques": its
	 * reward is the technique). In a fight where the network is told the teacher's technique, every tick of a moment
	 * pays for the share of the taught keys pressed as the teacher would, and a moment done pays once more; both times
	 * the technique's pull share now (0 once mastered, up to 3 while stuck), so the reward goes where it is not learned.
	 * An imitation reward beside the task's, as DeepMimic learns its many skills with PPO (Peng et al. 2018).
	 */
	static final float IMITATE = 0.02f;
	static final float DONE_BONUS = 0.2f;
	/**
	 * Since 24 Oct 16:35 a moment is paid for what it did to the fight, not for the keys alone (the user: "let it learn to turn
	 * that into damage the way a player would"): the done bonus only where it paid (the damage traded over it and the ticks
	 * after not a loss), and this on top where it won damage. In the update before, the technique reward came to about +131
	 * and the damage reward about -26: copying the keys paid five times what the trade did. The per-tick imitation was
	 * halved with it and put back at 17:30: the deflection's (T4) W/S and sprint slipped, 88% to 66% in the hour.
	 */
	static final float WON_BONUS = 0.3f;
	static final float SHARE_MOST = 3f;
	/**
	 * The golden rule (K2) broken: a knockback hit landed on a falling player, who crits back (the user's first wish). A
	 * whole knockout's worth since 24 Oct 15:40: at 0.5, 4 to 14 an update when jumpers (the uppercutter) came in.
	 */
	static final float K2_BROKEN = 1.0f;
	/**
	 * The basics teacher (the user, 24 Oct: "make a brand new teacher specializing on teaching this"): outside the technique
	 * moments the swing is its, as soon as the sword is back and only with the crosshair on them (the game's own pick, so in
	 * reach): no swing at nothing ("MANY free hit", the user, 18 Oct). In the 13:36 check the school network landed about
	 * half its swings, the installed model three in four. A swing at nothing also costs this much (0.1 until the 16:31
	 * check, where 37% of its swings were at nothing against the installed model's 9%; 0.3 until 19:00, when with the whiff
	 * guard it swung a fifth less than the installed model).
	 */
	static final float AIR_SWING = 0.15f;
	/**
	 * The crit teacher (the user, 24 Oct 15:20, the game's count of its own moves: "punish crit 0 of 89 ... ZERO percent
	 * here, fix it"). A crit is "a hit made while falling (not sprinting, not on the ground)"; of the update before, 339
	 * ticks of crit moments had 9 falling in reach with the sword back, 12 swings and no crit. On every tick a crit can be
	 * made now (falling, in reach, sword back, not sprinting, the crosshair on them) the swing is taught, technique moment
	 * or not, and a swing made there pays this much on top of its damage.
	 */
	static final float CRIT_SWING = 0.3f;
	/** Updates at the start of a new school that teach the critic only (the actor is the installed one's, the critic new). */
	static final int CRITIC_WARMUP = 3;
	/** The bot is put between these distances, in view. */
	static final float NEAREST = 3f;
	static final float FARTHEST = 8f;
	/** Steps whose policy loss counts for nothing: the opponent farther than this and out of sight. */
	static final float SKIP_FARTHER = 8f;
	/**
	 * Of the installed model's choice as a target, the share on its key: all of it. Its choices are drawn, so over many steps the target
	 * is its whole policy; a share spread over the other keys would teach a jump on every tenth tick (24 Oct: 176 jumps a minute).
	 */
	static final float SHADOW_SURE = 1f;
	private static final int KEEP_EVERY_MINUTES = 60;
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

	private final Minecraft mc;
	private final Mode mode;
	private final Config cfg;
	private Phase phase = Phase.WAIT_ARENA;
	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	private final BlindGuard blind = new BlindGuard();
	private SelfTest selfTest;
	private boolean selfTestPending = true;
	private final Random rng = new Random();

	private final SchoolLearner learner;
	private final SchoolStages stages = new SchoolStages();
	private final Teacher teacher = new Teacher();
	private final DuelReader reader = new DuelReader();
	/**
	 * Whether it does each technique when its moment comes; in training the stages are told of every moment. In a
	 * paired check one for each side (A the school's network, B the installed model), told to nobody.
	 */
	private final Performance perf;
	private final Performance perfB = new Performance(null);
	/** What the game shows it did, by the same detector the bots read it with, per side (0 A or training, 1 B): chances seen and taken. */
	private final int[][] ownSeen = new int[2][Habits.COUNT];
	private final int[][] ownDid = new int[2][Habits.COUNT];
	private int barTicks;
	private final ExecutorService updater;
	private Future<SchoolPpo.Stats> pending;
	private int warmupLeft;
	/**
	 * The installed model as it plays the user's fights, its rules and all. In a paired check it plays side B; in
	 * training it runs alongside the network on every tick without pressing anything, and what it would press is
	 * the installed teacher's target (plan part 6: each old rule becomes a teacher, its pull fading).
	 */
	private LoadedFighter installed;
	private final BodyState installedBody = new BodyState();

	// ---- the fight --------------------------------------------------------------------------------
	private final StepFacts facts = new StepFacts();
	/**
	 * The ticks where the teacher wants a crit, per side (0 the network or the teacher, 1 the installed model), step by step
	 * of what a crit needs: falling, in reach, the sword charged, not sprinting as the server has it; then the swings
	 * made (and of them the ones the game would make crits), the hits and the crits landed. Whether the crits are real,
	 * and if not, which of their needs is missing (24 Oct: the teacher's own punish crits came 4 in 363).
	 */
	private final long[][] critTrace = new long[2][9];
	/**
	 * Per side, the answer to jumping in (the priority): their take-offs toward it, its hits landed on them rising (T8), its
	 * knockback hits landed on them falling (what K2 forbids), and the crits it took within ten ticks of one of those.
	 */
	private final long[][] jumpIn = new long[2][5];
	private int sinceFallingHit = 1000;
	/** Real punish crits rewarded since the last update, and crits landed outside that window. */
	private int punishCritsPaid;
	private int otherCrits;
	/** Ticks a crit could be made on (see {@link #CRIT_SWING}), the swings made on them, and whether the last decision was one. */
	private int critChances;
	private int critSwings;
	/** Ticks of a lift the sprint let go was taught on (see the punish crit teacher). */
	private int sprintLetGo;
	/** Ticks of a lift's fall the aim onto them was taught on. */
	private int liftAim;
	/** Ticks it was taught to turn toward an opponent off the screen or well off the crosshair. */
	private int findAim;
	/** Per technique: swings its teacher asked for that a rule kept back, by the sword gate (0) and the whiff guard (1). */
	private final int[][] blockedSwings = new int[Technique.COUNT][2];
	/** Technique moments that won damage since the last update (see WON_BONUS). */
	private int momentsWon;
	private boolean critChanceAtClick;
	/** Ticks since their hit threw it up from the ground, -1 once it has landed (see OTHER_CRIT); on the ground the tick before. */
	private int liftedFor = -1;
	private int ticksSinceCrit = 1000;
	private boolean onGroundBefore = true;
	/**
	 * Each lift (thrown up from the ground by their hit) by the kind of that hit (Events: sprint, crit, plain): how many, how
	 * far toward a punish crit each got (came down in reach, with the sword back, not sprinting, the crosshair on them, a swing
	 * there, a crit), and how many the teacher took as a punish crit (B1). The user, 24 Oct 15:50: "punish crit didn't
	 * improve"; the game counted 0 or 1 of about 80 an update, the installed model 1-2% in the checks.
	 */
	private final int[][] lifts = new int[4][8];
	private int liftKind = -1;
	private int liftStage;
	private boolean liftAimedNow;
	private boolean liftTaught;
	/** The technique reward owed to the next step, and what it paid since the last update; the pull shares it uses. */
	private float shaping;
	private float shapingPaid;
	private int momentsPaid;
	private int k2Broken;
	private float[] pullsNow;
	/** Per side (0 the network, 1 the installed model in a check): swings, swings with the crosshair on them, hits landed. */
	private final long[][] swingCount = new long[2][3];
	/** The technique of the moment on the last tick, so a moment's start is told to the stages with the bot it came against. */
	private int lastMomentTechnique = -1;
	/** In a check, the school network's combo jump resets mistimed now and then, as they will be in a real fight. */
	private final dev.swordai.core.school.FairJump fairJump = new dev.swordai.core.school.FairJump(0x5EEDL);
	private int critWantedFor;
	private static final boolean[] CRIT_WANTED = new boolean[Technique.COUNT];
	/**
	 * The overlay: the installed model teaches the fighting between the techniques (and the camera), the file's teachers
	 * the technique moments. 24 Oct, in the game: the file's own ordinary fight, keys pressed exactly, lost 22 a fight to
	 * the installed model, and the student does not get past its teacher.
	 */
	static final boolean OVERLAY = true;

	static {
		for (Technique t : new Technique[] {Technique.B1, Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2,
				Technique.C6, Technique.K3}) {
			CRIT_WANTED[t.ordinal()] = true;
		}
	}
	private final SessionClock clock = new SessionClock();
	private final AimMotor motor;
	private final MixIdea idea = new MixIdea();
	private final SchoolRollout.Step step = new SchoolRollout.Step();
	private final int[] action = new int[ActionSpace.HEADS];
	private final int[] lastAction = new int[ActionSpace.HEADS];
	private final float[] oldProbs = new float[ActionSpace.TOTAL_LOGITS];
	private boolean hasLastAction;
	private boolean clicked;
	private boolean clickResolved;
	private boolean clickOnTarget;
	private float chargeAtClick;
	private boolean sprintAtClick;
	private boolean critAtClick;
	private boolean actedThisTick;
	private float turnYawThisTick;
	private float turnPitchThisTick;
	private Player currentBot;
	private int lastHurt;
	private boolean hurtNow;
	private int waitTicks;
	private int settleTicks;
	private int requestedEpisodeId;
	private long lastRunServerTick = -1;
	private int missingBotTicks;
	private int frozenTicks;
	private long resumeAtSequence;
	private float speed = 1f;
	private boolean speedApplied;

	private int fightBot;
	private Style fightStyle;
	private int fightTicks;
	private float fightDealt;
	private float fightTaken;
	private int fightTeacherTicks;
	/** Paired check: fights so far, and whether this one is the installed model's. */
	private long checkFights;
	private boolean installedPlays;
	private long checkSeed;

	// ---- what is counted between updates ----------------------------------------------------------
	private final float[] botDealt = new float[Specialists.KINDS];
	private final float[] botTaken = new float[Specialists.KINDS];
	private final int[] botTicks = new int[Specialists.KINDS];
	private final int[] botFights = new int[Specialists.KINDS];
	private final int[] botWins = new int[Specialists.KINDS];
	private final int[] botLosses = new int[Specialists.KINDS];
	/** Per technique: ticks its teacher spoke, and ticks the network's likeliest keys were the teacher's on all of them. */
	private final int[] spoke = new int[Technique.COUNT];
	private final int[] agreed = new int[Technique.COUNT];
	/** Per technique: how often the bots went into it. */
	private final int[] botUsed = new int[Technique.COUNT];
	private int lastBotTechnique = -1;
	private long stepsAtLastUpdate;
	private long millisAtLastUpdate = System.currentTimeMillis();
	private long lastKeptMillis = System.currentTimeMillis();
	private String lastUpdateLine = "no update yet";
	private int stalledCuts;

	/**
	 * In the check, side A is the teacher itself instead of the school's network: its keys pressed as they are, the
	 * installed model's camera. Whether the teacher wins in the game at all, as the toy fight cannot say (24 Oct).
	 */
	private final boolean teacherPlaysA;
	/** And outside the technique moments the installed model plays, its keys as well: the file's techniques laid over it. */
	private final boolean overlay;
	/** The teacher's techniques in its checks, when not every family reached so far. */
	private Technique[] checkTaught;

	SchoolSession(Minecraft mc, Mode mode) throws IOException {
		this(mc, mode, false, false);
	}

	SchoolSession(Minecraft mc, Mode mode, boolean teacherPlaysA, boolean overlay) throws IOException {
		this.mc = mc;
		this.mode = mode;
		this.teacherPlaysA = teacherPlaysA && mode == Mode.CHECK;
		this.overlay = overlay && this.teacherPlaysA;
		// For the teacher's checks: SWORDAI_CHECK_FAMILIES (say "0" or "0,1") picks the families it uses, to see what each costs.
		String families = this.teacherPlaysA ? System.getenv("SWORDAI_CHECK_FAMILIES") : null;
		if (families != null && !families.isBlank()) {
			java.util.List<Technique> t = new java.util.ArrayList<>();
			for (String f : families.split(",")) {
				java.util.Collections.addAll(t, SchoolStages.STAGES[Integer.parseInt(f.trim())].techniques);
			}
			checkTaught = t.toArray(new Technique[0]);
		}
		this.cfg = SwordFiles.loadConfig();
		this.motor = new AimMotor(cfg.aimResponse);
		this.perf = new Performance(mode == Mode.TRAIN ? stages::moment : null);
		Files.createDirectories(SwordFiles.schoolDir());
		learner = new SchoolLearner(System.nanoTime(), ROLLOUT + FIGHT_TICKS, 0.99f, 0.95f, cfg.resolvedTrainerThreads());
		Path teacherFile = installedCheckpoint();
		if (Files.exists(SwordFiles.schoolCheckpoint())) {
			learner.load(SwordFiles.schoolCheckpoint());
			if (Files.exists(teacherFile)) {
				learner.loadOldTeacher(teacherFile, false);
			}
		} else {
			if (!Files.exists(teacherFile)) {
				learner.close();
				throw new IOException("no installed model to start the school from: put it in " + SwordFiles.installedCopy());
			}
			// The school starts from the installed network, widened, which plays as it did (plan part 7).
			learner.loadOldTeacher(teacherFile, true);
			warmupLeft = CRITIC_WARMUP;
		}
		if (Files.exists(SwordFiles.schoolStages())) {
			stages.load(SwordFiles.schoolStages());
		}
		Config icfg = SwordFiles.readConfig(SwordFiles.installedCopy().resolve("config.json"));
		if (icfg != null && Files.exists(teacherFile)) {
			installed = LoadedFighter.loadFrom(teacherFile, icfg, SwordFiles.installedCopy().resolve("curriculum.properties"));
			installedBody.patchedUp = installed.patchedUp;
			// The swing waits for the sword as the installed model's does here (a full hit, a tick before the very end of the
			// wait; online has its own longer wait): both sides of the check alike, and the school as it would play once
			// installed. The dev config's 1.0 had the school swing a tick later than the model it is checked against (24 Oct).
			cfg.swingMinCharge = icfg.swingMinCharge;
		} else if (mode == Mode.CHECK) {
			learner.close();
			throw new IOException("no installed model with its settings in " + SwordFiles.installedCopy());
		}
		updater = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "swordai-school-update");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		int pictureFov = Math.min(cfg.visionFov, cfg.fov);
		ViewGeometry.setFov(pictureFov);
		float crop = ViewGeometry.cropFor(pictureFov, cfg.fov);
		grabber.setCrop(crop);
		boolean fast = cfg.trainSpeed > 1f;
		OptionsGuard.apply(mc, cfg.fov, fast);
		if (fast) {
			WindowGuard.shrink(mc, crop, cfg.speedWindowRows);
		}
		KeyedSkin.setTwoPass(false);
		KeyedSkin.setActive(true);
		grabber.setEnabled(true);
		IntegratedServer server = mc.getSingleplayerServer();
		UUID me = mc.player.getUUID();
		server.execute(() -> ArenaServer.start(server, me, cfg));
	}

	/** The installed network: the copy kept for the school. */
	private static Path installedCheckpoint() {
		return SwordFiles.installedCopy().resolve("checkpoint.bin");
	}

	String startLine() {
		return String.format(Locale.ROOT, "%s the technique school at %s (%d steps so far)%s.", overlay ? "Checking the teacher over the installed model against"
				: teacherPlaysA ? "Checking the teacher against"
				: mode == Mode.CHECK ? "Checking" : "Training",
				stages.current().name, stages.schoolSteps, warmupLeft > 0 ? ", starting from the installed network" : "");
	}

	// ---- AgentSession ---------------------------------------------------------------------------

	@Override
	public boolean stopped() {
		return phase == Phase.STOPPED;
	}

	@Override
	public boolean inControl() {
		return phase != Phase.STOPPED && phase != Phase.PAUSED;
	}

	@Override
	public void worldDrawn() {
		grabber.worldDrawn(mc);
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
		return false;
	}

	@Override
	public String describe() {
		return "technique school (" + mode.name().toLowerCase(Locale.ROOT) + "), " + phase.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		lines.add(describe() + String.format(Locale.ROOT, ", x%.1f", speed));
		lines.add(stages.describe());
		lines.add("last update: " + lastUpdateLine);
		return lines;
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
		int hurt = mc.player.hurtTime;
		hurtNow = hurt > lastHurt;
		lastHurt = hurt;
		SeenLag.follow(truth.botUuid());
		SeenLag.tick(phase == Phase.RUN);

		Screen screen = mc.screen;
		boolean interrupted = mc.isPaused() || (screen != null && !(screen instanceof ChatScreen));
		if (interrupted) {
			if (phase != Phase.UPDATING) {
				if (phase != Phase.PAUSED && phase != Phase.WAIT_ARENA) {
					suspendFight();
					phase = Phase.PAUSED;
				}
				return;
			}
		} else if (phase == Phase.PAUSED) {
			if (selfTestPending) {
				selfTest = new SelfTest();
				phase = Phase.SELFTEST;
			} else {
				beginFight(truth);
			}
			return;
		}
		mc.options.pauseOnLostFocus = false;
		OptionsGuard.hold(mc);
		if (phase != Phase.RUN) {
			lastRunServerTick = -1;
		}
		switch (phase) {
			case WAIT_ARENA -> {
				if (truth.active() && truth.botReady()) {
					selfTest = new SelfTest();
					phase = Phase.SELFTEST;
				} else if (++waitTicks > ARENA_TIMEOUT_TICKS) {
					fail("the arena did not come up within 30 seconds", null);
				}
			}
			case SELFTEST -> {
				keepView();
				AgentController.release(mc);
				if (selfTest.tick(mc, truth, grabber, observer)) {
					say("Vision self-test: " + selfTest.verdict());
					boolean fatal = selfTest.fatal();
					selfTest = null;
					selfTestPending = false;
					if (fatal) {
						fail("vision self-test failed, so the school was not started", null);
					} else {
						KeyedSkin.setTwoPass(cfg.showOpponentSkin);
						beginFight(truth);
					}
				}
			}
			case WAIT_RESET -> {
				AgentController.release(mc);
				if (truth.episodeId() >= requestedEpisodeId) {
					phase = Phase.SETTLE;
					settleTicks = SETTLE_TICKS;
				} else if (++waitTicks > RESET_TIMEOUT_TICKS) {
					fail("the server did not start the next fight", null);
				}
			}
			case SETTLE -> {
				keepView();
				AgentController.release(mc);
				if (--settleTicks <= 0) {
					// Nothing that happened while it was held is scored.
					CombatLog.clear();
					phase = Phase.RUN;
				}
			}
			case RUN -> {
				keepView();
				runTick(truth);
			}
			case UPDATING -> {
				AgentController.release(mc);
				if (pending.isDone()) {
					finishUpdate();
					if (phase == Phase.UPDATING) {
						if (interrupted) {
							phase = Phase.PAUSED;
						} else {
							beginFight(truth);
						}
					}
				}
			}
			case WAIT_FRAMES -> {
				AgentController.release(mc);
				if (grabber.sequence() >= resumeAtSequence) {
					beginFight(truth);
				}
			}
			default -> {
			}
		}
	}

	@Override
	public void endTick() {
		if (phase != Phase.RUN || !clicked || clickResolved) {
			return;
		}
		clickResolved = true;
		clickOnTarget = onOpponent();
		if (clickOnTarget && installed != null) {
			installed.fighter.hitDealt();
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || phase != Phase.RUN) {
			return false;
		}
		actedThisTick = false;
		if (installedPlays) {
			AgentController.hold(mc, installed.fighter.action());
			if (clicked && installed.fighter.answerForced() && !onOpponent()) {
				// As in a real fight: the rule's swing is only made at an opponent under the crosshair now.
				installed.fighter.answerCancelled();
				clicked = false;
			}
			return clicked;
		}
		AgentController.hold(mc, action);
		return clicked;
	}

	private boolean onOpponent() {
		return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == currentBot;
	}

	private void keepView() {
		if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		mc.player.getInventory().setSelectedSlot(0);
	}

	// ---- fights ---------------------------------------------------------------------------------

	private void beginFight(Truth truth) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			stop("left the world");
			return;
		}
		if (!speedApplied) {
			speedApplied = true;
			applySpeed(cfg.trainSpeed);
		}
		long seed;
		if (mode == Mode.CHECK) {
			long fight = checkFights++;
			long pair = fight / 2;
			installedPlays = (fight & 1) == 1;
			seed = mix(0x5C400L + pair);
			checkSeed = seed;
			Random draw = new Random(seed);
			fightBot = checkBot(pair);
			fightStyle = fightBot == Specialists.MIXER ? (pair % checkCycle() == checkCycle() - 1 ? Specialists.heldOutMixer(draw) : Specialists.mixer(draw))
					: Specialists.draw(fightBot, draw);
			learner.seedSampling(seed * 0x2545F4914F6CDD1DL);
		} else {
			installedPlays = false;
			fightBot = stages.drawBot(rng);
			fightStyle = fightBot == Specialists.MIXER ? Specialists.mixer(rng) : Specialists.draw(fightBot, rng);
			seed = rng.nextLong();
		}
		Random lagDraw = new Random(seed ^ 0x1A6L);
		// What it sees of them comes 1 to 4 ticks late, as on a real server (the user's fights, 24 Oct: 55-82 ms each way); 0 to 3
		// and mostly 0 or 1 before, so a jump-in crit seen late was sprint-hit and a combo answered late.
		int agentLag = 1 + lagDraw.nextInt(4);
		EpisodePlan plan = EpisodePlan.school(fightStyle, NEAREST, FARTHEST);
		plan.seed = seed;
		plan.seedYaw = mode == Mode.CHECK;
		plan.agentLag = agentLag;
		SeenLag.set(agentLag, 0f, 0);
		requestedEpisodeId = truth.episodeId() + 1;
		server.execute(() -> ArenaServer.reset(plan));
		AgentController.release(mc);
		mc.player.getInventory().setSelectedSlot(0);
		// One teacher against every bot: every technique of the families reached so far.
		teacher.ordinaryFight = !OVERLAY;
		teacher.start(checkTaught != null ? checkTaught : stages.taught(), cfg.swingMinCharge, seed ^ 0x7EAC4L);
		learner.resetIntent();
		shaping = 0f;
		lastMomentTechnique = -1;
		fairJump.reset();
		liftedFor = -1;
		onGroundBefore = true;
		liftKind = -1;
		liftStage = 0;
		liftTaught = false;
		reader.reset();
		perf.reset();
		perfB.reset();
		observer.resetEpisode();
		clock.resetEpisode();
		clock.botAttacks = true;
		motor.reset();
		idea.clear();
		if (installed != null) {
			installed.fighter.reset();
		}
		hasLastAction = false;
		clicked = false;
		frozenTicks = 0;
		fightTicks = 0;
		fightDealt = 0f;
		fightTaken = 0f;
		fightTeacherTicks = 0;
		lastBotTechnique = -1;
		waitTicks = 0;
		phase = Phase.WAIT_RESET;
	}

	/** Paired check: the bots it goes through, pair by pair: this stage's, then the real player twice (the second held out). */
	private int checkCycle() {
		return stages.current().bots.length + SchoolStages.PRIORITY_BOTS.length + 2;
	}

	/** The stage's bots, the bots that jump in (the priority, in every check), and the real player twice. */
	private int checkBot(long pair) {
		int[] bots = stages.current().bots;
		int i = (int) (pair % checkCycle());
		if (i < bots.length) {
			return bots[i];
		}
		i -= bots.length;
		return i < SchoolStages.PRIORITY_BOTS.length ? SchoolStages.PRIORITY_BOTS[i] : Specialists.MIXER;
	}

	private static long mix(long n) {
		long z = n * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private void runTick(Truth truth) {
		long now = truth.tick();
		long gap = lastRunServerTick < 0 ? 1 : now - lastRunServerTick;
		lastRunServerTick = now;
		if (gap > STALL_TICKS) {
			// The game stood still while the server went on: nothing of this fight says how it fights.
			stalledCuts++;
			lastRunServerTick = -1;
			if (mode == Mode.CHECK) {
				checkFights--;
			}
			suspendFight();
			beginFight(truth);
			return;
		}
		Player bot = truth.botUuid() == null ? null : mc.level.getPlayerByUUID(truth.botUuid());
		if (bot == null) {
			AgentController.release(mc);
			if (++missingBotTicks > MISSING_BOT_TICKS) {
				missingBotTicks = 0;
				suspendFight();
				beginFight(truth);
			}
			return;
		}
		missingBotTicks = 0;
		currentBot = bot;
		if (!observer.capture(grabber)) {
			AgentController.release(mc);
			return;
		}
		if (observer.stale()) {
			if (++frozenTicks >= FROZEN_FRAME_TICKS) {
				suspendFight();
				frozenTicks = 0;
				resumeAtSequence = grabber.sequence() + FRAMES_TO_RESUME;
				phase = Phase.WAIT_FRAMES;
				return;
			}
		} else {
			frozenTicks = 0;
		}
		step(truth, bot);
		if (KeyedSkin.twoPass() && blind.note(facts)) {
			SwordAiClient.skinBlindsTheAgent(grabber.grabCounts());
		}
	}

	/** One decision: scores the last step, then chooses and carries out the next. */
	private void step(Truth truth, Player bot) {
		LocalPlayer me = mc.player;
		facts.clear();
		observer.fillGeometry(facts, me, bot);
		facts.onGround = me.onGround();
		clock.ticksSinceHitDealt++;
		clock.ticksSinceHitTaken++;
		CombatLog.drain(facts);
		boolean shownDealt = hasLastAction && clicked && clickOnTarget;
		if (hasLastAction) {
			long[] sc = swingCount[installedPlays ? 1 : 0];
			sc[0] += clicked ? 1 : 0;
			sc[1] += clicked && clickOnTarget ? 1 : 0;
			sc[2] += facts.hitsDealt;
		}
		if (shownDealt) {
			clock.ticksSinceHitDealt = 0;
		}
		if (facts.hitsTaken > 0) {
			clock.ticksSinceHitTaken = 0;
		}
		// Thrown up by their hit from the ground, until it lands again (Habits' punish crit chance).
		liftedFor = facts.hitsTaken > 0 && onGroundBefore ? 0 : liftedFor < 0 ? -1 : liftedFor + 1;
		if (liftedFor > 3 && me.onGround() || liftedFor > PUNISH_WINDOW) {
			liftedFor = -1;
		}
		onGroundBefore = me.onGround();
		clock.takenStreak.tick(shownDealt ? 1 : 0, facts.hitsTaken);
		if (installed != null && hurtNow) {
			installed.fighter.hitTaken();
		}
		// The server's word on its crits (see DuelReader.tick): a crit it confirmed this tick or the two before.
		ticksSinceCrit = facts.hitsDealt > 0 && facts.critDealt ? 0 : Math.min(ticksSinceCrit + 1, 1000);
		reader.tick(me, bot, hasLastAction && clicked, chargeAtClick, sprintAtClick, critAtClick, ticksSinceCrit <= 2);
		if (liftedFor == 0) {
			endLift();
			liftKind = reader.events.theyHit;
		} else if (liftedFor < 0) {
			endLift();
		}
		int botTechnique = truth.botTechnique();
		if (botTechnique >= 0 && botTechnique != lastBotTechnique && botTechnique != Technique.BASE.ordinal()) {
			botUsed[botTechnique]++;
		}
		lastBotTechnique = botTechnique;

		// The new observation: the picture, its body, the memory of the fight; and for the critic the truth.
		clock.episodeFraction = Math.min(1f, fightTicks / (float) FIGHT_TICKS);
		observer.fillObs(step.obs.base, mc, me, bot, truth, facts, hasLastAction ? lastAction : null, ActionSpace.ALL_HEADS, 9, clock, motor, idea);
		System.arraycopy(reader.memory(), 0, step.obs.mem, 0, step.obs.mem.length);
		step.obs.setOpponent(botTechnique, fightStyle.p, fightBot);

		if (hasLastAction) {
			fightTicks++;
			fightDealt += facts.damageDealt;
			fightTaken += facts.damageTaken;
			boolean ko = facts.koDealt || facts.koTaken;
			boolean timeUp = fightTicks >= FIGHT_TICKS;
			boolean full = mode == Mode.TRAIN && learner.rolloutRoom() <= 1;
			if (mode == Mode.TRAIN) {
				float reward = PER_HEALTH * (facts.damageDealt - facts.damageTaken) + (facts.koDealt ? KNOCKOUT : 0f) - (facts.koTaken ? KNOCKOUT : 0f);
				if (facts.hitsDealt > 0 && facts.critDealt) {
					if (liftedFor >= 0) {
						reward += PUNISH_CRIT;
						punishCritsPaid++;
						liftStage = 6;
					} else {
						reward += OTHER_CRIT;
						otherCrits++;
					}
				}
				if (facts.hitsDealt > 0 && facts.sprintHit && reader.duel.them.falling()) {
					reward -= K2_BROKEN;
					k2Broken++;
				}
				if (clicked && !clickOnTarget) {
					reward -= AIR_SWING;
				}
				if (clicked && critChanceAtClick) {
					reward += CRIT_SWING;
					critSwings++;
				}
				reward += shaping;
				shapingPaid += shaping;
				shaping = 0f;
				learner.complete(reward, ko, !ko && (timeUp || full), step.obs);
			}
			if (ko || timeUp || full) {
				endFight(facts.koDealt, facts.koTaken);
				AgentController.release(mc);
				if (mode == Mode.TRAIN && learner.rolloutSize() >= ROLLOUT) {
					startUpdate();
				} else {
					beginFight(truth);
				}
				return;
			}
		}

		// ---- decide and act ----
		float charge = me.getAttackStrengthScale(0f);
		chargeAtClick = charge;
		sprintAtClick = truth.agentSprinting();
		critAtClick = facts.falling && !me.onGround() && !sprintAtClick && charge > 0.9f;
		liftAimedNow = false;
		if (liftedFor >= 0 && liftKind > 0 && facts.falling && !me.onGround() && facts.reachDist <= 3.0f) {
			int stage = charge <= 0.9f ? 1 : sprintAtClick ? 2 : !onOpponent() ? 3 : 4;
			liftStage = Math.max(liftStage, stage);
			liftAimedNow = stage == 4;
		}
		int keys = SwingGate.keys(ActionSpace.ALL_HEADS, charge, cfg.swingMinCharge);
		boolean chargeLocked = !ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		// The installed model's guard, the same for this network (24 Oct 17:10): no swing at an opponent plainly out of reach
		// or off the screen (SwingGate.outOfReach). Without it, in the 16:31 check 37% of its swings hit nothing, the
		// installed model's 9%, and that was the whole gap in damage dealt.
		// Never with the opponent under the crosshair: the distance read off the screen is rough, and at 17:15 it held back a
		// tenth to a fifth of the deflection's (T4) swings that would have landed.
		// Since 22:15 no swing at all without the opponent under the crosshair in reach, as the game picks it: the distance read
		// off the screen let through swings at an opponent aimed at but out of reach, again and again in the user's real fights
		// (24 Oct 22:05: "constantly hit opponent when aim and not in reach"). A swing that would hit nothing is not made.
		if (cfg.whiffGuard && !onOpponent()) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		teacher.tick(reader.duel, reader.events, onOpponent());
		liftTaught |= liftedFor >= 0 && teacher.technique() == Technique.B1;
		step.teacherHeads = teacher.target(step.teacher);
		step.teacherTechnique = step.teacherHeads != 0 ? teacher.technique().ordinal() : -1;
		// A technique's swing a rule kept the network from: the sword not back enough for the gate, or the whiff guard.
		int ao = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		if (mode == Mode.TRAIN && step.teacherTechnique >= 0 && (step.teacherHeads & (1 << ActionSpace.ATTACK)) != 0
				&& step.teacher[ao + 1] > step.teacher[ao] && !ActionSpace.unlocked(keys, ActionSpace.ATTACK)) {
			blockedSwings[step.teacherTechnique][chargeLocked ? 0 : 1]++;
		}
		if (installedPlays) {
			observer.fillBody(installedBody, me, bot, truth.floorY());
			installed.fighter.crosshairOnOpponent(onOpponent());
			int[] chosen = installed.fighter.decide(observer.frame, installedBody);
			System.arraycopy(chosen, 0, action, 0, ActionSpace.HEADS);
			perfB.tick(step.teacherTechnique, step.teacherHeads, step.teacher, action, ActionSpace.ALL_HEADS, facts.damageDealt, facts.damageTaken);
			traceCrit(1, action[ActionSpace.ATTACK] == 1);
			traceJumpIn(1);
			applyAction(installed.fighter.yawRate(), installed.fighter.pitchRate());
			clicked = action[ActionSpace.ATTACK] == 1;
			showBar(truth);
			return;
		}
		if (teacherPlaysA) {
			// The teacher's own keys, as they are; the installed model's camera.
			observer.fillBody(installedBody, me, bot, truth.floorY());
			installed.fighter.crosshairOnOpponent(onOpponent());
			int[] chosen = installed.fighter.decide(observer.frame, installedBody);
			System.arraycopy(chosen, 0, action, 0, ActionSpace.HEADS);
			dev.swordai.core.pvp.Keys k = teacher.keys();
			boolean techniqueMoment = step.teacherHeads != 0 && step.teacherTechnique != Technique.BASE.ordinal();
			// In the overlay the ordinary fight keeps the installed model's own keys; the technique moments are the teacher's.
			if (!overlay || techniqueMoment) {
				action[ActionSpace.FORWARD] = k.forward > 0 ? 1 : k.forward < 0 ? 2 : 0;
				action[ActionSpace.STRAFE] = k.strafe < 0 ? 1 : k.strafe > 0 ? 2 : 0;
				action[ActionSpace.SPRINT] = k.sprint && k.forward > 0 ? 1 : 0;
				action[ActionSpace.JUMP] = k.jump ? 1 : 0;
				action[ActionSpace.ATTACK] = k.attack && onOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK) ? 1 : 0;
			}
			perf.tick(step.teacherTechnique, step.teacherHeads, step.teacher, action, ActionSpace.ALL_HEADS, facts.damageDealt, facts.damageTaken);
			traceCrit(0, action[ActionSpace.ATTACK] == 1);
			traceJumpIn(0);
			applyAction(installed.fighter.yawRate(), installed.fighter.pitchRate());
			clicked = action[ActionSpace.ATTACK] == 1;
			// The fight's clock runs from its first action on (the school's network sets this on its side).
			System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
			hasLastAction = true;
			showBar(truth);
			return;
		}
		step.teacherWeight = 0f;
		if (mode == Mode.TRAIN && stages.oldPull() > 0f && (installed != null || learner.hasOldTeacher())) {
			if (installed != null) {
				// What the installed model would press here, rules and all: its choice, as a target.
				observer.fillBody(installedBody, me, bot, truth.floorY());
				installed.fighter.crosshairOnOpponent(onOpponent());
				int[] would = installed.fighter.decide(observer.frame, installedBody);
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					int off = ActionSpace.OFFSETS[h];
					int n = ActionSpace.SIZES[h];
					for (int k = 0; k < n; k++) {
						oldProbs[off + k] = k == would[h] ? SHADOW_SURE : (1f - SHADOW_SURE) / (n - 1);
					}
				}
			} else {
				learner.oldChoices(step.obs, oldProbs);
			}
			// The installed model teaches the camera; the body is the file's ordinary fight and techniques (one teacher
			// for it: two that disagree in the same spot teach neither, 24 Oct 01:50).
			int upTo = OVERLAY && step.teacherHeads == 0 ? ActionSpace.HEADS - 1 : ActionSpace.PITCH;
			for (int h = 0; h <= upTo; h++) {
				if ((step.teacherHeads & (1 << h)) == 0) {
					System.arraycopy(oldProbs, ActionSpace.OFFSETS[h], step.teacher, ActionSpace.OFFSETS[h], ActionSpace.SIZES[h]);
				}
			}
			step.teacherWeight = 1f;
		}
		if (mode == Mode.TRAIN && OVERLAY && step.teacherHeads == 0) {
			// The basics teacher: the swing outside the technique moments (see AIR_SWING).
			// The golden rule (K2) holds here too: no knockback hit (sprinting) on one who is falling.
			boolean swing = ActionSpace.unlocked(keys, ActionSpace.ATTACK) && onOpponent()
					&& !(reader.duel.them.falling() && reader.duel.me.sprinting);
			int off = ActionSpace.OFFSETS[ActionSpace.ATTACK];
			step.teacher[off] = swing ? 0f : 1f;
			step.teacher[off + 1] = swing ? 1f : 0f;
			step.teacherHeads = 1 << ActionSpace.ATTACK;
			step.teacherTechnique = Technique.BASE.ordinal();
		}
		// Not on a K2 moment: its lesson is the swing held back, and a second teacher there would teach both.
		critChanceAtClick = mode == Mode.TRAIN && OVERLAY && critAtClick && facts.reachDist <= 3.0f && onOpponent()
				&& ActionSpace.unlocked(keys, ActionSpace.ATTACK) && step.teacherTechnique != Technique.K2.ordinal();
		if (critChanceAtClick) {
			// The crit teacher (see CRIT_SWING): over whatever else is taught, a crit that can be made now is made.
			int off = ActionSpace.OFFSETS[ActionSpace.ATTACK];
			step.teacher[off] = 0f;
			step.teacher[off + 1] = 1f;
			step.teacherHeads |= 1 << ActionSpace.ATTACK;
			if (step.teacherTechnique < 0) {
				step.teacherTechnique = Technique.BASE.ordinal();
			}
			critChances++;
		}
		// Finding them: off the screen or well off the crosshair, the camera is turned straight toward where they are. In the
		// user's real fights (24 Oct 22:05) a player who jumped out of its view was lost, the camera shaking left and right and
		// never turning back; the bots of the school hardly ever leave its view, so nothing taught it.
		if (mode == Mode.TRAIN && OVERLAY && !onOpponent()) {
			double dx = bot.getX() - me.getX();
			double dz = bot.getZ() - me.getZ();
			double dy = bot.getY() + bot.getBbHeight() * 0.5 - me.getEyeY();
			float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			float yawOff = (wantYaw - me.getYRot()) % 360f;
			yawOff = yawOff > 180f ? yawOff - 360f : yawOff < -180f ? yawOff + 360f : yawOff;
			if (!facts.enemyVisible || Math.abs(yawOff) > 25f) {
				nearestBin(step.teacher, ActionSpace.YAW, ActionSpace.YAW_BINS, yawOff);
				nearestBin(step.teacher, ActionSpace.PITCH, ActionSpace.PITCH_BINS, wantPitch - me.getXRot());
				step.teacherHeads |= (1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH);
				if (step.teacherTechnique < 0) {
					step.teacherTechnique = Technique.BASE.ordinal();
				}
				findAim++;
			}
		}
		// The punish crit teacher (B1: "their hit knocks you up; you crit them on the way down"). Of the lifts after their
		// sprint hits, a third came down in reach, most with the sword back, and all but one still sprinting (24 Oct 16:15):
		// near them with the sword coming back, W and the sprint key are let go on the way, so that no sprint is left when
		// the crit can be made. S held stays (it ends the sprint as well); the K2 moment's own lesson stays its own.
		// By its own sprint as the client shows it (what the network sees and the B1 teacher goes by), not the server's a tick
		// behind: on that tick the two teachers asked W off and W on, and B1 was off on W/S up to a third of its ticks (20:50).
		if (mode == Mode.TRAIN && OVERLAY && liftedFor >= 0 && !me.onGround() && me.isSprinting() && facts.reachDist <= 3.5f
				&& charge > 0.5f && step.teacherTechnique != Technique.K2.ordinal()) {
			int f = ActionSpace.OFFSETS[ActionSpace.FORWARD];
			boolean back = (step.teacherHeads & (1 << ActionSpace.FORWARD)) != 0 && step.teacher[f + 2] > Math.max(step.teacher[f], step.teacher[f + 1]);
			if (!back) {
				step.teacher[f] = 1f;
				step.teacher[f + 1] = 0f;
				step.teacher[f + 2] = 0f;
			}
			int sp = ActionSpace.OFFSETS[ActionSpace.SPRINT];
			step.teacher[sp] = 1f;
			step.teacher[sp + 1] = 0f;
			step.teacherHeads |= (1 << ActionSpace.FORWARD) | (1 << ActionSpace.SPRINT);
			if (step.teacherTechnique < 0 || step.teacherTechnique == Technique.BASE.ordinal()) {
				step.teacherTechnique = Technique.B1.ordinal();
			}
			sprintLetGo++;
		}
		// The punish crit's aim: coming down onto them after their hit's lift, the camera turned onto their body. Of the falls
		// in reach with the sword back and no sprint, the crosshair was on them on 0 to 2 of 3 to 7 an update (24 Oct 20:15),
		// and nothing but the installed model, its pull fading, taught the camera there. Taught, not judged: the moment is
		// judged on its keys as before.
		// And every crit's fall of the crit family (24 Oct 20:40, the user: the double crit around them at 14%, off on the swing
		// a quarter of its ticks: going around them in the air the crosshair comes off them).
		boolean critFall = step.teacherTechnique == Technique.T1.ordinal() || step.teacherTechnique == Technique.T1A.ordinal()
				|| step.teacherTechnique == Technique.T1B.ordinal() || step.teacherTechnique == Technique.T1C.ordinal()
				|| step.teacherTechnique == Technique.T2.ordinal() || step.teacherTechnique == Technique.C6.ordinal();
		if (mode == Mode.TRAIN && OVERLAY && (liftedFor >= 0 || critFall) && facts.falling && !me.onGround() && facts.reachDist <= 3.5f
				&& charge > 0.5f && step.teacherTechnique != Technique.K2.ordinal()) {
			double dx = bot.getX() - me.getX();
			double dz = bot.getZ() - me.getZ();
			double dy = bot.getY() + bot.getBbHeight() * 0.5 - me.getEyeY();
			float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			float yawOff = (wantYaw - me.getYRot()) % 360f;
			yawOff = yawOff > 180f ? yawOff - 360f : yawOff < -180f ? yawOff + 360f : yawOff;
			nearestBin(step.teacher, ActionSpace.YAW, ActionSpace.YAW_BINS, yawOff);
			nearestBin(step.teacher, ActionSpace.PITCH, ActionSpace.PITCH_BINS, wantPitch - me.getXRot());
			step.teacherHeads |= (1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH);
			if (step.teacherTechnique < 0 || step.teacherTechnique == Technique.BASE.ordinal()) {
				step.teacherTechnique = Technique.B1.ordinal();
			}
			liftAim++;
		}
		step.policyWeight = !facts.enemyVisible && facts.reachDist > SKIP_FARTHER ? 0f : 1f;
		step.botTechnique = botTechnique;
		step.botKeys = truth.botKeys();
		step.kind = fightBot;
		learner.act(step, keys, mode == Mode.TRAIN, cfg.playTemperature, action);
		if (mode == Mode.CHECK) {
			fairJump.apply(action, me.onGround(), clock.ticksSinceHitTaken, clock.takenStreak.hits());
		}
		// Judged on the keys: the camera a teacher shows (the punish crit's aim) is taught, not counted for done.
		perf.tick(step.teacherTechnique, step.teacherHeads & ~((1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH)), step.teacher, action, keys,
				facts.damageDealt, facts.damageTaken);
		if (mode == Mode.TRAIN) {
			techniqueReward();
		}
		traceCrit(0, action[ActionSpace.ATTACK] == 1);
		traceJumpIn(0);
		if (step.teacherHeads != 0) {
			fightTeacherTicks++;
			int t = step.teacherTechnique;
			spoke[t]++;
			if (agrees(learner.lastLogits(), step.teacher, step.teacherHeads)) {
				agreed[t]++;
			}
		}
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		applyAction(motor.yawRate(), motor.pitchRate());
		clicked = action[ActionSpace.ATTACK] == 1;
		if (liftAimedNow && clicked) {
			liftStage = Math.max(liftStage, 5);
		}
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
		showBar(truth);
	}

	/** What it is doing, on the screen above the hotbar: the stage, the technique whose moment it is, its keys, the bot's. */
	private void showBar(Truth truth) {
		if (++barTicks < 4 || mc.gui == null) {
			return;
		}
		barTicks = 0;
		int t = step.teacherTechnique;
		String now = t >= 0 ? Technique.ALL[t].id + " " + Technique.ALL[t].title : "ordinary fight";
		int bt = truth.botTechnique();
		String bot = bt >= 0 && bt != Technique.BASE.ordinal() ? Technique.ALL[bt].id + " " + Technique.ALL[bt].title : "ordinary fight";
		String who = mode == Mode.CHECK ? (installedPlays ? "CHECK B (installed)" : teacherPlaysA ? "CHECK A (teacher)" : "CHECK A (school)")
				: "School " + stages.current().name;
		// The decider's choice, and what the network was told (the teacher's technique in most training fights).
		String decider = installedPlays || teacherPlaysA ? "" : " | decider: " + learner.chosen().id
				+ (learner.told() != learner.chosen() ? ", told " + learner.told().id : "");
		mc.gui.setOverlayMessage(Component.literal(String.format(Locale.ROOT, "%s | moment: %s%s | AI keys: %s | %s: %s | %.0f-%.0f",
				who, now, decider, keysOf(installedPlays && !teacherPlaysA ? installed.fighter.action() : action), Specialists.NAMES[fightBot], bot,
				fightDealt, fightTaken)), false);
	}

	/** What this step earns for doing the technique it was told (see {@link #IMITATE}); paid with the step's reward. */
	private void techniqueReward() {
		if (pullsNow == null) {
			pullsNow = stages.techPulls();
		}
		boolean told = learner.toldTheTeachers();
		int t = perf.lastTechnique;
		if (t >= 0 && t != lastMomentTechnique) {
			stages.sawMoment(fightBot, t);
		}
		lastMomentTechnique = t;
		if (told && t >= 0 && perf.lastMatch >= 0f) {
			shaping += IMITATE * perf.lastMatch * Math.min(SHARE_MOST, pullsNow[t]);
		}
		for (int i = 0; i < Technique.COUNT; i++) {
			int paid = perf.takePaid(i);
			int won = perf.takeWon(i);
			if (told && paid + won > 0 && i != Technique.BASE.ordinal()) {
				shaping += (DONE_BONUS * paid + WON_BONUS * won) * Math.min(SHARE_MOST, pullsNow[i]);
				momentsPaid += paid;
				momentsWon += won;
			}
		}
	}

	/** "120 swings, 90 with the crosshair on them (75%), 80 hits landed (67%)". */
	private String swingLine(int side) {
		long[] c = swingCount[side];
		return String.format(Locale.ROOT, "%d swings, %d with the crosshair on them (%.0f%%), %d hits landed (%.0f%%)", c[0], c[1],
				100.0 * c[1] / Math.max(1, c[0]), c[2], 100.0 * c[2] / Math.max(1, c[0]));
	}

	private static String keysOf(int[] a) {
		int fwd = a[ActionSpace.FORWARD];
		int side = a[ActionSpace.STRAFE];
		return (fwd == 1 ? "W" : fwd == 2 ? "S" : "-") + (side == 1 ? "A" : side == 2 ? "D" : "-") + (a[ActionSpace.SPRINT] == 1 ? " sprint" : "")
				+ (a[ActionSpace.JUMP] == 1 ? " jump" : "") + (a[ActionSpace.ATTACK] == 1 ? " swing" : "");
	}

	/** What the game showed it did: " jump reset (T9) 3/12 (25%);" for each habit it had chances at. */
	private String ownLine(int side) {
		StringBuilder b = new StringBuilder();
		for (int h = 0; h < Habits.COUNT; h++) {
			if (ownSeen[side][h] > 0) {
				b.append(String.format(Locale.ROOT, " %s %d/%d (%.0f%%);", Habits.NAMES[h], ownDid[side][h], ownSeen[side][h],
						100f * ownDid[side][h] / ownSeen[side][h]));
			}
		}
		return b.toString();
	}

	/** Per technique: " T7 12/30 done, 8 paid;". */
	private static String perfLine(Performance p) {
		StringBuilder b = new StringBuilder();
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (p.moments[i] > 0) {
				b.append(String.format(Locale.ROOT, " %s %d/%d done (%.0f%%), %d paid;", t.id, p.done[i], p.moments[i], 100f * p.done[i] / p.moments[i], p.paid[i]));
			}
		}
		return b.toString();
	}

	/** Whether the network's likeliest key is the teacher's on every head it taught. */
	private static boolean agrees(float[] logits, float[] target, int heads) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if ((heads & (1 << h)) == 0) {
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			int best = 0;
			for (int k = 1; k < ActionSpace.SIZES[h]; k++) {
				if (logits[off + k] > logits[off + best]) {
					best = k;
				}
			}
			// One of the keys it asks for (either strafe; stop or S, where those are alike).
			if (target[off + best] < Teacher.ANY) {
				return false;
			}
		}
		return true;
	}

	private void applyAction(float yawRate, float pitchRate) {
		LocalPlayer me = mc.player;
		float yawBefore = me.getYRot();
		float pitchBefore = me.getXRot();
		AgentController.apply(mc, installedPlays ? installed.fighter.action() : action, yawRate, pitchRate);
		turnYawThisTick = me.getYRot() - yawBefore;
		turnPitchThisTick = me.getXRot() - pitchBefore;
		actedThisTick = true;
		clickResolved = false;
		clickOnTarget = false;
		if (installedPlays) {
			hasLastAction = true;
		}
	}

	private void endFight(boolean won, boolean lost) {
		if (fightBot >= 0 && fightBot < Specialists.KINDS) {
			botDealt[fightBot] += fightDealt;
			botTaken[fightBot] += fightTaken;
			botTicks[fightBot] += fightTicks;
			botFights[fightBot]++;
			botWins[fightBot] += won ? 1 : 0;
			botLosses[fightBot] += lost ? 1 : 0;
		}
		int side = installedPlays ? 1 : 0;
		for (int h = 0; h < Habits.COUNT; h++) {
			ownSeen[side][h] += reader.own.seen(h);
			ownDid[side][h] += reader.own.did(h);
		}
		String result = won ? ", knockout" : lost ? ", knocked out" : "";
		if (mode == Mode.CHECK) {
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school pair %d %s %s%s: dealt %.1f, taken %.1f, ticks %d%s", (checkFights - 1) / 2,
					installedPlays ? "B" : "A", Specialists.NAMES[fightBot], fightStyle.heldOut ? " (held out)" : "", fightDealt, fightTaken, fightTicks, result));
			if (installedPlays && ((checkFights / 2) % 10 == 0)) {
				checkSummary();
			}
		} else {
			stages.fight(fightBot, fightDealt, fightTaken, fightTicks);
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school fight %s: dealt %.1f, taken %.1f, ticks %d%s, teacher spoke on %d ticks; %s",
					Specialists.NAMES[fightBot], fightDealt, fightTaken, fightTicks, result, fightTeacherTicks, fightStyle.describe()));
		}
		hasLastAction = false;
		clicked = false;
		java.util.Arrays.fill(recentCrits, 0);
		sinceFallingHit = 1000;
	}

	/** The fight is broken off (a pause, frames that stopped): keys let go, the step in progress dropped. */
	private void suspendFight() {
		AgentController.release(mc);
		if (mode == Mode.TRAIN && pending == null) {
			learner.interrupt();
		}
		hasLastAction = false;
		clicked = false;
		motor.reset();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.setIdle(true));
		}
	}

	// ---- learning -------------------------------------------------------------------------------

	/** Paired check: what each side did when the moments came, and what the game showed it did. */
	private void checkSummary() {
		SwordAi.LOGGER.info("school check after {} pairs | A (school) techniques:{} | B (installed) techniques:{} | A keys a minute off/on moments: {} | B: {} "
				+ "| A by the game:{} | B by the game:{} | A crit moments: {} | B crit moments: {} | A jumping in: {} | B jumping in: {} | A swings: {} | B swings: {}",
				checkFights / 2, perfLine(perf), perfLine(perfB), perf.keysLine(), perfB.keysLine(), ownLine(0), ownLine(1), critLine(0), critLine(1),
				jumpInLine(0), jumpInLine(1), swingLine(0), swingLine(1));
	}

	/** One tick of a side's crit moments (see {@link #critTrace}). */
	private void traceCrit(int side, boolean swung) {
		boolean wanted = step.teacherTechnique >= 0 && CRIT_WANTED[step.teacherTechnique];
		// A hit lands a tick or two after the swing: the moment's few ticks after it still count.
		critWantedFor = wanted ? 3 : Math.max(0, critWantedFor - 1);
		long[] c = critTrace[side];
		if (critWantedFor > 0) {
			c[7] += facts.hitsDealt;
			c[8] += facts.critDealt ? 1 : 0;
		}
		if (!wanted) {
			return;
		}
		c[0]++;
		boolean falling = facts.falling && !mc.player.onGround();
		if (falling) {
			c[1]++;
			if (facts.reachDist <= 3.0f) {
				c[2]++;
				if (chargeAtClick > 0.9f) {
					c[3]++;
					c[4] += sprintAtClick ? 0 : 1;
				}
			}
		}
		if (swung) {
			c[5]++;
			c[6] += critAtClick ? 1 : 0;
		}
	}

	/** A head's target: the bin nearest {@code degrees}, sure. */
	private static void nearestBin(float[] target, int head, float[] bins, float degrees) {
		int off = ActionSpace.OFFSETS[head];
		int best = 0;
		for (int k = 1; k < bins.length; k++) {
			if (Math.abs(bins[k] - degrees) < Math.abs(bins[best] - degrees)) {
				best = k;
			}
		}
		for (int k = 0; k < bins.length; k++) {
			target[off + k] = k == best ? 1f : 0f;
		}
	}

	/** " T4 2/5; B1 0/1" (sword gate / whiff guard), and starts the counts again. */
	private String blockedLine() {
		StringBuilder b = new StringBuilder();
		for (int t = 0; t < Technique.COUNT; t++) {
			if (blockedSwings[t][0] + blockedSwings[t][1] > 0) {
				b.append(' ').append(Technique.ALL[t].id).append(' ').append(blockedSwings[t][0]).append('/').append(blockedSwings[t][1]).append(';');
			}
			blockedSwings[t][0] = 0;
			blockedSwings[t][1] = 0;
		}
		return b.length() == 0 ? " none" : b.toString();
	}

	private void endLift() {
		if (liftKind > 0 && liftKind < lifts.length) {
			lifts[liftKind][0]++;
			for (int k = 1; k <= liftStage; k++) {
				lifts[liftKind][k]++;
			}
			lifts[liftKind][7] += liftTaught ? 1 : 0;
		}
		liftKind = -1;
		liftStage = 0;
		liftTaught = false;
	}

	/** "after sprint hits 40: in reach 6, sword back 3, not sprinting 1, aimed 1, swung 1, crit 0, teacher B1 0; after crits ...". */
	private String liftLine() {
		StringBuilder b = new StringBuilder();
		String[] names = {"", "sprint hits", "crits", "plain hits"};
		for (int k = 1; k < lifts.length; k++) {
			int[] c = lifts[k];
			b.append(String.format(Locale.ROOT, "%s after %s %d: in reach %d, sword back %d, not sprinting %d, aimed %d, swung %d, crit %d, teacher B1 %d",
					k == 1 ? "" : ";", names[k], c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]));
			java.util.Arrays.fill(c, 0);
		}
		return b.toString();
	}

	private void traceJumpIn(int side) {
		long[] c = jumpIn[side];
		dev.swordai.core.pvp.Body them = reader.duel.them;
		if (them.sinceJump == 0 && reader.duel.theyClose > 0.05 && reader.duel.flat < 6.5) {
			c[0]++;
		}
		sinceFallingHit = Math.min(sinceFallingHit + 1, 1000);
		if (facts.hitsDealt > 0 && them.rising()) {
			c[1]++;
		}
		if (facts.hitsDealt > 0 && facts.sprintHit && them.falling()) {
			c[2]++;
			sinceFallingHit = 0;
			// Their crit may come first, as they fall into it: the ten ticks before count too.
			c[3] += critsInLastTen();
		} else if (facts.critsTaken > 0 && sinceFallingHit <= 10) {
			c[3] += facts.critsTaken;
		}
		c[4] += facts.critsTaken;
		recentCrits[(int) (critClock++ % recentCrits.length)] = facts.critsTaken;
	}

	private final int[] recentCrits = new int[10];
	private long critClock;

	private int critsInLastTen() {
		int n = 0;
		for (int c : recentCrits) {
			n += c;
		}
		java.util.Arrays.fill(recentCrits, 0);
		return n;
	}

	private String jumpInLine(int side) {
		long[] c = jumpIn[side];
		return String.format(Locale.ROOT, "their jump-ins %d; its hits on them rising (T8) %d; its knockback hits on them falling (K2 broken) %d, crits taken "
				+ "within ten ticks %d; crits taken in all %d", c[0], c[1], c[2], c[3], c[4]);
	}

	private String critLine(int side) {
		long[] c = critTrace[side];
		return String.format(Locale.ROOT, "%d ticks, falling %d, and in reach %d, and charged %d, and not sprinting (server) %d; swings %d (crits at the click %d); "
				+ "hits %d, crits %d", c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]);
	}

	private void startUpdate() {
		phase = Phase.UPDATING;
		mc.getSingleplayerServer().execute(() -> ArenaServer.setIdle(true));
		long steps = learner.rolloutSize();
		stages.addSteps(steps);
		SchoolPpo.Settings set = new SchoolPpo.Settings();
		set.techPulls = stages.techPulls();
		set.oldPull = stages.oldPull();
		set.installedBody = OVERLAY;
		set.criticOnly = warmupLeft > 0;
		if (warmupLeft > 0) {
			warmupLeft--;
		}
		float predictorLr = 1e-3f;
		Path checkpoint = SwordFiles.schoolCheckpoint();
		Path stagesFile = SwordFiles.schoolStages();
		pending = updater.submit(() -> {
			SchoolPpo.Stats stats = learner.update(set, predictorLr);
			learner.save(checkpoint);
			stages.save(stagesFile);
			return stats;
		});
	}

	private void finishUpdate() {
		SchoolPpo.Stats stats;
		try {
			stats = pending.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("the school's update was interrupted", e);
			return;
		} catch (java.util.concurrent.ExecutionException e) {
			fail("the school's update failed: " + e.getCause(), e.getCause());
			return;
		} finally {
			pending = null;
		}
		long nowMillis = System.currentTimeMillis();
		long steps = learner.totalSteps;
		float perSecond = (steps - stepsAtLastUpdate) * 1000f / Math.max(1L, nowMillis - millisAtLastUpdate);
		stepsAtLastUpdate = steps;
		millisAtLastUpdate = nowMillis;
		StringBuilder bots = new StringBuilder();
		for (int b = 0; b < Specialists.KINDS; b++) {
			if (botFights[b] == 0) {
				continue;
			}
			float minutes = botTicks[b] / 1200f;
			bots.append(String.format(Locale.ROOT, " %s %d fights dealt %.1f taken %.1f a minute (net %+.1f) ko %d-%d;", Specialists.NAMES[b], botFights[b],
					botDealt[b] / minutes, botTaken[b] / minutes, (botDealt[b] - botTaken[b]) / minutes, botWins[b], botLosses[b]));
		}
		StringBuilder taught = new StringBuilder();
		StringBuilder used = new StringBuilder();
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (spoke[i] > 0) {
				taught.append(String.format(Locale.ROOT, " %s %d (%.0f%% as taught)", t.id, spoke[i], 100f * agreed[i] / spoke[i]));
			}
			if (botUsed[i] > 0) {
				used.append(' ').append(t.id).append(' ').append(botUsed[i]);
			}
		}
		stages.adapt();
		pullsNow = stages.techPulls();
		lastUpdateLine = String.format(Locale.ROOT, "update %d, %d steps (%.0f a second): %s", learner.updates, steps, perSecond, stats);
		SwordAi.LOGGER.info("school {} | {} | predictor {} | decider {} | teachers' examples again: {} | per bot:{} | its moments by technique (network's likeliest keys):{} | done since the last update:{} "
				+ "| why not done:{} | jumps, swings, S a minute off/on moments: {} | by the game:{} | crit moments: {} | jumping in: {} | real punish crits rewarded {}, other crits {} "
				+ "| crit chances {}, swung on {}, sprint let go taught on {} lift ticks, aim on {}, turned to find them on {} | their hits: {} | punish chances by their hit:{} | swings taught but kept back by a rule (sword gate/whiff guard):{} | technique reward {} for {} moments paid ({} won damage), K2 broken {} | swings: {} | bots went into:{}", lastUpdateLine, stages.describe(), learner.predictorLine(),
				learner.chooserLine(), learner.replayLine(), bots, taught, perfLine(perf), perf.whyLine(),
				perf.keysLine(), ownLine(0), critLine(0), jumpInLine(0), punishCritsPaid, otherCrits,
				critChances, critSwings, sprintLetGo, liftAim, findAim, reader.takeTheirHits(), liftLine(), blockedLine(), String.format(Locale.ROOT, "%.1f", shapingPaid), momentsPaid, momentsWon, k2Broken, swingLine(0), used);
		java.util.Arrays.fill(swingCount[0], 0L);
		punishCritsPaid = 0;
		otherCrits = 0;
		critChances = 0;
		critSwings = 0;
		sprintLetGo = 0;
		liftAim = 0;
		findAim = 0;
		shapingPaid = 0f;
		momentsPaid = 0;
		momentsWon = 0;
		k2Broken = 0;
		java.util.Arrays.fill(critTrace[0], 0L);
		java.util.Arrays.fill(jumpIn[0], 0L);
		float dealtAll = 0f;
		float takenAll = 0f;
		int ticksAll = 0;
		for (int b = 0; b < Specialists.KINDS; b++) {
			dealtAll += botDealt[b];
			takenAll += botTaken[b];
			ticksAll += botTicks[b];
		}
		float minutesAll = Math.max(1, ticksAll) / 1200f;
		say(String.format(Locale.ROOT, "%s %dk steps: done %s | damage a minute %.0f dealt, %.0f taken", stages.current().name, stages.stageSteps / 1000,
				stages.masteryBrief(), dealtAll / minutesAll, takenAll / minutesAll));
		perf.clear();
		for (int[] row : ownSeen) {
			java.util.Arrays.fill(row, 0);
		}
		for (int[] row : ownDid) {
			java.util.Arrays.fill(row, 0);
		}
		java.util.Arrays.fill(botDealt, 0f);
		java.util.Arrays.fill(botTaken, 0f);
		java.util.Arrays.fill(botTicks, 0);
		java.util.Arrays.fill(botFights, 0);
		java.util.Arrays.fill(botWins, 0);
		java.util.Arrays.fill(botLosses, 0);
		java.util.Arrays.fill(spoke, 0);
		java.util.Arrays.fill(agreed, 0);
		java.util.Arrays.fill(botUsed, 0);
		if (stages.passed()) {
			String from = stages.current().name;
			String to = stages.advance();
			say("The school passed " + from + "; on to " + to + ": " + stages.current().family + ".");
			try {
				stages.save(SwordFiles.schoolStages());
				Files.copy(SwordFiles.schoolCheckpoint(), SwordFiles.schoolDir().resolve("checkpoint-after-" + from + ".bin"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not keep the school's state", e);
			}
		}
		if (nowMillis - lastKeptMillis >= KEEP_EVERY_MINUTES * 60_000L) {
			lastKeptMillis = nowMillis;
			try {
				Files.copy(SwordFiles.schoolCheckpoint(), SwordFiles.schoolDir().resolve("kept-" + LocalTime.now().format(HHMM) + ".bin"),
						StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not keep an hourly copy", e);
			}
		}
	}

	private void applySpeed(float wanted) {
		float s = Math.max(1f, wanted);
		speed = s;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.setSpeed(s));
		}
		SwordAiClient.setTickMillis(s > 1f ? 50f / s : 0f);
		grabber.setSpeed(s);
	}

	// ---- stopping -------------------------------------------------------------------------------

	@Override
	public void stop(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		Phase was = phase;
		phase = Phase.STOPPED;
		if (mode == Mode.CHECK) {
			checkSummary();
		}
		try {
			AgentController.release(mc);
			if (pending != null) {
				// The update saves when it is done.
				try {
					pending.get(120, TimeUnit.SECONDS);
				} catch (Exception e) {
					SwordAi.LOGGER.warn("the school's last update did not finish", e);
				}
			} else if (mode == Mode.TRAIN && was != Phase.WAIT_ARENA && was != Phase.SELFTEST) {
				// What was gathered since the last update is not learned from; the stages are kept.
				stages.save(SwordFiles.schoolStages());
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not save the school's stages", e);
		} finally {
			updater.shutdownNow();
			learner.close();
			if (installed != null) {
				installed.close();
			}
			grabber.close();
			KeyedSkin.setActive(false);
			SeenLag.off();
			SwordAiClient.setTickMillis(0f);
			WindowGuard.restore(mc);
			OptionsGuard.restore(mc);
			IntegratedServer server = mc.getSingleplayerServer();
			if (server != null) {
				server.execute(() -> ArenaServer.stop(server));
			}
		}
		say("Stopped (" + reason + ").");
	}

	private void fail(String message, Throwable error) {
		SwordFiles.writeError(message, error);
		if (error != null) {
			SwordAi.LOGGER.error("SwordAI stopped: " + message, error);
		}
		say("Stopped because of a problem: " + message);
		stop("error");
	}

	private void say(String message) {
		SwordAi.LOGGER.info(message);
		Runnable show = () -> {
			if (mc.gui != null) {
				mc.gui.getChat().addMessage(Component.literal("[SwordAI] " + message));
			}
		};
		if (mc.isSameThread()) {
			show.run();
		} else {
			mc.execute(show);
		}
	}
}
