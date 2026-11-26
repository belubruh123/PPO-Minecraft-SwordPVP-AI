package dev.swordai.core.nn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All trainable tensors of one network plus Adam step counter. */
public final class ParamSet {
	private final List<Param> params = new ArrayList<>();
	public long adamSteps;

	public Param add(String name, int size) {
		Param p = new Param(name, size, params.size());
		params.add(p);
		return p;
	}

	public List<Param> params() {
		return params;
	}

	/** Frees Adam moment buffers on inference-only networks (PFSP snapshots, priorNet, selfNet), cutting RAM by 3x. */
	public void dropOptimizerState() {
		for (Param p : params) { p.m = null; p.v = null; }
	}

	public int totalSize() {
		int n = 0;
		for (Param p : params) n += p.w.length;
		return n;
	}

	public float[][] newGrads() {
		float[][] g = new float[params.size()][];
		for (Param p : params) g[p.id] = new float[p.w.length];
		return g;
	}

	public static void zeroGrads(float[][] g) {
		for (float[] a : g) Arrays.fill(a, 0f);
	}
}
