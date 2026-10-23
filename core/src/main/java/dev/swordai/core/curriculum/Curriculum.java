package dev.swordai.core.curriculum;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.config.Config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

/** Tracks which stage the agent is on, decides promotion, and paces the final stage's difficulty. */
public final class Curriculum {
	private static final int SKILL_MIN_FIGHTS = 10;
	/** Hits landed from very close over the recent window below which that threshold is not applied. */
	private static final int CLOSE_MIN_HITS = 20;
	/** Chances of a punish crit over the recent window below which the punish-crit threshold is not applied. */
	private static final int PUNISH_MIN_CHANCES = 20;
	/** Jumps over the recent window below which nothing is said about what comes of its jumps. */
	static final int JUMP_MIN_JUMPS = 20;

	/**
	 * The lessons of the crit drills, in the order they are taken (see {@code StageSpec.critDrillPass}).
	 * In both the fighter's feet and camera are held for it.
	 *
	 * <p>The jump: an opponent that stands still, and every few seconds is somewhere else. Half of the
	 * time it is right in the fighter's face: jump, and swing on the way down with the weapon back.
	 * The rest of the time it is farther off, at the range a sprint hit is made from or beyond:
	 * leave the jump alone; one made there gets no swing and costs. The only thing that tells the two
	 * apart beforehand is how large the opponent is on screen.
	 *
	 * <p>Where the jump is wanted is drawn tight on purpose. What a drill teaches becomes a habit, and
	 * a habit can be added to but not taken back. In 0.13.1 the jump was wanted anywhere within reach;
	 * in a fight the opponent is within reach whenever a sprint hit is on, so it jumped then, and a
	 * critical hit knocks nobody back: its combos were gone. Now it starts out jumping only at an
	 * opponent in its face, and whether a jump from a little farther pays as well is left to real
	 * fights, where taking it up is something it can learn.
	 *
	 * <p>When to jump and when not are taught together, from the first jump on, and that is the
	 * point. In 0.13.0 the timing came first, on an opponent that was always in reach, and the range
	 * after it. By then jumping was a habit: it jumped on nine ticks in ten wherever it stood. A habit
	 * like that cannot be unlearned one tick at a time, because holding back for a tick changes
	 * nothing when the next tick jumps anyway; a stand-in for the drill, played by the real network
	 * and learner ({@code RangeLessonTest}), still jumped at the far opponent on nine ticks in ten
	 * after 100,000 steps of the second lesson. Taught together from a fresh jump key, the same
	 * stand-in has it in about 55,000 steps.
	 */
	public static final int DRILL_RANGE = 0;
	/**
	 * The punish crit: an opponent that jumps in for critical hits and stays on top of the fighter.
	 * Its hit knocks the fighter up; the swing is kept for the way down, and made then if the
	 * opponent is still in reach. No jump key in this one: the only way into the air is the
	 * opponent's hit, so every critical hit made here is a punish crit.
	 */
	public static final int DRILL_PUNISH = 1;
	/** How many lessons there are; also what {@link #critDrillLesson()} says once all are passed. */
	public static final int DRILLS = 2;
	/** Ticks of standing with the opponent out of reach, in a batch of drills, below which nothing is said about whether it jumps then. */
	public static final int CRIT_DRILL_MIN_FAR_TICKS = 200;
	/** Chances of a punish crit in the drills below which nothing is said about how many of them it takes. */
	public static final int CRIT_DRILL_MIN_CHANCES = 20;
	/**
	 * Chances of a punish crit the second lesson is judged over. It is passed on practice, not on its
	 * first good batch: in 0.13.2 it was passed on the first twenty-odd chances, which it took by the
	 * way it starts out, and that was all the practice it got.
	 */
	public static final int PUNISH_LESSON_CHANCES = 150;
	/**
	 * After the lessons the drills go on, and have to stay passed for the stage to be passed. If this
	 * many steps of the jump drill go by without enough hits to judge it by, it has stopped making
	 * critical hits there, and that is not passed either.
	 */
	static final int KEEP_JUMP_STEPS = 2400;
	/**
	 * The punish drill is only a lesson if the opponent is still in reach when the fighter comes down,
	 * at least some of the time. If, over this many hits taken in it, fewer than
	 * {@link #PUNISH_DRILL_MIN_CHANCE_RATE} of them left a punish crit there to be made, the fault is
	 * the drill's and not the fighter's: the lesson is left, and said to have been (see
	 * {@link #critDrillLeft()}), rather than holding the fighter in it for ever.
	 */
	static final int PUNISH_DRILL_TRIAL_HITS = 600;
	static final float PUNISH_DRILL_MIN_CHANCE_RATE = 0.1f;
	/** Hits of the opponent's combos over the recent window below which nothing is said about getting out of them. */
	static final int OUT_MIN_COMBO_HITS = 30;
	/** Self-play: fights against the current champion before the challenger is judged at all. */
	static final int SELF_MIN_FIGHTS = 20;
	/** Self-play: the challenger takes the champion's place once it wins this share of them. */
	static final float SELF_WIN_RATE = 0.65f;
	/** Self-play: with this many fights and still no verdict, the older half stops counting. */
	static final int SELF_MAX_FIGHTS = 40;
	/** Self-play: a challenger that wins no more than this share of thirty fights has lost its way. */
	static final float SELF_COLLAPSE_RATE = 0.10f;
	static final int SELF_COLLAPSE_FIGHTS = 30;
	/** Self-play: after this many replacements in a row the challenger is left to work it out. */
	private static final int SELF_MAX_REPLACEMENTS = 2;
	/**
	 * Self-play: to take the newest copy's place, a challenger must also hold its own against the
	 * copies from before it: at least this share of at least this many fights against them. A style
	 * that beats the newest copy but loses to the ones it replaced would only send the fighters
	 * round in a circle.
	 */
	static final float SELF_OLD_WIN_RATE = 0.5f;
	static final int SELF_OLD_MIN_FIGHTS = 8;
	/** Stages fought against a copy replay the scripted fights at least this often, so they are not forgotten. */
	static final float SELF_REHEARSAL = 0.3f;
	/**
	 * Of those replayed fights, the share that goes to the scripted opponent that jumps in for critical
	 * hits. A copy of the fighter crits no more than the fighter does, so without these it could reach
	 * the end of the curriculum having hardly met an opponent that does; people who crit do it all the time.
	 */
	static final float SELF_CRIT_SHARE = 0.5f;

