package dev.swordai.core.school;

import dev.swordai.core.nn.Param;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ActorNet;
import dev.swordai.core.rl.BackNudge;
import dev.swordai.core.rl.JumpNudge;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchoolNetTest {
	private static void randomObs(Random rng, SchoolObs obs) {
		for (int i = 0; i < ObsSpec.PERI_SIZE; i++) {
			obs.base.peri[i] = (byte) (rng.nextInt(4) == 0 ? rng.nextInt(256) : 0);
		}
		for (int i = 0; i < ObsSpec.FOVEA_SIZE; i++) {
			obs.base.fovea[i] = (byte) (rng.nextInt(4) == 0 ? rng.nextInt(256) : 0);
		}
		do {
			for (int i = 0; i < ObsSpec.VEC; i++) {
				obs.base.vec[i] = rng.nextFloat() * 2f - 1f;
			}
			obs.base.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		} while (JumpNudge.inAir(obs.base.vec) || BackNudge.applies(obs.base.vec));
		for (int i = 0; i < SchoolObs.MEM; i++) {
			obs.mem[i] = rng.nextFloat();
		}
	}

	/** On the ground and out of a combo the installed network's heads are its trunk's: the widened one plays them exactly. */
	@Test
	void takenOverNetworkPlaysAsTheInstalledTrunk() {
		ActorNet old = new ActorNet(7L, true, true, 64, 2, 256, 0, 0, false, false);
		// Give every head a say (the heads start near zero).
		Random rng = new Random(3);
		for (Param p : old.params.params()) {
			if (p.name.startsWith("heads")) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] = (float) rng.nextGaussian() * 0.1f;
				}
			}
		}
		SchoolNet net = new SchoolNet(11L);
		long taken = net.takeOver(old.params);
		assertTrue(taken > 100_000, "took over " + taken);
		ActorNet.Workspace ows = old.newWorkspace();
		SchoolNet.Workspace ws = net.newWorkspace();
		SchoolObs obs = new SchoolObs();
		float[] pred = new float[SchoolNet.SIDE];
		int[] act = new int[ActionSpace.HEADS];
		for (int n = 0; n < 20; n++) {
			randomObs(rng, obs);
			for (int i = 0; i < pred.length; i++) {
				pred[i] = rng.nextFloat();
			}
			old.forward(ows, obs.base.peri, obs.base.fovea, obs.base.vec);
			net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, pred);
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				act[h] = rng.nextInt(ActionSpace.SIZES[h]);
			}
			net.conditionJump(ws, act);
			net.conditionAttack(ws, act);
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if (h == ActionSpace.JUMP) {
					continue; // the installed network's jump was its crit model's, taught as a teacher
				}
				for (int k = 0; k < ActionSpace.SIZES[h]; k++) {
					int o = ActionSpace.OFFSETS[h] + k;
					assertEquals(ows.logits[o], ws.logits[o], 1e-4f, "head " + h + " choice " + k);
				}
			}
		}
	}

	@Test
	void gradientsMatchFiniteDifferences() {
		SchoolNet net = new SchoolNet(5L);
		Random rng = new Random(9);
		// Wake the parts that start at zero, so their gradients are tested too.
		for (Param p : net.params.params()) {
			for (int i = 0; i < p.w.length; i++) {
				if (p.w[i] == 0f) {
					p.w[i] = (float) rng.nextGaussian() * 0.05f;
				}
			}
		}
		SchoolNet.Workspace ws = net.newWorkspace();
		SchoolObs obs = new SchoolObs();
		randomObs(rng, obs);
		float[] pred = new float[SchoolNet.SIDE];
		for (int i = 0; i < pred.length; i++) {
			pred[i] = rng.nextFloat();
		}
		byte[] act = new byte[ActionSpace.HEADS];
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			act[h] = (byte) rng.nextInt(ActionSpace.SIZES[h]);
		}
		float[] c = new float[ActionSpace.TOTAL_LOGITS];
		for (int i = 0; i < c.length; i++) {
			c[i] = (float) rng.nextGaussian();
		}
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, pred);
		net.condition(ws, act);
		for (float[] g : ws.grads) {
			java.util.Arrays.fill(g, 0f);
		}
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem);
		int checked = 0;
		int bad = 0;
		double centre = loss(net, ws, obs, pred, act, c);
		for (Param p : net.params.params()) {
			for (int t = 0; t < 4; t++) {
				int i = rng.nextInt(p.w.length);
				float keep = p.w[i];
				float eps = 1e-2f;
				p.w[i] = keep + eps;
				double up = loss(net, ws, obs, pred, act, c);
				p.w[i] = keep - eps;
				double down = loss(net, ws, obs, pred, act, c);
				p.w[i] = keep;
				// Across a ReLU's corner the two one-sided slopes differ and the central difference means nothing: not counted.
				double right = (up - centre) / eps;
				double left = (centre - down) / eps;
				if (Math.abs(right - left) > 0.1 * Math.max(1e-2, Math.max(Math.abs(right), Math.abs(left)))) {
					continue;
				}
				double numeric = (up - down) / (2 * eps);
				double analytic = ws.grads[p.id][i];
				double err = Math.abs(numeric - analytic) / Math.max(1e-2, Math.abs(numeric) + Math.abs(analytic));
				checked++;
				if (err > 0.05) {
					bad++;
					System.out.println(p.name + "[" + i + "] numeric " + numeric + " analytic " + analytic);
				}
			}
		}
		// A few may sit on a ReLU's corner.
		assertTrue(bad <= checked / 20, bad + " of " + checked + " gradients off");
	}

	/**
	 * Run 69a: a new network ignores the seen history (its layers start at zero, so a network saved before plays as it did); once
	 * those layers have weights, the history changes the logits.
	 */
	@Test
	void seenHistoryStartsSilentThenSpeaks() {
		Assumptions.assumeTrue(SchoolNet.SEEN_HISTORY, "the seen history is off");
		SchoolNet net = new SchoolNet(13L);
		SchoolNet.Workspace ws = net.newWorkspace();
		Random rng = new Random(21);
		SchoolObs obs = new SchoolObs();
		randomObs(rng, obs);
		float[] quiet = new float[SchoolNet.SIDE];
		float[] seen = new float[SchoolNet.SIDE];
		for (int i = 0; i < SchoolNet.SEEN_AT; i++) {
			quiet[i] = rng.nextFloat();
			seen[i] = quiet[i];
		}
		for (int i = SchoolNet.SEEN_AT; i < SchoolNet.SIDE; i++) {
			seen[i] = rng.nextFloat() * 2f - 1f;
		}
		assertEquals(SchoolNet.SEEN_AT + SchoolNet.SEEN, SchoolNet.SIDE, "the seen history ends the side input");
		float[] a = new float[ActionSpace.TOTAL_LOGITS];
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, quiet);
		System.arraycopy(ws.logits, 0, a, 0, a.length);
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, seen);
		for (int i = 0; i < a.length; i++) {
			assertEquals(a[i], ws.logits[i], 1e-6f, "logit " + i + " moved by a history the new network cannot read yet");
		}
		assertEquals(0f, net.seenWeightNorm(), 0f);
		for (Param p : net.params.params()) {
			if (p.name.startsWith("new.seen") || p.name.startsWith("seen.heads")) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] = (float) rng.nextGaussian() * 0.1f;
				}
			}
		}
		assertTrue(net.seenWeightNorm() > 1f);
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, seen);
		float moved = 0f;
		for (int i = 0; i < a.length; i++) {
			moved += Math.abs(a[i] - ws.logits[i]);
		}
		assertTrue(moved > 0.1f, "the history has no say though its layers have weights: " + moved);
	}

	private static double loss(SchoolNet net, SchoolNet.Workspace ws, SchoolObs obs, float[] pred, byte[] act, float[] c) {
		float[][] keep = ws.grads;
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, pred);
		net.condition(ws, act);
		double s = 0;
		for (int i = 0; i < c.length; i++) {
			s += (double) c[i] * ws.logits[i];
		}
		return s;
	}

	/** The network installed on 19 October loads as a teacher and is taken over. */
	@Test
	void installedNetworkLoads() throws Exception {
		Path v21 = Path.of(System.getProperty("user.dir")).resolve("../run/kept-v21-final-1950/checkpoint.bin").normalize();
		Assumptions.assumeTrue(Files.exists(v21), "no installed network here");
		SchoolLearner l = new SchoolLearner(1L, 16, 0.99f, 0.95f, 1);
		try {
			l.loadOldTeacher(v21, true);
			assertTrue(l.hasOldTeacher());
			SchoolObs obs = new SchoolObs();
			randomObs(new Random(2), obs);
			float[] probs = new float[ActionSpace.TOTAL_LOGITS];
			l.oldChoices(obs, probs);
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				float sum = 0f;
				for (int k = 0; k < ActionSpace.SIZES[h]; k++) {
					sum += probs[ActionSpace.OFFSETS[h] + k];
				}
				assertEquals(1f, sum, 1e-4f);
			}
		} finally {
			l.close();
		}
	}
}
