package dev.swordai.core.rl;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;

import java.util.Arrays;
import java.util.Random;

/**
 * The tactics model: a small network of its own that chooses, every {@link Tactic#EVERY} ticks, how
 * the fighter fights until the next choice ({@link Tactic}). It reads the same numbers the acting
 * network reads, among them what the opponent has been doing over the last seconds
 * ({@code ObsSpec.SC_STYLE_AWAY} and the two after it), which the acting network had learned to make
 * next to no use of. Its choice turns rules on and off and is handed to the acting network as an input
 * ({@code tactic.in} in {@link ActorNet}), so that the acting network can learn to fight each way.
 *
 * <p>It learns at its own pace, from its own choices only, by PPO over the half-second steps it
 * decides at (the manager of a hierarchy learns at its own time scale: FeUdal Networks, Vezhnevets et
 * al. ICML 2017). What a choice brought is everything rewarded while it stood, discounted from the
 * tick it was made, plus the critic's value of where it left the fighter; the critic does not see the
 * tactic, so its value is the baseline the choice is measured against (see {@link #advantages}).
 *
 * <p>It does not start from nothing. Training a manager from scratch is slow and unsteady, and the
 * fighter learns on the screen at about a hundred updates an hour, so the model starts out with the
 * plan it would be given by hand: crit back most of the time, space against an opponent that has been
 * standing at the edge of the reach, and the other ways now and then to find out what they bring. That
 * plan is a hand-set part of the network, learned on from there like the rest (as the swing in the
 * air of {@link ActorNet} starts out knowing one rule).
 */
public final class TacticNet {
	static final int HIDDEN = 32;
	/** The three style inputs, read straight into the choice: the hand-set part. */
	static final int STYLE = 3;
	/** Where it starts: crit back about half the time, each other way about a sixth. */
	static final float PRIOR_COUNTER = 1.2f;
	/** And the more of the last seconds the opponent stood at the edge of the reach, the more it spaces. */
	static final float PRIOR_SPACE_EDGE = 3f;

	public final ParamSet params = new ParamSet();
	private final Dense hidden;
	private final Dense out;
	private final Dense style;
	/** The opponent reader's numbers into the hidden units, starting at zero; null without the reader. */
	private final Dense opp;

	public TacticNet(long seed) {
		this(seed, false);
	}

	/** @param reads whether it is handed the opponent reader's numbers ({@link OpponentReader}) */
	public TacticNet(long seed, boolean reads) {
		Random rng = new Random(seed);
		hidden = new Dense(params, "tactic.hidden", ObsSpec.VEC, HIDDEN, rng, (float) Math.sqrt(2.0));
		out = new Dense(params, "tactic.out", HIDDEN, Tactic.COUNT, rng, 0.01f);
		style = new Dense(params, "tactic.style", STYLE, Tactic.COUNT, rng, 0f);
		out.bias.w[Tactic.COUNTER] = PRIOR_COUNTER;
		style.weight.w[1 * Tactic.COUNT + Tactic.SPACE] = PRIOR_SPACE_EDGE;
		// Last, so a model saved before it loads as it was.
		opp = reads ? new Dense(params, "tactic.opp", OpponentReader.Z, HIDDEN, rng, 0f) : null;
	}

	public final class Workspace {
		final float[] h = new float[HIDDEN];
		final float[] dH = new float[HIDDEN];
		final float[] styleIn = new float[STYLE];
		final float[] styleOut = new float[Tactic.COUNT];
		final float[] oppZ = new float[OpponentReader.Z];
		final float[] oppPre = new float[HIDDEN];
		boolean oppUsed;
		public final float[] logits = new float[Tactic.COUNT];
		public final float[] probs = new float[Tactic.COUNT];
		final float[] dLogits = new float[Tactic.COUNT];
		final float[][] grads = params.newGrads();
		/** The log-probability of the last choice {@link #choose} made. */
		public float logp;
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	/** Fills {@code ws.logits} and {@code ws.probs}. */
	public void forward(Workspace ws, float[] vec) {
		forward(ws, vec, null);
	}

	/** @param oppIn the opponent reader's numbers, null for none */
	public void forward(Workspace ws, float[] vec, float[] oppIn) {
		hidden.forward(vec, 0, ws.h, 0);
		ws.oppUsed = opp != null && oppIn != null;
		if (ws.oppUsed) {
			System.arraycopy(oppIn, 0, ws.oppZ, 0, OpponentReader.Z);
			opp.forward(ws.oppZ, 0, ws.oppPre, 0);
			for (int i = 0; i < HIDDEN; i++) {
				ws.h[i] += ws.oppPre[i];
			}
		}
		for (int i = 0; i < HIDDEN; i++) {
			if (ws.h[i] < 0f) {
				ws.h[i] = 0f;
			}
		}
		out.forward(ws.h, 0, ws.logits, 0);
		styleInputs(vec, ws.styleIn);
		style.forward(ws.styleIn, 0, ws.styleOut, 0);
		for (int k = 0; k < Tactic.COUNT; k++) {
			ws.logits[k] += ws.styleOut[k];
		}
		softmax(ws.logits, ws.probs);
	}

