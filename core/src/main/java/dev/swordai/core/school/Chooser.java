package dev.swordai.core.school;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ObsSpec;

import java.util.Random;

/**
 * Decides which technique to do now (the user's idea, 24 Oct: "a decision model that actually knows what these
 * techniques are, and its output is an input to the main model to make it do that technique"). Its choice goes into
 * the acting network as {@link SchoolNet#INTENT}; the acting network does the keys.
 *
 * <p>It reads the fighter's own inputs, the memory of the fight, the {@link Predictor}'s guess of what the opponent
 * is going into, and what it chose on the tick before and for how long, so carrying on with a technique is easy and the
 * real choice is when to start one. It learns from the technique teacher's own choices (the file's "when"); it is
 * kept apart from the acting network and learns only from its own loss.
 *
 * <p>Since 25 Oct 09:35 each of those choices counts by how the fight went after it (advantage-weighted regression):
 * a choice of the teacher's that was followed by more damage traded than the critic expected counts up to
 * {@link #AWR_MOST} times, one followed by less down toward nothing. The user: "instead of guard it should learn when to
 * not sprint hit when to and when to crit, I thought decision model does that"; copying the teacher alone, it chose the
 * golden rule's held swing against jump-in critters every time, and lost to them in every check of the night.
 */
public final class Chooser {
	/** Advantage-weighted regression: weight exp(advantage / AWR_BETA), the advantages scaled to one deviation, at most AWR_MOST. */
	public static final float AWR_BETA = 1f;
	public static final float AWR_MOST = 3f;
	/** Off in run 2 (EXPERIMENTS.md): the 06:54 recipe (on from 09:41). */
	public static final boolean AWR = false;
	/**
	 * Learns when to do what from how the fight went after its own choices (policy gradient on its own drawn choices,
	 * the advantages scaled to one deviation and cut at {@link #OUTCOME_CUT}), on top of copying the teacher's, which keeps
	 * it near the file's "when" until the outcomes say otherwise. The user, 26 Oct 12:55: "I am afraid decision model
	 * cannot know when to do what so let it learn as well". AWR above only weighs the teacher's own choices, so it could
	 * never choose what the teacher does not.
	 */
	public static final boolean OUTCOME = true;
	public static final float OUTCOME_WEIGHT = 1f;
	public static final float OUTCOME_CUT = 3f;
	public static final int IN = ObsSpec.VEC + SchoolObs.MEM + Predictor.OUT + SchoolNet.INTENT;
	public static final int HIDDEN = 128;

	public final ParamSet params = new ParamSet();
	private final Dense hidden1;
	private final Dense hidden2;
	private final Dense out;

	public Chooser(long seed) {
		Random rng = new Random(seed);
		float relu = (float) Math.sqrt(2.0);
		hidden1 = new Dense(params, "choose.h1", IN, HIDDEN, rng, relu);
		hidden2 = new Dense(params, "choose.h2", HIDDEN, HIDDEN, rng, relu);
		out = new Dense(params, "choose.out", HIDDEN, Technique.COUNT, rng, 0.1f);
	}

	public final class Workspace {
		final float[] in = new float[IN];
		final float[] h1 = new float[HIDDEN];
		final float[] h2 = new float[HIDDEN];
		final float[] logits = new float[Technique.COUNT];
		/** The chance of each technique. */
		public final float[] chances = new float[Technique.COUNT];
		final float[] dLogits = new float[Technique.COUNT];
		final float[] dH2 = new float[HIDDEN];
		final float[] dH1 = new float[HIDDEN];
		final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	/**
	 * @param side the side input of the step: the predictor's guess in its first {@link Predictor#OUT} values
	 * @param before the technique chosen on the tick before ({@link SchoolNet#INTENT} values)
	 * @return the likeliest technique (ordinal)
	 */
	public int forward(Workspace ws, float[] vec, float[] mem, float[] side, float[] before) {
		int o = 0;
		System.arraycopy(vec, 0, ws.in, o, ObsSpec.VEC);
		o += ObsSpec.VEC;
		System.arraycopy(mem, 0, ws.in, o, SchoolObs.MEM);
		o += SchoolObs.MEM;
		System.arraycopy(side, 0, ws.in, o, Predictor.OUT);
		o += Predictor.OUT;
		System.arraycopy(before, 0, ws.in, o, SchoolNet.INTENT);
		hidden1.forward(ws.in, 0, ws.h1, 0);
		relu(ws.h1);
		hidden2.forward(ws.h1, 0, ws.h2, 0);
		relu(ws.h2);
		out.forward(ws.h2, 0, ws.logits, 0);
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < Technique.COUNT; i++) {
			max = Math.max(max, ws.logits[i]);
		}
		float sum = 0f;
		for (int i = 0; i < Technique.COUNT; i++) {
			ws.chances[i] = (float) Math.exp(ws.logits[i] - max);
			sum += ws.chances[i];
		}
		int best = 0;
		for (int i = 0; i < Technique.COUNT; i++) {
			ws.chances[i] /= sum;
			if (ws.chances[i] > ws.chances[best]) {
				best = i;
			}
		}
		return best;
	}

