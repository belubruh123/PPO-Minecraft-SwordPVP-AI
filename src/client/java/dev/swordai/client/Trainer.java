package dev.swordai.client;

import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.BotProfile;
import dev.swordai.core.curriculum.Curriculum;
import dev.swordai.core.curriculum.RewardModel;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.report.UpdateRecord;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.AimRule;
import dev.swordai.core.rl.AnswerRule;
import dev.swordai.core.rl.ComboStrafe;
import dev.swordai.core.rl.CritTrade;
import dev.swordai.core.rl.FallWatch;
import dev.swordai.core.rl.FarRest;
import dev.swordai.core.rl.JumpIn;
import dev.swordai.core.rl.BackNudge;
import dev.swordai.core.rl.JumpNudge;
import dev.swordai.core.rl.JumpReset;
import dev.swordai.core.rl.LostRule;
import dev.swordai.core.rl.StraightIn;
import dev.swordai.core.rl.Learner;
import dev.swordai.core.rl.MeetJump;
import dev.swordai.core.rl.MixIdea;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.StepBack;
import dev.swordai.core.rl.SwingGate;
import dev.swordai.core.rl.UpdateStats;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.BotPilot;
import dev.swordai.server.CombatLog;
import dev.swordai.server.EpisodePlan;
import dev.swordai.server.ReplicaBody;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Training as such: which fight comes next, what a step was worth, when the network learns from
 * what it has gathered, and when a stage is passed. Everything here is the same whatever body the
 * agent has. The body is the subclass's business: {@link TrainingSession} plays the user's own
 * character and sees the screen; {@link HeadlessSession} plays a fake player and sees a drawn picture.
 *
 * <p>One thread drives a trainer from start to stop: the client thread for the first, the server
 * thread for the second. The PPO update is the only part that runs elsewhere.
 */
abstract class Trainer implements AgentSession {
	public enum Mode { TRAIN, PLAY, SELFTEST }

	enum Phase { WAIT_ARENA, SELFTEST, WAIT_RESET, SETTLE, OPENER, RUN, UPDATING, WAIT_FRAMES, PAUSED, STOPPED }

	/** Ticks of standing still after a reset, so the teleport has reached the client and frames are fresh. */
	static final int SETTLE_TICKS = 8;
	static final int ARENA_TIMEOUT_TICKS = 600;
	static final int RESET_TIMEOUT_TICKS = 200;
	static final int MISSING_BOT_TICKS = 100;
	/** A jump goes up for about this long; a rising hit later than that after the key was not this jump's. */
	private static final int UPPERCUT_JUMP_TICKS = 9;
	/** Being knocked into the air by a hit lasts about this long; a crit after that came from somewhere else. */
	private static final int PUNISH_WINDOW_TICKS = 16;
	/** A hit taken within this many decisions of the swing having been held for an opponent coming in is the crit it was held for. */
	private static final int PUNISH_AFTER_HELD_TICKS = 10;
	/** Self-play: this share of the fights is against one of the copies from before the current one. */
	private static final float OLDER_COPY_SHARE = 0.3f;
	/**
	 * How much of its swinging the scripted crit-jumper does as jumped critical hits when it is met
	 * again from a later stage: drawn per fight between these two.
	 */
	private static final float REPLAYED_CRIT_MIN = 0.5f;
	private static final float REPLAYED_CRIT_MAX = 0.9f;
	/** How long a crit drill lasts: twenty seconds. */
	private static final int CRIT_DRILL_TICKS = 400;
	/** A sword's reach, eye to hitbox, in blocks. */
	private static final float REACH = 3f;
	/**
	 * The first crit drill: the distance, eye to hitbox, within which it wants the jump made. Only with
	 * the opponent right in its face. Farther off, and that includes the whole range sprint hits are
	 * made at, a jump made in the drill gets no swing and is charged.
	 *
	 * <p>It is drawn this tight on purpose. What the drill teaches becomes a habit, and a habit can be
	 * added to later but not taken back: in real fights it can find that a jump from a little farther
	 * pays as well, and take that up; it could never have unlearned jumping at sprint-hit range.
	 */
	private static final float DRILL_CRIT_ZONE = RewardModel.IN_FACE;
	/** The crit drill with all its keys and the opponent that jumps in: the share of a hit's knockback that opponent takes, so that it stays close. */
	private static final float PUNISH_DRILL_KNOCKBACK = 0.3f;
	/** Combo drills before the duel: their copy of the agent is made anew once it has learned this many more steps. */
	private static final long DRILL_COPY_STEPS = 20_000;
	/** The crit drills that go on after their lessons, in equal shares: the jump and the punish crit held as they were, and the punish crit with all its keys. */
	private static final int KEPT_JUMP = 0;
	private static final int KEPT_FREE = 2;
	private static final int KEPT_DRILLS = 3;
	/** A combo drill starts anyway if the copy has not landed its first hits within this many ticks. */
	static final int OPENER_TIMEOUT_TICKS = 120;
	/**
	 * Fights against people are few, so what is left of one when it stops is learned from rather than
	 * dropped, as long as there are at least this many steps of it.
	 */
	static final int WORTH_LEARNING_STEPS = 256;

	final Minecraft mc;
	final Mode mode;
	final Config cfg;
	final Learner learner;
	/** The user plays and is recorded (see DemoPlan); null in every other session. */
	final DemoPlan demo;
	private final dev.swordai.core.rl.DemoFile.Recorder demoRec;
	/** Where the user was looking when the step being recorded began. */
	private float demoYaw;
	private float demoPitch;
	private int demoFights;
	private int demoWon;
	private int demoLost;
	private int demoWarmups;
	/** Whether the fights are being recorded: not until the user says so, so that they can warm up first. */
	private boolean demoRecording;
	/** Recording begins with the next fight. */
	private boolean demoArmed;
	/** Asked for within this many ticks of a fight's start, recording keeps that fight. */
	private static final int DEMO_GRACE_TICKS = 100;
	/** The keys of a step that is not recorded, for the step's facts. */
	private final int[] demoUnrecorded = ActionSpace.DEFAULTS.clone();
	final Curriculum curriculum;
	/** Read by the report from the client thread while the trainer may be adding to it from another. */
	final List<UpdateRecord> history;
	final boolean resumed;

	final SessionClock clock = new SessionClock();
	final RewardModel rewards = new RewardModel();
	/** Steps of the rollout being collected that were played on the current stage. */
	final Tally tally = new Tally();
	final Tally rehearsalTally = new Tally();
	/** The fights against a runner and against a circler since the last update; see {@code StageSpec.runnerShare}. */
	final Tally runnerTally = new Tally();
	final Tally circlerTally = new Tally();
	/** And against the one in the corner, the one that takes no knockback and the odd ones; see {@code StageSpec.cornerShare}. */
	final Tally cornerTally = new Tally();
	final Tally noKnockTally = new Tally();
	final Tally oddTally = new Tally();
	final Tally jumperTally = new Tally();
	final Tally punisherTally = new Tally();
	final Tally backerTally = new Tally();
	final Tally timerTally = new Tally();
	final Tally personTally = new Tally();
	final Tally spacerTally = new Tally();
	/** The fights against the one at the edge of the reach that only stands and hits; see {@link #KIND_SPACER}. */
	final Tally standerTally = new Tally();
	final Tally whifferTally = new Tally();
	final Tally outsideTally = new Tally();
	/** The jump-in drill ({@link #KIND_JUMPIN}): kept apart, and no part of passing a stage. */
	final Tally jumpInTally = new Tally();
	/** Fights it lost before, fought again; see LostCases. Counted apart, so the kinds' own numbers stay a fair draw. */
	final Tally replayTally = new Tally();
	/** The fights it lost, kept to be fought again; see Config.replayLostShare. */
	private dev.swordai.core.curriculum.LostCases lostCases;
	/** This fight as it was set up, kept if it is lost; or the kept fight it is a replay of. */
	private dev.swordai.core.curriculum.LostCases.Case episodeCase;
	private boolean episodeReplay;
	/** A fight made from one it lost or just beat, a little changed; counted with the replays. See LostCases.mutate. */
	private boolean episodeMutant;
	/** This fight's plan, for the heading the server gave the agent, kept with the fight if it is lost. */
	private EpisodePlan episodePlan;
	private float episodeDealt;
	private float episodeTaken;
	private int episodeTicksCounted;
	/** The running fight is against that one. */
	private boolean episodeStander;
	/** The jump made for it on a hit of a combo it is taking; see {@link JumpReset}. */
	private JumpReset jumpReset;
	/** Out of the line of a combo it is taking, to the side; see {@link ComboStrafe} and {@code Config.comboStrafeRule}. */
	private final ComboStrafe comboStrafe = new ComboStrafe();
	/** Ticks since the last update with A or D held for it by {@link #comboStrafe}, and the combo hits that began such a run. */
	private long comboStrafeTicks;
	private long comboStrafeHits;
	/** No swing at an opponent coming in through the air with its sword ready; see {@link CritTrade} and {@code Config.holdOnFallingJumper}. */
	private CritTrade critTrade;
	/** Since the last update: ticks the swing was held for that, and the punish crits made at once after such a crit; see {@code Config.instantPunish}. */
	private long holdCritTicks;
	private long holdCritKnockAways;
	private long instantPunishTicks;
	/** The clash jumps taken since the last update ({@code JumpIn.CLASH}). */
	private long clashJumps;
	private static final String[] JI_NAMES = {"fights", "crit for crit (same tick)", "trade (same tick, not both crits)",
			"hit first, hit back", "of those crit back", "hit first, no answer", "let land, punished", "of those punish crits",
			"let land, no answer", "taken twice", "nothing", "clash jumps"};
	private static final int JI_FIGHTS = 0;
	private static final int JI_CRIT_FOR_CRIT = 1;
	private static final int JI_TRADE = 2;
	private static final int JI_HIT_BACK = 3;
	private static final int JI_CRIT_BACK = 4;
	private static final int JI_CLEAN = 5;
	private static final int JI_PUNISHED = 6;
	private static final int JI_PUNISH_CRIT = 7;
	private static final int JI_UNANSWERED = 8;
	private static final int JI_TWICE = 9;
	private static final int JI_NOTHING = 10;
	private static final int JI_CLASH = 11;
	/** The jump-in drill since the last update ({@link #KIND_JUMPIN}), by how the exchange went; see {@link #JI_NAMES}. */
	private final long[] jumpInDrill = new long[JI_NAMES.length];
	private double jumpInDealtSum;
	private double jumpInTakenSum;
	/** In a jump-in drill: the episode ticks of the first hit taken and dealt (-1: none yet), whether each was a crit, and a second hit taken. */
	private int jumpInTakenTick = -1;
	private int jumpInDealtTick = -1;
	private boolean jumpInTakenCrit;
	private boolean jumpInDealtCrit;
	private boolean jumpInTakenTwice;
	/** One side's hit unanswered for this many ticks: the exchange is over. */
	private static final int JUMPIN_SETTLE_TICKS = 15;
	/** The step back after a hit of its own that has not sent the opponent away; see {@link StepBack}. */
	private final StepBack stepBack = new StepBack();
	private final dev.swordai.core.rl.EdgeHold edgeHold = new dev.swordai.core.rl.EdgeHold();
	/** When the tactics model chooses next; see {@code Config.tacticModel} and {@link dev.swordai.core.rl.Tactic}. */
	private final dev.swordai.core.rl.Tactic.Clock tacticClock = new dev.swordai.core.rl.Tactic.Clock();
	/** The window of the fight the opponent reader reads; see {@code Config.opponentReader}. */
	private final dev.swordai.core.rl.OpponentReader.Window oppWindow = new dev.swordai.core.rl.OpponentReader.Window();
	/** The running fight tick by tick, with {@code Config.traceFights}; see {@link FightTrace}. */
	private final FightTrace trace = new FightTrace();
	/** Tactics chosen since the last update, per kind of fight ({@link #episodeTask}, row 0 for none) and tactic. */
	private final int[][] tacticPicks = new int[dev.swordai.core.rl.CriticNet.TASKS + 1][dev.swordai.core.rl.Tactic.COUNT];
	/** The names of the kinds of fight, in the order of {@link #episodeTask}. */
	private static final String[] TASK_NAMES = {"own copy", "rehearsal", "runner", "circler", "corner", "no-knockback", "odd",
			"jumper", "punisher", "backer", "timer", "person", "spacer", "stander", "whiffer", "outside", "replay", "drill",
			"jump drill", "punish drill", "free drill", "0.14.0", "trader", "jump-in drill"};
	/** Ticks it stood at the edge of the reach of an opponent whose sword was ready; see EdgeHold. */
	private int edgeTicks;
	private boolean stepBackAtAction;
	static final int KIND_PLAIN = 0;
	static final int KIND_TRADER = 1;
	static final int KIND_RUNNER = 2;
	static final int KIND_CIRCLER = 3;
	static final int KIND_CORNER = 4;
	static final int KIND_NO_KNOCK = 5;
	static final int KIND_ODD = 6;
	/** The one that comes after it with a running jump and a critical hit; see {@code BotProfile.jumpIn}. */
	static final int KIND_JUMPER = 7;
	/** The one that takes part of the knockback and answers its hits with a critical hit on the way down; see {@code BotProfile.punish}. */
	static final int KIND_PUNISHER = 8;
	/** The one that backs out of a combo with S, as this fighter did; see {@code StageSpec.backerShare}. */
	static final int KIND_BACKER = 9;
	/** The one that plays on its rhythm; see {@code BotProfile.timer}. */
	static final int KIND_TIMER = 10;
	/** A scripted copy of a player it has fought; see {@code PersonStyle}. */
	static final int KIND_PERSON = 11;
	/** Crit, S, crit, S, at the edge of its reach; see {@code BotProfile.spacer}. */
	static final int KIND_SPACER = 12;
	/** The one that swings on rhythm at the edge of its reach, which spacing beats; see {@code StageSpec.whifferShare}. */
	static final int KIND_WHIFFER = 13;
	/** Carpet PvP's own fighter, an opponent not written here; see {@code Config.outsideShare}. */
	static final int KIND_OUTSIDE = 14;
	/**
	 * The jump-in drill: an opponent that does one thing, runs and jumps in at it with its sword ready and makes its crit
	 * on the way down, and the fight ends with that exchange; see {@code Config.jumpInShare} and {@link CritTrade}.
	 */
	static final int KIND_JUMPIN = 15;
	/** Which kind of scripted opponent the running fight is against. */
	int episodeKind;
	/** The jumps that are made for it; see {@code JumpIn}. */
	private JumpIn jumpIn;
	private JumpIn jumpInVsCopy;
	private JumpIn jumpInVsOthers;
	/** The camera brought onto the opponent after a hit; see {@code AimRule}. */
	private AimRule aimRule;
	/** See {@link LostRule}. */
	private final LostRule lostRule = new LostRule();
	private final FallWatch punishMemory = new FallWatch();
	private int dealtRow;
	private int ruleJumpAtAction;
	/**
	 * What became of each fall it was knocked into by a hit, since the last update: a critical hit
	 * on the way down; a hit that was not one, made first; the opponent never under its crosshair and
	 * in reach on the way down; that, but never with the sword back; anything else.
	 */
	long fallCrits;
	long fallPlain;
	long fallNoAim;
	/** The opponent was never within a sword's reach on the way down: nothing to be had. */
	long fallFar;
	long fallNoSword;
	long fallOther;
	/** Ticks since the last update on which the camera was the rule's; see {@code AimRule}. */
	long aimedTicks;
	/**
	 * What became of each jump the chase rule made, since the last update: a critical hit; another
	 * hit; the opponent in reach and under its crosshair on the way down, but no swing; in reach but
	 * never under its crosshair; never in reach.
	 */
	long chaseEndCrit;
	long chaseEndHit;
	long chaseEndNoSwing;
	long chaseEndNoAim;
	long chaseEndFar;
	private boolean chaseWatch;
	private boolean chaseAir;
	private boolean chaseNear;
	private boolean chasePick;
	private boolean fallWatch;
	private boolean fallPick;
	private boolean fallNear;
	private boolean fallSword;
	private boolean fallHit;
	private boolean fallCrit;
	private int fallTicks;
	final Tally lastRunner = new Tally();
	final Tally lastCircler = new Tally();
	boolean episodeRunner;
	boolean episodeCircler;
	/** Steps played in combo drills; only what they say about being comboed reaches the report. */
	final Tally drillTally = new Tally();
	/**
	 * The crit drills (see {@code StageSpec.critDrillShare}) since the last update, each kind in a tally
	 * of its own: held for the jump, held for the punish crit, and the punish crit with all its keys.
	 * None of them counts towards the stage's own numbers.
	 */
	final Tally jumpDrillTally = new Tally();
	final Tally punishDrillTally = new Tally();
	final Tally freeDrillTally = new Tally();
	/** Each as it stood at the last update that had any of it, shown while the new ones have nothing yet. */
	final Tally lastJumpDrill = new Tally();
	final Tally lastPunishDrill = new Tally();
	final Tally lastFreeDrill = new Tally();
	final StepFacts facts = new StepFacts();
	final int[] action = new int[ActionSpace.HEADS];
	final int[] lastAction = new int[ActionSpace.HEADS];
	final Random rng = new Random();
	/** Fights begun in this run, for {@code Config.evalSeed}. */
	private long evalFights;
	final ExecutorService updater;
	final AimMotor motor;
	/** The random plan for strafing and hit timing; only handed out in the stages that teach it. */
	final MixIdea idea = new MixIdea();
	/** The plan as it stood when the last action was chosen, which is what that action is scored against. */
	private boolean ideaOnAtAction;
	private int ideaStrafeAtAction;
	private boolean ideaCircleAtAction;
	private int ideaHoldAtAction;
	/** Distance to the opponent's hitbox when the last action was chosen. */
	private float reachAtAction;
	/** Whether the agent has pressed jump since it was last hit: a crit made after that is its own jump's doing. */
	private boolean jumpedSinceHitTaken = true;
	private int ticksSinceJumpKey = 1000;
	/** Fights against itself: the saved copies of the network that play the bot, newest first; empty until set up. */
	List<LoadedFighter> rivals = new ArrayList<>();
	/** Self-play: the running fight is against one of the copies from before the current one. */
	private boolean episodeVsOlder;
	/** Where the old model is among the rivals, or -1; see {@code Config.oldModelShare}. */
	private int oldModelIndex = -1;
	private boolean episodeVsOldModel;
	final Tally oldModelTally = new Tally();
	private int oldModelWins;
	private int oldModelLosses;
	/** The old model's sword: all the way back, every time, as it played on a server. */
	private static final float OLD_MODEL_CHARGE = 1f;
	/** The running fight is a combo drill: a copy of itself starts on top of it and lands the first hits. */
	boolean episodeDrill;
	/** The running episode is a crit drill; see the lessons in {@code Curriculum}. */
	boolean episodeCritDrill;
	/** The running fight is against a trader: the stage's opponent, but one that cannot be kept away; see {@code StageSpec.traderShare}. */
	boolean episodeTrader;
	/** The stage's own fights against traders since the last update, apart from the rest: they are the ones a critical hit is for. */
	final Tally traderTally = new Tally();
	final Tally lastTrader = new Tally();
	/** The running fight is against an opponent on a slow connection: its knockback comes a quarter to half a second late; see {@code StageSpec.slowShare}. */
	boolean episodeSlow;
	/** Swings the rule made since the last update: hit, sword back, opponent under the crosshair and in reach; see {@code AnswerRule}. */
	long forcedAnswers;
	/**
	 * Where the answer rule swings for it [0] and where it holds the swing back [1]: the network's own
	 * preference for swinging there (logit gap, and as a chance), before any push, and how often a swing
	 * was made. Logged and cleared at each update; see {@code Config.answerNudge}.
	 */
	private final double[] ruleGapSum = new double[2];
	private final double[] ruleSwingSum = new double[2];
	private final int[] ruleGapCount = new int[2];
	private final int[] ruleSwung = new int[2];
	/** Where the rule swings for it: how many ticks the network's own gap was below -8, -8..-4, -4..0, 0..4, above 4. */
	private final int[] ruleGapBins = new int[5];
	/** Hits since the last update after which the swing it owed was kept for the way down; see {@code AnswerRule.inTheAir}. */
	long keptAnswers;
	private boolean keptThisHit;
	/** The swing of the step just chosen is the rule's, not the network's: it is only made if the opponent is still under the crosshair at the click. */
	boolean answerAtAction;
	/** In the fight that is on, S is held for it in the ticks after a hit of a combo; see {@code Config.backRule}. */
	private boolean backRuleNow;
	/** How late a hit knocks the opponent back in the running fight, in ticks. */
	int episodeKnockLag;
	/** The share of a hit's knockback the opponent of the running fight takes; see {@link #onEqualTerms}. */
	float episodeBotKnockback = 1f;
	/** What the fighter sees of the opponent comes in fits and starts in the running fight. */
	boolean episodeStutter;
	/** The running fight was set up on equal terms; see {@code StageSpec.fairShare}. */
	boolean episodeFair;
	/** The stage's own fights since the last update by the opponent's connection, for the log: slow, and the rest. */
	final PingCount slowCount = new PingCount();
	final PingCount quickCount = new PingCount();
	final PingCount lastSlow = new PingCount();
	final PingCount lastQuick = new PingCount();
	/** The fights on equal terms against a copy of itself since this training was started; see {@code StageSpec.fairShare}. */
	final PingCount fairCount = new PingCount();
	int fairWon;
	int fairLost;

	/** What the fights against one kind of connection came to. */
	static final class PingCount {
		long steps;
		long dealt;
		long taken;
		long crits;
		long sprint;
		double reward;

		void add(StepFacts f, float r) {
			steps++;
			dealt += f.hitsDealt;
			taken += f.hitsTaken;
			if (f.hitsDealt > 0 && f.critDealt) {
				crits += f.hitsDealt;
			} else if (f.hitsDealt > 0 && f.sprintHit) {
				sprint += f.hitsDealt;
			}
			reward += r;
		}

		void set(PingCount o) {
			steps = o.steps;
			dealt = o.dealt;
			taken = o.taken;
			crits = o.crits;
			sprint = o.sprint;
			reward = o.reward;
		}

		void clear() {
			steps = 0;
			dealt = 0;
			taken = 0;
			crits = 0;
			sprint = 0;
			reward = 0.0;
		}

		String brief() {
			if (steps == 0) {
				return "-";
			}
			return String.format(Locale.ROOT, "%d steps: %d dealt (%.0f%% crit, %.0f%% sprint), %d taken, rew %.3f",
					steps, dealt, dealt == 0 ? 0f : 100f * crits / dealt, dealt == 0 ? 0f : 100f * sprint / dealt, taken, reward / steps);
		}

