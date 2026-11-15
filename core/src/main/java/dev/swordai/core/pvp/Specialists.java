package dev.swordai.core.pvp;

import java.util.Random;

/**
 * The opponents of the technique school (plan part 3): a bot for every technique, and the "real player"
 * that mixes them all and adapts. Each specialist plays the ordinary fight, and when its technique's
 * moment comes it uses it with a chance drawn per fight between 0.5 and 0.95. Its body (reaction,
 * timing, connection, aim) is drawn as a person's.
 */
public final class Specialists {
	public static final int PUNISH_CRITTER = 0;
	public static final int DOUBLE_CRITTER = 1;
	public static final int CRIT_BREAKER = 2;
	public static final int STANDING_CRITTER = 3;
	public static final int DEFLECTOR = 4;
	public static final int REVERSE_DEFLECTOR = 5;
	public static final int MISS_BAITER = 6;
	public static final int SWINGER = 7;
	public static final int THREE_BLOCK = 8;
	public static final int STRAIGHT_SPRINTER = 9;
	public static final int JUMP_IN_CRITTER = 10;
	public static final int JUMP_RESETTER = 11;
	public static final int RHYTHM_COMBOER = 12;
	public static final int STUN_ESCAPER = 13;
	public static final int ATTACK_THEN_S = 14;
	public static final int UPPERCUTTER = 15;
	public static final int LUNGER = 16;
	public static final int HOPPER = 17;
	public static final int FALL_SPACER = 18;
	public static final int STUNNER = 19;
	public static final int COUNT = 20;
	/** Not a specialist: the real player, every technique at a drawn chance, adapting. */
	public static final int MIXER = 20;
	public static final int KINDS = 21;

	public static final String[] NAMES = {"punish-critter", "double-critter", "crit-breaker", "standing-critter", "deflector",
			"reverse-deflector", "miss-baiter", "swinger", "three-block", "straight-sprinter", "jump-in-critter", "jump-resetter",
			"rhythm-comboer", "stun-escaper", "attack-then-s", "uppercutter", "lunger", "hopper", "fall-spacer", "stunner", "mixer"};

	/** What the AI is to learn against each (plan part 3), for the logs. */
	public static final String[] LESSONS = {"T1/T1a/T1b/T1c/T2 against punish crits", "C1, C2 against double crits",
			"C2 when its double crit is sprint-hit", "C6 logic, C1 against standing crits", "T5, T9b against deflections",
			"deflect with stun spacing (T4)", "no swing at what will be out of reach; T6", "T6 after its miss",
			"T9c, C7 against three-block hits", "T7 or T9c", "T8, K2, B1 against jump-ins", "C6, C7, spacing against jump resets",
			"T9a, C3 against combos", "T11a, C4, T11c against S", "no sprint-jump into its hit", "T8 against uppercuts",
			"K1/K2, T8, B1 against lunges", "back out, C3 against hops", "K2, K3", "C3, C5, T9a against stuns", "everything"};

	private Specialists() {
	}