	private static void relu(float[] x) {
		for (int i = 0; i < x.length; i++) {
			if (x[i] < 0f) {
				x[i] = 0f;
			}
		}
	}

	/** The technique a {@link SchoolNet#INTENT} vector holds. */
	static int techniqueOf(float[] intent, int off) {
		int best = Technique.BASE.ordinal();
		for (int i = 0; i < Technique.COUNT; i++) {
			if (intent[off + i] > 0.5f) {
				best = i;
			}
		}
		return best;
	}

	/** Statistics of one round of learning. */
	public static final class Stats {
		public int samples;
		public double loss;
		public int right;
		/** On the teacher's technique ticks (not the ordinary fight), and on its starts of one. */
		public int rightTechnique;
		public int technique;
		public int rightStart;
		public int starts;
		/** Ordinary-fight ticks after ordinary-fight ticks on which it would have started a technique. */
		public int falseStarts;
		public int calm;
		/** The sum of the advantage weights (see AWR_BETA). */
		public double weight;
		/** Its own choices learned from (OUTCOME): those away from the teacher's, and the advantage after each sort. */
		public int own;
		public int away;
		public double awayAdvantage;
		public double asTeacherAdvantage;

		@Override
		public String toString() {
			return samples == 0 ? "nothing to learn from" : String.format(java.util.Locale.ROOT,
					"loss %.3f over %d, chose as the teacher %.0f%%, on its techniques %.0f%% of %d, its starts %.0f%% of %d, "
							+ "a start where it did none %.1f%% of %d", loss / samples, samples, 100.0 * right / samples,
					100.0 * rightTechnique / Math.max(1, technique), technique, 100.0 * rightStart / Math.max(1, starts), starts,
					100.0 * falseStarts / Math.max(1, calm), calm) + String.format(java.util.Locale.ROOT, ", weighed by the outcome %.2f on average",
					weight / samples) + (own == 0 ? "" : String.format(java.util.Locale.ROOT,
					"; its own choices %d, away from the teacher's %.0f%%, the outcome after those %+.3f, after the teacher's %+.3f",
					own, 100.0 * away / own, awayAdvantage / Math.max(1, away), asTeacherAdvantage / Math.max(1, own - away)));
		}
	}

