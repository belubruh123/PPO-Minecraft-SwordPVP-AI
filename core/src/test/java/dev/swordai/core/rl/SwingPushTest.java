package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer rule as a push on the swing ({@code Config.answerNudge}): what was added to the swing's
 * logit when a step was played is added again when it is learned from, so the policy learned about is
 * the one that played, and a network that has not changed sees ratios of exactly 1.
 */
class SwingPushTest {
	private static Obs randomObs(Random rng) {
		Obs o = new Obs();
		for (int i = 0; i < ObsSpec.VEC; i++) {
			o.vec[i] = rng.nextFloat() * 2f - 1f;
		}
		// On the ground: the swing is the trunk's.
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		return o;
	}

	@Test
	void thePushIsReplayedInTheUpdate() {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.actorLr = 0f;
		cfg.criticLr = 0f;
		cfg.adaptiveLr = false;
		cfg.minActorLr = 0f;
		cfg.klStop = 0f;
		cfg.anchorWeight = 0f;
		Learner learner = new Learner(cfg, 3L, 1);
		Random rng = new Random(4);
		int[] action = new int[ActionSpace.HEADS];
		int swung = 0;
		for (int t = 0; t < 200; t++) {
			Obs obs = randomObs(rng);
			float push = t % 3 == 0 ? 6f : t % 3 == 1 ? -6f : 0f;
			learner.setSwingBias(push);
			learner.act(obs, ActionSpace.ALL_HEADS, false, true, action);
			if (push > 0f) {
				swung += action[ActionSpace.ATTACK];
			}
			learner.complete(rng.nextFloat() - 0.5f, false, false, randomObs(rng));
		}
		// Pushed by 6 the swing is nearly always made, whatever the network's own leaning was.
		assertTrue(swung > 50, "swung " + swung + " of 67 pushed");
		UpdateStats stats = learner.update();
		assertEquals(0f, stats.approxKl, 1e-5f, "the policy that played is the one learned about");
		assertEquals(0f, stats.clipFraction, 1e-6f);
		learner.close();
	}
}