	/** The style of specialist {@code bot} (or {@link #MIXER}) for one fight. */
	public static Style draw(int bot, Random rng) {
		Style s = new Style().drawHuman(rng);
		s.bot = bot;
		s.name = NAMES[bot];
		// Run 33 (2 Nov): people open an exchange with a jump-in crit. The user: "99% of user starts with jump in crit"; in
		// their 194 rounds the opponent jumped in before the first hit in at least half, mostly from 0-5 blocks
		// (tools/jumpins.py). Until then only the jump-in critter, the double critter and four mixers in ten opened so, and
		// in the checks the bots hit first in 70% of the openings with their sprint hits. The specialists below keep their own.
		s.critOpen = 0.5f + 0.4f * rng.nextFloat();
		s.critFrom = 2.5f + 3f * rng.nextFloat();
		// Bot audit (7 Nov): no bot landed its jump-in 2.5-3 blocks out as the user described players doing (2 Nov); half do now.
		s.jumpInLands = rng.nextBoolean();
		s.humanTiming = true;
		float p = 0.5f + 0.45f * rng.nextFloat();
		switch (bot) {
			// Bot audit (7 Nov): it crit back after the agent's hits only 4-7% (the hold for the way down, punishJumpIns, was
			// the teacher's alone): the document's "let it land, punish crit" now held for, so the agent meets real punish crits.
			case PUNISH_CRITTER -> {
				s.set(Technique.B1, p);
				s.punishJumpIns = true;
			}
			case DOUBLE_CRITTER -> {
				// It crits, and crits again; it opens with crits so that there is a punish crit to beat.
				s.set(Technique.T1, p).set(Technique.T1A, 0.5f * p).set(Technique.T1B, 0.4f * rng.nextFloat()).set(Technique.T1C, p)
						.set(Technique.B1, 0.6f);
				s.critOpen = 0.4f + 0.3f * rng.nextFloat();
				s.critFrom = 1.5f + 0.8f * rng.nextFloat();
			}
			case CRIT_BREAKER -> {
				s.set(Technique.C1, p).set(Technique.C2, 0.5f);
				s.believe[Habits.DOUBLE_CRIT] = 1f;
			}
			case STANDING_CRITTER -> {
				s.set(Technique.T2, p).set(Technique.C6, 0.5f * p);
				s.believe[Habits.JUMP_RESET] = 0.5f;
			}
			case DEFLECTOR -> {
				// Hit selecting: waits for the other's swing, and sprint-hits right after it.
				s.set(Technique.T4, p);
				s.hitSelect = p;
				s.reaction = 2 + rng.nextInt(3);
				// Its spacing as a person's, not always the stun's: "too late, or too close, and they hit you through your
				// deflection, and there is no stun" (T4). A deflection that does not stun is the reverse deflection's moment
				// (T5); from perfect stun spacing every time, the network had none in fifteen updates (24 Oct).
				s.spacing = 2.1f + 1.0f * rng.nextFloat();
			}
			case REVERSE_DEFLECTOR -> {
				s.set(Technique.T5, p).set(Technique.T4, 0.3f);
				s.believe[Habits.DEFLECT] = 0.5f;
			}
			case MISS_BAITER -> s.set(Technique.T6, p).set(Technique.T6A, 0.5f * rng.nextFloat());
			case SWINGER -> {
				// The mistake T6 punishes: swings early, out of reach.
				s.swingEarly = 0.4f + 0.8f * rng.nextFloat();
				s.swingCharge = 0.9f;
				s.closeIn = 0f;
			}
			case THREE_BLOCK -> {
				s.set(Technique.T7, p);
				s.strafe = 0.1f * rng.nextFloat();
				// Its hits are the three-block ones: from the edge of its reach.
				s.closeIn = 0f;
			}
			case STRAIGHT_SPRINTER -> {
				// The cue of T9c, with nothing behind it: straight in at a sprint, never a jump.
				s.noJumps = true;
				s.strafe = 0f;
				s.wtap = 0;
				s.stap = 0;
			}
			case JUMP_IN_CRITTER -> {
				// Jumps at the AI from 1.8 to 5.7 blocks and crits on the way down, rising and falling at all heights.
				s.critOpen = p;
				s.critFrom = 1.8f + 3.9f * rng.nextFloat();
				s.set(Technique.B1, 0.5f);
			}
			case JUMP_RESETTER -> {
				s.set(Technique.T9, p).set(Technique.T9A, 0.5f * p).set(Technique.T9B, 0.3f * p);
				s.believe[Habits.DEFLECT] = 0.3f;
			}
			case RHYTHM_COMBOER -> {
				s.rhythm = true;
				s.wtap = 1 + rng.nextInt(2);
			}
			case STUN_ESCAPER -> s.set(Technique.C3, p);
			case ATTACK_THEN_S -> s.set(Technique.C3, p).set(Technique.C4, 0.9f);
			case UPPERCUTTER -> s.set(Technique.T10, p);
			case LUNGER -> s.set(Technique.T11A, p);
			case HOPPER -> s.set(Technique.T11B, p).set(Technique.T11C, 0.5f * p);
			case FALL_SPACER -> s.set(Technique.K3, p).set(Technique.K2, 0.8f);
			case STUNNER -> {
				s.set(Technique.T4, 0.5f * p).set(Technique.T6, 0.4f * p).set(Technique.T7, 0.5f * p).set(Technique.T10, 0.3f * p);
				s.wtap = 1;
			}
			case MIXER -> mix(s, rng);
			default -> throw new IllegalArgumentException("no bot " + bot);
		}
		return s;
	}

