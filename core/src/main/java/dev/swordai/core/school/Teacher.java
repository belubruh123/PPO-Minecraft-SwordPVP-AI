package dev.swordai.core.school;

import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Keys;
import dev.swordai.core.pvp.PvpPlayer;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;

/**
 * A technique teacher (plan part 6): the bots' own {@link PvpPlayer}, run for the learner's body with a
 * style that uses the techniques of one family whenever their moment comes, at once and on time. Its
 * keys are never pressed: where one of its techniques is running, they become the target of the
 * kickstarting pull on the heads that technique uses ({@link SchoolPpo}).
 *
 * <p>It knows how its opponent is set to play (a teacher may; the network has to read it from the
 * memory of the fight), so its counters are the right ones.
 */
public final class Teacher {
	/** Of a target, the share on the teacher's key; the rest is spread over the other keys of the head. */
	static final float SURE = 0.99f;
	/** A key the target holds at least this much of is one the teacher asks for (alone, or with others alike). */
	public static final float ANY = 0.3f;
	/**
	 * Whether it teaches the file's ordinary fight (part 0) between the techniques. Off when the installed model teaches that
	 * instead (the overlay): the file's techniques at their moments, the installed model's fighting between them.
	 */
	public boolean ordinaryFight = true;

	/**
	 * How much more a technique teacher's press of the jump or the swing weighs than its other keys. A press is one tick
	 * among many of "not now", and missed on time it fails the whole moment: the uppercut missed its jump on half its
	 * moments (24 Oct, 11-17 an update) while its other keys were right on three ticks in four.
	 */
	public static final float PRESS_WEIGHT = 3f;

	/**
	 * {@link #PRESS_WEIGHT} where head {@code h} of {@code target} is a press of the jump or the swing, or W or the sprint key
	 * let go while still sprinting (the press that ends a sprint, which every crit needs: the punish crit kept W on a third of its
	 * ticks and so stayed sprinting, 24 Oct 11:36); else 1.
	 *
	 * @param vec the step's vector input (its own sprint is read from it)
	 */
	/**
	 * The punish crit (B1, and its wait against a jump-in) weighs this much more again on every key it teaches. The user,
	 * 26 Oct 16:30: "just guide it to ALWAYS do it ... I want to see it over 90%"; its jump-ins were punished 8-15%.
	 */
	public static final float PUNISH_WEIGHT = 3f;

	public static float pressWeight(float[] target, int h, float[] vec, int technique) {
		float w = techniquePressWeight(target, h, vec, technique);
		return technique == Technique.B1.ordinal() ? PUNISH_WEIGHT * w : w;
	}

