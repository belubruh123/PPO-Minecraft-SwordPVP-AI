package dev.swordai.core.nn;

/** One trainable tensor, stored flat, together with its Adam moments. */
public final class Param {
	public final String name;
	public final float[] w;
	public final float[] m;
	public final float[] v;
	/** Index into the gradient buffers handed out by {@link ParamSet#newGrads()}. */
	public final int id;

	Param(String name, int size, int id) {
		this.name = name;
		this.w = new float[size];
		this.m = new float[size];
		this.v = new float[size];
		this.id = id;
	}
}
