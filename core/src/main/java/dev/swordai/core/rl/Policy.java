package dev.swordai.core.rl;

import java.util.Random;

/** Maths for the factorised categorical policy: sampling, log-probabilities, entropy, gradients. */
public final class Policy {
	private Policy() {
	}

	/** In-place softmax over logits[off .. off+n), written to probs[off .. off+n). */
	public static void softmax(float[] logits, int off, int n, float[] probs) {
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			max = Math.max(max, logits[off + i]);
		}
		float sum = 0f;
		for (int i = 0; i < n; i++) {
			float e = (float) Math.exp(logits[off + i] - max);
			probs[off + i] = e;
			sum += e;
		}
		float inv = 1f / sum;
		for (int i = 0; i < n; i++) {
			probs[off + i] *= inv;
		}
	}

	/**
	 * Chooses an action for every head. Locked heads take their default and contribute nothing to
	 * the returned log-probability.
	 *
	 * @param temperature  1 samples from the policy as trained; lower values favour its preferred
	 *                     choices. The returned log-probability is only meaningful at 1.
	 * @param probsScratch length {@link ActionSpace#TOTAL_LOGITS}
	 * @return log pi(action | obs) summed over the unlocked heads
	 */
	/**
	 * Adds {@code bias} to the swing's logit: the answer rule as a push the network learns on top of
	 * ({@code Config.answerNudge}), instead of a swing made for it that it never hears of.
	 */
	public static void pushSwing(float[] logits, float bias) {
		if (bias != 0f) {
			logits[ActionSpace.OFFSETS[ActionSpace.ATTACK] + 1] += bias;
		}
	}

	/** How much the network prefers swinging to not swinging in these logits. */
	public static float swingGap(float[] logits) {
		int a = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		return logits[a + 1] - logits[a];
	}

	public static float sample(float[] logits, int headMask, boolean greedy, float temperature, Random rng, float[] probsScratch, int[] actionOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) {
				actionOut[h] = ActionSpace.DEFAULTS[h];
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			int n = ActionSpace.SIZES[h];
			if (temperature != 1f && temperature > 0f) {
				for (int i = 0; i < n; i++) {
					probsScratch[off + i] = logits[off + i] / temperature;
				}
				softmax(probsScratch, off, n, probsScratch);
			} else {
				softmax(logits, off, n, probsScratch);
			}
			int pick = n - 1;
			if (greedy) {
				float best = -1f;
				for (int i = 0; i < n; i++) {
					if (probsScratch[off + i] > best) {
						best = probsScratch[off + i];
						pick = i;
					}
				}
			} else {
				float u = rng.nextFloat();
				float acc = 0f;
				for (int i = 0; i < n; i++) {
					acc += probsScratch[off + i];
					if (u < acc) {
						pick = i;
						break;
					}
				}
			}
			actionOut[h] = pick;
			logp += (float) Math.log(Math.max(probsScratch[off + pick], 1e-12f));
		}
		return logp;
	}

	/**
	 * Evaluates stored actions under the current logits.
	 *
	 * @param probsOut   receives the softmax of every unlocked head
	 * @param entropyOut receives the per-head entropy (0 for locked heads); length HEADS
	 * @return log pi(action | obs) summed over the unlocked heads
	 */
	public static float evaluate(float[] logits, int headMask, byte[] action, float[] probsOut, float[] entropyOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			entropyOut[h] = 0f;
			if (!ActionSpace.unlocked(headMask, h)) {
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			int n = ActionSpace.SIZES[h];
			softmax(logits, off, n, probsOut);
			float ent = 0f;
			for (int i = 0; i < n; i++) {
				float p = probsOut[off + i];
				if (p > 1e-12f) {
					ent -= p * (float) Math.log(p);
				}
			}
			entropyOut[h] = ent;
			logp += (float) Math.log(Math.max(probsOut[off + action[h]], 1e-12f));
		}
		return logp;
	}

	/**
	 * Softmax of every head's logits into {@code probs}, whatever the mask: for a network's choices kept
	 * to be compared with later ({@link #kl}).
	 */
	public static void allProbs(float[] logits, float[] probs) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			softmax(logits, ActionSpace.OFFSETS[h], ActionSpace.SIZES[h], probs);
		}
	}

	/**
	 * KL(anchor || current) summed over the unlocked heads.
	 *
	 * @param anchorProbs the anchor's choices ({@link #allProbs})
	 * @param probs       the current network's, from {@link #evaluate} on the same state
	 */
	public static float kl(int headMask, float[] anchorProbs, float[] probs) {
		float kl = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) {
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			for (int i = 0; i < ActionSpace.SIZES[h]; i++) {
				float q = anchorProbs[off + i];
				if (q > 1e-12f) {
					kl += q * (float) (Math.log(q) - Math.log(Math.max(probs[off + i], 1e-12f)));
				}
			}
		}
		return kl;
	}

	/**
	 * Adds {@code weight} times dKL(anchor || current)/dLogits to {@code dLogits} for the unlocked
	 * heads: {@code weight * (p - q)}, the pull back towards what the anchor would choose.
	 */
	public static void addKlGradient(int headMask, float[] anchorProbs, float[] probs, float weight, float[] dLogits) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) {
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			for (int i = 0; i < ActionSpace.SIZES[h]; i++) {
				dLogits[off + i] += weight * (probs[off + i] - anchorProbs[off + i]);
			}
		}
	}

	/**
	 * Writes dLoss/dLogits for loss = dLogp * logp - sum_h entCoef[h] * entropy_h.
	 * {@code probs} and {@code entropy} must come from {@link #evaluate} on the same logits.
	 */
	public static void gradient(int headMask, byte[] action, float[] probs, float[] entropy, float dLogp, float[] entCoef, float[] dLogits) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			int off = ActionSpace.OFFSETS[h];
			int n = ActionSpace.SIZES[h];
			if (!ActionSpace.unlocked(headMask, h)) {
				for (int i = 0; i < n; i++) {
					dLogits[off + i] = 0f;
				}
				continue;
			}
			float ent = entropy[h];
			float coef = entCoef[h];
			int a = action[h];
			for (int i = 0; i < n; i++) {
				float p = probs[off + i];
				float g = dLogp * ((i == a ? 1f : 0f) - p);
				if (coef != 0f && p > 1e-12f) {
					g += coef * p * ((float) Math.log(p) + ent);
				}
				dLogits[off + i] = g;
			}
		}
	}
}