		/** For the status lines. */
		String line() {
			if (steps == 0) {
				return "none fought yet";
			}
			return String.format(Locale.ROOT, "%,d hits landed and %,d taken in %.1f minutes (%.1f to 1), %.0f%% of its hits critical and %.0f%% sprint hits",
					dealt, taken, steps / 1200f, taken == 0 ? (float) dealt : dealt / (float) taken,
					dealt == 0 ? 0f : 100f * crits / dealt, dealt == 0 ? 0f : 100f * sprint / dealt);
		}
	}
	/** Which lesson that drill is for: {@code Curriculum.DRILL_RANGE} or {@code Curriculum.DRILL_PUNISH}. */
	int critDrillKind;
	/**
	 * And one in which it is held, as in all of those that come first, before the stage's own fights,
	 * and in two in three of those that go on after them: the fighter cannot walk, strafe,
	 * sprint or turn. Its camera is kept on the opponent for it, and all it has is the jump key and
	 * the swing. With no W there is no sprint, so a swing made on the way down with the sword charged
	 * is a critical hit and nothing else can get in the way of finding that out. In the drill for the
	 * punish crit it has not even the jump: the opponent's hit is what puts it in the air.
	 */
	boolean episodeLockedDrill;
	private static final int LOCKED_DRILL_KEYS = ActionSpace.mask(ActionSpace.JUMP, ActionSpace.ATTACK);
	private static final int LOCKED_DRILL_GROUND_KEYS = ActionSpace.mask(ActionSpace.JUMP);
	private static final int PUNISH_DRILL_KEYS = ActionSpace.mask(ActionSpace.ATTACK);
	/** Held for the first crit drill, it is in a jump it took off for from too far away: that jump gets no swing. */
	private boolean drillJumpOutOfZone;
	/**
	 * Held for the first crit drill: how near the opponent has to be for a jump to be wanted. While it
	 * is the lesson, right in its face ({@link #DRILL_CRIT_ZONE}). Once the lessons are passed and the
	 * drill only goes on, the opponent is either there or out of reach altogether, and the line is
	 * drawn at a sword's reach: whether a jump from in between pays is then for the fights to
	 * settle, where it costs unless it lands, and the drill has nothing to say about it.
	 */
	private float episodeJumpZone = DRILL_CRIT_ZONE;
	/** Combo drill: the hits the copy is given before the agent gets its keys back. */
	private int openerHits;
	/** Combo drill: how many of them have landed, and the ticks since the last one. */
	private int openerSeen;
	private int openerSinceHit;
	/** Before the duel: the step count and the stage at which the drills' copy was last made. */
	private long drillCopySteps;
	private int drillCopyStage = -1;
	/** How far the last action really turned the camera; scored on the tick after. Set by {@link #applyAction}. */
	float lastYawTurn;
	float lastPitchTurn;
	/** The episode ends with the step being scored next, whatever else is true of it. Set by the body; cleared once used. */
	boolean cutNow;
	/** The first step after an update that the fight did not wait for: it was a copy's, so it is not scored. */
	private boolean resuming;
	/**
	 * Learning from a fight in safety: the proven network plays this fight, and nothing of it is
	 * learned from ({@code SafeLearn}). Set by the session between fights.
	 */
	boolean baseActs;
	/** A paired evaluation in this run, both networks fight for fight; null for none. See {@link PairedEval}. */
	private PairedEval paired;
	/** The step being scored was chosen by the proven network. */
	boolean byBase;

	Obs obs = new Obs();
	Obs nextObs = new Obs();
	boolean hasLastAction;
	volatile Phase phase = Phase.WAIT_ARENA;
	int waitTicks;
	int settleTicks;
	int episodeTick;
	int episodeStageIndex;
	StageSpec stage;
	int requestedEpisodeId;
	int lastHopId;
	int missingBotTicks;
	int criticOnlyLeft;

	/** Whether the last action swung. */
	boolean clicked;
	private float chargeAtClick;
	private boolean groundAtAction;
	private boolean jumpWindowAtAction;
	/** When the last action was chosen, a swing would have been a critical hit that landed. */
	private boolean critOpenAtAction;
	/** When the last action was chosen, it was in the air from the opponent's hit, with no jump of its own since. */
	private boolean knockedUpAtAction;

	Future<UpdateStats> pendingUpdate;
	private Tally pendingTally;
	long decisions;
	long staleDecisions;
	long lateDecisions;
	volatile String lastError;

	/** How many times its normal speed the world runs at while this trains. */
	volatile float speed = 1f;
	private boolean speedApplied;
	/** The newest row of the history, for whoever wants to act on it right after an update. */
	UpdateRecord lastRecord;
	private long paceMarkNanos;
	private long paceMarkSteps;
	/** Steps gathered per second of real time over the last update cycle, the update itself included; 0 until known. */
	volatile float stepsPerSecond;

	/**
	 * Loads config, checkpoint and curriculum state. The subclass sets up its body and asks the
	 * server for the arena.
	 *
	 * @throws IOException when the saved state cannot be read
	 */
	Trainer(Minecraft mc, Mode mode) throws IOException {
		this(mc, mode, false);
	}

	/**
	 * @param trainedOnly there has to be a trained network to carry on with: this does not start one
	 *                    from nothing, and moves nothing out of the way if what is saved cannot be read
	 */
	Trainer(Minecraft mc, Mode mode, boolean trainedOnly) throws IOException {
		this(mc, mode, trainedOnly, null);
	}

	/** @param demo the user plays the fights and is recorded (see DemoPlan); null for the network */
	Trainer(Minecraft mc, Mode mode, boolean trainedOnly, DemoPlan demo) throws IOException {
		this.mc = mc;
		this.mode = mode;
		this.demo = demo;
		this.demoRec = demo == null ? null : new dev.swordai.core.rl.DemoFile.Recorder();
		if (trainedOnly && !Files.exists(SwordFiles.checkpoint())) {
			throw new IOException("nothing has been trained yet; run /swordai train in a singleplayer world first");
		}
		this.cfg = SwordFiles.loadConfig();
		if (demo != null) {
			demo.apply(cfg);
		}
		this.learner = new Learner(cfg, System.nanoTime(), cfg.resolvedTrainerThreads());
		// Frozen (nothing learned), it fights at the temperature it is played at, to measure that; see Learner.recordTemperature.
		if (cfg.actorLr == 0f && cfg.criticLr == 0f) {
			learner.recordTemperature = cfg.playTemperature;
		}
		this.curriculum = new Curriculum(cfg.stages);
		this.stage = curriculum.current();

		boolean loaded = false;
		Path checkpoint = SwordFiles.checkpoint();
		if (Files.exists(checkpoint)) {
			try {
				learner.load(checkpoint);
				loaded = true;
				if (learner.upgraded()) {
					// The next save writes it with this version's inputs, which the version before cannot read.
					try {
						Files.copy(checkpoint, SwordFiles.beforeUpgrade(), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e) {
						SwordAi.LOGGER.warn("could not keep a copy of the checkpoint from the previous version", e);
					}
				}
			} catch (IOException e) {
				if (trainedOnly) {
					learner.close();
					throw e;
				}
				// A checkpoint from an older version whose network had a different shape. Its progress
				// cannot be carried over, so the whole old run is set aside and a new one begins.
				Path backup = SwordFiles.archiveRun();
				say("The saved checkpoint is from an older version (" + e.getMessage() + ") and cannot be continued. "
						+ "It was moved to " + (backup == null ? "a backup folder" : backup.getFileName()) + "; training starts fresh.");
			}
		}
		if (loaded && Files.exists(SwordFiles.curriculum())) {
			curriculum.load(SwordFiles.curriculum());
			this.stage = curriculum.current();
			if (curriculum.sentBackFrom() >= 0) {
				saveCurriculum();
				say(String.format(Locale.ROOT, "Stage %d (%s) teaches something in this version that it has not learned, so it goes back there from stage %d "
								+ "with everything it knows. (/swordai stage %d skips that.)",
						curriculum.stageIndex() + 1, stage.name, curriculum.sentBackFrom() + 1, curriculum.sentBackFrom() + 1));
			} else if (curriculum.stageBegunAgain()) {
				saveCurriculum();
				say(String.format(Locale.ROOT, "Stage %d (%s) asks for something else in this version, so its step count starts again; nothing it has learned is lost.",
						curriculum.stageIndex() + 1, stage.name));
			}
		}
		this.resumed = loaded;
		SwordAi.LOGGER.info("network: actor {} weights ({} wide vector units, {} blocks of {}), critic {} weights (fight kind {}, {} blocks of {})",
				learner.actorParameters(), cfg.actorVecWide, cfg.actorBlocks, cfg.actorBlockWidth, learner.criticParameters(),
				cfg.criticTask ? "shown" : "not shown", cfg.criticBlocks, cfg.criticBlockWidth);
		if (mode == Mode.TRAIN && Files.exists(SwordFiles.anchor())) {
			try {
				learner.loadAnchor(SwordFiles.anchor());
				SwordAi.LOGGER.info("anchor loaded from {}: training is held near it with weight {}", SwordFiles.anchor(), cfg.anchorWeight);
			} catch (IOException e) {
				say("The anchor network (swordai/anchor.bin) could not be read (" + e.getMessage() + "); training goes on without it.");
			}
		}
		paired = mode == Mode.TRAIN && demo == null ? PairedEval.of(cfg) : null;
		if (paired != null) {
			Path other = Path.of(cfg.pairedBase);
			if (Files.isDirectory(other)) {
				other = other.resolve("checkpoint.bin");
			}
			try {
				learner.loadBase(other);
				SwordAi.LOGGER.info("paired evaluation: {}", paired.describe(cfg));
			} catch (IOException | RuntimeException e) {
				say("The network to compare with (" + cfg.pairedBase + ") could not be read (" + e.getMessage() + "); no paired evaluation.");
				paired = null;
			}
		}
		if (mode == Mode.TRAIN && demo == null && cfg.demoWeight > 0f) {
			// The user's own recorded fights, learned from; see DemoFile.
			SwordAi.LOGGER.info("recorded fights: {}", learner.loadDemos(SwordFiles.demoFiles()));
		}
		this.history = new CopyOnWriteArrayList<>(SwordFiles.loadHistory());
		this.motor = new AimMotor(cfg.aimResponse);
		if (loaded && curriculum.stageIndex() > 0 && curriculum.stepsInStage() == 0) {
			// A stage is being started (again) by hand: as after a promotion, the critic first gets to
			// know the stage's rewards before the actor is allowed to move.
			criticOnlyLeft = cfg.criticWarmupUpdates;
		}
		if (loaded && learner.upgraded()) {
			// The rewards changed along with the inputs; the critic re-learns their scale before the actor moves.
			criticOnlyLeft = cfg.criticWarmupUpdates;
			if (learner.loadedWithoutAimMotor() && curriculum.stageIndex() > 0) {
				// It has never had to bring the camera to rest; that is taught in stage 1.
				curriculum.setStage(0);
				this.stage = curriculum.current();
				saveCurriculum();
				say("Your checkpoint was upgraded. Aiming works differently now (smooth turns, resting on the opponent), "
						+ "so it goes through the stages again from stage 1 with what it already knows.");
			} else {
				say("Your checkpoint from the previous version was upgraded; training continues from where it was. "
						+ "(The old one is kept as " + SwordFiles.beforeUpgrade().getFileName() + ", in case you go back to the previous version.)");
			}
		}

		if (loaded) {
			reopenHeads();
		}
		setLessons(true);

		this.updater = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "swordai-update");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		SwordFiles.clearLastError();
		// The picture, whether from the screen or drawn for a fake player, has one field of view.
		ViewGeometry.setFov(Math.min(cfg.visionFov, cfg.fov));
	}

	// ---- the body: what the subclass supplies --------------------------------------------------------

	/** Lets go of every key the agent might be holding. */
	abstract void releaseKeys();

	/** Start of an episode, after the keys were let go. */
	abstract void prepareBody();

	/** Forgets where the opponent was last seen: a new episode begins. */
	abstract void resetSight();

