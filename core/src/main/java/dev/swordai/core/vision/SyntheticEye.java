package dev.swordai.core.vision;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Draws what a fighter would see of one opponent, for a body that has no screen (a fake player on
 * the server). The opponent is the game's own player model ({@link PlayerFigure}), posed and
 * animated the way the game would, in the key colours. The picture then goes through the same
 * {@link MaskExtractor} as a real frame, so the masks and statistics come out in exactly the form
 * the network was trained on.
 *
 * <p>What it does not draw: whatever the opponent holds, and anything in the world that could hide
 * it. Like the real picture it holds nothing but the opponent's shape on screen. Not thread-safe.
 */
public final class SyntheticEye {
	/** The resolution a real frame is sampled at (see {@code MaskExtractor}), 16:9. */
	public static final int WIDTH = 480;
	public static final int HEIGHT = 270;

	/** Closer than this to the eye a corner cannot be projected; the box is then tested everywhere. */
	private static final double NEAR = 0.05;

	private static final int BLUE = 0xFFFF0000;
	private static final int GREEN = 0xFF00FF00;

	private final byte[] pixels = new byte[WIDTH * HEIGHT * 4];
	private final ByteBuffer buffer = ByteBuffer.wrap(pixels);
	private final MaskExtractor extractor = new MaskExtractor();
	private final PlayerFigure figure = new PlayerFigure();
	private final PlayerPose standing = new PlayerPose();
	private final Box[] boxes = new Box[figure.parts.length];
	private long sequence;

	public SyntheticEye() {
		for (int i = 0; i < boxes.length; i++) {
			boxes[i] = new Box(figure.parts[i]);
		}
	}

	/**
	 * An opponent standing still.
	 *
	 * @param eyeX     the fighter's eye position, and the direction it looks in (Minecraft angles)
	 * @param feetX    the opponent's position (its feet)
	 * @param bodyYaw  the way the opponent's body faces
	 * @param headYaw  the way its head faces
	 * @param out      receives the masks, the statistics and the camera angles
	 */
	public void look(double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
			double feetX, double feetY, double feetZ, float bodyYaw, float headYaw, float headPitch, MaskFrame out) {
		look(eyeX, eyeY, eyeZ, yaw, pitch, standing.standing(feetX, feetY, feetZ, bodyYaw, headYaw, headPitch), out);
	}

	/**
	 * @param eyeX     the fighter's eye position, and the direction it looks in (Minecraft angles)
	 * @param opponent the opponent as it is to be drawn
	 * @param out      receives the masks, the statistics and the camera angles
	 */
	public void look(double eyeX, double eyeY, double eyeZ, float yaw, float pitch, PlayerPose opponent, MaskFrame out) {
		Arrays.fill(pixels, (byte) 0);

		double yr = Math.toRadians(yaw);
		double pr = Math.toRadians(pitch);
		double sy = Math.sin(yr);
		double cy = Math.cos(yr);
		double sp = Math.sin(pr);
		double cp = Math.cos(pr);
		// The same camera basis ViewGeometry.toView projects onto.
		double[] right = {-cy, 0, -sy};
		double[] up = {-sy * sp, cp, cy * sp};
		double[] forward = {-sy * cp, -sp, cy * cp};

		figure.pose(opponent, eyeX, eyeY, eyeZ);

		int minX = WIDTH;
		int maxX = -1;
		int minY = HEIGHT;
		int maxY = -1;
		for (Box b : boxes) {
			b.aim(right, up, forward);
			if (b.onScreen) {
				minX = Math.min(minX, b.minX);
				maxX = Math.max(maxX, b.maxX);
				minY = Math.min(minY, b.minY);
				maxY = Math.max(maxY, b.maxY);
			}
		}

		float halfH = HEIGHT * 0.5f;
		float halfW = WIDTH * 0.5f;
		for (int y = minY; y <= maxY; y++) {
			double v = (halfH - (y + 0.5f)) / halfH;
			for (int x = minX; x <= maxX; x++) {
				double u = (x + 0.5f - halfW) / halfH;
				double nearest = Double.MAX_VALUE;
				int colour = 0;
				for (Box b : boxes) {
					if (!b.onScreen || x < b.minX || x > b.maxX || y < b.minY || y > b.maxY) {
						continue;
					}
					double t = b.hit(u, v);
					if (t < nearest) {
						nearest = t;
						colour = b.part.isHead ? BLUE : GREEN;
					}
				}
				if (colour != 0) {
					int i = (y * WIDTH + x) * 4;
					// R, G, B, A in that order.
					pixels[i] = (byte) colour;
					pixels[i + 1] = (byte) (colour >>> 8);
					pixels[i + 2] = (byte) (colour >>> 16);
					pixels[i + 3] = (byte) 0xFF;
				}
			}
		}

		extractor.extract(buffer, WIDTH, HEIGHT, false, out);
		out.sequence = ++sequence;
		out.requestedNanos = System.nanoTime();
		out.viewYaw = yaw;
		out.viewPitch = pitch;
	}

