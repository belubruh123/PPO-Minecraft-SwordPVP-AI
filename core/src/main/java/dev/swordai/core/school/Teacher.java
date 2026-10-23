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

	private final PvpPlayer player = new PvpPlayer();
	private final Keys keys = new Keys();
	private final boolean[] teaches = new boolean[Technique.COUNT];

	/** A fight: the family it teaches, and what it knows of the opponent's style (null: nothing). */
	public void start(Technique[] family, Style opponent, float swingCharge, long seed) {
		java.util.Arrays.fill(teaches, false);
		for (Technique t : family) {
			teaches[t.ordinal()] = true;
		}
		Style s = style(family);
		// It swings when the network can: no sooner than the game's limit on the network's swing.
		s.swingCharge = Math.max(s.swingCharge, swingCharge);
		player.start(s, seed);
		if (opponent != null) {
			player.tell(opponent.p(Technique.T1), opponent.p(Technique.T9), opponent.p(Technique.T4));
		}
	}

	/** The teacher's style: its family's techniques every time, no lag, no reaction time beyond a tick. */
	public static Style style(Technique[] family) {
		Style s = new Style();
		s.name = "teacher";
		for (Technique t : family) {
			s.set(t, 1f);
		}
		s.reaction = 1;
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

	public void tick(Duel d, Events e) {
		player.tick(d, e, keys);
	}

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
		if (keys.technique == Technique.BASE || !teaches[keys.technique.ordinal()] || keys.taught == 0) {
			return 0;
		}
		int heads = 0;
		if ((keys.taught & Keys.KEY_MOVE) != 0) {
			heads |= put(target, ActionSpace.FORWARD, keys.forward > 0 ? 1 : keys.forward < 0 ? 2 : 0);
		}
		if ((keys.taught & Keys.KEY_STRAFE) != 0) {
			heads |= put(target, ActionSpace.STRAFE, keys.strafe < 0 ? 1 : keys.strafe > 0 ? 2 : 0);
		}
		// The sprint key does nothing without W: taught only while the teacher holds W.
		if ((keys.taught & Keys.KEY_SPRINT) != 0 && keys.forward > 0) {
			heads |= put(target, ActionSpace.SPRINT, keys.sprint ? 1 : 0);
		}
		if ((keys.taught & Keys.KEY_JUMP) != 0) {
			heads |= put(target, ActionSpace.JUMP, keys.jump ? 1 : 0);
		}
		if ((keys.taught & Keys.KEY_ATTACK) != 0) {
			heads |= put(target, ActionSpace.ATTACK, keys.attack ? 1 : 0);
		}
		return heads;
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
