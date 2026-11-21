package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;

import java.util.*;

/** Truncated-BPTT recurrent PPO with AlphaStar prior KL pull, fading kickstarting, and supervised MoveSearch steps. */
public final class LeaguePpo {
	public static final int CHUNK = 32, EPOCHS = 4, CHUNKS_PER_BATCH = 8;
	public static final float GAMMA = FixedReward.GAMMA, LAMBDA = 0.95f, CLIP = 0.2f, VALUE_COEF = 0.5f, MAX_GRAD_NORM = 0.5f;
	// Low entropy on precision heads (YAW, PITCH, ATTACK) prevents aim dithering and uncharged/whiff click floors;
	// keep tactical exploration on movement heads (FORWARD, STRAFE, SPRINT, JUMP).
	private static final float[] ENTROPY_COEF = {0.001f, 0.001f, 0.006f, 0.006f, 0.006f, 0.004f, 0.001f};

	public static final class Stats {
		public int steps, epochsRun;
		public float policyLoss, valueLoss, approxKl, clipFrac, entropy, priorKl, demoCe, explainedVar, lr;
	}

	public float lr = 1.5e-4f, minLr = 3e-5f, maxLr = 3e-4f, targetKl = 0.025f, klStop = 0.06f;

	private final SwordNet.StepCache[] caches = new SwordNet.StepCache[CHUNK];
	private final SwordNet.Workspace actorWs, priorWs;
	private final SwordCritic.Workspace criticWs;
	private final float[] hCur = new float[ObsMemory.GRU_HIDDEN], hZero = new float[ObsMemory.GRU_HIDDEN];
	private final float[] dHNext = new float[ObsMemory.GRU_HIDDEN], dHPrev = new float[ObsMemory.GRU_HIDDEN];
	private final float[] probs = new float[ActionSpace.TOTAL_LOGITS], headEnt = new float[ActionSpace.HEADS];
	private final float[][] chunkLogitsGrad = new float[CHUNK][ActionSpace.TOTAL_LOGITS];
	private final Random rng;

	public LeaguePpo(SwordNet actor, SwordCritic critic, long seed) {
		this.actorWs = actor.newWorkspace();
		this.priorWs = actor.newWorkspace();
		this.criticWs = critic.newWorkspace();
		for (int i = 0; i < CHUNK; i++) caches[i] = actor.newStepCache();
		this.rng = new Random(seed);
	}

