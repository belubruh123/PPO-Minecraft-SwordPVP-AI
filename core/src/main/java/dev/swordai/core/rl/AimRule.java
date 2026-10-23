package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.ViewGeometry;

/**
 * The camera is brought onto the opponent for the fighter in the ticks after it has been hit, for
 * as long as it has not hit back: the ticks in which it is thrown into the air and comes down
 * again, which is where the swing it owes is made (see {@link AnswerRule}), as a critical hit if
 * the opponent is still in reach.
 *
 * <p>Why it is a rule. That swing is only made with the opponent under the crosshair, and after a
 * hit it mostly was not: of the falls after a hit in which the opponent stayed within a sword's
 * reach, half went by without the crosshair on it once. The hit moves the fighter, an opponent that
 * goes round it has moved on, and one that hit it from the side or from behind was never in the
 * picture. So for those ticks the turn is not left to the network: with the opponent on screen the
 * camera goes to the middle of it, as fast as the hand ({@link AimMotor}) gets there without going
 * past; with nothing on screen it turns, at the hand's full speed, to the side the opponent was last
 * seen on, or, never having seen it, away from the way the hit threw the fighter, which is where
 * the hit came from.
 *
 * <p>The same is done in the air of a jump that is made for it ({@link JumpIn}): the hit at the end
 * of a running jump needs the crosshair on an opponent that is itself running, and one such jump in
 * six came down with that opponent in reach and not under it. And, more gently, on an opponent that
 * is far away ({@link FarRest}).
 *
 * <p>It goes by what the network is shown: where the opponent is on screen, and the fighter's own body.
 *
 * <p>Not thread-safe; one per fighter.
 */
public final class AimRule {
	/** For this many ticks after a hit, if it has not hit back by then. */
	public static final int TICKS = 16;
	/** The share of what is left to turn that the camera covers on a tick: geometric, so it does not go past. */
	static final float GAIN = 0.6f;
	/** Nearer than this, in degrees, it is on: the camera comes to rest. */
	static final float ON_DEG = 0.4f;
	/**
	 * On an opponent that is far away a hand at rest stays at rest until the crosshair is this far
	 * off it, in degrees: about the opponent's own width at twenty blocks to either side. There is
	 * nothing to hit from there, and a camera that follows every step the opponent takes is a camera
	 * that never stops moving.
	 */
	static final float FAR_START_DEG = 2.5f;
	/** Thrown sideways faster than this (blocks a tick), the hit came from the other side. */
	static final float SIDE_SPEED = 0.04f;
	/** With {@link #level}: the aim stays this share of the opponent's height inside its top and bottom. */
	static final float LEVEL_MARGIN = 0.1f;

	private final float response;
	/** With nothing on screen and never anything seen: the way it has decided to turn; 0 until it has. */
	private int blindDir;
	private int yawBin;
	private int pitchBin;
	/** See {@link #level}. */
	private boolean level;

	/** @param response the hand's response; see {@link AimMotor} */
	public AimRule(float response) {
		this.response = Math.max(0.05f, Math.min(1f, response));
	}

	public void reset() {
		blindDir = 0;
	}

	/**
	 * Whether the camera is brought level with the fighter's own eye where the opponent's body spans that height, and
	 * onto the nearest part of it where it does not, instead of onto the middle of it; see {@code Config.aimLevel}. The
	 * game's reach is measured along the crosshair: from the eye down to the middle of an opponent on the same ground at
	 * the edge of the reach that ray is some 0.07 blocks longer than the level one, which is the margin by which the
	 * scripted opponents, aiming level, out-reached the fighter in the traces of 22 October.
	 */
	public void level(boolean on) {
		level = on;
	}

