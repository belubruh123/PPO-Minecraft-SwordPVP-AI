package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;

import java.util.Arrays;
import java.util.Random;

/**
 * The opponent reader: what kind of opponent this is, read off the last three seconds of the fight. A
 * window of a few numbers a tick (how far off the opponent is, how it moves, its sword and the
 * fighter's own, the hits either way, and how far the opponent's last hit sent it) goes through a small
 * network into sixteen numbers, and from those to a guess at the kind of opponent. The guess is
 * learned the plain supervised way: in training the kind of every fight is known (what the critic is
 * told, {@code Trainer.episodeTask}), and the reader is taught to name it. The sixteen numbers are what
 * the tactics model ({@link TacticNet}) and the acting network ({@link ActorNet}) are handed, both through
 * weights that start at zero; neither one's learning reaches back into the reader.
 *
 * <p>Why: the acting network sees one moment, and the three running averages of the opponent's style
 * ({@code ObsSpec.SC_STYLE_AWAY} and the two after it) are all it had of the seconds before; it had
 * learned to make next to no use of those. Against the jump-crit chaser, the whiffer and the 0.14.0
 * model it lost on 21 October what it won against the spacer and the timer: one way of fighting for
 * all of them. Telling them apart is the first step to fighting each its own way. From a review the
 * user passed on (21 October evening): an opponent encoder trained on the opponent's kind, its output
 * not trained by the policy's gradient (as in auxiliary-task agents such as UNREAL, Jaderberg et al.
 * ICLR 2017, and in policies that infer the opponent: He et al., DRON, ICML 2016), squashed with tanh so
 * that its scale cannot drift. A window instead of a recurrent network: the networks here are written
 * by hand, and a window needs no backpropagation through time.
 */
public final class OpponentReader {
	/** Numbers per sample; see {@link Window}. */
	public static final int CHANNELS = 10;
	/** Samples in the window, each over {@link #STRIDE} ticks: 3.2 seconds. */
	public static final int SAMPLES = 32;
	public static final int STRIDE = 2;
	public static final int INPUTS = CHANNELS * SAMPLES;
	/** The numbers handed on. */
	public static final int Z = 16;
	static final int HIDDEN = 64;
	/** The kinds of fight it names: the places in {@code Trainer.episodeTask}. */
	public static final int CLASSES = 23;
	/** Kinds with no one opponent behind them: a person, the outside bot, replays of lost fights, the drills. */
	private static final int[] UNLABELLED = {11, 15, 16, 17, 18, 19, 20};

	public final ParamSet params = new ParamSet();
	private final Dense in;
	private final Dense mid;
	private final Dense out;

	public OpponentReader(long seed) {
		Random rng = new Random(seed);
		in = new Dense(params, "reader.in", INPUTS, HIDDEN, rng, (float) Math.sqrt(2.0));
		mid = new Dense(params, "reader.mid", HIDDEN, Z, rng, 1f);
		out = new Dense(params, "reader.out", Z, CLASSES, rng, 1f);
	}

	/** The class a fight of this kind is taught as, or -1 where there is none to teach. */
	public static int label(int task) {
		if (task < 0 || task >= CLASSES) {
			return -1;
		}
		for (int u : UNLABELLED) {
			if (u == task) {
				return -1;
			}
		}
		return task;
	}

	public final class Workspace {
		final float[] h = new float[HIDDEN];
		final float[] z = new float[Z];
		final float[] logits = new float[CLASSES];
		public final float[] probs = new float[CLASSES];
		final float[] dLogits = new float[CLASSES];
		final float[] dZ = new float[Z];
		final float[] dH = new float[HIDDEN];
		final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	/** Reads a window ({@link Window#write}) into the sixteen numbers, each in [-1, 1]. Fills {@code ws.probs} with the guess. */
	public void read(Workspace ws, float[] window, float[] zOut) {
		forward(ws, window);
		System.arraycopy(ws.z, 0, zOut, 0, Z);
	}

