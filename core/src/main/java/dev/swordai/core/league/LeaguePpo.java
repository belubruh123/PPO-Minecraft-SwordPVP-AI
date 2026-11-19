package dev.swordai.core.league;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Policy;

import java.util.Arrays;
import java.util.Random;

/**
 * Recurrent PPO trainer with truncated BPTT over fixed-length chunks ({@link #CHUNK} ticks),
 * resetting GRU hidden state cleanly on episode boundaries. Combines:
 * <ol>
 *   <li>Clipped PPO surrogate objective + asymmetric critic value loss + entropy bonus.</li>
 *   <li>AlphaStar-style KL pull toward a frozen human-viable prior network ({@code priorNet}).</li>
 *   <li>Fading kickstarting cross-entropy on demonstration ticks ({@code kickWeight}).</li>
 *   <li>Supervised updates from game-verified outcome search ({@link MoveSearch}) and
 *       outcome-filtered winning human moments ({@link HumanImitation}).</li>
 * </ol>
 */
public final class LeaguePpo {
	public static final int CHUNK = 32;
	public static final float GAMMA = FixedReward.GAMMA;
	public static final float LAMBDA = 0.95f;
	public static final float CLIP = 0.2f;
	public static final float VALUE_COEF = 0.5f;
	public static final float MAX_GRAD_NORM = 0.5f;
	public static final int EPOCHS = 4;
	public static final int CHUNKS_PER_BATCH = 8; // 8 * 32 = 256 steps per minibatch

	private static final float[] ENTROPY_COEF = {0.005f, 0.005f, 0.010f, 0.010f, 0.010f, 0.005f, 0.012f};

	public static final class Stats {
		public int steps;
		public int epochsRun;
		public float policyLoss;
		public float valueLoss;
		public float approxKl;
		public float clipFrac;
		public float entropy;
		public float priorKl;
		public float demoCe;
		public float explainedVar;
		public float lr;
	}

	public float lr = 1.5e-4f;
	public float minLr = 3e-5f;
	public float maxLr = 3e-4f;
	public float targetKl = 0.025f;
	public float klStop = 0.06f;

	private final SwordNet.StepCache[] caches = new SwordNet.StepCache[CHUNK];
	private final SwordNet.Workspace actorWs;
	private final SwordCritic.Workspace criticWs;
	private final SwordNet.Workspace priorWs;
	private final float[] hCur = new float[ObsMemory.GRU_HIDDEN];
	private final float[] hZero = new float[ObsMemory.GRU_HIDDEN];
	private final float[] dHNext = new float[ObsMemory.GRU_HIDDEN];
	private final float[] dHPrev = new float[ObsMemory.GRU_HIDDEN];
	private final float[] probs = new float[ActionSpace.TOTAL_LOGITS];
	private final float[] headEnt = new float[ActionSpace.HEADS];
	private final float[][] chunkLogitsGrad = new float[CHUNK][ActionSpace.TOTAL_LOGITS];
	private final Random rng;

	public LeaguePpo(SwordNet actor, SwordCritic critic, long seed) {
		this.actorWs = actor.newWorkspace();
		this.criticWs = critic.newWorkspace();
		this.priorWs = actor.newWorkspace();
		for (int i = 0; i < CHUNK; i++) {
			caches[i] = actor.newStepCache();
		}
		this.rng = new Random(seed);
	}