	/**
	 * Run 93 (14 Nov ~02:00): the comboable bot, a realistic player that CAN be held in a sprint-hit combo (the user wants "an advance
	 * combo bot ... do sprint hit most of time", combos of 10+). Agent 32's finding: no bot in training lets itself be comboed (it
	 * stays in reach and hits back, its knockback late and often scaled down), so no reward could teach a held combo. This one has no
	 * technique, never jumps (no jump reset, no hop, no crit), takes the full knockback with a small ping, and after every hit it takes
	 * is stunned for {@link Style#stunAfterHit} ticks (run 93b; {@link #COMBOABLE_STUNNED}): no swing, and the spacing of a sword coming
	 * back, so it walks in again to about 3 blocks; then it swings when its sword is back and the other is in reach. Held at the right
	 * spacing and rhythm it is comboed (the document's T3 stun, T4, T7), and bad spacing or a late follow-up (walking into it with an
	 * empty sword, or coming after its stun) still costs a hit. Run 93 had it walk back with S instead ({@link Style#backAfterHit}).
	 */
	public static Style comboable(Random rng) {
		Style s = new Style().drawHuman(rng);
		s.bot = STRAIGHT_SPRINTER;
		s.name = "comboable";
		java.util.Arrays.fill(s.p, 0f);
		s.adapts = false;
		s.noJumps = true;
		s.critOpen = 0f;
		s.jumpInLands = false;
		s.humanTiming = true;
		s.reaction = 3 + rng.nextInt(3);
		// A small ping (its knockback reaches it 1-2 ticks late; TechniqueBot adds the agent's way up) and a fast view of the other.
		s.ping = 1 + rng.nextInt(2);
		s.lag = 1 + rng.nextInt(2);
		s.swingCharge = 0.95f + 0.05f * rng.nextFloat();
		s.spacing = 2.9f + 0.4f * rng.nextFloat();
		s.wtap = rng.nextInt(2);
		s.stap = 0;
		s.strafe = 0.1f * rng.nextFloat();
		s.closeIn = 0.1f + 0.3f * rng.nextFloat();
		s.answerCrit = 0f;
		s.flyOut = 0f;
		s.hitSelect = 0f;
		s.swingEarly = 0f;
		s.rhythm = false;
		// Longer than the 12-13 ticks the AI's sword takes to come back, so that the next hit of a held combo can land inside it
		// (run 93b: the stun; run 93: the walk back). The same draw for both.
		// Run 93c (14 Nov ~10:25, agent 37's plan step 2): 12-16 once "its next hit within 20" held 60%+ (64% over 57 fights since 09:41, 70% and 64% in
		// the last two full 10-minute windows): a tighter stun makes waiting at the edge of reach, not rushing, the way to hold it.
		int window = 12 + rng.nextInt(STUN_SPREAD);
		if (COMBOABLE_STUNNED) {
			s.stunAfterHit = window;
		} else {
			s.backAfterHit = window;
		}
		return s;
	}

	/**
	 * Run 97 (agent 39; the user, 14 Nov 14:50: "make sure its opponent is designed to teach it hold combo with hops lunge and upper cut and
	 * some regular sprint hit and random strafe ... upper cut or any jump combo style always had it's combo be broken by opponent so make
	 * sure it is stable to hold combo like that"): the combo breaker, the stunned comboable bot ({@link #comboable}, the same draws first)
	 * that gets out of a badly held combo the document's ways, each rolled per hit it takes after a short stun ({@link Style#breakStun}):
	 * T8 on one rising into its reach from its own jump (a hop, uppercut or lunge taken off too near), the T9a jump reset of a hit in a
	 * set rhythm with the crit falling, the C3 back-out (jump and S: the lunge's moment, T11a), and the swing back at one in its reach
	 * with an empty sword or too close. A hold timed as the document has it (falling into reach, from the edge of reach, the rhythm
	 * varied, the lunge after an S) still chains (agent 39's ComboSim numbers, EXPERIMENTS.md run 97).
	 */
	public static Style comboBreaker(Random rng) {
		Style s = comboable(rng);
		s.name = "combo-breaker";
		s.breakRising = BREAK_RISING_LEAST + (1f - BREAK_RISING_LEAST) * rng.nextFloat();
		s.breakRhythm = BREAK_RHYTHM_LEAST + 0.4f * rng.nextFloat();
		s.breakBackOut = BREAK_BACK_OUT_LEAST + 0.2f * rng.nextFloat();
		s.breakEmpty = BREAK_EMPTY_LEAST + 0.4f * rng.nextFloat();
		s.breakStun = BREAK_STUN_LEAST + rng.nextInt(3);
		return s;
	}

