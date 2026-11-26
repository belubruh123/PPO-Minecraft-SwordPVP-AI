package dev.swordai.core.nn;

/** One trainable tensor, stored flat, together with its Adam moments. */
public final class Param {
	public final String name;
	public final float[] w;
	public float[] m;
	public float[] v;
	/** Index into the gradient buffers handed out by {@link ParamSet#newGrads()}. */
	public final int id;
	/**
	 * The network's Adam step count when this tensor was added to a network loaded from a file that did not have it (0: it was
	 * there all along). Its Adam bias correction counts its own steps from then, so its first steps are Adam's usual size instead
	 * of the up to ten times larger ones a zero moment under an old step count gives. Not saved.
	 */
	public long bornAt;

	Param(String name, int size, int id) {
		this.name = name;
		this.w = new float[size];
		this.m = new float[size];
		this.v = new float[size];
		this.id = id;
	}
}
