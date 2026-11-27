package dev.swordai.core.league;

import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;
import dev.swordai.core.nn.SlotEncoder;
import dev.swordai.core.rl.ObsSpec;

import java.util.Random;

/**
 * Asymmetric value network for PPO: reads the observation vector, kit summary, local terrain,
 * training-only privileged ground truth, and detached 256-unit GRU hidden state. Never runs at play time.
 */
public final class SwordCritic {
	public static final int IN = ObsSpec.VEC + ObsSpec.PRIV + ObsMemory.GRU_HIDDEN + SlotEncoder.KIT_DIM + ObsSpec.TERRAIN_SIZE;
	static final int HIDDEN = 384;

	public final ParamSet params = new ParamSet();
	private final Dense l1;
	private final Dense l2;
	private final ResBlock block;
	private final Dense out;

	public SwordCritic(long seed) {
		Random rng = new Random(seed);
		float relu = (float) Math.sqrt(2.0);
		l1 = new Dense(params, "critic.l1", IN, HIDDEN, rng, relu);
		zeroGruInputWeights();
		l2 = new Dense(params, "critic.l2", HIDDEN, HIDDEN, rng, relu);
		block = new ResBlock(params, "critic.block", HIDDEN, HIDDEN, rng);
		out = new Dense(params, "critic.out", HIDDEN, 1, rng, 1f);
	}

	/** Zeroes the GRU input slice of {@code critic.l1} (and its Adam moments) for exact Net2Net continuity. */
	public void zeroGruInputWeights() {
		int start = (ObsSpec.VEC + ObsSpec.PRIV) * HIDDEN;
		int end = (ObsSpec.VEC + ObsSpec.PRIV + ObsMemory.GRU_HIDDEN) * HIDDEN;
		java.util.Arrays.fill(l1.weight.w, start, end, 0f);
		java.util.Arrays.fill(l1.weight.m, start, end, 0f);
		java.util.Arrays.fill(l1.weight.v, start, end, 0f);
	}

	public final class Workspace {
		final float[] in = new float[IN];
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

	public float forward(Workspace ws, float[] vec, float[] priv, float[] gruHidden, float[] kitCtx, float[] terrain) {
		int o = 0;
		System.arraycopy(vec, 0, ws.in, o, ObsSpec.VEC); o += ObsSpec.VEC;
		System.arraycopy(priv, 0, ws.in, o, ObsSpec.PRIV); o += ObsSpec.PRIV;
		System.arraycopy(gruHidden, 0, ws.in, o, ObsMemory.GRU_HIDDEN); o += ObsMemory.GRU_HIDDEN;
		if (kitCtx != null) System.arraycopy(kitCtx, 0, ws.in, o, SlotEncoder.KIT_DIM);
		else java.util.Arrays.fill(ws.in, o, o + SlotEncoder.KIT_DIM, 0f);
		o += SlotEncoder.KIT_DIM;
		if (terrain != null) System.arraycopy(terrain, 0, ws.in, o, ObsSpec.TERRAIN_SIZE);
		else java.util.Arrays.fill(ws.in, o, o + ObsSpec.TERRAIN_SIZE, 0f);
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
		for (int i = 0; i < a.length; i++) if (a[i] < 0f) a[i] = 0f;
	}

	private static void gate(float[] grad, float[] act) {
		for (int i = 0; i < grad.length; i++) if (act[i] <= 0f) grad[i] = 0f;
	}
}
