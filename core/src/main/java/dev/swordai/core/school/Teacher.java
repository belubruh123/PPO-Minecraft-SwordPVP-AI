package dev.swordai.core.school;

import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Keys;
import dev.swordai.core.pvp.PvpPlayer;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;

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
		s.hitSelect = s.p(Technique.T4) > 0f ? 0.5f : 0f;
		return s;
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
		if ((keys.taught & Keys.KEY_JUMP) != 0) {
			heads |= put(target, ActionSpace.JUMP, keys.jump ? 1 : 0);
		}
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
