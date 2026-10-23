package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.TakenStreak;
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

/**
 * The crit and escape models reading the trunk and the opponent reader ({@code Config.critBridge}, 23 October 2026): a
 * network saved without the bridge chooses exactly as before, the bridge learns, and nothing it learns reaches the trunk.
 */
class CritBridgeTest {
	private static byte[] save(dev.swordai.core.nn.ParamSet ps) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			ps.write(out);
		}
		return bytes.toByteArray();
	}

	private static ActorNet[] pair() throws IOException {
		Random rng = new Random(61);
		ActorNet small = new ActorNet(62, true, true, 64, 2, 256, Tactic.COUNT, OpponentReader.Z);
		for (Param p : small.params.params()) {
			for (int i = 0; i < p.w.length; i++) {
				p.w[i] += (float) rng.nextGaussian() * 0.05f;
			}
		}
		ActorNet big = new ActorNet(63, true, true, 64, 2, 256, Tactic.COUNT, OpponentReader.Z, true);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		return new ActorNet[] {small, big};
	}

	/** On the ground, in the air, and just after the second hit of a combo (the escape model's ticks). */
	private static float[] vec(Random rng, int trial) {
		float[] vec = new float[ObsSpec.VEC];
		for (int i = 0; i < vec.length; i++) {
			vec[i] = rng.nextFloat() < 0.3f ? 0f : (float) rng.nextGaussian();
		}
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = trial % 2;
		if (trial % 3 == 0) {
			vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / TakenStreak.SHOWN_MAX;
			vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 1f / BodyState.EVENT_FADE_TICKS;
		}
		return vec;
	}

	private static float[] opp(Random rng) {
		float[] opp = new float[OpponentReader.Z];
		for (int i = 0; i < opp.length; i++) {
			opp[i] = (float) Math.tanh(rng.nextGaussian());
		}
		return opp;
	}

	@Test
	void aBridgedActorChoosesAsBeforeUntilItLearns() throws IOException {
		ActorNet[] n = pair();
		assertEquals(6, n[1].params.missing(), "only the bridge is missing");
		ActorNet.Workspace a = n[0].newWorkspace();
		ActorNet.Workspace b = n[1].newWorkspace();
		Random rng = new Random(64);
		boolean escaped = false;
		for (int trial = 0; trial < 30; trial++) {
			byte[] peri = new byte[ObsSpec.PERI_SIZE];
			byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
			rng.nextBytes(peri);
			rng.nextBytes(fovea);
			float[] vec = vec(rng, trial);
			escaped |= BackNudge.applies(vec);
			float[] opp = opp(rng);
			n[0].forward(a, peri, fovea, vec, trial % Tactic.COUNT, opp);
			n[1].forward(b, peri, fovea, vec, trial % Tactic.COUNT, opp);
			assertArrayEquals(a.logits, b.logits, 1e-5f, "trial " + trial);
		}
		assertTrue(escaped, "the escape model's ticks were tried");
	}

	@Test
	void theBridgeLearnsAndTheTrunkHearsNothingOfIt() throws IOException {
		ActorNet[] n = pair();
		Random rng = new Random(65);
		int shared = n[0].params.params().size();
		float[] trunkReach = new float[2];
		for (int trial = 0; trial < 12; trial++) {
			ActorNet.Workspace a = n[0].newWorkspace();
			ActorNet.Workspace b = n[1].newWorkspace();
			byte[] peri = new byte[ObsSpec.PERI_SIZE];
			byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
			rng.nextBytes(peri);
			rng.nextBytes(fovea);
			float[] vec = vec(rng, trial);
			float[] opp = opp(rng);
			n[0].forward(a, peri, fovea, vec, 1, opp);
			n[1].forward(b, peri, fovea, vec, 1, opp);
			for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
				a.dLogits[i] = b.dLogits[i] = (float) rng.nextGaussian();
			}
			n[0].backward(a, peri, fovea, vec);
			n[1].backward(b, peri, fovea, vec);
			for (int p = 0; p < shared; p++) {
				assertArrayEquals(a.grads[p], b.grads[p], 1e-5f, n[0].params.params().get(p).name + " in trial " + trial);
			}
			for (int p = shared; p < b.grads.length; p++) {
				String name = n[1].params.params().get(p).name;
				float sum = 0f;
				for (float g : b.grads[p]) {
					sum += Math.abs(g);
				}
				if (name.startsWith("crit.trunk")) {
					trunkReach[0] += sum;
				} else if (name.startsWith("escape.trunk")) {
					trunkReach[1] += sum;
				}
			}
		}
		assertTrue(trunkReach[0] > 0f, "the crit model learns to read the trunk");
		assertTrue(trunkReach[1] > 0f, "the escape model learns to read the trunk");
	}
}
