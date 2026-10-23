package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.TakenStreak;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** From the second hit of a combo it is taking, A or D for half a second, away from the opponent's way across ({@link ComboStrafe}). */
class ComboStrafeTest {
	/** Hit on this very tick, the n-th hit in a row, the opponent moving across at this much a tick (right +). */
	private static float[] hit(int inRow, float across) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = Math.min(inRow, TakenStreak.SHOWN_MAX) / (float) TakenStreak.SHOWN_MAX;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_RIGHT] = across * BodyState.VELOCITY_SCALE;
		return v;
	}

	/** A tick later than {@code v}: the hit is no longer new. */
	private static float[] later(float[] v, int ticks) {
		float[] w = v.clone();
		w[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - ticks / BodyState.EVENT_FADE_TICKS;
		return w;
	}

	@Test
	void notOnTheFirstHitOfAnExchange() {
		ComboStrafe c = new ComboStrafe();
		assertEquals(0, c.plan(hit(1, 0.2f)));
		assertFalse(c.active());
	}

	@Test
	void fromTheSecondHitAwayFromTheOpponentsWayAcrossForHalfASecond() {
		ComboStrafe c = new ComboStrafe();
		float[] h = hit(2, 0.2f);
		assertEquals(ComboStrafe.LEFT, c.plan(h), "it comes across to the right: the fighter goes left");
		for (int t = 1; t < ComboStrafe.TICKS; t++) {
			assertEquals(ComboStrafe.LEFT, c.plan(later(h, t)));
		}
		assertEquals(0, c.plan(later(h, ComboStrafe.TICKS)), "then it is the network's again");

		ComboStrafe d = new ComboStrafe();
		assertEquals(ComboStrafe.RIGHT, d.plan(hit(3, -0.2f)), "across to the left: the fighter goes right");
	}

	@Test
	void straightAtItTheOtherWayFromTheTimeBefore() {
		ComboStrafe c = new ComboStrafe();
		int first = c.plan(hit(2, 0f));
		int second = c.plan(hit(3, 0f));
		assertTrue(first != 0 && second != 0);
		assertTrue(first != second, "it does not go the same way every time, where the opponent would learn it");
	}

	@Test
	void aHitOfItsOwnEndsIt() {
		ComboStrafe c = new ComboStrafe();
		float[] h = hit(2, 0.2f);
		c.plan(h);
		float[] mine = later(h, 3);
		mine[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
		assertEquals(0, c.plan(mine));
		assertFalse(c.active());
	}

	@Test
	void aNewRoundForgetsIt() {
		ComboStrafe c = new ComboStrafe();
		c.plan(hit(2, 0.2f));
		c.reset();
		assertEquals(0, c.plan(later(hit(2, 0.2f), 2)));
	}
}
