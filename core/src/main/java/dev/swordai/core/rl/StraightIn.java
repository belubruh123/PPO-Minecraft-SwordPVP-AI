package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * With the opponent in view and out of reach, the strafe keys are let go of: it closes the distance
 * running straight at the opponent. Going round someone is footwork for the range swords meet at,
 * and there the strafe stays the network's.
 *
 * <p>Why it is a rule. The user, 18 October: it strafes while it moves in to get close; "just walk
 * straight and only strafe when it is combo". An opponent that backed off in the middle of its
 * combo was gone round in a circle to be got at, which is slower than running at it and looks
 * wrong: the strafe plan ({@link MixIdea}) was handed out, and paid for, within five blocks.
 *
 * <p>Not in the ticks after a hit taken: a step to the side then is part of getting out of a combo.
 * Nor with the opponent cut off by the edge of the screen, where its height on it says it is farther
 * off than it is.
 */
public final class StraightIn {
	/** Farther than this from the opponent's front (reach is three blocks from the eye), it runs straight. */
	public static final float OUT = 3.6f;
	/** For this many ticks after a hit taken the strafe stays the network's. */
	static final int NOT_AFTER_HIT_TICKS = 10;
	/** The opponent's box reaching this close to the top or bottom of the screen is cut off by it. */
	static final float EDGE = 0.97f;

	private StraightIn() {
	}

	/** Whether the strafe keys are this rule's on this tick (and let go of). */
	public static boolean applies(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f) {
			return false;
		}
		if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] > 1f - NOT_AFTER_HIT_TICKS / BodyState.EVENT_FADE_TICKS) {
			return false;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] >= EDGE || vec[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] <= -EDGE) {
			return false;
		}
		return JumpIn.distance(vec) > OUT;
	}
}
