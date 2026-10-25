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
	/**
	 * Kept from the installed model as a teacher instead: on the heads no technique teacher had, what the installed
	 * fighter (its network and rules) would have pressed; one step in {@link #SHADOW_EVERY}. All under technique
	 * {@link Technique#BASE}'s slot, learned with the installed teacher's pull.
	 */
	private final boolean shadow;
	/** See {@link SchoolPpo.Settings#installedBody}. */
	public boolean installedBody;
	/**
	 * Every other example drawn technique first (any technique with examples and a pull, alike), then one of its
	 * examples: a rare technique (C3, K3: a few hundred of 20 000 examples) is practised as often as the common ones
	 * (T4, T7). Tried on its own once (24 Oct 02:28) it taught jumping everywhere, the techniques' keys spilling into the
	 * ordinary fight; with the technique told to the network ({@link SchoolNet#INTENT}) they have their own input, and
	 * the other half of the draws stays as it was.
	 */
	public boolean stratified;
	static final int SHADOW_EVERY = 3;
	private long shadowSeen;
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
		this(capacity, false);
	}

	public TeacherReplay(int capacity, boolean shadow) {
		this.capacity = capacity;
		this.shadow = shadow;
		peri = new byte[capacity][ObsSpec.PERI_SIZE];
		fovea = new byte[capacity][ObsSpec.FOVEA_SIZE];
		vec = new float[capacity][ObsSpec.VEC];
		mem = new float[capacity][SchoolObs.MEM];
		pred = new float[capacity][SchoolNet.SIDE];
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
			// Only where the network was told the technique the teacher was doing: else "told X, do Y" would be taught.
			if (!r.intentAgrees[i]) {
				continue;
			}
			int h = r.teacherHeads[i];
			int t = r.teacherTechnique[i];
			if (shadow) {
				if (r.teacherWeight[i] <= 0f || (shadowSeen++ % SHADOW_EVERY) != 0) {
					continue;
				}
				// The installed model teaches the camera; in the overlay, every key where no technique teacher speaks.
				boolean between = h == 0 || t == Technique.BASE.ordinal();
				h = (installedBody && between ? ActionSpace.ALL_HEADS : 1 << ActionSpace.YAW | 1 << ActionSpace.PITCH) & ~h;
				t = Technique.BASE.ordinal();
				if (h == 0) {
					continue;
				}
			}
			// Not a key the network could not press there (a swing before its sword was back).
			h &= r.headMask[i];
			// The ordinary fight's no-jump is on most steps: one in six of them is kept, so it does not crowd the techniques out.
			if (!shadow && (h == 0 || t < 0 || t == Technique.BASE.ordinal() && (baseSeen++ % 6) != 0)) {
				continue;
			}
			int j = next;
			next = (next + 1) % capacity;
			size = Math.min(capacity, size + 1);
			System.arraycopy(r.peri[i], 0, peri[j], 0, ObsSpec.PERI_SIZE);
			System.arraycopy(r.fovea[i], 0, fovea[j], 0, ObsSpec.FOVEA_SIZE);
			System.arraycopy(r.vec[i], 0, vec[j], 0, ObsSpec.VEC);
			System.arraycopy(r.mem[i], 0, mem[j], 0, SchoolObs.MEM);
			System.arraycopy(r.pred[i], 0, pred[j], 0, SchoolNet.SIDE);
			System.arraycopy(r.teacher[i], 0, target[j], 0, ActionSpace.TOTAL_LOGITS);
			for (int k = 0; k < ActionSpace.HEADS; k++) {
				// Its own key where it is one the teacher asks for (one of several alike), else the teacher's.
				boolean own = (h & (1 << k)) == 0 || r.teacher[i][ActionSpace.OFFSETS[k] + r.action[i][k]] >= Teacher.ANY;
				cond[j][k] = own ? r.action[i][k] : (byte) argmax(r.teacher[i], k);
			}
			heads[j] = h;
			technique[j] = t;
		}
	}

	/** The body's keys (not the camera): what {@link #addSelf} keeps of its own steps. */
	static final int BODY_HEADS = ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.JUMP, ActionSpace.ATTACK);
	/** A step of its own is kept by {@link #addSelf} when its advantage is this many deviations above the rollout's mean. */
	public static final float SELF_MIN = 1f;

	/**
	 * Self-imitation (Oh et al. 2018, "Self-Imitation Learning"): its own steps whose fight went clearly better than the
	 * critic expected (advantage at least {@link #SELF_MIN} deviations up), with its own body keys as the target, kept
	 * under {@link Technique#BASE}'s slot. The user, 25 Oct 09:40: when to sprint-hit, when not and when to crit should be
	 * learned "by few episodes, maybe 10-20": a good outcome is learned from again after every update, not once in a batch
	 * of 4 000 steps.
	 */
	public void addSelf(SchoolRollout r) {
		if (r.size == 0) {
			return;
		}
		double mean = 0.0;
		for (int i = 0; i < r.size; i++) {
			mean += r.advantage[i];
		}
		mean /= r.size;
		double var = 0.0;
		for (int i = 0; i < r.size; i++) {
			double d = r.advantage[i] - mean;
			var += d * d;
		}
		double std = Math.max(1e-6, Math.sqrt(var / r.size));
		for (int i = 0; i < r.size; i++) {
			if ((r.advantage[i] - mean) / std < SELF_MIN || r.policyWeight[i] <= 0f) {
				continue;
			}
			int h = BODY_HEADS & r.headMask[i];
			if (h == 0) {
				continue;
			}
			int j = next;
			next = (next + 1) % capacity;
			size = Math.min(capacity, size + 1);
			System.arraycopy(r.peri[i], 0, peri[j], 0, ObsSpec.PERI_SIZE);
			System.arraycopy(r.fovea[i], 0, fovea[j], 0, ObsSpec.FOVEA_SIZE);
			System.arraycopy(r.vec[i], 0, vec[j], 0, ObsSpec.VEC);
			System.arraycopy(r.mem[i], 0, mem[j], 0, SchoolObs.MEM);
			System.arraycopy(r.pred[i], 0, pred[j], 0, SchoolNet.SIDE);
			java.util.Arrays.fill(target[j], 0f);
			for (int k = 0; k < ActionSpace.HEADS; k++) {
				target[j][ActionSpace.OFFSETS[k] + r.action[i][k]] = 1f;
				cond[j][k] = r.action[i][k];
			}
			heads[j] = h;
			technique[j] = Technique.BASE.ordinal();
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
		float[] aimed = new float[ActionSpace.TOTAL_LOGITS];
		int[][] byTechnique = null;
		int[] kinds = null;
		int kindCount = 0;
		if (stratified) {
			int[] count = new int[Technique.COUNT];
			for (int i = 0; i < size; i++) {
				int t = technique[i];
				if (t >= 0 && t < Technique.COUNT) {
					count[t]++;
				}
			}
			byTechnique = new int[Technique.COUNT][];
			kinds = new int[Technique.COUNT];
			for (int t = 0; t < Technique.COUNT; t++) {
				byTechnique[t] = new int[count[t]];
				if (count[t] > 0 && pulls[t] > 0f) {
					kinds[kindCount++] = t;
				}
			}
			int[] at = new int[Technique.COUNT];
			for (int i = 0; i < size; i++) {
				int t = technique[i];
				if (t >= 0 && t < Technique.COUNT) {
					byTechnique[t][at[t]++] = i;
				}
			}
		}
		double ce = 0;
		int headsSeen = 0;
		int agreed = 0;
		for (int b = 0; b < batches; b++) {
			ParamSet.zero(ws.grads);
			int used = 0;
			for (int n = 0; n < batch; n++) {
				int i;
				if (kindCount > 0 && (n & 1) == 1) {
					int[] of = byTechnique[kinds[rng.nextInt(kindCount)]];
					i = of[rng.nextInt(of.length)];
				} else {
					i = rng.nextInt(size);
				}
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
					float wh = shadow ? w : w * Teacher.pressWeight(target[i], h, vec[i], t);
					Policy.softmax(ws.logits, off, size, probs);
					Teacher.aim(target[i], probs, off, size, aimed);
					int best = 0;
					for (int k = 0; k < size; k++) {
						float p = probs[off + k];
						float q = aimed[off + k];
						ws.dLogits[off + k] = wh * (p - q);
						if (q > 0f) {
							ce -= q * Math.log(Math.max(p, 1e-9f));
						}
						if (p > probs[off + best]) {
							best = k;
						}
					}
					headsSeen++;
					agreed += target[i][off + best] >= Teacher.ANY ? 1 : 0;
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
