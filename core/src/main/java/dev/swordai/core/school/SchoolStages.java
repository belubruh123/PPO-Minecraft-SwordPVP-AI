package dev.swordai.core.school;

import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Technique;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

/**
 * The stages of the technique school (plan part 5), in the order of {@code docs/PVP-TECHNIQUES.md}:
 * each a family of techniques, the specialist bots that teach it, and the teacher that shows how. The
 * basics of the stages before (aim, footwork, sprint hits, W-tap) are not taught again: the school
 * starts from the installed network, widened, which is also a teacher whose pull fades over the first
 * three stages (plan part 6).
 *
 * <p><b>Mastery</b> (the user, 24 Oct: each technique done on at least 65% of its moments, aiming for 85 to
 * 90%): every technique keeps a window of its last {@link #WINDOW} moments, done or not ({@link Performance}).
 * Its teacher's pull stays full while it does it on less than {@link #FLOOR} of them, and fades only once
 * it does on {@link #MASTERED} or more; it comes back if the technique slips. A stage is passed once it has
 * run {@link Stage#minSteps}, every one of its techniques that has had its moments is done on {@link #FLOOR}
 * of them and all of them together on {@link #MASTERED}, and against none of its bots the damage traded has
 * got worse; or, so that it can never hang, once it has run {@link Stage#maxSteps}. A share
 * {@link #REHEARSAL} of the fights is on the stages before (or, in the first one, against the real player),
 * and their techniques are taught there as their own mastery says: so they are reviewed all along.
 *
 * <p>The teacher is one and the same in every fight: it knows every technique of the families reached so far
 * ({@link #taught()}), whichever bot it meets. A teacher that punish-crits against this family's bots and sprints on
 * against the last family's teaches both in the same spot, and neither is learned (24 Oct: B1 1% as taught).
 */
public final class SchoolStages {
	public static final class Stage {
		public final String name;
		public final String family;
		/** The specialist bots ({@link Specialists} indexes); {@link Specialists#MIXER} for the real player. */
		public final int[] bots;
		/** The techniques its teacher shows, by the keys they use. */
		public final Technique[] techniques;
		public final long minSteps;
		public final long maxSteps;
		/** The share of this stage's own fights against the real player. */
		public final float mixerShare;

		Stage(String name, String family, int[] bots, Technique[] techniques, long minSteps, long maxSteps, float mixerShare) {
			this.name = name;
			this.family = family;
			this.bots = bots;
			this.techniques = techniques;
			this.minSteps = minSteps;
			this.maxSteps = maxSteps;
			this.mixerShare = mixerShare;
		}
	}

	/** On the screen at x4 the school makes about 260,000 steps an hour. */
	private static final long MIN = 150_000L;
	private static final long MAX = 300_000L;

	public static final Stage[] STAGES = {
			new Stage("T-A", "knockback and spacing (K1-K3, T6, T7, T8)",
					new int[] {Specialists.MISS_BAITER, Specialists.SWINGER, Specialists.STRAIGHT_SPRINTER, Specialists.JUMP_IN_CRITTER,
							Specialists.FALL_SPACER},
					new Technique[] {Technique.K2, Technique.K3, Technique.T6, Technique.T6A, Technique.T7, Technique.T8}, MIN, MAX, 0f),
			new Stage("T-B", "crit trades (B1, T1, C1, C2, T2)",
					new int[] {Specialists.PUNISH_CRITTER, Specialists.DOUBLE_CRITTER, Specialists.CRIT_BREAKER, Specialists.STANDING_CRITTER},
					new Technique[] {Technique.B1, Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2, Technique.C1,
							Technique.C2}, MIN, MAX, 0f),
			// The uppercutter is C4's moment: "they sprint-jump toward you as they attack" after a held S. Without one in the
			// family C4 had no moment in thirteen updates of its focus (24 Oct 15:10, 5% of 19).
			new Stage("T-C", "stuns and deflections (T3, T4, T5, C3, C4)",
					new int[] {Specialists.DEFLECTOR, Specialists.REVERSE_DEFLECTOR, Specialists.STUN_ESCAPER, Specialists.ATTACK_THEN_S,
							Specialists.STUNNER, Specialists.UPPERCUTTER},
					new Technique[] {Technique.T4, Technique.T5, Technique.C3, Technique.C4}, MIN, MAX, 0f),
			new Stage("T-D", "jump resets (T9, C6, C7)",
					new int[] {Specialists.THREE_BLOCK, Specialists.JUMP_RESETTER, Specialists.RHYTHM_COMBOER},
					new Technique[] {Technique.T9, Technique.T9A, Technique.T9B, Technique.T9C, Technique.C6, Technique.C7}, MIN, MAX, 0f),
			new Stage("T-E", "jumping (T10, T11)",
					new int[] {Specialists.STUN_ESCAPER, Specialists.UPPERCUTTER, Specialists.LUNGER, Specialists.HOPPER},
					new Technique[] {Technique.T10, Technique.T11A, Technique.T11B, Technique.T11C}, MIN, MAX, 0f),
			new Stage("T-F", "every technique at once",
					allSpecialists(), allTechniques(), MIN, Long.MAX_VALUE, 0.25f),
	};

	/**
	 * Every technique at once was tried on 24 Oct (00:30-01:00): with every teacher pulling at once the network fought
	 * worse by the minute (141 dealt, 414 taken a minute) and learned none of them. So the families come one at a time,
	 * round and round (a spiral): each in focus until mastered or for {@link #MAX} steps at most, then the next, and
	 * after the last the one done least; a share {@link #REHEARSAL} of the fights reviews the others all along.
	 */
	public static final boolean ALL_AT_ONCE = false;
	/** The families gone round: T-A to T-E. */
	public static final int FAMILIES = 5;
	/** In a family's focus, this share of its own fights is against the real player. */
	public static final float MIXER_IN_FOCUS = 0.15f;