	/**
	 * Runs one recurrent PPO update over {@code rollout}.
	 *
	 * @param priorNet    optional frozen human/distilled anchor policy (may be null)
	 * @param priorWeight KL(prior || current) pull weight (e.g. 0.02)
	 * @param kickWeight  fading kickstarting weight on rollout demo targets (0..1)
	 */
	public Stats update(SwordNet actor, SwordCritic critic, LeagueRollout rollout,
			SwordNet priorNet, float priorWeight, float kickWeight) {
		Stats stats = new Stats();
		int n = rollout.size();
		if (n < CHUNK) {
			return stats;
		}
		stats.steps = n;
		stats.explainedVar = explainedVariance(rollout.value, rollout.returns, n);

		// Normalise advantages once per update.
		double mean = 0;
		for (int i = 0; i < n; i++) {
			mean += rollout.advantage[i];
		}
		mean /= n;
		double var = 0;
		for (int i = 0; i < n; i++) {
			double d = rollout.advantage[i] - mean;
			var += d * d;
		}
		float invStd = (float) (1.0 / (Math.sqrt(var / n) + 1e-8));
		float[] adv = new float[n];
		for (int i = 0; i < n; i++) {
			adv[i] = (float) ((rollout.advantage[i] - mean) * invStd);
		}

		// Precompute frozen prior probabilities once (if active) using exact rollout hidden states.
		float[][] cachedPrior = null;
		if (priorNet != null && priorWeight > 0f) {
			cachedPrior = new float[n][ActionSpace.TOTAL_LOGITS];
			float[] hp = new float[ObsMemory.GRU_HIDDEN];
			for (int t = 0; t < n; t++) {
				if (t == 0 || rollout.done[t - 1]) {
					Arrays.fill(hp, 0f);
				}
				priorNet.forward(priorWs, priorWs.step, rollout.peri[t], rollout.fovea[t],
						rollout.vec[t], rollout.hist[t], rollout.seen[t], hp, hp);
				priorNet.condition(priorWs, priorWs.step, rollout.action[t]);
				Policy.allProbs(priorWs.step.logits, cachedPrior[t]);
			}
		}

		int numChunks = (n + CHUNK - 1) / CHUNK;
		int[] chunkOrder = new int[numChunks];
		for (int i = 0; i < numChunks; i++) {
			chunkOrder[i] = i;
		}

		double sumPol = 0, sumVal = 0, sumKl = 0, sumClip = 0, sumEnt = 0, sumPrior = 0, sumDemo = 0;
		long count = 0;
		long demoCount = 0;
		boolean stopped = false;

		for (int epoch = 0; epoch < EPOCHS && !stopped; epoch++) {
			stats.epochsRun = epoch + 1;
			shuffle(chunkOrder);
			for (int b = 0; b < numChunks; b += CHUNKS_PER_BATCH) {
				int bEnd = Math.min(numChunks, b + CHUNKS_PER_BATCH);
				ParamSet.zeroGrads(actorWs.grads);
				ParamSet.zeroGrads(criticWs.grads);
				int batchSteps = 0;
				double batchKl = 0;

				for (int ci = b; ci < bEnd; ci++) {
					int start = chunkOrder[ci] * CHUNK;
					int len = Math.min(CHUNK, n - start);
					if (len <= 0) {
						continue;
					}
					// Forward through the chunk sequentially, resetting GRU state on episode boundaries.
					System.arraycopy(rollout.hPrev[start], 0, hCur, 0, ObsMemory.GRU_HIDDEN);
					for (int k = 0; k < len; k++) {
						int t = start + k;
						if (k > 0 && rollout.done[t - 1]) {
							Arrays.fill(hCur, 0f);
						}
						SwordNet.StepCache sc = caches[k];
						actor.forward(actorWs, sc, rollout.peri[t], rollout.fovea[t],
								rollout.vec[t], rollout.hist[t], rollout.seen[t], hCur, hCur);
						actor.condition(actorWs, sc, rollout.action[t]);

						float newLogp = Policy.evaluate(sc.logits, rollout.headMask[t], rollout.action[t], probs, headEnt);
						float logRatio = newLogp - rollout.oldLogp[t];
						float ratio = (float) Math.exp(Math.max(-20f, Math.min(20f, logRatio)));
						float a = adv[t];
						float s1 = ratio * a;
						float clippedRatio = Math.max(1f - CLIP, Math.min(1f + CLIP, ratio));
						float s2 = clippedRatio * a;
						boolean clipped = (a >= 0f && ratio > 1f + CLIP) || (a < 0f && ratio < 1f - CLIP);
						float dLogp = clipped ? 0f : -ratio * a;

						Policy.gradient(rollout.headMask[t], rollout.action[t], probs, headEnt, dLogp, ENTROPY_COEF, chunkLogitsGrad[k]);

						if (cachedPrior != null) {
							float pkl = Policy.kl(rollout.headMask[t], cachedPrior[t], probs);
							Policy.addKlGradient(rollout.headMask[t], cachedPrior[t], probs, priorWeight, chunkLogitsGrad[k]);
							sumPrior += pkl;
						}
						if (kickWeight > 0f && rollout.demoWeight[t] > 0f) {
							float w = kickWeight * rollout.demoWeight[t];
							float ce = addActionCeGradient(rollout.headMask[t], rollout.demoAction[t], probs, w, chunkLogitsGrad[k]);
							sumDemo += ce;
							demoCount++;
						}

						// Critic forward + backward (detached GRU hidden state).
						float vPred = critic.forward(criticWs, rollout.vec[t], rollout.priv[t], sc.hOut);
						float vErr = vPred - rollout.returns[t];
						critic.backward(criticWs, VALUE_COEF * vErr);

						float kl = (ratio - 1f) - logRatio;
						sumPol += -Math.min(s1, s2);
						sumVal += 0.5f * vErr * vErr;
						sumKl += kl;
						batchKl += kl;
						if (clipped) {
							sumClip += 1.0;
						}
						for (int h = 0; h < ActionSpace.HEADS; h++) {
							sumEnt += headEnt[h];
						}
						batchSteps++;
						count++;
					}

					// Truncated BPTT backward through the chunk, breaking gradient flow across done boundaries.
					Arrays.fill(dHNext, 0f);
					for (int k = len - 1; k >= 0; k--) {
						int t = start + k;
						if (rollout.done[t]) {
							Arrays.fill(dHNext, 0f);
						}
						System.arraycopy(chunkLogitsGrad[k], 0, actorWs.dLogits, 0, ActionSpace.TOTAL_LOGITS);
						actor.backward(actorWs, caches[k], rollout.peri[t], rollout.fovea[t],
								rollout.vec[t], rollout.hist[t], rollout.seen[t], dHNext, dHPrev);
						System.arraycopy(dHPrev, 0, dHNext, 0, ObsMemory.GRU_HIDDEN);
					}
				}

				if (batchSteps > 0) {
					float inv = 1f / batchSteps;
					Adam.step(actor.params, actorWs.grads, lr, inv, MAX_GRAD_NORM);
					Adam.step(critic.params, criticWs.grads, lr * 2f, inv, MAX_GRAD_NORM);
					if (batchKl / batchSteps > klStop) {
						stopped = true;
						break;
					}
				}
			}
		}

		if (count > 0) {
			stats.policyLoss = (float) (sumPol / count);
			stats.valueLoss = (float) (sumVal / count);
			stats.approxKl = (float) (sumKl / count);
			stats.clipFrac = (float) (sumClip / count);
			stats.entropy = (float) (sumEnt / (count * ActionSpace.HEADS));
			stats.priorKl = cachedPrior != null ? (float) (sumPrior / count) : 0f;
			stats.demoCe = demoCount > 0 ? (float) (sumDemo / demoCount) : 0f;
		}
		// Adaptive LR based on actual KL.
		if (stats.approxKl > targetKl * 1.5f) {
			lr = Math.max(minLr, lr * 0.8f);
		} else if (stats.approxKl < targetKl * 0.5f && !stopped) {
			lr = Math.min(maxLr, lr * 1.1f);
		}
		stats.lr = lr;
		return stats;
	}