	/**
	 * Run 100 (agent 42; the user, 15 Nov 08:35, after the installed network run98-2210 beat most real players: "those rounds are
	 * advance crit spammers. You thought landing a punish crit is enough? no. they jump and crit after your punish crit and then also
	 * does crit combo ... make a replica version like that"): the crit spammer of the 7 rounds lost on 14 Nov evening (126 hits taken,
	 * 106 dealt), drawn from what those rounds measured (tools/critspam.py):
	 * <ul>
	 * <li>it opens nearly every exchange with a jump-in crit (59 of their 126 hits): take-off 3.5 blocks (p25 2.6, p75 4.7, spread within
	 * a round 0.6-7), the hit 9 ticks later (8-9) at 1.5 blocks (0.5-2.2), the AI on the ground with its sword ready;</li>
	 * <li>the double crit (T1, bait T1a): after its crit it jumps again on landing, before the AI's punish crit lands (17 of 33 punish
	 * crits), and keeps the jump through that punish crit (Style#critSpam), its second crit 5 ticks after the punish crit landed
	 * (p25 4, p75 8), 13 ticks after its own last swing (12-14: the moment its sword is back);</li>
	 * <li>lifted on the ground by the punish crit instead, it crits back on the way down (B1 back: answerCrit, answerByCrit): their hit
	 * back within 12 ticks 26 of 33 in the lost rounds (79%; won rounds 53%);</li>
	 * <li>the crit combo: after its crit back it jumps and crits again (T1c), took off again within 25 ticks 15 of 26, median 6 ticks.</li>
	 * </ul>
	 * Only a sprint hit stops its double crit early in the jump (the document's C1, "sprint-hit them, which knocks them away"; part 7:
	 * "sprint hit beats the double crit"); its C2 (S when its double crit is sprint-hit) is drawn low so that C1 works as the file says.
	 * The AI's answers are learned by the outcome, from the document only: C1, K3/jump along with their jump-in, T6 after its punish crit,
	 * T1c, K2 and the guard as they are.
	 */
	public static Style critSpammer(Random rng) {
		Style s = new Style().drawHuman(rng);
		s.bot = DOUBLE_CRITTER;
		s.name = "crit-spammer";
		s.adapts = false;
		s.humanTiming = true;
		s.critSpam = true;
		s.t1cAtLanding = true;
		s.jumpInLands = false;
		s.critOpen = 0.85f + 0.15f * rng.nextFloat();
		s.critFrom = 3.1f + 0.8f * rng.nextFloat();
		s.critFromSpread = CRIT_SPAM_FROM_SPREAD;
		float p = 0.35f + 0.3f * rng.nextFloat();
		s.set(Technique.T1, p).set(Technique.T1A, 0.15f + 0.15f * rng.nextFloat()).set(Technique.T1C, 0.45f + 0.3f * rng.nextFloat())
				.set(Technique.B1, 0.6f).set(Technique.C2, 0.25f);
		s.answerCrit = 0.85f + 0.1f * rng.nextFloat();
		s.answerByCrit = CRIT_SPAM_ANSWER_BY_CRIT;
		// Its hit back came 13 ticks (12-14) after its own swing: with the sword back, not short of it.
		s.swingCharge = 0.95f + 0.05f * rng.nextFloat();
		return s;
	}

	/** Run 100: the crit spammer's take-off spread (uniform, critFrom +- this) and its share of crit answers made as a crit back. */
	public static final float CRIT_SPAM_FROM_SPREAD = 2.1f;
	public static final float CRIT_SPAM_ANSWER_BY_CRIT = 0.9f;

	/** Run 97: the least of each escape's chance drawn for a combo breaker (each drawn up from here), and of its short stun, ticks. */
	public static final float BREAK_RISING_LEAST = 0.6f;
	public static final float BREAK_RHYTHM_LEAST = 0.4f;
	public static final float BREAK_BACK_OUT_LEAST = 0.15f;
	public static final float BREAK_EMPTY_LEAST = 0.5f;
	public static final int BREAK_STUN_LEAST = 3;

