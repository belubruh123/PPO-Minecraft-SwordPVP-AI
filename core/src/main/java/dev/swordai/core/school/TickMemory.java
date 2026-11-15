package dev.swordai.core.school;

import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;

import java.util.Arrays;
import java.util.Random;

/**
 * A learned short-term memory (run 109, the user 15 Nov 22:30: "make the network bigger and have memory"; before: "it don't even
 * know what it is doing when jump and just keep jump and jump for no reason"): a micro transformer over the last {@code ticks}
 * ticks of the fight.
 *
 * <p>The window itself is part of the stored observation (the side input, {@link SchoolNet#WIN_AT}), so PPO stays feed-forward:
 * no hidden state is carried between ticks, no back-propagation through time across steps, and minibatches can still be shuffled.
 * Each tick is a token of {@code per} numbers (what was seen and done that tick, see {@code SchoolLearner.Intent#pushTick}), the
 * newest first; a token whose first number (its "real tick" flag) is below one half is a tick before the fight began, and is masked.
 *
 * <ol>
 * <li>Embedding: a linear map of the token plus a learned embedding of its age.</li>
 * <li>One pre-norm transformer layer: causal multi-head self-attention (a tick sees itself and the ticks before it, never the ones
 * after) with a residual, then a per-tick feed-forward residual block ({@link ResBlock}).</li>
 * <li>A readout: multi-head attention from a query made of what the network sees now (the trunk's input) over every tick, and the
 * newest tick's state, mixed through a ReLU layer into the summary {@link #out} numbers.</li>
 * </ol>
 * The summary goes into the network through layers that start at zero ({@link SchoolNet}), so a network saved before plays exactly
 * as it did, and learns to read its memory from there.
 */
public final class TickMemory {
	private static final float LN_EPS = 1e-5f;

	public final int ticks;
	public final int per;
	public final int dim;
	public final int heads;
	public final int headDim;
	public final int out;
	public final int queryIn;
	private final float scale;

	private final Dense embed;
	private final Param pos;
	private final Param ln1g;
	private final Param ln1b;
	private final Dense qkv;
	private final Dense attnOut;
	private final ResBlock ffn;
	private final Param ln3g;
	private final Param ln3b;
	private final Dense query;
	private final Dense kv;
	private final Dense mix;

	public TickMemory(ParamSet ps, String name, int ticks, int per, int dim, int heads, int ffnHidden, int out, int queryIn, Random rng) {
		if (dim % heads != 0) {
			throw new IllegalArgumentException("dim " + dim + " is not a multiple of heads " + heads);
		}
		this.ticks = ticks;
		this.per = per;
		this.dim = dim;
		this.heads = heads;
		this.headDim = dim / heads;
		this.out = out;
		this.queryIn = queryIn;
		this.scale = (float) (1.0 / Math.sqrt(headDim));
		embed = new Dense(ps, name + ".embed", per, dim, rng, 1f);
		pos = ps.add(name + ".pos", ticks * dim);
		for (int i = 0; i < pos.w.length; i++) {
			pos.w[i] = (float) rng.nextGaussian() * 0.5f;
		}
		ln1g = ps.add(name + ".ln1.g", dim);
		Arrays.fill(ln1g.w, 1f);
		ln1b = ps.add(name + ".ln1.b", dim);
		qkv = new Dense(ps, name + ".qkv", dim, 3 * dim, rng, 1f);
		attnOut = new Dense(ps, name + ".attn.out", dim, dim, rng, 1f);
		ffn = new ResBlock(ps, name + ".ffn", dim, ffnHidden, rng);
		ln3g = ps.add(name + ".ln3.g", dim);
		Arrays.fill(ln3g.w, 1f);
		ln3b = ps.add(name + ".ln3.b", dim);
		query = new Dense(ps, name + ".query", queryIn, dim, rng, 1f);
		kv = new Dense(ps, name + ".kv", dim, 2 * dim, rng, 1f);
		mix = new Dense(ps, name + ".mix", 2 * dim, out, rng, (float) Math.sqrt(2.0));
	}

