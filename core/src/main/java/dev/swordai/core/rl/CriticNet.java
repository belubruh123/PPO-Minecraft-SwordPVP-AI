package dev.swordai.core.rl;

import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;

import java.util.Random;

/**
 * Value network. It reads the actor's vector input plus privileged ground truth, and is used only
 * to compute advantages during training; it never influences what the agent does at play time.
 */
public final class CriticNet {
	private static final int HIDDEN = 128;
	/** Kinds of fight the critic can be told apart ({@code Config.criticTask}); see {@code Trainer.episodeTask}. */
	public static final int TASKS = 32;

	public final ParamSet params = new ParamSet();
	public final boolean privileged;

	private final Dense l1;
	private final Dense l2;
	private final Dense out;
	/**
	 * Which kind of fight it is (the opponent, a drill, a replay of a lost fight), one-hot, into the
	 * first layer; null when the critic is not told. The opponents differ more than anything else the
	 * critic sees in what a fight is worth (a no-knockback bot deals three times what a runner does),
	 * and without this it has to work that out from the fight itself: up to 21 October it explained
	 * under half of the returns. The actor is never told; the critic may be, it does not play
	 * (asymmetric actor-critic, Pinto et al. 2018).
	 */
	private final Dense taskIn;
	private final ResBlock[] blocks;
	/** How many tensors at the end were added by growing the network; they start out computing nothing. */
	public final int grown;

	public CriticNet(long seed, boolean privileged) {
		this(seed, privileged, false, 0, 0);
	}

	public CriticNet(long seed, boolean privileged, boolean tasks, int blockCount, int blockWidth) {
		Random rng = new Random(seed);
		this.privileged = privileged;
		float relu = (float) Math.sqrt(2.0);
		l1 = new Dense(params, "critic.l1", ObsSpec.CRITIC_IN, HIDDEN, rng, relu);
		l2 = new Dense(params, "critic.l2", HIDDEN, HIDDEN, rng, relu);
		out = new Dense(params, "critic.out", HIDDEN, 1, rng, 1f);
		// Added on 21 October 2026, after the rest and at zero, so a saved critic loads as it was.
		int before = params.params().size();
		taskIn = tasks ? new Dense(params, "critic.task", TASKS, HIDDEN, rng, 0f) : null;
		blocks = new ResBlock[Math.max(0, blockCount)];
		for (int b = 0; b < blocks.length; b++) {
			blocks[b] = new ResBlock(params, "critic.block" + b, HIDDEN, blockWidth, rng);
		}
		grown = params.params().size() - before;
	}

	public final class Workspace {
		final float[] in = new float[ObsSpec.CRITIC_IN];
		final float[] h1 = new float[HIDDEN];
		final float[] h2 = new float[HIDDEN];
		final float[] top = blocks.length == 0 ? h2 : new float[HIDDEN];
		final ResBlock.Cache[] caches = new ResBlock.Cache[blocks.length];
		final float[] v = new float[1];
		final float[] dV = new float[1];
		final float[] dH1 = new float[HIDDEN];
		final float[] dH2 = new float[HIDDEN];
		int task = -1;
		public final float[][] grads = params.newGrads();

		Workspace() {
			for (int b = 0; b < blocks.length; b++) {
				caches[b] = blocks[b].newCache();
			}
		}
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	public float forward(Workspace ws, float[] vec, float[] priv) {
		return forward(ws, vec, priv, -1);
	}

	/** @param task the kind of fight, 0 to {@link #TASKS} - 1, or -1 when it is not known */
	public float forward(Workspace ws, float[] vec, float[] priv, int task) {
		System.arraycopy(vec, 0, ws.in, 0, ObsSpec.VEC);
		if (privileged) {
			System.arraycopy(priv, 0, ws.in, ObsSpec.VEC, ObsSpec.PRIV);
		}
		l1.forward(ws.in, 0, ws.h1, 0);
		ws.task = taskIn != null && task >= 0 && task < TASKS ? task : -1;
		if (ws.task >= 0) {
			float[] w = taskIn.weight.w;
			float[] b = taskIn.bias.w;
			int row = ws.task * HIDDEN;
			for (int o = 0; o < HIDDEN; o++) {
				ws.h1[o] += w[row + o] + b[o];
			}
		}
		relu(ws.h1);
		l2.forward(ws.h1, 0, ws.h2, 0);
		relu(ws.h2);
		if (blocks.length > 0) {
			System.arraycopy(ws.h2, 0, ws.top, 0, HIDDEN);
			for (int b = 0; b < blocks.length; b++) {
				blocks[b].forward(ws.top, 0, ws.caches[b]);
			}
		}
		out.forward(ws.top, 0, ws.v, 0);
		return ws.v[0];
	}

	/** Back-propagates dLoss/dValue; must follow {@link #forward} on the same workspace. */
	public void backward(Workspace ws, float dValue) {
		ws.dV[0] = dValue;
		out.backward(ws.top, 0, ws.dV, 0, ws.dH2, 0, ws.grads);
		for (int b = blocks.length - 1; b >= 0; b--) {
			blocks[b].backward(ws.dH2, 0, ws.caches[b], ws.grads);
		}
		gate(ws.dH2, ws.h2);
		l2.backward(ws.h1, 0, ws.dH2, 0, ws.dH1, 0, ws.grads);
		gate(ws.dH1, ws.h1);
		if (ws.task >= 0) {
			float[] gw = ws.grads[taskIn.weight.id];
			float[] gb = ws.grads[taskIn.bias.id];
			int row = ws.task * HIDDEN;
			for (int o = 0; o < HIDDEN; o++) {
				gw[row + o] += ws.dH1[o];
				gb[o] += ws.dH1[o];
			}
		}
		l1.backward(ws.in, 0, ws.dH1, 0, null, 0, ws.grads);
	}

	private static void relu(float[] a) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] < 0f) {
				a[i] = 0f;
			}
		}
	}

	private static void gate(float[] grad, float[] activation) {
		for (int i = 0; i < grad.length; i++) {
			if (activation[i] <= 0f) {
				grad[i] = 0f;
			}
		}
	}
}