	/**
	 * The push towards a jump right after a swing (see {@code JumpNudge}) starts at this strength. Added
	 * to a head that rests on "no jump" by {@link #REOPEN_BIAS}, it comes to a jump on about every
	 * other of those ticks.
	 */
	static final float NUDGE_START = 3f;
	static final float NUDGE_MAX = 12f;
	/** How far the push moves per update for a jump rate that is off its target by the whole range. */
	static final float NUDGE_GAIN = 4f;
	/** Fewer ticks of that situation than this in an update say too little to steer by. */
	static final int NUDGE_MIN_TICKS = 40;
	/**
	 * The same for the push towards S, which has far fewer ticks to go by: the ones right after a hit
	 * of a combo with the opponent still within three blocks, and a hit throws the fighter beyond
	 * that in a tick or two. With forty asked for, most updates of a fight against a copy of itself
	 * had too few and the push stayed where it was (0.14.0: three updates in eighteen moved it).
	 */
	static final int BACK_MIN_TICKS = 12;
	/** See {@link #load}: runs saved under rules from this version on keep a stage's clock through an update that does not change the stage. */
	static final int CLOCK_KEPT_FROM_RULES = 24;
	/** How firmly a reopened head rests on its default: about one tick in twenty goes the other way. */
	public static final float REOPEN_BIAS = 3f;

	/** Hits landed over the recent window below which it is too early to say whether the agent can combo. */
	static final int DRILL_MIN_HITS = 30;

	/** What {@link #judgeSelfPlay} decided. */
	public enum SelfPlay {
		/** Nothing yet: the fight goes on. */
		UNDECIDED,
		/** The challenger is clearly ahead: it becomes the champion every later challenger must beat. */
		CHALLENGER_WINS,
		/** The challenger has collapsed: it is replaced by a copy of the champion. */
		CHALLENGER_REPLACED
	}

	private final List<StageSpec> stages;
	private final ArrayDeque<Tally> window = new ArrayDeque<>();
	private final Tally windowTotal = new Tally();
	private final Tally skillTally = new Tally();

	private int stage;
	private long stepsInStage;
	private float botSkill;
	/** Self-play: which champion is being fought (the first is the network as it left the duel). */
	private int generation = 1;
	private int selfWins;
	private int selfLosses;
	private int oldWins;
	private int oldLosses;
	private int selfReplacements;
	/** The copy to fight has to be made anew from the agent as it is now: a stage against a copy has just begun. */
	private boolean freshCopy;
	/** See {@link #passedShortOf()}. */
	private String passedShortOf;
	/** See {@link #stageBegunAgain()}. */
	private boolean stageBegunAgain;
	/** See {@link #sentBackFrom()}. */
	private int sentBackFrom = -1;
	/** See {@link #jumpNudge()}. */
	private float jumpNudge;
	/** See {@link #backNudge()}. */
	private float backNudge;
	private boolean pushKeptFromLoad;
	/** The lesson the crit drills are at; {@link #DRILLS} once the last is passed. See {@link #critDrillsOnly()}. */
	private int critDrill;
	/** Hits taken in the punish drill so far, and how many of them left a punish crit there to be made. */
	private long punishDrillHits;
	private long punishDrillChances;
	/** Chances of a punish crit in the drills since they were last judged, and how many of them were taken. */
	private long punishChancesSince;
	private long punishCritsSince;
	private long punishJudgedChances;
	private long punishJudgedCrits;
	/** See {@link #critDrillLeft()}. */
	private boolean critDrillLeft;
	/** After the lessons: whether the drills it still does are still passed, and what has been gathered towards judging that again. */
	private boolean jumpKept = true;
	private boolean punishKept = true;
	private final Tally keptJump = new Tally();
	private final Tally keptPunish = new Tally();
	/** See {@link #takeReopenHeads()}. */
	private int reopenHeads;

	public Curriculum(List<StageSpec> stages) {
		if (stages.isEmpty()) {
			throw new IllegalArgumentException("the curriculum needs at least one stage");
		}
		this.stages = stages;
	}

	public int stageIndex() {
		return stage;
	}

	public int stageCount() {
		return stages.size();
	}

	public StageSpec current() {
		return stages.get(stage);
	}

	public StageSpec stage(int index) {
		return stages.get(index);
	}

	public long stepsInStage() {
		return stepsInStage;
	}

	public float botSkill() {
		return botSkill;
	}

	/** True while the current stage's opponent is a copy of the agent itself. */
	public boolean selfPlay() {
		return BotProfile.SELF.equals(current().bot.movement);
	}

	/** True while that copy is one that stays as it is: the fighter the agent was when the stage began. */
	public boolean fixedCopy() {
		return selfPlay() && current().bot.fixedCopy;
	}