	/** One thread's activations, kept from {@link #forward} for {@link #backward}. Arrays are indexed by the real ticks, newest first. */
	public final class Cache {
		int n;
		final int[] at = new int[ticks];
		final float[] e = new float[ticks * dim];
		final float[] xhat1 = new float[ticks * dim];
		final float[] rstd1 = new float[ticks];
		final float[] a = new float[ticks * dim];
		final float[] qkvOut = new float[ticks * 3 * dim];
		final float[] p = new float[heads * ticks * ticks];
		final float[] o = new float[ticks * dim];
		/** The residual stream after the attention, then (in place) after the feed-forward block. */
		final float[] h = new float[ticks * dim];
		final ResBlock.Cache[] ffnCaches = new ResBlock.Cache[ticks];
		final float[] xhat3 = new float[ticks * dim];
		final float[] rstd3 = new float[ticks];
		final float[] c = new float[ticks * dim];
		final float[] kvOut = new float[ticks * 2 * dim];
		final float[] q = new float[dim];
		final float[] pr = new float[heads * ticks];
		final float[] r = new float[2 * dim];
		final float[] zPre = new float[out];
		/** The summary. */
		public final float[] z = new float[out];

		final float[] tmp = new float[Math.max(3 * dim, ticks)];
		final float[] dZ = new float[out];
		final float[] dR = new float[2 * dim];
		final float[] dQ = new float[dim];
		final float[] dKv = new float[ticks * 2 * dim];
		final float[] dC = new float[ticks * dim];
		final float[] dH = new float[ticks * dim];
		final float[] dO = new float[ticks * dim];
		final float[] dQkv = new float[ticks * 3 * dim];
		final float[] dA = new float[ticks * dim];
		final float[] dE = new float[ticks * dim];

		Cache() {
			for (int t = 0; t < ticks; t++) {
				ffnCaches[t] = ffn.newCache();
			}
		}
	}

	public Cache newCache() {
		return new Cache();
	}

	/** How many numbers the window takes. */
	public int size() {
		return ticks * per;
	}

	/**
	 * The summary of the window {@code x[xOff .. xOff + ticks*per)} given what is seen now ({@code now[0 .. queryIn)}), into
	 * {@code c.z}.
	 */
	public void forward(float[] x, int xOff, float[] now, Cache c) {
		int n = 0;
		for (int t = 0; t < ticks; t++) {
			if (x[xOff + t * per] > 0.5f) {
				c.at[n++] = t;
			}
		}
		c.n = n;
		if (n == 0) {
			Arrays.fill(c.r, 0f);
			mix.forward(c.r, 0, c.zPre, 0);
			for (int i = 0; i < out; i++) {
				c.z[i] = Math.max(0f, c.zPre[i]);
			}
			return;
		}
		final int d = dim;
		final int d3 = 3 * dim;
		for (int s = 0; s < n; s++) {
			int t = c.at[s];
			embed.forward(x, xOff + t * per, c.e, s * d);
			for (int i = 0; i < d; i++) {
				c.e[s * d + i] += pos.w[t * d + i];
			}
			c.rstd1[s] = norm(c.e, s * d, ln1g.w, ln1b.w, c.xhat1, c.a, d);
			qkv.forward(c.a, s * d, c.qkvOut, s * d3);
		}
		// Causal self-attention: the tick s (newer at smaller s) sees the ticks u >= s, itself and the older ones.
		for (int hd = 0; hd < heads; hd++) {
			int qo = hd * headDim;
			int ko = d + hd * headDim;
			int vo = 2 * d + hd * headDim;
			for (int s = 0; s < n; s++) {
				int pb = (hd * ticks + s) * ticks;
				float most = Float.NEGATIVE_INFINITY;
				for (int u = s; u < n; u++) {
					float dot = 0f;
					for (int k = 0; k < headDim; k++) {
						dot += c.qkvOut[s * d3 + qo + k] * c.qkvOut[u * d3 + ko + k];
					}
					dot *= scale;
					c.p[pb + u] = dot;
					most = Math.max(most, dot);
				}
				float sum = 0f;
				for (int u = s; u < n; u++) {
					float ex = (float) Math.exp(c.p[pb + u] - most);
					c.p[pb + u] = ex;
					sum += ex;
				}
				float inv = 1f / sum;
				Arrays.fill(c.o, s * d + qo, s * d + qo + headDim, 0f);
				for (int u = s; u < n; u++) {
					float pu = c.p[pb + u] * inv;
					c.p[pb + u] = pu;
					for (int k = 0; k < headDim; k++) {
						c.o[s * d + qo + k] += pu * c.qkvOut[u * d3 + vo + k];
					}
				}
			}
		}
		for (int s = 0; s < n; s++) {
			attnOut.forward(c.o, s * d, c.h, s * d);
			for (int i = 0; i < d; i++) {
				c.h[s * d + i] += c.e[s * d + i];
			}
			ffn.forward(c.h, s * d, c.ffnCaches[s]);
			c.rstd3[s] = norm(c.h, s * d, ln3g.w, ln3b.w, c.xhat3, c.c, d);
			kv.forward(c.c, s * d, c.kvOut, s * 2 * d);
		}
		// The readout: what is seen now asks every tick.
		query.forward(now, 0, c.q, 0);
		for (int hd = 0; hd < heads; hd++) {
			int qo = hd * headDim;
			int vo = d + hd * headDim;
			int pb = hd * ticks;
			float most = Float.NEGATIVE_INFINITY;
			for (int s = 0; s < n; s++) {
				float dot = 0f;
				for (int k = 0; k < headDim; k++) {
					dot += c.q[qo + k] * c.kvOut[s * 2 * d + qo + k];
				}
				dot *= scale;
				c.pr[pb + s] = dot;
				most = Math.max(most, dot);
			}
			float sum = 0f;
			for (int s = 0; s < n; s++) {
				float ex = (float) Math.exp(c.pr[pb + s] - most);
				c.pr[pb + s] = ex;
				sum += ex;
			}
			float inv = 1f / sum;
			Arrays.fill(c.r, qo, qo + headDim, 0f);
			for (int s = 0; s < n; s++) {
				float ps = c.pr[pb + s] * inv;
				c.pr[pb + s] = ps;
				for (int k = 0; k < headDim; k++) {
					c.r[qo + k] += ps * c.kvOut[s * 2 * d + vo + k];
				}
			}
		}
		// And the newest real tick's own state.
		System.arraycopy(c.c, 0, c.r, d, d);
		mix.forward(c.r, 0, c.zPre, 0);
		for (int i = 0; i < out; i++) {
			c.z[i] = Math.max(0f, c.zPre[i]);
		}
	}

