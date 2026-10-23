package dev.swordai.core.rl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LostRuleTest {
	private static float[] view(boolean seen) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = seen ? 1f : 0f;
		return v;
	}

	@Test
	void offTheScreenForThreeTicksTheCameraIsTheRulesUntilItIsFoundAndAFewTicksMore() {
		LostRule rule = new LostRule();
		assertFalse(rule.applies(view(true)));
		assertFalse(rule.applies(view(false)));
		assertFalse(rule.applies(view(false)));
		assertTrue(rule.applies(view(false)), "the third tick off the screen");
		assertTrue(rule.applies(view(false)));
		for (int tick = 0; tick < LostRule.SETTLE_TICKS; tick++) {
			assertTrue(rule.applies(view(true)), "found, it is brought onto it");
		}
		assertFalse(rule.applies(view(true)), "and then it is the network's again");
		// A tick or two off the screen is not lost.
		assertFalse(rule.applies(view(false)));
		assertFalse(rule.applies(view(false)));
		assertFalse(rule.applies(view(true)));
		rule.applies(view(false));
		rule.applies(view(false));
		rule.applies(view(false));
		rule.reset();
		assertFalse(rule.applies(view(true)), "nothing carried over a new fight");
	}

	@Test
	void lostItTurnsToTheSideTheOpponentWasLastSeenOnAndComesLevel() {
		AimRule aim = new AimRule(0.5f);
		float[] gone = view(false);
		gone[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE] = -1f;
		// Looking down at the ground.
		gone[ObsSpec.VEC_SCALARS + ObsSpec.SC_PITCH] = 40f / 90f;
		aim.plan(gone, 0f, 0f, false);
		assertTrue(aim.yawBin() == 0, "to the left, as fast as the hand goes");
		assertTrue(ActionSpace.PITCH_BINS[aim.pitchBin()] < 0f, "and up towards level");
		gone[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE] = 1f;
		aim.plan(gone, 0f, 0f, false);
		assertTrue(aim.yawBin() == ActionSpace.YAW_BINS.length - 1, "to the right");
	}
}
