package dev.swordai.core.nn;

import java.util.Random;

/**
 * Fully connected layer. Weights are laid out [in][out] so both passes run as
 * "y += w_row * x_i" over a contiguous row: that shape is what HotSpot auto-vectorises,
 * and it lets zero inputs (ReLU outputs, empty mask cells) be skipped outright.
 */
public final class Dense {
	public final int in;
	public final int out;
	public final Param weight;
	public final Param bias;

	public Dense(ParamSet ps, String name, int in, int out, Random rng, float gain) {
		this.in = in;
		this.out = out;
		this.weight = ps.add(name + ".w", in * out);
		this.bias = ps.add(name + ".b", out);
		float std = gain / (float) Math.sqrt(in);
		for (int i = 0; i < weight.w.length; i++) {
			weight.w[i] = (float) rng.nextGaussian() * std;
		}
	}

	public void forward(float[] x, int xOff, float[] y, int yOff) {
		final float[] w = weight.w;
		final int n = out;
		System.arraycopy(bias.w, 0, y, yOff, n);
		for (int i = 0; i < in; i++) {
			float xi = x[xOff + i];
			if (xi != 0f) {
				int wo = i * n;
				for (int o = 0; o < n; o++) {
					y[yOff + o] += w[wo + o] * xi;
				}
			}
		}
	}

	/**
	 * Accumulates parameter gradients into {@code grads} and, when {@code dx} is non-null,
	 * overwrites dx[dxOff .. dxOff+in) with the gradient w.r.t. the input.
	 */
	public void backward(float[] x, int xOff, float[] dy, int dyOff, float[] dx, int dxOff, float[][] grads) {
		final float[] w = weight.w;
		final float[] gw = grads[weight.id];
		final float[] gb = grads[bias.id];
		final int n = out;
		for (int o = 0; o < n; o++) {
			gb[o] += dy[dyOff + o];
		}
		for (int i = 0; i < in; i++) {
			float xi = x[xOff + i];
			int wo = i * n;
			if (xi != 0f) {
				for (int o = 0; o < n; o++) {
					gw[wo + o] += xi * dy[dyOff + o];
				}
			}
			if (dx != null) {
				float s = 0f;
				for (int o = 0; o < n; o++) {
					s += w[wo + o] * dy[dyOff + o];
				}
				dx[dxOff + i] = s;
			}
		}
	}
}