	/** Whether the copy has to be made anew before the next fight. */
	public boolean freshCopyWanted() {
		return freshCopy;
	}

	/** Reads and clears {@link #freshCopyWanted}: the caller makes the copy. */
	public boolean takeFreshCopy() {
		boolean wanted = freshCopy;
		freshCopy = false;
		return wanted;
	}

	public int generation() {
		return generation;
	}

	/** Fights won and lost against the current champion since it was last judged. */
	public int selfWins() {
		return selfWins;
	}

	public int selfLosses() {
		return selfLosses;
	}

	/** Fights won and lost against the copies from before the current one, since it was last judged. */
	public int oldWins() {
		return oldWins;
	}

	public int oldLosses() {
		return oldLosses;
	}

	/**
	 * Self-play: the winner survives. Once enough fights have been decided, a challenger that is
	 * clearly ahead takes the champion's place (and the count starts over against it), and one that
	 * has all but stopped winning is replaced by a copy of the champion. Call after every update.
	 */
	public SelfPlay judgeSelfPlay() {
		return judgeSelfPlay(false);
	}

	/**
	 * @param olderCopies whether some of the fights are against copies from before the current one;
	 *                    if so, the challenger has to hold its own against those as well
	 */
	public SelfPlay judgeSelfPlay(boolean olderCopies) {
		int fights = selfWins + selfLosses;
		if (!selfPlay() || fights < SELF_MIN_FIGHTS) {
			return SelfPlay.UNDECIDED;
		}
		float rate = (float) selfWins / fights;
		int oldFights = oldWins + oldLosses;
		boolean holdsAgainstOlder = !olderCopies
				|| (oldFights >= SELF_OLD_MIN_FIGHTS && (float) oldWins / oldFights >= SELF_OLD_WIN_RATE);
		// A copy that is meant to stay is never replaced by the agent; beating it is what ends the stage.
		if (rate >= SELF_WIN_RATE && !current().bot.fixedCopy && holdsAgainstOlder) {
			generation++;
			selfWins = 0;
			selfLosses = 0;
			oldWins = 0;
			oldLosses = 0;
			selfReplacements = 0;
			return SelfPlay.CHALLENGER_WINS;
		}
		if (fights >= SELF_COLLAPSE_FIGHTS && rate <= SELF_COLLAPSE_RATE && selfReplacements < SELF_MAX_REPLACEMENTS) {
			selfWins = 0;
			selfLosses = 0;
			oldWins = 0;
			oldLosses = 0;
			selfReplacements++;
			return SelfPlay.CHALLENGER_REPLACED;
		}
		if (fights >= SELF_MAX_FIGHTS) {
			// Still undecided: let the recent fights weigh more than the old ones.
			selfWins /= 2;
			selfLosses /= 2;
			oldWins /= 2;
			oldLosses /= 2;
		}
		return SelfPlay.UNDECIDED;
	}

	/**
	 * Why the current stage's combo drills are not being run, or null when they are (or the stage has
	 * none). They wait until the agent's own recent fights show that it combos: see
	 * {@link StageSpec#drillFromSprintHits}.
	 */
	public String drillBlocker() {
		StageSpec st = current();
		if (st.drillShare <= 0f || (st.drillFromSprintHits <= 0f && st.drillFromCombo <= 0f)) {
			return null;
		}
		Tally t = windowTotal;
		if (t.hitsDealt < DRILL_MIN_HITS) {
			return (DRILL_MIN_HITS - t.hitsDealt) + " more hits needed to tell whether it combos yet";
		}
		String b = below("sprint hits", t.sprintHitFraction(), st.drillFromSprintHits, true);
		return b != null ? b : below("combo", t.meanCombo(), st.drillFromCombo, false);
	}

	/** Whether a fight of the current stage may be a combo drill right now. */
	public boolean drillsReady() {
		return current().drillShare > 0f && drillBlocker() == null;
	}

	/** A stage has just become the current one, by whatever way: what it asks for at its start is set up. */
	private void stageBegins() {
		reopenHeads = current().reopenHeads;
		jumpNudge = current().jumpNudgeRate > 0f ? NUDGE_START : 0f;
		// A push that was already on (the stage before kept it) carries over at the strength it had.
		backNudge = current().backNudgeRate > 0f || current().backNudgeKeep > 0f ? Math.max(NUDGE_START, backNudge) : 0f;
		critDrill = 0;
		punishDrillHits = 0;
		punishDrillChances = 0;
		punishChancesSince = 0;
		punishCritsSince = 0;
		critDrillLeft = false;
		jumpKept = true;
		punishKept = true;
		keptJump.clear();
		keptPunish.clear();
	}

	/** Fewer hits than this on the standing opponent say too little about how many of them are critical. */
	public static final int CRIT_DRILL_MIN_HITS = 30;

	/**
	 * True while the current stage is still in its crit drills and nothing else is fought: see
	 * {@code StageSpec.critDrillPass}.
	 */
	public boolean critDrillsOnly() {
		return current().critDrillPass > 0f && critDrill < DRILLS;
	}

	/** The lesson the crit drills are at ({@link #DRILL_RANGE} or {@link #DRILL_PUNISH}); {@link #DRILLS} once both are passed. */
	public int critDrillLesson() {
		return critDrill;
	}

	/**
	 * After {@link #recordCritDrills} has returned a lesson: true if that lesson was not passed but
	 * left, because the drill did not give the fighter the chances the lesson is made of.
	 */
	public boolean critDrillLeft() {
		return critDrillLeft;
	}

