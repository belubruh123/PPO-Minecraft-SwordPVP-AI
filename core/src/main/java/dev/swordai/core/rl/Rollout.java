package dev.swordai.core.rl;

/** Fixed-size on-policy buffer with generalised advantage estimation. */
public final class Rollout {
	public final int capacity;
	public int size;

	public final byte[][] peri;
	public final byte[][] fovea;
	public final float[][] vec;
	public final float[][] priv;
	/** The kind of fight each step was in, for the critic; see {@link Obs#task}. */
	public final int[] task;
	/** The tactic in force at each step ({@link Obs#tactic}); -1 for none. */
	public final int[] tactic;
	/** Whether the tactics model chose the tactic on this step, and the log-probability of that choice; see {@link TacticNet}. */
	public final boolean[] tacticStart;
	public final float[] tacticLogp;
	/** The opponent reader's window and reading at each step; see {@link OpponentReader}. */
	public final float[][] oppWindow;
	public final float[][] opp;
	public final byte[][] action;
	public final int[] headMask;
	public final float[] logp;
	/** How strong the {@link JumpNudge} was when the step was chosen. */
	public final float[] nudge;
	/** The same for the {@link BackNudge}. */
	public final float[] nudgeBack;
	/** A push on the swing logit the action was drawn with (the answer rule as a nudge, {@code Config.answerNudge}). */
	public final float[] attackBias;
	public final float[] value;
	public final float[] reward;
	/** True when the step ended its episode (any bootstrap value is already folded into reward). */
	public final boolean[] done;
	public final float[] advantage;
	public final float[] ret;

	public Rollout(int capacity) {
		this.capacity = capacity;
		peri = new byte[capacity][ObsSpec.PERI_SIZE];
		fovea = new byte[capacity][ObsSpec.FOVEA_SIZE];
		vec = new float[capacity][ObsSpec.VEC];
		priv = new float[capacity][ObsSpec.PRIV];
		task = new int[capacity];
		tactic = new int[capacity];
		tacticStart = new boolean[capacity];
		tacticLogp = new float[capacity];
		oppWindow = new float[capacity][OpponentReader.INPUTS];
		opp = new float[capacity][OpponentReader.Z];
		action = new byte[capacity][ActionSpace.HEADS];
		headMask = new int[capacity];
		logp = new float[capacity];
		nudge = new float[capacity];
		nudgeBack = new float[capacity];
		attackBias = new float[capacity];
		value = new float[capacity];
		reward = new float[capacity];
		done = new boolean[capacity];
		advantage = new float[capacity];
		ret = new float[capacity];
	}

	public boolean full() {
		return size >= capacity;
	}

	public void clear() {
		size = 0;
	}

	public void add(Obs obs, int[] act, int mask, float logProb, float val, float rew, boolean isDone) {
		add(obs, act, mask, logProb, 0f, val, rew, isDone);
	}

	/** @param jumpNudge the strength of the {@link JumpNudge} the action was drawn with */
	public void add(Obs obs, int[] act, int mask, float logProb, float jumpNudge, float val, float rew, boolean isDone) {
		add(obs, act, mask, logProb, jumpNudge, 0f, val, rew, isDone);
	}

	/** @param backNudge the strength of the {@link BackNudge} the action was drawn with */
	public void add(Obs obs, int[] act, int mask, float logProb, float jumpNudge, float backNudge, float val, float rew, boolean isDone) {
		add(obs, act, mask, logProb, jumpNudge, backNudge, 0f, val, rew, isDone);
	}

	/** @param swingBias what was added to the swing logit when the action was drawn ({@link Policy#pushSwing}) */
	public void add(Obs obs, int[] act, int mask, float logProb, float jumpNudge, float backNudge, float swingBias, float val, float rew, boolean isDone) {
		int i = size++;
		attackBias[i] = swingBias;
		System.arraycopy(obs.peri, 0, peri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(obs.fovea, 0, fovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(obs.vec, 0, vec[i], 0, ObsSpec.VEC);
		System.arraycopy(obs.priv, 0, priv[i], 0, ObsSpec.PRIV);
		task[i] = obs.task;
		tactic[i] = obs.tactic;
		tacticStart[i] = false;
		tacticLogp[i] = 0f;
		System.arraycopy(obs.oppWindow, 0, oppWindow[i], 0, OpponentReader.INPUTS);
		System.arraycopy(obs.opp, 0, opp[i], 0, OpponentReader.Z);
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			action[i][h] = (byte) act[h];
		}
		headMask[i] = mask;
		logp[i] = logProb;
		nudge[i] = jumpNudge;
		nudgeBack[i] = backNudge;
		value[i] = val;
		reward[i] = rew;
		done[i] = isDone;
	}

	/** The last stored step is where the tactics model chose its tactic, with this log-probability. */
	public void markTacticChoice(float logp) {
		if (size > 0) {
			tacticStart[size - 1] = true;
			tacticLogp[size - 1] = logp;
		}
	}

	/**
	 * Computes advantages and returns.
	 *
	 * @param lastValue value of the observation after the final stored step; ignored if that step
	 *                  ended its episode
	 */
	public void computeAdvantages(float lastValue, float gamma, float lambda) {
		float gae = 0f;
		for (int t = size - 1; t >= 0; t--) {
			float nextValue;
			if (done[t]) {
				nextValue = 0f;
				gae = 0f;
			} else {
				nextValue = (t == size - 1) ? lastValue : value[t + 1];
			}
			float delta = reward[t] + gamma * nextValue - value[t];
			gae = delta + gamma * lambda * gae;
			advantage[t] = gae;
			ret[t] = gae + value[t];
		}
	}
}
