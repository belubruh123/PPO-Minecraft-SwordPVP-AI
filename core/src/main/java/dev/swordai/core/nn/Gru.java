package dev.swordai.core.nn;

import java.util.Arrays;
import java.util.Random;

/**
 * Pure-Java Gated Recurrent Unit (PyTorch gate convention):
 *   [xz, xr, xn] = inProj(x)
 *   [hz, hr, hn] = hidProj(h_prev)
 *   z = sigmoid(xz + hz)
 *   r = sigmoid(xr + hr)
 *   n = tanh(xn + r * hn)
 *   h = (1 - z) * h_prev + z * n
 *
 * Supports truncated BPTT over sequence chunks via an array of {@link StepCache}s.
 */
public final class Gru {
	public final int in;
	public final int hidden;
	public final Dense inProj;
	public final Dense hidProj;

	public Gru(ParamSet ps, String name, int in, int hidden, Random rng) {
		this.in = in;
		this.hidden = hidden;
		this.inProj = new Dense(ps, name + ".in", in, 3 * hidden, rng, 1f);
		this.hidProj = new Dense(ps, name + ".hid", hidden, 3 * hidden, rng, 0.7f);
		// Bias update gate negative so h_t = (1 - z) * h_{t-1} + z * n retains memory across ticks from step 0.
		Arrays.fill(inProj.bias.w, 0, hidden, -1f);
	}

	/** Activations saved for one time step of a sequence chunk. */
	public final class StepCache {
		final float[] hPrev = new float[hidden];
		final float[] gx = new float[3 * hidden];
		final float[] gh = new float[3 * hidden];
		final float[] z = new float[hidden];
		final float[] r = new float[hidden];
		final float[] n = new float[hidden];
	}

	/** Scratch gradient buffers reused across steps within one worker thread. */
	public final class Scratch {
		final float[] dGx = new float[3 * hidden];
		final float[] dGh = new float[3 * hidden];
		final float[] dHProj = new float[hidden];
	}

	public StepCache newStepCache() {
		return new StepCache();
	}

	public Scratch newScratch() {
		return new Scratch();
	}

	/**
	 * Advances one tick: reads {@code x[xOff .. xOff+in)} and {@code hPrev[0 .. hidden)},
	 * writes {@code hOut[0 .. hidden)}. Safe when {@code hPrev == hOut}.
	 */
	public void forward(float[] x, int xOff, float[] hPrev, float[] hOut, StepCache c) {
		final int H = hidden;
		System.arraycopy(hPrev, 0, c.hPrev, 0, H);
		inProj.forward(x, xOff, c.gx, 0);
		hidProj.forward(c.hPrev, 0, c.gh, 0);
		for (int i = 0; i < H; i++) {
			float z = sigmoid(c.gx[i] + c.gh[i]);
			float r = sigmoid(c.gx[H + i] + c.gh[H + i]);
			float n = (float) Math.tanh(c.gx[2 * H + i] + r * c.gh[2 * H + i]);
			c.z[i] = z;
			c.r[i] = r;
			c.n[i] = n;
			hOut[i] = (1f - z) * c.hPrev[i] + z * n;
		}
	}

	/**
	 * Backward pass for one tick given {@code dH[0 .. hidden)} (gradient w.r.t. this step's output h_t).
	 * Accumulates parameter gradients into {@code grads}, writes gradient w.r.t. x into {@code dX}
	 * (if non-null), and writes gradient w.r.t. h_{t-1} into {@code dHPrev} (overwritten; safe if {@code dHPrev == dH}).
	 */
	public void backward(float[] x, int xOff, float[] dH, StepCache c, Scratch s, float[] dX, int dXOff, float[] dHPrev, float[][] grads) {
		final int H = hidden;
		for (int i = 0; i < H; i++) {
			float dh = dH[i];
			float z = c.z[i];
			float r = c.r[i];
			float n = c.n[i];
			float hn = c.gh[2 * H + i];
			float dzPre = dh * (n - c.hPrev[i]) * z * (1f - z);
			float dnPre = dh * z * (1f - n * n);
			float drPre = dnPre * hn * r * (1f - r);
			s.dGx[i] = dzPre;
			s.dGx[H + i] = drPre;
			s.dGx[2 * H + i] = dnPre;
			s.dGh[i] = dzPre;
			s.dGh[H + i] = drPre;
			s.dGh[2 * H + i] = dnPre * r;
		}
		inProj.backward(x, xOff, s.dGx, 0, dX, dXOff, grads);
		hidProj.backward(c.hPrev, 0, s.dGh, 0, s.dHProj, 0, grads);
		for (int i = 0; i < H; i++) {
			dHPrev[i] = (1f - c.z[i]) * dH[i] + s.dHProj[i];
		}
	}

	private static float sigmoid(float x) {
		if (x >= 0f) {
			float e = (float) Math.exp(-x);
			return 1f / (1f + e);
		}
		float e = (float) Math.exp(x);
		return e / (1f + e);
	}
}