	public Stats update(SwordNet actor, SwordCritic critic, LeagueRollout r, SwordNet priorNet, float priorWeight, float kickWeight) {
		Stats st = new Stats();
		int n = r.size();
		if (n < CHUNK) return st;
		st.steps = n;
		st.explainedVar = explainedVar(r.value, r.returns, n);

		double mean = 0, var = 0;
		for (int i = 0; i < n; i++) mean += r.advantage[i];
		mean /= n;
		for (int i = 0; i < n; i++) { double d = r.advantage[i] - mean; var += d * d; }
		float invStd = (float) (1.0 / (Math.sqrt(var / n) + 1e-8));
		float[] adv = new float[n];
		for (int i = 0; i < n; i++) adv[i] = (float) ((r.advantage[i] - mean) * invStd);

		float[][] cachedPrior = null;
		if (priorNet != null && priorWeight > 0f) {
			cachedPrior = new float[n][ActionSpace.TOTAL_LOGITS];
			float[] hp = new float[ObsMemory.GRU_HIDDEN];
			for (int t = 0; t < n; t++) {
				if (t == 0 || r.done[t - 1]) Arrays.fill(hp, 0f);
				priorNet.forward(priorWs, priorWs.step, r.peri[t], r.fovea[t], r.vec[t], r.hist[t], r.seen[t], hp, hp);
				priorNet.condition(priorWs, priorWs.step, r.action[t]);
				Policy.allProbs(priorWs.step.logits, cachedPrior[t]);
				if (r.vec[t][ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] < 0.85f || r.priv[t][ObsSpec.PV_IN_REACH] < 0.5f) {
					int aOff = ActionSpace.OFFSETS[ActionSpace.ATTACK];
					cachedPrior[t][aOff] = 1f;
					cachedPrior[t][aOff + 1] = 0f;
				}
			}
		}

		int numChunks = (n + CHUNK - 1) / CHUNK;
		int[] order = new int[numChunks];
		for (int i = 0; i < numChunks; i++) order[i] = i;

		double sumPol = 0, sumVal = 0, sumKl = 0, sumClip = 0, sumEnt = 0, sumPrior = 0, sumDemo = 0;
		long count = 0, demoCount = 0;
		boolean stopped = false;

		for (int ep = 0; ep < EPOCHS && !stopped; ep++) {
			st.epochsRun = ep + 1;
			for (int i = numChunks - 1; i > 0; i--) { int j = rng.nextInt(i + 1), t = order[i]; order[i] = order[j]; order[j] = t; }
			for (int b = 0; b < numChunks; b += CHUNKS_PER_BATCH) {
				int bEnd = Math.min(numChunks, b + CHUNKS_PER_BATCH);
				ParamSet.zeroGrads(actorWs.grads);
				ParamSet.zeroGrads(criticWs.grads);
				int batchSteps = 0;
				double batchKl = 0;

				for (int ci = b; ci < bEnd; ci++) {
					int start = order[ci] * CHUNK, len = Math.min(CHUNK, n - start);
					if (len <= 0) continue;
					System.arraycopy(r.hPrev[start], 0, hCur, 0, ObsMemory.GRU_HIDDEN);
					for (int k = 0; k < len; k++) {
						int t = start + k;
						if (k > 0 && r.done[t - 1]) Arrays.fill(hCur, 0f);
						SwordNet.StepCache sc = caches[k];
						actor.forward(actorWs, sc, r.peri[t], r.fovea[t], r.vec[t], r.hist[t], r.seen[t], hCur, hCur);
						actor.condition(actorWs, sc, r.action[t]);

						float newLogp = Policy.evaluate(sc.logits, r.headMask[t], r.action[t], probs, headEnt);
						float logRatio = newLogp - r.oldLogp[t];
						float ratio = (float) Math.exp(Math.max(-20f, Math.min(20f, logRatio)));
						float a = adv[t], s1 = ratio * a, s2 = Math.max(1f - CLIP, Math.min(1f + CLIP, ratio)) * a;
						boolean clipped = (a >= 0f && ratio > 1f + CLIP) || (a < 0f && ratio < 1f - CLIP);
						Policy.gradient(r.headMask[t], r.action[t], probs, headEnt, clipped ? 0f : -ratio * a, ENTROPY_COEF, chunkLogitsGrad[k]);

						if (cachedPrior != null) {
							sumPrior += Policy.kl(r.headMask[t], cachedPrior[t], probs);
							Policy.addKlGradient(r.headMask[t], cachedPrior[t], probs, priorWeight, chunkLogitsGrad[k]);
						}
						if (kickWeight > 0f && r.demoWeight[t] > 0f) {
							sumDemo += addCeGrad(r.headMask[t], r.demoAction[t], probs, kickWeight * r.demoWeight[t], chunkLogitsGrad[k]);
							demoCount++;
						}
						float vErr = critic.forward(criticWs, r.vec[t], r.priv[t], sc.hOut) - r.returns[t];
						critic.backward(criticWs, VALUE_COEF * vErr);

						float kl = (ratio - 1f) - logRatio;
						sumPol += -Math.min(s1, s2); sumVal += 0.5f * vErr * vErr; sumKl += kl; batchKl += kl;
						if (clipped) sumClip += 1.0;
						for (int h = 0; h < ActionSpace.HEADS; h++) sumEnt += headEnt[h];
						batchSteps++; count++;
					}
					Arrays.fill(dHNext, 0f);
					for (int k = len - 1; k >= 0; k--) {
						int t = start + k;
						if (r.done[t]) Arrays.fill(dHNext, 0f);
						System.arraycopy(chunkLogitsGrad[k], 0, actorWs.dLogits, 0, ActionSpace.TOTAL_LOGITS);
						actor.backward(actorWs, caches[k], r.peri[t], r.fovea[t], r.vec[t], r.hist[t], r.seen[t], dHNext, dHPrev);
						System.arraycopy(dHPrev, 0, dHNext, 0, ObsMemory.GRU_HIDDEN);
					}
				}
				if (batchSteps > 0) {
					float inv = 1f / batchSteps;
					Adam.step(actor.params, actorWs.grads, lr, inv, MAX_GRAD_NORM);
					Adam.step(critic.params, criticWs.grads, lr * 2f, inv, MAX_GRAD_NORM);
					if (batchKl / batchSteps > klStop) { stopped = true; break; }
				}
			}
		}
		if (count > 0) {
			st.policyLoss = (float) (sumPol / count); st.valueLoss = (float) (sumVal / count);
			st.approxKl = (float) (sumKl / count); st.clipFrac = (float) (sumClip / count);
			st.entropy = (float) (sumEnt / (count * ActionSpace.HEADS));
			st.priorKl = cachedPrior != null ? (float) (sumPrior / count) : 0f;
			st.demoCe = demoCount > 0 ? (float) (sumDemo / demoCount) : 0f;
		}
		if (st.approxKl > targetKl * 1.5f) lr = Math.max(minLr, lr * 0.8f);
		else if (st.approxKl < targetKl * 0.5f && !stopped) lr = Math.min(maxLr, lr * 1.1f);
		st.lr = lr;
		return st;
	}

