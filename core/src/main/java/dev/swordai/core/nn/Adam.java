package dev.swordai.core.nn;

/** Adam with global-norm gradient clipping. */
public final class Adam {
	private static final float BETA1 = 0.9f;
	private static final float BETA2 = 0.999f;
	private static final float EPS = 1e-5f;

	private Adam() {
	}

	public static float globalNorm(float[][] grads) {
		double sum = 0;
		for (float[] g : grads) {
			for (float x : g) {
				sum += (double) x * x;
			}
		}
		return (float) Math.sqrt(sum);
	}

	/**
	 * Applies one step. {@code scale} multiplies every gradient first (pass 1/batchSize for summed
	 * gradients). Returns the gradient norm before clipping, for diagnostics.
	 */
	public static float step(ParamSet ps, float[][] grads, float lr, float scale, float maxNorm) {
		float norm = globalNorm(grads) * scale;
		float clip = (maxNorm > 0f && norm > maxNorm) ? maxNorm / norm : 1f;
		float k = scale * clip;

		ps.adamSteps++;
		double t = ps.adamSteps;
		float corr1 = (float) (1.0 - Math.pow(BETA1, t));
		float corr2 = (float) (1.0 - Math.pow(BETA2, t));
		float stepSize = lr * (float) Math.sqrt(corr2) / corr1;

		for (Param p : ps.params()) {
			final float[] w = p.w;
			final float[] m = p.m;
			final float[] v = p.v;
			final float[] g = grads[p.id];
			for (int i = 0; i < w.length; i++) {
				float gi = g[i] * k;
				m[i] = BETA1 * m[i] + (1f - BETA1) * gi;
				v[i] = BETA2 * v[i] + (1f - BETA2) * gi * gi;
				w[i] -= stepSize * m[i] / ((float) Math.sqrt(v[i]) + EPS);
			}
		}
		return norm;
	}
}
