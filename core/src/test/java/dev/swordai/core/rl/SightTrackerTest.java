package dev.swordai.core.rl;

import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SightTrackerTest {
	private static final float X15 = (float) (Math.tan(Math.toRadians(15)) / ViewGeometry.tanHalfFov());

	/** A frame in which the opponent's head is at screen x, on the crosshair's height. */
	private static float[] frameWithHeadAt(float x) {
		float[] raw = new float[ObsSpec.STATS];
		raw[ObsSpec.ST_HEAD_VISIBLE] = 1f;
		raw[ObsSpec.ST_HEAD_X] = x;
		raw[ObsSpec.ST_HEAD_AREA] = 0.1f;
		ObsSpec.deriveStats(raw);
		raw[ObsSpec.ST_ANY_VISIBLE] = 1f;
		raw[ObsSpec.ST_ANY_X] = x;
		raw[ObsSpec.ST_ANY_Y] = -0.2f;
		raw[ObsSpec.ST_LEFT] = x - 0.1f;
		raw[ObsSpec.ST_RIGHT] = x + 0.1f;
		raw[ObsSpec.ST_TOP] = 0.1f;
		raw[ObsSpec.ST_BOTTOM] = -0.5f;
		raw[ObsSpec.ST_ANY_AREA] = 0.3f;
		return raw;
	}

	@Test
	void aFreshFrameIsPassedThroughUntouched() {
		SightTracker s = new SightTracker();
		float[] raw = frameWithHeadAt(X15);
		s.update(raw, 33f, 4f, 33f, 4f);
		assertFalse(s.frameIsLate());
		for (int i = 0; i < ObsSpec.STATS; i++) {
			assertEquals(raw[i], s.current()[i], 0f, "stat " + i);
		}
	}

	@Test
	void anOldFrameIsCorrectedForTheTurnMadeSinceItWasTaken() {
		// The image shows the head 15 degrees to the right, but the agent has already turned those
		// 15 degrees. Told the raw position it would turn again and swing past the target.
		SightTracker s = new SightTracker();
		s.update(frameWithHeadAt(X15), 0f, 0f, 15f, 0f);
		assertTrue(s.frameIsLate());
		float[] now = s.current();
		assertEquals(1f, now[ObsSpec.ST_HEAD_VISIBLE]);
		assertEquals(0f, now[ObsSpec.ST_HEAD_X], 1e-4);
		assertEquals(0f, now[ObsSpec.ST_HEAD_X_FINE], 1e-2);
		assertEquals(0f, now[ObsSpec.ST_ANY_X], 1e-4);
		assertEquals(-0.1f, now[ObsSpec.ST_LEFT], 1e-3);
		assertEquals(0.1f, now[ObsSpec.ST_RIGHT], 1e-3);

		float[] vec = new float[ObsSpec.VEC];
		s.write(vec);
		assertEquals(15f / 20f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_TURNED_YAW], 1e-5);
		assertEquals(0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_TURNED_PITCH], 1e-5);
	}

	@Test
	void thePreviousFrameIsShownInTodaysViewSoOnlyTheOpponentsMotionRemains() {
		SightTracker s = new SightTracker();
		// The opponent stands still 15 degrees to the right; the agent turns 5 degrees towards it
		// between two fresh frames.
		float x10 = (float) (Math.tan(Math.toRadians(10)) / ViewGeometry.tanHalfFov());
		s.update(frameWithHeadAt(X15), 0f, 0f, 0f, 0f);
		s.update(frameWithHeadAt(x10), 5f, 0f, 5f, 0f);
		float[] vec = new float[ObsSpec.VEC];
		s.write(vec);
		float current = vec[ObsSpec.VEC_STATS + ObsSpec.ST_HEAD_X];
		float previous = vec[ObsSpec.VEC_STATS + ObsSpec.STATS + ObsSpec.ST_HEAD_X];
		assertEquals(x10, current, 1e-4);
		assertEquals(current, previous, 1e-3, "a motionless opponent must not appear to move when the camera turns");
	}

	@Test
	void remembersWhichSideTheOpponentWasLastSeenOn() {
		SightTracker s = new SightTracker();
		float[] vec = new float[ObsSpec.VEC];
		s.write(vec);
		assertEquals(0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE]);
		assertEquals(1f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_SEEN], "never seen");

		s.update(frameWithHeadAt(-0.6f), 0f, 0f, 0f, 0f);
		assertEquals(-1, s.lastSeenSide());
		assertEquals(0, s.ticksSinceSeen());

		float[] nothing = new float[ObsSpec.STATS];
		for (int i = 0; i < 10; i++) {
			s.update(nothing, 0f, 0f, 0f, 0f);
		}
		s.write(vec);
		assertEquals(-1f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE], "the memory outlives the sighting");
		assertEquals(-1f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_UP]);
		assertEquals(10f / 40f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_SEEN], 1e-6);
		assertEquals(0f, vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE]);

		s.reset();
		assertEquals(0, s.lastSeenSide());
	}
}
