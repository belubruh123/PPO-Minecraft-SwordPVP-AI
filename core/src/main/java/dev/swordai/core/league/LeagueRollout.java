package dev.swordai.core.league;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsSpec;

import java.util.Arrays;

/**
 * Contiguous on-policy rollout buffer storing exact tick order for truncated recurrent BPTT
 * (see {@link LeaguePpo}), inventory slots, local terrain heightmaps, and optional demo/style targets.
 */
public final class LeagueRollout {
	public final int capacity;
	public final byte[][] peri;
	public final byte[][] fovea;
	public final float[][] vec;
	public final float[][] slots;
	public final float[][] terrain;
	public final float[][] priv;
	public final float[][] hist;
	public final float[][] seen;
	/** GRU hidden state entering each tick (used to seed each BPTT chunk). */
	public final float[][] hPrev;
	public final byte[][] action;
	public final int[] headMask;
	public final float[] oldLogp;
	public final float[] value;
	public final float[] reward;
	public final boolean[] done;
	public final byte[][] demoAction;
	public final float[] demoWeight;
	public float[][] styleProbs;
	public final float[] styleWeight;

	public final float[] advantage;
	public final float[] returns;

	private int size;

	public LeagueRollout(int capacity) {
		this.capacity = capacity;
		this.peri = new byte[capacity][ObsSpec.PERI_SIZE];
		this.fovea = new byte[capacity][ObsSpec.FOVEA_SIZE];
		this.vec = new float[capacity][ObsSpec.VEC];
		this.slots = new float[capacity][ObsSpec.SLOTS_SIZE];
		this.terrain = new float[capacity][ObsSpec.TERRAIN_SIZE];
		this.priv = new float[capacity][ObsSpec.PRIV];
		this.hist = new float[capacity][ObsMemory.HIST];
		this.seen = new float[capacity][ObsMemory.SEEN];
		this.hPrev = new float[capacity][ObsMemory.GRU_HIDDEN];
		this.action = new byte[capacity][ActionSpace.HEADS];
		this.headMask = new int[capacity];
		this.oldLogp = new float[capacity];
		this.value = new float[capacity];
		this.reward = new float[capacity];
		this.done = new boolean[capacity];
		this.demoAction = new byte[capacity][ActionSpace.HEADS];
		this.demoWeight = new float[capacity];
		this.styleWeight = new float[capacity];
		this.advantage = new float[capacity];
		this.returns = new float[capacity];
	}

	public int size() { return size; }
	public boolean isFull() { return size >= capacity; }
	public void clear() { size = 0; }

	public void add(Obs obs, float[] histIn, float[] seenIn, float[] hIn, int headMask, int[] action, float logp, float value,
			float reward, boolean done, int[] demoAction, float demoWeight) {
		if (size >= capacity) return;
		int i = size++;
		System.arraycopy(obs.peri, 0, peri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(obs.fovea, 0, fovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(obs.vec, 0, vec[i], 0, ObsSpec.VEC);
		System.arraycopy(obs.slots, 0, slots[i], 0, ObsSpec.SLOTS_SIZE);
		System.arraycopy(obs.terrain, 0, terrain[i], 0, ObsSpec.TERRAIN_SIZE);
		System.arraycopy(obs.priv, 0, priv[i], 0, ObsSpec.PRIV);
		System.arraycopy(histIn, 0, hist[i], 0, ObsMemory.HIST);
		System.arraycopy(seenIn, 0, seen[i], 0, ObsMemory.SEEN);
		System.arraycopy(hIn, 0, hPrev[i], 0, ObsMemory.GRU_HIDDEN);
		for (int h = 0; h < ActionSpace.HEADS; h++) this.action[i][h] = (byte) action[h];
		this.headMask[i] = headMask;
		this.oldLogp[i] = logp;
		this.value[i] = value;
		this.reward[i] = reward;
		this.done[i] = done;
		if (demoAction != null && demoWeight > 0f) {
			for (int h = 0; h < ActionSpace.HEADS; h++) this.demoAction[i][h] = (byte) demoAction[h];
			this.demoWeight[i] = demoWeight;
		} else {
			Arrays.fill(this.demoAction[i], (byte) 0);
			this.demoWeight[i] = 0f;
		}
		this.styleWeight[i] = 0f;
	}

	public void setStyle(float[] probs, float weight) {
		if (size <= 0 || probs == null || weight <= 0f) return;
		if (styleProbs == null) styleProbs = new float[capacity][ActionSpace.TOTAL_LOGITS];
		System.arraycopy(probs, 0, styleProbs[size - 1], 0, ActionSpace.TOTAL_LOGITS);
		styleWeight[size - 1] = weight;
	}

	public void computeGae(float lastValue, float gamma, float lambda) {
		float gae = 0f;
		for (int t = size - 1; t >= 0; t--) {
			float nextVal = done[t] ? 0f : (t + 1 < size ? value[t + 1] : lastValue);
			float nonTerm = done[t] ? 0f : 1f;
			float delta = reward[t] + gamma * nextVal - value[t];
			gae = delta + gamma * lambda * nonTerm * gae;
			advantage[t] = gae;
			returns[t] = gae + value[t];
		}
	}
}
