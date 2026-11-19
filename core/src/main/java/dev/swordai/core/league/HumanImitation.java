package dev.swordai.core.league;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.Policy;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.SyntheticEye;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Learns directly from the recorded real duels in {@code run/swordai/real-fights/*.txt.gz}
 * (Follow-up #2):
 * <ol>
 *   <li>Uses {@link InverseDynamics} to infer the human opponent's body keys on every tick
 *       (and head-angle deltas for camera bins).</li>
 *   <li><b>Main use — League opponents:</b> behaviour-clones each human who beat SwordAI into a
 *       compact {@link HumanPolicy} network so SwordAI practises directly against the people it
 *       loses to (driven on {@code ReplicaBody} in the arena).</li>
 *   <li><b>Second use — Outcome-filtered demonstrations:</b> extracts only moments where the
 *       human's play won the exchange (more hits dealt than taken in the next 20 ticks, or a
 *       crit/combo/KO), renders synthetic vision via {@link SyntheticEye}, and serves them as
 *       fading demonstrations to {@link LeaguePpo}.</li>
 * </ol>
 */
public final class HumanImitation {
	/** Compact ego-centric fight state readable both from RoundRecorder logs and live ReplicaBody. */
	public static final int STATE_DIM = 20;
	private static final int MIN_TICKS_PER_PLAYER = 80;
	private static final int MAX_DEMOS = 2048;

	/** One parsed tick from a recorded real duel (from SwordAI's log). */
	public static final class RoundTick {
		public float dist, mySword, oppSword, myHp, oppHp;
		public boolean myGround, oppGround, oppSprint, oppSwing;
		public float oppUp, oppToward, oppSideS, myToward, oppFacing, oppPitch;
		public boolean aiDealt, aiTaken, aiCrit;
	}

	public static final class Round {
		public final String opponent;
		public final boolean humanWon;
		public final List<RoundTick> ticks = new ArrayList<>();

		public Round(String opponent, boolean humanWon) {
			this.opponent = opponent;
			this.humanWon = humanWon;
		}
	}

	/** Compact MLP policy mimicking one real human player on {@code ReplicaBody}. */
	public static final class HumanPolicy {
		private static final int HIDDEN = 96;
		public final String name;
		public final int trainingTicks;
		public final ParamSet params = new ParamSet();
		private final Dense l1;
		private final Dense l2;
		private final Dense out;
		private final float[] h1 = new float[HIDDEN];
		private final float[] h2 = new float[HIDDEN];
		private final float[] logits = new float[ActionSpace.TOTAL_LOGITS];
		private final float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		private final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];
		private final float[] dH2 = new float[HIDDEN];
		private final float[] dH1 = new float[HIDDEN];
		private final float[][] grads;

		public HumanPolicy(String name, int trainingTicks, long seed) {
			this.name = name;
			this.trainingTicks = trainingTicks;
			Random rng = new Random(seed);
			float relu = (float) Math.sqrt(2.0);
			l1 = new Dense(params, "hp.l1", STATE_DIM, HIDDEN, rng, relu);
			l2 = new Dense(params, "hp.l2", HIDDEN, HIDDEN, rng, relu);
			out = new Dense(params, "hp.out", HIDDEN, ActionSpace.TOTAL_LOGITS, rng, 0.01f);
			grads = params.newGrads();
		}

		/** Samples all 7 heads from the cloned human policy given {@code state[STATE_DIM]}. */
		public synchronized void act(float[] state, float temperature, Random rng, int[] actionOut) {
			forward(state);
			Policy.sample(logits, ActionSpace.ALL_HEADS, false, temperature, rng, probs, actionOut);
		}

		synchronized float train(List<float[]> xs, List<byte[]> ys, int epochs, int batchSize, float lr, Random rng) {
			int n = xs.size();
			if (n == 0) {
				return 0f;
			}
			int[] order = new int[n];
			for (int i = 0; i < n; i++) {
				order[i] = i;
			}
			double sumCe = 0;
			long count = 0;
			for (int ep = 0; ep < epochs; ep++) {
				for (int i = n - 1; i > 0; i--) {
					int j = rng.nextInt(i + 1);
					int t = order[i];
					order[i] = order[j];
					order[j] = t;
				}
				for (int b = 0; b < n; b += batchSize) {
					int end = Math.min(n, b + batchSize);
					ParamSet.zeroGrads(grads);
					for (int k = b; k < end; k++) {
						int idx = order[k];
						float[] x = xs.get(idx);
						byte[] y = ys.get(idx);
						forward(x);
						Arrays.fill(dLogits, 0f);
						for (int h = 0; h < ActionSpace.HEADS; h++) {
							int off = ActionSpace.OFFSETS[h];
							int sz = ActionSpace.SIZES[h];
							Policy.softmax(logits, off, sz, probs);
							int a = y[h];
							sumCe -= Math.log(Math.max(probs[off + a], 1e-12f));
							for (int i = 0; i < sz; i++) {
								dLogits[off + i] = probs[off + i] - (i == a ? 1f : 0f);
							}
							count++;
						}
						backward(x);
					}
					Adam.step(params, grads, lr, 1f / (end - b), 1.0f);
				}
			}
			return count > 0 ? (float) (sumCe / count) : 0f;
		}

		private void forward(float[] x) {
			l1.forward(x, 0, h1, 0);
			relu(h1);
			l2.forward(h1, 0, h2, 0);
			relu(h2);
			out.forward(h2, 0, logits, 0);
		}

		private void backward(float[] x) {
			out.backward(h2, 0, dLogits, 0, dH2, 0, grads);
			gate(dH2, h2);
			l2.backward(h1, 0, dH2, 0, dH1, 0, grads);
			gate(dH1, h1);
			l1.backward(x, 0, dH1, 0, null, 0, grads);
		}

		/** Packs the live arena state of a bot fighting {@code opp} into {@code STATE_DIM} floats. */
		public static void packLiveState(float dist, float ownCharge, float oppCharge,
				boolean ownGround, boolean oppGround, boolean ownSprint,
				float ownFwd, float ownRight, float ownUp,
				float oppTowardMe, float oppUp, float ownHpFrac, float oppHpFrac,
				int sinceDealt, int sinceTaken, int ownCombo, int oppCombo,
				float yawErrDeg, float pitchDeg, float prevDist, float[] out) {
			out[0] = Math.min(2f, dist / 8f);
			out[1] = ownCharge;
			out[2] = oppCharge;
			out[3] = ownGround ? 1f : 0f;
			out[4] = oppGround ? 1f : 0f;
			out[5] = ownSprint ? 1f : 0f;
			out[6] = ownFwd * 5f;
			out[7] = ownRight * 5f;
			out[8] = ownUp * 5f;
			out[9] = oppTowardMe * 5f;
			out[10] = oppUp * 5f;
			out[11] = ownHpFrac;
			out[12] = oppHpFrac;
			out[13] = Math.max(0f, 1f - sinceDealt / 20f);
			out[14] = Math.max(0f, 1f - sinceTaken / 20f);
			out[15] = Math.min(5, ownCombo) / 5f;
			out[16] = Math.min(5, oppCombo) / 5f;
			out[17] = Math.max(-1f, Math.min(1f, yawErrDeg / 90f));
			out[18] = Math.max(-1f, Math.min(1f, pitchDeg / 90f));
			out[19] = Math.min(2f, prevDist / 8f);
		}
	}

	/** Outcome-filtered winning human demonstration ready for {@link LeaguePpo#supervisedStep}. */
	public static final class WinningDemos {
		public final byte[][] peri;
		public final byte[][] fovea;
		public final float[][] vec;
		public final float[][] hist;
		public final float[][] seen;
		public final byte[][] action;
		public final int size;

		WinningDemos(int n) {
			this.size = n;
			this.peri = new byte[n][ObsSpec.PERI_SIZE];
			this.fovea = new byte[n][ObsSpec.FOVEA_SIZE];
			this.vec = new float[n][ObsSpec.VEC];
			this.hist = new float[n][ObsMemory.HIST];
			this.seen = new float[n][ObsMemory.SEEN];
			this.action = new byte[n][ActionSpace.HEADS];
		}
	}

	private final List<Round> rounds = new ArrayList<>();
	private final List<HumanPolicy> opponents = new ArrayList<>();
	private WinningDemos winningDemos = new WinningDemos(0);

	public int roundCount() {
		return rounds.size();
	}

	public int lostRoundCount() {
		int c = 0;
		for (Round r : rounds) {
			if (r.humanWon) {
				c++;
			}
		}
		return c;
	}

	public List<HumanPolicy> opponents() {
		return opponents;
	}

	public WinningDemos winningDemos() {
		return winningDemos;
	}

	/** Loads all {@code *.txt.gz} real fight logs from {@code dir}. */
	public int loadDirectory(Path dir) {
		rounds.clear();
		if (dir == null || !Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> p.getFileName().toString().endsWith(".txt.gz")).sorted().forEach(p -> {
				try {
					Round r = parseRound(p);
					if (r != null && r.ticks.size() >= 15) {
						rounds.add(r);
					}
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
		return rounds.size();
	}

	/**
	 * Labels all loaded real rounds using {@code idm}, trains a BC {@link HumanPolicy} for every
	 * opponent who beat SwordAI (plus an aggregate real-human clone), and extracts outcome-filtered
	 * winning human demonstrations rendered through {@link SyntheticEye}.
	 */
	public void buildFromRealRounds(InverseDynamics idm, long seed) {
		opponents.clear();
		if (rounds.isEmpty()) {
			winningDemos = new WinningDemos(0);
			return;
		}
		Random rng = new Random(seed);
		Map<String, List<float[]>> byPlayerX = new HashMap<>();
		Map<String, List<byte[]>> byPlayerY = new HashMap<>();
		List<float[]> allLostX = new ArrayList<>();
		List<byte[]> allLostY = new ArrayList<>();

		// Collect labeled ticks and outcome-filtered winning human moments.
		List<Round> demoRounds = new ArrayList<>();
		List<Integer> demoTickIdx = new ArrayList<>();
		List<byte[]> demoActions = new ArrayList<>();

		float[] tPrev = new float[InverseDynamics.TICK_DIM];
		float[] tCur = new float[InverseDynamics.TICK_DIM];
		float[] tNext = new float[InverseDynamics.TICK_DIM];
		float[] winFeat = new float[InverseDynamics.IN_DIM];
		int[] pred = new int[ActionSpace.HEADS];

		for (Round r : rounds) {
			int n = r.ticks.size();
			if (n < 4) {
				continue;
			}
			int sinceDealt = 1000;
			int sinceTaken = 1000;
			int ownCombo = 0;
			int oppCombo = 0;
			for (int t = 1; t < n - 1; t++) {
				RoundTick p0 = r.ticks.get(t - 1);
				RoundTick p1 = r.ticks.get(t);
				RoundTick p2 = r.ticks.get(t + 1);
				// From the human's perspective: AI's TAKEN = human dealt; AI's DEALT = human took!
				if (p1.aiTaken) {
					sinceDealt = 0;
					ownCombo++;
					oppCombo = 0;
				} else {
					sinceDealt = Math.min(1000, sinceDealt + 1);
				}
				if (p1.aiDealt) {
					sinceTaken = 0;
					oppCombo++;
					ownCombo = 0;
				} else {
					sinceTaken = Math.min(1000, sinceTaken + 1);
				}

				packHumanKinematic(p0, tPrev);
				packHumanKinematic(p1, tCur);
				packHumanKinematic(p2, tNext);
				InverseDynamics.packWindow(tPrev, tCur, tNext, p1.aiDealt || p0.aiDealt, winFeat);
				idm.predict(winFeat, pred);

				// Camera bins directly from human's head movement.
				float dYaw = wrapDeg(p2.oppFacing - p1.oppFacing);
				float dPitch = p2.oppPitch - p1.oppPitch;
				pred[ActionSpace.YAW] = InverseDynamics.nearestBin(dYaw, ActionSpace.YAW_BINS);
				pred[ActionSpace.PITCH] = InverseDynamics.nearestBin(dPitch, ActionSpace.PITCH_BINS);

				byte[] actBytes = new byte[ActionSpace.HEADS];
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					actBytes[h] = (byte) pred[h];
				}
				float[] state = new float[STATE_DIM];
				double rad = Math.toRadians(p1.oppFacing);
				float egoFwd = (float) (p1.oppToward * Math.cos(rad) + p1.oppSideS * Math.sin(rad));
				float egoRight = (float) (-p1.oppToward * Math.sin(rad) + p1.oppSideS * Math.cos(rad));
				HumanPolicy.packLiveState(p1.dist, p1.oppSword, p1.mySword,
						p1.oppGround, p1.myGround, p1.oppSprint,
						egoFwd, egoRight, p1.oppUp,
						p1.myToward, 0f, p1.oppHp / 20f, p1.myHp / 20f,
						sinceDealt, sinceTaken, ownCombo, oppCombo,
						-p1.oppFacing, p1.oppPitch, p0.dist, state);

				if (r.humanWon) {
					byPlayerX.computeIfAbsent(r.opponent, k -> new ArrayList<>()).add(state);
					byPlayerY.computeIfAbsent(r.opponent, k -> new ArrayList<>()).add(actBytes);
					allLostX.add(state);
					allLostY.add(actBytes);
				}

				// Outcome filter: keep tick iff over the next 20 ticks the human strictly wins the exchange.
				if (demoActions.size() < MAX_DEMOS && humanWinsExchange(r.ticks, t)) {
					demoRounds.add(r);
					demoTickIdx.add(t);
					demoActions.add(actBytes);
				}
			}
		}

		// Train per-opponent BC policies for humans who beat SwordAI.
		for (Map.Entry<String, List<float[]>> e : byPlayerX.entrySet()) {
			List<float[]> xs = e.getValue();
			if (xs.size() >= MIN_TICKS_PER_PLAYER) {
				HumanPolicy hp = new HumanPolicy(e.getKey(), xs.size(), rng.nextLong());
				hp.train(xs, byPlayerY.get(e.getKey()), 12, 64, 1e-3f, rng);
				opponents.add(hp);
			}
		}
		// Always include an aggregate clone of all losing real rounds (handles opponents with few rounds).
		if (!allLostX.isEmpty()) {
			HumanPolicy agg = new HumanPolicy("real-humans", allLostX.size(), rng.nextLong());
			agg.train(allLostX, allLostY, 10, 64, 1e-3f, rng);
			opponents.add(agg);
		}

		// Render outcome-filtered winning human moments through SyntheticEye so SwordNet can learn from them.
		winningDemos = renderWinningDemos(demoRounds, demoTickIdx, demoActions);
	}

	private static boolean humanWinsExchange(List<RoundTick> ticks, int t) {
		int end = Math.min(ticks.size(), t + 20);
		int humanHits = 0;
		int aiHits = 0;
		boolean hitSoon = false;
		for (int k = t; k < end; k++) {
			if (ticks.get(k).aiTaken) {
				humanHits++;
				if (k <= t + 10) {
					hitSoon = true;
				}
			}
			if (ticks.get(k).aiDealt) {
				aiHits++;
			}
		}
		return hitSoon && humanHits > aiHits;
	}

	private static WinningDemos renderWinningDemos(List<Round> demoRounds, List<Integer> demoTickIdx, List<byte[]> demoActions) {
		int n = demoActions.size();
		WinningDemos out = new WinningDemos(n);
		if (n == 0) {
			return out;
		}
		SyntheticEye eye = new SyntheticEye();
		MaskFrame cur = new MaskFrame();
		MaskFrame prev = new MaskFrame();
		Obs obs = new Obs();
		for (int i = 0; i < n; i++) {
			Round r = demoRounds.get(i);
			int t = demoTickIdx.get(i);
			RoundTick p0 = r.ticks.get(Math.max(0, t - 1));
			RoundTick p1 = r.ticks.get(t);
			// Place human at origin looking along +Z (yaw=0) with offset oppFacing, AI at distance dist along +Z.
			double d0 = Math.max(0.6, p0.dist);
			double d1 = Math.max(0.6, p1.dist);
			eye.look(0, 1.62, 0, p0.oppFacing, p0.oppPitch, 0, 0, d0, 180f, 180f, 0f, prev);
			eye.look(0, 1.62, 0, p1.oppFacing, p1.oppPitch, 0, 0, d1, 180f, 180f, 0f, cur);
			Arrays.fill(obs.vec, 0f);
			ObsBuilder.writeVision(obs, cur, prev.stats);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_ON_GROUND, p1.oppGround ? 1f : 0f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_VEL_FORWARD, p1.oppToward * 5f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_VEL_RIGHT, p1.oppSideS * 5f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_VEL_UP, p1.oppUp * 5f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_PITCH, p1.oppPitch / 90f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_ATTACK_CHARGE, p1.oppSword);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_HEALTH, p1.oppHp / 20f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_SPRINTING, p1.oppSprint ? 1f : 0f);
			ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_OPP_CHARGE, p1.mySword);
			ObsBuilder.writeHeadMask(obs.vec, ActionSpace.ALL_HEADS);

			System.arraycopy(obs.peri, 0, out.peri[i], 0, ObsSpec.PERI_SIZE);
			System.arraycopy(obs.fovea, 0, out.fovea[i], 0, ObsSpec.FOVEA_SIZE);
			System.arraycopy(obs.vec, 0, out.vec[i], 0, ObsSpec.VEC);
			System.arraycopy(demoActions.get(i), 0, out.action[i], 0, ActionSpace.HEADS);
		}
		return out;
	}

	private static void packHumanKinematic(RoundTick tk, float[] dst) {
		InverseDynamics.packTick(tk.dist, tk.oppToward, tk.oppSideS, tk.oppUp,
				tk.oppGround, tk.oppSprint, tk.oppSword, tk.oppSwing, tk.oppFacing, tk.oppPitch, dst, 0);
	}

	/** Parses one gzipped {@code RoundRecorder} file. */
	public static Round parseRound(Path file) throws IOException {
		String fn = file.getFileName().toString();
		boolean humanWon = fn.contains("-lost.txt.gz");
		String opponent = "unknown";
		Round round = null;
		boolean inCsv = false;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (round == null && (line.startsWith("WON vs ") || line.startsWith("LOST vs ") || line.startsWith("OPEN vs "))) {
					humanWon = line.startsWith("LOST vs ");
					int vs = line.indexOf(" vs ") + 4;
					int end = line.indexOf(" (", vs);
					if (end < 0) {
						end = line.indexOf(':', vs);
					}
					opponent = end > vs ? line.substring(vs, end).trim() : "unknown";
					round = new Round(opponent, humanWon);
				} else if (line.startsWith("tick,dist,mySword,")) {
					if (round == null) {
						round = new Round(opponent, humanWon);
					}
					inCsv = true;
				} else if (inCsv && !line.isEmpty()) {
					String[] p = line.split(",", -1);
					if (p.length < 18) {
						continue;
					}
					RoundTick tk = new RoundTick();
					tk.dist = parseFloat(p[1]);
					tk.mySword = parseFloat(p[2]);
					tk.oppSword = parseFloat(p[3]);
					tk.myHp = parseFloat(p[4]);
					tk.oppHp = parseFloat(p[5]);
					tk.myGround = p[6].startsWith("g");
					tk.oppGround = p[7].startsWith("g");
					tk.oppSprint = p[7].contains("s");
					tk.oppUp = parseFloat(p[8]);
					tk.oppToward = parseFloat(p[9]);
					tk.myToward = parseFloat(p[11]);
					tk.oppSideS = parseFloat(p[14]);
					tk.oppFacing = parseFloat(p[15]);
					tk.oppPitch = parseFloat(p[16]);
					tk.oppSwing = "1".equals(p[17]);
					String ev = p.length > 18 ? p[18] : "";
					tk.aiDealt = ev.contains("DEALT");
					tk.aiTaken = ev.contains("TAKEN");
					tk.aiCrit = ev.contains("swing:crit");
					round.ticks.add(tk);
				}
			}
		}
		return round;
	}

	private static float parseFloat(String s) {
		try {
			return Float.parseFloat(s);
		} catch (NumberFormatException e) {
			return 0f;
		}
	}

	private static float wrapDeg(float d) {
		while (d > 180f) {
			d -= 360f;
		}
		while (d < -180f) {
			d += 360f;
		}
		return d;
	}

	private static void relu(float[] a) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] < 0f) {
				a[i] = 0f;
			}
		}
	}

	private static void gate(float[] g, float[] a) {
		for (int i = 0; i < g.length; i++) {
			if (a[i] <= 0f) {
				g[i] = 0f;
			}
		}
	}
}
