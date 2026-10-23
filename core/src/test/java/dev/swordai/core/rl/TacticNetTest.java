package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tactics model (21 October 2026): where it starts, what it learns from, and that it is saved with the network. */
class TacticNetTest {
	private static void fill(Obs o, Random rng) {
		for (int i = 0; i < o.vec.length; i++) {
			o.vec[i] = rng.nextFloat() < 0.4f ? 0f : (float) rng.nextGaussian() * 0.5f;
		}
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_AWAY] = 0f;
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE] = 0f;
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_DIST] = 0.4f;
	}

	@Test
	void itStartsWithThePlanItWouldBeGivenByHand() {
		TacticNet net = new TacticNet(5L);
		TacticNet.Workspace ws = net.newWorkspace();
		Obs o = new Obs();
		fill(o, new Random(6L));
		net.forward(ws, o.vec);
		assertTrue(ws.probs[Tactic.COUNTER] > 0.4f, "crit back about half the time, was " + ws.probs[Tactic.COUNTER]);
		for (int k = 0; k < Tactic.COUNT; k++) {
			assertTrue(ws.probs[k] > 0.08f, Tactic.name(k) + " is tried now and then, was " + ws.probs[k]);
		}
		// An opponent that has stood at the edge of the reach most of the last seconds: space.
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE] = 0.8f;
		net.forward(ws, o.vec);
		assertTrue(ws.probs[Tactic.SPACE] > ws.probs[Tactic.COUNTER], "against one at the edge it spaces most, was " + ws.probs[Tactic.SPACE]);
	}

	@Test
	void theGradientIsTheLossesSlope() {
		TacticNet net = new TacticNet(7L);
		Random rng = new Random(8L);
		for (Param p : net.params.params()) {
			for (int i = 0; i < p.w.length; i++) {
				p.w[i] += (float) rng.nextGaussian() * 0.1f;
			}
		}
		TacticNet.Workspace ws = net.newWorkspace();
		Obs o = new Obs();
		fill(o, rng);
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE] = 0.6f;
		float[] c = {0.7f, -1.1f, 0.4f, 0.9f};
		ParamSet.zero(ws.grads);
		net.forward(ws, o.vec);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, o.vec);
		int checked = 0;
		for (Param p : net.params.params()) {
			for (int k = 0; k < Math.min(12, p.w.length); k++) {
				int i = rng.nextInt(p.w.length);
				float keep = p.w[i];
				float h = 1e-2f;
				p.w[i] = keep + h;
				net.forward(ws, o.vec);
				double up = dot(c, ws.logits);
				p.w[i] = keep - h;
				net.forward(ws, o.vec);
				double down = dot(c, ws.logits);
				p.w[i] = keep;
				double numeric = (up - down) / (2.0 * h);
				assertEquals(numeric, ws.grads[p.id][i], 2e-2 * Math.max(1.0, Math.abs(numeric)), p.name + "[" + i + "]");
				checked++;
			}
		}
		assertTrue(checked > 20);
	}

	private static double dot(float[] a, float[] b) {
		double s = 0;
		for (int i = 0; i < a.length; i++) {
			s += a[i] * b[i];
		}
		return s;
	}

	private static Rollout rollout(int n) {
		Rollout r = new Rollout(n);
		Obs o = new Obs();
		for (int i = 0; i < n; i++) {
			r.add(o, new int[ActionSpace.HEADS], 0, 0f, 0f, 0f, false);
		}
		return r;
	}

	/** The plain definition, worked forwards: a choice's discounted TD errors while it stood, then the next choice's at gamma^n lambda. */
	private static double expected(Rollout r, float lastValue, float gamma, float lambda, int s) {
		double a = 0;
		double disc = 1;
		int t = s;
		while (true) {
			float nextValue = r.done[t] ? 0f : t == r.size - 1 ? lastValue : r.value[t + 1];
			a += disc * (r.reward[t] + gamma * nextValue - r.value[t]);
			disc *= gamma;
			if (r.done[t] || t == r.size - 1) {
				return a;
			}
			t++;
			if (r.tacticStart[t]) {
				return a + disc * lambda * expected(r, lastValue, gamma, lambda, t);
			}
			if (r.tactic[t] != r.tactic[t - 1]) {
				// A choice that was not recorded: nothing of it is this one's.
				return a;
			}
		}
	}

	@Test
	void eachChoiceIsCreditedWithWhatCameWhileItStoodAndAfter() {
		Random rng = new Random(9L);
		Rollout r = rollout(30);
		int[] tactic = {1, 1, 1, 1, 2, 2, 2, 0, 0, 0, 0, 3, 3, 3, 3, 3, 1, 1, 1, 1, 2, 2, 2, 2, 2, 0, 0, 0, 1, 1};
		boolean[] start = new boolean[30];
		for (int i : new int[] {0, 4, 7, 11, 16, 25, 28}) {
			start[i] = true;
		}
		// Step 20 changes the tactic without a recorded choice; the episode ends on step 13.
		for (int i = 0; i < 30; i++) {
			r.tactic[i] = tactic[i];
			r.tacticStart[i] = start[i];
			r.value[i] = (float) rng.nextGaussian();
			r.reward[i] = (float) rng.nextGaussian() * 0.3f;
		}
		r.done[13] = true;
		float gamma = 0.9f;
		float lambda = 0.8f;
		float[] adv = TacticNet.advantages(r, 0.37f, gamma, lambda);
		for (int i = 0; i < 30; i++) {
			if (!start[i]) {
				assertTrue(Float.isNaN(adv[i]), "step " + i + " is not a choice");
				continue;
			}
			assertEquals(expected(r, 0.37f, gamma, lambda, i), adv[i], 1e-4, "choice at step " + i);
		}
		// The choice at 11 stood until the episode ended at 13: nothing of 16 on is its.
		double own = 0;
		double disc = 1;
		for (int t = 11; t <= 13; t++) {
			float nextValue = t == 13 ? 0f : r.value[t + 1];
			own += disc * (r.reward[t] + gamma * nextValue - r.value[t]);
			disc *= gamma;
		}
		assertEquals(own, adv[11], 1e-4);
	}

	@Test
	void itLearnsToChooseTheTacticThatPays() {
		TacticNet net = new TacticNet(11L);
		TacticNet.Workspace ws = net.newWorkspace();
		Random rng = new Random(12L);
		Obs o = new Obs();
		fill(o, rng);
		net.forward(ws, o.vec);
		float before = ws.probs[Tactic.BACK];
		int n = 400;
		for (int update = 0; update < 40; update++) {
			Rollout r = new Rollout(n);
			int t = -1;
			float logp = 0f;
			for (int i = 0; i < n; i++) {
				boolean choose = i % Tactic.EVERY == 0;
				if (choose) {
					t = net.choose(ws, o.vec, rng);
					logp = ws.logp;
				}
				o.tactic = t;
				// Backing off pays a little on every tick it stands; the rest pay nothing. The critic expects nothing.
				r.add(o, new int[ActionSpace.HEADS], 0, 0f, 0f, t == Tactic.BACK ? 0.1f : 0f, i % 200 == 199);
				if (choose) {
					r.markTacticChoice(logp);
				}
			}
			net.update(r, 0f, 0.98f, 0.95f, 1e-3f, 0.2f, 0.01f, 8, 0.05f, 0.5f, rng);
		}
		net.forward(ws, o.vec);
		assertTrue(ws.probs[Tactic.BACK] > 0.6f, "the tactic that pays is chosen most: " + before + " -> " + ws.probs[Tactic.BACK]);
	}

	private static Config config(boolean tactics) {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.actorBlocks = 0;
		cfg.actorVecWide = 0;
		cfg.criticBlocks = 0;
		cfg.tacticModel = tactics;
		return cfg;
	}

	@Test
	void itIsSavedWithTheNetworkAndOnlyPlayedByOneTrainedWithIt(@TempDir Path dir) throws Exception {
		Path with = dir.resolve("with.bin");
		Path without = dir.resolve("without.bin");
		Obs o = new Obs();
		fill(o, new Random(13L));
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE] = 0.5f;
		float[] trained;
		try (Learner a = new Learner(config(true), 14L, 1)) {
			assertTrue(a.hasTactics());
			// Trained to something of its own.
			Random rng = new Random(15L);
			java.lang.reflect.Field f = Learner.class.getDeclaredField("tactics");
			f.setAccessible(true);
			TacticNet net = (TacticNet) f.get(a);
			for (Param p : net.params.params()) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] += (float) rng.nextGaussian();
				}
			}
			trained = a.tacticChances(o);
			a.save(with);
		}
		try (Learner b = new Learner(config(true), 17L, 1)) {
			b.load(with);
			assertTrue(b.playsTactics(), "a network saved with its tactics model plays with it");
			float[] loaded = b.tacticChances(o);
			for (int k = 0; k < Tactic.COUNT; k++) {
				assertEquals(trained[k], loaded[k], 1e-6f);
			}
		}
		try (Learner old = new Learner(config(false), 18L, 1)) {
			old.save(without);
		}
		try (Learner c = new Learner(config(true), 19L, 1); Learner made = new Learner(config(true), 20L, 1)) {
			c.load(without);
			assertTrue(c.hasTactics(), "training one starts its tactics model afresh");
			assertFalse(c.playsTactics(), "a network of before plays as it always did");
			assertEquals(made.tacticChances(o)[Tactic.COUNTER], c.tacticChances(o)[Tactic.COUNTER], 0.05f);
		}
	}
	@Test
	void heldNearItsPlanItDoesNotDropATacticOnNoise() {
		TacticNet net = new TacticNet(21L);
		TacticNet.Workspace ws = net.newWorkspace();
		Random rng = new Random(22L);
		Obs o = new Obs();
		fill(o, rng);
		int n = 400;
		for (int update = 0; update < 40; update++) {
			Rollout r = new Rollout(n);
			int t = -1;
			float logp = 0f;
			for (int i = 0; i < n; i++) {
				boolean choose = i % Tactic.EVERY == 0;
				if (choose) {
					t = net.choose(ws, o.vec, rng);
					logp = ws.logp;
				}
				o.tactic = t;
				// Crit back pays a little less, and the rest is noise.
				float reward = (float) rng.nextGaussian() * 0.3f - (t == Tactic.COUNTER ? 0.02f : 0f);
				r.add(o, new int[ActionSpace.HEADS], 0, 0f, 0f, reward, i % 200 == 199);
				if (choose) {
					r.markTacticChoice(logp);
				}
			}
			net.update(r, 0f, 0.98f, 0.95f, 1e-3f, 0.2f, 0.01f, 0.5f, 8, 0.05f, 0.5f, rng);
		}
		net.forward(ws, o.vec);
		assertTrue(ws.probs[Tactic.COUNTER] > 0.3f, "crit back is still tried often: " + ws.probs[Tactic.COUNTER]);
	}
}
