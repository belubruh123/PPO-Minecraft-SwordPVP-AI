package dev.swordai.core.nn;

import java.util.Random;

/**
 * Valid (unpadded) 2-D convolution over channel-last maps: index = (y * width + x) * channels + c.
 * Weights are [ky][kx][inC][outC], so one kernel row lines up with a contiguous run of inputs and
 * the inner loop is an axpy over the output channels. Zero inputs are skipped, which makes the
 * first layer almost free on the mostly-empty enemy masks.
 */
public final class Conv2d {
	public final int inH;
	public final int inW;
	public final int inC;
	public final int k;
	public final int stride;
	public final int outC;
	public final int outH;
	public final int outW;
	public final Param weight;
	public final Param bias;

	private static final float BYTE_SCALE = 1f / 255f;

	public Conv2d(ParamSet ps, String name, int inH, int inW, int inC, int k, int stride, int outC, Random rng) {
		this.inH = inH;
		this.inW = inW;
		this.inC = inC;
		this.k = k;
		this.stride = stride;
		this.outC = outC;
		this.outH = (inH - k) / stride + 1;
		this.outW = (inW - k) / stride + 1;
		this.weight = ps.add(name + ".w", k * k * inC * outC);
		this.bias = ps.add(name + ".b", outC);
		float std = (float) Math.sqrt(2.0 / (k * k * inC));
		for (int i = 0; i < weight.w.length; i++) {
			weight.w[i] = (float) rng.nextGaussian() * std;
		}
	}

	public int inSize() {
		return inH * inW * inC;
	}

	public int outSize() {
		return outH * outW * outC;
	}

	public void forward(float[] in, float[] out) {
		final float[] w = weight.w;
		final float[] b = bias.w;
		final int f = outC;
		final int run = k * inC;
		for (int oy = 0; oy < outH; oy++) {
			for (int ox = 0; ox < outW; ox++) {
				int o = (oy * outW + ox) * f;
				System.arraycopy(b, 0, out, o, f);
				for (int ky = 0; ky < k; ky++) {
					int inRow = ((oy * stride + ky) * inW + ox * stride) * inC;
					int wRow = ky * run * f;
					for (int j = 0; j < run; j++) {
						float x = in[inRow + j];
						if (x != 0f) {
							int wo = wRow + j * f;
							for (int c = 0; c < f; c++) {
								out[o + c] += w[wo + c] * x;
							}
						}
					}
				}
			}
		}
	}

	/** Same as {@link #forward(float[], float[])} for an unsigned-byte input scaled to 0..1. */
	public void forwardBytes(byte[] in, float[] out) {
		final float[] w = weight.w;
		final float[] b = bias.w;
		final int f = outC;
		final int run = k * inC;
		for (int oy = 0; oy < outH; oy++) {
			for (int ox = 0; ox < outW; ox++) {
				int o = (oy * outW + ox) * f;
				System.arraycopy(b, 0, out, o, f);
				for (int ky = 0; ky < k; ky++) {
					int inRow = ((oy * stride + ky) * inW + ox * stride) * inC;
					int wRow = ky * run * f;
					for (int j = 0; j < run; j++) {
						byte raw = in[inRow + j];
						if (raw != 0) {
							float x = (raw & 0xFF) * BYTE_SCALE;
							int wo = wRow + j * f;
							for (int c = 0; c < f; c++) {
								out[o + c] += w[wo + c] * x;
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Accumulates parameter gradients. When {@code dIn} is non-null it must be zeroed by the caller;
	 * the gradient w.r.t. the input is added into it.
	 */
	public void backward(float[] in, float[] dOut, float[] dIn, float[][] grads) {
		final float[] w = weight.w;
		final float[] gw = grads[weight.id];
		final float[] gb = grads[bias.id];
		final int f = outC;
		final int run = k * inC;
		for (int oy = 0; oy < outH; oy++) {
			for (int ox = 0; ox < outW; ox++) {
				int o = (oy * outW + ox) * f;
				for (int c = 0; c < f; c++) {
					gb[c] += dOut[o + c];
				}
				for (int ky = 0; ky < k; ky++) {
					int inRow = ((oy * stride + ky) * inW + ox * stride) * inC;
					int wRow = ky * run * f;
					for (int j = 0; j < run; j++) {
						float x = in[inRow + j];
						int wo = wRow + j * f;
						if (x != 0f) {
							for (int c = 0; c < f; c++) {
								gw[wo + c] += x * dOut[o + c];
							}
						}
						if (dIn != null) {
							float s = 0f;
							for (int c = 0; c < f; c++) {
								s += w[wo + c] * dOut[o + c];
							}
							dIn[inRow + j] += s;
						}
					}
				}
			}
		}
	}

	/** Parameter gradients for a byte input; there is nothing upstream to propagate into. */
	public void backwardBytes(byte[] in, float[] dOut, float[][] grads) {
		final float[] gw = grads[weight.id];
		final float[] gb = grads[bias.id];
		final int f = outC;
		final int run = k * inC;
		for (int oy = 0; oy < outH; oy++) {
			for (int ox = 0; ox < outW; ox++) {
				int o = (oy * outW + ox) * f;
				for (int c = 0; c < f; c++) {
					gb[c] += dOut[o + c];
				}
				for (int ky = 0; ky < k; ky++) {
					int inRow = ((oy * stride + ky) * inW + ox * stride) * inC;
					int wRow = ky * run * f;
					for (int j = 0; j < run; j++) {
						byte raw = in[inRow + j];
						if (raw != 0) {
							float x = (raw & 0xFF) * BYTE_SCALE;
							int wo = wRow + j * f;
							for (int c = 0; c < f; c++) {
								gw[wo + c] += x * dOut[o + c];
							}
						}
					}
				}
			}
		}
	}
}
