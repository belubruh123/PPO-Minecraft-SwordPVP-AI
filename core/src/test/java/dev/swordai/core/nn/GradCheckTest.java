package dev.swordai.core.nn;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ActorNet;
import dev.swordai.core.rl.CriticNet;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.OpponentReader;
import dev.swordai.core.rl.Policy;
import dev.swordai.core.rl.Tactic;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Compares every hand-written backward pass against finite differences. */
class GradCheckTest {
	private static final float EPS = 5e-3f;

	/**
	 * Perturbs a sample of weights in every tensor and checks the analytic gradient. A ReLU that
	 * flips inside the perturbation makes the numeric estimate wrong, so a small share of misses is
	 * tolerated; a real bug fails far more than that.
	 */
	private static void check(ParamSet ps, float[][] grads, DoubleSupplier loss, long seed, int perTensor) {
		Random rng = new Random(seed);
		int checked = 0;
		int bad = 0;
		StringBuilder firstBad = new StringBuilder();
		for (Param p : ps.params()) {
			for (int k = 0; k < perTensor; k++) {
				int i = rng.nextInt(p.w.length);
				float orig = p.w[i];
				p.w[i] = orig + EPS;
				double up = loss.getAsDouble();
				p.w[i] = orig - EPS;
				double down = loss.getAsDouble();
				p.w[i] = orig;
				double numeric = (up - down) / (2.0 * EPS);
				double analytic = grads[p.id][i];
				double tol = 2e-3 + 2e-2 * (Math.abs(numeric) + Math.abs(analytic));
				checked++;
				if (Math.abs(numeric - analytic) > tol) {
					bad++;
					if (firstBad.length() == 0) {
						firstBad.append(p.name).append('[').append(i).append("] numeric=").append(numeric)
								.append(" analytic=").append(analytic);
					}
				}
			}
		}
		assertTrue(checked > 0);
		if (bad > Math.max(1, checked / 50)) {
			fail(bad + " of " + checked + " gradients disagree, first: " + firstBad);
		}
	}

