package dev.swordai.core.rl;

/**
 * When the opponent has gone off the screen, the camera is not left to the network: it turns, at
 * the hand's full speed, to the side the opponent was last seen on, and comes level (an opponent on
 * the same ground is found at eye height), until the opponent is on screen again; then for a few
 * ticks more it is brought onto the opponent, so that what is found is not lost again at once. The
 * turn itself is {@link AimRule}'s, which did the same only in the ticks after a hit.
 *
 * <p>Why it is a rule. The user, 18 October: when it loses its aim, above all with the opponent
 * gone round behind it, "it doesn't know to spin 180 degrees to find my opponent", or it looks at
 * the ground and spins. A network that sees only the screen has nothing on it to go by once the
 * opponent has left it, and it had learned no search worth the name: in training the opponent is
 * almost never off the screen for long.
 *
 * <p>Not thread-safe; one per fighter.
 */
public final class LostRule {
	/** Off the screen for this many ticks in a row, the opponent is lost. */
	static final int LOST_FROM = 3;
	/** Found again, the camera is brought onto it for this many ticks more. */
	static final int SETTLE_TICKS = 4;

	private int unseen;
	private int settle;

	public void reset() {
		unseen = 0;
		settle = 0;
	}

	/** One tick: whether the camera is this rule's on it. */
	public boolean applies(float[] vec) {
		boolean seen = vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
		if (!seen) {
			unseen++;
			settle = 0;
			return unseen >= LOST_FROM;
		}
		if (unseen >= LOST_FROM) {
			settle = SETTLE_TICKS;
		}
		unseen = 0;
		if (settle > 0) {
			settle--;
			return true;
		}
		return false;
	}
}