	/**
	 * Of a stage's own fights, the share that reviews the other families. 0.3 until 24 Oct 19:25: with the crit trades in
	 * focus each other family had about 4% of the fights, and against the copy in the user's profile (the 17:44 check) the
	 * 18:48 check had lost T11a (-35), T6a (-28), T2 (-19), T11c (-18), C7 (-14) and T11b (-11). The user: "MUST not let it
	 * lose its skill of doing those techniques".
	 */
	public static final float REHEARSAL = 0.5f;
	/** Fights per bot a stage is judged by: its first ones against its last ones. */
	public static final int RECENT = 16;
	/** How far behind on damage a minute a bot may still be at the end of a stage. */
	public static final float BEHIND = 10f;
	/** The technique teacher's full pull; each technique's share of it follows its mastery. */
	public static final float TECH_PULL = 1.0f;
	/**
	 * The most a technique teacher pulls, however far under its target (its share still grows past this, and comes into force
	 * if the cap is lifted). Damage first (25 Oct 00:45): in the 00:26 check the school was 20 a fight behind the installed
	 * model (236 dealt and 446 taken a minute to its 316 and 402), swinging a third less, holding S and jumping more; with the
	 * teachers at up to two and three times their pull the damage reward had little say. 1 = the teacher's first pull. At 1
	 * the 01:22 check came to -15.1 (from -20.0); 0.5 from 02:05, as the technique moments still kept its swings down (32 a
	 * minute in them to the installed model's 53).
	 */
	// 25 Oct 06:20 off (techniques on at 0.5 cost -19.1 vs -8.6 off); 09:00 back at 0.2 as a review, the user: "Review every
	// skill while learning that so it won't forgot stuff"; 09:15 no cap (each technique's mastery share, up to PRIORITY_MOST),
	// the user: "training all the skill to back where they were like nearly all above 80% and crit need 80% and punish crit
	// 90% while review basic skill in between".
	public static final float PULL_CAP = 0f; // run 2 (EXPERIMENTS.md): the 06:54 recipe (3 = PRIORITY_MOST from 09:15)
	/**
	 * The answers to a jump-in (part 7: hit them while they rise, T8; no knockback hit on a falling one, K2; let it land and
	 * punish-crit, B1) taught on their own (25 Oct 07:40): with every teacher off the network was level with the installed
	 * model (06:54 check, -0.6) but knockback-hit falling jumpers 30 to 50 times an update, the user's first complaint.
	 */
	// 08:25 off: at 0.5 the 08:22 check was -38.4, swings 105 to 338 (K2 taught it not to swing at all). 09:15 back with the rest,
	// the user: "knock hit on jumper thing focus while learning those": K2's swing is now the golden rule's (SchoolSession.K2_GUARD
	// holds the attack key on every K2 moment, so nothing of "don't swing" is taught), T8 and B1 are taught.
	public static final float JUMP_IN_PULL_CAP = 0f; // run 2 (EXPERIMENTS.md): the 06:54 recipe (3 from 09:15)
	static final boolean[] JUMP_IN_ANSWER = new boolean[Technique.COUNT];
	/**
	 * The golden rule's teacher (K2: no knockback hit on a falling one) off from 25 Oct 09:35: in this game, as the checks
	 * measured it, the hit on the falling jumper is what beats the jump-in critters (see SchoolSession.K2_GUARD); taught, it
	 * kept the swing back from them and the damage went with it.
	 */
	public static final float K2_PULL = 0f;
	/**
	 * The techniques the run of EXPERIMENTS.md teaches, one family at a time (25 Oct, the one-week goal): each at its mastery
	 * share up to RUN_PULL_CAP, its pull weighted by the outcome (SchoolPpo.TEACH_AWR_MOST). 0: none.
	 */
	public static final float RUN_PULL_CAP = 1f; // 1 in runs 7-11 (the crit family); 0 in run 12 (run 6's recipe); 1 in runs 13-14 (B1 alone); 0 in run 15 to 22:05, then 1 (T7) to run 16b; 0 in run 17;
	// 1 (B1 alone) from 28 Oct 06:40: with JUMP_IN_PULL_CAP 0 the punish crit's pull was 0, so its teacher's keys were yielded
	// (SchoolSession NO_PULL_YIELDS) and taught nowhere, the punish drill of 27-28 Oct included: the user's method (the drill
	// forced to B1 teaches it to land it when told) had no lesson in it. Its keys are taught only where it is told B1.
	/**
	 * The installed model's fight-time rules give way to a technique being learned, on its keys (run 10 of EXPERIMENTS.md).
	 * Its StepBack ("after a hit of its own that has not sent the opponent away it steps back": W and the sprint off) took the
	 * keys of every technique that follows its own hit: the uppercut (T10, off on the sprint 37-76% at full pull, 25 Oct
	 * 09:20-09:43), the hops (T11b), the delayed sprint hit (C7), the standing crit (T2). The user, 15:00: the uppercut at 0%.
	 */
	public static final boolean RULES_YIELD = false; // on 25 Oct 22:05 - 26 Oct 00:20 (runs 16, 16b: their combos' third hit 52%, 43%); off in run 17
	/**
	 * The rules give way only when it has not been hit for this many ticks: in their combo they keep their keys (the jump
	 * reset, the step back). Giving way whenever the decider had chosen the technique, its combo escape went in the 23:19 check
	 * (their third hit in a row 52% to the installed model's 32%; run 6: 33%).
	 */
	public static final int YIELD_AFTER_HIT = 20;
	/**
	 * When the network is told the punish crit (the decision model chose it, or the punish drill forced it), the installed
	 * model's rules give it W, the sprint and the swing for the lift (PUNISH_KEYS), in training, checks and real fights: a crit
	 * needs the sprint let go and the swing kept for the way down, and the rules hold W and the sprint in every lift
	 * (RULES_YIELD is off). The user's method, 27 Oct: aggressive play keeps the rules' pressure; only the decision for the
	 * punish crit takes the keys. Giving them up in every lift whatever was chosen (SchoolSession#RULES_LET_GO_IN_LIFT,
	 * RULES_LET_GO_AT_CRIT) cost damage (26 Oct).
	 */
	public static final boolean PUNISH_TOLD_YIELD = false; // on 23:06-00:10 27 Oct in every fight: told B1, critted 4% (23% with the rules' keys), punish scenes won 6%; now only in the punish drill (SchoolSession), until the network beats the rules there
	public static final int PUNISH_KEYS = dev.swordai.core.rl.ActionSpace.mask(dev.swordai.core.rl.ActionSpace.FORWARD,
			dev.swordai.core.rl.ActionSpace.SPRINT, dev.swordai.core.rl.ActionSpace.ATTACK);
	/**
	 * The punish crit's swing learned in every told lift of the full fights, where a rule presses it (SchoolSession): a key a
	 * rule presses gets no lesson (SchoolPpo#teach skips the locked heads), so with PUNISH_TOLD_YIELD off the network's own
	 * swing in a lift was taught only in the rare punish drill, where on 28 Oct it never swung at the crit moment (the teacher's
	 * swing on 4 ticks, falling, charged, reach 2.8; school/punish-traces.txt). The rule's swing is still the one pressed;
	 * the network's swing key only copies the punish crit teacher's (held on the way up, swung on the way down), so nothing
	 * changes in the fight until the keys are handed over.
	 */
	public static final boolean PUNISH_SHADOW = true;
	/**
	 * The punish crit's lesson is its swing alone (28 Oct 19:25): with its pull on (RUN_TAUGHT) its W, sprint and the lift's
	 * aim spread into the fight within 15 minutes, twice (06:26-06:41, 18:58-19:20): sprint left off at T4 moments 59% -> 89%,
	 * T5 47% -> 87%, T7 37% -> 74%; turned to find them 0 -> 9; damage a minute about 330/340 -> 288/371. The swing held on
	 * the way up and pressed on the way down is the part the drill showed failing (school/punish-traces.txt).
	 */
	public static final int B1_TAUGHT_HEADS = dev.swordai.core.rl.ActionSpace.mask(dev.swordai.core.rl.ActionSpace.ATTACK);
	/**
	 * The jump reset's lesson is its jump alone (29 Oct 03:30), as the punish crit's is its swing: at its moments the network
	 * pressed no jump 99-100% of the time (its pull was 0 like every technique's but the run's), and the combo escape is the
	 * SOP's next focus; its W/S and sprint stay the installed model's so that nothing but the jump can spread.
	 */
	public static final int T9_TAUGHT_HEADS = dev.swordai.core.rl.ActionSpace.mask(dev.swordai.core.rl.ActionSpace.JUMP);

