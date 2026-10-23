package dev.swordai.core.rl;

/** One observation: what the actor sees, plus the critic-only ground truth. */
public final class Obs {
	public final byte[] peri = new byte[ObsSpec.PERI_SIZE];
	public final byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
	public final float[] vec = new float[ObsSpec.VEC];
	public final float[] priv = new float[ObsSpec.PRIV];
	/** The kind of fight, for the critic only ({@link CriticNet#TASKS}); -1 when not known. */
	public int task = -1;
	/** The tactic in force ({@link Tactic}), an input of the acting network; -1 for none. */
	public int tactic = -1;
	/** The opponent reader's window of the fight ({@link OpponentReader.Window}) and what it read from it, for the acting network. */
	public final float[] oppWindow = new float[OpponentReader.INPUTS];
	public final float[] opp = new float[OpponentReader.Z];

	public void copyFrom(Obs o) {
		System.arraycopy(o.peri, 0, peri, 0, peri.length);
		System.arraycopy(o.fovea, 0, fovea, 0, fovea.length);
		System.arraycopy(o.vec, 0, vec, 0, vec.length);
		System.arraycopy(o.priv, 0, priv, 0, priv.length);
		task = o.task;
		tactic = o.tactic;
		System.arraycopy(o.oppWindow, 0, oppWindow, 0, oppWindow.length);
		System.arraycopy(o.opp, 0, opp, 0, opp.length);
	}
}
