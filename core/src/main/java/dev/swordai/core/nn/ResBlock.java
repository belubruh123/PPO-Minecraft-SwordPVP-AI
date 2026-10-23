package dev.swordai.core.nn;

import java.util.Arrays;
import java.util.Random;

/**
 * A residual feed-forward block, {@code x + W2 relu(W1 LN(x))}, the building block SimBa (Lee et al.,
 * ICLR 2025) scales reinforcement-learning networks with: the layer norm keeps what the block sees at
 * one scale however the network below it grows, and the skip path keeps a plain way through.
 *
 * <p>Its output layer starts at zero, so a block added to a trained network leaves everything that
 * network computes exactly as it was (Net2Net's function-preserving growth, Chen et al., ICLR 2016, in
 * its simplest form). The first update moves the output layer, and from then on the gradient reaches
 * the rest of the block through it.
 */
public final class ResBlock {
	private static final float LN_EPS = 1e-5f;

	public final int width;
	public final int hidden;
	public final Param gain;
	public final Param shift;
	public final Dense up;
	public final Dense down;

	public ResBlock(ParamSet ps, String name, int width, int hidden, Random rng) {
		this.width = width;
		this.hidden = hidden;
		gain = ps.add(name + ".ln.g", width);
		Arrays.fill(gain.w, 1f);
		shift = ps.add(name + ".ln.b", width);
		up = new Dense(ps, name + ".up", width, hidden, rng, (float) Math.sqrt(2.0));
		down = new Dense(ps, name + ".down", hidden, width, rng, 0f);
	}

	/** One thread's activations of this block, kept from {@link #forward} for {@link #backward}. */
	public final class Cache {
		final float[] xhat = new float[width];
		final float[] z = new float[width];
		final float[] h = new float[hidden];
		final float[] out = new float[width];
		final float[] dz = new float[width];
		final float[] dh = new float[hidden];
		float rstd;
	}

	public Cache newCache() {
		return new Cache();
	}

	/** {@code x[off .. off+width)} becomes the block's output, in place. */
	public void forward(float[] x, int off, Cache c) {
		float mean = 0f;
		for (int i = 0; i < width; i++) {
			mean += x[off + i];
		}
		mean /= width;
		float var = 0f;
		for (int i = 0; i < width; i++) {
			float d = x[off + i] - mean;
			var += d * d;
		}
		var /= width;
		c.rstd = (float) (1.0 / Math.sqrt(var + LN_EPS));
		for (int i = 0; i < width; i++) {
			c.xhat[i] = (x[off + i] - mean) * c.rstd;
			c.z[i] = gain.w[i] * c.xhat[i] + shift.w[i];
		}
		up.forward(c.z, 0, c.h, 0);
		for (int i = 0; i < hidden; i++) {
			if (c.h[i] < 0f) {
				c.h[i] = 0f;
			}
		}
		down.forward(c.h, 0, c.out, 0);
		for (int i = 0; i < width; i++) {
			x[off + i] += c.out[i];
		}
	}

	/**
	 * On entry {@code dx[off .. off+width)} is the gradient with respect to the block's output; on exit
	 * it is the gradient with respect to its input (the skip path plus the branch). Parameter gradients
	 * are added to {@code grads}. Must follow {@link #forward} with the same cache.
	 */
	public void backward(float[] dx, int off, Cache c, float[][] grads) {
		down.backward(c.h, 0, dx, off, c.dh, 0, grads);
		for (int i = 0; i < hidden; i++) {
			if (c.h[i] <= 0f) {
				c.dh[i] = 0f;
			}
		}
		up.backward(c.z, 0, c.dh, 0, c.dz, 0, grads);
		float[] gGain = grads[gain.id];
		float[] gShift = grads[shift.id];
		float mean = 0f;
		float meanX = 0f;
		for (int i = 0; i < width; i++) {
			gGain[i] += c.dz[i] * c.xhat[i];
			gShift[i] += c.dz[i];
			float dXhat = c.dz[i] * gain.w[i];
			c.dz[i] = dXhat;
			mean += dXhat;
			meanX += dXhat * c.xhat[i];
		}
		mean /= width;
		meanX /= width;
		for (int i = 0; i < width; i++) {
			dx[off + i] += c.rstd * (c.dz[i] - mean - c.xhat[i] * meanX);
		}
	}
}
