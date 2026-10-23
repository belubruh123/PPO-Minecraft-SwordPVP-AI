import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'

e = Ed(R + 'rl/OpponentReader.java')
start = e.s.index('	/**\n	 * Learns to name the kind of each labelled step of the rollout')
end = e.s.index('	/**\n	 * The window one fighter keeps of its fight')
e.s = e.s[:start] + '''	/**
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

''' + e.s[end:]
e.save()

e = Ed(R + 'config/Config.java')
e.rep("""	/** The reader's learning rate (its own optimiser), and its passes over each rollout. */
	public float readerLr = 1e-3f;
	public int readerEpochs = 2;
""", """	/** The reader's learning rate (its own optimiser). */
	public float readerLr = 1e-3f;
	/** Minibatches of 128 it learns from after each update, drawn from its memory of this many steps; see {@code rl.OpponentReader.Memory}. */
	public int readerBatches = 16;
	public int readerMemory = 20000;
""")
e.save()

e = Ed(R + 'rl/Learner.java')
e.rep("""	private volatile OpponentReader.Stats lastReaderStats;
""", """	private volatile OpponentReader.Stats lastReaderStats;
	/** What the reader is taught from, across updates; made on demand. Not saved: it fills again in a few minutes. */
	private OpponentReader.Memory readerMemory;
""")
e.rep("""			lastReaderStats = reader.learn(rollout, cfg.readerLr, Math.max(1, cfg.readerEpochs), 128, cfg.maxGradNorm, rng);""",
"""			if (readerMemory == null) {
				readerMemory = new OpponentReader.Memory(cfg.readerMemory);
			}
			lastReaderStats = reader.learn(rollout, readerMemory, cfg.readerLr, Math.max(1, cfg.readerBatches), 128, cfg.maxGradNorm, rng);""")
e.save()

e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/OpponentReaderTest.java')
e.rep("""	@Test
	void itLearnsToNameTheOpponent() {
		OpponentReader reader = new OpponentReader(2L);
		Random rng = new Random(3L);
		Obs o = new Obs();
		int n = 256;
		OpponentReader.Stats last = null;
		for (int update = 0; update < 12; update++) {
			Rollout r = new Rollout(n);
			for (int i = 0; i < n; i++) {
				int kind = i % 2 == 0 ? SPACER : NO_KNOCKBACK;""", """	@Test
	void itLearnsToNameTheOpponentFromUpdatesOfOneKindEach() {
		OpponentReader reader = new OpponentReader(2L);
		OpponentReader.Memory memory = new OpponentReader.Memory(4000);
		Random rng = new Random(3L);
		Obs o = new Obs();
		int n = 256;
		OpponentReader.Stats last = null;
		for (int update = 0; update < 24; update++) {
			Rollout r = new Rollout(n);
			// As in training: each update's fights are against one kind of opponent.
			int kind = update % 2 == 0 ? SPACER : NO_KNOCKBACK;
			for (int i = 0; i < n; i++) {""")
e.rep("""			last = reader.learn(r, 1e-3f, 2, 32, 0.5f, rng);""", """			last = reader.learn(r, memory, 1e-3f, 16, 32, 0.5f, rng);""")
e.save()
