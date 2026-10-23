package dev.swordai.core.rl;

import dev.swordai.core.vision.ViewGeometry;

import java.util.Arrays;

/**
 * Turns the mask statistics of the newest frame into what the agent is told about the opponent's
 * position, adding two things a player has without thinking about them:
 *
 * <ul>
 *   <li><b>Knowing its own head movement.</b> A frame is always a little old. If the camera has
 *   turned since it was taken, the positions measured on it are shifted to where they are now,
 *   using nothing but the agent's own view angles. Without this the agent corrects twice for an
 *   error it has already fixed, swings past the target and wobbles around it.</li>
 *   <li><b>Remembering where the opponent went.</b> Which side it was last seen on and how long
 *   ago, so there is something to act on once it has left the screen.</li>
 * </ul>
 *
 * Everything here comes from pixels and the agent's own camera; no ground truth is involved.
 */
public final class SightTracker {
	/** Offsets smaller than this do not change the remembered side (the opponent is dead centre). */
	private static final float SIDE_DEADBAND = 0.03f;
	/** "How long ago" saturates after this many ticks. */
	private static final float SINCE_SEEN_TICKS = 40f;
	/** Turned-since values are scaled so a typical correction is around one. */
	private static final float TURN_SCALE_DEG = 20f;
	private static final float LATE_EPSILON_DEG = 0.01f;

	private final float[] current = new float[ObsSpec.STATS];
	private final float[] previous = new float[ObsSpec.STATS];
	private final float[] lastRaw = new float[ObsSpec.STATS];
	private final float[] point = new float[2];
	private float lastRawYaw;
	private float lastRawPitch;
	private boolean hasLast;

	private float turnedYaw;
	private float turnedPitch;
	private int lastSeenSide;
	private int lastSeenUp;
	private int ticksSinceSeen = Integer.MAX_VALUE / 2;

	/** Start of an episode: no previous frame, no memory. */
	public void reset() {
		hasLast = false;
		lastSeenSide = 0;
		lastSeenUp = 0;
		ticksSinceSeen = Integer.MAX_VALUE / 2;
		Arrays.fill(current, 0f);
		Arrays.fill(previous, 0f);
	}

	/**
	 * Feeds one decision's worth of vision.
	 *
	 * @param raw        statistics measured on the newest frame
	 * @param frameYaw   where the camera pointed when that frame was rendered
	 * @param yawNow     where the camera points at this moment
	 */
	public void update(float[] raw, float frameYaw, float framePitch, float yawNow, float pitchNow) {
		turnedYaw = ViewGeometry.wrapDegrees(yawNow - frameYaw);
		turnedPitch = pitchNow - framePitch;

		shift(raw, frameYaw, framePitch, yawNow, pitchNow, current);
		if (hasLast) {
			// The frame before, expressed in today's view too, so the difference is the opponent's motion alone.
			shift(lastRaw, lastRawYaw, lastRawPitch, yawNow, pitchNow, previous);
		} else {
			System.arraycopy(current, 0, previous, 0, ObsSpec.STATS);
		}
		System.arraycopy(raw, 0, lastRaw, 0, ObsSpec.STATS);
		lastRawYaw = frameYaw;
		lastRawPitch = framePitch;
		hasLast = true;

		if (current[ObsSpec.ST_ANY_VISIBLE] > 0f) {
			ticksSinceSeen = 0;
			float x = current[ObsSpec.ST_ANY_X];
			float y = current[ObsSpec.ST_ANY_Y];
			if (Math.abs(x) > SIDE_DEADBAND) {
				lastSeenSide = x > 0f ? 1 : -1;
			}
			if (Math.abs(y) > SIDE_DEADBAND) {
				lastSeenUp = y > 0f ? 1 : -1;
			}
		} else if (ticksSinceSeen < Integer.MAX_VALUE / 2) {
			ticksSinceSeen++;
		}
	}

