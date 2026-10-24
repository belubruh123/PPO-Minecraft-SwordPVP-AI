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
	public static final float PULL_CAP = 0f; // 25 Oct 06:20 synced control continued (techniques on at 0.5 cost -19.1 vs -8.6 off)
	/**
	 * The answers to a jump-in (part 7: hit them while they rise, T8; no knockback hit on a falling one, K2; let it land and
	 * punish-crit, B1) taught on their own (25 Oct 07:40): with every teacher off the network was level with the installed
	 * model (06:54 check, -0.6) but knockback-hit falling jumpers 30 to 50 times an update, the user's first complaint.
	 */
	public static final float JUMP_IN_PULL_CAP = 0.5f;
	static final boolean[] JUMP_IN_ANSWER = new boolean[Technique.COUNT];

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
	public static final float OLD_PULL = 1.0f;
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
	public static final int[] PRIORITY_BOTS = {Specialists.JUMP_IN_CRITTER, Specialists.FALL_SPACER, Specialists.ATTACK_THEN_S};
	/**
	 * A third until the priority ones were learned (24 Oct 13:40: T8 77%, K2 89%, B1 64%, T10 62%); then less, so a
	 * stage's own bots get the fights: in the crit trades, the double crit had about one moment an update.
	 */
	public static final float PRIORITY_SHARE = 0.15f;
	/**
	 * Of the rest, the share of fights against a bot of the family of a technique still under {@link #MASTERED}, the
	 * weakest most often. A technique's moments come against its own family's bots: C3 (jump + hold S against a stun)
	 * had one to three moments an update in the jumping stage and stayed at 0% all night (24 Oct; the user: a technique
	 * that does not rise within an hour is critical). 0.2 until 24 Oct 17:30, when 19 techniques were under 80% (the user:
	 * "make sure it maintains the skill and technique").
	 */
	public static final float WEAKEST_SHARE = 0.55f;
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
			p[t] = TECH_PULL * Math.min(JUMP_IN_ANSWER[t] ? JUMP_IN_PULL_CAP : PULL_CAP, pullShare[t]);
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
				s.name, s.family, stageSteps, s.minSteps, oldPull(), masteryLine()));
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
