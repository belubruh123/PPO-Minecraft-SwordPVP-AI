import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'

# ---- Obs
e = Ed(R + 'rl/Obs.java')
e.rep("""	public int tactic = -1;
""", """	public int tactic = -1;
	/** The opponent reader's window of the fight ({@link OpponentReader.Window}) and what it read from it, for the acting network. */
	public final float[] oppWindow = new float[OpponentReader.INPUTS];
	public final float[] opp = new float[OpponentReader.Z];
""")
e.rep("""		tactic = o.tactic;
""", """		tactic = o.tactic;
		System.arraycopy(o.oppWindow, 0, oppWindow, 0, oppWindow.length);
		System.arraycopy(o.opp, 0, opp, 0, opp.length);
""")
e.save()

# ---- Rollout
e = Ed(R + 'rl/Rollout.java')
e.rep("""	public final float[] tacticLogp;
""", """	public final float[] tacticLogp;
	/** The opponent reader's window and reading at each step; see {@link OpponentReader}. */
	public final float[][] oppWindow;
	public final float[][] opp;
""")
e.rep("""		tacticLogp = new float[capacity];
""", """		tacticLogp = new float[capacity];
		oppWindow = new float[capacity][OpponentReader.INPUTS];
		opp = new float[capacity][OpponentReader.Z];
""")
e.rep("""		tacticStart[i] = false;
		tacticLogp[i] = 0f;
""", """		tacticStart[i] = false;
		tacticLogp[i] = 0f;
		System.arraycopy(obs.oppWindow, 0, oppWindow[i], 0, OpponentReader.INPUTS);
		System.arraycopy(obs.opp, 0, opp[i], 0, OpponentReader.Z);
""")
e.save()

# ---- SilBuffer
e = Ed(R + 'rl/SilBuffer.java')
e.rep("""	final int[] tactic;
""", """	final int[] tactic;
	final float[][] opp;
""")
e.rep("""		tactic = new int[this.capacity];
""", """		tactic = new int[this.capacity];
		opp = new float[this.capacity][OpponentReader.Z];
""")
e.rep("""		tactic[j] = r.tactic[i];
""", """		tactic[j] = r.tactic[i];
		System.arraycopy(r.opp[i], 0, opp[j], 0, OpponentReader.Z);
""")
e.save()

# ---- ActorNet
e = Ed(R + 'rl/ActorNet.java')
e.rep("""	private final Dense tacticIn;
""", """	private final Dense tacticIn;
	/**
	 * The opponent reader's sixteen numbers ({@link OpponentReader}), into the trunk and, as a scale on
	 * the trunk's output (FiLM: Perez et al. AAAI 2018), just before the heads; null without the reader.
	 * Both start at zero.
	 */
	private final Dense oppIn;
	private final Dense oppFilm;
""")
e.rep("""	/** @param tactics how many tactics it can be told ({@link Tactic#COUNT}), 0 for none */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics) {
		Random rng = new Random(seed);""", """	/** @param tactics how many tactics it can be told ({@link Tactic#COUNT}), 0 for none */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics) {
		this(seed, usePeriphery, useFovea, vecWide, blockCount, blockWidth, tactics, 0);
	}

	/** @param opp how many numbers the opponent reader hands it ({@link OpponentReader#Z}), 0 for none */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics, int opp) {
		Random rng = new Random(seed);""")
e.rep("""		tacticIn = tactics > 0 ? new Dense(params, "tactic.in", tactics, TRUNK, rng, 0f) : null;
""", """		tacticIn = tactics > 0 ? new Dense(params, "tactic.in", tactics, TRUNK, rng, 0f) : null;
		// Added later the same evening: the opponent reader's numbers.
		oppIn = opp > 0 ? new Dense(params, "opp.in", opp, TRUNK, rng, 0f) : null;
		oppFilm = opp > 0 ? new Dense(params, "opp.film", opp, TRUNK, rng, 0f) : null;
""")
e.rep("""		/** The tactic of the last forward pass, -1 for none. */
		int tactic = -1;
""", """		/** The tactic of the last forward pass, -1 for none. */
		int tactic = -1;
		final float[] oppPre = new float[oppIn == null ? 0 : TRUNK];
		final float[] film = new float[oppFilm == null ? 0 : TRUNK];
		final float[] oppZ = new float[oppIn == null ? 0 : oppIn.in];
		/** Whether the last forward pass had the reader's numbers. */
		boolean oppUsed;
		/** What the heads read: the trunk after the blocks, scaled by the reader's numbers where there are any. */
		final float[] headIn = oppFilm == null ? top : new float[TRUNK];
		final float[] dHead = new float[oppFilm == null ? 0 : TRUNK];
""")
e.rep("""	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, int tactic) {
""", """	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, int tactic) {
		forward(ws, peri, fovea, vec, tactic, null);
	}

	/** @param opp the opponent reader's numbers ({@link OpponentReader}), null for none */
	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, int tactic, float[] opp) {
""")
e.rep("""			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.tacticPre[i];
			}
		}
		relu(ws.trunk);""", """			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.tacticPre[i];
			}
		}
		ws.oppUsed = oppIn != null && opp != null;
		if (ws.oppUsed) {
			System.arraycopy(opp, 0, ws.oppZ, 0, ws.oppZ.length);
			oppIn.forward(ws.oppZ, 0, ws.oppPre, 0);
			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.oppPre[i];
			}
		}
		relu(ws.trunk);""")
