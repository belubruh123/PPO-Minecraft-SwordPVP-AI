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
			HumanImitation.HumanPolicy.packLiveState(2.2f, 0.96f, 0.5f, false, true, false, 0.1f, 0f, -0.1f, 0.1f, 0f, 0.8f, 0.8f, 12, 6, 1, 0, 0f, 0f, 2.4f, st);
			for (HumanImitation.HumanPolicy hp : hi.opponents()) {
				assertTrue(hp.trainingTicks >= 1000, "Each playstyle cluster should be data-rich: " + hp.name + "=" + hp.trainingTicks);
				hp.probs(st, pr);
				float pAtk = pr[ActionSpace.OFFSETS[ActionSpace.ATTACK] + 1];
				float sumAtk = pr[ActionSpace.OFFSETS[ActionSpace.ATTACK]] + pAtk;
				assertEquals(1.0f, sumAtk, 1e-4f);
				assertTrue(pAtk > 0.15f, "Charged falling in-reach human clone must swing: " + hp.name + " pAtk=" + pAtk);
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
	void sightTrackerSaturatesQuicklyAfterPassThrough() {
		dev.swordai.core.rl.SightTracker st = new dev.swordai.core.rl.SightTracker();
		st.resetJustLost(0);
		float[] empty = new float[ObsSpec.STATS], vec = new float[ObsSpec.VEC];
		st.update(empty, 0f, 0f, 0f, 0f);
		st.write(vec);
		assertEquals(0.125f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_SEEN], 1e-4f);
		assertEquals(0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE], 1e-4f);
		for (int t = 0; t < 7; t++) st.update(empty, 0f, 0f, 0f, 0f);
		st.write(vec);
		assertEquals(1.0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_SEEN], 1e-4f);
	}

	@Test
	void moveSearchSelectsBestOutcomePreservesAimAndReleasesWMidAir() {
		// Verify JUMP_CRIT_FORWARD releases W mid-air so vanilla ClientSprint clears, and only attacks when falling && canHit.
		int[] a = new int[ActionSpace.HEADS];
		a[ActionSpace.YAW] = 7; a[ActionSpace.PITCH] = 5; a[ActionSpace.FORWARD] = 1; a[ActionSpace.STRAFE] = 2; a[ActionSpace.SPRINT] = 1; a[ActionSpace.ATTACK] = 1;
		MoveSearch.Candidate.JUMP_CRIT_FORWARD.apply(a, 0, true, false);
		assertEquals(1, a[ActionSpace.FORWARD]); assertEquals(0, a[ActionSpace.SPRINT]);
		assertEquals(1, a[ActionSpace.JUMP]); assertEquals(0, a[ActionSpace.ATTACK]);
		assertEquals(7, a[ActionSpace.YAW]); assertEquals(5, a[ActionSpace.PITCH]);
		MoveSearch.Candidate.JUMP_CRIT_FORWARD.apply(a, 2, true, false);
		assertEquals(0, a[ActionSpace.FORWARD], "Mid-air tick must release W so ClientSprint drops sprint");
		assertEquals(0, a[ActionSpace.JUMP]); assertEquals(0, a[ActionSpace.ATTACK]);
		MoveSearch.Candidate.JUMP_CRIT_FORWARD.apply(a, 6, true, true);
		assertEquals(0, a[ActionSpace.FORWARD]); assertEquals(1, a[ActionSpace.ATTACK], "Falling + canHit must swing crit");
		MoveSearch.Candidate.JUMP_CRIT_FORWARD.apply(a, 6, false, true);
		assertEquals(0, a[ActionSpace.ATTACK], "Out-of-reach or uncharged must not swing");

		// Verify SMP AXE_BREAK_SWORD_CRIT dynamically switches to arbitrary randomized axe/sword slots!
		MoveSearch.Candidate.AXE_BREAK_SWORD_CRIT.apply(a, 0, true, false, 6, 3, 8, true, true);
		assertEquals(4, a[ActionSpace.HOTBAR_SLOT], "Tick 0 should select axe slot 3 -> key 4");
		MoveSearch.Candidate.AXE_BREAK_SWORD_CRIT.apply(a, 3, true, false, 6, 3, 8, true, false);
		assertEquals(7, a[ActionSpace.HOTBAR_SLOT], "After shield breaks, switch to sword slot 6 -> key 7");

		MoveSearch ms = new MoveSearch();
		LeagueLearner learner = new LeagueLearner(64, 5L);
		Random rng = new Random(5L);
		Obs obs = randomObs(rng);
		double[] state = new double[28];
		ms.recordMoment(MoveSearch.Kind.AFTER_PUNISH_CRIT, false, state, obs, learner.memory.hist, learner.memory.seen, learner.memory.hidden);

		MoveSearch.SearchJob job = ms.startSearch(rng, 2, learner.actor);
		assertNotNull(job);
		assertEquals(2, job.lag);
		int[] dummyAct = new int[ActionSpace.HEADS];
		dummyAct[ActionSpace.YAW] = 7; dummyAct[ActionSpace.PITCH] = 5; dummyAct[ActionSpace.FORWARD] = 1; dummyAct[ActionSpace.JUMP] = 1; dummyAct[ActionSpace.ATTACK] = 1;
		while (!job.finished()) {
			MoveSearch.Candidate c = job.currentCandidate();
			job.noteFirstStep(obs, learner.lastHistIn(), learner.lastSeenIn(), learner.lastHiddenIn(), dummyAct);
			float dmgDealt = c == MoveSearch.Candidate.JUMP_CRIT_FORWARD ? 12f : 4f;
			job.finishTrial(dmgDealt, 4f, c == MoveSearch.Candidate.JUMP_CRIT_FORWARD, false);
		}
		assertEquals(MoveSearch.Candidate.JUMP_CRIT_FORWARD.ordinal(), job.winningCandidate());
		assertTrue(ms.commitSearch(job));
		assertEquals(1, ms.targetCount());

		for (int i = 0; i < 10; i++) {
			MoveSearch.SearchJob j2 = ms.startSearch(rng, 0, learner.actor);
			while (!j2.finished()) {
				MoveSearch.Candidate c = j2.currentCandidate();
				j2.noteFirstStep(obs, learner.lastHistIn(), learner.lastSeenIn(), learner.lastHiddenIn(), dummyAct);
				j2.finishTrial(c == MoveSearch.Candidate.JUMP_CRIT_FORWARD ? 14f : 2f, 4f, false, false);
			}
			ms.commitSearch(j2);
		}
		float ceBefore = ms.trainActor(learner.actor, learner.ppo, 1, 1e-3f, rng);
		float ceAfter = ms.trainActor(learner.actor, learner.ppo, 15, 2e-3f, rng);
		assertTrue(ceAfter < ceBefore, "Supervised search targets should decrease actor CE: " + ceBefore + " -> " + ceAfter);
		assertFalse(learner.hasNonFiniteWeights());
	}

	@Test
	void leaguePfspPoolPersistsAndReloadsAcrossRestarts(@TempDir Path tmp) throws Exception {
		League league = new League();
		SwordNet n1 = new SwordNet(101L); n1.gruEnabled = true;
		SwordNet n2 = new SwordNet(202L); n2.gruEnabled = false;
		int idx0 = league.addSnapshot("exp0", n1);
		int idx1 = league.addSnapshot("hum-crit-g1", n2);
		assertEquals(0, idx0); assertEquals(1, idx1);
		Path poolDir = tmp.resolve("league-pool");
		league.saveSnapshotSlot(poolDir, idx0);
		league.saveSnapshotSlot(poolDir, idx1);

		League reloaded = new League();
		assertEquals(2, reloaded.loadPool(poolDir));
		assertEquals(2, reloaded.snapshotCount());
		SwordNet picked = reloaded.randomSnapshot(new Random(3L));
		assertNotNull(picked);
		// Verify exact weights persisted via actor-only save/load.
		SwordNet checkN1 = LeagueLearner.loadActorOnly(poolDir.resolve("snap-00.bin"));
		assertArrayEquals(n1.params.params().get(0).w, checkN1.params.params().get(0).w, 1e-6f);
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
					net.backward(ws, caches[t], emptyPeri, emptyFovea, v, null, null, emptyHist, emptySeen, dHNext, dHPrev, null);
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

	@Test
	void moeBlockAndSlotEncoderMatchFiniteDifferences() {
		Random rng = new Random(1337L);
		// 1. Verify MoEBlock exact analytical gradients (including router softmax + Switch-Transformer balance grad)
		ParamSet mps = new ParamSet();
		dev.swordai.core.nn.MoEBlock moe = new dev.swordai.core.nn.MoEBlock(mps, "moe", 8, 10, 12, rng);
		// Perturb down weights slightly off zero so expert branch gradients are non-trivial!
		for (int e = 0; e < dev.swordai.core.nn.MoEBlock.EXPERTS; e++) {
			for (int i = 0; i < moe.down[e].weight.w.length; i++) moe.down[e].weight.w[i] = (float) rng.nextGaussian() * 0.25f;
		}
		float[] x = new float[8], rIn = new float[12], target = new float[8], out = new float[8], dOut = new float[8], dX = new float[8], dRIn = new float[12];
		float[] dGateBal = {0.03f, -0.02f, 0.01f, -0.02f};
		for (int i = 0; i < x.length; i++) { x[i] = (float) rng.nextGaussian() * 0.5f; target[i] = (float) rng.nextGaussian() * 0.5f; }
		for (int i = 0; i < rIn.length; i++) rIn[i] = (float) rng.nextGaussian() * 0.5f;
		dev.swordai.core.nn.MoEBlock.Cache mc = moe.newCache();
		dev.swordai.core.nn.MoEBlock.Scratch ms = moe.newScratch();
		float[][] mgr = mps.newGrads();
		moe.forward(x, rIn, out, mc);
		for (int i = 0; i < 8; i++) dOut[i] = out[i] - target[i];
		moe.backward(rIn, dOut, dGateBal, mc, ms, dX, dRIn, mgr);

		float eps = 2e-3f;
		double maxRel = 0;
		for (Param p : mps.params()) {
			for (int idx = 0; idx < p.w.length; idx += Math.max(1, p.w.length / 5)) {
				float orig = p.w[idx];
				p.w[idx] = orig + eps; double lp = moeLoss(moe, x, rIn, target, dGateBal);
				p.w[idx] = orig - eps; double lm = moeLoss(moe, x, rIn, target, dGateBal);
				p.w[idx] = orig;
				double num = (lp - lm) / (2 * eps), ana = mgr[p.id][idx];
				double rel = Math.abs(num - ana) / Math.max(1e-2, Math.abs(num) + Math.abs(ana));
				maxRel = Math.max(maxRel, rel);
			}
		}
		assertTrue(maxRel < 0.015, "MoEBlock gradient mismatch: maxRel=" + maxRel);

		// 2. Verify AlphaStar SlotEncoder + PointerHead exact analytical gradients
		ParamSet sps = new ParamSet();
		dev.swordai.core.nn.SlotEncoder se = new dev.swordai.core.nn.SlotEncoder(sps, "slot", 16, rng);
		for (int i = 0; i < se.ptrQuery.weight.w.length; i++) se.ptrQuery.weight.w[i] = (float) rng.nextGaussian() * 0.25f;
		for (int i = 0; i < se.ptrKeep.weight.w.length; i++) se.ptrKeep.weight.w[i] = (float) rng.nextGaussian() * 0.25f;
		float[] slots = new float[ObsSpec.SLOTS_SIZE];
		putSlot(slots, 1, ObsSpec.SF_SWORD, 0.85f, 1f);
		putSlot(slots, 5, ObsSpec.SF_AXE, 0.85f, 0f);
		putSlot(slots, 9, ObsSpec.SF_SHIELD, 0.5f, 0.5f);
		float[] top = new float[16], dTop = new float[16], dLogits = new float[ActionSpace.TOTAL_LOGITS];
		float[] logits = new float[ActionSpace.TOTAL_LOGITS], kitTgt = new float[dev.swordai.core.nn.SlotEncoder.KIT_DIM], ptrTgt = new float[10];
		for (int i = 0; i < top.length; i++) top[i] = (float) rng.nextGaussian() * 0.5f;
		for (int i = 0; i < kitTgt.length; i++) kitTgt[i] = (float) rng.nextGaussian() * 0.3f;
		for (int i = 0; i < ptrTgt.length; i++) ptrTgt[i] = (float) rng.nextGaussian() * 0.3f;
		dev.swordai.core.nn.SlotEncoder.Cache sc = se.newCache();
		dev.swordai.core.nn.SlotEncoder.Scratch ss = se.newScratch();
		float[][] sgr = sps.newGrads();
		se.encodeSlots(slots, sc, ss);
		se.writePointerLogits(top, logits, sc);
		int hbOff = ActionSpace.OFFSETS[ActionSpace.HOTBAR_SLOT];
		float[] dKit = new float[dev.swordai.core.nn.SlotEncoder.KIT_DIM];
		for (int i = 0; i < dKit.length; i++) dKit[i] = sc.kitCtx[i] - kitTgt[i];
		for (int i = 0; i < 10; i++) dLogits[hbOff + i] = logits[hbOff + i] - ptrTgt[i];
		se.backward(slots, top, dLogits, dKit, sc, ss, dTop, sgr);

		double maxRelSlot = 0, wNum = 0, wAna = 0;
		String wName = "";
		int wIdx = -1;
		for (Param p : sps.params()) {
			for (int idx = 0; idx < p.w.length; idx += Math.max(1, p.w.length / 5)) {
				float orig = p.w[idx];
				p.w[idx] = orig + eps; double lp = slotLoss(se, slots, top, kitTgt, ptrTgt);
				p.w[idx] = orig - eps; double lm = slotLoss(se, slots, top, kitTgt, ptrTgt);
				p.w[idx] = orig;
				double num = (lp - lm) / (2 * eps), ana = sgr[p.id][idx];
				double rel = Math.abs(num - ana) / Math.max(1e-2, Math.abs(num) + Math.abs(ana));
				if (rel > maxRelSlot) { maxRelSlot = rel; wName = p.name; wIdx = idx; wNum = num; wAna = ana; }
			}
		}
		assertTrue(maxRelSlot < 0.015, "SlotEncoder+Pointer gradient mismatch: maxRel=" + maxRelSlot
				+ " param=" + wName + "[" + wIdx + "] num=" + wNum + " ana=" + wAna);
	}

	private static double moeLoss(dev.swordai.core.nn.MoEBlock moe, float[] x, float[] rIn, float[] tgt, float[] dGateBal) {
		float[] out = new float[x.length];
		dev.swordai.core.nn.MoEBlock.Cache c = moe.newCache();
		moe.forward(x, rIn, out, c);
		double l = 0;
		for (int i = 0; i < out.length; i++) { double d = out[i] - tgt[i]; l += 0.5 * d * d; }
		for (int e = 0; e < dev.swordai.core.nn.MoEBlock.EXPERTS; e++) l += dGateBal[e] * c.gate[e];
		return l;
	}

	private static double slotLoss(dev.swordai.core.nn.SlotEncoder se, float[] slots, float[] top, float[] kitTgt, float[] ptrTgt) {
		dev.swordai.core.nn.SlotEncoder.Cache c = se.newCache();
		dev.swordai.core.nn.SlotEncoder.Scratch s = se.newScratch();
		float[] logits = new float[ActionSpace.TOTAL_LOGITS];
		se.encodeSlots(slots, c, s);
		se.writePointerLogits(top, logits, c);
		int hbOff = ActionSpace.OFFSETS[ActionSpace.HOTBAR_SLOT];
		double l = 0;
		for (int i = 0; i < kitTgt.length; i++) { double d = c.kitCtx[i] - kitTgt[i]; l += 0.5 * d * d; }
		for (int i = 0; i < 10; i++) { double d = logits[hbOff + i] - ptrTgt[i]; l += 0.5 * d * d; }
		return l;
	}

	@Test
	void pointerHeadIsStrictlyPermutationEquivariantAcrossHotbarSlots() {
		SwordNet net = new SwordNet(777L);
		Random rng = new Random(777L);
		// Give non-zero query weights so pointer scores differ strongly between Sword, Axe, Gap, and Empty slots.
		for (int i = 0; i < net.slotEncoder.ptrQuery.weight.w.length; i++) {
			net.slotEncoder.ptrQuery.weight.w[i] = (float) rng.nextGaussian() * 0.4f;
		}
		Obs a = randomObs(rng), b = new Obs();
		b.copyFrom(a);
		Arrays.fill(a.slots, 0f); Arrays.fill(b.slots, 0f);
		// Layout A: Sword in slot 0, Axe in slot 2, Gap in slot 5
		putSlot(a.slots, 0, ObsSpec.SF_SWORD, 0.85f, 1f);
		putSlot(a.slots, 2, ObsSpec.SF_AXE, 0.85f, 0f);
		putSlot(a.slots, 5, ObsSpec.SF_GAP, 0.65f, 0f);
		putSlot(a.slots, 9, ObsSpec.SF_SHIELD, 0.5f, 0.5f);
		// Layout B: Exact same items scrambled -> Sword in slot 7, Axe in slot 1, Gap in slot 8
		putSlot(b.slots, 7, ObsSpec.SF_SWORD, 0.85f, 1f);
		putSlot(b.slots, 1, ObsSpec.SF_AXE, 0.85f, 0f);
		putSlot(b.slots, 8, ObsSpec.SF_GAP, 0.65f, 0f);
		putSlot(b.slots, 9, ObsSpec.SF_SHIELD, 0.5f, 0.5f);

		SwordNet.Workspace wsA = net.newWorkspace(), wsB = net.newWorkspace();
		float[] h0 = new float[ObsMemory.GRU_HIDDEN], hist = new float[ObsMemory.HIST], seen = new float[ObsMemory.SEEN];
		net.forward(wsA, wsA.step, a.peri, a.fovea, a.vec, a.slots, a.terrain, hist, seen, h0, null);
		net.forward(wsB, wsB.step, b.peri, b.fovea, b.vec, b.slots, b.terrain, hist, seen, h0, null);

		// Kit context must be strictly permutation-invariant
		assertArrayEquals(wsA.step.kitCtx(), wsB.step.kitCtx(), 1e-5f, "Kit context must be permutation-invariant");
		// Pointer logits for Sword (0->7), Axe (2->1), Gap (5->8) must swap identically!
		int hb = ActionSpace.OFFSETS[ActionSpace.HOTBAR_SLOT];
		assertEquals(wsA.step.logits[hb], wsB.step.logits[hb], 1e-5f, "Keep logit must be invariant");
		assertEquals(wsA.step.logits[hb + 1 + 0], wsB.step.logits[hb + 1 + 7], 1e-5f, "Sword pointer logit must follow sword slot 0->7");
		assertEquals(wsA.step.logits[hb + 1 + 2], wsB.step.logits[hb + 1 + 1], 1e-5f, "Axe pointer logit must follow axe slot 2->1");
		assertEquals(wsA.step.logits[hb + 1 + 5], wsB.step.logits[hb + 1 + 8], 1e-5f, "Gap pointer logit must follow gap slot 5->8");
	}

	@Test
	void net2NetSurgeryFromRealSwordMasterAnchorPreservesWeightsAndLearnsSmp() throws Exception {
		Path swordMaster = Path.of("..", "run", "swordai", "anchors", "sword-master.bin");
		if (!Files.exists(swordMaster)) return;
		LeagueLearner learner = new LeagueLearner(128, 99L);
		assertTrue(learner.load(swordMaster), "Must load legacy Stage 5 sword-master.bin cleanly via Net2Net surgery");
		assertFalse(learner.hasNonFiniteWeights(), "No NaN/Inf weights after Net2Net surgery");
		assertTrue(learner.actor.gruWeightNorm() > 1.0f, "Must preserve trained Stage 5 GRU weights: norm=" + learner.actor.gruWeightNorm());
		assertFalse(LeagueLearner.hasSmpWeights(learner.actor), "Freshly surgeried actor must have zero SMP output weights on tick 0");

		// Verify neutral SMP biases hold steady on tick 0 (do not spam USE_ITEM, OFFHAND_SWAP, or INVENTORY)
		SwordNet.Workspace ws = learner.actor.newWorkspace();
		Obs probe = randomObs(new Random(5L));
		putSlot(probe.slots, 0, ObsSpec.SF_SWORD, 0.85f, 1f);
		learner.actor.forward(ws, ws.step, probe.peri, probe.fovea, probe.vec, probe.slots, probe.terrain,
				new float[ObsMemory.HIST], new float[ObsMemory.SEEN], new float[ObsMemory.GRU_HIDDEN], null);
		float[] p = new float[ActionSpace.TOTAL_LOGITS];
		dev.swordai.core.rl.Policy.allProbs(ws.step.logits, p);
		assertTrue(p[ActionSpace.OFFSETS[ActionSpace.USE_ITEM]] > 0.75f, "Tick-0 USE_ITEM=0 should dominate");
		assertTrue(p[ActionSpace.OFFSETS[ActionSpace.OFFHAND_SWAP]] > 0.85f, "Tick-0 OFFHAND_SWAP=0 should dominate");

		// Run 1 PPO update anchored to surgeried sword-master priorNet and confirm MoE + Pointer + Terrain start learning!
		SwordNet prior = LeagueLearner.loadActorOnly(swordMaster);
		Random rng = new Random(12L);
		LeagueLearner.Decision dec = new LeagueLearner.Decision();
		for (int t = 0; t < 128; t++) {
			Obs o = randomObs(rng);
			putSlot(o.slots, t % 9, ObsSpec.SF_SWORD, 0.85f, 1f);
			putSlot(o.slots, (t + 3) % 9, ObsSpec.SF_AXE, 0.85f, 0f);
			o.terrain[17] = 0.5f; // 1-block step directly ahead
			learner.decide(o, ActionSpace.PHASE1_HEADS, false, 1f, dec);
			learner.record(o, ActionSpace.PHASE1_HEADS, dec, (t % 32 == 31) ? 1f : 0.01f, t % 32 == 31, null, 0f);
		}
		LeaguePpo.Stats st = learner.update(0f, prior, 0.02f, 0f);
		assertTrue(Float.isFinite(st.policyLoss) && Float.isFinite(st.valueLoss));
		assertTrue(LeagueLearner.hasSmpWeights(learner.actor), "MoE / Pointer / Kit weights must receive gradients on update 1");
		for (int e = 0; e < dev.swordai.core.nn.MoEBlock.EXPERTS; e++) {
			assertTrue(st.moeUsage[e] > 0.10f && st.moeUsage[e] < 0.45f, "MoE expert " + e + " usage balanced: " + st.moeUsage[e]);
		}
	}

	private static void putSlot(float[] slots, int idx, int cat, float tier, float selected) {
		int off = idx * ObsSpec.SLOT_FEATS;
		slots[off + ObsSpec.SF_PRESENT] = 1f;
		slots[off + cat] = 1f;
		slots[off + ObsSpec.SF_TIER] = tier;
		slots[off + ObsSpec.SF_COUNT] = 0.16f;
		slots[off + ObsSpec.SF_DURABILITY] = 1f;
		slots[off + ObsSpec.SF_SELECTED] = selected;
	}

	private static Obs randomObs(Random rng) {
		Obs o = new Obs();
		for (int i = 0; i < o.vec.length; i++) o.vec[i] = (float) rng.nextGaussian() * 0.2f;
		for (int i = 0; i < o.priv.length; i++) o.priv[i] = (float) rng.nextGaussian() * 0.2f;
		for (int i = 0; i < o.terrain.length; i++) o.terrain[i] = (float) rng.nextGaussian() * 0.1f;
		Arrays.fill(o.peri, (byte) 0);
		Arrays.fill(o.fovea, (byte) 0);
		o.peri[10] = 1;
		o.fovea[10] = 1;
		return o;
	}
}