	private static float techniquePressWeight(float[] target, int h, float[] vec, int technique) {
		// The golden rule (K2): its swing held back weighs as a press. Taught as a plain "no swing" beside every swing the
		// basics teacher weighs three times, it slipped from 85-100% to about 60% an update (24 Oct 15:35, the user).
		if (h == ActionSpace.ATTACK && technique == Technique.K2.ordinal()) {
			int off = ActionSpace.OFFSETS[h];
			return target[off] > target[off + 1] ? PRESS_WEIGHT : 1f;
		}
		// The basics teacher's swing weighed as its swing held back from 16:35 to 19:00 (24 Oct); with the whiff guard keeping
		// it from swings at nothing, that and a dearer swing at nothing left it swinging a fifth less than the installed model
		// (18:48 check: 317 swings to 400, 79% landing): weighed three times again.
		// A strafe in a technique's moment weighs as a press too: the double crit around them (T1b) stayed at about a third,
		// off on A/D, where every other press it is taught weighs three times.
		if (h == ActionSpace.STRAFE && technique != Technique.BASE.ordinal()) {
			int off = ActionSpace.OFFSETS[h];
			return Math.max(target[off + 1], target[off + 2]) > target[off] ? PRESS_WEIGHT : 1f;
		}
		boolean inTechnique = technique >= 0 && technique != Technique.BASE.ordinal();
		// In a technique's moment the swing held back weighs as its swing: C7 waits for their jump, T6 for their miss, and with
		// the basics teacher's swing on them weighing three times the network swung early there (24 Oct 19:00, off on the
		// swing: T6 44% of its ticks, T10 12%, T5 13%, C7 11%). And S held (the way out, the step back, the stop that makes them
		// miss) and the sprint key pressed (T8 off on it 21%, T1a 14%), as the presses they are.
		// The jump the same: the standing crit (T2, C6) waits for the sword before it jumps, and was off on the jump on 12-33%
		// of its ticks (19:10), jumping while it should still wait.
		if (inTechnique && (h == ActionSpace.ATTACK || h == ActionSpace.JUMP)) {
			return PRESS_WEIGHT;
		}
		// W/S whatever it is to be: S alone weighed three times (19:06-19:50) leaned it to S where W was taught, and the
		// deflection (T4), the uppercut (T10) and the rising hit (T8), all off on W/S, slipped about ten points.
		if (inTechnique && h == ActionSpace.FORWARD) {
			return PRESS_WEIGHT;
		}
		if (inTechnique && h == ActionSpace.SPRINT) {
			int off = ActionSpace.OFFSETS[h];
			boolean sprinting = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] >= 0.5f;
			if (target[off + 1] > target[off] && !sprinting) {
				return PRESS_WEIGHT;
			}
		}
		return pressWeight(target, h, vec);
	}

	public static float pressWeight(float[] target, int h, float[] vec) {
		boolean sprinting = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] >= 0.5f;
		if (h == ActionSpace.FORWARD) {
			int off = ActionSpace.OFFSETS[h];
			boolean letGo = target[off + 1] < Math.max(target[off], target[off + 2]);
			return letGo && sprinting ? PRESS_WEIGHT : 1f;
		}
		if (h == ActionSpace.SPRINT) {
			// The sprint key let go while sprinting: held with W it keeps (or starts again) the sprint, and no crit comes (the
			// bait crit kept it on 60% of its ticks, 24 Oct 13:00, so the double crit after it never started).
			int off = ActionSpace.OFFSETS[h];
			return target[off] > target[off + 1] && sprinting ? PRESS_WEIGHT : 1f;
		}
		if (h != ActionSpace.JUMP && h != ActionSpace.ATTACK) {
			return 1f;
		}
		int off = ActionSpace.OFFSETS[h];
		return target[off + 1] > target[off] ? PRESS_WEIGHT : 1f;
	}

	private final PvpPlayer player = new PvpPlayer();
	private final Keys keys = new Keys();
	private final boolean[] teaches = new boolean[Technique.COUNT];

	/**
	 * A fight: the family it teaches. It knows of the opponent only what the network can know, what it has seen this
	 * fight: a teacher that knew the bot's style chose counters the network could not tell apart from its view, and
	 * the network learned a muddle of them (24 Oct).
	 */
	public void start(Technique[] family, float swingCharge, long seed) {
		java.util.Arrays.fill(teaches, false);
		for (Technique t : family) {
			teaches[t.ordinal()] = true;
		}
		Style s = style(family);
		// It swings when the network can: no sooner than the game's limit on the network's swing.
		s.swingCharge = Math.max(s.swingCharge, swingCharge);
		player.start(s, seed);
	}

	/** The teacher's style: its family's techniques every time, no lag, no reaction time beyond a tick. */
	public static Style style(Technique[] family) {
		Style s = new Style();
		s.name = "teacher";
		for (Technique t : family) {
			s.set(t, 1f);
		}
		// No reaction time: a plan starts on the tick its moment comes, never a tick later by chance.
		s.reaction = 0;
		s.timing = 1f;
		s.lag = 0;
		s.ping = 0;
		s.aimNoise = 0f;
		s.turnRate = 180f;
		s.swingCharge = 0.9f;
		s.spacing = 3.0f;
		s.wtap = 2;
		s.stap = 0;
		s.strafe = 0.3f;
		s.adapts = true;
		s.onlyWhenItWorks = true;
		s.punishJumpIns = true;
		// Jump resets wherever the file has them (T9 on a sprint hit, T9b, T9c), not only from their second hit in a row: the
		// user, 24 Oct 22:50, "remove jump reset barrier cause I didn't even see it manage to pull a jump reset".
		s.resetsInCombosOnly = false;
		// It swings first whenever it can: hit selecting as half the times, for a drawn while, would teach the swing as a coin
		// toss. The deflection (T4) is still shown, whenever they swing first.
		s.hitSelect = 0f;
		return s;
	}

	/** Ticks what the network sees of the other is behind the game this fight (see PvpPlayer#seenLag). */
	public void seenLag(int ticks) {
		player.seenLag(ticks);
	}

	/** @param aimed the opponent is under the crosshair and in reach: a swing the teacher asks for is only taught then */
	public void tick(Duel d, Events e, boolean aimed) {
		player.tick(d, e, keys);
		this.aimed = aimed;
	}

	private boolean aimed;

	/** The technique it is in now ({@link Technique#BASE} for the ordinary fight). */
	public Technique technique() {
		return keys.technique;
	}

	public Keys keys() {
		return keys;
	}

	/**
	 * Whether it has something to teach on this tick: one of its family's techniques is running and says
	 * which keys it uses. If so, writes the target into {@code target} and returns the heads it covers as
	 * {@link ActionSpace} head bits; else 0.
	 */
	public int target(float[] target) {
		if (keys.technique == Technique.BASE && !ordinaryFight) {
			return 0;
		}
		if (keys.technique == Technique.BASE) {
			// The ordinary fight of part 0: sprint at them, the sprint hit with the sword back, W-tap after a hit, S-tap
			// to keep the spacing while it comes back; and no jump in it, every jump of the file being a technique's
			// (24 Oct: 192 jumps a minute without this). Its strafe is a coin toss, left to the network.
			int heads = put(target, ActionSpace.JUMP, 0);
			heads |= putMove(target, keys.technique);
			if (keys.forward > 0) {
				heads |= put(target, ActionSpace.SPRINT, keys.sprint ? 1 : 0);
			}
			heads |= put(target, ActionSpace.ATTACK, keys.attack && aimed ? 1 : 0);
			return heads;
		}
		if (!teaches[keys.technique.ordinal()] || keys.taught == 0) {
			return 0;
		}
		int heads = 0;
		if ((keys.taught & Keys.KEY_MOVE) != 0) {
			heads |= putMove(target, keys.technique);
		}
		if ((keys.taught & Keys.KEY_STRAFE) != 0) {
			// Which way is the teacher's coin toss: either way is the strafe.
			heads |= keys.strafe != 0 ? putEither(target, ActionSpace.STRAFE, 1, 2) : put(target, ActionSpace.STRAFE, 0);
		}
		// The sprint key does nothing without W: taught only while the teacher holds W.
		if ((keys.taught & Keys.KEY_SPRINT) != 0 && keys.forward > 0) {
			heads |= put(target, ActionSpace.SPRINT, keys.sprint ? 1 : 0);
		}
		// A technique the file gives no jump has none: taught as no jump, or nothing teaches the jump key in its moments
		// (24 Oct, the overlay: 164 jumps a minute inside technique moments, the installed model 44).
		heads |= put(target, ActionSpace.JUMP, (keys.taught & Keys.KEY_JUMP) != 0 && keys.jump ? 1 : 0);
		if ((keys.taught & Keys.KEY_ATTACK) != 0) {
			// A swing at an opponent not under the crosshair is a miss: the camera first.
			heads |= put(target, ActionSpace.ATTACK, keys.attack && aimed ? 1 : 0);
		}
		return heads;
	}

	/** W, or not W: stop and S alike, but where S is the technique itself (C2, C3, C4). */
	private int putMove(float[] target, Technique t) {
		if (keys.forward > 0) {
			return put(target, ActionSpace.FORWARD, 1);
		}
		if (Performance.sNeeded(t.ordinal())) {
			return put(target, ActionSpace.FORWARD, keys.forward < 0 ? 2 : 0);
		}
		return putEither(target, ActionSpace.FORWARD, 0, 2);
	}

	/** Two keys alike: the teacher asks for either (see {@link #aim}). */
	private static int putEither(float[] target, int head, int a, int b) {
		int off = ActionSpace.OFFSETS[head];
		int n = ActionSpace.SIZES[head];
		float rest = (1f - SURE) / (n - 2);
		for (int k = 0; k < n; k++) {
			target[off + k] = k == a || k == b ? SURE / 2f : rest;
		}
		return 1 << head;
	}

	/**
	 * The target one head is pulled to, into {@code out}. Where the teacher asks for any of several keys alike (either
	 * strafe; stop or S), the pull is to the network's own split of them: the loss is minus the log of their summed
	 * chance (learning from a set of labels, Jin and Ghahramani 2002; Cour et al. 2011), whose gradient is p minus p
	 * kept to the set. A fixed half and half would split the vote between them and let a key taught nowhere there win
	 * it (24 Oct: T6a held W on 89% of its ticks).
	 */
	public static void aim(float[] target, float[] probs, int off, int n, float[] out) {
		int alike = 0;
		float mass = 0f;
		float share = 0f;
		for (int k = 0; k < n; k++) {
			if (target[off + k] >= ANY) {
				alike++;
				mass += probs[off + k];
				share += target[off + k];
			}
		}
		if (alike < 2) {
			System.arraycopy(target, off, out, off, n);
			return;
		}
		for (int k = 0; k < n; k++) {
			float t = target[off + k];
			out[off + k] = t >= ANY ? share * (mass > 1e-9f ? probs[off + k] / mass : 1f / alike) : t;
		}
	}

	private static int put(float[] target, int head, int choice) {
		int off = ActionSpace.OFFSETS[head];
		int n = ActionSpace.SIZES[head];
		float rest = (1f - SURE) / (n - 1);
		for (int k = 0; k < n; k++) {
			target[off + k] = k == choice ? SURE : rest;
		}
		return 1 << head;
	}
}