	void forward(Workspace ws, float[] window) {
		in.forward(window, 0, ws.h, 0);
		for (int i = 0; i < HIDDEN; i++) {
			if (ws.h[i] < 0f) {
				ws.h[i] = 0f;
			}
		}
		mid.forward(ws.h, 0, ws.z, 0);
		for (int i = 0; i < Z; i++) {
			ws.z[i] = (float) Math.tanh(ws.z[i]);
		}
		out.forward(ws.z, 0, ws.logits, 0);
		float max = Float.NEGATIVE_INFINITY;
		for (float l : ws.logits) {
			max = Math.max(max, l);
		}
		double sum = 0;
		for (int k = 0; k < CLASSES; k++) {
			ws.probs[k] = (float) Math.exp(ws.logits[k] - max);
			sum += ws.probs[k];
		}
		for (int k = 0; k < CLASSES; k++) {
			ws.probs[k] /= (float) sum;
		}
	}

	/** Back-propagates {@code ws.dLogits}; must follow {@link #forward} on the same workspace and window. */
	void backward(Workspace ws, float[] window) {
		out.backward(ws.z, 0, ws.dLogits, 0, ws.dZ, 0, ws.grads);
		for (int i = 0; i < Z; i++) {
			ws.dZ[i] *= 1f - ws.z[i] * ws.z[i];
		}
		mid.backward(ws.h, 0, ws.dZ, 0, ws.dH, 0, ws.grads);
		for (int i = 0; i < HIDDEN; i++) {
			if (ws.h[i] <= 0f) {
				ws.dH[i] = 0f;
			}
		}
		in.backward(window, 0, ws.dH, 0, null, 0, ws.grads);
	}

	/** What one round of learning did, for the log. */
	public static final class Stats {
		/** Steps with a kind to name, and how many it named right before learning from them. */
		public int labelled;
		public int right;
		public final int[] perClass = new int[CLASSES];
		public final int[] rightPerClass = new int[CLASSES];
		public float loss;

		/** @param names the kinds' names, by class */
		public String line(String[] names) {
			StringBuilder b = new StringBuilder();
			b.append(String.format(java.util.Locale.ROOT, "named right %d%% of %d steps before learning from them (loss %.3f)",
					labelled == 0 ? 0 : Math.round(100f * right / labelled), labelled, loss));
			for (int k = 0; k < CLASSES; k++) {
				if (perClass[k] > 0) {
					String name = k < names.length ? names[k] : "kind " + k;
					b.append(String.format(java.util.Locale.ROOT, ", %s %d%%", name, Math.round(100f * rightPerClass[k] / perClass[k])));
				}
			}
			return b.toString();
		}
	}

	/**
	 * What it has been taught from: a sample of the labelled steps of the last updates, every
	 * {@link #KEEP_EVERY}th step, the oldest written over. One update's rollout is one to three fights
	 * against one to three kinds of opponent; taught from that alone it learned to name whatever it had
	 * just seen, and named the next update's opponents right on none of their steps (21 October, 23:20:
	 * 0% three updates running, the loss rising 2.2, 2.9, 3.6). Drawn from many updates at once, the
	 * kinds are mixed as they are over a whole session (experience replay; Lin 1992, Mnih et al. 2015).
	 */
	public static final class Memory {
		final float[][] window;
		final int[] label;
		int size;
		private int next;

		public Memory(int capacity) {
			window = new float[Math.max(1, capacity)][INPUTS];
			label = new int[window.length];
		}

		void add(float[] w, int c) {
			System.arraycopy(w, 0, window[next], 0, INPUTS);
			label[next] = c;
			next = (next + 1) % window.length;
			size = Math.min(size + 1, window.length);
		}
	}

	/** Steps of a rollout kept in the {@link Memory}: one in this many (the steps next to it say about the same). */
	static final int KEEP_EVERY = 4;

	/**
	 * Counts how often it names the labelled steps of the rollout right (before learning anything from
	 * them), keeps a share of them in {@code memory}, and learns from {@code batches} minibatches drawn
	 * at random from the memory (cross-entropy, its own optimiser).
	 */
	public Stats learn(Rollout r, Memory memory, float lr, int batches, int batch, float maxGradNorm, Random rng) {
		Stats s = new Stats();
		Workspace ws = newWorkspace();
		int n = 0;
		for (int i = 0; i < r.size; i++) {
			int c = label(r.task[i]);
			if (c < 0) {
				continue;
			}
			forward(ws, r.oppWindow[i]);
			int best = 0;
			for (int k = 1; k < CLASSES; k++) {
				if (ws.probs[k] > ws.probs[best]) {
					best = k;
				}
			}
			s.perClass[c]++;
			if (best == c) {
				s.right++;
				s.rightPerClass[c]++;
			}
			if (n++ % KEEP_EVERY == 0) {
				memory.add(r.oppWindow[i], c);
			}
		}
		s.labelled = n;
		if (memory.size == 0) {
			return s;
		}
		double loss = 0;
		int seen = 0;
		for (int b = 0; b < batches; b++) {
			ParamSet.zero(ws.grads);
			for (int k = 0; k < batch; k++) {
				int i = rng.nextInt(memory.size);
				int c = memory.label[i];
				forward(ws, memory.window[i]);
				loss -= Math.log(Math.max(1e-8f, ws.probs[c]));
				seen++;
				for (int j = 0; j < CLASSES; j++) {
					ws.dLogits[j] = ws.probs[j] - (j == c ? 1f : 0f);
				}
				backward(ws, memory.window[i]);
			}
			Adam.step(params, ws.grads, lr, 1f / batch, maxGradNorm);
		}
		s.loss = (float) (loss / Math.max(1, seen));
		return s;
	}