	/**
	 * What the crit drills since the last update came to: those in which the fighter is held, each
	 * kind in its own tally. While the lessons are being taken this passes them, one after the other;
	 * after the last, the stage's own fights begin. From then on the drills go on as a share of the
	 * fights, and this keeps track of whether they are still passed (see {@link #critDrillsKept()}).
	 *
	 * @param jump   the drills against the opponent that stands still
	 * @param punish the drills against the one that jumps in for critical hits
	 * @return the lesson that these passed (or that was left, see {@link #critDrillLeft()}), or -1
	 */
	public int recordCritDrills(Tally jump, Tally punish) {
		critDrillLeft = false;
		StageSpec st = current();
		if (st.critDrillPass <= 0f) {
			return -1;
		}
		if (critDrill >= DRILLS) {
			keep(st, jump, punish);
			return -1;
		}
		int lesson = critDrill;
		boolean passed;
		if (lesson == DRILL_RANGE) {
			passed = jumpPassed(st, jump, st.critDrillPass);
		} else {
			// Gathered over as many batches as it takes: the lesson is passed on practice.
			punishChancesSince += punish.punishChances;
			punishCritsSince += punish.punishCrits;
			passed = false;
			if (punishChancesSince >= PUNISH_LESSON_CHANCES) {
				passed = punishCritsSince >= st.critDrillPunishPass * punishChancesSince;
				punishJudgedChances = punishChancesSince;
				punishJudgedCrits = punishCritsSince;
				punishChancesSince = 0;
				punishCritsSince = 0;
			}
			punishDrillHits += punish.hitsTaken;
			punishDrillChances += punish.punishChances;
			if (!passed && punishDrillHits >= PUNISH_DRILL_TRIAL_HITS
					&& punishDrillChances < PUNISH_DRILL_MIN_CHANCE_RATE * punishDrillHits) {
				critDrillLeft = true;
			}
		}
		if (!passed && !critDrillLeft) {
			return -1;
		}
		critDrill++;
		jumpKept = true;
		punishKept = true;
		keptJump.clear();
		keptPunish.clear();
		return lesson;
	}

	/** The chances of a punish crit gathered so far towards the {@link #PUNISH_LESSON_CHANCES} the second lesson is judged over, and how many were taken. */
	public long punishChancesSoFar() {
		return punishChancesSince;
	}

	public long punishCritsSoFar() {
		return punishCritsSince;
	}

	/** The chances the second lesson was last judged over, and how many of them were taken. */
	public long punishJudgedChances() {
		return punishJudgedChances;
	}

	public long punishJudgedCrits() {
		return punishJudgedCrits;
	}

	/**
	 * The first lesson's test: enough of its hits in the drill are critical. And, where the stage
	 * asks for it, it has stopped jumping at an opponent too far off for a jump.
	 *
	 * @param share the share of its hits that have to be critical
	 */
	private static boolean jumpPassed(StageSpec st, Tally t, float share) {
		return t.hitsDealt >= CRIT_DRILL_MIN_HITS && t.critShare() >= share
				&& (st.critDrillFarJumpMax <= 0f
				|| (t.farGroundTicks >= CRIT_DRILL_MIN_FAR_TICKS && t.farJumpRate() <= st.critDrillFarJumpMax));
	}

	/** After the lessons: the drills go on, and each is judged again whenever enough of it has been played. */
	private void keep(StageSpec st, Tally jump, Tally punish) {
		keptJump.add(jump);
		if (!st.critDrillFirstKept) {
			// A lesson that was passed, and no more is asked of it.
			jumpKept = true;
			keptJump.clear();
		} else if (keptJump.hitsDealt >= CRIT_DRILL_MIN_HITS && (st.critDrillFarJumpMax <= 0f || keptJump.farGroundTicks >= CRIT_DRILL_MIN_FAR_TICKS)) {
			jumpKept = jumpPassed(st, keptJump, st.critDrillKeep > 0f ? st.critDrillKeep : st.critDrillPass);
			keptJump.clear();
		} else if (keptJump.steps >= KEEP_JUMP_STEPS) {
			// Two minutes of the drill and not thirty hits: it has stopped fighting there.
			jumpKept = false;
			keptJump.clear();
		}
		keptPunish.add(punish);
		if (keptPunish.punishChances >= CRIT_DRILL_MIN_CHANCES) {
			punishKept = keptPunish.punishRate() >= st.critDrillPunishPass;
			keptPunish.clear();
		}
	}

	/**
	 * What is wrong with the crit drills it still does after their lessons, in a line, or null if they
	 * are still passed (or there are none). The stage is not passed while this says something: what
	 * was learned in the drills has to be there still when it moves on.
	 */
	public String critDrillsKept() {
		if (current().critDrillPass <= 0f || critDrill < DRILLS) {
			return null;
		}
		if (!jumpKept) {
			return current().critDrillFarJumpMax > 0f
					? "the jump drill, which it still does, is no longer passed: it has to crit the opponent in its face and leave the jump alone when the opponent is farther off"
					: "the crit-in-its-face drill, which it still does, is no longer passed: too few of its hits on the opponent that pushes into it and does not hit back are critical";
		}
		if (!punishKept) {
			return "the punish-crit drill, which it still does, is no longer passed: it takes too few of the chances the opponent's hits leave it";
		}
		return null;
	}

