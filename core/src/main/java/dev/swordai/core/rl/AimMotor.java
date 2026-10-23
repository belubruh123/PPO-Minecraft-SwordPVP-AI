package dev.swordai.core.rl;

/**
 * How a chosen camera speed becomes camera movement, modelled on a hand on a mouse.
 *
 * <p>The yaw and pitch heads pick the speed the agent wants. The hand takes that up gradually: each
 * tick it closes only part of the gap between the speed it has and the speed that is wanted. A turn
 * therefore builds up and dies away over a few ticks instead of jumping, and one odd choice moves
 * the view only part of the way.
 *
 * <p>A hand at rest also stays at rest. It only gets going once the built-up push reaches
 * {@link #BREAKAWAY}, so a real turn starts at once while a single small impulse does nothing, and
 * it comes to a dead stop as soon as the push has faded below {@link #REST_BELOW}. Holding still is
 * something the agent can do exactly, not a balance it has to keep.
 */
public final class AimMotor {
	/** A camera at rest starts moving once the push reaches this speed (degrees per tick). */
	public static final float BREAKAWAY = 1f;
	/** A moving camera whose push has faded below this speed stops dead. */
	public static final float REST_BELOW = 0.25f;
	/** Yaw speed that fills the turn-rate input. */
	private static final float YAW_SCALE = 40f;
	private static final float PITCH_SCALE = 18f;

	private final float response;
	private final Axis yaw = new Axis();
	private final Axis pitch = new Axis();

	/** @param response share of the gap between current and wanted speed closed per tick; 1 is instant */
	public AimMotor(float response) {
		this.response = Math.max(0.05f, Math.min(1f, response));
	}

	public void reset() {
		yaw.reset();
		pitch.reset();
	}

	/** Advances one tick towards the wanted speeds; the rates are then this tick's camera movement. */
	public void step(float wantedYaw, float wantedPitch) {
		yaw.step(wantedYaw);
		pitch.step(wantedPitch);
	}

	/** Degrees per tick; positive turns right. */
	public float yawRate() {
		return yaw.rate();
	}

	/** Degrees per tick; positive looks down. */
	public float pitchRate() {
		return pitch.rate();
	}

	/** The speed the hand is carrying on the yaw axis, moving or not yet; degrees per tick. */
	public float yawPush() {
		return yaw.push;
	}

	public float pitchPush() {
		return pitch.push;
	}

	/** What the hand feels of itself: the push it has built up on each axis. */
	public void write(float[] vec) {
		ObsBuilder.writeScalar(vec, ObsSpec.SC_TURN_RATE_YAW, compress(yaw.push / YAW_SCALE));
		ObsBuilder.writeScalar(vec, ObsSpec.SC_TURN_RATE_PITCH, compress(pitch.push / PITCH_SCALE));
	}

	/** Signed square root: keeps a push of a tenth of a degree as readable as a full flick. */
	static float compress(float x) {
		float a = Math.min(1.5f, Math.abs(x));
		return (float) Math.copySign(Math.sqrt(a), x);
	}

	private final class Axis {
		/** The speed the hand is pushing for; it keeps building while the camera is still at rest. */
		float push;
		boolean moving;

		void reset() {
			push = 0f;
			moving = false;
		}

		void step(float wanted) {
			if (response >= 1f) {
				push = wanted;
				moving = wanted != 0f;
				return;
			}
			push += response * (wanted - push);
			float size = Math.abs(push);
			if (moving ? size < REST_BELOW : size >= BREAKAWAY) {
				moving = !moving;
			}
			if (!moving && size < 0.01f) {
				push = 0f;
			}
		}

		float rate() {
			return moving ? push : 0f;
		}
	}
}
