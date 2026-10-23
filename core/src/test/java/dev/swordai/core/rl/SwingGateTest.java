package dev.swordai.core.rl;

import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No swing before the sword is back, nor at an opponent plainly out of reach ({@link SwingGate}). */
class SwingGateTest {
	/** The opponent this far away, straight ahead. */
	private static float[] at(float distance) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		float height = ViewGeometry.apparentSize(1.8f, distance);
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.1f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = -0.9f * height;
		return v;
	}

	@Test
	void theSwordHasToBeBack() {
		int all = ActionSpace.mask(ActionSpace.ATTACK, ActionSpace.FORWARD);
		assertEquals(ActionSpace.mask(ActionSpace.FORWARD), SwingGate.keys(all, 0.9f, 0.96f));
		assertEquals(all, SwingGate.keys(all, 1f, 0.96f));
	}

	@Test
	void noSwingAtAnOpponentPlainlyOutOfReach() {
		assertFalse(SwingGate.outOfReach(at(2.5f)), "in reach");
		assertFalse(SwingGate.outOfReach(at(3.2f)), "at the edge of reach it may still come in");
		assertTrue(SwingGate.outOfReach(at(4.6f)), "knocked back out of reach: a swing at nothing");
		assertTrue(SwingGate.outOfReach(at(6f)));
		float[] gone = at(2.5f);
		gone[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 0f;
		assertTrue(SwingGate.outOfReach(gone), "nowhere on the screen");
		// In its face only part of it is on the screen: short there, but not far.
		float[] cut = at(5f);
		cut[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 1f;
		assertFalse(SwingGate.outOfReach(cut));
	}
}
