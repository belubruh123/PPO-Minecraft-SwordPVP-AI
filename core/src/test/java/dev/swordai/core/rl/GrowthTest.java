package dev.swordai.core.rl;

import dev.swordai.core.nn.Param;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Growing a trained network (21 October 2026) must not change what it does: the grown parts start
 * out computing nothing, so a network saved before loads into the bigger one and chooses exactly as
 * it did, and only learning moves it from there.
 */
class GrowthTest {
	private static byte[] save(dev.swordai.core.nn.ParamSet ps) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			ps.write(out);
		}
		return bytes.toByteArray();
	}

	private static void shake(dev.swordai.core.nn.ParamSet ps, Random rng) {
		for (Param p : ps.params()) {
			for (int i = 0; i < p.w.length; i++) {
				p.w[i] += (float) rng.nextGaussian() * 0.05f;
			}
		}
	}

	@Test
	void aGrownActorChoosesAsTheOneItWasGrownFrom() throws IOException {
		Random rng = new Random(41);
		ActorNet small = new ActorNet(42, true, true);
		shake(small.params, rng);
		ActorNet big = new ActorNet(43, true, true, 64, 2, 256);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(big.grown, big.params.missing(), "only the grown parts are missing");
		ActorNet.Workspace a = small.newWorkspace();
		ActorNet.Workspace b = big.newWorkspace();
		for (int trial = 0; trial < 40; trial++) {
			byte[] peri = new byte[ObsSpec.PERI_SIZE];
			byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
			rng.nextBytes(peri);
			rng.nextBytes(fovea);
			float[] vec = new float[ObsSpec.VEC];
			for (int i = 0; i < vec.length; i++) {
				vec[i] = rng.nextFloat() < 0.3f ? 0f : (float) rng.nextGaussian();
			}
			// On the ground and in the air both.
			vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = trial % 2;
			small.forward(a, peri, fovea, vec);
			big.forward(b, peri, fovea, vec);
			for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
				assertEquals(a.logits[i], b.logits[i], 1e-5f, "logit " + i + " in trial " + trial);
			}
		}
	}

	@Test
	void anActorToldATacticChoosesAsBeforeUntilItLearns() throws IOException {
		Random rng = new Random(47);
		ActorNet small = new ActorNet(48, true, true, 64, 2, 256);
		shake(small.params, rng);
		ActorNet big = new ActorNet(49, true, true, 64, 2, 256, Tactic.COUNT);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(2, big.params.missing(), "only the tactic input is missing");
		ActorNet.Workspace a = small.newWorkspace();
		ActorNet.Workspace b = big.newWorkspace();
		for (int trial = 0; trial < 20; trial++) {
			byte[] peri = new byte[ObsSpec.PERI_SIZE];
			byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
			rng.nextBytes(peri);
			rng.nextBytes(fovea);
			float[] vec = new float[ObsSpec.VEC];
			for (int i = 0; i < vec.length; i++) {
				vec[i] = rng.nextFloat() < 0.3f ? 0f : (float) rng.nextGaussian();
			}
			vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = trial % 2;
			small.forward(a, peri, fovea, vec);
			big.forward(b, peri, fovea, vec, trial % (Tactic.COUNT + 1) - 1);
			for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
				assertEquals(a.logits[i], b.logits[i], 1e-5f, "logit " + i + " in trial " + trial);
			}
		}
	}

	@Test
	void anActorHandedTheOpponentReadingChoosesAsBeforeUntilItLearns() throws IOException {
		Random rng = new Random(57);
		ActorNet small = new ActorNet(58, true, true, 64, 2, 256, Tactic.COUNT);
		shake(small.params, rng);
		ActorNet big = new ActorNet(59, true, true, 64, 2, 256, Tactic.COUNT, OpponentReader.Z);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(4, big.params.missing(), "only the reader's two inputs are missing");
		ActorNet.Workspace a = small.newWorkspace();
		ActorNet.Workspace b = big.newWorkspace();
		for (int trial = 0; trial < 20; trial++) {
			byte[] peri = new byte[ObsSpec.PERI_SIZE];
			byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
			rng.nextBytes(peri);
			rng.nextBytes(fovea);
			float[] vec = new float[ObsSpec.VEC];
			for (int i = 0; i < vec.length; i++) {
				vec[i] = rng.nextFloat() < 0.3f ? 0f : (float) rng.nextGaussian();
			}
			vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = trial % 2;
			float[] opp = new float[OpponentReader.Z];
			for (int i = 0; i < opp.length; i++) {
				opp[i] = (float) Math.tanh(rng.nextGaussian());
			}
			small.forward(a, peri, fovea, vec, trial % Tactic.COUNT);
			big.forward(b, peri, fovea, vec, trial % Tactic.COUNT, opp);
			for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
				assertEquals(a.logits[i], b.logits[i], 1e-5f, "logit " + i + " in trial " + trial);
			}
		}
	}

	@Test
	void aGrownCriticValuesAsTheOneItWasGrownFrom() throws IOException {
		Random rng = new Random(44);
		CriticNet small = new CriticNet(45, true);
		shake(small.params, rng);
		CriticNet big = new CriticNet(46, true, true, 1, 256);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(big.grown, big.params.missing());
		CriticNet.Workspace a = small.newWorkspace();
		CriticNet.Workspace b = big.newWorkspace();
		for (int trial = 0; trial < 40; trial++) {
			float[] vec = new float[ObsSpec.VEC];
			float[] priv = new float[ObsSpec.PRIV];
			for (int i = 0; i < vec.length; i++) {
				vec[i] = (float) rng.nextGaussian();
			}
			for (int i = 0; i < priv.length; i++) {
				priv[i] = (float) rng.nextGaussian();
			}
			assertEquals(small.forward(a, vec, priv), big.forward(b, vec, priv, trial % CriticNet.TASKS), 1e-5f);
		}
	}
}