	/** Whether the camera is the rule's on this tick: hit not long ago, and not hit back since. */
	public static boolean applies(float[] vec) {
		return AnswerRule.owesAnAnswer(vec)
				&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] >= 1f - TICKS / BodyState.EVENT_FADE_TICKS;
	}

	/**
	 * Works out this tick's turn; {@link #yawBin} and {@link #pitchBin} then give the choices of the two heads.
	 *
	 * @param yawPush   the speed the hand is carrying on each axis, degrees a tick; see {@link AimMotor#yawPush}
	 */
	public void plan(float[] vec, float yawPush, float pitchPush) {
		plan(vec, yawPush, pitchPush, false);
	}

	/**
	 * @param far the opponent is far away (see {@link FarRest}): the hand stays at rest until the
	 *            crosshair is well off it, and does not run ahead of where the opponent is going
	 */
	public void plan(float[] vec, float yawPush, float pitchPush, boolean far) {
		int at = ObsSpec.VEC_STATS;
		if (vec[at + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
			blindDir = 0;
			float yawErr = degrees(vec[at + ObsSpec.ST_ANY_X]);
			// The picture's y is up, the pitch is down.
			float pitchErr = -degrees(vec[at + ObsSpec.ST_ANY_Y]);
			float middleErr = pitchErr;
			if (level && !far) {
				// Level with its own eye (the horizon is as far above the crosshair as the camera looks down), kept within the opponent.
				float top = degrees(vec[at + ObsSpec.ST_TOP]);
				float bottom = degrees(vec[at + ObsSpec.ST_BOTTOM]);
				float margin = LEVEL_MARGIN * Math.max(0f, top - bottom);
				float horizon = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_PITCH] * 90f;
				pitchErr = -Math.max(bottom + margin, Math.min(top - margin, horizon));
			}
			if (far) {
				boolean yawResting = Math.abs(yawPush) < AimMotor.REST_BELOW;
				boolean pitchResting = Math.abs(pitchPush) < AimMotor.REST_BELOW;
				yawBin = nearest(ActionSpace.YAW_BINS, yawResting && Math.abs(yawErr) < FAR_START_DEG ? 0f : wanted(yawErr, yawPush));
				pitchBin = nearest(ActionSpace.PITCH_BINS, pitchResting && Math.abs(pitchErr) < FAR_START_DEG ? 0f : wanted(pitchErr, pitchPush));
				return;
			}
			int before = at + ObsSpec.STATS;
			if (vec[before + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
				// Where it will be a tick on, if it goes on across the picture as it has.
				yawErr += clamp(yawErr - degrees(vec[before + ObsSpec.ST_ANY_X]), 12f);
				pitchErr += clamp(middleErr + degrees(vec[before + ObsSpec.ST_ANY_Y]), 6f);
			}
			yawBin = nearest(ActionSpace.YAW_BINS, wanted(yawErr, yawPush));
			pitchBin = nearest(ActionSpace.PITCH_BINS, wanted(pitchErr, pitchPush));
			return;
		}
		// Nothing on screen: round to where it was last seen, or to where the hit came from.
		int side = Math.round(vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE]);
		if (side == 0) {
			if (blindDir == 0) {
				float right = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_RIGHT] / BodyState.VELOCITY_SCALE;
				if (Math.abs(right) >= SIDE_SPEED) {
					// Thrown to the right: it was hit from the left.
					blindDir = right > 0f ? -1 : 1;
				}
			}
			side = blindDir != 0 ? blindDir : 1;
		}
		yawBin = side > 0 ? ActionSpace.YAW_BINS.length - 1 : 0;
		// And level: an opponent on the same ground is found at eye height.
		float pitchNow = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_PITCH] * 90f;
		pitchBin = nearest(ActionSpace.PITCH_BINS, wanted(-pitchNow, pitchPush));
	}

	public int yawBin() {
		return yawBin;
	}

	public int pitchBin() {
		return pitchBin;
	}

	/** The speed to ask the hand for, so that on the next tick the camera covers {@link #GAIN} of what is left. */
	private float wanted(float errDeg, float push) {
		if (Math.abs(errDeg) < ON_DEG) {
			// On it: ask for nothing, and the hand comes to rest.
			return 0f;
		}
		return push + (GAIN * errDeg - push) / response;
	}

	/** The angle from the crosshair of a point this far from it in the picture, in half heights. */
	private static float degrees(float halfHeights) {
		float tanHalfFov = 1f / ViewGeometry.apparentSize(1f, 1f);
		return (float) Math.toDegrees(Math.atan(halfHeights * tanHalfFov));
	}

	private static float clamp(float v, float most) {
		return Math.max(-most, Math.min(most, v));
	}

	static int nearest(float[] bins, float wanted) {
		int best = 0;
		for (int i = 1; i < bins.length; i++) {
			if (Math.abs(bins[i] - wanted) < Math.abs(bins[best] - wanted)) {
				best = i;
			}
		}
		return best;
	}
}