	/**
	 * The window one fighter keeps of its fight, a sample every {@link #STRIDE} ticks, oldest first. Per
	 * sample: the distance to the opponent (an eighth of the blocks, the last one known while it is out of
	 * sight), whether it is in sight, how fast it is moving away and up, its sword's charge and the
	 * fighter's own, a hit landed and a hit taken in the sample (kept, not averaged away), how fast the
	 * opponent was moving away four ticks after the fighter's last hit landed (held until the next one:
	 * near nothing only against an opponent that takes no knockback, which shows on the first hit), and
	 * how long the fight has been going (up to the window's length). Started afresh with every fight.
	 */
	public static final class Window {
		static final int KNOCKBACK_TICK = 4;
		private final float[] ring = new float[INPUTS];
		private final float[] sum = new float[CHANNELS];
		private int at;
		private int ticksIn;
		private int ticks;
		private float lastDistance = 0.5f;
		private int sinceDealt = 1000;
		private float knockback;

		public void reset() {
			Arrays.fill(ring, 0f);
			Arrays.fill(sum, 0f);
			at = 0;
			ticksIn = 0;
			ticks = 0;
			lastDistance = 0.5f;
			sinceDealt = 1000;
			knockback = 0f;
		}

		/** One tick of the fight, as the acting network is shown it. */
		public void push(float[] vec) {
			float fresh = 1f - 0.5f / BodyState.EVENT_FADE_TICKS;
			boolean seen = vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
			if (seen) {
				lastDistance = Math.min(JumpIn.distance(vec), 16f) / 8f;
			}
			float away = seen ? (scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE : 0f;
			float up = seen ? (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE : 0f;
			boolean dealt = scalar(vec, ObsSpec.SC_SINCE_HIT_DEALT) >= fresh;
			boolean taken = scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fresh;
			if (dealt) {
				sinceDealt = 0;
			} else if (sinceDealt < 1000) {
				sinceDealt++;
			}
			if (sinceDealt == KNOCKBACK_TICK && seen) {
				knockback = away;
			}
			sum[0] += lastDistance;
			sum[1] += seen ? 1f : 0f;
			sum[2] += 4f * away;
			sum[3] += 4f * up;
			sum[4] += scalar(vec, ObsSpec.SC_OPP_CHARGE);
			sum[5] += scalar(vec, ObsSpec.SC_ATTACK_CHARGE);
			sum[6] = Math.max(sum[6], dealt ? STRIDE : 0f);
			sum[7] = Math.max(sum[7], taken ? STRIDE : 0f);
			sum[8] += 4f * knockback;
			sum[9] += Math.min(1f, ticks / (float) (SAMPLES * STRIDE));
			ticks++;
			if (++ticksIn == STRIDE) {
				int base = at * CHANNELS;
				for (int c = 0; c < CHANNELS; c++) {
					ring[base + c] = sum[c] / STRIDE;
				}
				Arrays.fill(sum, 0f);
				ticksIn = 0;
				at = (at + 1) % SAMPLES;
			}
		}

		/** The window, oldest sample first; samples from before the fight began are zero. */
		public void write(float[] into) {
			int k = 0;
			for (int s = 0; s < SAMPLES; s++) {
				int base = ((at + s) % SAMPLES) * CHANNELS;
				System.arraycopy(ring, base, into, k, CHANNELS);
				k += CHANNELS;
			}
		}

		private static float scalar(float[] vec, int index) {
			return vec[ObsSpec.VEC_SCALARS + index];
		}
	}
}
