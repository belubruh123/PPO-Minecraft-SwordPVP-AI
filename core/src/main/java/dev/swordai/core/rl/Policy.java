package dev.swordai.core.rl;

import java.util.Random;

/** Factorised categorical policy maths: softmax, sampling, log-prob, entropy, KL, gradients. */
public final class Policy {
	private Policy() {
	}

	public static void softmax(float[] logits, int off, int n, float[] probs) {
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < n; i++) max = Math.max(max, logits[off + i]);
		float sum = 0f;
		for (int i = 0; i < n; i++) {
			float e = (float) Math.exp(logits[off + i] - max);
			probs[off + i] = e;
			sum += e;
		}
		float inv = 1f / sum;
		for (int i = 0; i < n; i++) probs[off + i] *= inv;
	}

	public static float sample(float[] logits, int headMask, boolean greedy, float temperature, Random rng, float[] probs, int[] actionOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) {
				actionOut[h] = ActionSpace.DEFAULTS[h];
				continue;
			}
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h];
			if (temperature != 1f && temperature > 0f) {
				for (int i = 0; i < n; i++) probs[off + i] = logits[off + i] / temperature;
				softmax(probs, off, n, probs);
			} else {
				softmax(logits, off, n, probs);
			}
			int pick = 0;
			if (greedy) {
				float best = -1f;
				for (int i = 0; i < n; i++) if (probs[off + i] > best) { best = probs[off + i]; pick = i; }
			} else {
				float u = rng.nextFloat(), acc = 0f;
				for (int i = 0; i < n; i++) {
					if (probs[off + i] > 0f) pick = i;
					acc += probs[off + i];
					if (u < acc) { pick = i; break; }
				}
			}
			actionOut[h] = pick;
			logp += (float) Math.log(Math.max(probs[off + pick], 1e-12f));
		}
		return logp;
	}

	public static float evaluate(float[] logits, int headMask, byte[] action, float[] probsOut, float[] entropyOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			entropyOut[h] = 0f;
			if (!ActionSpace.unlocked(headMask, h)) continue;
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h];
			softmax(logits, off, n, probsOut);
			float ent = 0f;
			for (int i = 0; i < n; i++) {
				float p = probsOut[off + i];
				if (p > 1e-12f) ent -= p * (float) Math.log(p);
			}
			entropyOut[h] = ent;
			logp += (float) Math.log(Math.max(probsOut[off + action[h]], 1e-12f));
		}
		return logp;
	}

	public static void allProbs(float[] logits, float[] probs) {
		for (int h = 0; h < ActionSpace.HEADS; h++) softmax(logits, ActionSpace.OFFSETS[h], ActionSpace.SIZES[h], probs);
	}

	public static float kl(int headMask, float[] anchorProbs, float[] probs) {
		float kl = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) continue;
			int off = ActionSpace.OFFSETS[h];
			for (int i = 0; i < ActionSpace.SIZES[h]; i++) {
				float q = anchorProbs[off + i];
				if (q > 1e-12f) kl += q * (float) (Math.log(q) - Math.log(Math.max(probs[off + i], 1e-12f)));
			}
		}
		return kl;
	}

	public static void addKlGradient(int headMask, float[] anchorProbs, float[] probs, float weight, float[] dLogits) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (!ActionSpace.unlocked(headMask, h)) continue;
			int off = ActionSpace.OFFSETS[h];
			for (int i = 0; i < ActionSpace.SIZES[h]; i++) {
				dLogits[off + i] += weight * (probs[off + i] - anchorProbs[off + i]);
			}
		}
	}

	public static void gradient(int headMask, byte[] action, float[] probs, float[] entropy, float dLogp, float[] entCoef, float[] dLogits) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h];
			if (!ActionSpace.unlocked(headMask, h)) {
				for (int i = 0; i < n; i++) dLogits[off + i] = 0f;
				continue;
			}
			float ent = entropy[h], coef = entCoef[h];
			int a = action[h];
			for (int i = 0; i < n; i++) {
				float p = probs[off + i];
				float g = dLogp * ((i == a ? 1f : 0f) - p);
				if (coef != 0f && p > 1e-12f) g += coef * p * ((float) Math.log(p) + ent);
				dLogits[off + i] = g;
			}
		}
	}
}