	/** One box of the opponent, seen from the eye at the origin. */
	private static final class Box {
		final PlayerFigure.Part part;
		// The eye and the three camera axes in the box's own frame.
		private final double[] origin = new double[3];
		private final double[] rightL = new double[3];
		private final double[] upL = new double[3];
		private final double[] forwardL = new double[3];

		boolean onScreen;
		int minX;
		int maxX;
		int minY;
		int maxY;

		Box(PlayerFigure.Part part) {
			this.part = part;
		}

		/** A world direction in the box's own frame. */
		private void toLocal(double x, double y, double z, double[] out) {
			double[] a = part.axes;
			out[0] = a[0] * x + a[3] * y + a[6] * z;
			out[1] = a[1] * x + a[4] * y + a[7] * z;
			out[2] = a[2] * x + a[5] * y + a[8] * z;
		}

		/** Prepares the box for a camera and works out which pixels it can cover. */
		void aim(double[] right, double[] up, double[] forward) {
			double tan = ViewGeometry.tanHalfFov();
			double[] c = part.centre;
			double[] a = part.axes;
			double[] h = part.half;
			toLocal(-c[0], -c[1], -c[2], origin);
			toLocal(right[0] * tan, right[1] * tan, right[2] * tan, rightL);
			toLocal(up[0] * tan, up[1] * tan, up[2] * tan, upL);
			toLocal(forward[0], forward[1], forward[2], forwardL);

			double lo = Double.MAX_VALUE;
			double hi = -Double.MAX_VALUE;
			double left = Double.MAX_VALUE;
			double rightMost = -Double.MAX_VALUE;
			int behind = 0;
			for (int corner = 0; corner < 8; corner++) {
				double lx = (corner & 1) == 0 ? -h[0] : h[0];
				double ly = (corner & 2) == 0 ? -h[1] : h[1];
				double lz = (corner & 4) == 0 ? -h[2] : h[2];
				double wx = c[0] + a[0] * lx + a[1] * ly + a[2] * lz;
				double wy = c[1] + a[3] * lx + a[4] * ly + a[5] * lz;
				double wz = c[2] + a[6] * lx + a[7] * ly + a[8] * lz;
				double f = wx * forward[0] + wy * forward[1] + wz * forward[2];
				if (f < NEAR) {
					behind++;
					continue;
				}
				double sx = (wx * right[0] + wy * right[1] + wz * right[2]) / f / tan;
				double sy = (wx * up[0] + wy * up[1] + wz * up[2]) / f / tan;
				left = Math.min(left, sx);
				rightMost = Math.max(rightMost, sx);
				lo = Math.min(lo, sy);
				hi = Math.max(hi, sy);
			}
			if (behind == 8) {
				onScreen = false;
				return;
			}
			float halfH = HEIGHT * 0.5f;
			float halfW = WIDTH * 0.5f;
			if (behind > 0) {
				// Partly behind the eye: its outline cannot be bounded, so every pixel is tested.
				minX = 0;
				maxX = WIDTH - 1;
				minY = 0;
				maxY = HEIGHT - 1;
			} else {
				minX = Math.max(0, (int) Math.floor(left * halfH + halfW) - 1);
				maxX = Math.min(WIDTH - 1, (int) Math.ceil(rightMost * halfH + halfW) + 1);
				minY = Math.max(0, (int) Math.floor(halfH - hi * halfH) - 1);
				maxY = Math.min(HEIGHT - 1, (int) Math.ceil(halfH - lo * halfH) + 1);
			}
			onScreen = minX <= maxX && minY <= maxY;
		}

		/**
		 * Depth at which the ray through screen position (u, v) meets the box, or
		 * {@link Double#MAX_VALUE} when it misses.
		 */
		double hit(double u, double v) {
			double near = 0.0;
			double far = Double.MAX_VALUE;
			for (int axis = 0; axis < 3; axis++) {
				double d = u * rightL[axis] + v * upL[axis] + forwardL[axis];
				double o = origin[axis];
				double max = part.half[axis];
				double min = -max;
				if (Math.abs(d) < 1e-9) {
					if (o < min || o > max) {
						return Double.MAX_VALUE;
					}
					continue;
				}
				double t1 = (min - o) / d;
				double t2 = (max - o) / d;
				if (t1 > t2) {
					double swap = t1;
					t1 = t2;
					t2 = swap;
				}
				near = Math.max(near, t1);
				far = Math.min(far, t2);
				if (near > far) {
					return Double.MAX_VALUE;
				}
			}
			return near;
		}
	}
}
