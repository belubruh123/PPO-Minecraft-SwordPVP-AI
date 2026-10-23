package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kickstarting: training held near an anchor network by KL(anchor || network) ({@code Config.anchorWeight}). */
class AnchorTest {
	private static Config config() {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.adaptiveLr = false;
		cfg.klStop = 0f;
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
	void theGradientIsThatOfTheKl() {
		Random rng = new Random(5);
		float[] anchorLogits = new float[ActionSpace.TOTAL_LOGITS];
		float[] logits = new float[ActionSpace.TOTAL_LOGITS];
		for (int i = 0; i < logits.length; i++) {
			anchorLogits[i] = rng.nextFloat() * 4f - 2f;
			logits[i] = rng.nextFloat() * 4f - 2f;
		}
		float[] q = new float[ActionSpace.TOTAL_LOGITS];
		Policy.allProbs(anchorLogits, q);
		int mask = ActionSpace.ALL_HEADS & ~(1 << ActionSpace.SPRINT);
		float[] p = new float[ActionSpace.TOTAL_LOGITS];
		Policy.allProbs(logits, p);
		float[] grad = new float[ActionSpace.TOTAL_LOGITS];
		Policy.addKlGradient(mask, q, p, 1f, grad);
		for (int i = 0; i < logits.length; i++) {
			float keep = logits[i];
			logits[i] = keep + 1e-3f;
			Policy.allProbs(logits, p);
			float up = Policy.kl(mask, q, p);
			logits[i] = keep - 1e-3f;
			Policy.allProbs(logits, p);
			float down = Policy.kl(mask, q, p);
			logits[i] = keep;
			assertEquals((up - down) / 2e-3f, grad[i], 2e-3f, "logit " + i);
		}
		Policy.allProbs(anchorLogits, p);
		assertEquals(0f, Policy.kl(mask, q, p), 1e-5f);
	}

	@Test
	void theAnchorHoldsTheNetworkNearIt() {
		float without = drift(0f);
		float with = drift(1f);
		assertTrue(with < 0.5f * without, "KL from the anchor: " + with + " held, " + without + " free");
	}

	/** KL(start || network) after updates that push the attack key on, held to the start with this weight. */
	private static float drift(float weight) {
		Config cfg = config();
		cfg.anchorWeight = weight;
		ActorNet actor = new ActorNet(7L, false, false);
		ActorNet start = new ActorNet(0L, false, false);
		start.params.copyWeightsFrom(actor.params);
		CriticNet critic = new CriticNet(8L, cfg.privilegedCritic);
		Ppo ppo = new Ppo(actor, critic, cfg, 1);
		ppo.setAnchor(start);
		Random rng = new Random(9);
		ActorNet.Workspace ws = actor.newWorkspace();
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		float[] ent = new float[ActionSpace.HEADS];
		int[] act = new int[ActionSpace.HEADS];
		byte[] a = new byte[ActionSpace.HEADS];
		Obs[] states = new Obs[128];
		for (int i = 0; i < states.length; i++) {
			states[i] = randomObs(rng);
		}
		UpdateStats last = null;
		for (int u = 0; u < 15; u++) {
			Rollout r = new Rollout(states.length);
			for (Obs o : states) {
				actor.forward(ws, o.peri, o.fovea, o.vec);
				Policy.sample(ws.logits, ActionSpace.ALL_HEADS, false, 1f, rng, probs, act);
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					a[h] = (byte) act[h];
				}
				float logp = Policy.evaluate(ws.logits, ActionSpace.ALL_HEADS, a, probs, ent);
				r.add(o, act, ActionSpace.ALL_HEADS, logp, 0f, 0f, false);
				int i = r.size - 1;
				r.advantage[i] = act[ActionSpace.ATTACK] == 1 ? 1f : -1f;
				r.ret[i] = 0f;
			}
			last = ppo.update(r, rng, true);
		}
		assertFalse(Float.isNaN(last.anchorKl));
		ActorNet.Workspace sws = start.newWorkspace();
		float[] q = new float[ActionSpace.TOTAL_LOGITS];
		double kl = 0;
		for (Obs o : states) {
			start.forward(sws, o.peri, o.fovea, o.vec);
			Policy.allProbs(sws.logits, q);
			actor.forward(ws, o.peri, o.fovea, o.vec);
			Policy.allProbs(ws.logits, probs);
			kl += Policy.kl(ActionSpace.ALL_HEADS, q, probs);
		}
		ppo.close();
		return (float) (kl / states.length);
	}
}