	/** What the lesson the crit drills are at asks for, in a line; null when there are none or all are passed. */
	public String critDrillGoal() {
		if (!critDrillsOnly()) {
			return null;
		}
		StageSpec st = current();
		if (critDrill == DRILL_RANGE && st.critDrillFarJumpMax <= 0f) {
			return String.format(Locale.ROOT, "crit drill 1 of %d, the crit in its face (with all its keys, against an opponent that never swings, keeps pushing into it and is hardly moved by a hit): "
					+ "%.0f%% of its hits have to be critical", DRILLS, st.critDrillPass * 100f);
		}
		return critDrill == DRILL_RANGE
				? String.format(Locale.ROOT, "crit drill 1 of %d, the jump (the opponent stands still, right in its face half of the time and farther off the rest): "
						+ "%.0f%% of its hits have to be critical, and it may jump on no more than %.0f%% of the ticks the opponent is farther off",
						DRILLS, st.critDrillPass * 100f, st.critDrillFarJumpMax * 100f)
				: String.format(Locale.ROOT, "crit drill 2 of %d, the punish crit (the opponent jumps in for critical hits and stays close): "
						+ "%.0f%% of the chances its hits leave have to be taken", DRILLS, st.critDrillPunishPass * 100f);
	}

	/**
	 * The heads that have to be put back to how a head starts before the stage that has just begun is
	 * played, as a bit set; the caller does it (see {@code Learner.reopenHead}). Reads and clears.
	 */
	public int takeReopenHeads() {
		int heads = reopenHeads;
		reopenHeads = 0;
		return heads;
	}

	/**
	 * The share of the ticks right after a swing on which the current stage wants jump pressed, for as
	 * long as it pushes for that: held, then let down to nothing. Zero when it does not push (any more).
	 */
	public float jumpNudgeTarget() {
		StageSpec st = current();
		if (st.jumpNudgeRate <= 0f || st.jumpNudgeSteps <= 0 || stepsInStage >= st.jumpNudgeSteps) {
			return 0f;
		}
		if (stepsInStage <= st.jumpNudgeHoldSteps) {
			return st.jumpNudgeRate;
		}
		return st.jumpNudgeRate * (st.jumpNudgeSteps - stepsInStage) / Math.max(1, st.jumpNudgeSteps - st.jumpNudgeHoldSteps);
	}

	/**
	 * How strongly the policy is being pushed towards a jump right after a swing; see {@code JumpNudge}.
	 * Steered after every update so that the jump is pressed about as often as {@link #jumpNudgeTarget}
	 * says: if the network leans against jumping, the push grows, so for the time the stage has set
	 * aside it cannot unlearn the jump before it has found out what a jump is for.
	 */
	public float jumpNudge() {
		return jumpNudgeTarget() > 0f ? jumpNudge : 0f;
	}

	/**
	 * The share of the ticks right after a hit of a combo it is taking on which the current stage
	 * wants S held, for as long as it pushes for that: held, then let down to nothing.
	 */
	public float backNudgeTarget() {
		StageSpec st = current();
		// Never below what the stage keeps it at for good; see StageSpec.backNudgeKeep.
		if (st.backNudgeRate <= 0f || st.backNudgeSteps <= 0 || stepsInStage >= st.backNudgeSteps) {
			return st.backNudgeKeep;
		}
		if (stepsInStage <= st.backNudgeHoldSteps) {
			return Math.max(st.backNudgeKeep, st.backNudgeRate);
		}
		return Math.max(st.backNudgeKeep,
				st.backNudgeRate * (st.backNudgeSteps - stepsInStage) / Math.max(1, st.backNudgeSteps - st.backNudgeHoldSteps));
	}

	/** How strongly the policy is being pushed towards S in those ticks; steered like {@link #jumpNudge()}. See {@code BackNudge}. */
	public float backNudge() {
		return backNudgeTarget() > 0f ? backNudge : 0f;
	}


	/** True once the stage that teaches following a plan ({@link StageSpec#ideas}) has been passed. */
	public boolean ideasLearned() {
		for (int i = 0; i < stages.size(); i++) {
			if (stages.get(i).ideas) {
				return stage > i;
			}
		}
		return false;
	}

	/** Jumps to a stage by hand; progress counters for it start over. */
	public void setStage(int index) {
		stage = Math.max(0, Math.min(stages.size() - 1, index));
		resetWindow();
		stepsInStage = 0;
		selfWins = 0;
		selfLosses = 0;
		oldWins = 0;
		oldLosses = 0;
		selfReplacements = 0;
		freshCopy = selfPlay();
		stageBegins();
	}

	/**
	 * The stage whose keys and rewards a fight against a real opponent is played and learned with.
	 * A real opponent does everything at once: it combos, it crits. So once the run has reached the
	 * stage before the one that has every key, such a fight is played as that one, with every key
	 * and with being comboed, getting out and critical hits all paid for. Earlier on, and from that
	 * stage onwards, it is played as the stage the run is in.
	 */
	public int fightStageIndex() {
		for (int i = 0; i < stages.size(); i++) {
			if (stages.get(i).headMask == ActionSpace.ALL_HEADS) {
				return stage >= i - 1 ? Math.max(stage, i) : stage;
			}
		}
		return stage;
	}

	/**
	 * Which stage the next episode plays. Mostly the current one; sometimes an earlier one so that
	 * skills learned before keep being exercised.
	 */
	public int pickEpisodeStage(Random rng, float rehearsalProbability) {
		return pickEpisodeStage(rng, rehearsalProbability, SELF_CRIT_SHARE);
	}

