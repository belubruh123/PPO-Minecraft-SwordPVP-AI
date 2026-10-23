package dev.swordai.core.rl;

import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;

import java.util.Arrays;
import java.util.Random;

/**
 * A memory of the last 3.2 seconds for the acting network ({@code Config.rhythmNet}): a causal temporal convolution (TCN:
 * Bai, Kolter and Koltun 2018; WaveNet, van den Oord et al. 2016) over the opponent reader's window ({@link
 * OpponentReader.Window}: 32 samples of two ticks, oldest first, ten numbers each: distance, in sight, moving away and up,
 * both swords, hits either way, the knockback, time in the fight). Four layers of {@link #H} channels, kernel {@link #K},
 * dilations 1, 2, 4 and 8, so the newest sample sees the 30 before it (3 seconds; the oldest one only through the mean);
 * out comes what the last layer makes of the newest sample and its mean over the window. The acting network takes that into its trunk and into the crit and escape models
 * through weights that start at zero, so a network plays as before until it learns to use it, and it is learned from the
 * policy's own gradient (the reader is taught only to name the opponent, and a one-frame network cannot see a rhythm).
 *
 * <p>Gemini's review, 23 October (Part 2, option A): a 1.9 fight is a 12.5-tick cycle of two swords, and a spacer that
 * crits and holds S, a stander at 3.3 blocks and a timer that jumps on its cooldown look alike in one frame; a window needs
 * no backpropagation through time, so the shuffled minibatches stay as they are. The user, 23 October: build it.
 */
final class RhythmNet {
	static final int T = OpponentReader.SAMPLES;
	static final int C = OpponentReader.CHANNELS;
	static final int H = 16;
	static final int K = 3;
	static final int[] DILATION = {1, 2, 4, 8};
	static final int L = DILATION.length;
	/** What it hands on: the last layer at the newest sample, and its mean over the window. */
	static final int OUT = 2 * H;

	private final Dense[] layers = new Dense[L];

	RhythmNet(ParamSet params, String name, Random rng) {
		for (int l = 0; l < L; l++) {
			layers[l] = new Dense(params, name + ".conv" + l, K * width(l), H, rng, (float) Math.sqrt(2.0));
		}
	}

	private static int width(int layer) {
		return layer == 0 ? C : H;
	}

	/** One pass's numbers, kept for the backward pass. */
	final class Cache {
		/** What each position of each layer read: itself and the {@link #K} - 1 dilated positions before it, zero before the window. */
		final float[][][] taps = new float[L][T][];
		final float[][][] out = new float[L][T][H];
		final float[][][] dOut = new float[L][T][H];
		final float[] tapGrad = new float[K * H];
		public final float[] feat = new float[OUT];

		Cache() {
			for (int l = 0; l < L; l++) {
				for (int t = 0; t < T; t++) {
					taps[l][t] = new float[K * width(l)];
				}
			}
		}
	}

	Cache newCache() {
		return new Cache();
	}

	/** @param window the reader's window, oldest sample first ({@link OpponentReader#INPUTS} numbers) */
	void forward(float[] window, Cache c) {
		for (int l = 0; l < L; l++) {
			int w = width(l);
			int d = DILATION[l];
			for (int t = 0; t < T; t++) {
				float[] tap = c.taps[l][t];
				for (int k = 0; k < K; k++) {
					int src = t - k * d;
					if (src < 0) {
						Arrays.fill(tap, k * w, (k + 1) * w, 0f);
					} else if (l == 0) {
						System.arraycopy(window, src * C, tap, k * w, w);
					} else {
						System.arraycopy(c.out[l - 1][src], 0, tap, k * w, w);
					}
				}
				float[] o = c.out[l][t];
				layers[l].forward(tap, 0, o, 0);
				for (int h = 0; h < H; h++) {
					if (o[h] < 0f) {
						o[h] = 0f;
					}
				}
			}
		}
		float[][] top = c.out[L - 1];
		System.arraycopy(top[T - 1], 0, c.feat, 0, H);
		Arrays.fill(c.feat, H, OUT, 0f);
		for (int t = 0; t < T; t++) {
			for (int h = 0; h < H; h++) {
				c.feat[H + h] += top[t][h] / T;
			}
		}
	}

	/** Back-propagates {@code dFeat} into {@code grads}; must follow {@link #forward} on the same cache. */
	void backward(Cache c, float[] dFeat, float[][] grads) {
		float[][] dTop = c.dOut[L - 1];
		for (int t = 0; t < T; t++) {
			for (int h = 0; h < H; h++) {
				dTop[t][h] = dFeat[H + h] / T;
			}
		}
		for (int h = 0; h < H; h++) {
			dTop[T - 1][h] += dFeat[h];
		}
		for (int l = L - 1; l >= 0; l--) {
			int w = width(l);
			int d = DILATION[l];
			if (l > 0) {
				for (float[] row : c.dOut[l - 1]) {
					Arrays.fill(row, 0f);
				}
			}
			for (int t = 0; t < T; t++) {
				float[] dy = c.dOut[l][t];
				float[] o = c.out[l][t];
				for (int h = 0; h < H; h++) {
					if (o[h] <= 0f) {
						dy[h] = 0f;
					}
				}
				layers[l].backward(c.taps[l][t], 0, dy, 0, l > 0 ? c.tapGrad : null, 0, grads);
				if (l > 0) {
					for (int k = 0; k < K; k++) {
						int src = t - k * d;
						if (src >= 0) {
							float[] into = c.dOut[l - 1][src];
							for (int i = 0; i < w; i++) {
								into[i] += c.tapGrad[k * w + i];
							}
						}
					}
				}
			}
		}
	}
}
