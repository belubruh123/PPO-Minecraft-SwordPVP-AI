package dev.swordai.core.rl;

import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hit and out ({@link StepBack#planShort}): S for four ticks after a hit of its own that the opponent in reach, sword back, can answer. */
class StepBackTest {
	/** The opponent this far off straight ahead with its sword at {@code oppCharge}; a hit of its own on this tick if {@code dealt}, one taken if {@code taken}. */
	private static float[] vec(float distance, float oppCharge, boolean dealt, boolean taken) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		float height = ViewGeometry.apparentSize(1.8f, distance);
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.1f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = -0.9f * height;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = oppCharge;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = dealt ? 1f : 0f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = taken ? 1f : 0f;
		return v;
	}

	private static int heldTicks(StepBack s, float distanceAtHit, float distanceAfter, float oppCharge) {
		int held = s.planShort(vec(distanceAtHit, oppCharge, true, false)) == StepBack.BACK ? 1 : 0;
		for (int t = 0; t < 12; t++) {
			held += s.planShort(vec(distanceAfter, oppCharge, false, false)) == StepBack.BACK ? 1 : 0;
		}
		return held;
	}

	@Test
	void sHeldForFourTicksAfterAHitWithTheOpponentInReachAndItsSwordBack() {
		assertEquals(StepBack.SHORT_TICKS, heldTicks(new StepBack(), 2.2f, 2.6f, 1f));
	}

	@Test
	void notWhenTheOpponentsSwordIsStillOnItsWayBack() {
		assertEquals(0, heldTicks(new StepBack(), 2.2f, 2.6f, 0.2f));
	}

	@Test
	void notWhenTheHitHasAlreadyPutTheOpponentOutOfReach() {
		assertEquals(0, heldTicks(new StepBack(), 3.2f, 3.4f, 1f));
	}

	@Test
	void stopsWhenTheOpponentIsThrownOutOfReachDuringTheHold() {
		StepBack s = new StepBack();
		assertEquals(StepBack.BACK, s.planShort(vec(2.2f, 1f, true, false)));
		assertEquals(StepBack.BACK, s.planShort(vec(2.8f, 1f, false, false)));
		// Sent flying: 4 blocks off, it goes after it again.
		assertEquals(0, s.planShort(vec(4.0f, 1f, false, false)));
		assertEquals(0, s.planShort(vec(2.4f, 1f, false, false)));
	}

	@Test
	void aHitTakenEndsTheHold() {
		StepBack s = new StepBack();
		assertEquals(StepBack.BACK, s.planShort(vec(2.2f, 1f, true, false)));
		assertEquals(StepBack.BACK, s.planShort(vec(2.3f, 1f, false, false)));
		assertEquals(0, s.planShort(vec(2.0f, 1f, false, true)));
		assertEquals(0, s.planShort(vec(2.0f, 1f, false, false)));
	}

	@Test
	void aNewHitStartsANewHold() {
		StepBack s = new StepBack();
		assertEquals(StepBack.SHORT_TICKS, heldTicks(s, 2.2f, 2.6f, 1f));
		assertEquals(StepBack.SHORT_TICKS, heldTicks(s, 2.0f, 2.4f, 1f));
	}

	@Test
	void nothingWithoutAHitOfItsOwn() {
		StepBack s = new StepBack();
		for (int t = 0; t < 30; t++) {
			assertEquals(0, s.planShort(vec(2.2f, 1f, false, false)));
		}
	}
}