e.rep("""		heads.forward(ws.top, 0, ws.logits, 0);
""", """		if (oppFilm != null) {
			if (ws.oppUsed) {
				oppFilm.forward(ws.oppZ, 0, ws.film, 0);
				for (int i = 0; i < TRUNK; i++) {
					ws.headIn[i] = ws.top[i] * (1f + ws.film[i]);
				}
			} else {
				System.arraycopy(ws.top, 0, ws.headIn, 0, TRUNK);
			}
		}
		heads.forward(ws.headIn, 0, ws.logits, 0);
""")
e.rep("""		heads.backward(ws.top, 0, ws.dLogits, 0, ws.dTrunk, 0, ws.grads);
""", """		if (oppFilm == null) {
			heads.backward(ws.top, 0, ws.dLogits, 0, ws.dTrunk, 0, ws.grads);
		} else {
			heads.backward(ws.headIn, 0, ws.dLogits, 0, ws.dHead, 0, ws.grads);
			if (ws.oppUsed) {
				for (int i = 0; i < TRUNK; i++) {
					ws.dTrunk[i] = ws.dHead[i] * (1f + ws.film[i]);
					// The scale's own gradient, put where the reader's numbers go in.
					ws.dHead[i] *= ws.top[i];
				}
				oppFilm.backward(ws.oppZ, 0, ws.dHead, 0, null, 0, ws.grads);
			} else {
				System.arraycopy(ws.dHead, 0, ws.dTrunk, 0, TRUNK);
			}
		}
""")
e.rep("""		if (ws.tactic >= 0) {
			tacticIn.backward(ws.tacticHot, 0, ws.dTrunk, 0, null, 0, ws.grads);
		}""", """		if (ws.tactic >= 0) {
			tacticIn.backward(ws.tacticHot, 0, ws.dTrunk, 0, null, 0, ws.grads);
		}
		if (ws.oppUsed) {
			oppIn.backward(ws.oppZ, 0, ws.dTrunk, 0, null, 0, ws.grads);
		}""")
e.save()

