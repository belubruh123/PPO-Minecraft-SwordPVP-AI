package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Out of reach it runs straight at the opponent; within it, and just after being hit, the strafe is the network's ({@link StraightIn}). */
class StraightInTest {
	/** The opponent this far away, straight ahead, nothing hit for a long time. */
	private static float[] at(float distance) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		float height = ViewGeometry.apparentSize(1.8f, distance);
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.1f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = -0.9f * height;
		return v;
	}

	@Test
	void outOfReachTheStrafeIsLetGoOfAndWithinItItIsTheNetworks() {
		assertTrue(StraightIn.applies(at(4.5f)), "backed off out of reach: straight at it");
		assertTrue(StraightIn.applies(at(8f)));
		assertFalse(StraightIn.applies(at(3.2f)), "at the edge of reach it may still go round");
		assertFalse(StraightIn.applies(at(2.2f)), "in a combo's range");
	}

	@Test
	void notJustAfterAHitTakenNorWithTheOpponentOffTheScreenOrCutOffByItsEdge() {
		float[] hit = at(4.5f);
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 3 / BodyState.EVENT_FADE_TICKS;
		assertFalse(StraightIn.applies(hit), "thrown back by a hit, a step aside is part of getting out");
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 12 / BodyState.EVENT_FADE_TICKS;
		assertTrue(StraightIn.applies(hit), "a while after it, straight in again");

		float[] gone = at(4.5f);
		gone[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 0f;
		assertFalse(StraightIn.applies(gone));

		// Looking down at an opponent close by, only its legs are on screen: short there, but not far.
		float[] cut = at(5f);
		cut[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 1f;
		assertFalse(StraightIn.applies(cut));
	}
}