	/**
	 * With the jump reset taught (RUN_TAUGHT), the file teacher's jump key is taught at every moment of the fights, its jumps and
	 * (on nearly every tick) its jump held back, at JUMP_PULL (SchoolSession): taught at the jump reset's moments alone (29 Oct
	 * 03:27-03:50) the jump spread into every technique within 20 minutes, nothing teaching the jump held back anywhere else.
	 */
	public static final boolean JUMP_EVERYWHERE = true;
	public static final float JUMP_PULL = 1f;

	/**
	 * The combo escape's lesson is its S alone (29 Oct 21:10): in their combo (their second hit in a row) the file holds S (C3,
	 * "jump + hold S vs stun"); at its moments the network held no S 95-100% of the time, and their third hit came after 37-48%
	 * of their combos in the hourly checks to the rules fighter's 32-42%, 53-57% in the mirror screens. The jump (T9 and C3's
	 * own) spread into the fight twice on 29 Oct and is not taught.
	 */
	public static final int C3_TAUGHT_HEADS = dev.swordai.core.rl.ActionSpace.mask(dev.swordai.core.rl.ActionSpace.FORWARD);

	/**
	 * Gated lessons (30 Oct): a technique's movement keys taught into its own option layer ({@link SchoolNet}, "option"), which
	 * adds to their logits only while the network is told that technique; nothing of the lesson reaches the shared layers, so
	 * at every other moment the network plays exactly as before. Every movement-key lesson taught into the whole network
	 * spread into the fight within 15-25 minutes: B1's W and sprint (28 Oct), T9's jump twice and C3's S (29 Oct). When the
	 * technique is done is the decider's (the user's method, 27 Oct); how, its option layer's.
	 */
	public static final boolean GATED = true;
	/**
	 * The punish crit's W and sprint (in reach for the fall, the sprint let go for the crit). Run 25 (30 Oct 01:11-02:05) gated
	 * the jump reset's jump and the combo escape's S too: both learned (T9 32 -> 99%, C3 23 -> 97%) and nothing spread, but the
	 * hourly check fell to -10.2 (3.7), swings 273 vs 342, backing out with S 12% vs 3% without fewer third hits; the punish
	 * crit was the part ahead of the rules fighter (24% vs 18%). Run 26: the punish crit alone.
	 */
	public static final int B1_GATED_HEADS = dev.swordai.core.rl.ActionSpace.mask(dev.swordai.core.rl.ActionSpace.FORWARD,
			dev.swordai.core.rl.ActionSpace.SPRINT);

	/** The keys of technique {@code technique} taught into its option layer alone (0: none, or GATED off). */
	public static int gatedHeads(int technique) {
		if (!GATED) {
			return 0;
		}
		return technique == Technique.B1.ordinal() ? B1_GATED_HEADS : 0;
	}

	/** The keys a technique's teacher teaches: B1 its swing, T9 its jump, C3 its S, the rest all theirs; and the gated ones. */
	public static int taughtHeads(int technique) {
		int whole = technique == Technique.B1.ordinal() ? B1_TAUGHT_HEADS : technique == Technique.T9.ordinal() ? T9_TAUGHT_HEADS
				: technique == Technique.C3.ordinal() ? C3_TAUGHT_HEADS : dev.swordai.core.rl.ActionSpace.ALL_HEADS;
		return whole | gatedHeads(technique);
	}
	public static final int PUNISH_SHADOW_KEYS = dev.swordai.core.rl.ActionSpace.mask(dev.swordai.core.rl.ActionSpace.ATTACK);

	/**
	 * Run 11 (EXPERIMENTS.md): learned from the fight, not from the teacher. A technique's keys are copied only on moments that
	 * went better than expected (the step's advantage above zero), and nothing pays for pressing the teacher's keys (the
	 * imitation and done rewards); the game's own results pay (damage, knockouts, real crits, real punish crits). The scripted
	 * teacher playing its techniques itself lost to the installed model in every test (24 Oct: -1.1 to -23.3 a fight), and every
	 * run that copied it more lost more (run 7 -22.5, 9b -8.7, 10 -17.1): copying it cannot beat the installed model. The user,
	 * 25 Oct 16:10: "My goal is to let it learn everything IN REAL FIGHT AS well so not aginst teacher".
	 */
	public static final boolean FROM_THE_FIGHT = true; // run 11 (25 Oct 17:10)

	/**
	 * Run 14 (EXPERIMENTS.md): the punish crit's swing key (the held swing in the lift and the swing on the way down) copied on
	 * every moment, not only where it paid. Copied only where it paid (run 13) it was hardly copied at all: the crit lands on
	 * few lifts, so the held swing that makes it was nearly never on a moment that paid, and the sword was back on 5 of 43 falls
	 * in reach (19:00 check). Copied in full with the jumped crits in run 10 it was learned (sword back 3-13 -> 11-26 an update)
	 * but those plans spread jumps into the fight (75 a minute to run 6's 44); the punish crit has no jump of its own.
	 */
	public static final boolean B1_SWING_ALWAYS = true; // run 14 (25 Oct 19:05)

	/** Whether technique {@code t} is one the run teaches (RUN_TAUGHT, with RUN_PULL_CAP on). */
	public static boolean runTaught(int t) {
		return RUN_PULL_CAP > 0f && t >= 0 && t < Technique.COUNT && RUN_TAUGHT[t];
	} // run 7 (EXPERIMENTS.md): the first technique family, the crits, with the basics reviewed in between
	static final boolean[] RUN_TAUGHT = new boolean[Technique.COUNT];

	static {
		// Runs 7-11: the crit family (the punish crit, the double crits, the standing crit); runs 13-14 the punish crit alone:
		// every run teaching the whole family lost, the crit tries spreading jumps into the fight. From 25 Oct 22:05 the
		// three-block hit (T7, "full-sprint hit before they are close enough to hit back"): the five opponents it loses to most
		// win at the edge of reach, hitting first as it walks into a ready sword (the investigation of 21:30). From 28 Oct 06:40
		// the punish crit alone again (RUN_PULL_CAP); 29 Oct 03:27-03:50 with the jump reset's jump too (T9_TAUGHT_HEADS): within 20 minutes
		// the network jumped at every technique's moments (jump off 0-17% -> 30-54%), swings 160 -> 80, damage dealt 340 -> 195 a minute.
		// 06:27-06:50 with the jump at every moment (JUMP_EVERYWHERE): the jump reset learned (jump off 100 -> 28%), combos got out of
		// 50 -> 59%, but damage a minute ~347/334 -> 279/400 and turns to find them 0 -> 19: off again.
		// 29 Oct 20:53-21:20 with the combo escape's S too (C3, C3_TAUGHT_HEADS): the S spread into every technique within 25
		// minutes (W/S off at T4 36 -> 75%, T7 8 -> 59%), swings ~140 -> 44-96 an update, damage dealt ~340 -> 155 a minute: off.
		// 30 Oct 01:11-02:05 (run 25) the three again, their movement keys gated: -10.2 in the hourly check. From 03:30 (run 26)
		// the punish crit alone, its W and sprint gated (B1_GATED_HEADS).
		for (Technique t : new Technique[] {Technique.B1}) {
			RUN_TAUGHT[t.ordinal()] = true;
		}
	}

