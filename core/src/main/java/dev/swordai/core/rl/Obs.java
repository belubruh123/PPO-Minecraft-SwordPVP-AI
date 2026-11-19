package dev.swordai.core.rl;

/** One observation: what the actor sees, plus training-only critic ground truth. */
public final class Obs {
	public final byte[] peri = new byte[ObsSpec.PERI_SIZE];
	public final byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
	public final float[] vec = new float[ObsSpec.VEC];
	public final float[] priv = new float[ObsSpec.PRIV];

	public void copyFrom(Obs o) {
		System.arraycopy(o.peri, 0, peri, 0, peri.length);
		System.arraycopy(o.fovea, 0, fovea, 0, fovea.length);
		System.arraycopy(o.vec, 0, vec, 0, vec.length);
		System.arraycopy(o.priv, 0, priv, 0, priv.length);
	}
}
