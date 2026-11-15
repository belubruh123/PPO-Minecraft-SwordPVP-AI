package dev.swordai.core.school;

import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run 109: the learned short-term memory ({@link TickMemory}) and the deeper new tower ({@link SchoolNet#DEEPER}). */
class TickMemoryTest {
	/** The networks a checkpoint of which must load and play exactly as before: run 35 (the user's), the training one, run 98. */
	private static final String[] CHECKPOINTS = {
			"D:/swordai/run/ab-keep/run35-0657.bin",
			"D:/swordai/run/best-run35/run35-0657.bin",
			"D:/swordai/run/swordai/school/checkpoint.bin",
			"D:/swordai/run/best-candidates/run98-2210.bin",
	};

	private static void randomObs(Random rng, SchoolObs obs) {
		for (int i = 0; i < ObsSpec.PERI_SIZE; i++) {
			obs.base.peri[i] = (byte) (rng.nextInt(4) == 0 ? rng.nextInt(256) : 0);
		}
		for (int i = 0; i < ObsSpec.FOVEA_SIZE; i++) {
			obs.base.fovea[i] = (byte) (rng.nextInt(4) == 0 ? rng.nextInt(256) : 0);
		}
		for (int i = 0; i < ObsSpec.VEC; i++) {
			obs.base.vec[i] = rng.nextFloat() * 2f - 1f;
		}
		for (int i = 0; i < ObsSpec.PRIV; i++) {
			obs.base.priv[i] = rng.nextFloat();
		}
		for (int i = 0; i < SchoolObs.MEM; i++) {
			obs.mem[i] = rng.nextFloat();
		}
		obs.setOpponent(rng.nextInt(Technique.COUNT), null, rng.nextInt(Specialists.KINDS));
	}

	/** A side input with a full window (every tick real) unless {@code holes}: then some ticks are before the fight. */
	private static void randomSide(Random rng, float[] side, boolean holes) {
		for (int i = 0; i < SchoolNet.WIN_AT; i++) {
			side[i] = rng.nextFloat();
		}
		if (!SchoolNet.MEMORY) {
			return;
		}
		for (int t = 0; t < SchoolNet.WIN_TICKS; t++) {
			int at = SchoolNet.WIN_AT + t * SchoolNet.WIN_PER;
			side[at] = holes && rng.nextInt(3) == 0 ? 0f : 1f;
			for (int k = 1; k < SchoolNet.WIN_PER; k++) {
				side[at + k] = rng.nextFloat() * 2f - 1f;
			}
		}
	}

	private static void wake(ParamSet ps, String prefix, Random rng, float std) {
		for (Param p : ps.params()) {
			if (p.name.startsWith(prefix)) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] = (float) rng.nextGaussian() * std;
				}
			}
		}
	}

	@Test
	void gradientsMatchFiniteDifferences() {
		ParamSet ps = new ParamSet();
		Random rng = new Random(4);
		int ticks = 6;
		int per = 5;
		int queryIn = 7;
		TickMemory m = new TickMemory(ps, "tm", ticks, per, 8, 2, 12, 10, queryIn, rng);
		// The feed-forward block's output layer starts at zero: woken, so its gradients are tested too.
		wake(ps, "tm.ffn.down", rng, 0.3f);
		TickMemory.Cache c = m.newCache();
		float[] x = new float[ticks * per];
		for (int t = 0; t < ticks; t++) {
			x[t * per] = t == 2 || t == 4 ? 0f : 1f; // two ticks before the fight, in the middle of the window
			for (int k = 1; k < per; k++) {
				x[t * per + k] = (float) rng.nextGaussian();
			}
		}
		float[] now = new float[queryIn];
		for (int i = 0; i < queryIn; i++) {
			now[i] = (float) rng.nextGaussian();
		}
		float[] coef = new float[m.out];
		for (int i = 0; i < coef.length; i++) {
			coef[i] = (float) rng.nextGaussian();
		}
		float[][] grads = ps.newGrads();
		m.forward(x, 0, now, c);
		float[] dNow = new float[queryIn];
		m.backward(x, 0, now, coef, c, dNow, grads);
		double centre = loss(m, c, x, now, coef);
		int checked = 0;
		int bad = 0;
		for (Param p : ps.params()) {
			for (int t = 0; t < Math.min(p.w.length, 12); t++) {
				int i = rng.nextInt(p.w.length);
				Diff d = diff(() -> loss(m, c, x, now, coef), p.w, i, centre);
				if (d == null) {
					continue;
				}
				checked++;
				if (!close(d.numeric, grads[p.id][i])) {
					bad++;
					System.out.println(p.name + "[" + i + "] numeric " + d.numeric + " analytic " + grads[p.id][i]);
				}
			}
		}
		for (int i = 0; i < queryIn; i++) {
			Diff d = diff(() -> loss(m, c, x, now, coef), now, i, centre);
			if (d != null) {
				checked++;
				if (!close(d.numeric, dNow[i])) {
					bad++;
					System.out.println("now[" + i + "] numeric " + d.numeric + " analytic " + dNow[i]);
				}
			}
		}
		assertTrue(checked > 100, "checked " + checked);
		assertTrue(bad <= checked / 50, bad + " of " + checked + " gradients off");
	}

	private record Diff(double numeric) {
	}

	private static Diff diff(java.util.function.DoubleSupplier f, float[] w, int i, double centre) {
		float keep = w[i];
		float eps = 1e-3f;
		w[i] = keep + eps;
		double up = f.getAsDouble();
		w[i] = keep - eps;
		double down = f.getAsDouble();
		w[i] = keep;
		double right = (up - centre) / eps;
		double left = (centre - down) / eps;
		// Across a ReLU's corner the one-sided slopes differ: not counted.
		if (Math.abs(right - left) > 0.05 * Math.max(1e-2, Math.max(Math.abs(right), Math.abs(left)))) {
			return null;
		}
		return new Diff((up - down) / (2 * eps));
	}

	private static boolean close(double numeric, double analytic) {
		return Math.abs(numeric - analytic) / Math.max(1e-2, Math.abs(numeric) + Math.abs(analytic)) < 0.02;
	}

	private static double loss(TickMemory m, TickMemory.Cache c, float[] x, float[] now, float[] coef) {
		m.forward(x, 0, now, c);
		double s = 0;
		for (int i = 0; i < coef.length; i++) {
			s += (double) coef[i] * c.z[i];
		}
		return s;
	}

	/** A tick sees only itself and the ticks before it: what came after it in the window (newer ticks) never changes its state. */
	@Test
	void noTickSeesTheFuture() {
		ParamSet ps = new ParamSet();
		Random rng = new Random(5);
		int ticks = 5;
		int per = 4;
		TickMemory m = new TickMemory(ps, "tm", ticks, per, 8, 2, 8, 6, 3, rng);
		wake(ps, "tm.ffn.down", rng, 0.3f);
		TickMemory.Cache c = m.newCache();
		float[] x = new float[ticks * per];
		for (int i = 0; i < x.length; i++) {
			x[i] = i % per == 0 ? 1f : (float) rng.nextGaussian();
		}
		float[] now = {0.3f, -0.2f, 0.5f};
		m.forward(x, 0, now, c);
		float[] oldest = new float[m.dim];
		System.arraycopy(c.h, (ticks - 1) * m.dim, oldest, 0, m.dim);
		// Change the newest tick: the oldest tick's state stays.
		x[1] += 2f;
		m.forward(x, 0, now, c);
		for (int i = 0; i < m.dim; i++) {
			assertEquals(oldest[i], c.h[(ticks - 1) * m.dim + i], 0f);
		}
	}

	/** A fresh network ignores its memory and its extra blocks: they change no logit until their layers have weights. */
	@Test
	void growthStartsSilentThenSpeaks() {
		Assumptions.assumeTrue(SchoolNet.MEMORY, "the memory is off");
		SchoolNet net = new SchoolNet(17L);
		SchoolNet.Workspace ws = net.newWorkspace();
		Random rng = new Random(23);
		SchoolObs obs = new SchoolObs();
		randomObs(rng, obs);
		float[] side = new float[SchoolNet.SIDE];
		randomSide(rng, side, true);
		float[] grown = logits(net, ws, obs, side, false);
		float[] before = logits(net, ws, obs, side, true);
		assertEquals(0f, maxDiff(grown, before), 0f);
		assertEquals(0f, net.memoryWeightNorm(), 0f);
		wake(net.params, "tm.new", rng, 0.1f);
		wake(net.params, "tm.heads", rng, 0.1f);
		assertTrue(net.memoryWeightNorm() > 1f);
		float[] speaks = logits(net, ws, obs, side, false);
		assertTrue(maxDiff(speaks, before) > 0.01f, "the memory has no say though its layers have weights");
		// And it reads the window: another window, other logits.
		float[] other = side.clone();
		randomSide(rng, other, false);
		System.arraycopy(side, 0, other, 0, SchoolNet.WIN_AT);
		assertTrue(maxDiff(logits(net, ws, obs, other, false), speaks) > 1e-3f, "the window changes nothing");
	}

	/** Each act puts the tick before (what it showed and the keys really pressed on it) in front of the window; a new fight empties it. */
	@Test
	void theWindowHoldsTheTicksBeforeNewestFirst() {
		Assumptions.assumeTrue(SchoolNet.MEMORY, "the memory is off");
		SchoolLearner l = new SchoolLearner(6L, 16, 0.99f, 0.95f, 1);
		try {
			Random rng = new Random(12);
			SchoolRollout.Step s = new SchoolRollout.Step();
			int[] act = new int[ActionSpace.HEADS];
			int ticks = 4;
			float[] health = new float[ticks];
			for (int t = 0; t < ticks; t++) {
				randomObs(rng, s.obs);
				health[t] = s.obs.base.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HEALTH];
				java.util.Arrays.fill(s.obs.base.vec, ObsSpec.VEC_LAST_ACTION, ObsSpec.VEC_LAST_ACTION + ActionSpace.TOTAL_LOGITS, 0f);
				// The keys pressed on the tick before: the jump on tick 1 (seen as the last action on tick 2).
				s.obs.base.vec[ObsSpec.VEC_LAST_ACTION + ActionSpace.OFFSETS[ActionSpace.JUMP] + (t == 2 ? 1 : 0)] = 1f;
				s.obs.base.vec[ObsSpec.VEC_LAST_ACTION + ActionSpace.OFFSETS[ActionSpace.FORWARD] + 1] = 1f;
				l.act(s, ActionSpace.ALL_HEADS, false, 1f, act);
			}
			int per = SchoolNet.WIN_PER;
			// Token 0 is the tick before the last act's (tick 2), token 1 tick 1, token 2 tick 0; older tokens are empty.
			for (int back = 0; back < ticks - 1; back++) {
				int at = SchoolNet.WIN_AT + back * per;
				assertEquals(1f, s.pred[at], 0f, "real tick, " + back);
				assertEquals(SchoolNet.winValue(health[ticks - 2 - back]), s.pred[at + 15], 0f, "health, " + back + " ticks back");
				assertEquals(back == 1 ? 1f : 0f, s.pred[at + 8], 0f, "the jump pressed, " + back + " ticks back");
				assertEquals(1f, s.pred[at + 2], 0f, "W/S, " + back);
			}
			for (int i = SchoolNet.WIN_AT + (ticks - 1) * per; i < SchoolNet.SIDE; i++) {
				assertEquals(0f, s.pred[i], 0f, "an empty older tick");
			}
			// As a buffer keeps it (the window as bytes), the side input gives exactly the logits it was acted on with.
			SchoolRollout r = new SchoolRollout(2);
			r.add(s, 0f, false);
			wake(l.actor.params, "tm.new", rng, 0.1f);
			wake(l.actor.params, "tm.heads", rng, 0.1f);
			SchoolNet.Workspace ws = l.actor.newWorkspace();
			float[] acted = logits(l.actor, ws, s.obs, s.pred, false);
			l.actor.forwardKept(ws, r.peri[0], r.fovea[0], r.vec[0], r.mem[0], r.pred[0], r.win[0]);
			l.actor.condition(ws, new byte[] {3, 2, 1, 2, 1, 0, 1});
			assertEquals(0f, maxDiff(acted, ws.logits), 0f, "kept and acted windows differ");
			l.resetIntent();
			randomObs(rng, s.obs);
			l.act(s, ActionSpace.ALL_HEADS, false, 1f, act);
			for (int i = SchoolNet.WIN_AT; i < SchoolNet.SIDE; i++) {
				assertEquals(0f, s.pred[i], 0f, "the fight before");
			}
		} finally {
			l.close();
		}
	}

	/**
	 * Every real checkpoint at hand loads into the grown network and plays exactly as the network before run 109 (the memory and the
	 * extra blocks skipped) on full windows; it saves and loads back the same; and one PPO update on it runs without NaN.
	 */
	@Test
	void realCheckpointsLoadIdenticalRoundTripAndUpdate(@TempDir Path dir) throws Exception {
		List<Path> found = new ArrayList<>();
		for (String name : CHECKPOINTS) {
			Path p = Path.of(name);
			if (Files.isRegularFile(p)) {
				found.add(p);
			}
		}
		Assumptions.assumeTrue(!found.isEmpty(), "no real checkpoint here");
		int n = 0;
		for (Path source : found) {
			Path file = dir.resolve("copy-" + (n++) + ".bin");
			Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
			SchoolLearner l = new SchoolLearner(31L, 300, 0.99f, 0.95f, 1);
			try {
				l.load(file);
				if (l.actor.params.missing() == 0) {
					// 16 Nov 01:10: a checkpoint saved by training after run 109 went live has the grown parts trained (not zero), so it
					// no longer plays as the network before them; only the files saved before run 109 are held to the identity.
					System.out.println("run 109 identity skipped, " + source + ": saved with the grown parts (trained since)");
					continue;
				}
				Random rng = new Random(41);
				SchoolNet.Workspace ws = l.actor.newWorkspace();
				SchoolObs obs = new SchoolObs();
				float[] side = new float[SchoolNet.SIDE];
				float worst = 0f;
				for (int k = 0; k < 8; k++) {
					randomObs(rng, obs);
					randomSide(rng, side, k % 2 == 1);
					float[] grown = logits(l.actor, ws, obs, side, false);
					float[] before = logits(l.actor, ws, obs, side, true);
					for (float v : grown) {
						assertTrue(Float.isFinite(v), source + ": a logit not finite");
					}
					worst = Math.max(worst, maxDiff(grown, before));
					assertTrue(Float.isFinite(l.value(obs)), source + ": value not finite");
				}
				System.out.println("run 109 identity, " + source + ": max |logit diff| " + worst + ", " + l.actor.params.missing()
						+ " new tensors, " + l.actor.params.totalSize() + " actor weights");
				assertTrue(worst < 1e-5f, source + ": max |logit diff| " + worst);

				// Round trip, with the memory awake so that its weights are tested too.
				wake(l.actor.params, "tm.new", rng, 0.05f);
				wake(l.actor.params, "tm.heads", rng, 0.05f);
				Path saved = dir.resolve("saved-" + n + ".bin");
				l.save(saved);
				SchoolLearner back = new SchoolLearner(77L, 16, 0.99f, 0.95f, 1);
				try {
					back.load(saved);
					assertEquals(0, back.actor.params.missing());
					SchoolNet.Workspace bws = back.actor.newWorkspace();
					randomObs(rng, obs);
					randomSide(rng, side, false);
					float[] a = logits(l.actor, ws, obs, side, false);
					float[] b = logits(back.actor, bws, obs, side, false);
					assertEquals(0f, maxDiff(a, b), 0f, source + ": round trip");
					assertEquals(l.value(obs), back.value(obs), 0f);
				} finally {
					back.close();
				}

				// One update: the window fills from the acts, and nothing becomes NaN.
				float normBefore = l.actor.memoryWeightNorm();
				SchoolRollout.Step s = new SchoolRollout.Step();
				int[] act = new int[ActionSpace.HEADS];
				l.resetIntent();
				for (int t = 0; t < 256; t++) {
					randomObs(rng, s.obs);
					s.policyWeight = 1f;
					l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
					randomObs(rng, s.obs);
					l.complete(rng.nextFloat() - 0.5f, t % 100 == 99, false, s.obs);
					if (t % 100 == 99) {
						l.resetIntent();
					}
				}
				SchoolPpo.Settings set = new SchoolPpo.Settings();
				set.minibatch = 64;
				set.epochs = 1;
				SchoolPpo.Stats st = l.update(set, 0f);
				assertTrue(Float.isFinite(st.policyLoss) && Float.isFinite(st.valueLoss), st.toString());
				for (Param p : l.actor.params.params()) {
					for (float v : p.w) {
						assertTrue(Float.isFinite(v), source + ": " + p.name + " not finite after an update");
					}
				}
				assertTrue(l.actor.memoryWeightNorm() != normBefore, "the memory's layers did not learn");
			} finally {
				l.close();
			}
		}
	}

	private static float[] logits(SchoolNet net, SchoolNet.Workspace ws, SchoolObs obs, float[] side, boolean before) {
		net.grownOff = before;
		try {
			net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, side);
			byte[] act = {3, 2, 1, 2, 1, 0, 1};
			net.condition(ws, act);
			return ws.logits.clone();
		} finally {
			net.grownOff = false;
		}
	}

	private static float maxDiff(float[] a, float[] b) {
		float m = 0f;
		for (int i = 0; i < a.length; i++) {
			m = Math.max(m, Math.abs(a[i] - b[i]));
		}
		return m;
	}
}