	/**
	 * One supervised cross-entropy step over a batch of single-tick examples (used by
	 * {@link MoveSearch} outcome-verified targets and {@link HumanImitation} winning moments).
	 *
	 * @return mean per-head cross-entropy
	 */
	public float supervisedStep(SwordNet actor, byte[][] peri, byte[][] fovea, float[][] vec,
			float[][] hist, float[][] seen, float[][] hPrev, byte[][] targetAction, int count, float stepLr) {
		if (count <= 0) {
			return 0f;
		}
		ParamSet.zeroGrads(actorWs.grads);
		double sumCe = 0;
		for (int i = 0; i < count; i++) {
			float[] hp = hPrev != null && hPrev[i] != null ? hPrev[i] : hZero;
			actor.forward(actorWs, actorWs.step, peri[i], fovea[i], vec[i], hist[i], seen[i], hp, null);
			actor.condition(actorWs, actorWs.step, targetAction[i]);
			Policy.allProbs(actorWs.step.logits, probs);
			Arrays.fill(actorWs.dLogits, 0f);
			sumCe += addActionCeGradient(ActionSpace.ALL_HEADS, targetAction[i], probs, 1f, actorWs.dLogits);
			actor.backward(actorWs, actorWs.step, peri[i], fovea[i], vec[i], hist[i], seen[i], null, null);
		}
		Adam.step(actor.params, actorWs.grads, stepLr, 1f / count, MAX_GRAD_NORM);
		return (float) (sumCe / (count * ActionSpace.HEADS));
	}