	/**
	 * The basics teacher's swing (the ordinary fight's moments: swing as soon as the sword is back with them under the
	 * crosshair) at least this hard. 25 Oct 09:55: on those moments it did not swing on 43-64% of them, 14-28 swings a
	 * minute off the techniques to the installed model's 40, and the damage dealt went with it (215 a minute to 297).
	 */
	public static final float BASE_PULL_LEAST = 0f; // run 2 (EXPERIMENTS.md): the 06:54 recipe (2 proposed 09:55)

	static {
		JUMP_IN_ANSWER[Technique.T8.ordinal()] = true;
		JUMP_IN_ANSWER[Technique.K2.ordinal()] = true;
		JUMP_IN_ANSWER[Technique.B1.ordinal()] = true;
	}
	/** The moments a technique is judged by, the least of them that counts, and the bars. */
	public static final int WINDOW = 40;
	public static final int ENOUGH = 20;
	public static final float FLOOR = 0.65f;
	public static final float MASTERED = 0.85f;
	/** The installed network's pull at the school's start; it fades to nothing over the first three stages' least steps. */
	// 2 from 25 Oct 09:55: between the techniques its keys (its W/S, its swing) held too lightly; the S of the file's escapes
	// spread into the whole fight (S 166 a minute off the moments to its 79, swings 22 to 40; 09:49 check).
	public static final float OLD_PULL = 0.5f; // run 6 (EXPERIMENTS.md): the anchor loosened (1.0 in runs 2-5; 2 proposed 09:55)
	/**
	 * About a day on the screen. Since the overlay (24 Oct) the installed model teaches the fighting between the techniques,
	 * not only the camera: it stays the network's teacher there until the network plays as well as it does.
	 */
	private static final long OLD_FADE = 6_000_000L;

	private static int[] allSpecialists() {
		int[] b = new int[Specialists.COUNT];
		for (int i = 0; i < b.length; i++) {
			b[i] = i;
		}
		return b;
	}

	private static Technique[] allTechniques() {
		Technique[] t = new Technique[Technique.COUNT - 1];
		System.arraycopy(Technique.ALL, 1, t, 0, t.length);
		return t;
	}

	// ---- progress ---------------------------------------------------------------------------------

	public int stage;
	/** The last family the spiral has come to so far: the teacher knows them all, up to it. */
	public int reached;
	public long stageSteps;
	public long schoolSteps;
	/** Per bot of the current stage: (dealt - taken) a minute of its fights, first ones and the last ones. */
	private final float[][] first = new float[Specialists.KINDS][RECENT];
	private final int[] firstCount = new int[Specialists.KINDS];
	private final float[][] last = new float[Specialists.KINDS][RECENT];
	private final int[] lastCount = new int[Specialists.KINDS];
	private final int[] lastAt = new int[Specialists.KINDS];
	/** Per technique: its last moments (1 done, 0 not), how many, where the next goes; and its pull's share. */
	private final byte[][] moments = new byte[Technique.COUNT][WINDOW];
	private final byte[][] paidMoments = new byte[Technique.COUNT][WINDOW];
	private final int[] momentCount = new int[Technique.COUNT];
	private final int[] momentAt = new int[Technique.COUNT];
	private final float[] pullShare = initialShares();

	private static float[] initialShares() {
		float[] f = new float[Technique.COUNT];
		java.util.Arrays.fill(f, 1f);
		for (int t = 0; t < f.length; t++) {
			f[t] = PRIORITY[t] ? 2f : 1f;
		}
		return f;
	}

	/** A moment of technique {@code t} is over (see {@link Performance}). */
	public void moment(int t, boolean done, boolean paid) {
		moments[t][momentAt[t]] = (byte) (done ? 1 : 0);
		paidMoments[t][momentAt[t]] = (byte) (paid ? 1 : 0);
		momentAt[t] = (momentAt[t] + 1) % WINDOW;
		momentCount[t] = Math.min(WINDOW, momentCount[t] + 1);
	}

	/** The share of its last moments technique {@code t} was done on; NaN before any. */
	public float doneRate(int t) {
		return rate(moments[t], momentCount[t]);
	}

	public float paidRate(int t) {
		return rate(paidMoments[t], momentCount[t]);
	}

	public int momentsSeen(int t) {
		return momentCount[t];
	}

	private static float rate(byte[] a, int n) {
		if (n == 0) {
			return Float.NaN;
		}
		int s = 0;
		for (int i = 0; i < n; i++) {
			s += a[i];
		}
		return s / (float) n;
	}

	/** After each update: each technique's pull follows how well it is done. */
	public void adapt() {
		for (int t = 0; t < Technique.COUNT; t++) {
			if (momentCount[t] < ENOUGH / 2) {
				continue;
			}
			// The user, 24 Oct: each at least 85%, guided stronger. The pull grows while it is under 85%, up to twice the
			// first (three times for the priority ones), holds from 85% to 90%, and fades only above 90%.
			float r = doneRate(t);
			float most = PRIORITY[t] ? PRIORITY_MOST : MOST;
			if (r >= target(t) + HOLD) {
				pullShare[t] = Math.max(0f, pullShare[t] * 0.85f - 0.01f);
			} else if (r < target(t)) {
				pullShare[t] = Math.min(most, Math.max(pullShare[t], PRIORITY[t] ? 2f : 1f) + 0.25f);
			}
		}
	}

	/** Above this a technique's pull fades. */
	public static final float HELD = 0.9f;
	/** How far over its target a technique's pull holds before it fades (85% -> 90%). */
	public static final float HOLD = HELD - MASTERED;
	/**
	 * The user's bars (24 Oct 15:00): the deflection (T4) and jump + hold S (C3) at 90%, the reverse deflection (T5) and
	 * attack-then-S (C4) at least 80%. A technique's pull grows while under its target and the stage is not passed while one
	 * of its techniques is under its bar.
	 */
	private static final float[] TARGET = new float[Technique.COUNT];
	private static final float[] BAR = new float[Technique.COUNT];

	/** What technique {@code t}'s pull is grown to: {@link #MASTERED}, or the user's bar where higher. */
	public static float target(int t) {
		return Math.max(MASTERED, TARGET[t]);
	}