	/**
	 * Run 93b (agent 37, 14 Nov 05:30): the comboable bot is stunned after each hit it takes (Style#stunAfterHit: no swing, and the
	 * spacing of a sword coming back) instead of walking back with S (Style#backAfterHit). The walk back was the document's C3, the
	 * way OUT of a combo ("jump and hold S, so that you fly out of the trade"): S held through its knockback carried it about 4.5
	 * blocks (3.2 without S), and it came back only once its S was over, sword ready, 15-25 ticks after the hit (past COMBO_GAP 20),
	 * head-on. A sprinter that chased it perfectly (agent 37's game-physics simulation, tools/combosim, 300 fights) had 3+ runs in
	 * 1% of its runs (none at a 4-tick view lag), and after 47% of its hits no next hit nor theirs within 20 ticks, 4.4 blocks apart;
	 * in training 180 fights gave runs to 3 four times. Stunned and not fleeing, it is the document's T3 victim: knocked back, it
	 * walks in again to the edge of reach, and the next sprint hit lands if the AI kept up and swung as its sword came back; late or
	 * too far, it is hit (its stun ends). In the simulation: the perfect sprinter's 3+ runs 1% -> 8% of its runs (10+ 18 -> 94),
	 * a noisy half-trained policy's 3+ runs 0.04 -> 0.55 a fight, at every view lag. false: run 93 as it was.
	 */
	public static final boolean COMBOABLE_STUNNED = true;
	/** Run 93c: the stun draw is 12 + 0..STUN_SPREAD-1 ticks (run 93b: 9, i.e. 12-20; run 93c: 5, i.e. 12-16). */
	public static final int STUN_SPREAD = 9; // run 93d (12:30): back to 12-20; with 12-16 "next" fell 64% -> 46% (68 fights) under run 95

	/**
	 * A specialist that does other things too (the user, 24 Oct 22:50: an opponent for each case "that is suitable to use the
	 * PVP method but like doing other stuff than the teacher and is random, so it learns all the cases and adapts"): {@code
	 * bot}'s own technique as {@link #draw} gives it, and every technique it does not have at a real player's chances (as
	 * {@link #mix} draws them, a little lower), so that its case comes among other play and has to be told from it.
	 */
	public static Style varied(int bot, Random rng) {
		Style s = draw(bot, rng);
		if (bot == MIXER) {
			return s;
		}
		Style other = new Style();
		mix(other, rng);
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (t != Technique.BASE && s.p[i] == 0f) {
				s.p[i] = 0.6f * other.p[i];
			}
		}
		s.adapts = true;
		s.name = "random " + NAMES[bot];
		return s;
	}

	/**
	 * The real player: every technique at its own chance, most low and a few high, as a person has a
	 * game of their own; and it adapts to what it sees ({@link Style#adapts}).
	 */
	public static void mix(Style s, Random rng) {
		s.name = "mixer";
		s.bot = MIXER;
		s.adapts = true;
		for (Technique t : Technique.ALL) {
			if (t == Technique.BASE) {
				continue;
			}
			float u = rng.nextFloat();
			// About one in four techniques is one of its favourites; the rest it uses now and then or never.
			s.p[t.ordinal()] = u < 0.25f ? 0.5f + 0.45f * rng.nextFloat() : u < 0.65f ? 0.25f * rng.nextFloat() : 0f;
		}
		// The real player opens with a jump-in crit as people do (run 33, see draw).
		s.critOpen = 0.5f + 0.4f * rng.nextFloat();
		s.hitSelect = rng.nextFloat() < 0.4f ? s.p(Technique.T4) : 0f;
		s.critFrom = 2.5f + 3f * rng.nextFloat();
		s.rhythm = rng.nextFloat() < 0.2f;
	}

	/**
	 * Whether the style drawn from this seed is one only the tests meet (plan part 3: some styles are held
	 * out for testing). One seed in eight.
	 */
	public static boolean heldOut(long seed) {
		long h = seed * 0x9E3779B97F4A7C15L;
		return (h >>> 61) == 0;
	}

	/** A real player's style for a training fight: drawn from seeds that are not held out. */
	public static Style mixer(Random rng) {
		while (true) {
			long seed = rng.nextLong();
			if (!heldOut(seed)) {
				return draw(MIXER, new Random(seed));
			}
		}
	}

	/** A held-out real player, for the tests. */
	public static Style heldOutMixer(Random rng) {
		while (true) {
			long seed = rng.nextLong();
			if (heldOut(seed)) {
				Style s = draw(MIXER, new Random(seed));
				s.heldOut = true;
				return s;
			}
		}
	}
}
