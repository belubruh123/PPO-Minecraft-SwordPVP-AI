package dev.swordai.core.vision;

import dev.swordai.core.rl.ObsSpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Turns a rendered RGBA frame into the enemy masks. The opponent is drawn with a colour-keyed skin
 * (pure blue head, pure green body); everything else in the frame is discarded.
 *
 * <p>Only a 16:9 region centred on the crosshair is used and all coordinates are expressed in
 * units of half the frame height, so the result is the same whatever shape the window is.
 * Not thread-safe: use one instance per thread.
 */
public final class MaskExtractor {
	public static final int NONE = 0;
	public static final int HEAD = 1;
	public static final int BODY = 2;

	/** Frames taller than this are subsampled so the cost stays flat on big windows. */
	private static final int TARGET_ROWS = 270;
	/** A key channel must be at least this bright, and at least four times each other channel. */
	private static final int MIN_KEY = 48;
	/** Fewer samples than this is treated as noise, not as an enemy. */
	private static final int MIN_SAMPLES = 2;

	private final int[] periCount = new int[ObsSpec.PERI_SIZE];
	private final int[] periTotal = new int[ObsSpec.PERI_W * ObsSpec.PERI_H];
	private final int[] foveaCount = new int[ObsSpec.FOVEA_SIZE];
	private final int[] foveaTotal = new int[ObsSpec.FOVEA * ObsSpec.FOVEA];

	/** Classifies one pixel; channels are 0..255. */
	public static int classify(int r, int g, int b) {
		if (b >= MIN_KEY && r * 4 <= b && g * 4 <= b) {
			return HEAD;
		}
		if (g >= MIN_KEY && r * 4 <= g && b * 4 <= g) {
			return BODY;
		}
		return NONE;
	}

	/**
	 * @param rgba     pixels as R,G,B,A bytes, row after row, {@code width * height * 4} bytes from index 0
	 * @param bottomUp true when row 0 is the bottom of the image (OpenGL read-back order)
	 */
	public void extract(ByteBuffer rgba, int width, int height, boolean bottomUp, MaskFrame out) {
		extract(rgba, width, height, bottomUp, 1f, out);
	}