	/** The least technique {@code t} is to be done on for its stage to pass: {@link #FLOOR}, or the user's bar. */
	public static float bar(int t) {
		return Math.max(FLOOR, BAR[t]);
	}
	/** The most a technique's pull share grows to; for the priority ones. */
	public static final float MOST = 2f;
	public static final float PRIORITY_MOST = 3f;
	/**
	 * The user's first wish (24 Oct): the answer to an opponent who jumps so that the sprint hit lands on them falling
	 * (K3) and crits back. Hit them while they rise (T8), no knockback hit on a falling one (K2), the punish crit once
	 * they have landed it (B1). The installed model's biggest flaw.
	 */
	public static final boolean[] PRIORITY = new boolean[Technique.COUNT];
	/** The bots that give those moments, and the share of every stage's fights against them. */
	// The jump-in critter twice (25 Oct 09:15, the user: "knock hit on jumper thing focus while learning those").
	public static final int[] PRIORITY_BOTS = {Specialists.JUMP_IN_CRITTER, Specialists.FALL_SPACER, Specialists.ATTACK_THEN_S}; // run 2 (EXPERIMENTS.md): the 06:54 recipe
	/**
	 * A third until the priority ones were learned (24 Oct 13:40: T8 77%, K2 89%, B1 64%, T10 62%); then less, so a
	 * stage's own bots get the fights: in the crit trades, the double crit had about one moment an update.
	 */
	/**
	 * The opponents it loses to most in the paired checks, fought this share of the training fights, each by how much it
	 * loses to it (net damage a fight over the 13:51 to 20:36 checks of 25 Oct, about 20 fights each). The user, 25 Oct 21:20:
	 * "look at the case it lose against to and let it fight against those and see why and the method it should do to counter".
	 * Training only: the checks' opponents stay as they were, so the checks stay comparable.
	 */
	public static final int[] LOSS_BOTS = {Specialists.ATTACK_THEN_S, Specialists.STUN_ESCAPER, Specialists.UPPERCUTTER,
			Specialists.LUNGER, Specialists.FALL_SPACER};
	public static final float[] LOSS_WEIGHTS = {52.7f, 34.7f, 31.5f, 22.8f, 22.0f};
	public static final float LOSS_SHARE = 0.2f; // 0.35 in runs 15b-16b; 0.2 in run 17
	public static final float PRIORITY_SHARE = 0.15f; // run 2 (EXPERIMENTS.md): the 06:54 recipe (0.25 from 09:15)
	/**
	 * Of the rest, the share of fights against a bot of the family of a technique still under {@link #MASTERED}, the
	 * weakest most often. A technique's moments come against its own family's bots: C3 (jump + hold S against a stun)
	 * had one to three moments an update in the jumping stage and stayed at 0% all night (24 Oct; the user: a technique
	 * that does not rise within an hour is critical). 0.2 until 24 Oct 17:30, when 19 techniques were under 80% (the user:
	 * "make sure it maintains the skill and technique").
	 */
	public static final float WEAKEST_SHARE = 0.55f;
	/**
	 * Run 10 (EXPERIMENTS.md): with a run teaching one family (RUN_TAUGHT), the weakest-technique fights go to that family's
	 * techniques only. Until then they went to every family's weakest (the uppercut, the hops, the jump resets, none taught in
	 * the run), and the crit run fought the punish-critter in 1-2 of about 9 fights an update: the double crit, which "only
	 * works if the opponent tries to punish-crit you", had 2 moments in 10 minutes (25 Oct 15:44). The user, 15:38: "DOUBLE
	 * CRIT WHERE THE HECK IS IT LEARNING".
	 */
	public static final boolean WEAKEST_RUN_ONLY = false;
	/** Under this a technique's pull toward its bots is {@link #UNDER_WEIGHT} times its gap (the user, 24 Oct 23:40: "any skill below 80% should be heavily focused"). */
	public static final float UNDER = 0.8f;
	public static final float UNDER_WEIGHT = 3f;

	static {
		PRIORITY[Technique.T8.ordinal()] = true;
		PRIORITY[Technique.K2.ordinal()] = true;
		PRIORITY[Technique.B1.ordinal()] = true;
		// The user, 24 Oct 10:25: the uppercut matters more than hops and lunges. Its moments come against one that backs
		// out with S after its hit (attack-then-S), so that bot is among the priority ones too.
		PRIORITY[Technique.T10.ordinal()] = true;
		// The user, 24 Oct 18:00: "prioritize the crit part and make all at least 80%, since crit is basically the best
		// method beside combo" (then T1b 40%, T1c 55%, T2 57%, T1 65%, C1 75%).
		for (Technique t : new Technique[] {Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2, Technique.C1, Technique.C2}) {
			PRIORITY[t.ordinal()] = true;
		}
		for (Technique t : new Technique[] {Technique.B1, Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2, Technique.C1,
				Technique.C2}) {
			BAR[t.ordinal()] = 0.8f;
		}
		// The user, 24 Oct 20:10: "all the crit above 80% ... and so do punish crit at 90%".
		TARGET[Technique.B1.ordinal()] = 0.9f;
		BAR[Technique.B1.ordinal()] = 0.9f;
		TARGET[Technique.T4.ordinal()] = 0.9f;
		TARGET[Technique.C3.ordinal()] = 0.9f;
		BAR[Technique.T4.ordinal()] = 0.9f;
		BAR[Technique.C3.ordinal()] = 0.9f;
		BAR[Technique.T5.ordinal()] = 0.8f;
		BAR[Technique.C4.ordinal()] = 0.8f;
	}

	/** The pull of each technique's teacher now. */
	public float[] techPulls() {
		float[] p = new float[Technique.COUNT];
		for (int t = 0; t < p.length; t++) {
			p[t] = t == Technique.K2.ordinal() ? K2_PULL : TECH_PULL * Math.min(JUMP_IN_ANSWER[t] ? JUMP_IN_PULL_CAP : PULL_CAP, pullShare[t]);
			if (t == Technique.BASE.ordinal()) {
				p[t] = Math.max(p[t], BASE_PULL_LEAST);
			}
			if (RUN_PULL_CAP > 0f && RUN_TAUGHT[t]) {
				p[t] = TECH_PULL * Math.min(RUN_PULL_CAP, pullShare[t]);
			}
		}
		return p;
	}

	/** Whether this stage's techniques are mastered: each that has had its moments on {@link #FLOOR}, all of them on {@link #MASTERED}. */
	public boolean mastered() {
		int all = 0;
		int done = 0;
		int judged = 0;
		for (Technique t : current().techniques) {
			int i = t.ordinal();
			// A technique that has not had its moments is not learned: the crit trades (T-B) passed in an hour with the double
			// crit (T1), T1b, C1 and C2 never once come up (24 Oct). The stage then goes on to its step cap.
			if (momentCount[i] < ENOUGH) {
				return false;
			}
			judged++;
			float r = doneRate(i);
			if (r < bar(i)) {
				return false;
			}
			all += momentCount[i];
			done += Math.round(r * momentCount[i]);
		}
		return judged > 0 && done >= MASTERED * all;
	}

