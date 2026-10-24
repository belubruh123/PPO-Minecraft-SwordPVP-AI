package dev.swordai.core.school;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ObsSpec;

import java.util.Random;

/**
 * Guesses what the opponent is about to do (plan part 4): which technique it is going into in the next
 * moments, and whether in the next four ticks it will jump, hold S, swing. It reads the fighter's own
 * inputs and the memory of this fight, and is taught from what the bots of the school say they do
 * (their technique and keys every tick), as an auxiliary task (Hernandez-Leal et al. 2019; DRON).
 *
 * <p>Kept apart from the policy: it learns only from its own loss, and its guesses go into the acting
 * network as inputs that start at zero weight, so the network can come to use them or not. In a real
 * fight there is nothing to teach it from; it guesses all the same.
 */
public final class Predictor {
	public static final int IN = ObsSpec.VEC + SchoolObs.MEM;
	public static final int HIDDEN = 64;
	/** Jump, S, swing within the next {@link #AHEAD} ticks. */
	public static final int ACTS = 3;
	public static final int OUT = Technique.COUNT + ACTS;
	public static final int AHEAD = 4;
	/** The technique it is going into: the first one other than the ordinary fight within this many ticks. */
	public static final int TECHNIQUE_AHEAD = 8;

	public final ParamSet params = new ParamSet();
	private final Dense hidden;
	private final Dense out;

	public Predictor(long seed) {
		Random rng = new Random(seed);
		hidden = new Dense(params, "pred.hidden", IN, HIDDEN, rng, (float) Math.sqrt(2.0));
		out = new Dense(params, "pred.out", HIDDEN, OUT, rng, 0.1f);
	}

	public final class Workspace {
		final float[] in = new float[IN];
		final float[] h = new float[HIDDEN];
		final float[] logits = new float[OUT];
		/** What it hands the acting network: the technique as chances, the three acts as chances. */
		public final float[] guess = new float[OUT];
		final float[] dLogits = new float[OUT];
		final float[] dH = new float[HIDDEN];
		final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	public void forward(Workspace ws, float[] vec, float[] mem) {
		System.arraycopy(vec, 0, ws.in, 0, ObsSpec.VEC);
		System.arraycopy(mem, 0, ws.in, ObsSpec.VEC, SchoolObs.MEM);
		hidden.forward(ws.in, 0, ws.h, 0);
		for (int i = 0; i < HIDDEN; i++) {
			if (ws.h[i] < 0f) {
				ws.h[i] = 0f;
			}
		}
		out.forward(ws.h, 0, ws.logits, 0);
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < Technique.COUNT; i++) {
			max = Math.max(max, ws.logits[i]);
		}
		float sum = 0f;
		for (int i = 0; i < Technique.COUNT; i++) {
			ws.guess[i] = (float) Math.exp(ws.logits[i] - max);
			sum += ws.guess[i];
		}
		for (int i = 0; i < Technique.COUNT; i++) {
			ws.guess[i] /= sum;
		}
		for (int a = 0; a < ACTS; a++) {
			ws.guess[Technique.COUNT + a] = (float) (1.0 / (1.0 + Math.exp(-ws.logits[Technique.COUNT + a])));
		}
	}

	/** Statistics of one round of learning. */
	public static final class Stats {
		public int samples;
		public double loss;
		/** How often the technique guessed most likely was the one that came; and how often that was not the ordinary fight. */
		public int right;
		public int labelled;
		public int rightSpecial;
		public int special;

		@Override
		public String toString() {
			return samples == 0 ? "nothing to learn from" : String.format(java.util.Locale.ROOT,
					"loss %.3f over %d, technique guessed right %.0f%% (%.0f%% of the %d not the ordinary fight)", loss / samples, samples,
					100.0 * right / Math.max(1, labelled), 100.0 * rightSpecial / Math.max(1, special), special);
		}
	}

	/**
	 * Learns from the labelled steps of a rollout: {@code batches} minibatches of {@code batch} steps.
	 * A step's label is {@code technique[i]} (or -1 for none) and its three acts in {@code acts[i]}.
	 */
	public Stats learn(SchoolRollout r, float lr, int batches, int batch, float maxNorm, Random rng) {
		Stats s = new Stats();
		int[] labelled = new int[r.size];
		int n = 0;
		for (int i = 0; i < r.size; i++) {
			if (r.predTechnique[i] >= 0) {
				labelled[n++] = i;
			}
		}
		if (n == 0) {
			return s;
		}
		// Each technique weighted by how rare it is (the square root of the inverse of its share, at most 10): the
		// ordinary fight is most of the ticks, and unweighted it guessed the ordinary fight on almost every technique
		// moment (24 Oct: 17% right on them), so the acting network was not told that a punish crit is coming
		// (class-balanced loss, Cui et al. 2019).
		int[] count = new int[Technique.COUNT];
		int classes = 0;
		for (int k = 0; k < n; k++) {
			if (count[r.predTechnique[labelled[k]]]++ == 0) {
				classes++;
			}
		}
		float[] weight = new float[Technique.COUNT];
		for (int c = 0; c < Technique.COUNT; c++) {
			weight[c] = count[c] == 0 ? 0f : (float) Math.min(10.0, Math.sqrt(n / (double) (classes * count[c])));
		}
		Workspace ws = newWorkspace();
		for (int b = 0; b < batches; b++) {
			ParamSet.zero(ws.grads);
			int size = Math.min(batch, n);
			for (int k = 0; k < size; k++) {
				int i = labelled[rng.nextInt(n)];
				forward(ws, r.vec[i], r.mem[i]);
				int label = r.predTechnique[i];
				for (int c = 0; c < Technique.COUNT; c++) {
					ws.dLogits[c] = weight[label] * (ws.guess[c] - (c == label ? 1f : 0f));
				}
				s.loss -= Math.log(Math.max(1e-9f, ws.guess[label]));
				int best = 0;
				for (int c = 1; c < Technique.COUNT; c++) {
					if (ws.guess[c] > ws.guess[best]) {
						best = c;
					}
				}
				s.labelled++;
				s.right += best == label ? 1 : 0;
				if (label != Technique.BASE.ordinal()) {
					s.special++;
					s.rightSpecial += best == label ? 1 : 0;
				}
				for (int a = 0; a < ACTS; a++) {
					float y = (r.predActs[i] >> a & 1) != 0 ? 1f : 0f;
					float p = ws.guess[Technique.COUNT + a];
					ws.dLogits[Technique.COUNT + a] = p - y;
					s.loss -= y * Math.log(Math.max(1e-9f, p)) + (1 - y) * Math.log(Math.max(1e-9f, 1 - p));
				}
				out.backward(ws.h, 0, ws.dLogits, 0, ws.dH, 0, ws.grads);
				for (int h = 0; h < HIDDEN; h++) {
					if (ws.h[h] <= 0f) {
						ws.dH[h] = 0f;
					}
				}
				hidden.backward(ws.in, 0, ws.dH, 0, null, 0, ws.grads);
				s.samples++;
			}
			Adam.step(params, ws.grads, lr, 1f / size, maxNorm);
		}
		return s;
	}
}