	/**
	 * @param crop the middle part of the frame to use, as a fraction of its height (1 is all of it).
	 *             The middle of a frame drawn with a wide field of view is exactly the picture of a
	 *             narrower one, so this lets the screen and the network use different fields of view.
	 *             Everything reported is relative to the part used, as if it were the whole frame.
	 */
	public void extract(ByteBuffer rgba, int width, int height, boolean bottomUp, float crop, MaskFrame out) {
		Arrays.fill(periCount, 0);
		Arrays.fill(periTotal, 0);
		Arrays.fill(foveaCount, 0);
		Arrays.fill(foveaTotal, 0);
		out.width = width;
		out.height = height;

		// Little-endian int read: R is the low byte regardless of the buffer's own order.
		ByteBuffer px = rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		// The part of the frame that is looked at: "used" rows high, centred, starting at row y0.
		float used = height * Math.max(0.05f, Math.min(1f, crop));
		float y0 = (height - used) * 0.5f;
		int stride = Math.max(1, (int) (used / TARGET_ROWS));
		float regionW = used * ObsSpec.ASPECT;
		float regionX0 = (width - regionW) * 0.5f;
		float foveaSide = used * ObsSpec.FOVEA_FRACTION;
		float foveaX0 = (width - foveaSide) * 0.5f;
		float foveaY0 = (height - foveaSide) * 0.5f;
		float periXScale = ObsSpec.PERI_W / regionW;
		float periYScale = ObsSpec.PERI_H / used;
		float foveaScale = ObsSpec.FOVEA / foveaSide;

		long headN = 0;
		long headSumX = 0;
		long headSumY = 0;
		long anyN = 0;
		long anySumX = 0;
		long anySumY = 0;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;

		int xStart = Math.max(0, (int) Math.ceil(regionX0)) + stride / 2;
		int xEnd = Math.min(width, (int) Math.floor(regionX0 + regionW));
		int yStart = Math.max(0, (int) Math.ceil(y0)) + stride / 2;
		int yEnd = Math.min(height, (int) Math.floor(y0 + used));

		for (int y = yStart; y < yEnd; y += stride) {
			int row = bottomUp ? height - 1 - y : y;
			int base = row * width * 4;
			int py = Math.min(ObsSpec.PERI_H - 1, (int) ((y - y0) * periYScale));
			float fyf = (y - foveaY0) * foveaScale;
			boolean foveaRow = fyf >= 0f && fyf < ObsSpec.FOVEA;
			int fy = (int) fyf;

			for (int x = xStart; x < xEnd; x += stride) {
				int v = px.getInt(base + x * 4);
				int cls = classify(v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF);

				int pxCell = Math.min(ObsSpec.PERI_W - 1, (int) ((x - regionX0) * periXScale));
				int cell = py * ObsSpec.PERI_W + pxCell;
				periTotal[cell]++;

				boolean inFovea = false;
				int fcell = 0;
				if (foveaRow) {
					float fxf = (x - foveaX0) * foveaScale;
					if (fxf >= 0f && fxf < ObsSpec.FOVEA) {
						inFovea = true;
						fcell = fy * ObsSpec.FOVEA + (int) fxf;
						foveaTotal[fcell]++;
					}
				}

				if (cls == NONE) {
					continue;
				}
				int ch = cls == HEAD ? ObsSpec.CH_HEAD : ObsSpec.CH_BODY;
				periCount[cell * ObsSpec.CHANNELS + ch]++;
				if (inFovea) {
					foveaCount[fcell * ObsSpec.CHANNELS + ch]++;
				}
				anyN++;
				anySumX += x;
				anySumY += y;
				if (x < minX) {
					minX = x;
				}
				if (x > maxX) {
					maxX = x;
				}
				if (y < minY) {
					minY = y;
				}
				if (y > maxY) {
					maxY = y;
				}
				if (cls == HEAD) {
					headN++;
					headSumX += x;
					headSumY += y;
				}
			}
		}

		fill(periCount, periTotal, out.peri);
		fill(foveaCount, foveaTotal, out.fovea);
		out.headSamples = (int) headN;
		out.bodySamples = (int) (anyN - headN);

		float[] s = out.stats;
		Arrays.fill(s, 0f);
		// Positions are measured from the middle of the frame, in half-heights of the part used.
		float halfH = used * 0.5f;
		float midY = height * 0.5f;
		float halfW = width * 0.5f;
		float half = stride * 0.5f;
		float pixelArea = (float) stride * stride / (used * used);
		if (headN >= MIN_SAMPLES) {
			s[ObsSpec.ST_HEAD_VISIBLE] = 1f;
			s[ObsSpec.ST_HEAD_X] = ((float) headSumX / headN + 0.5f - halfW) / halfH;
			s[ObsSpec.ST_HEAD_Y] = (midY - ((float) headSumY / headN + 0.5f)) / halfH;
			s[ObsSpec.ST_HEAD_AREA] = (float) Math.sqrt(headN * pixelArea);
			ObsSpec.deriveStats(s);
		}
		if (anyN >= MIN_SAMPLES) {
			s[ObsSpec.ST_ANY_VISIBLE] = 1f;
			s[ObsSpec.ST_ANY_X] = ((float) anySumX / anyN + 0.5f - halfW) / halfH;
			s[ObsSpec.ST_ANY_Y] = (midY - ((float) anySumY / anyN + 0.5f)) / halfH;
			// A sample stands for a stride-wide block, so the box extends half a stride past its centres.
			s[ObsSpec.ST_LEFT] = (minX + 0.5f - half - halfW) / halfH;
			s[ObsSpec.ST_RIGHT] = (maxX + 0.5f + half - halfW) / halfH;
			s[ObsSpec.ST_TOP] = (midY - (minY + 0.5f - half)) / halfH;
			s[ObsSpec.ST_BOTTOM] = (midY - (maxY + 0.5f + half)) / halfH;
			s[ObsSpec.ST_ANY_AREA] = (float) Math.sqrt(anyN * pixelArea);
		}
	}

	/** Coverage of each cell as 0..255. Cells that fall outside the frame stay empty. */
	private static void fill(int[] count, int[] total, byte[] out) {
		for (int cell = 0; cell < total.length; cell++) {
			int t = total[cell];
			for (int ch = 0; ch < ObsSpec.CHANNELS; ch++) {
				int i = cell * ObsSpec.CHANNELS + ch;
				out[i] = t == 0 ? 0 : (byte) ((count[i] * 255 + t / 2) / t);
			}
		}
	}
}