	/** Per technique of this stage: " T7 did 48% paid 30% (40) pull 1.00;", the ordinary fight first. */
	public String masteryLine() {
		StringBuilder b = new StringBuilder();
		int base = Technique.BASE.ordinal();
		b.append(String.format(Locale.ROOT, " ordinary fight without a hop %.0f%% (%d) pull %.2f;", 100f * doneRate(base), momentCount[base], pullShare[base]));
		for (Technique t : current().techniques) {
			int i = t.ordinal();
			b.append(String.format(Locale.ROOT, " %s did %.0f%% paid %.0f%% (%d) pull %.2f;", t.id, 100f * doneRate(i), 100f * paidRate(i),
					momentCount[i], pullShare[i]));
		}
		return b.toString();
	}

	/** For the chat: "85%+: 3, 65%+: 7 of 20 | weakest: T6a 0%, T6 3%, T7 12% | no hop 40%". */
	public String masteryBrief() {
		int judged = 0;
		int mastered = 0;
		int floor = 0;
		java.util.List<Technique> seen = new java.util.ArrayList<>();
		for (Technique t : current().techniques) {
			int i = t.ordinal();
			if (momentCount[i] < 5) {
				continue;
			}
			judged++;
			float r = doneRate(i);
			mastered += r >= MASTERED ? 1 : 0;
			floor += r >= FLOOR ? 1 : 0;
			seen.add(t);
		}
		if (judged == 0) {
			return "no moments yet";
		}
		seen.sort((a, c) -> Float.compare(doneRate(a.ordinal()), doneRate(c.ordinal())));
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "85%%+: %d, 65%%+: %d of %d | weakest:", mastered, floor, judged));
		for (int k = 0; k < Math.min(5, seen.size()); k++) {
			Technique t = seen.get(k);
			b.append(k == 0 ? " " : ", ").append(t.id).append(' ').append(Math.round(100f * doneRate(t.ordinal()))).append('%');
		}
		int base = Technique.BASE.ordinal();
		if (momentCount[base] > 0) {
			b.append(String.format(Locale.ROOT, " | ordinary fight without a hop %.0f%%", 100f * doneRate(base)));
		}
		return b.toString();
	}

	public SchoolStages() {
		if (ALL_AT_ONCE) {
			stage = STAGES.length - 1;
		}
	}

	public Stage current() {
		return STAGES[stage];
	}

	/** What the teacher knows: the techniques of every family reached so far; all of them in the last stage. */
	public Technique[] taught() {
		if (ALL_AT_ONCE || stage >= FAMILIES) {
			return STAGES[STAGES.length - 1].techniques;
		}
		java.util.List<Technique> t = new java.util.ArrayList<>();
		for (int f = 0; f <= Math.max(reached, stage); f++) {
			java.util.Collections.addAll(t, STAGES[f].techniques);
		}
		return t.toArray(new Technique[0]);
	}

	/** The stage a bot's techniques belong to: the first that has it, the last stage aside. */
	public static int familyOf(int bot) {
		for (int i = 0; i < STAGES.length - 1; i++) {
			for (int b : STAGES[i].bots) {
				if (b == bot) {
					return i;
				}
			}
		}
		return STAGES.length - 1;
	}

	/** How well the techniques of a family are done: the mean of those that have had their moments; 0 before any. */
	public float familyMastery(int family) {
		float sum = 0f;
		int n = 0;
		for (Technique t : STAGES[family].techniques) {
			int i = t.ordinal();
			if (momentCount[i] >= 5) {
				sum += doneRate(i);
				n++;
			}
		}
		return n == 0 ? 0f : sum / n;
	}

	/** A fight's opponent: a bot of this stage, of a stage before (rehearsal), or the real player. */
	/**
	 * Moments of each technique seen against each bot (since this run started): against which bots a technique's
	 * moments come. C6 (a standing crit against a jump reset) comes only against the jump-resetter, after a stun of the
	 * network's own, one bot of three in its family: drawn by family it had one or two moments an update (24 Oct).
	 */
	private final float[][] botMoments = new float[Specialists.KINDS][Technique.COUNT];

	/** A moment of technique {@code t} began against bot {@code bot} ({@code Specialists} index). */
	public void sawMoment(int bot, int t) {
		if (bot >= 0 && bot < Specialists.KINDS && t >= 0 && t < Technique.COUNT) {
			botMoments[bot][t]++;
		}
	}

	/**
	 * A bot for a technique reached and not mastered, the further under {@link #MASTERED} the likelier: one it has had
	 * that technique's moments against, by how many, else one of its family; -1 for none.
	 */
	int weakestBot(Random rng) {
		int last = Math.min(reached, FAMILIES - 1);
		float total = 0f;
		float[][] w = new float[last + 1][];
		for (int f = 0; f <= last; f++) {
			Technique[] ts = STAGES[f].techniques;
			w[f] = new float[ts.length];
			for (int i = 0; i < ts.length; i++) {
				int t = ts[i].ordinal();
				if (WEAKEST_RUN_ONLY && RUN_PULL_CAP > 0f && !RUN_TAUGHT[t]) {
					w[f][i] = 0f;
					continue;
				}
				// Starved (too few moments to judge) weighs twice: its moments only come against its own bots.
				boolean starved = momentCount[t] < ENOUGH / 2;
				float r = starved ? 0f : doneRate(t);
				w[f][i] = Math.max(0f, target(t) - r) * (starved ? 2f : 1f) * (r < UNDER ? UNDER_WEIGHT : 1f);
				total += w[f][i];
			}
		}
		if (total <= 0f) {
			return -1;
		}
		float u = rng.nextFloat() * total;
		for (int f = 0; f <= last; f++) {
			for (int i = 0; i < w[f].length; i++) {
				u -= w[f][i];
				if (u <= 0f) {
					int b = botFor(STAGES[f].techniques[i].ordinal(), rng);
					if (b >= 0) {
						return b;
					}
					int[] bots = STAGES[f].bots;
					return bots[rng.nextInt(bots.length)];
				}
			}
		}
		int[] bots = STAGES[last].bots;
		return bots[rng.nextInt(bots.length)];
	}

	/** A bot drawn by how many moments of technique {@code t} it has given; -1 before any. */
	int botFor(int t, Random rng) {
		float total = 0f;
		for (int b = 0; b < Specialists.KINDS; b++) {
			total += botMoments[b][t];
		}
		if (total <= 0f) {
			return -1;
		}
		float u = rng.nextFloat() * total;
		for (int b = 0; b < Specialists.KINDS; b++) {
			u -= botMoments[b][t];
			if (u <= 0f && botMoments[b][t] > 0f) {
				return b;
			}
		}
		return -1;
	}

	public int drawBot(Random rng) {
		Stage s = current();
		if (rng.nextFloat() < LOSS_SHARE) {
			float total = 0f;
			for (float w : LOSS_WEIGHTS) {
				total += w;
			}
			float u = rng.nextFloat() * total;
			for (int i = 0; i < LOSS_BOTS.length; i++) {
				u -= LOSS_WEIGHTS[i];
				if (u <= 0f) {
					return LOSS_BOTS[i];
				}
			}
			return LOSS_BOTS[LOSS_BOTS.length - 1];
		}
		if (rng.nextFloat() < PRIORITY_SHARE) {
			return PRIORITY_BOTS[rng.nextInt(PRIORITY_BOTS.length)];
		}
		if (rng.nextFloat() < WEAKEST_SHARE) {
			int b = weakestBot(rng);
			if (b >= 0) {
				return b;
			}
		}
		if (!ALL_AT_ONCE && stage < FAMILIES) {
			if (rng.nextFloat() >= REHEARSAL) {
				return rng.nextFloat() < MIXER_IN_FOCUS ? Specialists.MIXER : s.bots[rng.nextInt(s.bots.length)];
			}
			// Review: another family, most often the one done least; the bots of families not reached yet now and then,
			// for the techniques already known against other ways of fighting.
			float[] w = new float[FAMILIES];
			float total = 0f;
			for (int f = 0; f < FAMILIES; f++) {
				w[f] = f == stage ? 0f : f > reached ? 0.25f : 0.25f + 1f - familyMastery(f);
				total += w[f];
			}
			float u = rng.nextFloat() * total;
			int f = 0;
			while (f < FAMILIES - 1 && (u -= w[f]) > 0f) {
				f++;
			}
			if (f == stage) {
				f = (f + 1) % FAMILIES;
			}
			int[] bots = STAGES[f].bots;
			return bots[rng.nextInt(bots.length)];
		}
		if (stage == STAGES.length - 1 && rng.nextFloat() >= s.mixerShare) {
			// Most to the bots whose techniques are done least.
			float[] w = new float[s.bots.length];
			float total = 0f;
			for (int i = 0; i < w.length; i++) {
				w[i] = 0.25f + 1f - familyMastery(familyOf(s.bots[i]));
				total += w[i];
			}
			float u = rng.nextFloat() * total;
			for (int i = 0; i < w.length; i++) {
				u -= w[i];
				if (u <= 0f) {
					return s.bots[i];
				}
			}
			return s.bots[w.length - 1];
		}
		if (stage == STAGES.length - 1 || rng.nextFloat() >= REHEARSAL) {
			if (s.mixerShare > 0f && rng.nextFloat() < s.mixerShare) {
				return Specialists.MIXER;
			}
			return s.bots[rng.nextInt(s.bots.length)];
		}
		if (stage == 0) {
			return Specialists.MIXER;
		}
		Stage before = STAGES[rng.nextInt(stage)];
		return rng.nextFloat() < 0.25f ? Specialists.MIXER : before.bots[rng.nextInt(before.bots.length)];
	}

	/** The stage a bot is taught in: this one if it is one of its bots, else the first stage that has it. */
	public int stageOf(int bot) {
		for (int b : current().bots) {
			if (b == bot) {
				return stage;
			}
		}
		for (int i = 0; i < STAGES.length; i++) {
			for (int b : STAGES[i].bots) {
				if (b == bot) {
					return i;
				}
			}
		}
		return stage;
	}

	/**
	 * The installed model's pull: no longer fading (24 Oct 22:15). Faded to 0.59, the school network lost the installed model's
	 * aim and basics: in the user's real fights against a player new to the game it took more hits than it dealt, its
	 * crosshair 3 to 15 degrees off them, shaking left and right when they jumped out of its view. It teaches the camera and
	 * the fight between the techniques for good.
	 */
	public float oldPull() {
		return OLD_PULL;
	}

	/**
	 * Run 15 (EXPERIMENTS.md): the copying of the installed model and of the frozen best on the body's keys fades from full to
	 * nothing over this many steps from the start of the run, so the fight's own reward can take it past them (kickstarting,
	 * Schmitt et al. 2018, DeepMind: a student distilled from a teacher with a weight annealed to zero went past the teacher).
	 * The camera stays copied at full (24 Oct 22:15: the pull faded on every key lost its aim in the user's real fights). Runs
	 * 1-14 kept the body pinned to the installed model (half pull plus the frozen best since run 9) and came out level with it
	 * (+0.1 to +5.6): pinned to it, it can only be about as good as it. 0: no fading.
	 */
	public static final long FADE_STEPS = 0L; // 600k in run 15 (25 Oct 19:50-22:30): the checks fell as it faded (+0.6 at 0.40, -7.0 at 0.20); off from run 16
	private long fadeFrom = -1L;
	/**
	 * The fade stops at this share (a pull of 0.05): fine-tuned with no copying at all, a policy forgets its skills in the
	 * situations it seldom meets (Wolczyk et al. 2024, "Fine-tuning reinforcement learning models is secretly a forgetting
	 * mitigation problem"; the research round of 25 Oct 20:45), and AlphaStar kept its copying term to the end.
	 */
	public static final float FADE_FLOOR = 0.1f;

	/**
	 * The fading of the body's copying starts now, unless the saved state has its start already: a check restarts the session,
	 * and until 25 Oct 20:44 every check started the fade again (the pull back at 0.48 after 0.40 at the 20:36 check).
	 */
	public void fadeFromNow() {
		if (fadeFrom < 0L) {
			fadeFrom = schoolSteps;
		}
	}

	/** The share of the copying left on the body's keys: 1 at the run's start, 0 after FADE_STEPS. */
	public float bodyFade() {
		if (FADE_STEPS <= 0L || fadeFrom < 0L) {
			return 1f;
		}
		return Math.max(FADE_FLOOR, 1f - (schoolSteps - fadeFrom) / (float) FADE_STEPS);
	}

	/**
	 * Run 20 (EXPERIMENTS.md): a budget for how far the body may go from what it copies instead of a fixed pull (the adaptive
	 * KL controller of Ziegler et al. 2019, "Fine-Tuning Language Models from Human Preferences"; VPT, Baker et al. 2022, kept
	 * its copying term and let it shrink slowly). After every update the body's pull moves by at most BUDGET_STEP (a share of
	 * itself) toward keeping the body keys' cross-entropy to their copying targets at BODY_CE_TARGET: it loosens while the body
	 * stays close and tightens once it goes past. The research of 26 Oct 05:20: since run 9 every untaught key copies the frozen
	 * run 6 at a fixed 0.5 (ANCHOR_TO_BEST), so every run ties run 6. 0: the fixed pull (OLD_PULL times bodyFade).
	 */
	public static final float BODY_CE_TARGET = 0.40f; // run 20 (26 Oct); at the fixed 0.5 the body sat at 0.24-0.30 (run 19)
	public static final float BUDGET_STEP = 0.03f;
	public static final float BUDGET_MIN_PULL = 0.05f;
	private float bodyPull = -1f;

	/** The copying's pull on the body's keys now (the camera keeps oldPull). */
	public float bodyPull() {
		if (BODY_CE_TARGET <= 0f) {
			return oldPull() * bodyFade();
		}
		return bodyPull < 0f ? oldPull() : bodyPull;
	}

	/** An update is over: the body keys' cross-entropy to their copying targets was {@code ce}. */
	public void noteBodyCe(float ce) {
		if (BODY_CE_TARGET <= 0f || ce <= 0f) {
			return;
		}
		float e = Math.max(-1f, Math.min(1f, (ce - BODY_CE_TARGET) / BODY_CE_TARGET));
		bodyPull = Math.max(BUDGET_MIN_PULL, Math.min(OLD_PULL, bodyPull() * (float) Math.exp(BUDGET_STEP * e)));
	}

	public void addSteps(long steps) {
		stageSteps += steps;
		schoolSteps += steps;
	}

	/** One fight against {@code bot} is over: its damage traded. Only this stage's own bots count for passing it. */
	public void fight(int bot, float dealt, float taken, int ticks) {
		if (bot < 0 || bot >= Specialists.KINDS || ticks < 100 || stageOf(bot) != stage) {
			return;
		}
		float perMinute = (dealt - taken) * 1200f / ticks;
		if (firstCount[bot] < RECENT) {
			first[bot][firstCount[bot]++] = perMinute;
		}
		last[bot][lastAt[bot]] = perMinute;
		lastAt[bot] = (lastAt[bot] + 1) % RECENT;
		lastCount[bot] = Math.min(RECENT, lastCount[bot] + 1);
	}

	private static float mean(float[] a, int n) {
		float s = 0f;
		for (int i = 0; i < n; i++) {
			s += a[i];
		}
		return n == 0 ? Float.NaN : s / n;
	}

	/** Whether the stage is passed (see the class comment); the last stage never is. */
	public boolean passed() {
		Stage s = current();
		if (stage == STAGES.length - 1) {
			return false;
		}
		if (stageSteps >= s.maxSteps) {
			return true;
		}
		if (stageSteps < s.minSteps) {
			return false;
		}
		if (!mastered()) {
			return false;
		}
		for (int b : s.bots) {
			if (lastCount[b] < RECENT || firstCount[b] < RECENT) {
				return false;
			}
			// Mastered, and not at the price of the fight: no bot's damage traded worse than at the stage's start.
			if (mean(last[b], lastCount[b]) < mean(first[b], firstCount[b]) - BEHIND) {
				return false;
			}
		}
		return true;
	}

	/** On to the next stage (round the spiral: after the last family, the one done least); returns its name. */
	public String advance() {
		if (!ALL_AT_ONCE && stage >= FAMILIES - 1) {
			int least = 0;
			for (int f = 1; f < FAMILIES; f++) {
				if (familyMastery(f) < familyMastery(least)) {
					least = f;
				}
			}
			stage = least;
		} else {
			stage = Math.min(STAGES.length - 1, stage + 1);
		}
		reached = Math.max(reached, Math.min(stage, FAMILIES - 1));
		stageSteps = 0;
		java.util.Arrays.fill(firstCount, 0);
		java.util.Arrays.fill(lastCount, 0);
		java.util.Arrays.fill(lastAt, 0);
		return current().name;
	}

	/** Where the stage stands, per bot: first and last damage a minute. */
	public String describe() {
		Stage s = current();
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
				"%s %s, %d of at least %d steps, installed network's pull %.2f; mastery:%s damage a minute first->last:",
				s.name, s.family, stageSteps, s.minSteps, bodyPull(), masteryLine()));
		for (int bot : s.bots) {
			b.append(String.format(Locale.ROOT, " %s %+.0f->%+.0f (%d)", Specialists.NAMES[bot], mean(first[bot], firstCount[bot]),
					mean(last[bot], lastCount[bot]), lastCount[bot]));
		}
		return b.toString();
	}

	public void save(Path file) throws IOException {
		Properties p = new Properties();
		p.setProperty("stage", Integer.toString(stage));
		p.setProperty("reached", Integer.toString(reached));
		p.setProperty("stageSteps", Long.toString(stageSteps));
		p.setProperty("schoolSteps", Long.toString(schoolSteps));
		if (fadeFrom >= 0L) {
			p.setProperty("fadeFrom", Long.toString(fadeFrom));
		}
		if (bodyPull >= 0f) {
			p.setProperty("bodyPull", Float.toString(bodyPull));
		}
		for (int b = 0; b < Specialists.KINDS; b++) {
			p.setProperty("first." + b, join(first[b], firstCount[b]));
			p.setProperty("last." + b, join(last[b], lastCount[b]) + "@" + lastAt[b]);
		}
		for (int t = 0; t < Technique.COUNT; t++) {
			StringBuilder m = new StringBuilder();
			for (int i = 0; i < momentCount[t]; i++) {
				m.append(moments[t][i]).append(paidMoments[t][i]);
			}
			p.setProperty("moments." + t, m + "@" + momentAt[t] + "@" + pullShare[t]);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			p.store(w, "SwordAI technique school");
		}
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	public void load(Path file) throws IOException {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		stage = Math.max(0, Math.min(STAGES.length - 1, Integer.parseInt(p.getProperty("stage", "0"))));
		if (ALL_AT_ONCE) {
			stage = STAGES.length - 1;
		}
		reached = Math.max(Math.min(stage, FAMILIES - 1), Integer.parseInt(p.getProperty("reached", "0")));
		stageSteps = Long.parseLong(p.getProperty("stageSteps", "0"));
		schoolSteps = Long.parseLong(p.getProperty("schoolSteps", "0"));
		fadeFrom = Long.parseLong(p.getProperty("fadeFrom", "-1"));
		bodyPull = Float.parseFloat(p.getProperty("bodyPull", "-1"));
		for (int b = 0; b < Specialists.KINDS; b++) {
			firstCount[b] = split(p.getProperty("first." + b, ""), first[b]);
			String l = p.getProperty("last." + b, "@0");
			int at = l.lastIndexOf('@');
			lastCount[b] = split(l.substring(0, at), last[b]);
			lastAt[b] = Integer.parseInt(l.substring(at + 1)) % RECENT;
		}
		for (int t = 0; t < Technique.COUNT; t++) {
			String[] m = p.getProperty("moments." + t, "@0@1").split("@", -1);
			momentCount[t] = Math.min(WINDOW, m[0].length() / 2);
			for (int i = 0; i < momentCount[t]; i++) {
				moments[t][i] = (byte) (m[0].charAt(2 * i) - '0');
				paidMoments[t][i] = (byte) (m[0].charAt(2 * i + 1) - '0');
			}
			momentAt[t] = Integer.parseInt(m[1]) % WINDOW;
			pullShare[t] = Float.parseFloat(m[2]);
		}
	}

	private static String join(float[] a, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			b.append(i == 0 ? "" : ",").append(a[i]);
		}
		return b.toString();
	}

	private static int split(String s, float[] into) {
		if (s.isEmpty()) {
			return 0;
		}
		String[] parts = s.split(",");
		int n = Math.min(parts.length, into.length);
		for (int i = 0; i < n; i++) {
			into[i] = Float.parseFloat(parts[i]);
		}
		return n;
	}
}