	/** Back-propagates {@code ws.dLogits} into {@code ws.grads}; must follow {@link #forward} on the same workspace and input. */
	void backward(Workspace ws, float[] vec) {
		style.backward(ws.styleIn, 0, ws.dLogits, 0, null, 0, ws.grads);
		out.backward(ws.h, 0, ws.dLogits, 0, ws.dH, 0, ws.grads);
		for (int i = 0; i < HIDDEN; i++) {
			if (ws.h[i] <= 0f) {
				ws.dH[i] = 0f;
			}
		}
		hidden.backward(vec, 0, ws.dH, 0, null, 0, ws.grads);
		if (ws.oppUsed) {
			opp.backward(ws.oppZ, 0, ws.dH, 0, null, 0, ws.grads);
		}
	}

	/** Draws a tactic from the model's choices on {@code vec}; its log-probability is left in {@code ws.logp}. */
	public int choose(Workspace ws, float[] vec, Random rng) {
		return choose(ws, vec, null, rng);
	}

	/** @param oppIn the opponent reader's numbers, null for none */
	public int choose(Workspace ws, float[] vec, float[] oppIn, Random rng) {
		forward(ws, vec, oppIn);
		float u = rng.nextFloat();
		int pick = Tactic.COUNT - 1;
		for (int k = 0; k < Tactic.COUNT; k++) {
			u -= ws.probs[k];
			if (u < 0f) {
				pick = k;
				break;
			}
		}
		ws.logp = (float) Math.log(Math.max(1e-8f, ws.probs[pick]));
		return pick;
	}

	/** The plan it starts with, on these inputs: its first choices before anything is learned. */
	static void priorChances(float[] vec, float[] probs) {
		float[] logits = new float[Tactic.COUNT];
		logits[Tactic.COUNTER] = PRIOR_COUNTER;
		logits[Tactic.SPACE] = PRIOR_SPACE_EDGE * vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE];
		softmax(logits, probs);
	}

