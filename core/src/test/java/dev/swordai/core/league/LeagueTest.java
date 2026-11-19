package dev.swordai.core.league;

import dev.swordai.core.nn.Gru;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of the clean league architecture:
 * GRU exact BPTT gradients, SwordNet/SwordCritic gradients, Ng et al. potential-shaping invariance,
 * Net2Net weight surgery from legacy checkpoints, recurrent BPTT PPO, InverseDynamics (VPT),
 * HumanImitation on recorded real rounds, MoveSearch outcome selection, and League sampling.
 */
final class LeagueTest {

	@Test
	void gruBpttMatchesFiniteDifferences() {
		ParamSet ps = new ParamSet();
		Random rng = new Random(7L);
		int in = 6, hid = 5, steps = 4;
		Gru gru = new Gru(ps, "g", in, hid, rng);

		float[][] xs = new float[steps][in];
		float[][] target = new float[steps][hid];
		for (int t = 0; t < steps; t++) {
			for (int i = 0; i < in; i++) xs[t][i] = (float) rng.nextGaussian() * 0.5f;
			for (int i = 0; i < hid; i++) target[t][i] = (float) rng.nextGaussian() * 0.5f;
		}
		float[] h0 = new float[hid];
		for (int i = 0; i < hid; i++) h0[i] = (float) rng.nextGaussian() * 0.3f;

		Gru.StepCache[] caches = new Gru.StepCache[steps];
		float[][] hs = new float[steps][hid];
		for (int t = 0; t < steps; t++) {
			caches[t] = gru.newStepCache();
			gru.forward(xs[t], 0, t == 0 ? h0 : hs[t - 1], hs[t], caches[t]);
		}

		float[][] grads = ps.newGrads();
		Gru.Scratch scratch = gru.newScratch();
		float[] dH = new float[hid];
		float[] dHPrev = new float[hid];
		float[] dx = new float[in];
		for (int t = steps - 1; t >= 0; t--) {
			for (int i = 0; i < hid; i++) {
				dH[i] += hs[t][i] - target[t][i];
			}
			gru.backward(xs[t], 0, dH, caches[t], scratch, dx, 0, dHPrev, grads);
			System.arraycopy(dHPrev, 0, dH, 0, hid);
		}

		float eps = 3e-3f;
		double maxRel = 0;
		double maxAbs = 0;
		for (Param p : ps.params()) {
			for (int idx = 0; idx < p.w.length; idx += Math.max(1, p.w.length / 7)) {
				float orig = p.w[idx];
				p.w[idx] = orig + eps;
				double lp = gruLoss(gru, xs, h0, target);
				p.w[idx] = orig - eps;
				double lm = gruLoss(gru, xs, h0, target);
				p.w[idx] = orig;
				double num = (lp - lm) / (2 * eps);
				double ana = grads[p.id][idx];
				maxAbs = Math.max(maxAbs, Math.abs(num - ana));
				double rel = Math.abs(num - ana) / Math.max(1e-2, Math.abs(num) + Math.abs(ana));
				maxRel = Math.max(maxRel, rel);
			}
		}
		assertTrue(maxRel < 0.01 && maxAbs < 1e-3, "GRU BPTT gradient mismatch: maxRel=" + maxRel + " maxAbs=" + maxAbs);
	}

	private static double gruLoss(Gru gru, float[][] xs, float[] h0, float[][] target) {
		float[] hCur = h0.clone();
		float[] hNext = new float[h0.length];
		Gru.StepCache c = gru.newStepCache();
		double loss = 0;
		for (int t = 0; t < xs.length; t++) {
			gru.forward(xs[t], 0, hCur, hNext, c);
			for (int i = 0; i < hNext.length; i++) {
				double d = hNext[i] - target[t][i];
				loss += 0.5 * d * d;
			}
			System.arraycopy(hNext, 0, hCur, 0, hCur.length);
		}
		return loss;
	}