	/** @param critShare in self-play, the share of the scripted fights that goes to the crit stage */
	public int pickEpisodeStage(Random rng, float rehearsalProbability, float critShare) {
		if (stage == 0) {
			return 0;
		}
		if (!selfPlay()) {
			return rng.nextFloat() < rehearsalProbability ? rng.nextInt(stage) : stage;
		}
		// Fighting only itself, it would forget the kinds of opponent it is not: the one that rushes in,
		// the one that jumps in for crits, the one that backs off after its hits. So here rehearsal is
		// more frequent and goes to the scripted fights only, not to the aiming drills.
		if (rng.nextFloat() >= Math.max(rehearsalProbability, SELF_REHEARSAL)) {
			return stage;
		}
		int scripted = 0;
		int critJumper = -1;
		for (int i = 0; i < stage; i++) {
			if (scriptedFight(i)) {
				scripted++;
				if (stages.get(i).bot.critChance > 0f) {
					critJumper = i;
				}
			}
		}
		if (critJumper >= 0 && rng.nextFloat() < critShare) {
			return critJumper;
		}
		if (scripted == 0) {
			return rng.nextInt(stage);
		}
		int pick = rng.nextInt(scripted);
		for (int i = 0; i < stage; i++) {
			if (scriptedFight(i) && pick-- == 0) {
				return i;
			}
		}
		return stage;
	}

	private boolean scriptedFight(int index) {
		BotProfile bot = stages.get(index).bot;
		return bot.attacks && !BotProfile.SELF.equals(bot.movement);
	}

	/** Adds a batch of steps played on the current stage (rehearsal episodes are not passed here). */
	public void record(Tally batch) {
		if (batch.steps == 0) {
			return;
		}
		Tally copy = new Tally();
		copy.add(batch);
		window.addLast(copy);
		windowTotal.add(copy);
		stepsInStage += batch.steps;
		skillTally.add(batch);
		float jumpTarget = jumpNudgeTarget();
		if (jumpTarget <= 0f) {
			jumpNudge = 0f;
		} else if (batch.jumpWindowTicks >= NUDGE_MIN_TICKS) {
			jumpNudge = Math.max(0f, Math.min(NUDGE_MAX, jumpNudge + NUDGE_GAIN * (jumpTarget - batch.jumpWindowRate())));
		}
		float backTarget = backNudgeTarget();
		if (backTarget <= 0f) {
			backNudge = 0f;
		} else if (batch.backTicks >= BACK_MIN_TICKS) {
			backNudge = Math.max(0f, Math.min(NUDGE_MAX, backNudge + NUDGE_GAIN * (backTarget - batch.backRate())));
		}
		if (selfPlay()) {
			selfWins += (int) batch.wins;
			selfLosses += (int) batch.losses;
			oldWins += (int) batch.oldWins;
			oldLosses += (int) batch.oldLosses;
		}

		int limit = current().promote.windowSteps;
		while (window.size() > 1 && windowTotal.steps - window.peekFirst().steps >= limit) {
			window.removeFirst();
			recomputeWindowTotal();
		}
	}

	/** Metrics over the recent window on the current stage. */
	public Tally recent() {
		return windowTotal;
	}

	/** Why the agent has not been promoted yet, or null when it is ready. */
	public String promotionBlocker() {
		Promotion p = current().promote;
		if (p.endless) {
			return "final stage";
		}
		if (critDrillsOnly()) {
			return "the crit drills come first, and nothing else is fought until the last is passed; now " + critDrillGoal();
		}
		String forgotten = critDrillsKept();
		if (forgotten != null) {
			return forgotten;
		}
		if (stepsInStage < p.minSteps) {
			return String.format(Locale.ROOT, "%,d more steps needed in this stage", p.minSteps - stepsInStage);
		}
		Tally t = windowTotal;
		String b;
		if ((b = below("on-head", t.onHead(), p.minOnHead, true)) != null) {
			return b;
		}
		if ((b = below("on-target", t.onTarget(), p.minOnTarget, true)) != null) {
			return b;
		}
		if ((b = below("steady aim", t.still(), p.minStill, true)) != null) {
			return b;
		}
		if (p.maxAimError > 0f && t.aimError() > p.maxAimError) {
			return String.format(Locale.ROOT, "aim error %.1f deg > %.1f", t.aimError(), p.maxAimError);
		}
		if ((b = below("in-range", t.inRange(), p.minInRange, true)) != null) {
			return b;
		}
		if ((b = below("click accuracy", t.clickAccuracy(), p.minClickAccuracy, true)) != null) {
			return b;
		}
		if (p.maxSpamRate > 0f && t.spamRate() > p.maxSpamRate) {
			return String.format(Locale.ROOT, "spam clicks %.0f%% > %.0f%%", t.spamRate() * 100f, p.maxSpamRate * 100f);
		}
		if ((b = below("full-charge hits", t.fullCharge(), p.minFullCharge, true)) != null) {
			return b;
		}
		if ((b = below("hits/min", t.hitsPerMinute(), p.minHitsPerMinute, false)) != null) {
			return b;
		}
		if ((b = below("sprint hits", t.sprintHitFraction(), p.minSprintHits, true)) != null) {
			return b;
		}
		if ((b = below("combo", t.meanCombo(), p.minCombo, false)) != null) {
			return b;
		}
		if ((b = below("W-tap rate", t.tapRate(), p.minTapRate, true)) != null) {
			return b;
		}
		if ((b = below("hits dealt:taken", t.hitRatio(), p.minHitRatio, false)) != null) {
			return b;
		}
		// Only asked once the opponent has been in its face often enough to tell.
		if (t.closeHits >= CLOSE_MIN_HITS && (b = below("sprint hits from close", t.closeSprint(), p.minCloseSprint, true)) != null) {
			return b;
		}
		// How it fights rather than whether it can: asked for, but not for ever.
		if (!styleWaived() && (b = styleBlocker()) != null) {
			return p.styleOptionalAfterSteps <= 0 ? b
					: String.format(Locale.ROOT, "%s (it moves on without that after %,d more steps in this stage)", b, p.styleOptionalAfterSteps - stepsInStage);
		}
		if (p.minBotSkill > 0f && botSkill < p.minBotSkill - 1e-4f) {
			return String.format(Locale.ROOT, "bot skill %.0f%% < %.0f%%", botSkill * 100f, p.minBotSkill * 100f);
		}
		if (p.minWinRate > 0f) {
			long fights = t.wins + t.losses;
			if (fights < p.minFights) {
				return (p.minFights - fights) + " more decided fights needed";
			}
			if ((b = below("win rate", t.winRate(), p.minWinRate, true)) != null) {
				return b;
			}
		}
		return null;
	}

