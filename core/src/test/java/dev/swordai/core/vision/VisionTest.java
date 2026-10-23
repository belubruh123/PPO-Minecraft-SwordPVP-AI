package dev.swordai.core.vision;

import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionTest {
	private static final int[] GREY = {128, 128, 128};
	private static final int[] HEAD = {0, 0, 255};
	private static final int[] BODY = {0, 255, 0};

	/** Frame builder working in top-down image coordinates, optionally stored bottom-up like OpenGL. */
	private static final class Frame {
		final int w;
		final int h;
		final boolean bottomUp;
		final ByteBuffer buf;

		Frame(int w, int h, boolean bottomUp) {
			this.w = w;
			this.h = h;
			this.bottomUp = bottomUp;
			this.buf = ByteBuffer.allocateDirect(w * h * 4);
			rect(0, 0, w, h, GREY);
		}

		void rect(int x0, int y0, int x1, int y1, int[] rgb) {
			for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
				int row = bottomUp ? h - 1 - y : y;
				for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
					int i = (row * w + x) * 4;
					buf.put(i, (byte) rgb[0]);
					buf.put(i + 1, (byte) rgb[1]);
					buf.put(i + 2, (byte) rgb[2]);
					buf.put(i + 3, (byte) 255);
				}
			}
		}

		MaskFrame extract() {
			MaskFrame out = new MaskFrame();
			new MaskExtractor().extract(buf, w, h, bottomUp, out);
			return out;
		}

		MaskFrame extract(float crop) {
			MaskFrame out = new MaskFrame();
			new MaskExtractor().extract(buf, w, h, bottomUp, crop, out);
			return out;
		}
	}

	@Test
	void theMiddleOfAWideFrameIsThePictureOfANarrowerFieldOfView() {
		// A 960x540 frame; its middle half is the 480x270 frame below, pixel for pixel.
		for (boolean bottomUp : new boolean[] {true, false}) {
			Frame wide = new Frame(960, 540, bottomUp);
			Frame narrow = new Frame(480, 270, bottomUp);
			int[][] rects = {{250, 120, 262, 132}, {244, 132, 268, 190}, {30, 40, 60, 90}};
			int[][] colours = {HEAD, BODY, BODY};
			for (int i = 0; i < rects.length; i++) {
				int[] r = rects[i];
				narrow.rect(r[0], r[1], r[2], r[3], colours[i]);
				wide.rect(r[0] + 240, r[1] + 135, r[2] + 240, r[3] + 135, colours[i]);
			}
			// Something outside the middle half must not be seen at all.
			wide.rect(10, 10, 200, 100, HEAD);
			wide.rect(800, 420, 950, 530, BODY);

			MaskFrame a = narrow.extract();
			MaskFrame b = wide.extract(0.5f);
			assertEquals(a.headSamples, b.headSamples);
			assertEquals(a.bodySamples, b.bodySamples);
			for (int i = 0; i < ObsSpec.STATS; i++) {
				assertEquals(a.stats[i], b.stats[i], 1e-5f, "stat " + i);
			}
			org.junit.jupiter.api.Assertions.assertArrayEquals(a.peri, b.peri);
			org.junit.jupiter.api.Assertions.assertArrayEquals(a.fovea, b.fovea);
			// Without the crop the same frame shows everything at half the size.
			MaskFrame whole = wide.extract();
			assertTrue(whole.stats[ObsSpec.ST_ANY_AREA] > 0f && whole.headSamples != a.headSamples);
		}
	}

	@Test
	void cropFollowsTheTwoFieldsOfView() {
		assertEquals(1f, ViewGeometry.cropFor(90f, 90f));
		assertEquals(1f, ViewGeometry.cropFor(100f, 90f), "never wider than the screen");
		assertEquals((float) (Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(45))), ViewGeometry.cropFor(70f, 90f), 1e-6f);
	}

	@Test
	void theFieldOfViewOfThePictureCanBeSet() {
		assertEquals(ObsSpec.FOV_DEG, ViewGeometry.fov());
		assertEquals(90f, ObsSpec.FOV_DEG, "the screen and the picture are FOV 90 unless the config says otherwise");
		try {
			ViewGeometry.setFov(70f);
			assertEquals((float) Math.tan(Math.toRadians(35)), ViewGeometry.tanHalfFov(), 1e-6f);
			float at70 = ViewGeometry.apparentSize(1.8f, 3f);
			ViewGeometry.setFov(90f);
			// The same opponent at the same range looks smaller in a wider view.
			assertEquals(at70 * (float) Math.tan(Math.toRadians(35)), ViewGeometry.apparentSize(1.8f, 3f), 1e-5f);
			ViewGeometry.setFov(500f);
			assertEquals(ViewGeometry.MAX_FOV_DEG, ViewGeometry.fov());
		} finally {
			ViewGeometry.setFov(ObsSpec.FOV_DEG);
		}
	}

	@Test
	void classifiesOnlySaturatedKeyColours() {
		assertEquals(MaskExtractor.HEAD, MaskExtractor.classify(0, 0, 255));
		assertEquals(MaskExtractor.HEAD, MaskExtractor.classify(10, 10, 120), "shaded head");
		assertEquals(MaskExtractor.BODY, MaskExtractor.classify(0, 255, 0));
		assertEquals(MaskExtractor.BODY, MaskExtractor.classify(12, 100, 8), "shaded body");
		assertEquals(MaskExtractor.NONE, MaskExtractor.classify(120, 167, 255), "day sky");
		assertEquals(MaskExtractor.NONE, MaskExtractor.classify(145, 189, 89), "grass");
		assertEquals(MaskExtractor.NONE, MaskExtractor.classify(72, 181, 24), "leaves");
		assertEquals(MaskExtractor.NONE, MaskExtractor.classify(60, 220, 215), "diamond sword");
		assertEquals(MaskExtractor.NONE, MaskExtractor.classify(128, 128, 128), "stone");
		assertEquals(MaskExtractor.NONE, MaskExtractor.classify(0, 0, 20), "too dark");
	}

	@Test
	void emptyFrameHasNoEnemy() {
		MaskFrame f = new Frame(480, 270, true).extract();
		assertFalse(f.enemyVisible());
		assertEquals(0, f.headSamples);
		for (byte b : f.peri) {
			assertEquals(0, b);
		}
		for (float s : f.stats) {
			assertEquals(0f, s);
		}
	}

	@Test
	void findsHeadAndBodyWithCorrectGeometry() {
		// 16:9 frame; head 20x20 px centred on the crosshair, body below it.
		Frame frame = new Frame(480, 270, true);
		frame.rect(230, 125, 250, 145, HEAD);
		frame.rect(228, 145, 252, 215, BODY);
		MaskFrame f = frame.extract();

		assertTrue(f.enemyVisible());
		assertEquals(400, f.headSamples);
		assertEquals(24 * 70, f.bodySamples);
		float[] s = f.stats;
		assertEquals(1f, s[ObsSpec.ST_HEAD_VISIBLE]);
		assertEquals(0f, s[ObsSpec.ST_HEAD_X], 1e-3);
		assertEquals(0f, s[ObsSpec.ST_HEAD_Y], 1e-3);
		// 20 px head on a 270 px frame.
		assertEquals(20f / 270f, s[ObsSpec.ST_HEAD_AREA], 1e-3);
		// Box edges in half-frame-height units: x from 228..252, y from 125..215, centre (240, 135).
		assertEquals((228 - 240) / 135f, s[ObsSpec.ST_LEFT], 1e-3);
		assertEquals((252 - 240) / 135f, s[ObsSpec.ST_RIGHT], 1e-3);
		assertEquals((135 - 125) / 135f, s[ObsSpec.ST_TOP], 1e-3);
		assertEquals((135 - 215) / 135f, s[ObsSpec.ST_BOTTOM], 1e-3);
		assertTrue(s[ObsSpec.ST_ANY_Y] < 0f, "body hangs below the crosshair");

		// Periphery: each cell is 10x10 px here. The head covers cells (23..24, 12..13).
		int headCell = (13 * ObsSpec.PERI_W + 23) * ObsSpec.CHANNELS + ObsSpec.CH_HEAD;
		assertTrue((f.peri[headCell] & 0xFF) > 100, "head cell should be mostly covered");
		int farCell = (2 * ObsSpec.PERI_W + 2) * ObsSpec.CHANNELS + ObsSpec.CH_HEAD;
		assertEquals(0, f.peri[farCell]);
		// Fovea centre is on the head.
		int foveaCentre = (15 * ObsSpec.FOVEA + 15) * ObsSpec.CHANNELS + ObsSpec.CH_HEAD;
		assertEquals(255, f.fovea[foveaCentre] & 0xFF);
	}

	@Test
	void rowOrderDoesNotChangeTheResult() {
		Frame a = new Frame(480, 270, true);
		Frame b = new Frame(480, 270, false);
		for (Frame f : new Frame[] {a, b}) {
			f.rect(300, 40, 318, 58, HEAD);
			f.rect(298, 58, 320, 120, BODY);
		}
		MaskFrame fa = a.extract();
		MaskFrame fb = b.extract();
		assertTrue(fa.stats[ObsSpec.ST_HEAD_Y] > 0.5f, "head is in the upper half");
		assertTrue(fa.stats[ObsSpec.ST_HEAD_X] > 0.4f, "head is right of centre");
		for (int i = 0; i < ObsSpec.STATS; i++) {
			assertEquals(fa.stats[i], fb.stats[i], 1e-6, "stat " + i);
		}
		for (int i = 0; i < fa.peri.length; i++) {
			assertEquals(fa.peri[i], fb.peri[i]);
		}
	}

	@Test
	void windowShapeAndSizeDoNotChangeTheStatistics() {
		// Same scene (in half-height units) on a small 16:9 window, a 4x larger one, and an ultrawide.
		MaskFrame small = scene(480, 270);
		MaskFrame large = scene(1920, 1080);
		MaskFrame wide = scene(2560, 1080);
		for (int i = 0; i < ObsSpec.STATS; i++) {
			assertEquals(small.stats[i], large.stats[i], 0.02, "stat " + i + " small vs large");
			assertEquals(large.stats[i], wide.stats[i], 0.02, "stat " + i + " large vs ultrawide");
		}
	}

	private static MaskFrame scene(int w, int h) {
		Frame f = new Frame(w, h, true);
		float half = h / 2f;
		int cx = w / 2;
		int cy = h / 2;
		f.rect(cx + Math.round(0.20f * half), cy - Math.round(0.30f * half), cx + Math.round(0.36f * half), cy - Math.round(0.14f * half), HEAD);
		f.rect(cx + Math.round(0.18f * half), cy - Math.round(0.14f * half), cx + Math.round(0.38f * half), cy + Math.round(0.40f * half), BODY);
		return f.extract();
	}

	@Test
	void enemyOutsideThe16by9RegionIsIgnored() {
		// On an ultrawide frame the far-left strip is outside what the agent is allowed to see.
		Frame f = new Frame(2560, 1080, true);
		f.rect(10, 500, 80, 580, HEAD);
		assertFalse(f.extract().enemyVisible());
	}

	@Test
	void reprojectMovesAPointByTheCamerasOwnTurn() {
		float[] out = new float[2];
		float t = ViewGeometry.tanHalfFov();
		// No turn: the point stays where it was, wherever the camera was looking.
		assertTrue(ViewGeometry.reproject(0.3f, -0.2f, 40f, 12f, 40f, 12f, out));
		assertEquals(0.3f, out[0], 1e-5);
		assertEquals(-0.2f, out[1], 1e-5);
		// Something dead ahead ends up 10 degrees to the left after turning 10 degrees right.
		assertTrue(ViewGeometry.reproject(0f, 0f, 0f, 0f, 10f, 0f, out));
		assertEquals(-Math.tan(Math.toRadians(10)) / t, out[0], 1e-4);
		assertEquals(0f, out[1], 1e-4);
		// Looking down by 10 degrees leaves it 10 degrees above the crosshair.
		assertTrue(ViewGeometry.reproject(0f, 0f, 0f, 0f, 0f, 10f, out));
		assertEquals(0f, out[0], 1e-4);
		assertEquals(Math.tan(Math.toRadians(10)) / t, out[1], 1e-4);
		// A target 15 degrees to the right is centred once the camera has turned 15 degrees right,
		// from any starting heading.
		float x15 = (float) (Math.tan(Math.toRadians(15)) / t);
		assertTrue(ViewGeometry.reproject(x15, 0f, -170f, 0f, -155f, 0f, out));
		assertEquals(0f, out[0], 1e-4);
		assertEquals(0f, out[1], 1e-4);
		// Turned right around: it is behind the camera now.
		assertFalse(ViewGeometry.reproject(0f, 0f, 0f, 0f, 180f, 0f, out));
	}

	@Test
	void viewGeometryFollowsMinecraftConventions() {
		float[] v = new float[3];
		// Yaw 0 faces +Z; west (-X) is then to the right.
		ViewGeometry.toView(0, 0, 5, 0f, 0f, v);
		assertEquals(0f, v[0], 1e-5);
		assertEquals(5f, v[2], 1e-5);
		ViewGeometry.toView(-1, 0, 0, 0f, 0f, v);
		assertEquals(1f, v[0], 1e-5);
		// Yaw 90 faces -X.
		ViewGeometry.toView(-3, 0, 0, 90f, 0f, v);
		assertEquals(3f, v[2], 1e-5);
		// Positive pitch looks down.
		ViewGeometry.toView(0, -2, 0, 0f, 90f, v);
		assertEquals(2f, v[2], 1e-5);
		// A point above eye level shows up above the crosshair.
		ViewGeometry.toView(0, 1, 5, 0f, 0f, v);
		assertTrue(ViewGeometry.screenY(v[1], v[2]) > 0f);

		assertEquals(0f, ViewGeometry.yawTo(0, 1), 1e-4);
		assertEquals(90f, ViewGeometry.yawTo(-1, 0), 1e-4);
		assertEquals(-90f, ViewGeometry.yawTo(1, 0), 1e-4);
		assertEquals(-45f, ViewGeometry.pitchTo(0, 1, 1), 1e-4);
		assertEquals(170f, ViewGeometry.wrapDegrees(-190f), 1e-4);
		assertEquals(-170f, ViewGeometry.wrapDegrees(190f), 1e-4);

		assertEquals(0f, ViewGeometry.angleTo(0, 0, 4, 0f, 0f), 1e-3);
		assertEquals(90f, ViewGeometry.angleTo(-4, 0, 0, 0f, 0f), 1e-3);
		assertEquals(30f, ViewGeometry.angleTo(0, 0, 4, 0f, 30f), 1e-3);

		// A 1.8-block player 3 blocks away fills 30% of the frame height at FOV 90, and 43% at FOV 70.
		assertEquals(0.300f, ViewGeometry.apparentSize(1.8f, 3f) / 2f, 2e-3);
		try {
			ViewGeometry.setFov(70f);
			assertEquals(0.428f, ViewGeometry.apparentSize(1.8f, 3f) / 2f, 2e-3);
		} finally {
			ViewGeometry.setFov(ObsSpec.FOV_DEG);
		}
	}
}