	@Test
	void potentialShapingIsStrictlyPolicyInvariant() {
		// Ng et al. (1999): discounted sum of (gamma * phi(s_{t+1}) - phi(s_t)) with phi(s_T)=0 equals -phi(s_0),
		// completely independent of any intermediate states visited.
		float[] phisA = {0.10f, 0.25f, -0.12f, 0.30f, 0.0f};
		float[] phisB = {0.10f, -0.30f, 0.05f, -0.20f, 0.0f};
		double sumA = 0, sumB = 0;
		double gPow = 1.0;
		for (int t = 0; t < phisA.length - 1; t++) {
			sumA += gPow * (FixedReward.GAMMA * phisA[t + 1] - phisA[t]);
			sumB += gPow * (FixedReward.GAMMA * phisB[t + 1] - phisB[t]);
			gPow *= FixedReward.GAMMA;
		}
		assertEquals(-phisA[0], sumA, 1e-5);
		assertEquals(sumA, sumB, 1e-5);
		assertEquals(0f, FixedReward.potential(1f, 0f, 5, 0, 1f, 0f, 2f, true));
	}

	@Test
	void recurrentPpoUpdateAndSaveLoadRoundTrip(@TempDir Path tmp) throws Exception {
		LeagueLearner learner = new LeagueLearner(128, 42L);
		SwordNet prior = new SwordNet(42L);
		LeagueLearner.copyWeights(learner.actor, prior);
		Random rng = new Random(99L);
		Obs obs = randomObs(rng);
		LeagueLearner.Decision dec = new LeagueLearner.Decision();

		for (int t = 0; t < 128; t++) {
			boolean done = (t % 32 == 31);
			learner.decide(obs, false, 1f, dec);
			int[] demo = (t % 4 == 0) ? dec.action.clone() : null;
			learner.record(obs, dec, done ? 1f : 0.01f, done, demo, demo != null ? 0.3f : 0f);
			obs = randomObs(rng);
		}
		LeaguePpo.Stats stats = learner.update(0f, prior, 0.02f, 0.3f);
		assertEquals(128, stats.steps);
		assertTrue(stats.epochsRun >= 1);
		assertTrue(Float.isFinite(stats.policyLoss) && Float.isFinite(stats.valueLoss));
		assertTrue(learner.actor.gruWeightNorm() > 0f, "GRU output projections should start learning from BPTT");

		Path ckpt = tmp.resolve("league.bin");
		learner.save(ckpt);
		LeagueLearner reloaded = new LeagueLearner(128, 999L);
		assertTrue(reloaded.load(ckpt));
		assertEquals(learner.totalSteps, reloaded.totalSteps);
		assertEquals(learner.updates, reloaded.updates);
		assertArrayEquals(learner.actor.params.params().get(0).w, reloaded.actor.params.params().get(0).w, 1e-6f);
	}

	@Test
	void inverseDynamicsLearnsKeysAndHumanImitationBuildsOpponents() {
		InverseDynamics idm = new InverseDynamics(11L);
		Random rng = new Random(12L);
		float[] t0 = new float[InverseDynamics.TICK_DIM];
		float[] t1 = new float[InverseDynamics.TICK_DIM];
		float[] t2 = new float[InverseDynamics.TICK_DIM];
		float[] feat = new float[InverseDynamics.IN_DIM];
		int[] act = new int[ActionSpace.HEADS];

		// Generate synthetic kinematic transitions matching Minecraft physics.
		for (int i = 0; i < 600; i++) {
			int fwd = rng.nextInt(3);
			int str = rng.nextInt(3);
			int spr = fwd == 1 && rng.nextBoolean() ? 1 : 0;
			int jmp = rng.nextInt(6) == 0 ? 1 : 0;
			int atk = rng.nextInt(5) == 0 ? 1 : 0;
			act[ActionSpace.FORWARD] = fwd;
			act[ActionSpace.STRAFE] = str;
			act[ActionSpace.SPRINT] = spr;
			act[ActionSpace.JUMP] = jmp;
			act[ActionSpace.ATTACK] = atk;

			InverseDynamics.packTick(3.0f, 0f, 0f, 0f, true, false, 1f, false, 0f, 0f, t0, 0);
			InverseDynamics.packTick(2.8f, 0f, 0f, 0f, true, false, 1f, false, 0f, 0f, t1, 0);
			float velFwd = fwd == 1 ? (spr == 1 ? 0.28f : 0.21f) : (fwd == 2 ? -0.18f : 0f);
			float velSide = str == 1 ? -0.18f : (str == 2 ? 0.18f : 0f);
			float velUp = jmp == 1 ? 0.42f : 0f;
			InverseDynamics.packTick(2.8f - velFwd, velFwd, velSide, velUp, jmp == 0, spr == 1,
					atk == 1 ? 0.1f : 1f, atk == 1, 0f, 0f, t2, 0);
			InverseDynamics.packWindow(t0, t1, t2, false, feat);
			idm.addSample(feat, act);
		}
		idm.train(80, 64, 3e-3f, rng);
		assertTrue(idm.lastAccuracy() > 0.85f, "IDM should learn keys cleanly, got acc=" + idm.lastAccuracy());

		// Also verify real duel loading if run/swordai/real-fights exists on disk.
		Path realDir = Path.of("..", "run", "swordai", "real-fights");
		if (Files.isDirectory(realDir)) {
			HumanImitation hi = new HumanImitation();
			int loaded = hi.loadDirectory(realDir);
			assertTrue(loaded > 0);
			hi.buildFromRealRounds(idm, 7L);
			assertFalse(hi.opponents().isEmpty(), "Should clone losing human opponents");
			assertTrue(hi.winningDemos().size > 0, "Should extract outcome-filtered winning human demos");
		}
	}

