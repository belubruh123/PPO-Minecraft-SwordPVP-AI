package dev.swordai.core.vision;

import dev.swordai.core.rl.ObsSpec;

import java.util.Locale;

/**
 * Measures how well the drawn picture ({@link SyntheticEye}) matches the real one. During training
 * and play the opponent is on the real screen; drawing the same moment again and comparing the two
 * shows, in numbers from the user's own game, whether a fighter given the drawn picture sees what
 * it saw on the screen.
 */
public final class EyeCheck {
	private long frames;
	private long both;
	private long onlyReal;
	private long onlyDrawn;
	private double offX;
	private double offY;
	private double headOff;
	private long headBoth;
	private double logHeight;
	private double logWidth;
	private double logArea;
	private double logHeadArea;
	private double periShared;
	private double periEither;
	private double foveaShared;
	private double foveaEither;

	public void clear() {
		frames = 0;
		both = 0;
		onlyReal = 0;
		onlyDrawn = 0;
		offX = 0;
		offY = 0;
		headOff = 0;
		headBoth = 0;
		logHeight = 0;
		logWidth = 0;
		logArea = 0;
		logHeadArea = 0;
		periShared = 0;
		periEither = 0;
		foveaShared = 0;
		foveaEither = 0;
	}

	/** Adds one moment: the frame from the screen and the same moment drawn. */
	public void add(MaskFrame real, MaskFrame drawn) {
		frames++;
		boolean r = real.enemyVisible();
		boolean d = drawn.enemyVisible();
		if (r != d) {
			if (r) {
				onlyReal++;
			} else {
				onlyDrawn++;
			}
		}
		overlap(real.peri, drawn.peri, true);
		overlap(real.fovea, drawn.fovea, false);
		if (!r || !d) {
			return;
		}
		both++;
		float[] a = real.stats;
		float[] b = drawn.stats;
		offX += Math.abs(b[ObsSpec.ST_ANY_X] - a[ObsSpec.ST_ANY_X]);
		offY += Math.abs(b[ObsSpec.ST_ANY_Y] - a[ObsSpec.ST_ANY_Y]);
		logHeight += ratio(b[ObsSpec.ST_TOP] - b[ObsSpec.ST_BOTTOM], a[ObsSpec.ST_TOP] - a[ObsSpec.ST_BOTTOM]);
		logWidth += ratio(b[ObsSpec.ST_RIGHT] - b[ObsSpec.ST_LEFT], a[ObsSpec.ST_RIGHT] - a[ObsSpec.ST_LEFT]);
		logArea += 2.0 * ratio(b[ObsSpec.ST_ANY_AREA], a[ObsSpec.ST_ANY_AREA]);
		if (a[ObsSpec.ST_HEAD_VISIBLE] > 0f && b[ObsSpec.ST_HEAD_VISIBLE] > 0f) {
			headBoth++;
			headOff += Math.hypot(b[ObsSpec.ST_HEAD_X] - a[ObsSpec.ST_HEAD_X], b[ObsSpec.ST_HEAD_Y] - a[ObsSpec.ST_HEAD_Y]);
			logHeadArea += 2.0 * ratio(b[ObsSpec.ST_HEAD_AREA], a[ObsSpec.ST_HEAD_AREA]);
		}
	}

	private static double ratio(float drawn, float real) {
		if (drawn <= 1e-4f || real <= 1e-4f) {
			return 0.0;
		}
		return Math.log(drawn / real);
	}

	private void overlap(byte[] real, byte[] drawn, boolean peri) {
		double shared = 0;
		double either = 0;
		for (int i = 0; i < real.length; i++) {
			int a = real[i] & 0xFF;
			int b = drawn[i] & 0xFF;
			shared += Math.min(a, b);
			either += Math.max(a, b);
		}
		if (peri) {
			periShared += shared;
			periEither += either;
		} else {
			foveaShared += shared;
			foveaEither += either;
		}
	}

	public long frames() {
		return frames;
	}

	/** How much of the two wide masks coincides, 0..1. */
	public double wideOverlap() {
		return periEither <= 0 ? 0.0 : periShared / periEither;
	}

	public double centreOverlap() {
		return foveaEither <= 0 ? 0.0 : foveaShared / foveaEither;
	}

	/** The drawn opponent's height on screen as a multiple of the real one's (geometric mean). */
	public double heightRatio() {
		return both == 0 ? 1.0 : Math.exp(logHeight / both);
	}

	public double areaRatio() {
		return both == 0 ? 1.0 : Math.exp(logArea / both);
	}

	/** One line for the report, or null before anything was compared. */
	public String summary() {
		if (frames == 0) {
			return null;
		}
		double n = Math.max(1, both);
		double h = Math.max(1, headBoth);
		return String.format(Locale.ROOT,
				"%d moments compared: masks coincide %.0f%% (wide) / %.0f%% (centre); drawn vs real size: height x%.3f, width x%.3f, area x%.3f, head area x%.3f; "
						+ "position off by %.3f across, %.3f up (head %.3f) of half the screen height; seen in only one of the two: real %.1f%%, drawn %.1f%%",
				frames, wideOverlap() * 100.0, centreOverlap() * 100.0,
				heightRatio(), Math.exp(logWidth / n), areaRatio(), Math.exp(logHeadArea / h),
				offX / n, offY / n, headOff / h,
				100.0 * onlyReal / frames, 100.0 * onlyDrawn / frames);
	}
}
