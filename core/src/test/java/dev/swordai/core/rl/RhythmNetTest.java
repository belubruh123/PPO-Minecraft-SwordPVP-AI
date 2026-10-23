package dev.swordai.core.rl;

import dev.swordai.core.nn.Param;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The memory of the last seconds ({@link RhythmNet}, {@code Config.rhythmNet}): grown into a network without changing it, learned right. */
class RhythmNetTest {
	private static byte[] save(dev.swordai.core.nn.ParamSet ps) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			ps.write(out);
		}
		return bytes.toByteArray();
	}

	private static ActorNet withMemory(long seed) {
		return new ActorNet(seed, false, false, 64, 2, 256, Tactic.COUNT, OpponentReader.Z, true, true);
	}

	private static float[] vec(Random rng, int trial) {
		float[] vec = new float[ObsSpec.VEC];
		for (int i = 0; i < vec.length; i++) {
			vec[i] = rng.nextFloat() < 0.3f ? 0f : (float) rng.nextGaussian();
		}
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = trial % 2;
		return vec;
	}

	private static float[] window(Random rng) {
		float[] w = new float[OpponentReader.INPUTS];
		for (int i = 0; i < w.length; i++) {
			w[i] = rng.nextFloat();
		}
		return w;
	}

	private static float[] opp(Random rng) {
		float[] opp = new float[OpponentReader.Z];
		for (int i = 0; i < opp.length; i++) {
			opp[i] = (float) Math.tanh(rng.nextGaussian());
		}
		return opp;
	}

	@Test
	void aNetworkGivenTheMemoryChoosesAsBeforeUntilItLearns() throws IOException {
		Random rng = new Random(71);
		ActorNet small = new ActorNet(72, false, false, 64, 2, 256, Tactic.COUNT, OpponentReader.Z, true);
		for (Param p : small.params.params()) {
			for (int i = 0; i < p.w.length; i++) {
				p.w[i] += (float) rng.nextGaussian() * 0.05f;
			}
		}
		ActorNet big = withMemory(73);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(2 * (RhythmNet.L + 3), big.params.missing(), "only the memory is missing");
		ActorNet.Workspace a = small.newWorkspace();
		ActorNet.Workspace b = big.newWorkspace();
		for (int trial = 0; trial < 20; trial++) {
			float[] vec = vec(rng, trial);
			float[] opp = opp(rng);
			small.forward(a, null, null, vec, trial % Tactic.COUNT, opp);
			big.forward(b, null, null, vec, trial % Tactic.COUNT, opp, window(rng));
			assertArrayEquals(a.logits, b.logits, 1e-5f, "trial " + trial);
		}
	}

	/** The memory's weights' gradients against finite differences, with its outputs into the network made non-zero. */
	@Test
	void theMemoryLearnsTheGradientItShould() {
		Random rng = new Random(74);
		ActorNet net = withMemory(75);
		for (Param p : net.params.params()) {
			if (p.name.startsWith("rhythm.trunk") || p.name.startsWith("rhythm.crit") || p.name.startsWith("rhythm.escape")) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] = (float) rng.nextGaussian() * 0.1f;
				}
			}
		}
		ActorNet.Workspace ws = net.newWorkspace();
		float[] vec = vec(rng, 0);
		float[] opp = opp(rng);
		float[] window = window(rng);
		float[] c = new float[ActionSpace.TOTAL_LOGITS];
		for (int i = 0; i < c.length; i++) {
			c[i] = (float) rng.nextGaussian();
		}
		net.forward(ws, null, null, vec, 2, opp, window);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, null, null, vec);
		int checked = 0;
		int agreed = 0;
		for (int p = 0; p < net.params.params().size(); p++) {
			Param param = net.params.params().get(p);
			if (!param.name.startsWith("rhythm.conv")) {
				continue;
			}
			for (int n = 0; n < 6; n++) {
				int i = rng.nextInt(param.w.length);
				float keep = param.w[i];
				double eps = 1e-2;
				param.w[i] = (float) (keep + eps);
				double up = loss(net, vec, opp, window, c);
				param.w[i] = (float) (keep - eps);
				double down = loss(net, vec, opp, window, c);
				param.w[i] = keep;
				double numeric = (up - down) / (2 * eps);
				double analytic = ws.grads[p][i];
				checked++;
				if (Math.abs(numeric - analytic) <= 2e-3 + 0.05 * Math.abs(numeric)) {
					agreed++;
				}
			}
		}
		for (int p = 0; p < net.params.params().size(); p++) {
			String name = net.params.params().get(p).name;
			if (name.equals("rhythm.trunk.b") || name.equals("rhythm.crit.b") || name.equals("rhythm.escape.b")) {
				for (float g : ws.grads[p]) {
					assertEquals(0f, g, name + " stays at zero: the units it would feed have their own bias");
				}
			}
		}
		// A weight right at a unit's kink can disagree; nearly all must agree.
		assertTrue(agreed >= checked - 2, agreed + " of " + checked + " gradients agree");
		assertTrue(checked >= 24);
	}

	private static double loss(ActorNet net, float[] vec, float[] opp, float[] window, float[] c) {
		ActorNet.Workspace ws = net.newWorkspace();
		net.forward(ws, null, null, vec, 2, opp, window);
		double s = 0;
		for (int i = 0; i < c.length; i++) {
			s += c[i] * ws.logits[i];
		}
		return s;
	}

	@Test
	void whatItHandsOnDependsOnTheWholeWindowAndNothingAfterIt() {
		RhythmNet r = new RhythmNet(new dev.swordai.core.nn.ParamSet(), "r", new Random(76));
		RhythmNet.Cache a = r.newCache();
		RhythmNet.Cache b = r.newCache();
		Random rng = new Random(77);
		float[] w = window(rng);
		r.forward(w, a);
		float[] last = a.feat.clone();
		float alive = 0f;
		for (float f : last) {
			alive += Math.abs(f);
		}
		assertTrue(alive > 0f, "it hands something on");
		// Sample 1 reaches the newest sample's features (dilations 1, 2, 4, 8 with kernel 3: 30 back); sample 0 only the mean.
		assertTrue(moved(r, w, 1, last, 0) > 0f, "the newest sample's features see three seconds back");
		assertTrue(moved(r, w, 0, last, 0) == 0f, "but not the very oldest sample");
		assertTrue(moved(r, w, 0, last, RhythmNet.H) > 0f, "which the mean over the window does see");
	}

	/** How far the features from {@code from} on move when sample {@code sample} of the window changes. */
	private static float moved(RhythmNet r, float[] w, int sample, float[] before, int from) {
		float[] changed = w.clone();
		for (int ch = 0; ch < RhythmNet.C; ch++) {
			changed[sample * RhythmNet.C + ch] += 1f;
		}
		RhythmNet.Cache c = r.newCache();
		r.forward(changed, c);
		float moved = 0f;
		for (int h = from; h < from + RhythmNet.H; h++) {
			moved += Math.abs(c.feat[h] - before[h]);
		}
		return moved;
	}
}
