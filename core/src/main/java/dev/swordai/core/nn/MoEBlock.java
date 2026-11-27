package dev.swordai.core.nn;

import java.util.Arrays;
import java.util.Random;

/**
 * Soft-Gated Mixture of Experts residual block for Deep RL parameter & multi-kit scaling
 * (Obando-Ceron et al., ICML 2024 "Mixtures of Experts Unlock Parameter Scaling for Deep RL";
 * Fedus et al., JMLR 2022 "Switch Transformers" auxiliary load-balancing loss).
 *
 * <p>Computes {@code out = x + sum_e g_e(routerIn) * down_e(relu(up_e(LN_e(x))))} where every
 * expert's output projection {@code down_e} is zero-initialized, giving exact bit-for-bit Net2Net
 * function preservation ({@code out == x}) on tick 0 when expanding a pre-trained Sword policy.
 */
public final class MoEBlock {
	private static final float LN_EPS = 1e-5f;
	public static final int EXPERTS = 4;

	public final int width;
	public final int hidden;
	public final int routerIn;
	public final Dense router;
	public final Param[] gain = new Param[EXPERTS];
	public final Param[] shift = new Param[EXPERTS];
	public final Dense[] up = new Dense[EXPERTS];
	public final Dense[] down = new Dense[EXPERTS];

	public MoEBlock(ParamSet ps, String name, int width, int hidden, int routerIn, Random rng) {
		this.width = width;
		this.hidden = hidden;
		this.routerIn = routerIn;
		this.router = new Dense(ps, name + ".router", routerIn, EXPERTS, rng, 0.01f);
		float relu = (float) Math.sqrt(2.0);
		for (int e = 0; e < EXPERTS; e++) {
			String p = name + ".e" + e;
			gain[e] = ps.add(p + ".ln.g", width);
			Arrays.fill(gain[e].w, 1f);
			shift[e] = ps.add(p + ".ln.b", width);
			up[e] = new Dense(ps, p + ".up", width, hidden, rng, relu);
			down[e] = new Dense(ps, p + ".down", hidden, width, rng, 0f);
		}
	}

	public final class Cache {
		public final float[] gate = new float[EXPERTS];
		final float[] rLogits = new float[EXPERTS];
		final float[][] xhat = new float[EXPERTS][width];
		final float[][] z = new float[EXPERTS][width];
		final float[][] h = new float[EXPERTS][hidden];
		final float[][] delta = new float[EXPERTS][width];
		final float[] rstd = new float[EXPERTS];
	}

	public final class Scratch {
		final float[] dGate = new float[EXPERTS];
		final float[] dRLogits = new float[EXPERTS];
		final float[] dDelta = new float[width];
		final float[] dH = new float[hidden];
		final float[] dZ = new float[width];
	}

	public Cache newCache() { return new Cache(); }
	public Scratch newScratch() { return new Scratch(); }

	/** Reads {@code x[0..width)} and {@code rIn[0..routerIn)}, writes {@code out[0..width)} (may alias {@code x}). */
	public void forward(float[] x, float[] rIn, float[] out, Cache c) {
		router.forward(rIn, 0, c.rLogits, 0);
		float max = Float.NEGATIVE_INFINITY;
		for (int e = 0; e < EXPERTS; e++) if (c.rLogits[e] > max) max = c.rLogits[e];
		float sum = 0f;
		for (int e = 0; e < EXPERTS; e++) {
			float v = (float) Math.exp(c.rLogits[e] - max);
			c.gate[e] = v;
			sum += v;
		}
		float inv = 1f / sum;
		for (int e = 0; e < EXPERTS; e++) c.gate[e] *= inv;

		// Shared input statistics for LayerNorm (same x across all experts)
		float mean = 0f;
		for (int i = 0; i < width; i++) mean += x[i];
		mean /= width;
		float var = 0f;
		for (int i = 0; i < width; i++) { float d = x[i] - mean; var += d * d; }
		float rstd = (float) (1.0 / Math.sqrt(var / width + LN_EPS));

		System.arraycopy(x, 0, out, 0, width);
		for (int e = 0; e < EXPERTS; e++) {
			c.rstd[e] = rstd;
			float[] xh = c.xhat[e], ze = c.z[e], he = c.h[e], de = c.delta[e];
			float[] gw = gain[e].w, sw = shift[e].w;
			for (int i = 0; i < width; i++) {
				float n = (x[i] - mean) * rstd;
				xh[i] = n;
				ze[i] = gw[i] * n + sw[i];
			}
			up[e].forward(ze, 0, he, 0);
			for (int i = 0; i < hidden; i++) if (he[i] < 0f) he[i] = 0f;
			down[e].forward(he, 0, de, 0);
			float g = c.gate[e];
			for (int i = 0; i < width; i++) out[i] += g * de[i];
		}
	}

	/**
	 * Backpropagates {@code dOut[0..width)} and optional Switch-Transformer load-balance gradient
	 * {@code dGateExtra[0..EXPERTS)} (may be null), accumulating into {@code dX[0..width)},
	 * {@code dRIn[0..routerIn)} (if non-null), and {@code grads}.
	 */
	public void backward(float[] rIn, float[] dOut, float[] dGateExtra, Cache c, Scratch s,
			float[] dX, float[] dRIn, float[][] grads) {
		for (int i = 0; i < width; i++) dX[i] += dOut[i];
		for (int e = 0; e < EXPERTS; e++) {
			float[] de = c.delta[e];
			float dg = dGateExtra != null ? dGateExtra[e] : 0f;
			float g = c.gate[e];
			for (int i = 0; i < width; i++) {
				dg += dOut[i] * de[i];
				s.dDelta[i] = g * dOut[i];
			}
			s.dGate[e] = dg;

			float[] he = c.h[e];
			down[e].backward(he, 0, s.dDelta, 0, s.dH, 0, grads);
			for (int i = 0; i < hidden; i++) if (he[i] <= 0f) s.dH[i] = 0f;
			up[e].backward(c.z[e], 0, s.dH, 0, s.dZ, 0, grads);

			float[] xh = c.xhat[e];
			float[] gw = gain[e].w;
			float[] gGain = grads[gain[e].id];
			float[] gShift = grads[shift[e].id];
			float mean = 0f, meanX = 0f;
			for (int i = 0; i < width; i++) {
				gGain[i] += s.dZ[i] * xh[i];
				gShift[i] += s.dZ[i];
				float dxh = s.dZ[i] * gw[i];
				s.dZ[i] = dxh;
				mean += dxh;
				meanX += dxh * xh[i];
			}
			mean /= width;
			meanX /= width;
			float rstd = c.rstd[e];
			for (int i = 0; i < width; i++) {
				dX[i] += rstd * (s.dZ[i] - mean - xh[i] * meanX);
			}
		}
		float dot = 0f;
		for (int e = 0; e < EXPERTS; e++) dot += c.gate[e] * s.dGate[e];
		for (int e = 0; e < EXPERTS; e++) s.dRLogits[e] = c.gate[e] * (s.dGate[e] - dot);
		router.backward(rIn, 0, s.dRLogits, 0, dRIn, 0, grads);
	}
}