	/**
	 * Soft-target distillation step (KL(teacherProbs || student)) on a minibatch of rollout ticks,
	 * used to transfer a legacy distilled network cleanly into {@link SwordNet} with zero skill loss.
	 */
	public float distillStep(SwordNet actor, byte[][] peri, byte[][] fovea, float[][] vec,
			float[][] hist, float[][] seen, float[][] hPrev, byte[][] takenAction, float[][] teacherProbs,
			int count, float stepLr) {
		if (count <= 0) {
			return 0f;
		}
		ParamSet.zeroGrads(actorWs.grads);
		double sumKl = 0;
		for (int i = 0; i < count; i++) {
			float[] hp = hPrev != null && hPrev[i] != null ? hPrev[i] : hZero;
			actor.forward(actorWs, actorWs.step, peri[i], fovea[i], vec[i], hist[i], seen[i], hp, null);
			actor.condition(actorWs, actorWs.step, takenAction[i]);
			Policy.allProbs(actorWs.step.logits, probs);
			Arrays.fill(actorWs.dLogits, 0f);
			sumKl += Policy.kl(ActionSpace.ALL_HEADS, teacherProbs[i], probs);
			Policy.addKlGradient(ActionSpace.ALL_HEADS, teacherProbs[i], probs, 1f, actorWs.dLogits);
			actor.backward(actorWs, actorWs.step, peri[i], fovea[i], vec[i], hist[i], seen[i], null, null);
		}
		Adam.step(actor.params, actorWs.grads, stepLr, 1f / count, MAX_GRAD_NORM);
		return (float) (sumKl / count);
	}

	private static float addActionCeGradient(int headMask, byte[] target, float[] probs, float weight, float[] dLogits) {
		float ce = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) {
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			int n = ActionSpace.SIZES[h];
			int a = target[h];
			ce -= (float) Math.log(Math.max(probs[off + a], 1e-12f));
			for (int i = 0; i < n; i++) {
				dLogits[off + i] += weight * (probs[off + i] - (i == a ? 1f : 0f));
			}
		}
		return ce;
	}

	private static float explainedVariance(float[] pred, float[] target, int n) {
		double mean = 0;
		for (int i = 0; i < n; i++) {
			mean += target[i];
		}
		mean /= n;
		double varY = 0;
		double varErr = 0;
		for (int i = 0; i < n; i++) {
			double dy = target[i] - mean;
			double e = target[i] - pred[i];
			varY += dy * dy;
			varErr += e * e;
		}
		return varY < 1e-8 ? 0f : (float) (1.0 - varErr / varY);
	}

	private void shuffle(int[] a) {
		for (int i = a.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int t = a[i];
			a[i] = a[j];
			a[j] = t;
		}
	}
}
