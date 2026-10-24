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
		float p = 0.5f + 0.45f * rng.nextFloat();
		switch (bot) {
			case PUNISH_CRITTER -> s.set(Technique.B1, p);
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
			}
			case THREE_BLOCK -> {
				s.set(Technique.T7, p);
				s.strafe = 0.1f * rng.nextFloat();
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
		s.critOpen = rng.nextFloat() < 0.4f ? 0.5f * rng.nextFloat() : 0f;
		s.hitSelect = rng.nextFloat() < 0.4f ? s.p(Technique.T4) : 0f;
		s.critFrom = 1.5f + 3f * rng.nextFloat();
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
