package dev.swordai.core.rl;

/**
 * Self-imitation learning (Oh et al., 2018): the steps that went far better than the critic expected,
 * kept to be learned from again after the update that first saw them. On-policy PPO learns from a
 * step once and throws it away, so a fight it wins once in ten against an opponent it cannot yet beat
 * moves it about as much as the nine it loses, and what won it is gone by the next update. Kept here,
 * such steps are imitated (their action made likelier) for as long as the critic still expects less
 * than they brought, and no longer: once it has learned them, they no longer pull. See
 * {@code Ppo.selfImitate} and {@code Config.silWeight}.
 *
 * <p>A ring of the most recent such steps; each holds what the actor and the critic need again.
 */
public final class SilBuffer {
	public final int capacity;
	public int size;
	private int next;

	final byte[][] peri;
	final byte[][] fovea;
	final float[][] vec;
	final float[][] priv;
	final byte[][] action;
	final int[] headMask;
	final float[] nudge;
	final float[] nudgeBack;
	final float[] attackBias;
	final int[] tactic;
	final float[][] opp;
	/** The kind of fight, for the critic ({@code Config.criticTask}); until 23 October it was not kept, and the critic judged these steps as of no kind. */
	final int[] task;
	/** The opponent reader's window of the step, for the memory ({@code Config.rhythmNet}); null where there was none. Made as needed. */
	final float[][] window;
	/** The step's return as it was computed for its update (lambda-return, in the scaled reward). */
	final float[] ret;

	public SilBuffer(int capacity) {
		this.capacity = Math.max(1, capacity);
		peri = new byte[this.capacity][ObsSpec.PERI_SIZE];
		fovea = new byte[this.capacity][ObsSpec.FOVEA_SIZE];
		vec = new float[this.capacity][ObsSpec.VEC];
		priv = new float[this.capacity][ObsSpec.PRIV];
		action = new byte[this.capacity][ActionSpace.HEADS];
		headMask = new int[this.capacity];
		nudge = new float[this.capacity];
		nudgeBack = new float[this.capacity];
		attackBias = new float[this.capacity];
		tactic = new int[this.capacity];
		opp = new float[this.capacity][OpponentReader.Z];
		task = new int[this.capacity];
		window = new float[this.capacity][];
		ret = new float[this.capacity];
	}

	/**
	 * Keeps the steps of a rollout, its advantages computed, whose advantage stands {@code z} standard
	 * deviations or more above the rollout's mean (and above zero). Returns how many were kept.
	 */
	public int addFrom(Rollout r, float z) {
		int n = r.size;
		if (n == 0) {
			return 0;
		}
		double mean = 0;
		for (int i = 0; i < n; i++) {
			mean += r.advantage[i];
		}
		mean /= n;
		double var = 0;
		for (int i = 0; i < n; i++) {
			double d = r.advantage[i] - mean;
			var += d * d;
		}
		double above = mean + z * Math.sqrt(var / n);
		int kept = 0;
		for (int i = 0; i < n; i++) {
			if (r.advantage[i] > 0f && r.advantage[i] > above) {
				store(r, i);
				kept++;
			}
		}
		return kept;
	}

	private void store(Rollout r, int i) {
		int j = next;
		next = (next + 1) % capacity;
		if (size < capacity) {
			size++;
		}
		System.arraycopy(r.peri[i], 0, peri[j], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(r.fovea[i], 0, fovea[j], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(r.vec[i], 0, vec[j], 0, ObsSpec.VEC);
		System.arraycopy(r.priv[i], 0, priv[j], 0, ObsSpec.PRIV);
		System.arraycopy(r.action[i], 0, action[j], 0, ActionSpace.HEADS);
		headMask[j] = r.headMask[i];
		nudge[j] = r.nudge[i];
		nudgeBack[j] = r.nudgeBack[i];
		attackBias[j] = r.attackBias[i];
		tactic[j] = r.tactic[i];
		System.arraycopy(r.opp[i], 0, opp[j], 0, OpponentReader.Z);
		task[j] = r.task[i];
		keepWindow(j, r.oppWindow[i]);
		ret[j] = r.ret[i];
	}

	/** Keeps step {@code i} of a person's recorded fight with its return; see {@link DemoFile}. */
	public void put(DemoFile.Fight f, int i, float stepReturn) {
		put(f, i, stepReturn, null);
	}

	/** @param seen the opponent reader's window at that step, made again from the fight's ticks; null for none */
	public void put(DemoFile.Fight f, int i, float stepReturn, float[] seen) {
		int j = next;
		next = (next + 1) % capacity;
		if (size < capacity) {
			size++;
		}
		System.arraycopy(f.peri[i], 0, peri[j], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(f.fovea[i], 0, fovea[j], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(f.vec[i], 0, vec[j], 0, ObsSpec.VEC);
		System.arraycopy(f.priv[i], 0, priv[j], 0, ObsSpec.PRIV);
		System.arraycopy(f.action[i], 0, action[j], 0, ActionSpace.HEADS);
		headMask[j] = f.headMask[i];
		nudge[j] = 0f;
		nudgeBack[j] = 0f;
		attackBias[j] = 0f;
		tactic[j] = f.tactic[i];
		System.arraycopy(f.opp[i], 0, opp[j], 0, OpponentReader.Z);
		task[j] = f.task[i];
		keepWindow(j, seen);
		ret[j] = stepReturn;
	}

	private void keepWindow(int j, float[] seen) {
		if (seen == null) {
			window[j] = null;
			return;
		}
		if (window[j] == null) {
			window[j] = new float[OpponentReader.INPUTS];
		}
		System.arraycopy(seen, 0, window[j], 0, OpponentReader.INPUTS);
	}

	public void clear() {
		size = 0;
		next = 0;
	}
}