	/**
	 * Learns from the steps of a rollout that have a teacher's label ({@code intentLabel} at least 0): {@code batches}
	 * minibatches of {@code batch} steps, every step alike. Weighted by rarity (and its starts twice) it started a
	 * technique on a fifth of the ordinary fight's ticks (24 Oct 11:20): a loss weighted by class learns the chances
	 * times the weights (Menon et al. 2021, logit adjustment), and its likeliest is then a rare technique far too often.
	 * In a fight a start it should not make (S held, a jump, no hit) costs far more than one it misses (it fights as
	 * the installed model taught), so it learns the chances as they are.
	 */
	public Stats learn(SchoolRollout r, float lr, int batches, int batch, float maxNorm, Random rng) {
		Stats s = new Stats();
		int[] labelled = new int[r.size];
		int n = 0;
		for (int i = 0; i < r.size; i++) {
			if (r.intentLabel[i] >= 0) {
				labelled[n++] = i;
			}
		}
		if (n == 0) {
			return s;
		}
		int base = Technique.BASE.ordinal();
		double mean = 0.0;
		for (int k = 0; k < n; k++) {
			mean += r.advantage[labelled[k]];
		}
		mean /= n;
		double var = 0.0;
		for (int k = 0; k < n; k++) {
			double d = r.advantage[labelled[k]] - mean;
			var += d * d;
		}
		float std = (float) Math.max(1e-6, Math.sqrt(var / n));
		// Its own choices' advantages scaled apart (OUTCOME).
		double ownMean = 0.0;
		int owns = 0;
		for (int k = 0; k < n; k++) {
			if (r.choice[labelled[k]] >= 0) {
				ownMean += r.advantage[labelled[k]];
				owns++;
			}
		}
		ownMean /= Math.max(1, owns);
		double ownVar = 0.0;
		for (int k = 0; k < n; k++) {
			if (r.choice[labelled[k]] >= 0) {
				double d = r.advantage[labelled[k]] - ownMean;
				ownVar += d * d;
			}
		}
		float ownStd = (float) Math.max(1e-6, Math.sqrt(ownVar / Math.max(1, owns)));
		for (int k = 0; k < n; k++) {
			int i = labelled[k];
			if (r.choice[i] >= 0) {
				s.own++;
				if (r.choice[i] != r.intentLabel[i]) {
					s.away++;
					s.awayAdvantage += r.advantage[i];
				} else {
					s.asTeacherAdvantage += r.advantage[i];
				}
			}
		}
		Workspace ws = newWorkspace();
		for (int b = 0; b < batches; b++) {
			ParamSet.zero(ws.grads);
			int size = Math.min(batch, n);
			for (int k = 0; k < size; k++) {
				int i = labelled[rng.nextInt(n)];
				int best = forward(ws, r.vec[i], r.mem[i], r.pred[i], r.prevIntent[i]);
				int label = r.intentLabel[i];
				int before = techniqueOf(r.prevIntent[i], 0);
				boolean start = label != base && label != before;
				float w = AWR ? (float) Math.min(AWR_MOST, Math.exp((r.advantage[i] - mean) / std / AWR_BETA)) : 1f;
				s.weight += w;
				for (int c = 0; c < Technique.COUNT; c++) {
					ws.dLogits[c] = w * (ws.chances[c] - (c == label ? 1f : 0f));
				}
				int own = r.choice[i];
				if (OUTCOME && own >= 0) {
					// -a log p(own): its own choice up when the fight went better after it than expected, down when worse.
					float a = OUTCOME_WEIGHT * Math.max(-OUTCOME_CUT, Math.min(OUTCOME_CUT, (float) ((r.advantage[i] - ownMean) / ownStd)));
					for (int c = 0; c < Technique.COUNT; c++) {
						ws.dLogits[c] += a * (ws.chances[c] - (c == own ? 1f : 0f));
					}
				}
				s.loss -= Math.log(Math.max(1e-9f, ws.chances[label]));
				s.samples++;
				s.right += best == label ? 1 : 0;
				if (label != base) {
					s.technique++;
					s.rightTechnique += best == label ? 1 : 0;
				}
				if (start) {
					s.starts++;
					s.rightStart += best == label ? 1 : 0;
				}
				if (label == base && before == base) {
					s.calm++;
					s.falseStarts += best != base ? 1 : 0;
				}
				out.backward(ws.h2, 0, ws.dLogits, 0, ws.dH2, 0, ws.grads);
				gate(ws.dH2, ws.h2);
				hidden2.backward(ws.h1, 0, ws.dH2, 0, ws.dH1, 0, ws.grads);
				gate(ws.dH1, ws.h1);
				hidden1.backward(ws.in, 0, ws.dH1, 0, null, 0, ws.grads);
			}
			Adam.step(params, ws.grads, lr, 1f / size, maxNorm);
		}
		return s;
	}

	private static void gate(float[] d, float[] h) {
		for (int i = 0; i < d.length; i++) {
			if (h[i] <= 0f) {
				d[i] = 0f;
			}
		}
	}
}