	public float supervisedStep(SwordNet actor, byte[][] peri, byte[][] fovea, float[][] vec, float[][] hist, float[][] seen,
			float[][] hPrev, byte[][] targetAction, int count, float stepLr) {
		if (count <= 0) return 0f;
		ParamSet.zeroGrads(actorWs.grads);
		double sumCe = 0;
		for (int i = 0; i < count; i++) {
			float[] hp = (hPrev != null && hPrev[i] != null) ? hPrev[i] : hZero;
			actor.forward(actorWs, actorWs.step, peri[i], fovea[i], vec[i], hist[i], seen[i], hp, null);
			actor.condition(actorWs, actorWs.step, targetAction[i]);
			Policy.allProbs(actorWs.step.logits, probs);
			Arrays.fill(actorWs.dLogits, 0f);
			sumCe += addCeGrad(ActionSpace.ALL_HEADS, targetAction[i], probs, 1f, actorWs.dLogits);
			actor.backward(actorWs, actorWs.step, peri[i], fovea[i], vec[i], hist[i], seen[i], null, null);
		}
		Adam.step(actor.params, actorWs.grads, stepLr, 1f / count, MAX_GRAD_NORM);
		return (float) (sumCe / (count * ActionSpace.HEADS));
	}

	private static float addCeGrad(int headMask, byte[] target, float[] probs, float w, float[] dLogits) {
		float ce = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) continue;
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h], a = target[h];
			ce -= (float) Math.log(Math.max(probs[off + a], 1e-12f));
			for (int i = 0; i < n; i++) dLogits[off + i] += w * (probs[off + i] - (i == a ? 1f : 0f));
		}
		return ce;
	}

	private static float explainedVar(float[] pred, float[] target, int n) {
		double mean = 0, vy = 0, ve = 0;
		for (int i = 0; i < n; i++) mean += target[i];
		mean /= n;
		for (int i = 0; i < n; i++) { double dy = target[i] - mean, e = target[i] - pred[i]; vy += dy * dy; ve += e * e; }
		return vy < 1e-8 ? 0f : (float) (1.0 - ve / vy);
	}
}
