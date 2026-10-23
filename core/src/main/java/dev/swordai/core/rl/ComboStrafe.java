package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * Out of the line of a combo, to the side: from the second hit in a row it is taking, A or D is held for {@link #TICKS}
 * ticks after each such hit, away from the way the opponent is moving across its view (or the other way from the last
 * time, when it is not moving across). An opponent that has it in a combo comes after it in a straight line, sprint hit
 * after sprint hit; one that has gone to the side has to turn to follow, and its next hit comes later, from an angle, or
 * not at all, and the fighter comes in again from there. With the jump reset ({@link JumpReset}) on the tick of the hit,
 * it is a jump out to the side.
 *
 * <p>The user, 23 October, watching it lose rounds to combos: it "don't know how to run and run to left or right to make
 * its combo hard to continue and go back in". From its fight traces: from the second hit of a combo on, it held W in 82
 * to 97 ticks in 100 (the jump reset), and the combos of three hits and more came from the whiffer, the one that jumps in
 * for crits and the trader. See {@code Config.comboStrafeRule}.
 *
 * <p>Not thread-safe; one per fighter.
 */
public final class ComboStrafe {
	/** A or D held this long after each hit of a combo: half a second. */
	public static final int TICKS = 10;
	/** The opponent moving across at this much a tick or more, blocks: it is going that way, and the fighter goes the other. */
	static final float ACROSS = 0.04f;
	/** The strafe head's values: A, D. */
	static final int LEFT = 1;
	static final int RIGHT = 2;

	private int left;
	private int side = RIGHT;
	private int last = RIGHT;

	/**
	 * Once a decision.
	 *
	 * @return the strafe key to hold ({@code ActionSpace.STRAFE}: 1 A, 2 D), or 0 to leave it to the network
	 */
	public int plan(float[] vec) {
		if (JumpReset.hitOnThisTick(vec) && BackNudge.inCombo(vec)) {
			float across = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_RIGHT] / BodyState.VELOCITY_SCALE;
			side = across >= ACROSS ? LEFT : across <= -ACROSS ? RIGHT : last == LEFT ? RIGHT : LEFT;
			last = side;
			left = TICKS;
		} else if (hitDealtNow(vec)) {
			// Its own hit landed: the combo is over, and the fight goes on as the network has it.
			left = 0;
		}
		if (left <= 0) {
			return 0;
		}
		left--;
		return side;
	}

	public boolean active() {
		return left > 0;
	}

	public void reset() {
		left = 0;
	}

	/** A hit of its own on this very tick, as it is shown: the fresh end of its fading clock. */
	static boolean hitDealtNow(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] >= 1f - 0.5f / BodyState.EVENT_FADE_TICKS;
	}
}