	/**
	 * Back-propagates {@code dz} (the gradient on the summary of the last {@link #forward}) into {@code grads}, and writes the
	 * gradient on what is seen now into {@code dNow[0 .. queryIn)} (overwritten; zero when no tick was real).
	 */
	public void backward(float[] x, int xOff, float[] now, float[] dz, Cache c, float[] dNow, float[][] grads) {
		for (int i = 0; i < out; i++) {
			c.dZ[i] = c.z[i] > 0f ? dz[i] : 0f;
		}
		mix.backward(c.r, 0, c.dZ, 0, c.dR, 0, grads);
		int n = c.n;
		if (n == 0) {
			Arrays.fill(dNow, 0, queryIn, 0f);
			return;
		}
		final int d = dim;
		final int d2 = 2 * dim;
		final int d3 = 3 * dim;
		Arrays.fill(c.dC, 0, n * d, 0f);
		Arrays.fill(c.dKv, 0, n * d2, 0f);
		Arrays.fill(c.dQ, 0f);
		for (int i = 0; i < d; i++) {
			c.dC[i] += c.dR[d + i];
		}
		// The readout.
		for (int hd = 0; hd < heads; hd++) {
			int qo = hd * headDim;
			int vo = d + hd * headDim;
			int pb = hd * ticks;
			float dotSum = 0f;
			for (int s = 0; s < n; s++) {
				float ps = c.pr[pb + s];
				float dp = 0f;
				for (int k = 0; k < headDim; k++) {
					float g = c.dR[qo + k];
					dp += g * c.kvOut[s * d2 + vo + k];
					c.dKv[s * d2 + vo + k] += ps * g;
				}
				c.tmp[s] = dp;
				dotSum += ps * dp;
			}
			for (int s = 0; s < n; s++) {
				float ds = c.pr[pb + s] * (c.tmp[s] - dotSum) * scale;
				if (ds == 0f) {
					continue;
				}
				for (int k = 0; k < headDim; k++) {
					c.dQ[qo + k] += ds * c.kvOut[s * d2 + qo + k];
					c.dKv[s * d2 + qo + k] += ds * c.q[qo + k];
				}
			}
		}
		query.backward(now, 0, c.dQ, 0, dNow, 0, grads);
		for (int s = 0; s < n; s++) {
			kv.backward(c.c, s * d, c.dKv, s * d2, c.tmp, 0, grads);
			for (int i = 0; i < d; i++) {
				c.dC[s * d + i] += c.tmp[i];
			}
			normBackward(c.dC, s * d, ln3g, ln3b, c.xhat3, c.rstd3[s], c.dH, grads, d);
			ffn.backward(c.dH, s * d, c.ffnCaches[s], grads);
			// h = e + attnOut(o): the skip path and the attention's.
			System.arraycopy(c.dH, s * d, c.dE, s * d, d);
			attnOut.backward(c.o, s * d, c.dH, s * d, c.dO, s * d, grads);
		}
		Arrays.fill(c.dQkv, 0, n * d3, 0f);
		for (int hd = 0; hd < heads; hd++) {
			int qo = hd * headDim;
			int ko = d + hd * headDim;
			int vo = 2 * d + hd * headDim;
			for (int s = 0; s < n; s++) {
				int pb = (hd * ticks + s) * ticks;
				float dotSum = 0f;
				for (int u = s; u < n; u++) {
					float pu = c.p[pb + u];
					float dp = 0f;
					for (int k = 0; k < headDim; k++) {
						float g = c.dO[s * d + qo + k];
						dp += g * c.qkvOut[u * d3 + vo + k];
						c.dQkv[u * d3 + vo + k] += pu * g;
					}
					c.tmp[u] = dp;
					dotSum += pu * dp;
				}
				for (int u = s; u < n; u++) {
					float ds = c.p[pb + u] * (c.tmp[u] - dotSum) * scale;
					if (ds == 0f) {
						continue;
					}
					for (int k = 0; k < headDim; k++) {
						c.dQkv[s * d3 + qo + k] += ds * c.qkvOut[u * d3 + ko + k];
						c.dQkv[u * d3 + ko + k] += ds * c.qkvOut[s * d3 + qo + k];
					}
				}
			}
		}
		float[] gPos = grads[pos.id];
		for (int s = 0; s < n; s++) {
			qkv.backward(c.a, s * d, c.dQkv, s * d3, c.dA, s * d, grads);
			// dE += the layer norm's input gradient (normBackward overwrites, so through tmp).
			normBackward(c.dA, s * d, ln1g, ln1b, c.xhat1, c.rstd1[s], c.tmp, grads, d, s * d);
			int t = c.at[s];
			for (int i = 0; i < d; i++) {
				c.dE[s * d + i] += c.tmp[i];
				gPos[t * d + i] += c.dE[s * d + i];
			}
			embed.backward(x, xOff + t * per, c.dE, s * d, null, 0, grads);
		}
	}