	static void styleInputs(float[] vec, float[] into) {
		into[0] = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_AWAY];
		into[1] = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE];
		into[2] = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_DIST];
	}

	private static void softmax(float[] logits, float[] probs) {
		float max = Float.NEGATIVE_INFINITY;
		for (float l : logits) {
			max = Math.max(max, l);
		}
		double sum = 0;
		for (int k = 0; k < logits.length; k++) {
			probs[k] = (float) Math.exp(logits[k] - max);
			sum += probs[k];
		}
		for (int k = 0; k < logits.length; k++) {
			probs[k] /= (float) sum;
		}
	}

	/**
	 * What each choice in the rollout brought over the critic's expectation, at the half-second scale
	 * (generalised advantage estimation over the choices, as for a semi-Markov decision process):
	 * for a choice made on step s that stood until step e,
	 * {@code A = sum over s<=t<e of gamma^(t-s) delta_t + gamma^(e-s) lambda A_next},
	 * with {@code delta_t} the critic's own TD error of step t. The sum telescopes to the discounted
	 * reward while the choice stood plus the discounted value it left, minus the value it was made
	 * in. The next choice's part only counts when it was made in the same episode and recorded.
	 * Steps that are not choices are left at NaN.
	 *
	 * @param lastValue the value after the last stored step, as for {@link Rollout#computeAdvantages}
	 */
	static float[] advantages(Rollout r, float lastValue, float gamma, float lambda) {
		int n = r.size;
		float[] adv = new float[n];
		Arrays.fill(adv, Float.NaN);
		float run = 0f;
		float disc = 1f;
		float next = 0f;
		boolean nextValid = false;
		for (int t = n - 1; t >= 0; t--) {
			if (r.done[t] || t + 1 < n && r.tactic[t + 1] != r.tactic[t] && !r.tacticStart[t + 1]) {
				// What follows is another episode, or a choice that was not recorded: nothing of it is this one's.
				run = 0f;
				disc = 1f;
				nextValid = false;
			}
			float nextValue = r.done[t] ? 0f : t == n - 1 ? lastValue : r.value[t + 1];
			float delta = r.reward[t] + gamma * nextValue - r.value[t];
			run = delta + gamma * run;
			disc *= gamma;
			if (r.tacticStart[t]) {
				float a = run + (nextValid ? disc * lambda * next : 0f);
				adv[t] = a;
				next = a;
				nextValid = true;
				run = 0f;
				disc = 1f;
			}
		}
		return adv;
	}

	/** What one update of the tactics model did, for the log. */
	public static final class Stats {
		public int choices;
		public final int[] chosen = new int[Tactic.COUNT];
		/** Mean advantage (in the scaled reward) of the choices of each tactic. */
		public final float[] meanAdvantage = new float[Tactic.COUNT];
		public float entropy;
		public float approxKl;
		public int epochsRun;

		@Override
		public String toString() {
			StringBuilder b = new StringBuilder();
			b.append(choices).append(" choices:");
			for (int k = 0; k < Tactic.COUNT; k++) {
				b.append(String.format(java.util.Locale.ROOT, " %s %d (%+.3f)", Tactic.name(k), chosen[k], meanAdvantage[k]));
			}
			b.append(String.format(java.util.Locale.ROOT, ", entropy %.3f, KL %.4f, epochs %d", entropy, approxKl, epochsRun));
			return b.toString();
		}
	}

	/**
	 * One PPO update of the tactics model on the choices recorded in {@code r} (clipped surrogate,
	 * advantages normalised over the choices, an entropy bonus, stopped early past {@code klStop}).
	 * The acting network and the critic are not touched. Fewer than four choices: nothing is learned.
	 */
	public Stats update(Rollout r, float lastValue, float gamma, float lambda, float lr, float clip, float entropyCoef,
			int epochs, float klStop, float maxGradNorm, Random rng) {
		return update(r, lastValue, gamma, lambda, lr, clip, entropyCoef, 0f, epochs, klStop, maxGradNorm, rng);
	}

	/** @param priorWeight how firmly it is held near the plan it starts with ({@code Config.tacticPriorWeight}) */
	public Stats update(Rollout r, float lastValue, float gamma, float lambda, float lr, float clip, float entropyCoef,
			float priorWeight, int epochs, float klStop, float maxGradNorm, Random rng) {
		Stats s = new Stats();
		float[] adv = advantages(r, lastValue, gamma, lambda);
		int[] idx = new int[r.size];
		int n = 0;
		double[] advSum = new double[Tactic.COUNT];
		for (int i = 0; i < r.size; i++) {
			if (r.tacticStart[i] && !Float.isNaN(adv[i]) && r.tactic[i] >= 0 && r.tactic[i] < Tactic.COUNT) {
				idx[n++] = i;
				s.chosen[r.tactic[i]]++;
				advSum[r.tactic[i]] += adv[i];
			}
		}
		s.choices = n;
		for (int k = 0; k < Tactic.COUNT; k++) {
			s.meanAdvantage[k] = s.chosen[k] == 0 ? 0f : (float) (advSum[k] / s.chosen[k]);
		}
		if (n < 4) {
			return s;
		}
		double mean = 0;
		for (int j = 0; j < n; j++) {
			mean += adv[idx[j]];
		}
		mean /= n;
		double var = 0;
		for (int j = 0; j < n; j++) {
			double d = adv[idx[j]] - mean;
			var += d * d;
		}
		float std = (float) Math.sqrt(var / n) + 1e-8f;
		Workspace ws = newWorkspace();
		float[] prior = new float[Tactic.COUNT];
		for (int epoch = 0; epoch < epochs; epoch++) {
			ParamSet.zero(ws.grads);
			double kl = 0;
			double ent = 0;
			for (int j = 0; j < n; j++) {
				int i = idx[j];
				int a = r.tactic[i];
				forward(ws, r.vec[i], opp == null ? null : r.opp[i]);
				float logp = (float) Math.log(Math.max(1e-8f, ws.probs[a]));
				float ratio = (float) Math.exp(logp - r.tacticLogp[i]);
				float an = (float) ((adv[i] - mean) / std);
				boolean inside = ratio >= 1f - clip && ratio <= 1f + clip;
				float surr1 = ratio * an;
				float surr2 = Math.max(1f - clip, Math.min(1f + clip, ratio)) * an;
				float dLogp = (surr1 <= surr2 || inside) ? -an * ratio : 0f;
				float h = 0f;
				for (int k = 0; k < Tactic.COUNT; k++) {
					float p = ws.probs[k];
					h -= p * (float) Math.log(Math.max(1e-8f, p));
				}
				if (priorWeight > 0f) {
					priorChances(r.vec[i], prior);
				}
				for (int k = 0; k < Tactic.COUNT; k++) {
					float p = ws.probs[k];
					float dlogpdl = (k == a ? 1f : 0f) - p;
					ws.dLogits[k] = dLogp * dlogpdl + entropyCoef * p * ((float) Math.log(Math.max(1e-8f, p)) + h);
					if (priorWeight > 0f) {
						// KL(plan || model): pulls each choice back towards the plan's.
						ws.dLogits[k] += priorWeight * (p - prior[k]);
					}
				}
				backward(ws, r.vec[i]);
				kl += r.tacticLogp[i] - logp;
				ent += h;
			}
			Adam.step(params, ws.grads, lr, 1f / n, maxGradNorm);
			s.epochsRun = epoch + 1;
			s.approxKl = (float) (kl / n);
			s.entropy = (float) (ent / n);
			if (klStop > 0f && s.approxKl > klStop) {
				break;
			}
		}
		return s;
	}
}
