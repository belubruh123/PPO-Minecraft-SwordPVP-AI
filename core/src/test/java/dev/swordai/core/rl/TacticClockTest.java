package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the tactics model chooses: every half second, and on events when asked to (21 October 2026). */
class TacticClockTest {
	private static float[] quiet() {
		float[] vec = new float[ObsSpec.VEC];
		vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		return vec;
	}

	private static int choicesOver(Tactic.Clock clock, float[][] ticks, boolean onEvents) {
		int n = 0;
		for (float[] vec : ticks) {
			if (clock.due(vec, onEvents)) {
				clock.set(Tactic.PRESS);
				n++;
			}
		}
		return n;
	}

	@Test
	void everyHalfSecondWhenNothingHappens() {
		float[][] ticks = new float[40][];
		for (int i = 0; i < ticks.length; i++) {
			ticks[i] = quiet();
		}
		assertEquals(4, choicesOver(new Tactic.Clock(), ticks, true));
	}

	@Test
	void andAgainRightAfterAHitASwingOrAJumpWhenAsked() {
		float[][] ticks = new float[20][];
		for (int i = 0; i < ticks.length; i++) {
			ticks[i] = quiet();
		}
		// A hit taken on tick 4, the opponent's swing on tick 9 (its sword starting over), its jump on tick 11 (too soon after: no choice), nothing after.
		ticks[4][ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		for (int i = 9; i < ticks.length; i++) {
			ticks[i][ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.1f;
		}
		ticks[11][ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.5f * BodyState.VELOCITY_SCALE;
		Tactic.Clock clock = new Tactic.Clock();
		boolean[] chose = new boolean[ticks.length];
		for (int i = 0; i < ticks.length; i++) {
			chose[i] = clock.due(ticks[i], true);
			if (chose[i]) {
				clock.set(Tactic.COUNTER);
			}
		}
		assertTrue(chose[0], "the first tick of a fight chooses");
		assertTrue(chose[4], "a hit taken");
		assertTrue(chose[9], "the opponent's swing");
		assertFalse(chose[11], "a choice stands at least " + Tactic.LEAST + " ticks");
		assertTrue(chose[19], "and half a second after the last one");
		assertEquals(2, choicesOver(new Tactic.Clock(), ticks, false), "without events: ticks 0 and 10 only");
	}
}
