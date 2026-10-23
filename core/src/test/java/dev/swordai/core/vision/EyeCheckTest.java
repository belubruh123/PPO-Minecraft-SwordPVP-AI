package dev.swordai.core.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EyeCheckTest {
	private static MaskFrame at(double distance) {
		MaskFrame frame = new MaskFrame();
		new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, distance, 180f, 180f, 0f, frame);
		return frame;
	}

	@Test
	void theSamePictureTwiceAgreesCompletely() {
		EyeCheck check = new EyeCheck();
		assertNull(check.summary());
		check.add(at(3), at(3));
		check.add(at(5), at(5));
		assertEquals(1.0, check.wideOverlap(), 1e-9);
		assertEquals(1.0, check.centreOverlap(), 1e-9);
		assertEquals(1.0, check.heightRatio(), 1e-9);
		assertEquals(1.0, check.areaRatio(), 1e-9);
		assertNotNull(check.summary());
	}

	@Test
	void aPictureDrawnTooLargeShowsUpAsSuch() {
		EyeCheck check = new EyeCheck();
		// "Real" is the opponent at 3.3 blocks, "drawn" at 3.0: a tenth too close.
		check.add(at(3.3), at(3.0));
		assertEquals(1.1, check.heightRatio(), 0.03);
		assertEquals(1.21, check.areaRatio(), 0.06);
		assertTrue(check.wideOverlap() < 0.95);

		check.clear();
		assertEquals(0, check.frames());
	}
}