	/**
	 * A stage that has just begun may want a control put back to how a control starts (the jump key
	 * in the crit stage). Done once, before the stage is played; never while an update is running.
	 */
	void reopenHeads() {
		int heads = curriculum.takeReopenHeads();
		if (heads == 0) {
			return;
		}
		StringBuilder names = new StringBuilder();
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (ActionSpace.unlocked(heads, h)) {
				learner.reopenHead(h, Curriculum.REOPEN_BIAS);
				names.append(names.length() == 0 ? "" : ", ").append(ActionSpace.NAMES[h]);
			}
		}
		saveCurriculum();
		say("Stage " + (curriculum.stageIndex() + 1) + " (" + curriculum.current().name + ") starts the " + names
				+ " control afresh: whatever it had made of it before is set aside, the rest of what it knows is untouched.");
	}

	/**
	 * The keys the running fight is played with: the stage's, or in a locked crit drill only the jump
	 * and the swing. And in such a drill the swing only in the air: on the ground there is just the
	 * jump key, so every swing it makes is one a critical hit can come of, and a swing on the ground
	 * cannot use the sword up first.
	 */
	int episodeKeys() {
		// And never the swing while the weapon is not back; see SwingGate.
		return SwingGate.keys(keysOfTheFight(), chargeAtClick, swingMinCharge());
	}

	/**
	 * Whether the running fight is one on equal terms: one set up that way (see
	 * {@code StageSpec.fairShare}), or one that came out that way. Only such a fight counts as won or
	 * lost against a copy of itself. One whose knockback comes late stays in reach after it is hit and
	 * swings back, while the fighter is thrown clear by every hit it takes: measured against the copy
	 * it was a minute before, it landed 38 hits a minute and took 57 where the knockback came a
	 * quarter to half a second late. One that is knocked back less is back in reach sooner, which is
	 * the same advantage in small, and a picture of the opponent that comes in fits and starts is
	 * the fighter's handicap alone. Those fights are there to be learned from.
	 */
	boolean onEqualTerms() {
		return judgesCopyFights() && (episodeFair || (episodeKnockLag == 0 && episodeBotKnockback >= 1f && !episodeStutter));
	}

	/** How many ticks later than drawn a copy of itself gets to see the fighter in this kind of training; see {@code Config.screenCopyLagTicks}. */
	int copyLagTicks() {
		return 0;
	}

	/** Whether a fight against a copy of itself can be on equal terms at all in this kind of training; not on the screen. */
	boolean judgesCopyFights() {
		return true;
	}

	/** Whether the body's own game has the opponent under the crosshair and within reach right now; see {@code AnswerRule}. */
	boolean crosshairOnOpponent() {
		return false;
	}

	/**
	 * Follows each fall it is knocked into by a hit, from the hit to the landing or to its own next
	 * hit, and counts what became of it; see {@link #fallCrits}. That is what says why a punish crit
	 * was not made when one was not.
	 *
	 * @param keys the keys that are the network's on this tick
	 */
	private void watchFall(int keys) {
		if (fallWatch) {
			if (facts.hitsDealt > 0) {
				fallHit = true;
				fallCrit = facts.critDealt;
			}
			if (!groundAtAction && facts.falling) {
				fallTicks++;
				fallPick |= crosshairOnOpponent();
				fallNear |= facts.reachDist <= REACH;
				fallSword |= crosshairOnOpponent() && bodyCharge() >= swingMinCharge();
			}
			if (fallHit || facts.hitsTaken > 0 || groundAtAction && fallTicks > 0) {
				Tally t = episodeTally();
				if (fallCrit) {
					fallCrits++;
					t.fallCrits++;
				} else if (fallHit) {
					fallPlain++;
					t.fallPlain++;
				} else if (fallTicks > 0) {
					if (!fallNear) {
						fallFar++;
						t.fallFar++;
					} else if (!fallPick) {
						fallNoAim++;
						t.fallNoAim++;
					} else if (!fallSword) {
						fallNoSword++;
						t.fallNoSword++;
					} else {
						fallOther++;
						t.fallOther++;
					}
				}
				fallWatch = false;
			}
		}
		if (facts.hitsTaken > 0 && !episodeLockedDrill) {
			fallWatch = true;
			fallPick = false;
			fallNear = false;
			fallSword = false;
			fallHit = false;
			fallCrit = false;
			fallTicks = 0;
		}
	}

	private JumpIn newJumpIn() {
		JumpIn made = new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, cfg.chaseClosingMost, rng.nextLong());
		made.chaseInCombo(cfg.chaseInCombo);
		made.approachHops(cfg.hopRule);
		made.counterCrit(cfg.counterCritRule);
		made.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
		made.edgeCrit(cfg.edgeCritRule);
		made.comboJump(cfg.comboJumpRule);
		made.comboJumpChargeMin(cfg.comboJumpChargeMin);
		made.clashJump(cfg.clashJumpRule);
		return made;
	}

	/** Follows each jump the chase rule makes from the take-off to the landing; see {@link #chaseEndCrit}. */
	private void watchChase() {
		if (chaseWatch) {
			if (facts.hitsDealt > 0) {
				if (facts.critDealt) {
					chaseEndCrit++;
				} else {
					chaseEndHit++;
				}
				chaseWatch = false;
			} else if (!groundAtAction) {
				chaseAir = true;
				if (facts.falling) {
					chaseNear |= facts.reachDist <= REACH;
					chasePick |= crosshairOnOpponent();
				}
			} else if (chaseAir || facts.hitsTaken > 0) {
				if (chasePick) {
					chaseEndNoSwing++;
				} else if (chaseNear) {
					chaseEndNoAim++;
				} else {
					chaseEndFar++;
				}
				chaseWatch = false;
			}
		}
		if (ruleJumpAtAction == JumpIn.CHASE || ruleJumpAtAction == JumpIn.HOP) {
			chaseWatch = true;
			chaseAir = false;
			chaseNear = false;
			chasePick = false;
		}
	}

	/**
	 * A scripted opponent drawn afresh: how quick it is, how well it aims, how near it likes to be,
	 * how often it jumps, backs off, is late with a swing. See {@code StageSpec.randomShare}.
	 *
	 * @param whole also how it attacks: whether it comes in for critical hits, stays on top of them, or keeps pushing in
	 */
	private void randomise(BotProfile b, boolean whole) {
		b.reactionTicks = 2 + rng.nextInt(8);
		b.aimNoiseDeg = 1.5f + 6f * rng.nextFloat();
		b.turnRateDeg = 20f + 25f * rng.nextFloat();
		b.preferredRange = 2.2f + 0.8f * rng.nextFloat();
		b.jumpChance = rng.nextFloat() < 0.3f ? 0.04f * rng.nextFloat() : 0f;
		b.wtap = rng.nextBoolean();
		b.dodgeChance = 0.4f * rng.nextFloat();
		b.swingSlip = 0.5f * rng.nextFloat();
		b.attackChargeMin = 0.85f + 0.15f * rng.nextFloat();
		if (whole) {
			b.critChance = rng.nextBoolean() ? 0.8f * rng.nextFloat() : 0f;
			b.critPress = rng.nextFloat();
			b.rushChance = rng.nextFloat() < 0.3f ? 1f : 0f;
		}
	}

	/** The rule's swing was not carried out: at the click the opponent was no longer under the crosshair. Nothing was swung. */
	void answerCancelled() {
		if (answerAtAction) {
			answerAtAction = false;
			clicked = false;
			action[ActionSpace.ATTACK] = 0;
			forcedAnswers--;
		}
	}

	/** How far back the weapon has to be before the attack key is the network's; more on someone's server. */
	float swingMinCharge() {
		return cfg.swingMinCharge;
	}

	/** The least charge for a swing a rule makes; see {@code Config.ruleSwingCharge}. */
	float ruleSwingCharge() {
		return Math.max(swingMinCharge(), cfg.ruleSwingCharge);
	}

	/** The player the running fight is a copy of, if it is one; see {@link #KIND_PERSON}. */
	private dev.swordai.core.curriculum.PersonStyle episodePerson;
	/** The players there are copies of, read again now and then: the user may be fighting meanwhile. */
	private java.util.List<dev.swordai.core.curriculum.PersonStyle> people = java.util.List.of();
	private long peopleReadAt;

	/**
	 * A player to fight a copy of, or null if there is none: those it lost rounds to come up most, and
	 * those it beat by little next. Rounds lost count three times, and each player counts at least once.
	 */
	private dev.swordai.core.curriculum.PersonStyle pickPerson() {
		long now = System.currentTimeMillis();
		if (now - peopleReadAt > 600_000L) {
			people = StyleWatch.known();
			peopleReadAt = now;
		}
		if (people.isEmpty()) {
			return null;
		}
		double total = 0;
		double[] weight = new double[people.size()];
		for (int i = 0; i < weight.length; i++) {
			dev.swordai.core.curriculum.PersonStyle p = people.get(i);
			double close = p.hitsDealt > 0 ? Math.min(2.0, (double) p.hitsTaken / p.hitsDealt) : 1.0;
			weight[i] = 1.0 + 3.0 * p.losses + 2.0 * close;
			total += weight[i];
		}
		double r = rng.nextDouble() * total;
		for (int i = 0; i < weight.length; i++) {
			r -= weight[i];
			if (r <= 0) {
				return people.get(i);
			}
		}
		return people.get(people.size() - 1);
	}

	/** One of the counts of the scripted opponent that plays on its rhythm, cleared as it is read. */
	private static int timerCount(int which) {
		return dev.swordai.server.BotBrain.TIMER_COUNTS.getAndSet(which, 0);
	}

	private int keysOfTheFight() {
		if (!episodeLockedDrill) {
			return stage.headMask;
		}
		if (critDrillKind == Curriculum.DRILL_PUNISH) {
			// No jump either: it is in the air when the opponent's hit has put it there, and then it has the swing.
			return groundAtAction ? 0 : PUNISH_DRILL_KEYS;
		}
		if (groundAtAction) {
			return LOCKED_DRILL_GROUND_KEYS;
		}
		return drillJumpOutOfZone ? 0 : LOCKED_DRILL_KEYS;
	}

	/**
	 * What the opponent's connection takes one way in an ordinary fight, in ticks: from none to
	 * {@code most}, short ones more often than long ones. With two ticks at the most, four fights in
	 * ten get none (sparring at home), four get one and two get two (200 ms).
	 */
	private int drawPathLag(int most) {
		return (int) Math.round(most * Math.pow(rng.nextDouble(), 1.5));
	}

	/** Whether the running fight is the first crit drill with the fighter held: the one that teaches where to jump. */
	private boolean heldJumpDrill() {
		return episodeLockedDrill && critDrillKind == Curriculum.DRILL_RANGE;
	}

	/**
	 * Hands the learner what the current stage is doing to get a move tried: the pushes for the fight
	 * about to begin.
	 *
	 * @param ownStage false for a fight of an earlier stage, which is played as that stage is: without a push
	 */
	private void setLessons(boolean ownStage) {
		learner.setJumpNudge(ownStage ? curriculum.jumpNudge() : 0f);
		// Where backing out of a combo is a rule there is nothing left to push for; see Config.backRule.
		// A rule in every fight of a stage that has it, also one replayed from a later stage: that is
		// how a fight against a person is fought. (Up to the first evening against people it was a rule
		// in the current stage's own fights only, so in self-play every scripted opponent was met
		// without it, and with it for the first time by whoever it then played against.)
		backRuleNow = cfg.backRule && stage != null && stage.backNudgeKeep > 0f;
		// Nor where it jumps with the hit instead: S is then for the hit that finds it in the air, or for nothing.
		learner.setBackNudge(ownStage && !backRuleNow && cfg.jumpResetChance <= 0f ? curriculum.backNudge() : 0f);
	}

	/**
	 * How the agent gets to see its opponent in the fight that is about to begin: this many ticks
	 * late, and with this chance on each tick of nothing new for one to {@code stallTicks} ticks.
	 * Nothing to do where the opponent is a person on a server: there the lag is real.
	 */
	void setAgentLag(int ticks, float stallChance, int stallTicks) {
	}

	/**
	 * Whether the body's own game has learned of a hit on it since the last tick: it has the knockback
	 * and its feet are still on the ground. What a jump made with the hit goes by ({@link JumpReset}).
	 * A body on the server knows on the tick it is shown; the user's character a moment later, when
	 * its game has been told, and that is the tick a fight on someone's server goes by.
	 */
	boolean bodyHitNow() {
		return JumpReset.hitOnThisTick(obs.vec);
	}

	/** How many times it found an opponent to be one that answers its hits on the way down; see {@link StepBack#wary}. */
	private int waryBegan;
	/** Ticks with the opponent under the crosshair within reach and the sword all the way back; on how many of them no swing was made, and of those how many by the rule that keeps it for the way down. */
	private int pickTicks;
	private int pickNoSwing;
	private int pickKept;
	/** Ticks on which it ran in at an opponent that was on its way up, and the swings made there; see {@link MeetJump}. */
	private int meetTicks;
	/** See RewardWeights.jumpInReady: this step's jump in at it, if one began, and whether one is under way. */
	private int jumpInAtAction;
	private boolean jumpInSeen;
	private int jumpInQuiet;
	/** Jumps in at it since the last update: with the sword down [0], with it ready [1]. */
	private final int[] jumpIns = new int[2];
	private int meetSwings;
	/** Ticks it held S out of the way of a jump made at it, its sword not back; see MeetJump.dodges. */
	private int dodgeTicks;
	/** Ticks by how far they got through what {@link MeetJump#applies} asks; see {@link MeetJump#how}. */
	private final int[] meetHow = new int[5];
	/**
	 * Spacing: on its feet, the opponent in view and within six blocks, no hit either way for six
	 * ticks, the distance kept (as the critic measures it) with the opponent's sword back and with it
	 * on its way back; see {@code ObsSpec.SC_OPP_CHARGE}.
	 */
	private long spaceReadyTicks;
	/** Ticks the strafe keys were let go of for a straight run at an opponent out of reach; see {@link StraightIn}. */
	private long straightTicks;
	private double spaceReadySum;
	private long spaceCoolTicks;
	private double spaceCoolSum;
	/**
	 * Spacing as a player sees it: the times it walked into reach (W held, the opponent in view) with the
	 * opponent's sword back [0] and with it on its way back [1]; against the 0.14.0 model alone [2], [3].
	 * Spacing is the second more than the first. The distance kept above cannot show it: right after the
	 * opponent's hit its sword is on its way back and the fighter is knocked away, so it reads farther.
	 */
	private final int[] walkedIn = new int[4];
	/**
	 * The share of rounds it has been losing to each kind of scripted opponent lately (by kind, smoothed
	 * over updates; a half until there is anything to go by). See {@code Config.hardOpponents}.
	 */
	private final float[] kindLoss = initialKindLoss();
	/** No kind comes up less than this share of what the stage gives it, however it fares against it. */
	static final float HARD_FLOOR = 0.35f;
	static final float HARD_CAP = 2f;
	private static final String[] KIND_NAMES = {"plain", "trader", "runner", "circler", "corner", "no-knockback", "odd", "jumper",
			"punisher", "backer", "timer", "people", "spacer", "whiffer", "outside", "jump-in"};
	private float spaceLastReach = 99f;
	/** The opponent's sword when the step being scored was chosen, and on the tick before; see StepFacts.walkedInOnCooldown. */
	private float oppChargeAtAction = 1f;
	private float spaceLastOppCharge = 1f;
	/** The spacing reward was paid since the opponent's last swing. */
	private boolean spacePaid;
	/** Ticks with the opponent five blocks off or more, by what kept it from a running jump; see {@link JumpIn#hopWhy}. */
	private final int[] hopWhy = new int[7];
	private int hitNowSame;
	private int hitNowLater;
	private int hitNowAlone;
	private boolean hitNowAwaited;

	/** How far the weapon has recharged, 0 to 1, as the body's own client counts it. */
	abstract float bodyCharge();

	abstract float bodyPitch();

	/** The body's yaw in degrees, unwrapped: for the turn a person makes (DemoPlan). Only the screen's body has one. */
	float personYaw() {
		return 0f;
	}

	// ---- the user plays and is recorded (DemoPlan) ------------------------------------------------

	/**
	 * The user's record key or {@code /swordai demo record|pause}. On: recording begins with the next fight
	 * (the fights before are warm-ups and are not kept). Off: nothing more is kept, nor the fight under way.
	 */
	void demoRecord(boolean on) {
		if (on) {
			if (demoRecording || demoArmed) {
				say(demoRecording ? "Already recording." : "Recording begins with the next fight.");
				return;
			}
			if (phase == Phase.RUN && episodeTick < DEMO_GRACE_TICKS) {
				// Pressed as the fight began: this fight is kept, from its start (every step is held until it ends).
				demoRecording = true;
				say("Recording this fight and the ones after it.");
				return;
			}
			demoArmed = true;
			say("Recording begins with the next fight.");
		} else {
			boolean was = demoRecording || demoArmed;
			demoRecording = false;
			demoArmed = false;
			demoRec.clear();
			say(was ? "Recording paused; this fight is not kept. " + demoFights + " recorded so far." : "Not recording.");
		}
	}

	/** A fight broken off while it was recorded: kept as far as it went. */
	private void demoSave() {
		String opponent = kindName(episodeKind) + (episodeStander ? " (stands)" : "");
		try {
			demoRec.write(demo.nextFile(opponent), opponent);
			demoFights++;
		} catch (IOException e) {
			demoRec.clear();
			SwordAi.LOGGER.warn("could not save a recorded fight", e);
		}
	}

	/** Whether the record key turns recording off (it is on, or about to be) rather than on. */
	boolean demoRecordingOrArmed() {
		return demoRecording || demoArmed;
	}

	/**
	 * At the next decision: the turn the user made since the step being recorded began, as the yaw and
	 * pitch the network would have had to choose for it, worked back through the hand model; the hand
	 * then carries on as if it had, so that what the network feels of its hand matches the camera.
	 */
	private void demoTurned() {
		if (!hasLastAction) {
			// A new fight, or one taken up again after a pause: the step begun before is not scored, and what is
			// held of a fight that was broken off is kept as far as it went if it was being recorded.
			demoRec.drop();
			if (demoRec.size() > 0) {
				if (demoRecording) {
					demoSave();
				} else {
					demoRec.clear();
				}
			}
			if (demoArmed) {
				demoArmed = false;
				demoRecording = true;
				say("Recording this fight and the ones after it.");
			}
			return;
		}
		float yawTurn = net.minecraft.util.Mth.wrapDegrees(personYaw() - demoYaw);
		float pitchTurn = bodyPitch() - demoPitch;
		lastYawTurn = yawTurn;
		lastPitchTurn = pitchTurn;
		if (!demoRec.pending()) {
			return;
		}
		int[] a = demoRec.action();
		a[ActionSpace.YAW] = dev.swordai.core.rl.HumanAction.turnBin(ActionSpace.YAW_BINS, motor.yawPush(), yawTurn, cfg.aimResponse);
		a[ActionSpace.PITCH] = dev.swordai.core.rl.HumanAction.turnBin(ActionSpace.PITCH_BINS, motor.pitchPush(), pitchTurn, cfg.aimResponse);
		motor.step(ActionSpace.yawDegrees(a), ActionSpace.pitchDegrees(a));
		System.arraycopy(a, 0, lastAction, 0, ActionSpace.HEADS);
	}

	/** Mid-tick: the keys the user holds and whether they clicked, for the step being recorded if one is. Returns whether to swing now. */
	boolean demoKeys(boolean w, boolean s, boolean a, boolean d, boolean sprint, boolean jump, boolean attack) {
		int[] keys = demoRec.pending() ? demoRec.action() : demoUnrecorded;
		dev.swordai.core.rl.HumanAction.keys(keys, w, s, a, d, sprint, jump, attack);
		System.arraycopy(keys, 0, lastAction, 0, ActionSpace.HEADS);
		clicked = attack;
		return attack;
	}

	/** A fight is over: written if it is being recorded, and the user told how it went. */
	private void demoFightOver() {
		String opponent = kindName(episodeKind) + (episodeStander ? " (stands)" : "");
		String how = String.format(Locale.ROOT, "you dealt %.0f, took %.0f%s", episodeDealt, episodeTaken,
				facts.koDealt ? ", won" : facts.koTaken ? ", lost" : "");
		if (!demoRecording) {
			demoRec.clear();
			demoWarmups++;
			say(String.format(Locale.ROOT, "Warm-up %d vs %s: %s. Not recorded%s.", demoWarmups, opponent, how,
					demoArmed ? "; recording begins with the next fight" : " - press " + SwordAiClient.recordKeyName() + " or /swordai demo record when you are ready"));
			return;
		}
		demoFights++;
		if (facts.koDealt) {
			demoWon++;
		} else if (facts.koTaken) {
			demoLost++;
		}
		try {
			demoRec.write(demo.nextFile(opponent), opponent);
		} catch (IOException e) {
			demoRec.clear();
			say("That fight could not be saved: " + e.getMessage());
			return;
		}
		say(String.format(Locale.ROOT, "Fight %d vs %s recorded: %s. Won %d, lost %d so far. %s pauses, /swordai demo stop ends.",
				demoFights, opponent, how, demoWon, demoLost, SwordAiClient.recordKeyName()));
	}

	/** On stopping: a fight under way that is being recorded is kept as far as it went. */
	void finishDemo() {
		if (demoRec == null) {
			return;
		}
		demoRec.drop();
		if (demoRecording && demoRec.size() > 0) {
			demoSave();
		}
		SwordAi.LOGGER.info("demo: {} fights recorded ({} won, {} lost), {} warm-ups, against {}", demoFights, demoWon, demoLost, demoWarmups, demo.kind);
	}

	int demoFights() {
		return demoFights;
	}

	/** Whether the body stands on the ground: a jump key pressed now is a jump. */
	abstract boolean bodyOnGround();

	/** Whether the server has the agent sprinting: what its next hit is judged with. */
	abstract boolean serverSprinting(Truth truth);

	/** Ground truth about where the crosshair is; see {@code Sightline}. */
	abstract void fillGeometry(StepFacts facts);

	/**
	 * Writes the observation for the current tick, from the body's newest picture.
	 *
	 * @param lastActionOrNull the action taken on the previous tick, or null at the start of an episode
	 */
	abstract void fillObs(Obs obs, Truth truth, int[] lastActionOrNull);

	/** How late the agent sees, for the per-update log (21 Oct: whole runs went much better or worse, for either network). */
	String timingLine() {
		return "";
	}

	/** True when the last observation was built from a picture taken before the camera's latest move. */
	abstract boolean frameLate();

	/** Whether the last action's swing had the opponent under the crosshair and in reach. */
	abstract boolean clickWasOnTarget();

	/**
	 * Carries the action out: the camera turns by the given degrees, the keys are held, and a swing is
	 * made if the action has one. Sets {@link #lastYawTurn} and {@link #lastPitchTurn} to what the
	 * camera really turned.
	 */
	abstract void applyAction(int[] action, float yawRate, float pitchRate);

	/** How old the picture is when the agent decides on it, in ticks. */
	abstract float frameAgeTicks();

	/** Mean time it took to get a picture since the last update, in milliseconds; 0 where it takes none. */
	abstract float captureMillis();

	/** Whether this trains without the screen. */
	boolean headless() {
		return false;
	}

	/** The speed asked for, as a multiple of normal. */
	abstract float wantedSpeed();

	/** The speed has been set: whatever else has to keep pace is told here. */
	void onSpeed(float speed) {
	}

	/** The network starts learning from what it has gathered, or has just finished; the fights wait meanwhile. */
	void onUpdating(boolean started) {
	}

	/** After every update, once its numbers are in the history. */
	void afterUpdate() {
	}

	/** A line about the picture for the status, or null. */
	String pictureLine() {
		return null;
	}

	// ---- the opponent: the arena's, unless the subclass fights a person -------------------------------

	/** Moves what has happened in the fight since the last step into {@code facts}. */
	void drainCombat(StepFacts facts) {
		CombatLog.drain(facts);
	}

	/**
	 * The hits of its own that the network is told of on this step: its "I have just hit" input, and
	 * the end of the opponent's combo as it counts that. In the arena that is what the server has
	 * confirmed, which is there one tick after the swing. See {@code FightLearnSession} for why a
	 * fight on someone else's server cannot wait for the confirmation.
	 */
	int hitsDealtAsShown(StepFacts facts) {
		// What it is told in a fight on a server, where there is nobody to ask: its own swing had the
		// opponent under the crosshair and in reach, as its own game saw it. The same in training, so
		// that the input means one thing everywhere. (Up to 0.13.3 training went by what the server
		// confirmed. That comes on the same tick and is nearly always the same thing; it differs for a
		// swing that reaches an opponent still flashing from the last hit.)
		return facts.clickOnTarget ? 1 : 0;
	}

	/**
	 * The least share of a batch's returns the critic has to explain for the actor to learn from that
	 * batch. Nothing is asked in the arena. Against people it is: those fights are few, short and
	 * unlike anything the critic was trained on, and when it has no idea what a fight was worth, what
	 * the actor would learn from it is noise (0.13.2, fights on a server: the critic explained less
	 * than nothing of four batches in a row, and the actor was moved by each of them several times as
	 * far as a training update moves it).
	 */
	float actorNeedsExplained() {
		return Float.NEGATIVE_INFINITY;
	}

	/** Where what is learned is saved: the checkpoint, except where the proven network is kept apart; see SafeLearn. */
	Path learnedFile() {
		return SwordFiles.checkpoint();
	}

	/** Goes up whenever the opponent was put somewhere else, so that nothing is compared across the jump. */
	int hopId(Truth truth) {
		return truth.hopId();
	}

	/** The most ticks the running episode may last. */
	int episodeLimit() {
		return episodeDrill ? stage.drillTicks : episodeCritDrill ? CRIT_DRILL_TICKS : episodeKind == KIND_JUMPIN ? cfg.jumpInTicks : stage.episodeTicks;
	}

	/**
	 * Whether these fights are the stage's own: counted towards passing it, and judged. Fights against
	 * a person are learned from and nothing more.
	 */
	boolean stageFights() {
		return true;
	}

	/** Who the fights are against, as the history records it: see {@link UpdateRecord#opponent}. */
	int opponentKind() {
		return UpdateRecord.OPPONENT_STAGE;
	}

	/**
	 * Whether the fight goes on when an episode ends without it being over. In the arena it never does:
	 * the fighters are put back. A person fights on, so the next episode begins on the same tick.
	 */
	boolean fightGoesOn() {
		return false;
	}

	/**
	 * Whether the fight goes on while the network learns from what it has gathered. A person does not
	 * wait, so there a copy of the network plays meanwhile, and those steps are not learned from.
	 */
	boolean playsOnWhileLearning() {
		return false;
	}

	// ---- queries used by commands, mixins and the report ----------------------------------------

	public Mode mode() {
		return mode;
	}

	@Override
	public boolean stopped() {
		return phase == Phase.STOPPED;
	}

	public boolean resumed() {
		return resumed;
	}

	public Curriculum curriculum() {
		return curriculum;
	}

	public List<UpdateRecord> history() {
		return history;
	}

	public Learner learner() {
		return learner;
	}

	public Config config() {
		return cfg;
	}

	/** The result of the vision check, where there is one. */
	public String selfTestSummary() {
		return null;
	}

	public String lastError() {
		return lastError;
	}

	public String phaseName() {
		return phase.name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	@Override
	public String describe() {
		return mode + ", " + phaseName() + (resumed ? " (resumed from checkpoint)" : " (fresh start)");
	}

	/** Jumps to a stage by hand (zero-based). The critic gets a moment to adjust, as after a promotion. */
	public void setStage(int index) {
		curriculum.setStage(index);
		criticOnlyLeft = cfg.criticWarmupUpdates;
		tally.clear();
		traderTally.clear();
		slowCount.clear();
		quickCount.clear();
		drillTally.clear();
		saveCurriculum();
	}

	/** Sets the speed the world runs at from here on, and tells everything that has to keep pace. */
	void applySpeed(float wanted) {
		float s = Math.max(1f, wanted);
		speed = s;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.setSpeed(s));
		}
		onSpeed(s);
	}

	/** A line about how fast it is going, or null before that is known. */
	String paceLine() {
		if (mode != Mode.TRAIN || stepsPerSecond <= 0f) {
			return speed > 1f ? String.format(Locale.ROOT, "speed: set to x%.1f", speed) : null;
		}
		return String.format(Locale.ROOT, "speed: set to x%.1f; over the last update cycle it gathered %.0f steps a second, x%.1f of real time (learning included)",
				speed, stepsPerSecond, stepsPerSecond / 20f);
	}

	// ---- the per-tick pieces every body uses ----------------------------------------------------------

	/** Whether the fight about to begin starts with the opponent's combo already on: the agent is held until its first hits have landed. */
	boolean headStart() {
		return openerHits > 0;
	}

	/** Combo drill: follows the hits the copy lands on the held agent; true once it has landed them all. */
	boolean openerLanded() {
		int taken = CombatLog.hitsTakenSoFar();
		if (taken > openerSeen) {
			openerSeen = taken;
			openerSinceHit = 0;
		} else {
			openerSinceHit++;
		}
		return taken >= openerHits;
	}

	/** The agent gets its keys: the episode proper begins. */
	void startRun(Truth truth) {
		resetSight();
		restHand();
		if (stage.ideas) {
			// A plan only holds swings back in a stage that scores it.
			idea.allowHolds(stage.reward.mixTiming > 0f);
			idea.start(rng);
		} else {
			idea.clear();
		}
		lastHopId = truth.hopId();
		// Nothing that happened while it was held is scored.
		int taken = CombatLog.clearCountingTaken();
		if (headStart() && taken > 0) {
			// It starts that many hits into the opponent's combo, and knows as much of it as it would in
			// a fight: how long ago the last hit was, and that it has not jumped since.
			rewards.startComboed(stage, taken, episodeTally());
			clock.ticksSinceHitTaken = openerSinceHit;
			clock.takenStreak.start(taken);
			jumpedSinceHitTaken = false;
		}
		phase = Phase.RUN;
	}

	void restHand() {
		motor.reset();
		lastYawTurn = 0f;
		lastPitchTurn = 0f;
	}

	/**
	 * One decision, once the body has its new picture: scores the step chosen a tick ago, then chooses
	 * and carries out the next. The same for every body.
	 */
	void step(Truth truth) {
		// Only a fight that does not wait gets here while the network is learning: then a copy of it
		// chose the step, and nothing about that step is scored or stored. Nor is the first one after.
		// In a paired evaluation the other network's fights are scored like any (their damage, their end), only
		// not learned from; see PairedEval. In safe learning its fights are the session's to count.
		boolean unscored = pendingUpdate != null || resuming || byBase && paired == null;
		resuming = false;
		if (demo != null) {
			demoTurned();
		}
		// The plan moves on once per decision; the step being scored below is held against the plan
		// as it stood when that step was chosen.
		if (stage.ideas) {
			idea.tick(rng, bodyCharge());
		}

		// ---- score the step that was decided last tick --------------------------------------
		facts.clear();
		fillGeometry(facts);
		facts.serverSprinting = serverSprinting(truth);
		facts.onGround = bodyOnGround();
		clock.ticksSinceHitDealt++;
		clock.ticksSinceHitTaken++;
		if (hasLastAction) {
			int hop = hopId(truth);
			boolean hopped = hop != lastHopId;
			lastHopId = hop;
			facts.discontinuity = hopped;
			facts.clicked = clicked;
			facts.clickOnTarget = clickWasOnTarget();
			facts.chargeAtClick = chargeAtClick;
			facts.charge = bodyCharge();
			facts.forwardAxis = ActionSpace.forwardAxis(lastAction);
			facts.strafeAxis = ActionSpace.strafeAxis(lastAction);
			facts.sprintKey = lastAction[ActionSpace.SPRINT] == 1;
			facts.yawTurnDeg = lastYawTurn;
			facts.pitchTurnDeg = lastPitchTurn;
			facts.agentPitchDeg = bodyPitch();
			facts.jumpKey = lastAction[ActionSpace.JUMP] == 1;
			facts.tookOff = facts.jumpKey && groundAtAction;
			facts.ruleJump = facts.tookOff ? ruleJumpAtAction : 0;
			facts.stepBack = stepBackAtAction;
			facts.jumpWindow = jumpWindowAtAction;
			facts.reachAtAction = reachAtAction;
			facts.oppCharge = oppChargeAtAction;
			facts.walkedInOnCooldown = !spacePaid && facts.enemyVisible && reachAtAction > REACH && facts.reachDist <= REACH
					&& facts.forwardAxis > 0 && oppChargeAtAction < StepFacts.OPP_READY;
			if (facts.walkedInOnCooldown) {
				spacePaid = true;
			}
			facts.oppJumpIn = jumpInAtAction;
			facts.walkedInOnReady = facts.enemyVisible && reachAtAction > REACH && facts.reachDist <= REACH
					&& facts.forwardAxis > 0 && oppChargeAtAction >= StepFacts.OPP_READY;
			facts.critOpen = critOpenAtAction;
			facts.knockedUp = knockedUpAtAction;
			facts.inAir = !groundAtAction;
			facts.critZone = heldJumpDrill() ? episodeJumpZone : 0f;
			// Held in place for the punish drill, being hit is the drill's doing and not its own.
			facts.cannotAvoid = episodeLockedDrill && critDrillKind == Curriculum.DRILL_PUNISH;
			facts.ideaOn = ideaOnAtAction;
			facts.ideaStrafe = ideaStrafeAtAction;
			facts.ideaCircle = ideaCircleAtAction;
			facts.ideaHold = ideaHoldAtAction;
			drainCombat(facts);
			if (facts.jumpKey) {
				jumpedSinceHitTaken = true;
				ticksSinceJumpKey = 0;
			} else if (ticksSinceJumpKey < 1000) {
				ticksSinceJumpKey++;
			}
			// An uppercut is a rising sprint hit from its own jump, not from being knocked upwards.
			facts.uppercut = facts.uppercut && ticksSinceJumpKey <= UPPERCUT_JUMP_TICKS;
			// A punish crit: a critical hit made while still in the air from the opponent's hit, with no
			// jump of its own in between.
			facts.punishCrit = facts.critDealt && !jumpedSinceHitTaken && clock.ticksSinceHitTaken <= PUNISH_WINDOW_TICKS;
			int shownDealt = hitsDealtAsShown(facts);
			if (shownDealt > 0) {
				clock.ticksSinceHitDealt = 0;
			}
			if (facts.hitsTaken > 0) {
				clock.ticksSinceHitTaken = 0;
				jumpedSinceHitTaken = false;
			}
			clock.takenStreak.tick(shownDealt, facts.hitsTaken);

			if (unscored) {
				fillObs(nextObs, truth, lastAction);
				nextObs.task = episodeTask();
				if (frameLate()) {
					lateDecisions++;
				}
			} else {
				Tally target = episodeTally();
				int episodeTicks = episodeLimit();
				// A fight to the knockout that reaches its time limit is a draw, and ends there. A drill is
				// not such a fight: it is simply over.
				facts.timedOut = !episodeDrill && !episodeCritDrill && episodeKind != KIND_JUMPIN && stage.koEndsEpisode && !facts.koDealt && !facts.koTaken && episodeTick + 1 >= episodeTicks;
				float reward = rewards.step(stage, facts, target);
				episodeDealt += facts.damageDealt;
				episodeTaken += facts.damageTaken;
				episodeTicksCounted++;
				if (target == tally || target == traderTally) {
					(episodeSlow ? slowCount : quickCount).add(facts, reward);
					if (episodeFair) {
						fairCount.add(facts, reward);
					}
				}
				episodeTick++;
				clock.combo = rewards.combo();
				clock.episodeFraction = Math.min(1f, episodeTick / (float) Math.max(1, episodeTicks));

				boolean goesOn = fightGoesOn();
				boolean terminal = stage.koEndsEpisode && (facts.koDealt || facts.koTaken || facts.timedOut);
				boolean exchanged = episodeKind == KIND_JUMPIN && jumpInStep(facts);
				boolean cut = episodeTick >= episodeTicks || exchanged
						|| rewards.lostTooLong(stage)
						|| (mode == Mode.TRAIN && learner.rolloutRoom() <= 1)
						|| cutNow
						// A fight that goes on anyway is cut as soon as there is enough to learn from.
						|| (goesOn && mode == Mode.TRAIN && learner.rolloutSize() + 1 >= wantedRollout());
				cutNow = false;
				fillObs(nextObs, truth, lastAction);
				nextObs.task = episodeTask();
				if (frameLate()) {
					lateDecisions++;
				}
				if (demo != null) {
					demoRec.complete(reward, terminal);
				} else if (mode == Mode.TRAIN && !byBase) {
					learner.complete(reward, terminal, cut && !terminal, nextObs);
				}
				if (demo != null && (terminal || cut) && !goesOn) {
					demoFightOver();
				}
				if (terminal || cut) {
					if (episodeKind == KIND_JUMPIN) {
						jumpInEnd();
					}
					if (paired != null && !episodeDrill && !episodeCritDrill) {
						// Every fight of a paired evaluation, the old model's too, with its pair and side; see PairedEval.
						String kind = episodeVsOldModel ? "the 0.14.0 model" : episodeVsOlder ? "an older copy" : episodeStander ? "stander" : kindName(episodeKind);
						SwordAi.LOGGER.info(String.format(Locale.ROOT, "paired result %d %s: %s, dealt %.1f, taken %.1f, ticks %d%s", PairedEval.pair(evalFights - 1),
								baseActs ? "B" : "A", kind, episodeDealt, episodeTaken, episodeTicksCounted, terminal && !facts.timedOut ? ", knockout" : ""));
					}
					if (episodeDrill || episodeCritDrill) {
						// It started from behind, or its opponent stood still: who wins a drill says nothing about who wins a fight.
						rewards.endEpisode(target, false, false);
					} else if (episodeVsOldModel) {
						// Its own count: whether it has found the old model's timing, and wins.
						rewards.endEpisode(target, false, false);
						if (facts.koDealt) {
							oldModelWins++;
						} else if (facts.koTaken) {
							oldModelLosses++;
						}
					} else if (episodeVsOlder) {
						// Counted on its own: beating an older copy says something else than beating the newest.
						rewards.endEpisode(target, false, false);
						if (!onEqualTerms()) {
							// Not a fight that says which of the two is the better; see onEqualTerms.
						} else if (facts.koDealt) {
							target.oldWins++;
						} else if (facts.koTaken) {
							target.oldLosses++;
						}
					} else {
						boolean counts = onEqualTerms();
						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);
						if (demo == null) {
							noteCase(terminal && !facts.timedOut);
						}
						if (episodeFair && facts.koDealt) {
							fairWon++;
						} else if (episodeFair && facts.koTaken) {
							fairLost++;
						}
					}
					boolean update = mode == Mode.TRAIN && learner.rolloutReady(curriculum.current().rolloutSteps);
					if (!goesOn) {
						releaseKeys();
						if (update) {
							startUpdate();
						}
						if (!update || playsOnWhileLearning()) {
							beginEpisode(truth);
						}
						return;
					}
					// The fight goes on: the next episode begins on this very tick, from the picture
					// just taken, and the body keeps its keys.
					episodeTick = 0;
					clock.episodeFraction = 0f;
					if (update) {
						startUpdate();
					}
				}
			}
			Obs swap = obs;
			obs = nextObs;
			nextObs = swap;
		} else {
			lastHopId = hopId(truth);
			fillObs(obs, truth, null);
			obs.task = episodeTask();
		}

		// ---- decide and act ------------------------------------------------------------------
		chargeAtClick = bodyCharge();
		reachAtAction = facts.reachDist;
		groundAtAction = bodyOnGround();
		jumpWindowAtAction = stage.unlocked(ActionSpace.JUMP) && JumpNudge.applies(obs.vec);
		// As things stand now, for scoring the step about to be chosen: would a swing be a critical hit
		// that lands, and is the air it is in the opponent's doing.
		critOpenAtAction = !groundAtAction && facts.falling && chargeAtClick >= JumpNudge.CRIT_CHARGE && !facts.serverSprinting
				&& facts.onBody && facts.reachDist <= REACH;
		knockedUpAtAction = !groundAtAction && !jumpedSinceHitTaken && clock.ticksSinceHitTaken <= PUNISH_WINDOW_TICKS;
		int keys = episodeKeys();
		// How it fights for the next half second, chosen by the tactics model; see Tactic. Not in the drills
		// that hold it in place, which have no use for any of it.
		// What kind of opponent this is, read off the last seconds of the fight; see OpponentReader.
		if (learner.hasReader()) {
			oppWindow.push(obs.vec);
			oppWindow.write(obs.oppWindow);
			learner.readOpponent(obs, pendingUpdate != null, baseActs);
		}
		int tactic = -1;
		// A network compared with in a paired evaluation that was saved without a tactics model fights with none.
		if (learner.hasTactics() && !episodeLockedDrill && !(baseActs && paired != null && !learner.baseHasTactics())) {
			if (tacticClock.due(obs.vec, cfg.tacticOnEvents)) {
				int chosen;
				if (baseActs) {
					chosen = learner.chooseTacticBase(obs);
				} else if (pendingUpdate != null) {
					chosen = learner.chooseTacticStandIn(obs);
				} else {
					chosen = learner.chooseTactic(obs, mode == Mode.TRAIN && demo == null);
				}
				tacticClock.set(chosen);
				int task = obs.task;
				tacticPicks[task >= 0 && task < dev.swordai.core.rl.CriticNet.TASKS ? task + 1 : 0][chosen]++;
			}
			tactic = tacticClock.current();
		}
		obs.tactic = tactic;
		// Hit and not yet having hit back, with the sword back and the opponent under the crosshair and
		// near: the swing is not the network's to leave out. The key is taken from it for the tick, as
		// by the rule about the sword's charge, and pressed. See AnswerRule.
		boolean owes = cfg.answerRule && swingMinCharge() > 0f && chargeAtClick >= ruleSwingCharge() && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent(), cfg.answerSpareBacking);
		// In the air it is kept for the way down and made as a critical hit; see AnswerRule.inTheAir.
		punishMemory.tick(obs.vec);
		// An opponent coming at it through the air, still on its way up, is run at and hit at once; see MeetJump.
		if (stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill) {
			meetHow[MeetJump.how(obs.vec, cfg.meetWhenComing)]++;
		}
		// The opponent jumping in at it: counted once a jump, with the sword ready or not; see RewardWeights.jumpInReady.
		int jumpingIn = stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill ? MeetJump.jumpIn(obs.vec) : 0;
		jumpInAtAction = 0;
		if (jumpingIn == 0) {
			if (++jumpInQuiet >= 4) {
				jumpInSeen = false;
			}
		} else {
			jumpInQuiet = 0;
			if (!jumpInSeen) {
				jumpInSeen = true;
				jumpInAtAction = jumpingIn;
				jumpIns[jumpingIn - 1]++;
			}
		}
		float oppCharge = obs.vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_OPP_CHARGE];
		if (oppCharge < spaceLastOppCharge - 0.05f) {
			// The opponent swung again: walking in on the way back of this swing is paid once more.
			spacePaid = false;
		}
		spaceLastOppCharge = oppCharge;
		oppChargeAtAction = oppCharge;
		if (facts.enemyVisible) {
			if (spaceLastReach > REACH && facts.reachDist <= REACH && facts.forwardAxis > 0) {
				int cooling = obs.vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_OPP_CHARGE] >= 0.9f ? 0 : 1;
				walkedIn[cooling]++;
				if (episodeVsOldModel) {
					walkedIn[2 + cooling]++;
				}
			}
			spaceLastReach = facts.reachDist;
		}
		if (groundAtAction && facts.enemyVisible && facts.reachDist < 6f && clock.ticksSinceHitTaken >= 6 && clock.ticksSinceHitDealt >= 6) {
			if (obs.vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_OPP_CHARGE] >= 0.9f) {
				spaceReadyTicks++;
				spaceReadySum += facts.reachDist;
			} else {
				spaceCoolTicks++;
				spaceCoolSum += facts.reachDist;
			}
		}
		// An opponent coming in through the air with its sword ready: no swing at it (its crit is let land, and punished),
		// and no running at it to knock it away, unless that is certain; see CritTrade. Followed on every decision.
		boolean holdCrit = false;
		boolean incoming = false;
		if ((cfg.holdOnFallingJumper || cfg.instantPunish) && !episodeLockedDrill && swingMinCharge() > 0f) {
			if (critTrade == null) {
				critTrade = new CritTrade(cfg.holdKnockbackDelay);
			}
			critTrade.holdOnRising(cfg.holdOnRising);
			boolean holds = critTrade.holds(obs.vec);
			holdCrit = cfg.holdOnFallingJumper && holds && ActionSpace.unlocked(keys, ActionSpace.ATTACK);
			incoming = cfg.holdOnFallingJumper && critTrade.incoming() && !critTrade.knockAway();
			if (critTrade.knockAway()) {
				holdCritKnockAways++;
			}
		}
		boolean meet = cfg.meetJumpRule && cfg.answerRule && !episodeLockedDrill && swingMinCharge() > 0f && stage.unlocked(ActionSpace.JUMP)
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)
				&& MeetJump.applies(obs.vec, cfg.meetWhenComing) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE);
		// On the ground the swing is made at once; in the air it stays the swing it would have been.
		boolean meetGround = meet && groundAtAction;
		int when = owes && cfg.punishRule && !meetGround ? AnswerRule.when(obs.vec, punishMemory.punishable(), cfg.punishInCombo && punishMemory.proven(), cfg.punishWaitOnGround, cfg.keepWithin) : AnswerRule.NOW;
		boolean answer = owes && when == AnswerRule.NOW;
		boolean keep = owes && when != AnswerRule.NOW;
		boolean letGo = owes && when == AnswerRule.LET_GO;
		// Hit by a jumper's crit (or the one it held its swing for): the punish crit at once; see CritTrade.punish.
		int punish = CritTrade.NONE;
		if (cfg.instantPunish && critTrade != null && !episodeLockedDrill && (critTrade.heldWithin(PUNISH_AFTER_HELD_TICKS) || critTrade.jumperHit())
				&& AnswerRule.owesAnAnswer(obs.vec) && (!BackNudge.inCombo(obs.vec) || punishMemory.proven())) {
			punish = critTrade.punish(obs.vec);
		}
		if (punish != CritTrade.NONE) {
			answer = owes && punish == CritTrade.SWING;
			keep = owes && !answer;
			letGo = false;
			instantPunishTicks++;
		}
		if (holdCrit && punish == CritTrade.NONE) {
			answer = false;
			keep = owes;
			letGo = false;
		}
		answerAtAction = answer;
		// With Config.answerNudge the rule pushes the network's swing instead of pressing it.
		boolean nudged = owes && cfg.answerNudge > 0f;
		if (owes && !nudged || holdCrit || punish != CritTrade.NONE) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (letGo || punish != CritTrade.NONE && punish != CritTrade.WAIT) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		// A hit of a combo it is taking: it jumps with it, W and the sprint key down; see JumpReset.
		int reset = 0;
		if (!episodeLockedDrill && stage.backNudgeKeep > 0f && cfg.jumpResetChance > 0f && stage.unlocked(ActionSpace.JUMP)) {
			if (jumpReset == null) {
				jumpReset = new JumpReset(cfg.jumpResetChance, rng.nextLong());
				jumpReset.chasers(cfg.jumpResetChasers);
				jumpReset.counters(cfg.jumpResetCounterChance);
			}
			boolean shown = JumpReset.hitOnThisTick(obs.vec);
			boolean hitNow = bodyHitNow();
			// When the body's own game learns of a hit, against when the trainer does.
			if (shown && hitNow) {
				hitNowSame++;
				hitNowAwaited = false;
			} else if (shown) {
				hitNowAwaited = true;
			} else if (hitNow) {
				if (hitNowAwaited) {
					hitNowLater++;
				} else {
					hitNowAlone++;
				}
				hitNowAwaited = false;
			}
			reset = jumpReset.plan(obs.vec, keys, hitNow);
		}
		if (reset != 0 || letGo || punish != CritTrade.NONE) {
			// The jump made with a hit, and the critical hit it is about to make, come first.
			meet = false;
		}
		// Where it does not, S is held for it in the ticks after that hit; see Config.backRule.
		boolean backOut = backRuleNow && reset == 0 && !meet && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		keys = JumpIn.keys(keys, reset);
		// After a hit of its own that has not sent the opponent away it steps back; see StepBack.
		boolean wasWary = stepBack.wary();
		if (jumpReset != null) {
			stepBack.chasedBy(jumpReset.chased());
		}
		boolean stepping = dev.swordai.core.rl.Tactic.stepsBack(tactic, cfg.stepBackRule);
		int step = (stepping || tactic >= 0) && !episodeLockedDrill && stage.unlocked(ActionSpace.JUMP)
				? stepBack.plan(obs.vec, tactic == dev.swordai.core.rl.Tactic.BACK) : 0;
		if (!stepping) {
			step = 0;
		}
		// An opponent jumping in at it with its own sword not back: out of the way of that jump; see MeetJump.dodges.
		boolean dodge = cfg.dodgeJumpRule && cfg.answerRule && !episodeLockedDrill && !meet && reset == 0 && !letGo && swingMinCharge() > 0f
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE)
				&& MeetJump.dodges(obs.vec, cfg.meetWhenComing);
		if (dodge) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
			dodgeTicks++;
		}
		// Just out of reach of an opponent whose sword is ready, it does not walk in; see EdgeHold.
		boolean held = dev.swordai.core.rl.Tactic.holdsEdge(tactic, cfg.edgeHoldRule) && !episodeLockedDrill && edgeHold.plan(obs.vec);
		boolean hold = held && !owes && !backOut && reset == 0 && !dodge && !meet && !letGo && !(cfg.clashJumpRule && incoming)
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)
				&& (jumpIn == null || jumpIn.kind() == JumpIn.NONE);
		if (hold) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
			edgeTicks++;
		}
		if (stepBack.wary() && !wasWary) {
			waryBegan++;
		}
		watchFall(keys);
		// The jumps that are made for it, and its keys in the air of them; see JumpIn. And the camera
		// is left alone on an opponent that is far away; see FarRest.
		if (facts.hitsTaken > 0) {
			dealtRow = 0;
		}
		dealtRow += facts.hitsDealt;
		int jump = 0;
		ruleJumpAtAction = 0;
		if (!episodeLockedDrill) {
			if (jumpIn == null) {
				jumpIn = newJumpIn();
			}
			jumpIn.counterCrit(dev.swordai.core.rl.Tactic.counters(tactic, cfg.counterCritRule));
			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0 || meet || dodge || hold || punish != CritTrade.NONE, dealtRow);
			if (jumpIn.hopWhy() >= 0) {
				hopWhy[jumpIn.hopWhy()]++;
			}
			keys = JumpIn.keys(keys, jump);
			ruleJumpAtAction = jumpIn.tookOff();
			if (ruleJumpAtAction == JumpIn.CLASH) {
				clashJumps++;
			}
		}
		if (jumpReset != null && jumpReset.tookOff()) {
			ruleJumpAtAction = JumpReset.KIND;
		}
		// Run at, but not hit to knock away where its crit comes down anyway: the walk in keeps it in reach for the punish
		// crit (23 Oct drill: with the walk in held back too, 0 dealt and 21 taken in 40% of the fights).
		boolean meetSwing = meet && meetGround && !owes && !holdCrit && chargeAtClick >= ruleSwingCharge() && crosshairOnOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		if (meet) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
			meetTicks++;
		}
		if (meetSwing) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (owes || backOut || reset != 0 || meet || dodge || hold || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE
				|| !ActionSpace.unlocked(keys, ActionSpace.FORWARD) || !ActionSpace.unlocked(keys, ActionSpace.SPRINT)) {
			step = 0;
		}
		if (step != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		stepBackAtAction = step != 0;
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		// And it is steered for it on an opponent that is far away; see FarRest.
		boolean camera = !episodeLockedDrill && stage.unlocked(ActionSpace.YAW) && stage.unlocked(ActionSpace.PITCH);
		// And in the air of a jump that is made for it: the hit at the end of it needs the crosshair on the opponent.
		boolean hitAim = camera && cfg.aimRule && cfg.answerRule && stage.unlocked(ActionSpace.ATTACK)
				&& (AimRule.applies(obs.vec) || jumpIn != null && jumpIn.kind() != JumpIn.NONE);
		// And with the opponent off the screen it is turned to find it; see LostRule.
		boolean lost = lostRule.applies(obs.vec) && camera && cfg.lostRule;
		boolean aim = hitAim || lost || camera && cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec);
		watchChase();
		if (aim) {
			if (aimRule == null) {
				aimRule = new AimRule(cfg.aimResponse);
				aimRule.level(cfg.aimLevel);
			}
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim && !lost);
			keys &= ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
			aimedTicks++;
		}
		// Out of reach it runs straight at the opponent, not round it; see StraightIn.
		boolean straight = cfg.straightInRule && !episodeLockedDrill && stage.unlocked(ActionSpace.STRAFE) && StraightIn.applies(obs.vec);
		if (straight) {
			keys &= ~ActionSpace.mask(ActionSpace.STRAFE);
			straightTicks++;
		}
		// From the second hit of a combo it is taking, out of its line to the side; see ComboStrafe.
		int aside = 0;
		if (cfg.comboStrafeRule && !episodeLockedDrill && stage.unlocked(ActionSpace.STRAFE)) {
			boolean was = comboStrafe.active();
			aside = comboStrafe.plan(obs.vec);
			if (aside != 0) {
				keys &= ~ActionSpace.mask(ActionSpace.STRAFE);
				comboStrafeTicks++;
				if (!was || JumpReset.hitOnThisTick(obs.vec)) {
					comboStrafeHits++;
				}
			}
		}
		// Nor a swing at an opponent plainly out of reach; see SwingGate.outOfReach.
		if (cfg.whiffGuard && !episodeLockedDrill && SwingGate.outOfReach(obs.vec)) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (demo != null) {
			// The user plays: what they do is recorded, not chosen (see DemoPlan). Their keys and click are
			// read as this tick goes on, their turn at the next decision.
			// Every step is held, recording or not: a fight is kept from its start when the user asks for it
			// early in the fight (DEMO_GRACE_TICKS), and dropped at its end if not.
			demoRec.begin(obs, keys);
			demoYaw = personYaw();
			demoPitch = bodyPitch();
			clicked = false;
			System.arraycopy(demoRec.action(), 0, lastAction, 0, ActionSpace.HEADS);
			hasLastAction = true;
			return;
		}
		learner.setSwingBias(nudged ? (answer ? cfg.answerNudge : keep ? -cfg.answerNudge : 0f) : 0f);
		if (baseActs) {
			// Learning in safety: the proven network plays this fight, as it is; see SafeLearn.
			learner.actBase(obs, keys, false, cfg.playTemperature, action);
		} else if (pendingUpdate != null) {
			// The network is busy learning; the copy kept when that began chooses, as the network would have.
			learner.actStandIn(obs, keys, false, action);
		} else {
			learner.act(obs, keys, false, mode == Mode.TRAIN, cfg.playTemperature, action);
		}
		byBase = baseActs;
		if (owes && (answer || keep)) {
			// What the network itself would do where the rule acts, before any push: logged at each update.
			float gap = learner.lastSwingGap();
			if (!Float.isNaN(gap)) {
				int k = answer ? 0 : 1;
				ruleGapSum[k] += gap;
				ruleSwingSum[k] += 1.0 / (1.0 + Math.exp(-gap));
				ruleGapCount[k]++;
				if (action[ActionSpace.ATTACK] == 1) {
					ruleSwung[k]++;
				}
				if (k == 0) {
					ruleGapBins[gap < -8f ? 0 : gap < -4f ? 1 : gap < 0f ? 2 : gap < 4f ? 3 : 4]++;
				}
			}
		}
		if (answer) {
			if (!nudged) {
				action[ActionSpace.ATTACK] = 1;
			}
			forcedAnswers++;
		}
		if (crosshairOnOpponent() && chargeAtClick >= 1f && !episodeLockedDrill) {
			pickTicks++;
			if (action[ActionSpace.ATTACK] == 0) {
				pickNoSwing++;
				if (keep) {
					pickKept++;
				}
			}
		}
		if (facts.hitsTaken > 0) {
			keptThisHit = false;
		}
		if (keep) {
			if (!nudged) {
				action[ActionSpace.ATTACK] = 0;
			}
			if (!keptThisHit) {
				keptThisHit = true;
				keptAnswers++;
			}
		}
		JumpIn.apply(action, reset);
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (dodge) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
			action[ActionSpace.SPRINT] = 0;
		}
		if (hold) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (meet) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 1;
			if (owes && answer) {
				meetSwings++;
			}
		}
		if (meetSwing) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			action[ActionSpace.ATTACK] = 1;
			answerAtAction = true;
			forcedAnswers++;
			meetSwings++;
		}
		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerAtAction = true;
			forcedAnswers++;
		}
		if (holdCrit && (jump & JumpIn.SWING) == 0) {
			action[ActionSpace.ATTACK] = 0;
			holdCritTicks++;
		}
		if (punish == CritTrade.WAIT) {
			action[ActionSpace.ATTACK] = 0;
		} else if (punish != CritTrade.NONE) {
			action[ActionSpace.FORWARD] = punish == CritTrade.LET_GO ? 0 : 1;
			action[ActionSpace.SPRINT] = 0;
			action[ActionSpace.ATTACK] = answer ? 1 : 0;
		}
		if (aim) {
			action[ActionSpace.YAW] = aimRule.yawBin();
			action[ActionSpace.PITCH] = aimRule.pitchBin();
		}
		if (straight) {
			action[ActionSpace.STRAFE] = 0;
		}
		if (aside != 0) {
			action[ActionSpace.STRAFE] = aside;
		}
		if (groundAtAction) {
			// Taking off in the drill from farther away than it wants the jump made.
			drillJumpOutOfZone = heldJumpDrill() && action[ActionSpace.JUMP] == 1 && reachAtAction > episodeJumpZone;
		}
		ideaOnAtAction = idea.on();
		ideaStrafeAtAction = idea.strafe();
		ideaCircleAtAction = idea.circling();
		ideaHoldAtAction = idea.hold();
		if (action[ActionSpace.ATTACK] == 1) {
			idea.swung(rng);
		}
		// The heads choose a turning speed; the hand model decides how much of it happens this tick.
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		clicked = action[ActionSpace.ATTACK] == 1;
		if (episodeLockedDrill) {
			// The camera is put on the opponent's head for it, wherever its jump has carried its eyes.
			applyAction(action, facts.yawErrDeg, facts.pitchErrDeg);
		} else {
			applyAction(action, motor.yawRate(), motor.pitchRate());
		}
		if (trace.on()) {
			String extra = (holdCrit ? "holdcrit " : "") + (punish == CritTrade.LET_GO ? "p-letgo " : punish == CritTrade.WALK ? "p-walk "
					: punish == CritTrade.SWING ? "p-swing " : punish == CritTrade.WAIT ? "p-wait " : "");
			trace.tick(traceLine(truth, tactic, answer, keep, meet, meetSwing, reset, aside, backOut, step, dodge, hold, jump, owes, extra));
		}
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
	}

	/** How the fight was set up, for the head of its trace. */
	private String traceSetup(EpisodePlan plan) {
		return String.format(Locale.ROOT, "its knockback %.0f%%, its connection %d ticks (seen %d, it sees %d), crit chance %.2f, reaction %d ticks%s",
				100f * plan.botKnockback, plan.botPing, plan.agentLag, plan.botLag, plan.bot.critChance, plan.bot.reactionTicks,
				episodeStander ? ", stands and hits" : plan.bot.jumpInFrom > 0f ? String.format(Locale.ROOT, ", jumps in from %.1f", plan.bot.jumpInFrom) : "");
	}

	/** One tick of the trace: the state the step was chosen in, what was chosen and by what, and what the last tick brought. */
	private String traceLine(Truth truth, int tactic, boolean answer, boolean keep, boolean meet, boolean meetSwing, int reset, int aside, boolean backOut,
			int step, boolean dodge, boolean hold, int jump, boolean owes, String extra) {
		float[] v = obs.vec;
		int sc = dev.swordai.core.rl.ObsSpec.VEC_SCALARS;
		float scale = dev.swordai.core.env.BodyState.VELOCITY_SCALE;
		float up = (v[sc + dev.swordai.core.rl.ObsSpec.SC_REL_VEL_UP] + v[sc + dev.swordai.core.rl.ObsSpec.SC_VEL_UP]) / scale;
		float toward = -(v[sc + dev.swordai.core.rl.ObsSpec.SC_REL_VEL_FORWARD] + v[sc + dev.swordai.core.rl.ObsSpec.SC_VEL_FORWARD]) / scale;
		StringBuilder keys = new StringBuilder();
		int fwd = ActionSpace.forwardAxis(action);
		int side = ActionSpace.strafeAxis(action);
		keys.append(fwd > 0 ? 'W' : fwd < 0 ? 'S' : '.');
		keys.append(side > 0 ? 'D' : side < 0 ? 'A' : '.');
		keys.append(action[ActionSpace.SPRINT] == 1 ? 'r' : '.');
		keys.append(action[ActionSpace.JUMP] == 1 ? 'J' : '.');
		keys.append(action[ActionSpace.ATTACK] == 1 ? 'X' : '.');
		StringBuilder rules = new StringBuilder();
		if (answer) rules.append("answer ");
		if (keep) rules.append("keep ");
		if (owes && !answer && !keep) rules.append("owes ");
		if (meet) rules.append(meetSwing ? "meet+swing " : "meet ");
		if (reset != 0) rules.append("jumpreset ");
		if (aside != 0) rules.append("aside ");
		if (backOut) rules.append("backout ");
		if (step != 0) rules.append("stepback ");
		if (dodge) rules.append("dodge ");
		if (hold) rules.append("edgehold ");
		if (jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE) rules.append("jumpin:").append(jumpIn == null ? 0 : jumpIn.kind()).append(' ');
		rules.append(extra);
		StringBuilder ev = new StringBuilder();
		if (facts.hitsDealt > 0) {
			ev.append(String.format(Locale.ROOT, "HIT %s%.1f ", facts.critDealt ? "CRIT " : facts.sprintHit ? "sprint " : "", facts.damageDealt));
		}
		if (facts.hitsTaken > 0) {
			ev.append(String.format(Locale.ROOT, "TAKEN %s%.1f ", facts.critsTaken > 0 ? "CRIT " : "", facts.damageTaken));
		}
		if (facts.clicked && facts.hitsDealt == 0) {
			ev.append(facts.clickOnTarget ? "swing-blocked " : "whiff ");
		}
		return String.format(Locale.ROOT, "%4d | %s | %.2f %.2f | %s%s | %+.2f %+.2f | %s | %s| %s | %s| %.0f/%.0f",
				episodeTick, facts.enemyVisible ? String.format(Locale.ROOT, "%.2f", facts.reachDist) : "  - ", chargeAtClick, truth.botCharge(),
				groundAtAction ? "G" : "a", truth.botOnGround() ? "G" : "a", up, toward, keys, rules,
				dev.swordai.core.rl.Tactic.name(tactic), ev, episodeDealt, episodeTaken);
	}

	private static float[] initialKindLoss() {
		float[] f = new float[KIND_JUMPIN + 1];
		java.util.Arrays.fill(f, 0.5f);
		return f;
	}

	/** How much more often a kind it loses this share of its rounds to comes up: twice at all lost, a third at none. */
	static float hardWeight(float loss) {
		return Math.max(HARD_FLOOR, Math.min(HARD_CAP, loss / 0.5f));
	}

	/** The stage's shares of the scripted kinds, weighted by the rounds lost to each lately, with the same total. */
	private void hardWeights(float[] shares) {
		float before = 0f;
		float after = 0f;
		for (int i = 0; i < shares.length; i++) {
			before += shares[i];
			shares[i] *= hardWeight(kindLoss[i + 1]);
			after += shares[i];
		}
		if (after > 0f) {
			for (int i = 0; i < shares.length; i++) {
				shares[i] *= before / after;
			}
		}
	}

	private Tally kindTally(int kind) {
		return switch (kind) {
			case KIND_TRADER -> traderTally;
			case KIND_RUNNER -> runnerTally;
			case KIND_CIRCLER -> circlerTally;
			case KIND_CORNER -> cornerTally;
			case KIND_NO_KNOCK -> noKnockTally;
			case KIND_ODD -> oddTally;
			case KIND_JUMPER -> jumperTally;
			case KIND_PUNISHER -> punisherTally;
			case KIND_BACKER -> backerTally;
			case KIND_TIMER -> timerTally;
			case KIND_PERSON -> personTally;
			case KIND_SPACER -> spacerTally;
			case KIND_WHIFFER -> whifferTally;
			case KIND_OUTSIDE -> outsideTally;
			case KIND_JUMPIN -> jumpInTally;
			default -> null;
		};
	}

	/**
	 * Called once the fight's plan is drawn: keeps a copy of how this fight was set up, or, for a share of
	 * the fights against a stage's scripted opponents, sets it up as one it lost before; see LostCases.
	 */
	private void setUpCase(EpisodePlan plan, boolean scripted) {
		episodeCase = null;
		episodeReplay = false;
		episodeMutant = false;
		episodeDealt = 0f;
		episodeTaken = 0f;
		episodeTicksCounted = 0;
		if (mode != Mode.TRAIN || !scripted || episodeKind == KIND_OUTSIDE || episodeKind == KIND_PERSON || episodeDrill || episodeCritDrill) {
			return;
		}
		if (lostCases == null) {
			lostCases = SwordFiles.loadLostCases();
		}
		if (episodeKind != KIND_JUMPIN && cfg.replayLostShare > 0f && rng.nextFloat() < cfg.replayLostShare) {
			lostCases.byPotential = cfg.replayByPotential;
			dev.swordai.core.curriculum.LostCases.Case c = lostCases.pick(rng, episodeStageIndex);
			if (c != null) {
				episodeCase = c;
				episodeReplay = true;
				applyCase(plan, c);
				// Exactly as it was, from the start: the same draws, the same heading. See EpisodePlan.seed.
				plan.seed = c.seed;
				if (c.hasYaw) {
					plan.startYaw = c.startYaw;
				}
				if (cfg.traceFights) {
					trace.begin(kindName(episodeKind), c.seed, true, traceSetup(plan));
				}
				return;
			}
		}
		if (episodeKind != KIND_JUMPIN && cfg.mutateShare > 0f && rng.nextFloat() < cfg.mutateShare) {
			// A fight close to one it lost or only just beat: see LostCases.mutate.
			dev.swordai.core.curriculum.LostCases.Case from = lostCases.pickToMutate(rng, episodeStageIndex);
			if (from != null) {
				applyCase(plan, dev.swordai.core.curriculum.LostCases.mutate(from, rng));
				episodeMutant = true;
			}
		}
		dev.swordai.core.curriculum.LostCases.Case c = new dev.swordai.core.curriculum.LostCases.Case();
		c.stage = episodeStageIndex;
		c.kind = episodeKind;
		c.stander = episodeStander;
		c.slow = episodeSlow;
		c.bot = plan.bot.copy();
		c.agentLag = plan.agentLag;
		c.botLag = plan.botLag;
		c.botPing = plan.botPing;
		c.agentStall = plan.agentStall;
		c.agentStallTicks = plan.agentStallTicks;
		c.botKnockback = plan.botKnockback;
		c.cornerStart = plan.cornerStart;
		// Drawn from a seed of its own, so that it can be set up exactly again if it is lost.
		long seed;
		do {
			seed = rng.nextLong();
		} while (seed == 0L);
		plan.seed = seed;
		c.seed = seed;
		episodePlan = plan;
		episodeCase = c;
		if (cfg.traceFights) {
			trace.begin(kindName(episodeKind), seed, false, traceSetup(plan));
		}
		if (cfg.evalSeed != 0) {
			plan.seedYaw = true;
			// Paired evaluation: the same fight set up for every network has the same seed at the same place.
			SwordAi.LOGGER.info("eval fight {}: {}, seed {}", evalFights, kindName(episodeKind), Long.toHexString(seed));
		}
	}

	/** Sets the fight up as that one was (the opponent, the connection, the corner), not yet its draws. */
	private void applyCase(EpisodePlan plan, dev.swordai.core.curriculum.LostCases.Case c) {
		c.bot.copyInto(plan.bot);
		plan.agentLag = c.agentLag;
		plan.botLag = c.botLag;
		plan.botPing = c.botPing;
		plan.agentStall = c.agentStall;
		plan.agentStallTicks = c.agentStallTicks;
		plan.botKnockback = c.botKnockback;
		plan.cornerStart = c.cornerStart;
		episodeKind = c.kind;
		episodeStander = c.stander;
		episodeSlow = c.slow;
		episodeTrader = episodeKind == KIND_TRADER;
		episodeRunner = episodeKind == KIND_RUNNER;
		episodeCircler = episodeKind == KIND_CIRCLER;
	}

	/** A scripted fight is over: a fresh one it lost is kept, a kept one is counted; see LostCases. */
	private void noteCase(boolean knockedOut) {
		if (episodeCase == null || lostCases == null) {
			return;
		}
		float potential = learner.takeEpisodePotential();
		if (!Float.isNaN(potential)) {
			episodeCase.potential = potential;
		}
		if (cfg.evalSeed != 0) {
			// Paired evaluation, fight by fight: the same N is the same fight for every network measured.
			if (paired == null) {
				SwordAi.LOGGER.info(String.format(Locale.ROOT, "eval result %d: %s, dealt %.1f, taken %.1f, ticks %d%s", evalFights,
						kindName(episodeKind), episodeDealt, episodeTaken, episodeTicksCounted, knockedOut ? ", knockout" : ""));
			}
		}
		if (trace.on()) {
			trace.end(SwordFiles.dir().resolve("traces"), kindName(episodeKind), episodeCase.seed, episodeDealt, episodeTaken, episodeTicksCounted);
		}
		if (episodeKind == KIND_JUMPIN) {
			// A drill of one exchange: nothing to keep.
		} else if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		} else {
			if (episodePlan != null && !Float.isNaN(episodePlan.usedYaw)) {
				episodeCase.startYaw = episodePlan.usedYaw;
				episodeCase.hasYaw = true;
			}
			lostCases.freshFight(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		}
		episodeCase = null;
	}

	/** The crit stage, where the jump-in drill is played; -1 if there is none before the current one. */
	private int jumpInStage() {
		for (int i = 0; i < curriculum.stageCount() && i <= curriculum.stageIndex(); i++) {
			if ("Crits".equals(curriculum.stage(i).name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * One step of the jump-in drill: whether its exchange is over. That is once both have hit (the fighter's crit for its
	 * crit, a hit and the crit back, or the crit let land and the punish), once one side's hit has gone unanswered for
	 * {@link #JUMPIN_SETTLE_TICKS}, or at a second hit taken without one dealt.
	 */
	private boolean jumpInStep(StepFacts f) {
		if (f.hitsTaken > 0) {
			if (jumpInTakenTick < 0) {
				jumpInTakenTick = episodeTick;
				jumpInTakenCrit = f.critsTaken > 0;
			} else if (jumpInDealtTick < 0) {
				jumpInTakenTwice = true;
				return true;
			}
		}
		if (f.hitsDealt > 0 && jumpInDealtTick < 0) {
			jumpInDealtTick = episodeTick;
			jumpInDealtCrit = f.critDealt;
		}
		if (jumpInTakenTick >= 0 && jumpInDealtTick >= 0) {
			return true;
		}
		int first = Math.max(jumpInTakenTick, jumpInDealtTick);
		return first >= 0 && episodeTick - first >= JUMPIN_SETTLE_TICKS;
	}

	/** The jump-in drill's fight is over: how its exchange went is counted. */
	private void jumpInEnd() {
		jumpInDrill[JI_FIGHTS]++;
		jumpInDealtSum += episodeDealt;
		jumpInTakenSum += episodeTaken;
		boolean dealt = jumpInDealtTick >= 0;
		boolean taken = jumpInTakenTick >= 0;
		if (dealt && taken && jumpInDealtTick == jumpInTakenTick) {
			jumpInDrill[jumpInDealtCrit && jumpInTakenCrit ? JI_CRIT_FOR_CRIT : JI_TRADE]++;
		} else if (dealt && taken && jumpInDealtTick < jumpInTakenTick) {
			jumpInDrill[JI_HIT_BACK]++;
			if (jumpInTakenCrit) {
				jumpInDrill[JI_CRIT_BACK]++;
			}
		} else if (dealt && taken) {
			jumpInDrill[JI_PUNISHED]++;
			if (jumpInDealtCrit) {
				jumpInDrill[JI_PUNISH_CRIT]++;
			}
		} else if (dealt) {
			jumpInDrill[JI_CLEAN]++;
		} else if (jumpInTakenTwice) {
			jumpInDrill[JI_TWICE]++;
		} else if (taken) {
			jumpInDrill[JI_UNANSWERED]++;
		} else {
			jumpInDrill[JI_NOTHING]++;
		}
		jumpInTakenTick = -1;
		jumpInDealtTick = -1;
		jumpInTakenCrit = false;
		jumpInDealtCrit = false;
		jumpInTakenTwice = false;
	}

	/** For the log at each update: the jump-in drill and the rules about an opponent coming in; the counts are cleared. */
	private String jumpInLine() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < JI_NAMES.length; i++) {
			b.append(i == 0 ? "" : ", ").append(JI_NAMES[i]).append(' ').append(i == JI_CLASH ? clashJumps : jumpInDrill[i]);
		}
		long n = jumpInDrill[JI_FIGHTS];
		b.append(String.format(Locale.ROOT, " | a drill fight: dealt %.1f, taken %.1f", n == 0 ? 0.0 : jumpInDealtSum / n, n == 0 ? 0.0 : jumpInTakenSum / n));
		b.append(String.format(Locale.ROOT, " | swing held for one coming in %d ticks, knock-away certain %d ticks, instant punish %d ticks",
				holdCritTicks, holdCritKnockAways, instantPunishTicks));
		java.util.Arrays.fill(jumpInDrill, 0);
		jumpInDealtSum = 0;
		jumpInTakenSum = 0;
		clashJumps = 0;
		holdCritTicks = 0;
		holdCritKnockAways = 0;
		instantPunishTicks = 0;
		return b.toString();
	}

	/** The name a kind of opponent has in the log. */
	static String kindName(int kind) {
		return kind >= 0 && kind < KIND_NAMES.length ? KIND_NAMES[kind] : "plain";
	}

	/** The chance a scripted fight is against the outside bot: none unless the game runs on Carpet PvP. */
	private float outsideChance() {
		if (cfg.outsideShare <= 0f || !ArenaServer.hasBotCommand(mc.getSingleplayerServer())) {
			return 0f;
		}
		return Math.min(0.5f, cfg.outsideShare * (cfg.hardOpponents ? hardWeight(kindLoss[KIND_OUTSIDE]) : 1f));
	}

	/** One of {@code Config.outsideLevels}, drawn. */
	private String pickOutsideLevel() {
		String[] levels = cfg.outsideLevels == null ? new String[0] : cfg.outsideLevels.split(",");
		String level = levels.length == 0 ? "" : levels[rng.nextInt(levels.length)].trim().toLowerCase(Locale.ROOT);
		return level.isEmpty() ? "skilled" : level;
	}

	/** At each update, before the tallies are cleared: the rounds lost to each kind go into {@link #kindLoss}. */
	private void noteKindResults() {
		StringBuilder line = new StringBuilder();
		for (int k = KIND_TRADER; k <= KIND_OUTSIDE; k++) {
			Tally t = kindTally(k);
			long won = t.roundsWon + (k == KIND_SPACER ? standerTally.roundsWon : 0);
			long lost = t.roundsLost + (k == KIND_SPACER ? standerTally.roundsLost : 0);
			if (won + lost >= 2) {
				kindLoss[k] = 0.85f * kindLoss[k] + 0.15f * lost / (float) (won + lost);
			}
			line.append(String.format(Locale.ROOT, " %s %.2f", KIND_NAMES[k], kindLoss[k]));
		}
		if (cfg.hardOpponents) {
			SwordAi.LOGGER.info("rounds lost lately, by kind (each comes up that much more or less often):{}", line);
		}
	}

	void beginEpisode(Truth truth) {
		learner.clearEpisodePotential();
		if (mode == Mode.TRAIN && cfg.evalSeed != 0) {
			// Paired evaluation: this fight is set up as the same fight is for any network measured with this number.
			// Mixed (SplitMix64) first: java.util.Random's first draws from neighbouring seeds are nearly the
			// same, and with seed + n every fight had gone to the same stage.
			long fight = evalFights++;
			long n = fight;
			if (paired != null) {
				// Both fights of a pair from the same seed, the one by the network compared with in its own settings.
				baseActs = PairedEval.bFights(fight);
				paired.apply(cfg, baseActs);
				n = PairedEval.pair(fight);
			}
			long z = cfg.evalSeed * 0x9E3779B97F4A7C15L + (n + 1) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			rng.setSeed(z ^ (z >>> 31));
			if (paired != null) {
				// Both sides' action draws from the same seed too; see Learner.seedSampling.
				learner.seedSampling((z ^ (z >>> 31)) * 0x2545F4914F6CDD1DL);
			}
		}
		spaceLastReach = 99f;
		jumpInTakenTick = -1;
		jumpInDealtTick = -1;
		jumpInTakenCrit = false;
		jumpInDealtCrit = false;
		jumpInTakenTwice = false;
		oppChargeAtAction = 1f;
		spaceLastOppCharge = 1f;
		spacePaid = false;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			stop("left the world");
			return;
		}
		if (!speedApplied) {
			// From the first fight on, and not before: the vision check runs at normal speed.
			speedApplied = true;
			applySpeed(mode == Mode.TRAIN ? wantedSpeed() : 1f);
		}
		if (pendingUpdate == null) {
			// A stage set by hand while training runs is taken up here, between fights and updates.
			reopenHeads();
		}
		// A share of the fights are the jump-in drill, played in the crit stage; see Config.jumpInShare.
		int critsStage = jumpInStage();
		boolean jumpInFight = mode == Mode.TRAIN && demo == null && critsStage >= 0 && cfg.jumpInShare > 0f && rng.nextFloat() < cfg.jumpInShare;
		episodeStageIndex = jumpInFight ? critsStage
				: mode == Mode.TRAIN ? curriculum.pickEpisodeStage(rng, cfg.rehearsalProbability, cfg.selfCritShare)
				: curriculum.stageIndex();
		stage = curriculum.stage(episodeStageIndex);
		boolean againstItself = BotProfile.SELF.equals(stage.bot.movement);
		if (againstItself && (rivals.isEmpty() || curriculum.freshCopyWanted())) {
			try {
				installRival(server);
			} catch (IOException | RuntimeException e) {
				fail("could not set up the copy of itself to fight: " + e, e);
				return;
			}
		}
		// A share of the current stage's fights are combo drills. Rehearsals of earlier stages are left
		// as they are: they are there for the scripted opponents.
		// And they wait until its own fights show it can combo: before that its copy cannot either.
		episodeDrill = mode == Mode.TRAIN && episodeStageIndex == curriculum.stageIndex()
				&& curriculum.drillsReady() && rng.nextFloat() < stage.drillShare;
		if (episodeDrill && !againstItself
				&& (drillCopyStage != curriculum.stageIndex() || learner.totalSteps - drillCopySteps >= DRILL_COPY_STEPS)) {
			// Before the duel there is no copy to fight yet, so the drills keep one of their own, made
			// anew every so often so that it stays the fighter the agent is.
			drillCopySteps = learner.totalSteps;
			drillCopyStage = curriculum.stageIndex();
			try {
				installDrillCopy(server);
			} catch (IOException | RuntimeException e) {
				SwordAi.LOGGER.warn("combo drills: could not set up the copy of itself, so they are left out for now", e);
			}
		}
		if (rivals.isEmpty()) {
			episodeDrill = false;
		}
		// In self-play some fights are against the copies it has already beaten, so that it cannot get
		// ahead of the newest one with something the older ones would have punished.
		int rivalIndex = 0;
		// The old model, where there is one, is the last of them and has a share of its own.
		boolean copies = againstItself && !episodeDrill && !stage.bot.fixedCopy;
		boolean oldThere = oldModelIndex > 0 && oldModelIndex < rivals.size();
		int older = rivals.size() - 1 - (oldThere ? 1 : 0);
		episodeVsOldModel = copies && oldThere && rng.nextFloat() < cfg.oldModelShare;
		if (episodeVsOldModel) {
			rivalIndex = oldModelIndex;
		} else if (copies && older > 0 && rng.nextFloat() < OLDER_COPY_SHARE) {
			rivalIndex = 1 + rng.nextInt(older);
		}
		episodeVsOlder = rivalIndex > 0 && !episodeVsOldModel;
		// The crit drills come first and alone, one lesson after the other, until the last is passed.
		boolean drillsOnly = episodeStageIndex == curriculum.stageIndex() && curriculum.critDrillsOnly();
		float critDrills = drillsOnly ? 1f : stage.critDrillShare;
		episodeCritDrill = mode == Mode.TRAIN && episodeStageIndex == curriculum.stageIndex()
				&& critDrills > 0f && rng.nextFloat() < critDrills;
		if (episodeCritDrill) {
			episodeDrill = false;
		}
		if (jumpInFight) {
			episodeDrill = false;
			episodeCritDrill = false;
		}
		// Until then the drill is the lesson it is at, with its feet and camera held. Afterwards the
		// drills go on, so that what they taught is still practised: both of them as they were, and
		// the punish crit with all its keys as well. (Up to 0.13.2 only that last one went on. The jump
		// was not practised again after its lesson, and was gone a few updates into the fights.) With
		// its keys it is not put against the opponent that stands still: it could crit that one from
		// anywhere in reach at no cost, and would learn to jump at sprint-hit range after all.
		int keptDrill = rng.nextInt(KEPT_DRILLS);
		critDrillKind = !episodeCritDrill ? -1 : drillsOnly ? curriculum.critDrillLesson()
				: keptDrill == KEPT_JUMP ? Curriculum.DRILL_RANGE : Curriculum.DRILL_PUNISH;
		// The first drill is held only where the stage still draws a line for the jump (as up to 0.13.3).
		// As the crit in its face it is played with every key, against an opponent that pushes into it.
		boolean face = critDrillKind == Curriculum.DRILL_RANGE && stage.critDrillFarJumpMax <= 0f;
		episodeLockedDrill = episodeCritDrill && !face && (drillsOnly || keptDrill != KEPT_FREE);
		episodeJumpZone = drillsOnly ? DRILL_CRIT_ZONE : REACH;
		EpisodePlan plan = episodeDrill ? EpisodePlan.drill(stage)
				: episodeCritDrill ? (face ? EpisodePlan.faceDrill() : EpisodePlan.critDrill(critDrillKind, !drillsOnly))
				: new EpisodePlan(stage, curriculum.botSkill(), rivalIndex);
		// A share of the stage's own fights are against a trader: the same opponent, but it keeps
		// pushing in, is quick to swing and is hardly knocked back; see StageSpec.traderShare.
		// And shares against one that keeps running out of the fight, and one that goes round the
		// fighter making critical hits; see StageSpec.runnerShare.
		boolean scripted = !episodeDrill && !episodeCritDrill && !againstItself;
		float kind = rng.nextFloat();
		episodeKind = KIND_PLAIN;
		if (scripted) {
			float[] shares = {stage.traderShare, stage.runnerShare, stage.circlerShare, stage.cornerShare, stage.noKnockShare, stage.oddShare,
					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare, stage.peopleShare, stage.spacerShare, stage.whifferShare};
			if (cfg.hardOpponents) {
				hardWeights(shares);
			}
			float upTo = 0f;
			for (int i = 0; i < shares.length; i++) {
				upTo += shares[i];
				if (kind < upTo) {
					episodeKind = i + 1;
					break;
				}
			}
		}
		// And a share against the outside bot (Carpet PvP's own fighter), where the game has it; see Config.outsideShare.
		if (scripted && rng.nextFloat() < outsideChance()) {
			episodeKind = KIND_OUTSIDE;
		}
		if (jumpInFight && scripted) {
			episodeKind = KIND_JUMPIN;
		}
		episodeTrader = episodeKind == KIND_TRADER;
		episodeStander = false;
		episodeRunner = episodeKind == KIND_RUNNER;
		episodeCircler = episodeKind == KIND_CIRCLER;
		// Every other scripted opponent is one drawn afresh; see StageSpec.randomShare. Not the ones
		// whose whole point is how they are set up.
		if (scripted && episodeKind != KIND_CIRCLER && episodeKind != KIND_CORNER && episodeKind != KIND_NO_KNOCK
				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && episodeKind != KIND_TIMER && episodeKind != KIND_PERSON
				&& episodeKind != KIND_SPACER && episodeKind != KIND_WHIFFER && episodeKind != KIND_OUTSIDE && episodeKind != KIND_JUMPIN
				&& rng.nextFloat() < stage.randomShare) {
			randomise(plan.bot, episodeKind == KIND_PLAIN);
		}
		boolean beside = false;
		if (scripted && stage.opponentJumpReset > 0f) {
			plan.bot.jumpResetChance = stage.opponentJumpReset * rng.nextFloat() * 2f;
		}
		if (episodeTrader) {
			plan.bot.rushChance = 1f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, stage.traderReactionTicks);
			plan.bot.dodgeChance = 0f;
		} else if (episodeRunner) {
			plan.bot.fleeChance = stage.runnerFlee;
			plan.bot.rushChance = 0f;
			plan.bot.critChance = 0f;
			// Half of them keep away for good: only a fighter that jumps after them gets to them.
			plan.bot.fleeUntilHit = rng.nextFloat() < stage.runnerKeepsAway;
			plan.bot.sprint = true;
		} else if (episodeCircler) {
			plan.bot.orbit = true;
			plan.bot.critChance = stage.circlerCrit;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			beside = rng.nextFloat() < stage.besideShare;
		} else if (episodeKind == KIND_CORNER) {
			plan.cornerStart = true;
			plan.bot.holdGround = true;
			plan.bot.critChance = 0.9f;
			plan.bot.critPress = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 3);
			plan.bot.farChance = 0f;
		} else if (episodeKind == KIND_NO_KNOCK) {
			// On top of the fighter, a critical hit every time its sword is back, and hardly moved by a hit.
			plan.bot.rushChance = 1f;
			plan.bot.critChance = 0.9f;
			plan.bot.critPress = 1f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 2);
			beside = rng.nextFloat() < 0.5f * stage.besideShare;
		} else if (episodeKind == KIND_ODD) {
			plan.bot.antic = BotProfile.ANTICS[rng.nextInt(BotProfile.ANTICS.length)];
			plan.bot.fleeChance = 0f;
			if (BotProfile.ANTIC_SPAM.equals(plan.bot.antic)) {
				plan.bot.rushChance = 1f;
				plan.bot.critChance = 0f;
			}
		} else if (episodeKind == KIND_JUMPER) {
			// As good a player as a script can be. Its combo is sprint hits from the edge of its reach, on
			// rhythm, with nothing late and nothing off; and whenever the fighter backs off out of that
			// reach it comes after it with a running jump and makes its hit on the way down, a critical
			// one. The fight begins with its combo already on: one or two hits in (the head start).
			plan.bot.jumpIn = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;
			plan.bot.critPress = 1f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.reactionTicks = rng.nextInt(2);
			plan.bot.aimNoiseDeg = 0.5f + rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 1f;
			plan.bot.preferredRange = 2.9f;
			plan.bot.followUpTicks = dev.swordai.core.env.TakenStreak.COMBO_TICKS;
			plan.bot.followUpReach = 0.3f * rng.nextFloat();
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = BotProfile.DRILL_MIN_DISTANCE;
			plan.bot.maxDistance = BotProfile.DRILL_MAX_DISTANCE;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_BACKER) {
			// Hit, it walks backwards with its eyes on the fighter and its sword ready, for twelve ticks:
			// what this fighter did about a combo until a player showed what that is good for.
			plan.bot.dodgeChance = 1f;
			plan.bot.dodgeTicks = 12;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.critChance = 0f;
			plan.bot.sprint = true;
		} else if (episodeKind == KIND_PERSON) {
			episodePerson = pickPerson();
			if (episodePerson == null) {
				episodeKind = KIND_PLAIN;
			} else {
				episodePerson.applyTo(plan.bot, rng);
				plan.bot.minDistance = BotProfile.DRILL_MIN_DISTANCE;
				plan.bot.maxDistance = BotProfile.DRILL_MAX_DISTANCE;
				plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
				plan.bot.minBearingDeg = 0f;
				plan.bot.farChance = 0f;
				plan.bot.maxPillar = 0;
			}
		} else if (episodeKind == KIND_TIMER) {
			// As sharp as the one that comes after it with a running jump, and it knows the fighter's rhythm.
			plan.bot.timer = true;
			plan.bot.punish = true;
			// Hit on its feet, it jumps at once, and so is on its way down for the punish crit however
			// little the hit lifted it.
			plan.bot.jumpResetChance = 0.8f + 0.2f * rng.nextFloat();
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;
			plan.bot.critPress = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = true;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.reactionTicks = rng.nextInt(2);
			plan.bot.aimNoiseDeg = 0.5f + rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 0.92f;
			plan.bot.preferredRange = 3.4f;
			plan.bot.followUpTicks = 0;
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = BotProfile.DRILL_MIN_DISTANCE;
			plan.bot.maxDistance = BotProfile.DRILL_MAX_DISTANCE;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_SPACER) {
			// Crit, S, crit, S: a step outside its reach while its sword comes back, in with a jump once it
			// is back, the critical hit on the way down, and straight back out with S. Nothing late,
			// nothing off, never to the side. The user, 18 October: "crit and then PRESS S like go out of
			// distance and Crit and go out of distance", at a "PERFECT 3 block".
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.spacer = true;
			plan.bot.sprint = true;
			// A third of them stand and hit, a third come in for a critical hit and back out every time,
			// and a third do either, swing by swing.
			int style = demo != null ? demo.spacerStyle(rng) : rng.nextInt(3);
			episodeStander = style == 0;
			plan.bot.critChance = style == 0 ? 0f : style == 1 ? 1f : 0.3f + 0.4f * rng.nextFloat();
			plan.bot.critPress = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.antic = "";
			plan.bot.reactionTicks = 0;
			plan.bot.aimNoiseDeg = 0.3f + 0.7f * rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 0.92f + 0.08f * rng.nextFloat();
			plan.bot.followUpTicks = 0;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.jumpResetChance = 0.5f * rng.nextFloat();
			plan.bot.farChance = 0f;
			plan.bot.minDistance = 3.5f;
			plan.bot.maxDistance = 6f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_WHIFFER) {
			// It walks in and swings the moment its sword is back, on rhythm, whether or not the fighter is
			// quite in reach (up to half a block to a block and a fifth short): held off at the edge it misses,
			// and its cooldown is the opening. Half of them run straight in, the others come to about 2.7.
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.sprint = true;
			plan.bot.rushChance = rng.nextBoolean() ? 1f : 0f;
			plan.bot.critChance = 0.2f * rng.nextFloat();
			plan.bot.critPress = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.antic = "";
			plan.bot.reactionTicks = rng.nextInt(3);
			plan.bot.aimNoiseDeg = 1f + 2f * rng.nextFloat();
			plan.bot.turnRateDeg = 40f;
			plan.bot.attackChargeMin = 0.9f + 0.1f * rng.nextFloat();
			plan.bot.followUpTicks = Integer.MAX_VALUE / 4;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0.5f + 0.7f * rng.nextFloat();
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = 3.5f;
			plan.bot.maxDistance = 7f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_OUTSIDE) {
			// Carpet PvP's own fighter plays it (see ArenaServer.setOutside): it plans its fight some ticks
			// ahead and, at the higher levels, crits, strafes and W-taps. The brain only places it.
			plan.bot.outside = pickOutsideLevel();
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.fleeChance = 0f;
			plan.bot.minDistance = 4f;
			plan.bot.maxDistance = 9f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_JUMPIN) {
			// It does one thing: from four and a half to six and a half blocks off it runs and jumps in at the fighter with
			// its sword ready, and makes its crit on the way down, wherever the fighter's hit has put it (the user's friends,
			// 23 October). The fight is that exchange; see jumpInStep.
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.jumpIn = true;
			plan.bot.jumpInAlways = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;
			plan.bot.critPress = 1f;
			plan.bot.punish = false;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.jumpResetChance = 0f;
			plan.bot.antic = "";
			plan.bot.reactionTicks = 0;
			plan.bot.aimNoiseDeg = 0.5f + rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 0.95f;
			plan.bot.preferredRange = 2.9f;
			plan.bot.followUpTicks = 0;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			// From where it takes off, drawn per fight: from right in front (1.8) to a running jump's farthest (5.6); and it
			// starts anywhere from 2.5 to 6.5 off (the user, 23 October: "let the opponent jump in from a close distance as well").
			plan.bot.jumpInFrom = 1.8f + 3.8f * rng.nextFloat();
			plan.bot.minDistance = 2.5f;
			plan.bot.maxDistance = 6.5f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.
			plan.bot.punish = true;
			plan.bot.fleeChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 2 + rng.nextInt(4));
		}
		if (beside) {
			// Close by and out of the picture: off to a side, or behind.
			plan.bot.minBearingDeg = 70f;
			plan.bot.maxBearingDeg = 180f;
			plan.bot.minDistance = 1.8f;
			plan.bot.maxDistance = 3.0f;
			plan.bot.farChance = 0f;
		}
		// The scripted crit-jumper, met again from a later stage, has the habit the way a person has it:
		// it jumps in for most of its swings, in some fights for nearly all of them.
		if (!episodeDrill && episodeStageIndex != curriculum.stageIndex() && plan.bot.critChance > 0f && episodeKind != KIND_PERSON) {
			plan.bot.critChance = Math.max(plan.bot.critChance,
					REPLAYED_CRIT_MIN + (REPLAYED_CRIT_MAX - REPLAYED_CRIT_MIN) * rng.nextFloat());
		}
		// Each fight has its own connection.
		episodeSlow = false;
		if (stage.pathLagTicks > 0) {
			// One connection for all of it, as on a server. A hit moves the opponent once it has gone to
			// the opponent's game and the answer is back, which is its connection twice; what each sees of
			// the other is old by the opponent's way and the fighter's own. A share of the fights are
			// against an opponent on a slow connection: knocked back a quarter to half a second late.
			episodeSlow = stage.slowShare > 0f && rng.nextFloat() < stage.slowShare;
			int knock = episodeSlow
					? stage.slowKnockbackMinTicks + rng.nextInt(Math.max(1, stage.slowKnockbackMaxTicks - stage.slowKnockbackMinTicks + 1))
					: 2 * drawPathLag(stage.pathLagTicks);
			int seen = Math.min(ReplicaBody.MAX_LAG_TICKS, (knock + 1) / 2 + rng.nextInt(3));
			plan.agentLag = seen;
			plan.botLag = seen;
			plan.botPing = knock;
		} else {
			plan.agentLag = stage.seenLagTicks > 0 ? rng.nextInt(stage.seenLagTicks + 1) : 0;
			plan.botLag = stage.lagTicks > 0 ? rng.nextInt(stage.lagTicks + 1) : 0;
			plan.botPing = stage.hitLagTicks > 0 ? rng.nextInt(stage.hitLagTicks + 1) : 0;
		}
		if (episodeKind == KIND_PERSON) {
			// That player's connection, as the server reported it.
			episodeSlow = false;
			int lag = episodePerson.lagTicks();
			plan.botPing = 2 * lag;
			plan.agentLag = Math.min(ReplicaBody.MAX_LAG_TICKS, lag + 1);
			plan.botLag = lag;
		}
		if (episodeKind == KIND_TIMER || episodeKind == KIND_SPACER || episodeKind == KIND_OUTSIDE || episodeKind == KIND_JUMPIN) {
			// The user's connection and his friend's: 50 to 100 ms each way. What it sees of the opponent
			// is two or three ticks old, and its hit moves the opponent three to five ticks after it lands.
			episodeSlow = false;
			plan.botPing = 3 + rng.nextInt(3);
			plan.agentLag = 2 + rng.nextInt(2);
			plan.botLag = 1 + rng.nextInt(2);
		}
		plan.agentKnockLag = 0;
		if (episodeKind == KIND_JUMPIN) {
			// And a hit moves the fighter itself one or two ticks late, its own way from the server: the time in which the
			// crit back is there to be made after the opponent's crit (the user, 23 October).
			plan.agentKnockLag = 1 + rng.nextInt(2);
		}
		// In every other fight the opponent also takes less than the full knockback.
		plan.botKnockback = stage.knockbackMin > 0f && stage.knockbackMin < 1f && rng.nextBoolean()
				? stage.knockbackMin + (1f - stage.knockbackMin) * rng.nextFloat() : 1f;
		if (episodeTrader || face || episodeCircler) {
			// It takes a part of the hit, drawn per fight, and is back before the sword is.
			plan.botKnockback = stage.traderKnockbackMin + (stage.traderKnockbackMax - stage.traderKnockbackMin) * rng.nextFloat();
		} else if (episodeKind == KIND_CORNER) {
			// With the walls behind it a hit moves it along them at the most.
			plan.botKnockback = 0.3f + 0.4f * rng.nextFloat();
		} else if (episodeKind == KIND_NO_KNOCK) {
			plan.botKnockback = 0.15f * rng.nextFloat();
		} else if (episodeKind == KIND_PUNISHER) {
			// A server that hands out a part of the knockback: a third to four fifths of it.
			plan.botKnockback = 0.3f + 0.5f * rng.nextFloat();
		} else if (episodeKind == KIND_TIMER) {
			// On a server that hands out somewhat less.
			plan.botKnockback = 0.4f + 0.5f * rng.nextFloat();
		} else if (episodeKind == KIND_PERSON) {
			// As far as that player was seen to go when hit.
			plan.botKnockback = episodePerson.knockback();
		} else if (episodeKind == KIND_JUMPIN) {
			// A server's full knockback, late by the connection drawn above.
			plan.botKnockback = 1f;
		} else if (episodeCritDrill) {
			// The standing opponent stands: a hit does not move it at all, so the next swing can be made
			// from the same spot, and nothing about it is late. The one that jumps in is not moved either
			// while the fighter is held; with all its keys the fighter can push it back a little.
			plan.botKnockback = critDrillKind == Curriculum.DRILL_PUNISH && !episodeLockedDrill ? PUNISH_DRILL_KNOCKBACK : 0f;
			plan.botPing = 0;
		}
		// In every other fight the opponent's connection stutters as well, a little or a lot.
		boolean stutter = stage.lagStutter > 0f && rng.nextBoolean();
		plan.agentStall = stutter ? stage.lagStutter * rng.nextFloat() : 0f;
		plan.agentStallTicks = stutter
				? StageSpec.LAG_STALL_MIN_TICKS + rng.nextInt(StageSpec.LAG_STALL_MAX_TICKS - StageSpec.LAG_STALL_MIN_TICKS + 1) : 0;
		// Against a copy of itself a share of the fights are on equal terms, and those are the ones
		// that count; see StageSpec.fairShare.
		episodeFair = againstItself && !episodeDrill && !episodeCritDrill && stage.fairShare > 0f
				&& rng.nextFloat() < stage.fairShare;
		if (episodeFair) {
			episodeSlow = false;
			plan.agentLag = 0;
			plan.botLag = 0;
			plan.botPing = 0;
			plan.botKnockback = 1f;
			plan.agentStall = 0f;
			plan.agentStallTicks = 0;
			plan.bot.maxBearingDeg = 0f;
		}
		// Against a copy, who moves first is drawn on every tick; see EpisodePlan.mixedTurns.
		plan.mixedTurns = againstItself && cfg.selfPlayTurns;
		if (againstItself || episodeDrill) {
			// On the screen the copy is given the delay the screen costs the fighter; see Config.screenCopyLagTicks.
			plan.botLag = Math.min(ReplicaBody.MAX_LAG_TICKS, plan.botLag + copyLagTicks());
		}
		// A share of these fights are ones it lost before, set up exactly as they were; see LostCases.
		setUpCase(plan, scripted);
		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;
		episodeStutter = plan.agentStall > 0f;
		setAgentLag(plan.agentLag, plan.agentStall, plan.agentStallTicks);
		// The push towards a jump is the current stage's; a fight of an earlier stage is played as that stage is.
		setLessons(episodeStageIndex == curriculum.stageIndex());
		int fewest = Math.max(1, Math.min(stage.drillMinHits, stage.drillHits));
		openerHits = episodeDrill ? fewest + rng.nextInt(Math.max(1, stage.drillHits - fewest + 1))
				: episodeKind == KIND_JUMPER ? 1 + rng.nextInt(2) : 0;
		if (episodeCase != null) {
			// The jump critter's head start is part of the fight as it was.
			if (!episodeReplay) {
				episodeCase.openerHits = openerHits;
			} else if (episodeCase.openerHits >= 0) {
				openerHits = episodeCase.openerHits;
			}
		}
		openerSeen = 0;
		openerSinceHit = 0;
		clock.resetEpisode();
		clock.botSkill = curriculum.botSkill();
		clock.botAttacks = plan.bot.attacks;
		requestedEpisodeId = truth.episodeId() + 1;
		server.execute(() -> ArenaServer.reset(plan));

		releaseKeys();
		prepareBody();
		hasLastAction = false;
		clicked = false;
		dealtRow = 0;
		fallWatch = false;
		chaseWatch = false;
		// Against a copy of itself the opponent of this fight is the opponent of the last: what it has
		// found out about how that one answers its jumps is kept, as it is from round to round against
		// a person. A scripted opponent is a new one every time.
		if (againstItself || episodeDrill) {
			if (jumpInVsCopy == null) {
				jumpInVsCopy = newJumpIn();
			}
			jumpIn = jumpInVsCopy;
			jumpIn.newRound();
			stepBack.newRound();
			if (jumpReset != null) {
				jumpReset.newRound();
			}
			comboStrafe.reset();
			if (critTrade != null) {
				critTrade.reset();
			}
		} else {
			stepBack.reset();
			if (jumpReset != null) {
				jumpReset.reset();
			}
			comboStrafe.reset();
			if (critTrade != null) {
				critTrade.reset();
			}
			if (jumpInVsOthers == null) {
				jumpInVsOthers = newJumpIn();
			}
			jumpIn = jumpInVsOthers;
			jumpIn.reset();
		}
		if (paired != null) {
			// Set when the jump rules were made; the side of this fight may have it otherwise.
			jumpIn.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
			jumpIn.comboJump(cfg.comboJumpRule);
			jumpIn.comboJumpChargeMin(cfg.comboJumpChargeMin);
			jumpIn.clashJump(cfg.clashJumpRule);
		}
		edgeHold.reset();
		tacticClock.reset();
		oppWindow.reset();
		if (aimRule != null) {
			aimRule.reset();
		}
		lostRule.reset();
		punishMemory.reset();
		drillJumpOutOfZone = false;
		jumpedSinceHitTaken = true;
		ticksSinceJumpKey = 1000;
		episodeTick = 0;
		waitTicks = 0;
		missingBotTicks = 0;
		phase = Phase.WAIT_RESET;
	}

	/**
	 * An episode against a person begins, and the agent has its keys: the controls and rewards of the
	 * stage it is in, and nothing for the server to set up. Where the two stand is not this class's
	 * business.
	 */
	void beginAgainstPerson() {
		episodeStageIndex = curriculum.fightStageIndex();
		stage = curriculum.stage(episodeStageIndex);
		setAgentLag(0, 0f, 0);
		// The pushes are help against the training bot; a real fight is played as the network plays.
		setLessons(false);
		episodeDrill = false;
		episodeCritDrill = false;
		episodeTrader = false;
		episodeRunner = false;
		episodeCircler = false;
		episodeKind = KIND_PLAIN;
		dealtRow = 0;
		fallWatch = false;
		chaseWatch = false;
		if (jumpIn != null) {
			// A new round against the same person.
			jumpIn.newRound();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		lostRule.reset();
		punishMemory.reset();
		stepBack.newRound();
		tacticClock.reset();
		oppWindow.reset();
		if (jumpReset != null) {
			jumpReset.newRound();
		}
		comboStrafe.reset();
		if (critTrade != null) {
			critTrade.reset();
		}
		episodeSlow = false;
		critDrillKind = -1;
		episodeLockedDrill = false;
		episodeVsOlder = false;
		openerHits = 0;
		openerSeen = 0;
		openerSinceHit = 0;
		clock.resetEpisode();
		// What the critic is told about the opponent: one that hits back.
		clock.botSkill = curriculum.botSkill();
		clock.botAttacks = true;
		hasLastAction = false;
		clicked = false;
		cutNow = false;
		jumpedSinceHitTaken = true;
		ticksSinceJumpKey = 1000;
		episodeTick = 0;
		resetSight();
		restHand();
		if (stage.ideas) {
			// A plan only holds swings back in a stage that scores it.
			idea.allowHolds(stage.reward.mixTiming > 0f);
			idea.start(rng);
		} else {
			idea.clear();
		}
		phase = Phase.RUN;
	}

	/** How many steps the current stage wants an update made from. */
	int wantedRollout() {
		int steps = curriculum.current().rolloutSteps;
		return steps > 0 ? steps : cfg.rolloutSteps;
	}

	/**
	 * Before stopping: if enough has been gathered since the last update, it is learned from now.
	 * {@link #saveProgress} then waits for that to finish. For fights against people, which are too
	 * few to throw the last minute of away.
	 */
	void learnFromTheRest() {
		if (mode == Mode.TRAIN && pendingUpdate == null && learner.rolloutSize() >= WORTH_LEARNING_STEPS) {
			startUpdate();
		}
	}

	/** Against a person: the network has finished learning from the last batch, if it had been. False if that went wrong. */
	boolean updateFinished() {
		if (pendingUpdate != null && pendingUpdate.isDone()) {
			finishUpdate();
		}
		return phase != Phase.STOPPED;
	}

	/** Cuts the running episode cleanly: keys released, the step in progress dropped, the bot stood down. */
	void suspendEpisode() {
		releaseKeys();
		// While the network is learning there is no episode to cut: a copy is playing, unrecorded.
		if (pendingUpdate == null) {
			learner.interrupt();
			if (hasLastAction) {
				rewards.endEpisode(episodeTally(), false, false);
			} else {
				// Set up, perhaps already some hits into a drill, and never played.
				rewards.reset();
			}
		}
		hasLastAction = false;
		clicked = false;
		restHand();
		setServerIdle(true);
	}

	/**
	 * The game stood still in the middle of a fight while the server went on with it (23 Oct: Windows changed its
	 * sound device, the client stopped for that, and the opponent knocked the agent out in what were five decisions
	 * to it). What happened then says nothing about how it fights: the fight is cut unscored and is not kept as a
	 * lost one, and in an evaluation the same fight, on the same side of its pair, is fought again.
	 */
	void cutStalledFight(long ticks, Truth truth) {
		boolean again = mode == Mode.TRAIN && cfg.evalSeed != 0;
		SwordAi.LOGGER.info("fight cut: the game stood still for {} ticks while the server went on with the fight; not scored{}",
				ticks, again ? ", fought again" : "");
		if (again) {
			evalFights--;
		}
		episodeCase = null;
		suspendEpisode();
		beginEpisode(truth);
	}

	/**
	 * The kind of fight running, as the critic is told it ({@code Config.criticTask}): the place of the
	 * episode's tally in this list. The order is part of what a trained critic has learned: new kinds go
	 * at the end, and there is room for {@code CriticNet.TASKS}.
	 */
	private Tally[] taskTallies;

	/** What the tactics model chose since the last update, in all and per kind of fight; the counts are cleared. */
	private String tacticPicksLine() {
		int[] all = new int[dev.swordai.core.rl.Tactic.COUNT];
		StringBuilder kinds = new StringBuilder();
		for (int row = 0; row < tacticPicks.length; row++) {
			int n = 0;
			for (int k = 0; k < all.length; k++) {
				n += tacticPicks[row][k];
				all[k] += tacticPicks[row][k];
			}
			if (n == 0) {
				continue;
			}
			String name = row == 0 ? "other" : row - 1 < TASK_NAMES.length ? TASK_NAMES[row - 1] : "kind " + (row - 1);
			kinds.append(" | ").append(name).append(' ').append(n).append(':');
			for (int k = 0; k < all.length; k++) {
				kinds.append(String.format(Locale.ROOT, " %.0f%%", 100.0 * tacticPicks[row][k] / n));
			}
			java.util.Arrays.fill(tacticPicks[row], 0);
		}
		int total = 0;
		for (int x : all) {
			total += x;
		}
		StringBuilder b = new StringBuilder();
		b.append(total).append(" choices");
		for (int k = 0; k < all.length; k++) {
			b.append(String.format(Locale.ROOT, ", %s %.0f%%", dev.swordai.core.rl.Tactic.name(k), total == 0 ? 0.0 : 100.0 * all[k] / total));
		}
		b.append(" (per kind, in that order)").append(kinds);
		return b.toString();
	}

	int episodeTask() {
		if (taskTallies == null) {
			taskTallies = new Tally[] {tally, rehearsalTally, runnerTally, circlerTally, cornerTally, noKnockTally, oddTally,
					jumperTally, punisherTally, backerTally, timerTally, personTally, spacerTally, standerTally, whifferTally,
					outsideTally, replayTally, drillTally, jumpDrillTally, punishDrillTally, freeDrillTally, oldModelTally, traderTally, jumpInTally};
		}
		Tally t = episodeTally();
		for (int i = 0; i < taskTallies.length; i++) {
			if (taskTallies[i] == t) {
				return i;
			}
		}
		return -1;
	}

	/** Where the running episode's numbers go: only the current stage's own fights count towards passing it. */
	Tally episodeTally() {
		if (episodeDrill) {
			return drillTally;
		}
		if (episodeCritDrill) {
			// The first drill is counted as itself, held or not; the second apart when it has all its keys.
			return critDrillKind == Curriculum.DRILL_RANGE ? jumpDrillTally : episodeLockedDrill ? punishDrillTally : freeDrillTally;
		}
		if (episodeVsOldModel) {
			return oldModelTally;
		}
		// Before the kinds: a replay is the worst of a kind, picked for that, not a fair draw of it.
		if (episodeReplay || episodeMutant) {
			return replayTally;
		}
		// The fights against traders are the stage's own as much as the rest; they are only counted
		// apart until the update, so that it can be seen what it does against each kind.
		// The fights against a runner and against a circler are kept apart wherever they are fought,
		// in their own stage or replayed from a later one: they are what says whether it jumps for a
		// critical hit where one is to be had.
		if (episodeRunner) {
			return runnerTally;
		}
		if (episodeCircler) {
			return circlerTally;
		}
		if (episodeKind == KIND_CORNER) {
			return cornerTally;
		}
		if (episodeKind == KIND_NO_KNOCK) {
			return noKnockTally;
		}
		if (episodeKind == KIND_ODD) {
			return oddTally;
		}
		if (episodeKind == KIND_JUMPER) {
			return jumperTally;
		}
		if (episodeKind == KIND_PUNISHER) {
			return punisherTally;
		}
		if (episodeKind == KIND_BACKER) {
			return backerTally;
		}
		if (episodeKind == KIND_TIMER) {
			return timerTally;
		}
		if (episodeKind == KIND_PERSON) {
			return personTally;
		}
		if (episodeKind == KIND_SPACER) {
			return episodeStander ? standerTally : spacerTally;
		}
		if (episodeKind == KIND_WHIFFER) {
			return whifferTally;
		}
		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		if (episodeKind == KIND_JUMPIN) {
			return jumpInTally;
		}
		return episodeStageIndex != curriculum.stageIndex() ? rehearsalTally : episodeTrader ? traderTally : tally;
	}

	void setServerIdle(boolean idle) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.setIdle(idle));
		}
	}

	// ---- PPO update -----------------------------------------------------------------------------

	void startUpdate() {
		if (playsOnWhileLearning()) {
			// The fight does not wait: a copy of the network as it is now plays on meanwhile.
			learner.keepStandIn();
		} else {
			phase = Phase.UPDATING;
			setServerIdle(true);
		}
		onUpdating(true);
		boolean trainActor = criticOnlyLeft <= 0;
		if (!trainActor) {
			criticOnlyLeft--;
		}
		pendingTally = new Tally();
		pendingTally.add(tally);
		pendingTally.add(traderTally);
		if (curriculum.current().runnerShare > 0f || curriculum.current().circlerShare > 0f) {
			// Where they are the stage's own fights they count towards it like the others.
			pendingTally.add(runnerTally);
			pendingTally.add(circlerTally);
			pendingTally.add(cornerTally);
			pendingTally.add(noKnockTally);
			pendingTally.add(oddTally);
			pendingTally.add(jumperTally);
			pendingTally.add(punisherTally);
			pendingTally.add(backerTally);
			pendingTally.add(timerTally);
			pendingTally.add(personTally);
			pendingTally.add(spacerTally);
			pendingTally.add(standerTally);
			pendingTally.add(whifferTally);
			pendingTally.add(outsideTally);
			pendingTally.add(replayTally);
		}
		// How it copes with being comboed shows mostly in the drills.
		pendingTally.addCombosTaken(drillTally);
		logFights();
		noteKindResults();
		if (lostCases != null) {
			// And how the fights it lost before are going: kept, fought again, beaten for good.
			SwordAi.LOGGER.info("lost fights: {}", lostCases.takeCounts(Trainer::kindName));
			SwordFiles.saveLostCases(lostCases);
		}
		if (cfg.silWeight > 0f) {
			SwordAi.LOGGER.info("self-imitation: {}", learner.silLine());
		}
		if (cfg.demoWeight > 0f) {
			SwordAi.LOGGER.info("learning from your fights: {}", learner.demoLine());
		}
		SwordAi.LOGGER.info("jumps in at it: met with the sword ready {}, with it down {}", jumpIns[1], jumpIns[0]);
		String timing = timingLine();
		if (!timing.isEmpty()) {
			SwordAi.LOGGER.info("timing: {}", timing);
		}
		SwordAi.LOGGER.info(String.format(Locale.ROOT,
				"answer rule (push %.1f): where it swings, the network's own swing chance %.2f (gap %.2f, %d ticks, swung %d); where it holds, %.2f (gap %.2f, %d ticks, swung %d)",
				cfg.answerNudge, ruleGapCount[0] == 0 ? Double.NaN : ruleSwingSum[0] / ruleGapCount[0], ruleGapCount[0] == 0 ? Double.NaN : ruleGapSum[0] / ruleGapCount[0], ruleGapCount[0], ruleSwung[0],
				ruleGapCount[1] == 0 ? Double.NaN : ruleSwingSum[1] / ruleGapCount[1], ruleGapCount[1] == 0 ? Double.NaN : ruleGapSum[1] / ruleGapCount[1], ruleGapCount[1], ruleSwung[1]));
		java.util.Arrays.fill(ruleGapSum, 0);
		java.util.Arrays.fill(ruleSwingSum, 0);
		java.util.Arrays.fill(ruleGapCount, 0);
		SwordAi.LOGGER.info("answer rule: where it swings, the network's own gap below -8 {}, -8..-4 {}, -4..0 {}, 0..4 {}, above 4 {}",
				ruleGapBins[0], ruleGapBins[1], ruleGapBins[2], ruleGapBins[3], ruleGapBins[4]);
		java.util.Arrays.fill(ruleSwung, 0);
		java.util.Arrays.fill(ruleGapBins, 0);
		if (learner.hasTactics()) {
			SwordAi.LOGGER.info("tactics model: {}", learner.tacticLine());
			SwordAi.LOGGER.info("tactics chosen: {}", tacticPicksLine());
		}
		if (learner.hasReader()) {
			SwordAi.LOGGER.info("opponent reader: {}", learner.readerLine(TASK_NAMES));
		}
		jumpIns[0] = 0;
		jumpIns[1] = 0;
		tally.clear();
		keepLast(traderTally, lastTrader);
		traderTally.clear();
		keepLast(runnerTally, lastRunner);
		runnerTally.clear();
		keepLast(circlerTally, lastCircler);
		circlerTally.clear();
		cornerTally.clear();
		noKnockTally.clear();
		oddTally.clear();
		jumperTally.clear();
		punisherTally.clear();
		backerTally.clear();
		timerTally.clear();
		personTally.clear();
		spacerTally.clear();
		standerTally.clear();
		whifferTally.clear();
		outsideTally.clear();
		jumpInTally.clear();
		replayTally.clear();
		oldModelTally.clear();
		if (slowCount.steps > 0) {
			lastSlow.set(slowCount);
		}
		if (quickCount.steps > 0) {
			lastQuick.set(quickCount);
		}
		slowCount.clear();
		quickCount.clear();
		rehearsalTally.clear();
		drillTally.clear();
		keepLast(jumpDrillTally, lastJumpDrill);
		keepLast(punishDrillTally, lastPunishDrill);
		keepLast(freeDrillTally, lastFreeDrill);
		int lessonPassed = curriculum.recordCritDrills(jumpDrillTally, punishDrillTally);
		sayDrillResult(lessonPassed, jumpDrillTally, punishDrillTally);
		if (lessonPassed >= 0) {
			// The fights that follow are another drill, or the stage's own: as after a promotion, the
			// critic gets to know what they are worth before the actor moves on them.
			criticOnlyLeft = cfg.criticWarmupUpdates;
		}
		jumpDrillTally.clear();
		punishDrillTally.clear();
		freeDrillTally.clear();
		keepCritLines();
		Path checkpoint = learnedFile();
		float entropyScale = curriculum.current().entropyScale;
		float actorNeeds = actorNeedsExplained();
		pendingUpdate = updater.submit(() -> {
			UpdateStats stats = learner.update(trainActor, entropyScale, actorNeeds);
			learner.save(checkpoint);
			return stats;
		});
	}

	/**
	 * One line in the game's log per update with what each kind of fight came to since the last one:
	 * the history file has the stage's own fights only, and what the drills and the fights against
	 * traders were doing an hour ago is otherwise not kept anywhere.
	 */
	private void logFights() {
		if (!ActionSpace.unlocked(curriculum.current().headMask, ActionSpace.JUMP) || !stageFights()) {
			return;
		}
		SwordAi.LOGGER.info("fights since the last update ({} steps in total): own {} | traders {} | crit drill 1 {} | punish held {} | punish free {} | slow connection {} | ordinary connection {} | combo drills {} | scripted opponents of earlier stages {} | runners {} | circlers {} | corner {} | no knockback {} | odd {} | jump critters {} | punishers {} | backers {} | timers {} | people {} | spacers {} | standers {} | whiffers {} | 0.14.0 model {} | outside bot {} | replays of lost fights {} | swings made by the rules {} | hits after which the swing was kept for the way down {}, punish crits made {} | falls after a hit: crit {}, a plain hit first {}, opponent never under the crosshair {}, sword never back {}, other {}, opponent out of reach {} | ticks on which the camera was a rule {} | chase jumps ended: crit {}, another hit {}, under the crosshair but no swing {}, in reach but not under the crosshair {}, never in reach {} | its own game learned of a hit: on the tick it was shown {}, later {}, without {} | found to answer its hits {} times | ran in at an opponent on its way up on {} ticks, swings {} (ticks with one on its way up within range {}: its own feet off the ground {}, sword not back {}, not closing {}), stepped out of one with its sword not back on {} ticks, held at the edge of the reach of a ready sword on {} ticks | opponent under the crosshair with the sword back on {} ticks, no swing on {} of them, {} of those kept for the way down | five blocks off or more on the ground on {} ticks: jumped {}, not going at it {}, hit lately {}, to the side {}, sword not back {}, left alone {}, coming in {} | timer: jumps for the sword {} (crits {}), jumps at it arriving {} (crits {}), hit out of either {}, punish crits {} | distance kept: opponent's sword back {} over {} ticks, on its way back {} over {} ticks | straight runs {} ticks | fights against the 0.14.0 model: won {} lost {} | walked into reach on a ready sword {}, on one coming back {} (against the 0.14.0 model {} / {})",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally),
				slowCount.brief(), quickCount.brief(), brief(drillTally), brief(rehearsalTally), brief(runnerTally), brief(circlerTally),
				brief(cornerTally), brief(noKnockTally), brief(oddTally), brief(jumperTally), brief(punisherTally), brief(backerTally), brief(timerTally), brief(personTally), brief(spacerTally), brief(standerTally), brief(whifferTally), brief(oldModelTally), brief(outsideTally), brief(replayTally), forcedAnswers, keptAnswers,
				tally.punishCrits + traderTally.punishCrits + runnerTally.punishCrits + circlerTally.punishCrits + rehearsalTally.punishCrits
						+ punishDrillTally.punishCrits + freeDrillTally.punishCrits + drillTally.punishCrits
						+ cornerTally.punishCrits + noKnockTally.punishCrits + oddTally.punishCrits + jumperTally.punishCrits + punisherTally.punishCrits + backerTally.punishCrits + timerTally.punishCrits,
				fallCrits, fallPlain, fallNoAim, fallNoSword, fallOther, fallFar, aimedTicks,
				chaseEndCrit, chaseEndHit, chaseEndNoSwing, chaseEndNoAim, chaseEndFar, hitNowSame, hitNowLater, hitNowAlone, waryBegan, meetTicks, meetSwings,
				meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3], dodgeTicks, edgeTicks, pickTicks, pickNoSwing, pickKept,
				java.util.Arrays.stream(hopWhy).sum(), hopWhy[0], hopWhy[1], hopWhy[2], hopWhy[3], hopWhy[4], hopWhy[5], hopWhy[6],
				timerCount(dev.swordai.server.BotBrain.TIMER_GAP_JUMPS), timerCount(dev.swordai.server.BotBrain.TIMER_GAP_CRITS),
				timerCount(dev.swordai.server.BotBrain.TIMER_ARRIVE_JUMPS), timerCount(dev.swordai.server.BotBrain.TIMER_ARRIVE_CRITS),
				timerCount(dev.swordai.server.BotBrain.TIMER_HIT_IN_JUMP), timerCount(dev.swordai.server.BotBrain.TIMER_PUNISH_CRITS),
				String.format(Locale.ROOT, "%.2f", spaceReadyTicks == 0 ? 0.0 : spaceReadySum / spaceReadyTicks), spaceReadyTicks,
				String.format(Locale.ROOT, "%.2f", spaceCoolTicks == 0 ? 0.0 : spaceCoolSum / spaceCoolTicks), spaceCoolTicks, straightTicks, oldModelWins, oldModelLosses,
				walkedIn[0], walkedIn[1], walkedIn[2], walkedIn[3]);
		java.util.Arrays.fill(walkedIn, 0);
		if (cfg.jumpInShare > 0f || cfg.holdOnFallingJumper || cfg.clashJumpRule || cfg.instantPunish) {
			SwordAi.LOGGER.info("jump-in drill and crit trades: {}", jumpInLine());
		}
		if (cfg.comboStrafeRule) {
			SwordAi.LOGGER.info("out of the line of combos to the side (comboStrafeRule): {} combo hits, A or D held {} ticks", comboStrafeHits, comboStrafeTicks);
		}
		comboStrafeHits = 0;
		comboStrafeTicks = 0;
		straightTicks = 0;
		oldModelWins = 0;
		oldModelLosses = 0;
		spaceReadyTicks = 0;
		spaceReadySum = 0;
		spaceCoolTicks = 0;
		spaceCoolSum = 0;
		java.util.Arrays.fill(hopWhy, 0);
		pickTicks = 0;
		pickNoSwing = 0;
		pickKept = 0;
		java.util.Arrays.fill(meetHow, 0);
		meetTicks = 0;
		dodgeTicks = 0;
		edgeTicks = 0;
		meetSwings = 0;
		waryBegan = 0;
		hitNowSame = 0;
		hitNowLater = 0;
		hitNowAlone = 0;
		aimedTicks = 0;
		chaseEndCrit = 0;
		chaseEndHit = 0;
		chaseEndNoSwing = 0;
		chaseEndNoAim = 0;
		chaseEndFar = 0;
		forcedAnswers = 0;
		keptAnswers = 0;
		fallCrits = 0;
		fallPlain = 0;
		fallNoAim = 0;
		fallFar = 0;
		fallNoSword = 0;
		fallOther = 0;
	}

	private static String brief(Tally t) {
		if (t.steps == 0) {
			return "-";
		}
		return String.format(Locale.ROOT, "%d steps: %d hits (%.0f%% crit, %.0f%% sprint), %d taken, %.0f jumps/min of which %.0f%% crit, punish %d/%d, rew %.3f, "
						+ "sweeps %d, early %d, swings %d, air swings %d (%d at nothing), hits answered %d/%d, combos on it %d of which a third hit %d, combos over %d of which without a swing back %d (%d of them three hits or more), S held %d/%d, chase jumps %d of which a crit %d, "
						+ "rule jumps: chase %d (crit %d) face %d (crit %d) upper %d (hit %d) hit in the air %d, uppercuts %d, far: ticks %d still %d flips %d turned %d, found again %d times in %d ticks, in view %d, falls: crit %d plain %d noaim %d nosword %d other %d far %d, "
						+ "resets %d, step back ticks %d, hit after its own hit %d, crits taken %d, own combos %d of %d hits, hops %d, damage %.0f dealt / %.0f taken, rounds %d won %d lost, "
						+ "crit-back jumps %d (crit %d)",
				t.steps, t.hitsDealt, t.critShare() * 100f, t.sprintHitFraction() * 100f, t.hitsTaken, t.jumpsPerMinute(), t.jumpCritRate() * 100f,
				t.punishCrits, t.punishChances, t.steps == 0 ? 0f : (float) (t.rewardSum / t.steps),
				t.sweepHits, t.spamClicks, t.clicks, t.airSwings, t.airWhiffs, t.answers, t.hitsToAnswer, t.twoHitCombos, t.threeHitCombos,
				t.combosOver, t.combosUnanswered, t.combosSatThrough, t.pushHeld, t.pushTicks, t.chaseJumps, t.chaseCrits,
				t.chaseRuleJumps, t.chaseRuleCrits, t.faceRuleJumps, t.faceRuleCrits, t.upperRuleJumps, t.upperRuleHits, t.ruleJumpsHit,
				t.uppercuts, t.farTicks, t.farStillTicks, t.farFlips, t.farTurnCentiDeg / 100, t.reacquires, t.reacquireTicks, t.visibleTicks,
				t.fallCrits, t.fallPlain, t.fallNoAim, t.fallNoSword, t.fallOther, t.fallFar,
				t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken, t.combos, t.comboHits, t.hopRuleJumps, t.damageDealt, t.damageTaken, t.roundsWon, t.roundsLost,
				t.counterRuleJumps, t.counterRuleCrits);
	}

	private static void keepLast(Tally now, Tally last) {
		if (now.steps > 0) {
			last.clear();
			last.add(now);
		}
	}

	/**
	 * Writes what {@link #critLines()} says to a file, so that a report made when training is not
	 * running (after a fight against a person, say) still has what the drills had come to.
	 */
	private void keepCritLines() {
		List<String> lines = critLines();
		if (lines.isEmpty()) {
			return;
		}
		lines.add(0, String.format(Locale.ROOT, "when training last ran (%,d steps in total, %,d in the stage):", learner.totalSteps, curriculum.stepsInStage()));
		try {
			Files.write(SwordFiles.critNotes(), lines);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("Could not write {}", SwordFiles.critNotes(), e);
		}
	}

	/** Says so when a batch of crit drills has passed a lesson, or when a lesson was left because its drill gave no chances. */
	private void sayDrillResult(int lesson, Tally jump, Tally drills) {
		if (lesson < 0) {
			return;
		}
		if (lesson == Curriculum.DRILL_RANGE) {
			drills = jump;
		}
		boolean face = curriculum.current().critDrillFarJumpMax <= 0f;
		String what = lesson == Curriculum.DRILL_RANGE && face
				? String.format(Locale.ROOT, "Crit drill 1 of %d (the crit in its face) passed: %.0f%% of its %,d hits were critical, and %.0f%% of the %,d jumps it made ended in one.",
						Curriculum.DRILLS, drills.critShare() * 100f, drills.hitsDealt, drills.jumpCritRate() * 100f, drills.jumpsEnded)
				: lesson == Curriculum.DRILL_RANGE
				? String.format(Locale.ROOT, "Crit drill 1 of %d (the jump) passed: %.0f%% of its %,d hits were critical, and with the opponent farther off than right in its face it jumped %,d times in %,d ticks of standing there.",
						Curriculum.DRILLS, drills.critShare() * 100f, drills.hitsDealt, drills.farJumps, drills.farGroundTicks)
				: curriculum.critDrillLeft()
						? String.format(Locale.ROOT, "Crit drill 2 of %d (the punish crit) was left without being passed: the opponent's hits in it hardly ever left a punish crit there to be made "
								+ "(%,d chances in the last %,d hits, %,d of those hits critical), so the drill is not doing its job and holding the AI in it would teach nothing. Please send /swordai report.",
								Curriculum.DRILLS, drills.punishChances, drills.hitsTaken, drills.critsTaken)
						: String.format(Locale.ROOT, "Crit drill 2 of %d (the punish crit) passed: of the last %,d chances the opponent's hits left it, it took %,d.",
								Curriculum.DRILLS, curriculum.punishJudgedChances(), curriculum.punishJudgedCrits());
		String next = curriculum.critDrillGoal();
		say(what + (next != null ? " Next: " + next + "."
				: " The stage's own fights begin. Three in ten of them stay drills, so that it goes on practising both, "
						+ (curriculum.current().critDrillFirstKept ? "and both have to stay passed for the stage to be passed."
								: "and the punish crit has to stay passed for the stage to be passed. Whether it goes on jumping is left to what its fights pay.")));
	}

	void finishUpdate() {
		UpdateStats stats;
		try {
			stats = pendingUpdate.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("the training update was interrupted", e);
			return;
		} catch (ExecutionException e) {
			fail("the training update failed: " + e.getCause(), e.getCause());
			return;
		} finally {
			pendingUpdate = null;
		}

		int stageBefore = curriculum.stageIndex();
		// A person is not the stage's opponent: those fights are learned from, and that is all.
		boolean stageFights = stageFights();
		if (stageFights) {
			curriculum.record(pendingTally);
		}
		UpdateRecord record = UpdateRecord.of(System.currentTimeMillis(), learner.totalSteps, learner.updates,
				stageBefore, curriculum.botSkill(), pendingTally, stats);
		record.fps = mc.getFps();
		record.staleFrames = decisions == 0 ? 0f : (float) staleDecisions / decisions;
		record.lateFrames = decisions == 0 ? 0f : (float) lateDecisions / decisions;
		record.captureMillis = captureMillis();
		decisions = 0;
		staleDecisions = 0;
		lateDecisions = 0;
		record.speed = speed;
		record.headless = headless();
		record.opponent = opponentKind();
		history.add(record);
		if (!Float.isNaN(stats.anchorKl)) {
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "drift from the anchor: KL %.4f (weight %.2f), update KL %.4f, actor learning rate %.1e",
					stats.anchorKl, cfg.anchorWeight, stats.approxKl, stats.actorLr));
		}
		SwordFiles.appendHistory(record);
		lastRecord = record;
		long now = System.nanoTime();
		if (paceMarkNanos != 0L && now > paceMarkNanos) {
			stepsPerSecond = (float) ((learner.totalSteps - paceMarkSteps) / ((now - paceMarkNanos) / 1e9));
		}
		paceMarkNanos = now;
		paceMarkSteps = learner.totalSteps;

		if (!stageFights) {
			// Nothing is passed and nobody is judged on these.
		} else if (curriculum.tryPromote()) {
			criticOnlyLeft = cfg.criticWarmupUpdates;
			StageSpec next = curriculum.current();
			reopenHeads();
			String shortOf = curriculum.passedShortOf();
			say(String.format(Locale.ROOT, "Stage %d %s. Now stage %d/%d: %s - %s", stageBefore + 1,
					shortOf == null ? "learned" : "left behind after its full time, still short of one thing (" + shortOf + "), which the next stages go on teaching",
					curriculum.stageIndex() + 1, curriculum.stageCount(), next.name, next.goal));
			try {
				Files.copy(SwordFiles.checkpoint(), SwordFiles.stageCheckpoint(stageBefore + 1), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not keep a copy of the stage checkpoint", e);
			}
		} else if (curriculum.adaptSkill()) {
			say(String.format(Locale.ROOT, "Sparring bot skill is now %.0f%%", curriculum.botSkill() * 100f));
		}
		if (stageFights) {
			judgeSelfPlay();
		}
		saveCurriculum();
		// If the fight went on meanwhile, the step in progress was a copy's.
		resuming = playsOnWhileLearning();
		onUpdating(false);
		afterUpdate();
	}

	// ---- self-play ------------------------------------------------------------------------------

	/**
	 * Loads the saved copies of the network that play the bot (making the newest from the network as
	 * it is now when a stage against a copy begins) and hands them to the server. Only called while
	 * no update is running.
	 */
	void installRival(IntegratedServer server) throws IOException {
		Path champion = SwordFiles.champion();
		// A stage against a copy that has just begun fights the agent as it is now, whatever copy an
		// earlier stage or an earlier run left behind.
		boolean fresh = curriculum.takeFreshCopy();
		if (fresh || !Files.exists(champion)) {
			if (!curriculum.fixedCopy()) {
				// The copy it has just left behind (the duel's) is the first of the older ones.
				SwordFiles.retireChampion();
			}
			learner.save(champion);
			saveCurriculum();
			say(curriculum.fixedCopy()
					? "The duel begins: from here it fights a saved copy of itself as it is right now. The duel is won once it beats that copy four times out of five."
					: "Self-play begins: from here it fights a saved copy of itself. Whenever it clearly beats the copy, it becomes the copy to beat.");
		}
		float frameAgeTicks = frameAgeTicks();
		int headMask = curriculum.current().headMask;
		List<LoadedFighter> next = new ArrayList<>();
		next.add(LoadedFighter.loadRival(champion, cfg, curriculum, headMask, frameAgeTicks));
		if (!curriculum.fixedCopy()) {
			for (Path older : SwordFiles.olderChampions()) {
				try {
					next.add(LoadedFighter.loadRival(older, cfg, curriculum, headMask, frameAgeTicks));
				} catch (IOException | RuntimeException e) {
					// From a version whose network had another shape: it cannot play any more.
					SwordAi.LOGGER.warn("self-play: skipping the older copy " + older.getFileName() + ": " + e);
				}
			}
		}
		// And the old model, as it played; see Config.oldModelShare.
		int oldAt = -1;
		if (!curriculum.fixedCopy() && cfg.oldModelShare > 0f && Files.exists(SwordFiles.oldModel())) {
			try {
				LoadedFighter old = LoadedFighter.loadRival(SwordFiles.oldModel(), SwordFiles.loadOldModelConfig(cfg), curriculum, headMask, frameAgeTicks);
				old.fighter.swingNoEarlierThan(OLD_MODEL_CHARGE, 0);
				old.fighter.rulesNoEarlierThan(OLD_MODEL_CHARGE);
				next.add(old);
				oldAt = next.size() - 1;
			} catch (IOException | RuntimeException e) {
				SwordAi.LOGGER.warn("self-play: skipping the old model: " + e);
			}
		}
		oldModelIndex = oldAt;
		handOver(server, next, frameAgeTicks);
	}

	/**
	 * Before the duel: saves the network as it is now and loads that copy to play the bot in the combo
	 * drills. Only called while no update is running.
	 */
	void installDrillCopy(IntegratedServer server) throws IOException {
		Path file = SwordFiles.drillCopy();
		learner.save(file);
		oldModelIndex = -1;
		float frameAgeTicks = frameAgeTicks();
		List<LoadedFighter> next = new ArrayList<>();
		next.add(LoadedFighter.loadRival(file, cfg, curriculum, curriculum.current().headMask, frameAgeTicks));
		boolean first = rivals.isEmpty();
		handOver(server, next, frameAgeTicks);
		if (first) {
			say(String.format(Locale.ROOT, "Combo drills are on: about %.0f%% of this stage's fights are short ones against a saved copy of itself that starts on top of it and lands the first hits, "
					+ "so that it learns to get out of a combo. They are not counted towards passing the stage.", curriculum.current().drillShare * 100f));
		}
	}

	/** Makes these copies the ones that play the bot, and lets go of the ones they replace. */
	void handOver(IntegratedServer server, List<LoadedFighter> next, float frameAgeTicks) {
		List<LoadedFighter> previous = rivals;
		rivals = next;
		BotPilot[] pilots = new BotPilot[next.size()];
		for (int i = 0; i < pilots.length; i++) {
			pilots[i] = new BotPilot(next.get(i).fighter, frameAgeTicks);
		}
		// From here on the copies belong to the server thread; the ones they replace are let go there too.
		server.execute(() -> {
			ArenaServer.setRivals(pilots);
			for (LoadedFighter old : previous) {
				old.close();
			}
		});
	}

	/** The winner survives: after an update, see whether the agent has earned the copy's place, or lost its own. */
	void judgeSelfPlay() {
		if (!curriculum.selfPlay()) {
			return;
		}
		int wins = curriculum.selfWins();
		int fights = wins + curriculum.selfLosses();
		Curriculum.SelfPlay verdict = curriculum.judgeSelfPlay(rivals.size() > 1);
		if (verdict == Curriculum.SelfPlay.UNDECIDED) {
			return;
		}
		try {
			if (verdict == Curriculum.SelfPlay.CHALLENGER_WINS) {
				// The copy it has beaten joins the older ones; the update that just finished saved
				// exactly the network that won those fights.
				SwordFiles.retireChampion();
				Files.copy(SwordFiles.checkpoint(), SwordFiles.champion(), StandardCopyOption.REPLACE_EXISTING);
				say(String.format(Locale.ROOT, "It won %d of its last %d fights against the copy of itself, so it is now the one to beat (generation %d).",
						wins, fights, curriculum.generation()));
			} else {
				long steps = learner.totalSteps;
				long updates = learner.updates;
				learner.load(SwordFiles.champion());
				learner.totalSteps = steps;
				learner.updates = updates;
				learner.save(SwordFiles.checkpoint());
				criticOnlyLeft = cfg.criticWarmupUpdates;
				say(String.format(Locale.ROOT, "It won only %d of its last %d fights against the copy of itself and had stopped improving, so it starts again from the copy.",
						wins, fights));
			}
			IntegratedServer server = mc.getSingleplayerServer();
			if (verdict == Curriculum.SelfPlay.CHALLENGER_WINS && server != null && phase != Phase.STOPPED) {
				installRival(server);
			}
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("self-play: could not exchange the networks", e);
			say("Self-play could not swap the networks (" + e + "); it carries on against the same copy.");
		}
	}

	void saveCurriculum() {
		try {
			curriculum.save(SwordFiles.curriculum());
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not save the curriculum state", e);
		}
	}

	// ---- stopping -------------------------------------------------------------------------------

	/**
	 * Saves what was learned. If an update is running it is given time to finish, since it saves the
	 * checkpoint itself; otherwise the checkpoint is written here.
	 *
	 * @param was the phase the trainer was in when it was told to stop
	 */
	void saveProgress(Phase was) {
		if (demo != null) {
			// The user played: nothing was learned, and the fights are already on disk.
			return;
		}
		if (pendingUpdate != null) {
			try {
				// The update already saves the checkpoint; give it time to finish rather than lose it.
				pendingUpdate.get(60, TimeUnit.SECONDS);
				finishUpdate();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException | TimeoutException e) {
				SwordAi.LOGGER.warn("the last training update did not finish cleanly", e);
			}
			phase = Phase.STOPPED;
		} else if (mode == Mode.TRAIN && was != Phase.WAIT_ARENA && was != Phase.SELFTEST) {
			learner.interrupt();
			try {
				learner.save(learnedFile());
				if (lostCases != null) {
					SwordFiles.saveLostCases(lostCases);
				}
			} catch (IOException e) {
				say("Could not save the checkpoint: " + e);
			}
			saveCurriculum();
		}
	}

	/** Ends the learning side for good. */
	void closeLearning() {
		updater.shutdownNow();
		learner.close();
	}

	/** Takes the arena down and lets go of the copies that were playing in it. */
	void releaseArena() {
		IntegratedServer server = mc.getSingleplayerServer();
		List<LoadedFighter> copies = rivals;
		rivals = new ArrayList<>();
		oldModelIndex = -1;
		if (server != null) {
			// The arena goes first, on its own thread; only then are the copies it was using let go.
			server.execute(() -> {
				ArenaServer.stop(server);
				for (LoadedFighter copy : copies) {
					copy.close();
				}
			});
		} else {
			for (LoadedFighter copy : copies) {
				copy.close();
			}
		}
	}

	void fail(String message, Throwable error) {
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

	/** A line in the chat; safe from whichever thread the training runs on. */
	void say(String message) {
		SwordAi.LOGGER.info(message);
		if (mc.isSameThread()) {
			showInChat(message);
		} else {
			mc.execute(() -> showInChat(message));
		}
	}

	private void showInChat(String message) {
		if (mc.gui != null) {
			mc.gui.getChat().addMessage(Component.literal("[SwordAI] " + message));
		}
	}

	/** What the crit stage's drills and its own fights have come to lately, for the status and the report; empty in any other stage. */
	List<String> critLines() {
		List<String> out = new ArrayList<>();
		StageSpec current = curriculum.current();
		// The drills are the stage's own; a fight against a person has none.
		if (mode == Mode.TRAIN && current.critDrillShare > 0f && stageFights()) {
			String goal = curriculum.critDrillGoal();
			boolean face = current.critDrillFarJumpMax <= 0f;
			if (goal != null) {
				boolean held = curriculum.critDrillLesson() == Curriculum.DRILL_PUNISH || !face;
				out.add("now " + goal + ". Every fight of this stage is that drill until it is passed"
						+ (held ? "; it cannot walk or turn in it, only jump and swing while in the air" : "")
						+ (curriculum.critDrillLesson() == Curriculum.DRILL_PUNISH
								? String.format(Locale.ROOT, " (here not even jump: the opponent's hit is what puts it in the air). It is judged over %,d chances, not on its first good batch; so far %,d, of which it took %,d",
										Curriculum.PUNISH_LESSON_CHANCES, curriculum.punishChancesSoFar(), curriculum.punishCritsSoFar())
								: ""));
			} else {
				String forgotten = curriculum.critDrillsKept();
				out.add(String.format(Locale.ROOT, "crit drills: both lessons passed. %.0f%% of this stage's fights stay drills, a third each: the %s, the punish crit held as in its lesson, "
						+ "and the punish crit with all its keys. %s: %s",
						current.critDrillShare * 100f, face ? "crit in its face" : "jump",
						current.critDrillFirstKept ? "Still passed" : "The punish crit has to stay passed (the first drill was a lesson, and whether it still jumps is left to what its fights pay); it is",
						forgotten == null ? "yes" : "no, " + forgotten));
			}
			out.add(drillLine(face ? "the crit-in-its-face drill" : "the jump drill", now(jumpDrillTally, lastJumpDrill)));
			out.add(drillLine("the punish drill, held", now(punishDrillTally, lastPunishDrill)));
			if (goal == null) {
				out.add(drillLine("the punish drill with all its keys", now(freeDrillTally, lastFreeDrill)));
			}
		}
		if (mode == Mode.TRAIN && current.reward.wastedJump > 0f && !curriculum.critDrillsOnly()) {
			out.add(fightLine("in its own fights since the last update, against the opponent that a sprint hit knocks away", tally));
			if (current.traderShare > 0f) {
				out.add(fightLine(String.format(Locale.ROOT, "and against the one that cannot be kept away (%.0f%% of its fights), lately", current.traderShare * 100f),
						now(traderTally, lastTrader)));
			}
		}
		if (mode == Mode.TRAIN && current.slowShare > 0f && !curriculum.critDrillsOnly()) {
			out.add(String.format(Locale.ROOT, "by the opponent's connection, lately: slow (knocked back %.2f to %.2f s late, %.0f%% of the fights) %s; ordinary (none to %.1f s late) %s",
					current.slowKnockbackMinTicks / 20f, current.slowKnockbackMaxTicks / 20f, current.slowShare * 100f,
					(slowCount.steps > 0 ? slowCount : lastSlow).line(), current.pathLagTicks / 10f, (quickCount.steps > 0 ? quickCount : lastQuick).line()));
		}
		return out;
	}

	private static String fightLine(String what, Tally t) {
		if (t.steps == 0) {
			return what + ": none fought yet";
		}
		return String.format(Locale.ROOT, "%s: %,d hits, %,d of them critical and %,d sprint hits; %,d jumps ended, %,d of them in a critical hit, %,d were made from too far off; "
						+ "%,d swings in the air, %,d of them at nothing; %,d hits taken (%,d of them critical hits by the opponent) left a punish crit to make %,d times, %,d made",
				what, t.hitsDealt, t.critHits, t.sprintHits, t.jumpsEnded, t.jumpCrits, t.farJumps, t.airSwings, t.airWhiffs,
				t.hitsTaken, t.critsTaken, t.punishChances, t.punishCrits);
	}

	private static Tally now(Tally sinceUpdate, Tally last) {
		return sinceUpdate.steps > 0 ? sinceUpdate : last;
	}

	private static String drillLine(String name, Tally drill) {
		if (drill.steps == 0) {
			return name + " lately: none played yet";
		}
		return String.format(Locale.ROOT, "%s lately: %,d hits, %.0f%% of them critical (%.0f%% sprint hits, %.0f%% made on the way up, %.0f%% sweeps); "
						+ "%.0f jumps a minute, %.0f%% of the %,d that ended came to a critical hit; with the opponent too far off for a jump it jumped %,d times in %,d ticks of standing there (%.1f%%); "
						+ "%,d swings in the air, %,d of them at nothing; it took %,d hits (%,d of them critical hits by the opponent), %,d of them left a punish crit to make, %,d made",
				name, drill.hitsDealt, drill.critShare() * 100f, drill.sprintHitFraction() * 100f, drill.uppercutShare() * 100f, drill.sweepShare() * 100f,
				drill.jumpsPerMinute(), drill.jumpCritRate() * 100f, drill.jumpsEnded, drill.farJumps, drill.farGroundTicks, drill.farJumpRate() * 100f,
				drill.airSwings, drill.airWhiffs, drill.hitsTaken, drill.critsTaken, drill.punishChances, drill.punishCrits);
	}

	/** Lines for {@code /swordai status}. */
	@Override
	public List<String> statusLines() {
		List<String> out = new ArrayList<>();
		StageSpec current = curriculum.current();
		Tally recent = mode == Mode.TRAIN ? curriculum.recent() : tally;
		out.add(String.format(Locale.ROOT, "%s, %s. Stage %d/%d: %s (%s)", mode, phaseName(),
				curriculum.stageIndex() + 1, curriculum.stageCount(), current.name, current.goal));
		out.add(String.format(Locale.ROOT, "%,d steps in total, %,d in this stage, %d updates", learner.totalSteps,
				curriculum.stepsInStage(), learner.updates));
		String pace = paceLine();
		if (pace != null) {
			out.add(pace);
		}
		out.add(String.format(Locale.ROOT, "recent: on target %.0f%%, steady %.0f%% of that, %.1f deg off on average, hits/min %.1f, dealt:taken %.1f",
				recent.onTarget() * 100f, recent.still() * 100f, recent.aimError(), recent.hitsPerMinute(), recent.hitRatio()));
		String picture = pictureLine();
		if (picture != null) {
			out.add(picture);
		}
		String jumpHelp = Report.jumpLine(curriculum);
		if (jumpHelp != null) {
			out.add(jumpHelp + String.format(Locale.ROOT, "; since the last update it pressed jump on %.0f%% of those ticks", tally.jumpWindowRate() * 100f));
		}
		if (tally.hitsTaken > 0 && (tally.critsTaken > 0 || current.bot.critChance > 0f)) {
			out.add(String.format(Locale.ROOT, "hits it took since the last update: %,d, of them critical hits: %,d (%.0f%%)",
					tally.hitsTaken, tally.critsTaken, 100f * tally.critsTaken / tally.hitsTaken));
		}
		out.addAll(critLines());
		String backHelp = Report.backLine(cfg, curriculum);
		if (backHelp != null) {
			out.add(backHelp + String.format(Locale.ROOT, "; since the last update it held S on %.0f%% of those ticks (%d of them)", tally.pushRate() * 100f, tally.pushTicks));
		}
		String rules = Report.rulesLine(cfg, curriculum);
		if (rules != null) {
			out.add(rules);
		}
		if (mode == Mode.TRAIN && current.drillShare > 0f) {
			String waiting = curriculum.drillBlocker();
			out.add(waiting != null
					? "combo drills: not yet, they start once it lands combos of its own against the stage's opponent (" + waiting + ")"
					: String.format(Locale.ROOT, "combo drills: about %.0f%% of the fights start with a copy of itself on top of it and its first %d to %d hits landed; "
							+ "in the drills since the last update: comboed %d times, backed out of %d and came back with a hit, hit its way out of %d, held S in %.0f%% of the ticks after a combo hit; "
							+ "of the %d combos that were at two hits with it free to act, %d got a third (out by the second hit: %.0f%%)",
							current.drillShare * 100f, Math.min(current.drillMinHits, current.drillHits), current.drillHits,
							drillTally.combosTaken, drillTally.comboEscapes, drillTally.comboBreaks, drillTally.pushRate() * 100f,
							drillTally.twoHitCombos, drillTally.threeHitCombos, drillTally.outByTwoRate() * 100f));
		}
		if (curriculum.selfPlay() && mode == Mode.TRAIN && !judgesCopyFights()) {
			out.add("on the screen its fights against the copy are not counted as won or lost: your character sees the copy and clicks as a player does, "
					+ "a tick or two behind a copy that plays on the server itself. The duel and self-play move on in /swordai train headless; "
					+ "the screen is for the stretch before a fight");
		}
		if (curriculum.selfPlay() && fairCount.steps > 0) {
			out.add(String.format(Locale.ROOT, "fights on equal terms against a copy of itself since this training was started: %d won, %d lost (%s)",
					fairWon, fairLost, fairCount.brief()));
		}
		if (curriculum.selfPlay() && !curriculum.fixedCopy()) {
			out.add(String.format(Locale.ROOT, "self-play generation %d: %d won, %d lost against the current copy (it takes the copy's place at 65%% of 20 or more; "
							+ "counted are the fights on equal terms, about one in four: nothing late for either, the same knockback, and they start facing each other)",
					curriculum.generation(), curriculum.selfWins(), curriculum.selfLosses()));
			if (rivals.size() > 1) {
				out.add(String.format(Locale.ROOT, "and %d won, %d lost against the %d copies before it (it also needs half of 8 or more of those)",
						curriculum.oldWins(), curriculum.oldLosses(), rivals.size() - 1));
			}
		} else if (mode == Mode.TRAIN) {
			if (curriculum.fixedCopy()) {
				out.add(String.format(Locale.ROOT, "duel against the copy of itself from when the duel began: %d won, %d lost in the recent fights on equal terms "
								+ "(about one in four: nothing late for either, the same knockback, and they start facing each other; the rest are fought and learned from, and not counted)",
						recent.wins, recent.losses));
			}
			String blocker = curriculum.promotionBlocker();
			out.add("next stage: " + (blocker == null ? "ready, promotes at the next update" : blocker));
		}
		return out;
	}
}
