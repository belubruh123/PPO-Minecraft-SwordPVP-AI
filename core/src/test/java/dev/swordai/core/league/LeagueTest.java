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
	void inverseDynamicsLearnsKeysAndHumanImitationClustersPlaystyles(@TempDir Path tmp) throws Exception {
		InverseDynamics idm = new InverseDynamics(11L);
		Random rng = new Random(12L);
		float[][] tk = new float[InverseDynamics.WINDOW][InverseDynamics.TICK_DIM];
		float[] feat = new float[InverseDynamics.IN_DIM];
		int[] act = new int[ActionSpace.HEADS];

		// Generate synthetic 5-tick kinematic transitions matching client-lerped Minecraft physics.
		for (int i = 0; i < 800; i++) {
			int fwd = rng.nextInt(3);
			int str = rng.nextInt(3);
			int spr = fwd == 1 && rng.nextBoolean() ? 1 : 0;
			int jmp = rng.nextInt(6) == 0 ? 1 : 0;
			int atk = rng.nextInt(5) == 0 ? 1 : 0;
			act[ActionSpace.FORWARD] = fwd; act[ActionSpace.STRAFE] = str;
			act[ActionSpace.SPRINT] = spr; act[ActionSpace.JUMP] = jmp; act[ActionSpace.ATTACK] = atk;

			float velFwd = fwd == 1 ? (spr == 1 ? 0.28f : 0.21f) : (fwd == 2 ? -0.18f : 0f);
			float velSide = str == 1 ? -0.18f : (str == 2 ? 0.18f : 0f);
			float velUp = jmp == 1 ? 0.42f : 0f;
			InverseDynamics.packTick(3.2f, 0f, 0f, 0f, true, false, 1f, false, 0f, 0f, tk[0], 0);
			InverseDynamics.packTick(3.0f, 0f, 0f, 0f, true, false, 1f, false, 0f, 0f, tk[1], 0);
			InverseDynamics.packTick(2.8f, 0f, 0f, 0f, true, false, 1f, false, 0f, 0f, tk[2], 0);
			InverseDynamics.packTick(2.8f - velFwd * 0.5f, velFwd * 0.5f, velSide * 0.5f, velUp * 0.6f, jmp == 0, spr == 1,
					atk == 1 ? 0.1f : 1f, atk == 1, 0f, 0f, tk[3], 0);
			InverseDynamics.packTick(2.8f - velFwd, velFwd, velSide, velUp * 0.8f, jmp == 0, spr == 1,
					atk == 1 ? 0.18f : 1f, atk == 1, 0f, 0f, tk[4], 0);
			InverseDynamics.packWindow(tk[0], tk[1], tk[2], tk[3], tk[4], false, feat);
			idm.addSample(feat, act);
		}
		idm.train(100, 64, 3e-3f, rng);
		assertTrue(idm.lastAccuracy() > 0.85f, "5-tick IDM should learn keys cleanly, got acc=" + idm.lastAccuracy());
		Path idmFile = tmp.resolve("idm5.bin");
		idm.save(idmFile);
		InverseDynamics idmLoaded = new InverseDynamics(99L);
		assertTrue(idmLoaded.load(idmFile));
		assertEquals(idm.trainedSteps(), idmLoaded.trainedSteps());

		// Verify LaggedRecorder produces 5-tick client-lerped samples cleanly.
		InverseDynamics.LaggedRecorder rec = new InverseDynamics.LaggedRecorder();
		rec.idm = idmLoaded;
		int before = idmLoaded.sampleCount();
		for (int t = 0; t < 8; t++) {
			rec.record(0, 0, t * 0.2, 0f, 0f, 0f, true, true, false, 0, 0, 0, 3.0, 3f - t * 0.2f, act);
		}
		assertEquals(before + 4, idmLoaded.sampleCount(), "LaggedRecorder should emit 1 sample per tick after 5-tick warmup");

		// Verify playstyle-clustered HumanImitation on real recorded fights (if present on disk).
		Path realDir = Path.of("..", "run", "swordai", "real-fights");
		if (Files.isDirectory(realDir)) {
			HumanImitation hi = new HumanImitation();
			int loaded = hi.loadDirectory(realDir);
			assertTrue(loaded > 0);
			hi.buildFromRealRounds(idm, 7L);
			assertEquals(4, hi.opponents().size(), "Should build 3 playstyle clusters + all-humans");
			float[] st = new float[HumanImitation.STATE_DIM], pr = new float[ActionSpace.TOTAL_LOGITS];
			HumanImitation.HumanPolicy.packLiveState(2.6f, 1.0f, 0.5f, true, true, true, 0.2f, 0f, 0f, 0.1f, 0f, 1f, 1f, 20, 4, 0, 1, 0f, 0f, 2.8f, st);
			for (HumanImitation.HumanPolicy hp : hi.opponents()) {
				assertTrue(hp.trainingTicks >= 1000, "Each playstyle cluster should be data-rich: " + hp.name + "=" + hp.trainingTicks);
				hp.probs(st, pr);
				float sumAtk = pr[ActionSpace.OFFSETS[ActionSpace.ATTACK]] + pr[ActionSpace.OFFSETS[ActionSpace.ATTACK] + 1];
				assertEquals(1.0f, sumAtk, 1e-4f);
			}
		}
	}

	@Test
	void humanStyleKlPullsMovementAndRespectsClickHygiene() {
		LeagueLearner learner = new LeagueLearner(128, 71L);
		SwordNet prior = new SwordNet(71L);
		LeagueLearner.copyWeights(learner.actor, prior);
		Random rng = new Random(73L);
		// Style target: strong S-tap (FORWARD=2) and ATTACK=1; verify PPO pulls FORWARD->2 while keeping ATTACK=0 when uncharged!
		float[] style = new float[ActionSpace.TOTAL_LOGITS];
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			int off = ActionSpace.OFFSETS[h], sz = ActionSpace.SIZES[h];
			for (int k = 0; k < sz; k++) style[off + k] = 1f / sz;
		}
		int fwdOff = ActionSpace.OFFSETS[ActionSpace.FORWARD], atkOff = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		style[fwdOff] = 0.02f; style[fwdOff + 1] = 0.03f; style[fwdOff + 2] = 0.95f;
		style[atkOff] = 0.05f; style[atkOff + 1] = 0.95f;

		Obs probe = randomObs(rng);
		probe.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.2f; // uncharged!
		probe.priv[ObsSpec.PV_IN_REACH] = 0f;
		SwordNet.Workspace ws = learner.actor.newWorkspace();
		float[] pBefore = new float[ActionSpace.TOTAL_LOGITS], pAfter = new float[ActionSpace.TOTAL_LOGITS];
		learner.actor.forward(ws, ws.step, probe.peri, probe.fovea, probe.vec, new float[ObsMemory.HIST], new float[ObsMemory.SEEN], new float[ObsMemory.GRU_HIDDEN], null);
		dev.swordai.core.rl.Policy.allProbs(ws.step.logits, pBefore);

		LeagueLearner.Decision dec = new LeagueLearner.Decision();
		for (int u = 0; u < 3; u++) {
			for (int t = 0; t < 128; t++) {
				Obs o = randomObs(rng);
				o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.2f; // uncharged!
				o.priv[ObsSpec.PV_IN_REACH] = 0f;
				learner.decide(o, false, 1f, dec);
				learner.record(o, dec, 0f, t % 32 == 31, null, 0f);
				learner.recordStyle(style, 0.25f);
			}
			learner.update(0f, prior, 0.01f, 0f);
		}
		learner.actor.forward(ws, ws.step, probe.peri, probe.fovea, probe.vec, new float[ObsMemory.HIST], new float[ObsMemory.SEEN], new float[ObsMemory.GRU_HIDDEN], null);
		dev.swordai.core.rl.Policy.allProbs(ws.step.logits, pAfter);
		assertTrue(pAfter[fwdOff + 2] > pBefore[fwdOff + 2] + 0.05f, "Style KL must pull FORWARD->S: " + pBefore[fwdOff + 2] + " -> " + pAfter[fwdOff + 2]);
		assertTrue(pAfter[atkOff + 1] < pBefore[atkOff + 1], "Uncharged click hygiene must prevent style KL from raising uncharged ATTACK: " + pBefore[atkOff + 1] + " -> " + pAfter[atkOff + 1]);
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

	@Test
	void stage2ToStage3aNet2NetIsExactAndLearnsThroughGru(@TempDir Path tmp) throws Exception {
		LeagueLearner stage2 = new LeagueLearner(128, 17L);
		stage2.setGruEnabled(false);
		SwordNet prior = new SwordNet(17L);
		prior.gruEnabled = false;
		LeagueLearner.copyWeights(stage2.actor, prior);
		Random rng = new Random(23L);
		LeagueLearner.Decision dec = new LeagueLearner.Decision();

		for (int u = 0; u < 2; u++) {
			for (int t = 0; t < 128; t++) {
				Obs o = randomObs(rng);
				stage2.decide(o, false, 1f, dec);
				stage2.record(o, dec, (t % 32 == 31) ? 1f : 0.01f, t % 32 == 31, null, 0f);
			}
			stage2.update(0f, prior, 0.02f, 0f);
		}
		assertEquals(0f, stage2.actor.gruWeightNorm(), 1e-7f, "Stage 2 must keep GRU output weights exactly zero");
		Path ckpt = tmp.resolve("stage2.bin");
		stage2.save(ckpt);

		LeagueLearner s2Check = new LeagueLearner(128, 77L);
		assertTrue(s2Check.load(ckpt));
		s2Check.setGruEnabled(false);

		LeagueLearner stage3a = new LeagueLearner(128, 77L);
		assertTrue(stage3a.load(ckpt));
		stage3a.setGruEnabled(true);

		// Verify Adam bornAt protection on untrained GRU tensors.
		for (Param p : stage3a.actor.params.params()) {
			if (p.name.startsWith("gru.")) assertEquals(stage3a.actor.params.adamSteps, p.bornAt, "missing bornAt on " + p.name);
		}

		// Verify bit-for-bit identical actor logits AND critic values across a multi-step sequence before update 1.
		SwordNet.Workspace ws2 = s2Check.actor.newWorkspace(), ws3 = stage3a.actor.newWorkspace();
		ObsMemory m2 = new ObsMemory(), m3 = new ObsMemory();
		float[] h2 = new float[ObsMemory.GRU_HIDDEN], h3 = new float[ObsMemory.GRU_HIDDEN];
		for (int t = 0; t < 12; t++) {
			Obs o = randomObs(rng);
			m2.pushSeen(o.vec); m3.pushSeen(o.vec);
			s2Check.actor.forward(ws2, ws2.step, o.peri, o.fovea, o.vec, m2.hist, m2.seen, h2, h2);
			stage3a.actor.forward(ws3, ws3.step, o.peri, o.fovea, o.vec, m3.hist, m3.seen, h3, h3);
			assertArrayEquals(ws2.step.logits, ws3.step.logits, 1e-5f, "Stage 3a actor logits diverged from Stage 2 at tick " + t);
			assertEquals(s2Check.bootstrapValue(o), stage3a.bootstrapValue(o), 1e-5f, "Stage 3a critic diverged from Stage 2 at tick " + t);
		}

		// Snapshot GRU cell weights, run 2 Stage 3a recurrent PPO updates, and verify GRU cell weights actually update via BPTT.
		float[] gruInBefore = null, gruHidBefore = null;
		for (Param p : stage3a.actor.params.params()) {
			if (p.name.equals("gru.in.w")) gruInBefore = p.w.clone();
			if (p.name.equals("gru.hid.w")) gruHidBefore = p.w.clone();
		}
		assertNotNull(gruInBefore); assertNotNull(gruHidBefore);
		stage3a.resetEpisode();
		for (int u = 0; u < 2; u++) {
			for (int t = 0; t < 128; t++) {
				Obs o = randomObs(rng);
				stage3a.decide(o, false, 1f, dec);
				stage3a.record(o, dec, (t % 32 == 31) ? 1f : -0.01f, t % 32 == 31, null, 0f);
			}
			LeaguePpo.Stats st = stage3a.update(0f, prior, 0.02f, 0f);
			assertTrue(Float.isFinite(st.policyLoss) && Float.isFinite(st.valueLoss));
		}
		assertTrue(stage3a.actor.gruWeightNorm() > 0f, "Stage 3a GRU output projections should grow");
		double diffIn = 0, diffHid = 0;
		for (Param p : stage3a.actor.params.params()) {
			if (p.name.equals("gru.in.w")) for (int i = 0; i < p.w.length; i++) diffIn += Math.abs(p.w[i] - gruInBefore[i]);
			if (p.name.equals("gru.hid.w")) for (int i = 0; i < p.w.length; i++) diffHid += Math.abs(p.w[i] - gruHidBefore[i]);
		}
		assertTrue(diffIn > 1e-5 && diffHid > 1e-5, "BPTT must update gru.in.w and gru.hid.w: diffIn=" + diffIn + " diffHid=" + diffHid);
	}

	@Test
	void gruSolvesDelayedCueBeyondShortWindow() {
		// 14-tick memory task: cue appears ONLY at tick 0; ticks 1..14 are identical zeros (exceeding 8-tick seen & 10-tick hist).
		final int DELAY = 14;
		final int atkOff = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		SwordNet net = new SwordNet(31L);
		net.gruEnabled = true;
		SwordNet.Workspace ws = net.newWorkspace();
		SwordNet.StepCache[] caches = new SwordNet.StepCache[DELAY + 1];
		for (int t = 0; t <= DELAY; t++) caches[t] = net.newStepCache();
		byte[] emptyPeri = new byte[ObsSpec.PERI_SIZE], emptyFovea = new byte[ObsSpec.FOVEA_SIZE];
		float[] emptyHist = new float[ObsMemory.HIST], emptySeen = new float[ObsMemory.SEEN];
		float[] zeroVec = new float[ObsSpec.VEC], posVec = new float[ObsSpec.VEC], negVec = new float[ObsSpec.VEC];
		for (int i = 0; i < 8; i++) { posVec[ObsSpec.VEC_SCALARS + i] = 1f; negVec[ObsSpec.VEC_SCALARS + i] = -1f; }
		float[] hCur = new float[ObsMemory.GRU_HIDDEN], dHNext = new float[ObsMemory.GRU_HIDDEN], dHPrev = new float[ObsMemory.GRU_HIDDEN];
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];

		// First confirm non-GRU net gives identical logits for +cue and -cue at tick DELAY (cannot solve).
		SwordNet noGru = new SwordNet(31L);
		noGru.gruEnabled = false;
		SwordNet.Workspace wsNo = noGru.newWorkspace();
		float[] logitsPos = new float[2], logitsNeg = new float[2];
		for (int sign : new int[]{1, -1}) {
			Arrays.fill(hCur, 0f);
			for (int t = 0; t <= DELAY; t++) {
				float[] v = (t == 0) ? (sign > 0 ? posVec : negVec) : zeroVec;
				noGru.forward(wsNo, caches[t], emptyPeri, emptyFovea, v, emptyHist, emptySeen, hCur, hCur);
			}
			float[] dst = sign > 0 ? logitsPos : logitsNeg;
			dst[0] = caches[DELAY].logits[atkOff]; dst[1] = caches[DELAY].logits[atkOff + 1];
		}
		assertArrayEquals(logitsPos, logitsNeg, 1e-6f, "Feed-forward net without GRU must be blind to 14-tick-old cue");

		// Train GRU net via exact BPTT on paired (+cue -> 1, -cue -> 0) sequences.
		for (int step = 0; step < 60; step++) {
			dev.swordai.core.nn.ParamSet.zeroGrads(ws.grads);
			for (int sign : new int[]{1, -1}) {
				int target = sign > 0 ? 1 : 0;
				Arrays.fill(hCur, 0f);
				for (int t = 0; t <= DELAY; t++) {
					float[] v = (t == 0) ? (sign > 0 ? posVec : negVec) : zeroVec;
					net.forward(ws, caches[t], emptyPeri, emptyFovea, v, emptyHist, emptySeen, hCur, hCur);
				}
				dev.swordai.core.rl.Policy.softmax(caches[DELAY].logits, atkOff, 2, probs);
				Arrays.fill(dHNext, 0f);
				for (int t = DELAY; t >= 0; t--) {
					Arrays.fill(ws.dLogits, 0f);
					if (t == DELAY) {
						ws.dLogits[atkOff] = probs[atkOff] - (target == 0 ? 1f : 0f);
						ws.dLogits[atkOff + 1] = probs[atkOff + 1] - (target == 1 ? 1f : 0f);
					}
					float[] v = (t == 0) ? (sign > 0 ? posVec : negVec) : zeroVec;
					net.backward(ws, caches[t], emptyPeri, emptyFovea, v, emptyHist, emptySeen, dHNext, dHPrev);
					System.arraycopy(dHPrev, 0, dHNext, 0, ObsMemory.GRU_HIDDEN);
				}
			}
			dev.swordai.core.nn.Adam.step(net.params, ws.grads, 2e-3f, 0.5f, 1.0f);
		}
		int ok = 0;
		for (int sign : new int[]{1, -1}) {
			int target = sign > 0 ? 1 : 0;
			Arrays.fill(hCur, 0f);
			for (int t = 0; t <= DELAY; t++) {
				float[] v = (t == 0) ? (sign > 0 ? posVec : negVec) : zeroVec;
				net.forward(ws, caches[t], emptyPeri, emptyFovea, v, emptyHist, emptySeen, hCur, hCur);
			}
			int pick = caches[DELAY].logits[atkOff + 1] > caches[DELAY].logits[atkOff] ? 1 : 0;
			if (pick == target) ok++;
		}
		assertEquals(2, ok, "GRU with BPTT must solve 14-tick delayed cue cleanly");
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
