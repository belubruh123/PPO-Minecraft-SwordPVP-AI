import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'

e = Ed(R + 'config/Config.java')
e.rep("""	/** Its entropy bonus: keeps it trying every way now and then. */
	public float tacticEntropy = 0.01f;
""", """	/** Its entropy bonus: keeps it trying every way now and then. */
	public float tacticEntropy = 0.01f;
	/**
	 * How firmly the tactics model is held near the plan it starts with (a KL to it on every choice, as
	 * the acting network is held near the installed one), 0 for not at all. On 21 October, unheld, it
	 * went from crit back on 45% of its choices to 7% in two hours of training: crit back pays against
	 * the spacer and not against the jump-crit chaser and the whiffer, and without telling them apart it
	 * dropped it everywhere; the spacer went from +58 to -70 a minute.
	 */
	public float tacticPriorWeight = 0f;
""")
e.save()

e = Ed(R + 'rl/TacticNet.java')
e.rep("""	/** The class a fight""", """	/** The class a fight""") if False else None
e.rep("""	static void styleInputs(float[] vec, float[] into) {""", """	/** The plan it starts with, on these inputs: its first choices before anything is learned. */
	static void priorChances(float[] vec, float[] probs) {
		float[] logits = new float[Tactic.COUNT];
		logits[Tactic.COUNTER] = PRIOR_COUNTER;
		logits[Tactic.SPACE] = PRIOR_SPACE_EDGE * vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_EDGE];
		softmax(logits, probs);
	}

	static void styleInputs(float[] vec, float[] into) {""")
e.rep("""	public Stats update(Rollout r, float lastValue, float gamma, float lambda, float lr, float clip, float entropyCoef,
			int epochs, float klStop, float maxGradNorm, Random rng) {""", """	public Stats update(Rollout r, float lastValue, float gamma, float lambda, float lr, float clip, float entropyCoef,
			int epochs, float klStop, float maxGradNorm, Random rng) {
		return update(r, lastValue, gamma, lambda, lr, clip, entropyCoef, 0f, epochs, klStop, maxGradNorm, rng);
	}

	/** @param priorWeight how firmly it is held near the plan it starts with ({@code Config.tacticPriorWeight}) */
	public Stats update(Rollout r, float lastValue, float gamma, float lambda, float lr, float clip, float entropyCoef,
			float priorWeight, int epochs, float klStop, float maxGradNorm, Random rng) {""")
e.rep("""		Workspace ws = newWorkspace();
		for (int epoch = 0; epoch < epochs; epoch++) {""", """		Workspace ws = newWorkspace();
		float[] prior = new float[Tactic.COUNT];
		for (int epoch = 0; epoch < epochs; epoch++) {""")
e.rep("""				for (int k = 0; k < Tactic.COUNT; k++) {
					float p = ws.probs[k];
					float dlogpdl = (k == a ? 1f : 0f) - p;
					ws.dLogits[k] = dLogp * dlogpdl + entropyCoef * p * ((float) Math.log(Math.max(1e-8f, p)) + h);
				}""", """				if (priorWeight > 0f) {
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
				}""")
e.save()

e = Ed(R + 'rl/Learner.java')
e.rep("""			TacticNet.Stats t = tactics.update(rollout, lastValue, cfg.gamma, cfg.lambda, cfg.tacticLr, cfg.clip, cfg.tacticEntropy,
					cfg.epochs, cfg.klStop, cfg.maxGradNorm, rng);""", """			TacticNet.Stats t = tactics.update(rollout, lastValue, cfg.gamma, cfg.lambda, cfg.tacticLr, cfg.clip, cfg.tacticEntropy,
					cfg.tacticPriorWeight, cfg.epochs, cfg.klStop, cfg.maxGradNorm, rng);""")
e.save()