# ---- TacticNet
e = Ed(R + 'rl/TacticNet.java')
e.rep("""	private final Dense style;

	public TacticNet(long seed) {
		Random rng = new Random(seed);
		hidden = new Dense(params, "tactic.hidden", ObsSpec.VEC, HIDDEN, rng, (float) Math.sqrt(2.0));
		out = new Dense(params, "tactic.out", HIDDEN, Tactic.COUNT, rng, 0.01f);
		style = new Dense(params, "tactic.style", STYLE, Tactic.COUNT, rng, 0f);
		out.bias.w[Tactic.COUNTER] = PRIOR_COUNTER;
		style.weight.w[1 * Tactic.COUNT + Tactic.SPACE] = PRIOR_SPACE_EDGE;
	}
""", """	private final Dense style;
	/** The opponent reader's numbers into the hidden units, starting at zero; null without the reader. */
	private final Dense opp;

	public TacticNet(long seed) {
		this(seed, false);
	}

	/** @param reads whether it is handed the opponent reader's numbers ({@link OpponentReader}) */
	public TacticNet(long seed, boolean reads) {
		Random rng = new Random(seed);
		hidden = new Dense(params, "tactic.hidden", ObsSpec.VEC, HIDDEN, rng, (float) Math.sqrt(2.0));
		out = new Dense(params, "tactic.out", HIDDEN, Tactic.COUNT, rng, 0.01f);
		style = new Dense(params, "tactic.style", STYLE, Tactic.COUNT, rng, 0f);
		out.bias.w[Tactic.COUNTER] = PRIOR_COUNTER;
		style.weight.w[1 * Tactic.COUNT + Tactic.SPACE] = PRIOR_SPACE_EDGE;
		// Last, so a model saved before it loads as it was.
		opp = reads ? new Dense(params, "tactic.opp", OpponentReader.Z, HIDDEN, rng, 0f) : null;
	}
""")
e.rep("""		final float[] styleOut = new float[Tactic.COUNT];
""", """		final float[] styleOut = new float[Tactic.COUNT];
		final float[] oppZ = new float[OpponentReader.Z];
		final float[] oppPre = new float[HIDDEN];
		boolean oppUsed;
""")
e.rep("""	/** Fills {@code ws.logits} and {@code ws.probs}. */
	public void forward(Workspace ws, float[] vec) {
		hidden.forward(vec, 0, ws.h, 0);
		for (int i = 0; i < HIDDEN; i++) {""", """	/** Fills {@code ws.logits} and {@code ws.probs}. */
	public void forward(Workspace ws, float[] vec) {
		forward(ws, vec, null);
	}

	/** @param oppIn the opponent reader's numbers, null for none */
	public void forward(Workspace ws, float[] vec, float[] oppIn) {
		hidden.forward(vec, 0, ws.h, 0);
		ws.oppUsed = opp != null && oppIn != null;
		if (ws.oppUsed) {
			System.arraycopy(oppIn, 0, ws.oppZ, 0, OpponentReader.Z);
			opp.forward(ws.oppZ, 0, ws.oppPre, 0);
			for (int i = 0; i < HIDDEN; i++) {
				ws.h[i] += ws.oppPre[i];
			}
		}
		for (int i = 0; i < HIDDEN; i++) {""")
e.rep("""		hidden.backward(vec, 0, ws.dH, 0, null, 0, ws.grads);
	}""", """		hidden.backward(vec, 0, ws.dH, 0, null, 0, ws.grads);
		if (ws.oppUsed) {
			opp.backward(ws.oppZ, 0, ws.dH, 0, null, 0, ws.grads);
		}
	}""")
e.rep("""	public int choose(Workspace ws, float[] vec, Random rng) {
		forward(ws, vec);""", """	public int choose(Workspace ws, float[] vec, Random rng) {
		return choose(ws, vec, null, rng);
	}

	/** @param oppIn the opponent reader's numbers, null for none */
	public int choose(Workspace ws, float[] vec, float[] oppIn, Random rng) {
		forward(ws, vec, oppIn);""")
e.rep("""				forward(ws, r.vec[i]);
				float logp""", """				forward(ws, r.vec[i], opp == null ? null : r.opp[i]);
				float logp""")
e.save()

# ---- Ppo
e = Ed(R + 'rl/Ppo.java')
e.rep("""				actor.forward(aw, r.peri[i], r.fovea[i], r.vec[i], r.tactic[i]);""", """				actor.forward(aw, r.peri[i], r.fovea[i], r.vec[i], r.tactic[i], r.opp[i]);""")
e.rep("""				a.forward(anchorWs, r.peri[i], r.fovea[i], r.vec[i], r.tactic[i]);""", """				a.forward(anchorWs, r.peri[i], r.fovea[i], r.vec[i], r.tactic[i], r.opp[i]);""")
e.rep("""				actor.forward(aw, b.peri[i], b.fovea[i], b.vec[i], b.tactic[i]);""", """				actor.forward(aw, b.peri[i], b.fovea[i], b.vec[i], b.tactic[i], b.opp[i]);""")
e.save()

# ---- Config
e = Ed(R + 'config/Config.java')
e.rep("""	public boolean tacticOnEvents = true;
""", """	public boolean tacticOnEvents = true;
	/**
	 * The opponent reader ({@code rl.OpponentReader}; only with {@code tacticModel}): a small network
	 * reads the last 3.2 seconds of the fight into sixteen numbers, taught to name the kind of opponent,
	 * and hands them to the tactics model and the acting network. A network trained with it must be
	 * played with it.
	 */
	public boolean opponentReader = false;
	/** The reader's learning rate (its own optimiser), and its passes over each rollout. */
	public float readerLr = 1e-3f;
	public int readerEpochs = 2;
""")
e.save()
