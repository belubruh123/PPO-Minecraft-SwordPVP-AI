package dev.swordai.core.school;

import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;
import dev.swordai.core.rl.ObsSpec;

import java.util.Random;

/**
 * The value network of the technique school: wide and apart from the actor ("What matters in on-policy
 * RL", Andrychowicz et al. 2021). It reads everything the actor reads but the pictures, the ground truth
 * of the stages before, the memory, and what only training knows of the opponent: its technique at this
 * moment, its style and which bot it is (asymmetric actor-critic: it never plays).
 */
public final class SchoolCritic {
	static final int HIDDEN = 256;

	public final ParamSet params = new ParamSet();
	private final Dense l1;
	private final Dense l2;
	private final ResBlock block;
	private final Dense out;

	public SchoolCritic(long seed) {
		Random rng = new Random(seed);
		float relu = (float) Math.sqrt(2.0);
		l1 = new Dense(params, "critic.l1", SchoolObs.CRITIC_IN, HIDDEN, rng, relu);
		l2 = new Dense(params, "critic.l2", HIDDEN, HIDDEN, rng, relu);
		block = new ResBlock(params, "critic.block", HIDDEN, HIDDEN, rng);
		out = new Dense(params, "critic.out", HIDDEN, 1, rng, 1f);
	}

	public final class Workspace {
		final float[] in = new float[SchoolObs.CRITIC_IN];
		final float[] h1 = new float[HIDDEN];
		final float[] h2 = new float[HIDDEN];
		final float[] top = new float[HIDDEN];
		final ResBlock.Cache cache = block.newCache();
		final float[] v = new float[1];
		final float[] dV = new float[1];
		final float[] dH1 = new float[HIDDEN];
		final float[] dH2 = new float[HIDDEN];
		public final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	public float forward(Workspace ws, float[] vec, float[] priv, float[] mem, float[] oppTruth) {
		int o = 0;
		System.arraycopy(vec, 0, ws.in, o, ObsSpec.VEC);
		o += ObsSpec.VEC;
		System.arraycopy(priv, 0, ws.in, o, ObsSpec.PRIV);
		o += ObsSpec.PRIV;
		System.arraycopy(mem, 0, ws.in, o, SchoolObs.MEM);
		o += SchoolObs.MEM;
		System.arraycopy(oppTruth, 0, ws.in, o, SchoolObs.OPP_TRUTH);
		l1.forward(ws.in, 0, ws.h1, 0);
		relu(ws.h1);
		l2.forward(ws.h1, 0, ws.h2, 0);
		relu(ws.h2);
		System.arraycopy(ws.h2, 0, ws.top, 0, HIDDEN);
		block.forward(ws.top, 0, ws.cache);
		out.forward(ws.top, 0, ws.v, 0);
		return ws.v[0];
	}

	public void backward(Workspace ws, float dValue) {
		ws.dV[0] = dValue;
		out.backward(ws.top, 0, ws.dV, 0, ws.dH2, 0, ws.grads);
		block.backward(ws.dH2, 0, ws.cache, ws.grads);
		gate(ws.dH2, ws.h2);
		l2.backward(ws.h1, 0, ws.dH2, 0, ws.dH1, 0, ws.grads);
		gate(ws.dH1, ws.h1);
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