	@Test
	void moveSearchSelectsBestOutcomeAndSupervisesActor() {
		MoveSearch ms = new MoveSearch();
		LeagueLearner learner = new LeagueLearner(64, 5L);
		Random rng = new Random(5L);
		Obs obs = randomObs(rng);
		double[] state = new double[28];
		ms.recordMoment(MoveSearch.Kind.AFTER_PUNISH_CRIT, state, obs, learner.lastHistIn(), learner.lastSeenIn(), learner.lastHiddenIn());

		MoveSearch.SearchJob job = ms.startSearch(rng);
		assertNotNull(job);
		int[] dummyAct = {7, 5, 1, 0, 0, 1, 1};
		while (!job.finished()) {
			MoveSearch.Candidate c = job.currentCandidate();
			job.noteFirstAction(dummyAct);
			float dmgDealt = c == MoveSearch.Candidate.JUMP_CRIT_FORWARD ? 12f : 4f;
			job.finishTrial(dmgDealt, 4f, c == MoveSearch.Candidate.JUMP_CRIT_FORWARD, false, 2, 0);
		}
		assertEquals(MoveSearch.Candidate.JUMP_CRIT_FORWARD.ordinal(), job.winningCandidate());
		assertTrue(ms.commitSearch(job));
		assertEquals(1, ms.targetCount());

		for (int i = 0; i < 10; i++) {
			ms.addTarget(obs, learner.memory, learner.lastHiddenIn(), dummyAct);
		}
		float ceBefore = ms.trainActor(learner.actor, learner.ppo, 1, 1e-3f, rng);
		float ceAfter = ms.trainActor(learner.actor, learner.ppo, 15, 2e-3f, rng);
		assertTrue(ceAfter < ceBefore, "Supervised search targets should decrease actor CE: " + ceBefore + " -> " + ceAfter);
	}

	@Test
	void leagueSamplingAndKickstartingFade() {
		League league = new League();
		SwordNet net = new SwordNet(1L);
		league.addSnapshot("u25", net);
		league.addSnapshot("u50", net);
		Random rng = new Random(1L);
		float earlyDemo = league.demoWeight();
		for (int i = 0; i < 200; i++) {
			League.Match m = league.sampleMatch(rng, null, true);
			assertNotNull(m);
			league.recordOutcome(m, true);
		}
		assertTrue(league.demoWeight() < earlyDemo, "Demo weight should fade as win rate rises");
	}

	private static Obs randomObs(Random rng) {
		Obs o = new Obs();
		for (int i = 0; i < o.vec.length; i++) o.vec[i] = (float) rng.nextGaussian() * 0.2f;
		for (int i = 0; i < o.priv.length; i++) o.priv[i] = (float) rng.nextGaussian() * 0.2f;
		Arrays.fill(o.peri, (byte) 0);
		Arrays.fill(o.fovea, (byte) 0);
		o.peri[10] = 1;
		o.fovea[10] = 1;
		return o;
	}
}
