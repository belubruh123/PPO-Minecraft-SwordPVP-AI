package dev.swordai.core.rl;

/**
 * A steady hand on an opponent that is far away. With the opponent on screen and a long way off,
 * the camera is not the network's to move: it is brought onto the opponent and kept there by
 * {@link AimRule}'s plain steering, which turns towards it by a share of what is left on every
 * tick, follows it smoothly while it walks across the picture, and rests while it does not.
 *
 * <p>Why it is a rule. The hand model ({@link AimMotor}) starts a turn at a degree a tick and no
 * less, so the smallest correction it can make from rest is a good degree. Twenty blocks away the
 * whole opponent is a degree and a half wide. The network, paid for a crosshair on the opponent,
 * went on correcting there, each correction as large as the target, one way and then the other:
 * the picture shook. Nothing can be hit from there; what a player does is keep the opponent about
 * in the middle of the screen with a quiet hand.
 *
 * <p>It goes by what the network itself is shown: how tall the opponent is on screen, which says
 * how far away it is, and where it is from the crosshair.
 */
public final class FarRest {
	/** Farther away than this, as {@link JumpIn#distance} reads it. */
	public static final float FROM = 9f;

	private FarRest() {
	}

	/** Whether the camera is steered for it on this tick. */
	public static boolean applies(float[] vec) {
		return vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f && JumpIn.distance(vec) >= FROM && JumpIn.distance(vec) < Float.MAX_VALUE;
	}
}