	/** The first of the stage's style thresholds that is not met, or null: see {@link Promotion#styleOptionalAfterSteps}. */
	private String styleBlocker() {
		Promotion p = current().promote;
		Tally t = windowTotal;
		String b;
		// Punish crits are only asked of a fighter that is being crit: one that keeps the opponent from
		// landing them has solved the same problem another way.
		if (t.punishChances >= PUNISH_MIN_CHANCES && (b = below("punish crits", t.punishRate(), p.minPunishRate, true)) != null) {
			return b;
		}
		if ((b = below("strafing as planned", t.mixStrafe(), p.minMixStrafe, true)) != null) {
			return b;
		}
		if ((b = below("held hits", t.mixTiming(), p.minMixTiming, true)) != null) {
			return b;
		}
		// Getting out of combos is asked of a fighter that has been in enough of them to tell.
		if (p.minBackRate > 0f || p.minOutRate > 0f) {
			if (t.comboedHits < OUT_MIN_COMBO_HITS) {
				return (OUT_MIN_COMBO_HITS - t.comboedHits) + " more hits of combos on it needed to tell whether it gets out of them";
			}
			if ((b = below("holding S when comboed", t.backRate(), p.minBackRate, true)) != null) {
				return b;
			}
			if ((b = below("combo hits it got out after", t.outRate(), p.minOutRate, true)) != null) {
				return b;
			}
		}
		if ((b = below("critical hits", t.critShare(), p.minCritShare, true)) != null) {
			return b;
		}
		// Asked of a fighter that jumps often enough to tell. One that gets its critical hits without
		// jumping, from the opponent's knockback, has nothing to answer for here.
		return t.jumpsEnded >= JUMP_MIN_JUMPS ? below("jumps that end in a critical hit", t.jumpCritRate(), p.minJumpCritRate, true) : null;
	}

	/** Whether the stage has been played for so long that its style thresholds no longer have to be met. */
	private boolean styleWaived() {
		Promotion p = current().promote;
		return p.styleOptionalAfterSteps > 0 && stepsInStage >= p.styleOptionalAfterSteps;
	}

	/**
	 * True after {@link #load} when the saved state was written under older rules and the stage it is
	 * in is one whose habit thresholds lapse with time: what it has to learn there has changed, so the
	 * time it has for that starts again.
	 */
	public boolean stageBegunAgain() {
		return stageBegunAgain;
	}

	/**
	 * After {@link #load}: the stage the run was in when it was saved, if it has been put back to an
	 * earlier one because that stage now teaches something it has not learned (see
	 * {@link StageSpec#lessonChangedIn}); -1 otherwise.
	 */
	public int sentBackFrom() {
		return sentBackFrom;
	}

	/**
	 * After {@link #tryPromote} has returned true: what the stage just passed was still short of when
	 * it was passed on time rather than on its numbers, or null when it had met everything.
	 */
	public String passedShortOf() {
		return passedShortOf;
	}

	private static String below(String name, float value, float threshold, boolean percent) {
		if (threshold <= 0f || value >= threshold) {
			return null;
		}
		return percent
				? String.format(Locale.ROOT, "%s %.0f%% < %.0f%%", name, value * 100f, threshold * 100f)
				: String.format(Locale.ROOT, "%s %.1f < %.1f", name, value, threshold);
	}

	/** Advances to the next stage when every criterion holds. Returns true if it did. */
	public boolean tryPromote() {
		if (stage >= stages.size() - 1 || promotionBlocker() != null) {
			return false;
		}
		passedShortOf = styleWaived() ? styleBlocker() : null;
		stage++;
		stepsInStage = 0;
		resetWindow();
		selfWins = 0;
		selfLosses = 0;
		oldWins = 0;
		oldLosses = 0;
		selfReplacements = 0;
		freshCopy = selfPlay();
		stageBegins();
		return true;
	}

	/**
	 * Final stage only: once enough fights have been decided, makes the bot sharper if the agent is
	 * winning comfortably and easier if it is being crushed. Returns true when the skill changed.
	 */
	public boolean adaptSkill() {
		if (!current().bot.scalesWithSkill || skillTally.wins + skillTally.losses < SKILL_MIN_FIGHTS) {
			return false;
		}
		float winRate = skillTally.winRate();
		float before = botSkill;
		if (winRate >= 0.6f) {
			botSkill = Math.min(1f, botSkill + 0.1f);
		} else if (winRate <= 0.3f) {
			botSkill = Math.max(0f, botSkill - 0.05f);
		}
		skillTally.clear();
		return botSkill != before;
	}

	private void resetWindow() {
		window.clear();
		windowTotal.clear();
		skillTally.clear();
	}

	private void recomputeWindowTotal() {
		windowTotal.clear();
		for (Tally t : window) {
			windowTotal.add(t);
		}
	}

