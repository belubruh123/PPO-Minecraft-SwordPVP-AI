package dev.swordai.core.nn;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * All parameters of one network. Weights are shared between threads (read-only while gradients are
 * being computed); every worker thread owns its own gradient buffers from {@link #newGrads()}.
 */
public final class ParamSet {
	private final List<Param> params = new ArrayList<>();
	/** Adam step counter, kept here so it is checkpointed with the moments. */
	public long adamSteps;
	private int missing;

	/** How many tensors at the end of this network the file last read did not have: parts added since it was saved. */
	public int missing() {
		return missing;
	}

	public Param add(String name, int size) {
		Param p = new Param(name, size, params.size());
		params.add(p);
		return p;
	}

	public List<Param> params() {
		return params;
	}

	public int totalSize() {
		int n = 0;
		for (Param p : params) {
			n += p.w.length;
		}
		return n;
	}

	/** Takes over the weights of a network of the same shape. The optimiser's moments are left alone. */
	public void copyWeightsFrom(ParamSet other) {
		if (other.params.size() != params.size()) {
			throw new IllegalArgumentException("the networks have " + other.params.size() + " and " + params.size() + " tensors");
		}
		for (int i = 0; i < params.size(); i++) {
			float[] from = other.params.get(i).w;
			float[] to = params.get(i).w;
			if (from.length != to.length) {
				throw new IllegalArgumentException("tensor " + params.get(i).name + " has another size in the other network");
			}
			System.arraycopy(from, 0, to, 0, to.length);
		}
	}

	public float[][] newGrads() {
		float[][] g = new float[params.size()][];
		for (Param p : params) {
			g[p.id] = new float[p.w.length];
		}
		return g;
	}

	public static void zero(float[][] grads) {
		for (float[] g : grads) {
			Arrays.fill(g, 0f);
		}
	}

	/** dst += src, element-wise over every buffer. */
	public static void accumulate(float[][] dst, float[][] src) {
		for (int i = 0; i < dst.length; i++) {
			float[] d = dst[i];
			float[] s = src[i];
			for (int j = 0; j < d.length; j++) {
				d[j] += s[j];
			}
		}
	}

	public void write(DataOutputStream out) throws IOException {
		out.writeLong(adamSteps);
		out.writeInt(params.size());
		for (Param p : params) {
			out.writeUTF(p.name);
			out.writeInt(p.w.length);
			writeFloats(out, p.w);
			writeFloats(out, p.m);
			writeFloats(out, p.v);
		}
	}

	/** Loads into the existing layout; fails loudly if the saved network has a different shape. */
	public void read(DataInputStream in) throws IOException {
		read(in, 0, 0);
	}

	/**
	 * Loads a network saved before {@code inserted} new inputs were added at position
	 * {@code insertAt} of an input vector. Layers fed by that vector have fewer rows in the file;
	 * their rows are moved to the new positions and the rows for the new inputs start at zero, so
	 * the loaded network behaves exactly as it did until it learns to use them.
	 * With {@code inserted == 0} every tensor must match exactly.
	 */
	public void read(DataInputStream in, int insertAt, int inserted) throws IOException {
		long steps = in.readLong();
		int n = in.readInt();
		if (n > params.size()) {
			throw new IOException("checkpoint has " + n + " tensors, network expects " + params.size());
		}
		// A network that has gained parts since it was saved: the file holds the tensors it had then,
		// in order, and the parts added after them stay as they were made, new.
		missing = params.size() - n;
		int index = 0;
		for (Param p : params) {
			if (index++ >= n) {
				break;
			}
			String name = in.readUTF();
			int len = in.readInt();
			if (!name.equals(p.name)) {
				throw new IOException("checkpoint tensor " + name + " found where " + p.name + " was expected");
			}
			if (len == p.w.length) {
				readFloats(in, p.w);
				readFloats(in, p.m);
				readFloats(in, p.v);
				continue;
			}
			int grown = p.w.length - len;
			int cols = inserted > 0 && grown > 0 && grown % inserted == 0 ? grown / inserted : 0;
			if (cols == 0 || len % cols != 0 || insertAt > len / cols) {
				throw new IOException("checkpoint tensor " + name + "[" + len + "] does not match "
						+ p.name + "[" + p.w.length + "]");
			}
			float[] saved = new float[len];
			for (float[] target : new float[][] {p.w, p.m, p.v}) {
				readFloats(in, saved);
				Arrays.fill(target, 0f);
				System.arraycopy(saved, 0, target, 0, insertAt * cols);
				System.arraycopy(saved, insertAt * cols, target, (insertAt + inserted) * cols, len - insertAt * cols);
			}
		}
		adamSteps = steps;
	}

	private static void writeFloats(DataOutputStream out, float[] a) throws IOException {
		for (float f : a) {
			out.writeFloat(f);
		}
	}

	private static void readFloats(DataInputStream in, float[] a) throws IOException {
		for (int i = 0; i < a.length; i++) {
			a[i] = in.readFloat();
		}
	}
}
