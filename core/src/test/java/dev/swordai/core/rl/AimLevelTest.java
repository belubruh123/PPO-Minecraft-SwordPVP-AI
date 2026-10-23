package dev.swordai.core.rl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code Config.aimLevel} the aim rule brings the camera level with the fighter's eye on an opponent whose body spans
 * that height, not onto the middle of it: the shortest ray, the longest reach. Above or below it, onto its nearest part.
 */
class AimLevelTest {
	/** An opponent on screen: its middle, top and bottom, in half-heights of the picture, the camera at this pitch. */
	private static float[] seen(float middle, float top, float bottom, float pitchDeg) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_Y] = middle;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = top;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = bottom;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_PITCH] = pitchDeg / 90f;
		return v;
	}

	private static float pitchTurn(AimRule rule, float[] v) {
		rule.plan(v, 0f, 0f);
		return ActionSpace.PITCH_BINS[rule.pitchBin()];
	}

	@Test
	void levelWithItsOwnEyeWhereTheOpponentSpansThatHeight() {
		// Looking level at an opponent on the same ground: its middle is below the crosshair, its top a little above.
		float[] v = seen(-0.3f, 0.15f, -0.75f, 0f);
		AimRule middle = new AimRule(1f);
		assertTrue(pitchTurn(middle, v) > 0f, "the old rule looks down to its middle");
		AimRule level = new AimRule(1f);
		level.level(true);
		assertEquals(0f, pitchTurn(level, v), 1e-6f, "level: stays");
		// Looking 10 degrees down at it: brought back up towards level.
		assertTrue(pitchTurn(level, seen(0.0f, 0.45f, -0.45f, 10f)) < 0f);
		// An opponent wholly below the eye (the fighter in the air above it): onto its top part, not level.
		float down = pitchTurn(level, seen(-0.6f, -0.2f, -1.0f, 0f));
		assertTrue(down > 0f, "down to its top");
		assertTrue(down < pitchTurn(new AimRule(1f), seen(-0.6f, -0.2f, -1.0f, 0f)) + 1e-6f, "but not as far down as its middle");
	}
}
