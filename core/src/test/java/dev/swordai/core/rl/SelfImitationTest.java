package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Self-imitation: the steps that went far better than expected, kept and made likelier ({@link SilBuffer}). */
class SelfImitationTest {
	private static Config config() {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		return cfg;
	}

	private static Obs randomObs(Random rng) {
		Obs o = new Obs();
		for (int i = 0; i < ObsSpec.VEC; i++) {
			o.vec[i] = rng.nextFloat() * 2f - 1f;
		}
		return o;
	}

	@Test
	void onlyTheStepsFarAboveTheRestAreKept() {
		Rollout r = new Rollout(100);
		Random rng = new Random(1);
		int[] act = new int[ActionSpace.HEADS];
		for (int i = 0; i < 100; i++) {
			r.add(randomObs(rng), act, ActionSpace.ALL_HEADS, 0f, 0f, 0f, false);
			r.advantage[i] = i % 33 == 5 ? 5f : 0.01f * (i % 7);
			r.ret[i] = r.advantage[i];
		}
		SilBuffer buf = new SilBuffer(50);
		assertEquals(3, buf.addFrom(r, 1.5f));
		assertEquals(3, buf.size);
	}

	@Test
	void aKeptStepThatWentBetterThanExpectedIsMadeLikelier() {
		Config cfg = config();
		ActorNet actor = new ActorNet(7L, false, false);
		CriticNet critic = new CriticNet(8L, cfg.privilegedCritic);
		Ppo ppo = new Ppo(actor, critic, cfg, 1);
		Random rng = new Random(2);
		Obs obs = randomObs(rng);
		int[] act = new int[ActionSpace.HEADS];
		// An action the untrained policy is unlikely to pick: the last choice of every head.
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			act[h] = ActionSpace.SIZES[h] - 1;
		}
		byte[] a = new byte[ActionSpace.HEADS];
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			a[h] = (byte) act[h];
		}
		Rollout r = new Rollout(20);
		for (int i = 0; i < 20; i++) {
			r.add(obs, act, ActionSpace.ALL_HEADS, 0f, 0f, 0f, false);
			r.advantage[i] = i == 0 ? 3f : 0f;
			r.ret[i] = i == 0 ? 3f : 0f;
		}
		SilBuffer buf = new SilBuffer(10);
		assertEquals(1, buf.addFrom(r, 1.5f));
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		float[] ent = new float[ActionSpace.HEADS];
		ActorNet.Workspace ws = actor.newWorkspace();
		actor.forward(ws, obs.peri, obs.fovea, obs.vec);
		float before = Policy.evaluate(ws.logits, ActionSpace.ALL_HEADS, a, probs, ent);
		Ppo.SilStats s = ppo.selfImitate(buf, rng, 100, 1f, 0.01f, 1f);
		assertEquals(1, s.stillBetter);
		actor.forward(ws, obs.peri, obs.fovea, obs.vec);
		float after = Policy.evaluate(ws.logits, ActionSpace.ALL_HEADS, a, probs, ent);
		assertTrue(after > before + 0.05f, "log-probability " + before + " -> " + after);
		ppo.close();
	}

	@Test
	void aStepNoBetterThanExpectedTeachesNothing() {
		Config cfg = config();
		ActorNet actor = new ActorNet(7L, false, false);
		CriticNet critic = new CriticNet(8L, cfg.privilegedCritic);
		Ppo ppo = new Ppo(actor, critic, cfg, 1);
		Random rng = new Random(3);
		Rollout r = new Rollout(20);
		int[] act = new int[ActionSpace.HEADS];
		for (int i = 0; i < 20; i++) {
			r.add(randomObs(rng), act, ActionSpace.ALL_HEADS, 0f, 0f, 0f, false);
			r.advantage[i] = i == 0 ? 3f : 0f;
			// Its return far below anything the critic expects.
			r.ret[i] = -1000f;
		}
		SilBuffer buf = new SilBuffer(10);
		buf.addFrom(r, 1.5f);
		Ppo.SilStats s = ppo.selfImitate(buf, rng, 5, 1f, 0.01f, 1f);
		assertEquals(0, s.stillBetter);
		assertEquals(0, s.samples);
		ppo.close();
	}
}