	public void save(Path file) throws IOException {
		Properties p = new Properties();
		p.setProperty("stage", Integer.toString(stage));
		p.setProperty("stepsInStage", Long.toString(stepsInStage));
		p.setProperty("botSkill", Float.toString(botSkill));
		p.setProperty("generation", Integer.toString(generation));
		p.setProperty("selfWins", Integer.toString(selfWins));
		p.setProperty("selfLosses", Integer.toString(selfLosses));
		p.setProperty("freshCopy", Boolean.toString(freshCopy));
		p.setProperty("oldWins", Integer.toString(oldWins));
		p.setProperty("oldLosses", Integer.toString(oldLosses));
		p.setProperty("rules", Integer.toString(Config.CURRENT_VERSION));
		p.setProperty("jumpNudge", Float.toString(jumpNudge));
		p.setProperty("backNudge", Float.toString(backNudge));
		p.setProperty("critDrill", Integer.toString(critDrill));
		p.setProperty("punishDrillHits", Long.toString(punishDrillHits));
		p.setProperty("punishDrillChances", Long.toString(punishDrillChances));
		p.setProperty("punishChancesSince", Long.toString(punishChancesSince));
		p.setProperty("punishCritsSince", Long.toString(punishCritsSince));
		p.setProperty("jumpKept", Boolean.toString(jumpKept));
		p.setProperty("punishKept", Boolean.toString(punishKept));
		p.setProperty("reopenHeads", Integer.toString(reopenHeads));
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			p.store(w, "SwordAI curriculum state");
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	public void load(Path file) throws IOException {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		try {
			stage = Math.max(0, Math.min(stages.size() - 1, Integer.parseInt(p.getProperty("stage", "0"))));
			stepsInStage = Long.parseLong(p.getProperty("stepsInStage", "0"));
			botSkill = Float.parseFloat(p.getProperty("botSkill", "0"));
			generation = Math.max(1, Integer.parseInt(p.getProperty("generation", "1")));
			selfWins = Math.max(0, Integer.parseInt(p.getProperty("selfWins", "0")));
			selfLosses = Math.max(0, Integer.parseInt(p.getProperty("selfLosses", "0")));
			freshCopy = Boolean.parseBoolean(p.getProperty("freshCopy", "false"));
			oldWins = Math.max(0, Integer.parseInt(p.getProperty("oldWins", "0")));
			oldLosses = Math.max(0, Integer.parseInt(p.getProperty("oldLosses", "0")));
			jumpNudge = Math.max(0f, Math.min(NUDGE_MAX, Float.parseFloat(p.getProperty("jumpNudge", "0"))));
			backNudge = Math.max(0f, Math.min(NUDGE_MAX, Float.parseFloat(p.getProperty("backNudge", "0"))));
			// A push that the stage keeps on, in a run saved before it did: it starts where a push starts.
			pushKeptFromLoad = true;
			// (Up to 0.12.3 there was one drill, and the file said only whether it was passed.)
			boolean passedTheOnlyDrill = Boolean.parseBoolean(p.getProperty("critDrillsPassed", "false"));
			critDrill = Math.max(0, Math.min(DRILLS, Integer.parseInt(p.getProperty("critDrill", passedTheOnlyDrill ? Integer.toString(DRILLS) : "0"))));
			punishDrillHits = Math.max(0L, Long.parseLong(p.getProperty("punishDrillHits", "0")));
			punishDrillChances = Math.max(0L, Long.parseLong(p.getProperty("punishDrillChances", "0")));
			critDrillLeft = false;
			jumpKept = Boolean.parseBoolean(p.getProperty("jumpKept", "true"));
			punishKept = Boolean.parseBoolean(p.getProperty("punishKept", "true"));
			keptJump.clear();
			keptPunish.clear();
			punishChancesSince = Math.max(0L, Long.parseLong(p.getProperty("punishChancesSince", "0")));
			punishCritsSince = Math.max(0L, Long.parseLong(p.getProperty("punishCritsSince", "0")));
			reopenHeads = Integer.parseInt(p.getProperty("reopenHeads", "0"));
			int rules = Integer.parseInt(p.getProperty("rules", "0"));
			sentBackFrom = -1;
			if (rules < Config.CURRENT_VERSION && !selfPlay()) {
				// A stage it has already passed teaches something else now. Among the scripted stages
				// that means going back to it; a run that is fighting itself is left where it is.
				for (int i = 0; i < stage; i++) {
					if (rules < stages.get(i).lessonChangedIn) {
						sentBackFrom = stage;
						stage = i;
						stepsInStage = 0;
						stageBegins();
						break;
					}
				}
			}
			// Up to version 23 any change of the rules began a stage with a time limit again, whatever had
			// changed. Since then a stage says itself when its lesson has changed, and a run saved under
			// newer rules keeps its place and its clock through an update that leaves its stage alone: no
			// control is started afresh and no help begun again for nothing.
			stageBegunAgain = sentBackFrom < 0 && rules < Config.CURRENT_VERSION
					&& (rules < current().lessonChangedIn
					|| (rules < CLOCK_KEPT_FROM_RULES && stepsInStage > 0 && current().promote.styleOptionalAfterSteps > 0));
			if (stageBegunAgain) {
				stepsInStage = 0;
				stageBegins();
			}
		} catch (NumberFormatException e) {
			throw new IOException("curriculum state file is corrupt: " + e.getMessage(), e);
		}
		if (pushKeptFromLoad && backNudgeTarget() > 0f && backNudge <= 0f) {
			backNudge = NUDGE_START;
		}
		pushKeptFromLoad = false;
		resetWindow();
	}
}