	private static float[] randomFloats(Random rng, int n, float zeroFraction) {
		float[] a = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = rng.nextFloat() < zeroFraction ? 0f : (float) rng.nextGaussian();
		}
		return a;
	}

	private static byte[] randomBytes(Random rng, int n, float zeroFraction) {
		byte[] a = new byte[n];
		for (int i = 0; i < n; i++) {
			a[i] = rng.nextFloat() < zeroFraction ? 0 : (byte) rng.nextInt(256);
		}
		return a;
	}

	private static double dot(float[] a, float[] b) {
		double s = 0;
		for (int i = 0; i < a.length; i++) {
			s += (double) a[i] * b[i];
		}
		return s;
	}

	@Test
	void dense() {
		Random rng = new Random(1);
		ParamSet ps = new ParamSet();
		Dense layer = new Dense(ps, "d", 13, 7, rng, 1f);
		for (int i = 0; i < layer.bias.w.length; i++) {
			layer.bias.w[i] = (float) rng.nextGaussian();
		}
		float[] x = randomFloats(rng, 13 + 2, 0.3f);
		float[] c = randomFloats(rng, 7 + 1, 0f);
		float[] y = new float[7 + 1];
		float[] dx = new float[13 + 2];
		float[][] grads = ps.newGrads();

		layer.forward(x, 2, y, 1);
		layer.backward(x, 2, c, 1, dx, 2, grads);
		check(ps, grads, () -> {
			layer.forward(x, 2, y, 1);
			double s = 0;
			for (int o = 0; o < 7; o++) {
				s += (double) c[1 + o] * y[1 + o];
			}
			return s;
		}, 2, 40);

		// Input gradient, checked the same way by perturbing x directly.
		for (int i = 0; i < 13; i++) {
			float orig = x[2 + i];
			x[2 + i] = orig + EPS;
			layer.forward(x, 2, y, 1);
			double up = 0;
			for (int o = 0; o < 7; o++) {
				up += (double) c[1 + o] * y[1 + o];
			}
			x[2 + i] = orig - EPS;
			layer.forward(x, 2, y, 1);
			double down = 0;
			for (int o = 0; o < 7; o++) {
				down += (double) c[1 + o] * y[1 + o];
			}
			x[2 + i] = orig;
			double numeric = (up - down) / (2.0 * EPS);
			assertTrue(Math.abs(numeric - dx[2 + i]) < 2e-3 + 2e-2 * Math.abs(numeric), "dx[" + i + "]");
		}
	}

	@Test
	void conv2dFloatInput() {
		Random rng = new Random(3);
		ParamSet ps = new ParamSet();
		Conv2d conv = new Conv2d(ps, "c", 9, 11, 3, 3, 2, 5, rng);
		for (int i = 0; i < conv.bias.w.length; i++) {
			conv.bias.w[i] = (float) rng.nextGaussian();
		}
		float[] in = randomFloats(rng, conv.inSize(), 0.4f);
		float[] c = randomFloats(rng, conv.outSize(), 0f);
		float[] out = new float[conv.outSize()];
		float[] dIn = new float[conv.inSize()];
		float[][] grads = ps.newGrads();

		conv.forward(in, out);
		conv.backward(in, c, dIn, grads);
		check(ps, grads, () -> {
			conv.forward(in, out);
			return dot(c, out);
		}, 4, 60);

		Random pick = new Random(5);
		for (int k = 0; k < 40; k++) {
			int i = pick.nextInt(in.length);
			float orig = in[i];
			in[i] = orig + EPS;
			conv.forward(in, out);
			double up = dot(c, out);
			in[i] = orig - EPS;
			conv.forward(in, out);
			double down = dot(c, out);
			in[i] = orig;
			double numeric = (up - down) / (2.0 * EPS);
			assertTrue(Math.abs(numeric - dIn[i]) < 2e-3 + 2e-2 * Math.abs(numeric), "dIn[" + i + "]");
		}
	}

	@Test
	void conv2dByteInput() {
		Random rng = new Random(6);
		ParamSet ps = new ParamSet();
		Conv2d conv = new Conv2d(ps, "c", 10, 8, 2, 4, 2, 6, rng);
		byte[] in = randomBytes(rng, conv.inSize(), 0.5f);
		float[] c = randomFloats(rng, conv.outSize(), 0f);
		float[] out = new float[conv.outSize()];
		float[][] grads = ps.newGrads();

		conv.forwardBytes(in, out);
		conv.backwardBytes(in, c, grads);
		check(ps, grads, () -> {
			conv.forwardBytes(in, out);
			return dot(c, out);
		}, 7, 60);
	}

	@Test
	void actorNetEndToEnd() {
		Random rng = new Random(8);
		ActorNet net = new ActorNet(9, true, true);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		// On the ground: every key but the jump is the trunk's.
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec);
			return dot(c, ws.logits);
		}, 10, 25);
	}

	/**
	 * In the air the sprint key, W/S and the swing are the crit model's. The trunk's view of whether
	 * there is a hit goes into the swing as something given, not as something it learns from; here the
	 * trunk sees a hit, so it holds nothing back and has no part in the swing at all.
	 */
	@Test
	void actorNetInTheAir() {
		Random rng = new Random(21);
		ActorNet net = new ActorNet(22, true, true);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = -0.2f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 0f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 0f;
		int attack = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		for (Param p : net.params.params()) {
			if (p.name.equals("heads.b")) {
				p.w[attack + 1] = 6f;
			}
		}
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec);
			return dot(c, ws.logits);
		}, 23, 25);
	}

	@Test
	void actorNetWithoutImageBranches() {
		Random rng = new Random(11);
		ActorNet net = new ActorNet(12, false, false);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = new byte[ObsSpec.PERI_SIZE];
		byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		// The image branches are off, so their tensors must receive exactly zero gradient.
		for (Param p : net.params.params()) {
			if (p.name.startsWith("peri.") || p.name.startsWith("fovea.")) {
				for (float g : ws.grads[p.id]) {
					assertTrue(g == 0f, p.name + " should be untouched");
				}
			}
		}
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec);
			return dot(c, ws.logits);
		}, 13, 25);
	}

	@Test
	void criticNet() {
		Random rng = new Random(14);
		CriticNet net = new CriticNet(15, true);
		CriticNet.Workspace ws = net.newWorkspace();
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.2f);
		float[] priv = randomFloats(rng, ObsSpec.PRIV, 0.2f);

		net.forward(ws, vec, priv);
		net.backward(ws, 1.7f);
		check(net.params, ws.grads, () -> 1.7 * net.forward(ws, vec, priv), 16, 40);
	}

	@Test
	void policyGradient() {
		Random rng = new Random(17);
		float[] logits = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);
		// Pitch and jump are locked: their logits must get no gradient.
		int mask = ActionSpace.ALL_HEADS & ~ActionSpace.mask(ActionSpace.PITCH, ActionSpace.JUMP);
		byte[] action = new byte[ActionSpace.HEADS];
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			action[h] = (byte) rng.nextInt(ActionSpace.SIZES[h]);
		}
		float[] entCoef = {0.3f, 0.3f, 0.2f, 0.1f, 0.4f, 0.4f, 0.25f};
		float dLogp = -0.8f;
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		float[] entropy = new float[ActionSpace.HEADS];
		float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];

		Policy.evaluate(logits, mask, action, probs, entropy);
		Policy.gradient(mask, action, probs, entropy, dLogp, entCoef, dLogits);

		for (int i = 0; i < logits.length; i++) {
			float orig = logits[i];
			logits[i] = orig + 1e-3f;
			double up = policyLoss(logits, mask, action, dLogp, entCoef);
			logits[i] = orig - 1e-3f;
			double down = policyLoss(logits, mask, action, dLogp, entCoef);
			logits[i] = orig;
			double numeric = (up - down) / 2e-3;
			assertTrue(Math.abs(numeric - dLogits[i]) < 2e-3 + 2e-2 * Math.abs(numeric),
					"logit " + i + " numeric=" + numeric + " analytic=" + dLogits[i]);
		}
		int pitchOff = ActionSpace.OFFSETS[ActionSpace.PITCH];
		for (int i = 0; i < ActionSpace.SIZES[ActionSpace.PITCH]; i++) {
			assertTrue(dLogits[pitchOff + i] == 0f);
		}
	}

	/** Gives the parts that start at zero (a block's output layer, the wide units' output, the critic's fight kind) weights of their own, so their gradients are checked where they are not trivially zero. */
	private static void wakeGrown(ParamSet ps, Random rng) {
		for (Param p : ps.params()) {
			if (p.name.contains(".down.") || p.name.startsWith("vec.wide.out") || p.name.startsWith("critic.task") || p.name.startsWith("tactic.in") || p.name.startsWith("opp.")
					|| p.name.contains(".ln.")) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] += (float) rng.nextGaussian() * 0.1f;
				}
			}
		}
	}

	@Test
	void resBlock() {
		Random rng = new Random(31);
		ParamSet ps = new ParamSet();
		ResBlock block = new ResBlock(ps, "b", 9, 14, rng);
		wakeGrown(ps, rng);
		ResBlock.Cache cache = block.newCache();
		float[] x0 = randomFloats(rng, 9 + 3, 0.2f);
		float[] c = randomFloats(rng, 9, 0f);
		float[] x = new float[x0.length];
		float[] dx = new float[x0.length];
		float[][] grads = ps.newGrads();

		System.arraycopy(x0, 0, x, 0, x.length);
		block.forward(x, 3, cache);
		System.arraycopy(c, 0, dx, 3, 9);
		block.backward(dx, 3, cache, grads);
		DoubleSupplier loss = () -> {
			System.arraycopy(x0, 0, x, 0, x.length);
			block.forward(x, 3, cache);
			double s = 0;
			for (int i = 0; i < 9; i++) {
				s += (double) c[i] * x[3 + i];
			}
			return s;
		};
		check(ps, grads, loss, 32, 40);
		// And the gradient with respect to the input, skip path and layer norm both.
		for (int i = 0; i < 9; i++) {
			float orig = x0[3 + i];
			x0[3 + i] = orig + 1e-3f;
			double up = loss.getAsDouble();
			x0[3 + i] = orig - 1e-3f;
			double down = loss.getAsDouble();
			x0[3 + i] = orig;
			double numeric = (up - down) / 2e-3;
			assertTrue(Math.abs(numeric - dx[3 + i]) < 3e-3 + 3e-2 * Math.abs(numeric),
					"input " + i + " numeric=" + numeric + " analytic=" + dx[3 + i]);
		}
	}

	@Test
	void actorNetGrown() {
		Random rng = new Random(33);
		ActorNet net = new ActorNet(34, true, true, 16, 2, 24);
		wakeGrown(net.params, rng);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec);
			return dot(c, ws.logits);
		}, 35, 25);
	}

	@Test
	void actorNetToldATactic() {
		Random rng = new Random(51);
		ActorNet net = new ActorNet(52, true, true, 16, 1, 24, Tactic.COUNT);
		wakeGrown(net.params, rng);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec, Tactic.SPACE);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec, Tactic.SPACE);
			return dot(c, ws.logits);
		}, 53, 25);
	}

	@Test
	void actorNetReadingTheOpponent() {
		Random rng = new Random(61);
		ActorNet net = new ActorNet(62, true, true, 16, 1, 24, Tactic.COUNT, OpponentReader.Z);
		wakeGrown(net.params, rng);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] opp = new float[OpponentReader.Z];
		for (int i = 0; i < opp.length; i++) {
			opp[i] = (float) Math.tanh(rng.nextGaussian());
		}
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec, Tactic.BACK, opp);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec, Tactic.BACK, opp);
			return dot(c, ws.logits);
		}, 63, 25);
	}

	@Test
	void criticNetGrown() {
		Random rng = new Random(36);
		CriticNet net = new CriticNet(37, true, true, 2, 20);
		wakeGrown(net.params, rng);
		CriticNet.Workspace ws = net.newWorkspace();
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.2f);
		float[] priv = randomFloats(rng, ObsSpec.PRIV, 0.2f);

		net.forward(ws, vec, priv, 5);
		net.backward(ws, 1.3f);
		check(net.params, ws.grads, () -> 1.3 * net.forward(ws, vec, priv, 5), 38, 40);
	}

	private static double policyLoss(float[] logits, int mask, byte[] action, float dLogp, float[] entCoef) {
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		float[] entropy = new float[ActionSpace.HEADS];
		double logp = Policy.evaluate(logits, mask, action, probs, entropy);
		double loss = dLogp * logp;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			loss -= entCoef[h] * entropy[h];
		}
		return loss;
	}
}