	/** Copies {@code raw} to {@code out} with every position moved to where it is in the current view. */
	private void shift(float[] raw, float yawThen, float pitchThen, float yawNow, float pitchNow, float[] out) {
		System.arraycopy(raw, 0, out, 0, ObsSpec.STATS);
		boolean moved = Math.abs(ViewGeometry.wrapDegrees(yawNow - yawThen)) > LATE_EPSILON_DEG
				|| Math.abs(pitchNow - pitchThen) > LATE_EPSILON_DEG;
		if (!moved) {
			return;
		}
		if (raw[ObsSpec.ST_HEAD_VISIBLE] > 0f) {
			if (ViewGeometry.reproject(raw[ObsSpec.ST_HEAD_X], raw[ObsSpec.ST_HEAD_Y], yawThen, pitchThen, yawNow, pitchNow, point)) {
				out[ObsSpec.ST_HEAD_X] = point[0];
				out[ObsSpec.ST_HEAD_Y] = point[1];
				ObsSpec.deriveStats(out);
			} else {
				clearHead(out);
			}
		}
		if (raw[ObsSpec.ST_ANY_VISIBLE] > 0f) {
			if (ViewGeometry.reproject(raw[ObsSpec.ST_ANY_X], raw[ObsSpec.ST_ANY_Y], yawThen, pitchThen, yawNow, pitchNow, point)) {
				float dx = point[0] - raw[ObsSpec.ST_ANY_X];
				float dy = point[1] - raw[ObsSpec.ST_ANY_Y];
				out[ObsSpec.ST_ANY_X] = point[0];
				out[ObsSpec.ST_ANY_Y] = point[1];
				out[ObsSpec.ST_LEFT] += dx;
				out[ObsSpec.ST_RIGHT] += dx;
				out[ObsSpec.ST_TOP] += dy;
				out[ObsSpec.ST_BOTTOM] += dy;
			} else {
				Arrays.fill(out, 0f);
			}
		}
	}

	private static void clearHead(float[] s) {
		s[ObsSpec.ST_HEAD_VISIBLE] = 0f;
		s[ObsSpec.ST_HEAD_X] = 0f;
		s[ObsSpec.ST_HEAD_Y] = 0f;
		s[ObsSpec.ST_HEAD_AREA] = 0f;
		ObsSpec.deriveStats(s);
	}

	/** Writes the statistics of this and the previous frame and the tracker's scalars into an observation vector. */
	public void write(float[] vec) {
		ObsBuilder.writeStats(vec, current, previous);
		ObsBuilder.writeScalar(vec, ObsSpec.SC_TURNED_YAW, clamp(turnedYaw / TURN_SCALE_DEG));
		ObsBuilder.writeScalar(vec, ObsSpec.SC_TURNED_PITCH, clamp(turnedPitch / TURN_SCALE_DEG));
		ObsBuilder.writeScalar(vec, ObsSpec.SC_LAST_SEEN_SIDE, lastSeenSide);
		ObsBuilder.writeScalar(vec, ObsSpec.SC_LAST_SEEN_UP, lastSeenUp);
		ObsBuilder.writeScalar(vec, ObsSpec.SC_SINCE_SEEN, Math.min(1f, ticksSinceSeen / SINCE_SEEN_TICKS));
	}

	/** True when the camera has moved since the frame in use was taken. */
	public boolean frameIsLate() {
		return Math.abs(turnedYaw) > LATE_EPSILON_DEG || Math.abs(turnedPitch) > LATE_EPSILON_DEG;
	}

	public int lastSeenSide() {
		return lastSeenSide;
	}

	public int ticksSinceSeen() {
		return ticksSinceSeen;
	}

	/** Statistics of the newest frame as the agent is told them (shifted to the current view). */
	public float[] current() {
		return current;
	}

	private static float clamp(float v) {
		return Math.max(-2f, Math.min(2f, v));
	}
}
