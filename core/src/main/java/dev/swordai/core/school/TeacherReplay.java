package dev.swordai.core.school;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.Policy;

import java.util.Random;

/**
 * The technique teachers' examples, kept and learned from again and again (DAgger, Ross et al. 2011: the states
 * are the ones the network itself got into, the keys the teacher's): the last {@link #capacity} steps on which a
 * technique teacher spoke. After every update the network is taught on minibatches of them, each technique as hard
 * as its own pull says ({@link SchoolStages#techPulls}): so how to do a technique is learned from every moment seen
 * lately, not only from the newest batch, while when to do it is still the reward's.
 *
 * <p>Only the heads the teacher taught are pulled; the jump's and the swing's heads are told the teacher's W/S,
 * sprint and jump where it taught them, as they would be told the network's own (teacher forcing).
 */
public final class TeacherReplay {
	public final int capacity;
	private int size;
	private int next;
	private final byte[][] peri;
	private final byte[][] fovea;
	private final float[][] vec;
	private final float[][] mem;
	private final float[][] pred;
	private final float[][] target;
	private final byte[][] cond;
	private final int[] heads;
	private final int[] technique;
	private long baseSeen;

	public TeacherReplay(int capacity) {
		this.capacity = capacity;
		peri = new byte[capacity][ObsSpec.PERI_SIZE];
		fovea = new byte[capacity][ObsSpec.FOVEA_SIZE];
		vec = new float[capacity][ObsSpec.VEC];
		mem = new float[capacity][SchoolObs.MEM];
		pred = new float[capacity][Predictor.OUT];
		target = new float[capacity][ActionSpace.TOTAL_LOGITS];
		cond = new byte[capacity][ActionSpace.HEADS];
		heads = new int[capacity];
		technique = new int[capacity];
	}

	public int size() {
		return size;
	}

	/** Keeps every step of {@code r} on which a technique teacher spoke. */
	public void addFrom(SchoolRollout r) {
		for (int i = 0; i < r.size; i++) {
			int h = r.teacherHeads[i];
			int t = r.teacherTechnique[i];
			// The ordinary fight's no-jump is on most steps: one in six of them is kept, so it does not crowd the techniques out.
			if (h == 0 || t < 0 || t == Technique.BASE.ordinal() && (baseSeen++ % 6) != 0) {
				continue;
			}
			int j = next;
			next = (next + 1) % capacity;
			size = Math.min(capacity, size + 1);
			System.arraycopy(r.peri[i], 0, peri[j], 0, ObsSpec.PERI_SIZE);
			System.arraycopy(r.fovea[i], 0, fovea[j], 0, ObsSpec.FOVEA_SIZE);
			System.arraycopy(r.vec[i], 0, vec[j], 0, ObsSpec.VEC);
			System.arraycopy(r.mem[i], 0, mem[j], 0, SchoolObs.MEM);
			System.arraycopy(r.pred[i], 0, pred[j], 0, Predictor.OUT);
			System.arraycopy(r.teacher[i], 0, target[j], 0, ActionSpace.TOTAL_LOGITS);
			for (int k = 0; k < ActionSpace.HEADS; k++) {
				cond[j][k] = (h & (1 << k)) != 0 ? (byte) argmax(r.teacher[i], k) : r.action[i][k];
			}
			heads[j] = h;
			technique[j] = t;
		}
	}

	/** What one pass of learning did: the mean cross-entropy to the teachers before it, and how often the network's likeliest key was theirs. */
	public static final class Stats {
		public int steps;
		public float ce;
		public float agree;
		public long millis;

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT, "%d examples, ce %.3f, likeliest keys the teacher's %.0f%%, %d ms", steps, ce, 100f * agree, millis);
		}
	}

	/**
	 * {@code batches} minibatches of {@code batch} examples, each pulled with its technique's pull; nothing is learned
	 * for a technique whose pull is zero.
	 */
	public Stats learn(SchoolNet actor, SchoolNet.Workspace ws, float[] pulls, int batches, int batch, float lr, float maxNorm, Random rng) {
		long start = System.nanoTime();
		Stats st = new Stats();
		if (size == 0) {
			return st;
		}
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		double ce = 0;
		int headsSeen = 0;
		int agreed = 0;
		for (int b = 0; b < batches; b++) {
			ParamSet.zero(ws.grads);
			int used = 0;
			for (int n = 0; n < batch; n++) {
				int i = rng.nextInt(size);
				int t = technique[i];
				float w = t >= 0 && t < Technique.COUNT ? pulls[t] : 0f;
				if (w <= 0f) {
					continue;
				}
				actor.forward(ws, peri[i], fovea[i], vec[i], mem[i], pred[i]);
				actor.condition(ws, cond[i]);
				java.util.Arrays.fill(ws.dLogits, 0f);
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					if ((heads[i] & (1 << h)) == 0) {
						continue;
					}
					int off = ActionSpace.OFFSETS[h];
					int size = ActionSpace.SIZES[h];
					Policy.softmax(ws.logits, off, size, probs);
					int best = 0;
					for (int k = 0; k < size; k++) {
						float p = probs[off + k];
						float q = target[i][off + k];
						ws.dLogits[off + k] = w * (p - q);
						if (q > 0f) {
							ce -= q * Math.log(Math.max(p, 1e-9f));
						}
						if (p > probs[off + best]) {
							best = k;
						}
					}
					headsSeen++;
					agreed += best == argmax(target[i], h) ? 1 : 0;
				}
				actor.backward(ws, peri[i], fovea[i], vec[i], mem[i]);
				used++;
			}
			if (used > 0) {
				Adam.step(actor.params, ws.grads, lr, 1f / used, maxNorm);
				st.steps += used;
			}
		}
		st.ce = headsSeen == 0 ? 0f : (float) (ce / headsSeen);
		st.agree = headsSeen == 0 ? 0f : (float) agreed / headsSeen;
		st.millis = (System.nanoTime() - start) / 1_000_000L;
		return st;
	}

	private static int argmax(float[] target, int h) {
		int off = ActionSpace.OFFSETS[h];
		int best = 0;
		for (int k = 1; k < ActionSpace.SIZES[h]; k++) {
			if (target[off + k] > target[off + best]) {
				best = k;
			}
		}
		return best;
	}
}
