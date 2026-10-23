package dev.swordai.core.vision;

import dev.swordai.core.rl.ObsSpec;

/**
 * Camera maths in Minecraft's conventions: yaw 0 faces +Z and grows clockwise seen from above,
 * positive pitch looks down. Screen coordinates are in units of half the frame height, x to the
 * right and y up, which is what {@link MaskExtractor} reports.
 */
public final class ViewGeometry {
	/** The narrowest and widest field of view the game itself offers. */
	public static final float MIN_FOV_DEG = 30f;
	public static final float MAX_FOV_DEG = 110f;

	private static volatile float fovDeg = ObsSpec.FOV_DEG;
	private static volatile float tanHalfFov = (float) Math.tan(Math.toRadians(ObsSpec.FOV_DEG * 0.5));

	private ViewGeometry() {
	}

	/**
	 * Sets the vertical field of view of the picture the network gets. One session runs at a time and
	 * sets this before it starts; everything that turns screen positions into angles reads it.
	 */
	public static void setFov(float degrees) {
		float d = Math.max(MIN_FOV_DEG, Math.min(MAX_FOV_DEG, degrees));
		fovDeg = d;
		tanHalfFov = (float) Math.tan(Math.toRadians(d * 0.5));
	}

	public static float fov() {
		return fovDeg;
	}

	/** Half the frame height, measured at distance one. */
	public static float tanHalfFov() {
		return tanHalfFov;
	}

	/**
	 * How much of a frame drawn with a wider field of view is the picture of a narrower one: the
	 * middle part, this fraction of its height. One when they are the same.
	 */
	public static float cropFor(float pictureFovDeg, float screenFovDeg) {
		if (pictureFovDeg >= screenFovDeg) {
			return 1f;
		}
		return (float) (Math.tan(Math.toRadians(pictureFovDeg * 0.5)) / Math.tan(Math.toRadians(screenFovDeg * 0.5)));
	}

	public static float wrapDegrees(float deg) {
		float d = deg % 360f;
		if (d >= 180f) {
			d -= 360f;
		}
		if (d < -180f) {
			d += 360f;
		}
		return d;
	}

	/** Yaw that looks along the horizontal direction (dx, dz). */
	public static float yawTo(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/** Pitch that looks along (dx, dy, dz). */
	public static float pitchTo(double dx, double dy, double dz) {
		return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
	}

	/**
	 * Rotates a world-space vector into the view frame of a camera with the given yaw and pitch.
	 *
	 * @param out receives {right, up, forward}
	 */
	public static void toView(double x, double y, double z, float yawDeg, float pitchDeg, float[] out) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double sy = Math.sin(yaw);
		double cy = Math.cos(yaw);
		double sp = Math.sin(pitch);
		double cp = Math.cos(pitch);
		// forward = (-sy*cp, -sp, cy*cp), right = (-cy, 0, -sy), up = right x forward
		double right = -cy * x - sy * z;
		double forward = -sy * cp * x - sp * y + cy * cp * z;
		double up = -sy * sp * x + cp * y + cy * sp * z;
		out[0] = (float) right;
		out[1] = (float) up;
		out[2] = (float) forward;
	}

	/** Horizontal-only version of {@link #toView}: ignores pitch. {@code out} receives {right, up, forward}. */
	public static void toYawFrame(double x, double y, double z, float yawDeg, float[] out) {
		toView(x, y, z, yawDeg, 0f, out);
	}

	/** Screen x of a view-space point, or NaN when it is behind the camera. */
	public static float screenX(float right, float forward) {
		return forward <= 1e-4f ? Float.NaN : right / forward / tanHalfFov;
	}

	public static float screenY(float up, float forward) {
		return forward <= 1e-4f ? Float.NaN : up / forward / tanHalfFov;
	}

	/**
	 * Where a point that was seen at screen position (x, y) by a camera looking along
	 * (yawThen, pitchThen) appears now that the camera looks along (yawNow, pitchNow).
	 *
	 * @param out receives {x, y}
	 * @return false when the point is now behind the camera ({@code out} is then left untouched)
	 */
	public static boolean reproject(float x, float y, float yawThen, float pitchThen, float yawNow, float pitchNow, float[] out) {
		// Direction of the point in the old view frame (right, up, forward), then out to the world.
		double r = x * tanHalfFov;
		double u = y * tanHalfFov;
		double yaw = Math.toRadians(yawThen);
		double pitch = Math.toRadians(pitchThen);
		double sy = Math.sin(yaw);
		double cy = Math.cos(yaw);
		double sp = Math.sin(pitch);
		double cp = Math.cos(pitch);
		// world = right * r + up * u + forward, with the same basis vectors toView() projects onto.
		double wx = -cy * r - sy * sp * u - sy * cp;
		double wy = cp * u - sp;
		double wz = -sy * r + cy * sp * u + cy * cp;
		float[] v = new float[3];
		toView(wx, wy, wz, yawNow, pitchNow, v);
		if (v[2] <= 1e-4f) {
			return false;
		}
		out[0] = v[0] / v[2] / tanHalfFov;
		out[1] = v[1] / v[2] / tanHalfFov;
		return true;
	}

	/** On-screen size, in half-frame-heights, of something {@code size} blocks across at {@code distance}. */
	public static float apparentSize(float size, float distance) {
		return size / distance / tanHalfFov;
	}

	/** Angle in degrees between the view direction of (yaw, pitch) and the vector (dx, dy, dz). */
	public static float angleTo(double dx, double dy, double dz, float yawDeg, float pitchDeg) {
		float[] v = new float[3];
		toView(dx, dy, dz, yawDeg, pitchDeg, v);
		double len = Math.sqrt((double) v[0] * v[0] + (double) v[1] * v[1] + (double) v[2] * v[2]);
		if (len < 1e-9) {
			return 0f;
		}
		double c = Math.max(-1.0, Math.min(1.0, v[2] / len));
		return (float) Math.toDegrees(Math.acos(c));
	}
}
