package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * What it does about an opponent that is coming at it through the air: with its sword back and its
 * feet on the ground, it runs in at that opponent while it is still going up, and swings the moment
 * the game has it under the crosshair.
 *
 * <p>In the air it is W and the sprint key alone (the swing there has its own rule): thrown back by
 * a hit, the way towards the opponent begins before it is down, and every tick of it counts.
 *
 * <p>Why. A critical hit can only be made on the way down. A player who jumps at the fighter for
 * one is, for the six ticks of the way up, a player who cannot hurt it as he means to and cannot
 * change where he is going. Left alone he comes down in reach and makes his hit. Met on the way up
 * he is hit first, with a sprint behind it, and thrown back out of his jump: there is no critical
 * hit, and the next hit is the fighter's. Standing still, or backing off, the fighter is only in
 * reach of him when he is already coming down.
 *
 * <p>It overrides the swing being kept for the fighter's own way down ({@link AnswerRule}): that
 * is for an opponent that is already there, and this one is not there yet.
 *
 * <p>Read from what the network is shown. No state.
 */
public final class MeetJump {
	/** Going up at this much a tick or more: the first five ticks of a jump. */
	static final float RISING = 0.05f;
	/** And the two are closing on each other by this much a tick or more. */
	static final float CLOSING = 0.1f;
	/** Or the opponent itself is coming at it by this much a tick, whatever the fighter is doing. */
	static final float COMING = 0.08f;
	/** No farther off than a running jump carries, as {@link JumpIn#distance} reads it. */
	static final float WITHIN = 6f;
	/** The sword back, or back by the time the opponent is in reach: three or four ticks from it. */
	static final float CHARGE = 0.7f;

	/** It gets out of the way of a jump made at it from no farther off than this. */
	static final float DODGE_WITHIN = 4.5f;

	private MeetJump() {
	}

	/** Whether this is a tick to run in at an opponent that is on its way up and coming. */
	public static boolean applies(float[] vec) {
		return applies(vec, false);
	}

	/**
	 * @param coming also when only the opponent is coming ({@link #COMING}): the fighter carried back by
	 *               a hit it took a moment ago is not closing, and that is when a jump-crit comes in
	 */
	public static boolean applies(float[] vec, boolean coming) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {
			return false;
		}
		// The opponent's own speed up or down: what the fighter sees of it, less its own.
		float up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		return up >= RISING && approaching(vec, coming) && JumpIn.distance(vec) <= WITHIN;
	}

	/**
	 * Whether this is a tick to get out of the way of an opponent coming at it through the air: on its way
	 * up, coming, near, and the fighter's sword not back, so that it cannot be met ({@link #applies}). S is
	 * held for it: a jump cannot change where it is going, and walking backwards for the six ticks of the
	 * way up keeps the fighter as far off as the jump carries the opponent in, so the critical hit comes
	 * down out of reach, or is not made. By the time the opponent can jump again the sword is back, and
	 * that jump is met.
	 *
	 * <p>That is spacing as the players who space it do it: out of reach while the other sword is ready
	 * and its own is not, in again once it is. Before 19 October nothing did this, and on half the jumps
	 * made at it its sword was not back: the crit landed, and the sprint hit after it.
	 */
	public static boolean dodges(float[] vec, boolean coming) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) >= CHARGE) {
			return false;
		}
		float up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		return up >= RISING && approaching(vec, coming) && JumpIn.distance(vec) <= DODGE_WITHIN;
	}

	/**
	 * Whether the opponent is jumping in at the fighter now (on its way up, coming, near enough to land
	 * on it): 0 not, 1 with the fighter's sword not back in time to meet it, 2 with it back. For the
	 * reward that teaches it to keep its sword for that moment ({@code RewardWeights.jumpInReady}).
	 */
	public static int jumpIn(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || JumpIn.distance(vec) > WITHIN) {
			return 0;
		}
		float up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		if (up < RISING || !approaching(vec, true)) {
			return 0;
		}
		return scalar(vec, ObsSpec.SC_ATTACK_CHARGE) >= CHARGE ? 2 : 1;
	}

	private static boolean approaching(float[] vec, boolean coming) {
		float closing = -scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) / BodyState.VELOCITY_SCALE;
		// The opponent's own speed towards the fighter: the closing, less the fighter's own part of it.
		float toward = -(scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
		return closing >= CLOSING || coming && toward >= COMING;
	}

	/**
	 * For the log: where this observation stops short of what {@link #applies} asks. 0: no opponent
	 * on its way up within range; 2: there is one, but its sword
	 * is not far enough back; 3: the two are not closing; 4: nowhere, which is {@link #applies}.
	 */
	public static int how(float[] vec) {
		return how(vec, false);
	}

	/** @param coming see {@link #applies(float[], boolean)} */
	public static int how(float[] vec, boolean coming) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || JumpIn.distance(vec) > WITHIN
				|| (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE < RISING) {
			return 0;
		}
		if (scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {
			return 2;
		}
		return approaching(vec, coming) ? 4 : 3;
	}

	private static float scalar(float[] vec, int index) {
		return vec[ObsSpec.VEC_SCALARS + index];
	}
}
