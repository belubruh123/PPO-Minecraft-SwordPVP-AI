package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * Spacing, the half of it that is about coming in: with the opponent just out of reach and its sword
 * ready, the fighter does not walk into that reach. It stands at the edge (W and the sprint key up) and
 * lets the opponent come to it, into its own sword; once the opponent's sword has been spent (a swing,
 * hit or miss) it goes in, and the crit back ({@link JumpIn#COUNTER}) is made from here.
 *
 * <p>Why. Against a player who stands at the edge of the reach, whoever steps into it first on a ready
 * sword is hit first, and the fighter sees everything a few ticks late. On 19 October it walked into
 * reach on a ready sword twice as often as on one coming back, and the players who space it (the
 * spacer, the timer, the user's friend) crit it every time it did.
 *
 * <p>It does not wait for ever: an opponent that never swings first (one who only stands there) would
 * have it standing there too. After {@link #MOST_TICKS} of waiting with its own sword back it goes in
 * anyway, and is left to do so for {@link #FREE_TICKS}. Nor in its own combo (a hit of its own in the
 * last {@link #AFTER_HIT_DEALT_TICKS} ticks): an opponent that has just been hit is to be followed.
 *
 * <p>Read from what the network is shown. Not thread-safe; one per fighter.
 */
public final class EdgeHold {
	/** Nearer than this (from the opponent's front, as {@link JumpIn#distance} reads it) it is in reach already. */
	static final float FROM = 3.0f;
	/** And farther than this there is a way to go yet before the edge. */
	static final float TO = 4.2f;
	/** The opponent's sword counts as ready from here on ({@code StepFacts.OPP_READY}). */
	static final float OPP_READY = 0.9f;
	/** Its own sword counts as back from here on. */
	static final float OWN_READY = 0.9f;
	/** At most this long at the edge with its own sword back, then in anyway. */
	static final int MOST_TICKS = 20;
	/** And after that not held again for this long. */
	static final int FREE_TICKS = 30;
	/** Not within this many ticks of a hit of its own: its combo. */
	static final int AFTER_HIT_DEALT_TICKS = 15;
	/** The opponent's box reaching this close to the top or bottom of the screen is cut off by it. */
	static final float EDGE = 0.97f;

	private int waited;
	private int free;

	public void reset() {
		waited = 0;
		free = 0;
	}

	/** Whether W and the sprint key are this rule's on this tick (and let go of). Call once a tick. */
	public boolean plan(float[] vec) {
		if (free > 0) {
			free--;
			return false;
		}
		if (!holds(vec)) {
			waited = 0;
			return false;
		}
		if (scalar(vec, ObsSpec.SC_ATTACK_CHARGE) >= OWN_READY && ++waited > MOST_TICKS) {
			waited = 0;
			free = FREE_TICKS;
			return false;
		}
		return true;
	}

	/** Whether this observation is one to hold at the edge on, the time limit aside. */
	public static boolean holds(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f) {
			return false;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] >= EDGE || vec[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] <= -EDGE) {
			return false;
		}
		if (scalar(vec, ObsSpec.SC_OPP_CHARGE) < OPP_READY) {
			return false;
		}
		if (scalar(vec, ObsSpec.SC_SINCE_HIT_DEALT) > 1f - AFTER_HIT_DEALT_TICKS / BodyState.EVENT_FADE_TICKS) {
			return false;
		}
		float d = JumpIn.distance(vec);
		return d >= FROM && d <= TO;
	}

	private static float scalar(float[] vec, int index) {
		return vec[ObsSpec.VEC_SCALARS + index];
	}
}