	/** Layer norm of {@code x[off .. off+n)} into {@code y[off ..]}, keeping x-hat; returns 1/std. */
	private static float norm(float[] x, int off, float[] g, float[] b, float[] xhat, float[] y, int n) {
		float mean = 0f;
		for (int i = 0; i < n; i++) {
			mean += x[off + i];
		}
		mean /= n;
		float var = 0f;
		for (int i = 0; i < n; i++) {
			float dv = x[off + i] - mean;
			var += dv * dv;
		}
		var /= n;
		float rstd = (float) (1.0 / Math.sqrt(var + LN_EPS));
		for (int i = 0; i < n; i++) {
			float xh = (x[off + i] - mean) * rstd;
			xhat[off + i] = xh;
			y[off + i] = g[i] * xh + b[i];
		}
		return rstd;
	}

	/** The layer norm's backward for the token at {@code off}: its input gradient into {@code dx[off ..]} (overwritten). */
	private static void normBackward(float[] dy, int off, Param g, Param b, float[] xhat, float rstd, float[] dx, float[][] grads, int n) {
		normBackward(dy, off, g, b, xhat, rstd, dx, grads, n, off, off);
	}

	/** The same with the x-hat at {@code off} and the input gradient written to {@code dx[0 .. n)}. */
	private static void normBackward(float[] dy, int off, Param g, Param b, float[] xhat, float rstd, float[] dx, float[][] grads, int n,
			int xhatOff) {
		normBackward(dy, off, g, b, xhat, rstd, dx, grads, n, xhatOff, 0);
	}

	private static void normBackward(float[] dy, int off, Param g, Param b, float[] xhat, float rstd, float[] dx, float[][] grads, int n,
			int xhatOff, int dxOff) {
		float[] gg = grads[g.id];
		float[] gb = grads[b.id];
		float mean = 0f;
		float meanX = 0f;
		for (int i = 0; i < n; i++) {
			float dyi = dy[off + i];
			float xh = xhat[xhatOff + i];
			gg[i] += dyi * xh;
			gb[i] += dyi;
			float dxh = dyi * g.w[i];
			mean += dxh;
			meanX += dxh * xh;
		}
		mean /= n;
		meanX /= n;
		for (int i = 0; i < n; i++) {
			float dxh = dy[off + i] * g.w[i];
			dx[dxOff + i] = rstd * (dxh - mean - xhat[xhatOff + i] * meanX);
		}
	}
}
