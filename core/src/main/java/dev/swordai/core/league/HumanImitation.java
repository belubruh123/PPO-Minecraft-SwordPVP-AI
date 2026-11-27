package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Playstyle-clustered human imitation from recorded real duels: labels human keys via the
 * lagged 5-tick VPT {@link InverseDynamics} model and clusters strong human rounds by combat
 * kinematics (uppercut-spacer, crit-pressure, deflect-combo, all-humans) rather than usernames.
 */
public final class HumanImitation {
	public static final int STATE_DIM = 20;
	public static final String[] CLUSTERS = {"uppercut-spacer", "crit-pressure", "deflect-combo", "all-humans"};
	private static final int MIN_TICKS = 80, MAX_CLUSTER_SAMPLES = 16_384, MAX_STRONG_ROUNDS = 72;
	private static final float ATTACK_POS_WEIGHT = 2.5f, JUMP_POS_WEIGHT = 1.6f;

	public static final class RoundTick {
		public float dist, mySword, oppSword, myHp, oppHp, oppUp, oppToward, oppSideS, myToward, oppFacing, oppPitch;
		public boolean myGround, oppGround, oppSprint, oppSwing, aiDealt, aiTaken, aiTakenCrit;
	}

	public static final class Round {
		public final String opponent;
		public final boolean humanWon;
		public final List<RoundTick> ticks = new ArrayList<>();
		public Round(String opponent, boolean humanWon) { this.opponent = opponent; this.humanWon = humanWon; }
	}

	public static final class HumanPolicy {
		/** Movement, sprint, jump, and attack heads used for Human-KL RL anchoring (leaves aim to SwordNet). */
		public static final int STYLE_HEADS = (1 << ActionSpace.FORWARD) | (1 << ActionSpace.STRAFE)
				| (1 << ActionSpace.SPRINT) | (1 << ActionSpace.JUMP) | (1 << ActionSpace.ATTACK);
		private static final int HIDDEN = 128;
		public final String name;
		public final int trainingTicks;
		public final ParamSet params = new ParamSet();
		private final Dense l1, l2, out;
		private final float[] h1 = new float[HIDDEN], h2 = new float[HIDDEN], dH1 = new float[HIDDEN], dH2 = new float[HIDDEN];
		private final float[] logits = new float[ActionSpace.TOTAL_LOGITS], probs = new float[ActionSpace.TOTAL_LOGITS];
		private final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];
		private final float[][] grads;

		public HumanPolicy(String name, int trainingTicks, long seed) {
			this.name = name; this.trainingTicks = trainingTicks;
			Random rng = new Random(seed); float relu = (float) Math.sqrt(2.0);
			l1 = new Dense(params, "hp.l1", STATE_DIM, HIDDEN, rng, relu);
			l2 = new Dense(params, "hp.l2", HIDDEN, HIDDEN, rng, relu);
			out = new Dense(params, "hp.out", HIDDEN, ActionSpace.TOTAL_LOGITS, rng, 0.01f);
			grads = params.newGrads();
		}

		public synchronized void probs(float[] state, float[] probsOut) {
			l1.forward(state, 0, h1, 0); relu(h1);
			l2.forward(h1, 0, h2, 0); relu(h2);
			out.forward(h2, 0, logits, 0);
			Policy.allProbs(logits, probsOut);
		}

		public synchronized void act(float[] state, float temperature, Random rng, int[] actionOut) {
			l1.forward(state, 0, h1, 0); relu(h1);
			l2.forward(h1, 0, h2, 0); relu(h2);
			out.forward(h2, 0, logits, 0);
			Policy.sample(logits, ActionSpace.SWORD_HEADS, false, temperature, rng, probs, actionOut);
		}

		synchronized void train(List<float[]> xs, List<byte[]> ys, int epochs, int batchSize, float lr, Random rng) {
			int total = xs.size(); if (total == 0) return;
			int n = Math.min(total, MAX_CLUSTER_SAMPLES);
			int[] idxPool = new int[total];
			for (int i = 0; i < total; i++) idxPool[i] = i;
			if (total > n) {
				for (int i = 0; i < n; i++) { int j = i + rng.nextInt(total - i), t = idxPool[i]; idxPool[i] = idxPool[j]; idxPool[j] = t; }
			}
			int[] order = Arrays.copyOf(idxPool, n);
			for (int ep = 0; ep < epochs; ep++) {
				for (int i = n - 1; i > 0; i--) { int j = rng.nextInt(i + 1), t = order[i]; order[i] = order[j]; order[j] = t; }
				for (int b = 0; b < n; b += batchSize) {
					int end = Math.min(n, b + batchSize);
					ParamSet.zeroGrads(grads);
					for (int k = b; k < end; k++) {
						float[] x = xs.get(order[k]); byte[] y = ys.get(order[k]);
						l1.forward(x, 0, h1, 0); relu(h1);
						l2.forward(h1, 0, h2, 0); relu(h2);
						out.forward(h2, 0, logits, 0);
						Arrays.fill(dLogits, 0f);
						for (int h = 0; h < ActionSpace.HEADS; h++) {
							int off = ActionSpace.OFFSETS[h], sz = ActionSpace.SIZES[h], a = y[h];
							Policy.softmax(logits, off, sz, probs);
							float w = (h == ActionSpace.ATTACK && a == 1) ? ATTACK_POS_WEIGHT
									: (h == ActionSpace.JUMP && a == 1) ? JUMP_POS_WEIGHT : 1f;
							for (int i = 0; i < sz; i++) dLogits[off + i] = w * (probs[off + i] - (i == a ? 1f : 0f));
						}
						out.backward(h2, 0, dLogits, 0, dH2, 0, grads); gate(dH2, h2);
						l2.backward(h1, 0, dH2, 0, dH1, 0, grads); gate(dH1, h1);
						l1.backward(x, 0, dH1, 0, null, 0, grads);
					}
					Adam.step(params, grads, lr, 1f / (end - b), 1.0f);
				}
			}
		}

		public static void packLiveState(float dist, float ownChg, float oppChg, boolean ownG, boolean oppG, boolean ownSpr,
				float ownFwd, float ownRight, float ownUp, float oppTowardMe, float oppUp, float ownHp, float oppHp,
				int sinceDealt, int sinceTaken, int ownCombo, int oppCombo, float yawErrDeg, float pitchDeg, float prevDist, float[] out) {
			out[0] = Math.min(2f, dist / 8f); out[1] = ownChg; out[2] = oppChg;
			out[3] = ownG ? 1f : 0f; out[4] = oppG ? 1f : 0f; out[5] = ownSpr ? 1f : 0f;
			out[6] = ownFwd * 5f; out[7] = ownRight * 5f; out[8] = ownUp * 5f;
			out[9] = oppTowardMe * 5f; out[10] = oppUp * 5f; out[11] = ownHp; out[12] = oppHp;
			out[13] = Math.max(0f, 1f - sinceDealt / 20f); out[14] = Math.max(0f, 1f - sinceTaken / 20f);
			out[15] = Math.min(5, ownCombo) / 5f; out[16] = Math.min(5, oppCombo) / 5f;
			out[17] = Math.max(-1f, Math.min(1f, yawErrDeg / 90f)); out[18] = Math.max(-1f, Math.min(1f, pitchDeg / 90f));
			out[19] = Math.min(2f, prevDist / 8f);
		}
	}

	private final List<Round> rounds = new ArrayList<>();
	private final List<HumanPolicy> opponents = new ArrayList<>();

	public int roundCount() { return rounds.size(); }
	public List<HumanPolicy> opponents() { return opponents; }

	public int loadDirectory(Path dir) {
		rounds.clear();
		if (dir == null || !Files.isDirectory(dir)) return 0;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> p.getFileName().toString().endsWith(".txt.gz")).sorted().forEach(p -> {
				try { Round r = parseRound(p); if (r != null && r.ticks.size() >= 15) rounds.add(r); } catch (IOException ignored) {}
			});
		} catch (IOException ignored) {}
		return rounds.size();
	}

	public void buildFromRealRounds(InverseDynamics idm, long seed) {
		opponents.clear();
		if (rounds.isEmpty()) return;
		Random rng = new Random(seed);

		List<Round> strong = new ArrayList<>();
		List<float[]> rawScores = new ArrayList<>();
		for (Round r : rounds) {
			float[] feat = styleScores(r);
			if (feat != null) { strong.add(r); rawScores.add(feat); }
		}
		if (strong.isEmpty()) {
			for (Round r : rounds) { strong.add(r); rawScores.add(new float[] {0f, 0f, 0f}); }
		}
		// Keep a rolling window of the newest strong rounds so ancient weak-AI losses don't dilute modern high-rank opponents.
		if (strong.size() > MAX_STRONG_ROUNDS) {
			int drop = strong.size() - MAX_STRONG_ROUNDS;
			strong = new ArrayList<>(strong.subList(drop, strong.size()));
			rawScores = new ArrayList<>(rawScores.subList(drop, rawScores.size()));
		}
		int[] clusterOf = assignPlaystyles(rawScores);

		Map<String, List<float[]>> byX = new LinkedHashMap<>();
		Map<String, List<byte[]>> byY = new LinkedHashMap<>();
		for (String name : CLUSTERS) { byX.put(name, new ArrayList<>()); byY.put(name, new ArrayList<>()); }

		float[][] tk = new float[InverseDynamics.WINDOW][InverseDynamics.TICK_DIM];
		float[] win = new float[InverseDynamics.IN_DIM];
		int[] pred = new int[ActionSpace.HEADS];

		for (int ri = 0; ri < strong.size(); ri++) {
			Round r = strong.get(ri);
			String cl = CLUSTERS[clusterOf[ri]];
			int n = r.ticks.size(); if (n < 7) continue;
			int copies = r.humanWon ? 2 : 1;
			int sinceD = 1000, sinceT = 1000, ownC = 0, oppC = 0;
			for (int t = 2; t < n - 3; t++) {
				RoundTick pM1 = r.ticks.get(t - 1), p0 = r.ticks.get(t), pP1 = r.ticks.get(t + 1),
						pP2 = r.ticks.get(t + 2), pP3 = r.ticks.get(t + 3);
				if (p0.aiTaken) { sinceD = 0; ownC++; oppC = 0; } else sinceD = Math.min(1000, sinceD + 1);
				if (p0.aiDealt) { sinceT = 0; oppC++; ownC = 0; } else sinceT = Math.min(1000, sinceT + 1);
				if (p0.dist > 8.5f) continue;

				for (int w = 0; w < InverseDynamics.WINDOW; w++) packKin(r.ticks.get(t - 2 + w), tk[w]);
				InverseDynamics.packWindow(tk[0], tk[1], tk[2], tk[3], tk[4], p0.aiDealt || pM1.aiDealt, win);
				idm.predict(win, pred);
				// Direct physics labels compensate for 3-tick RemoteView glide (which lags bounce-jump oppUp to t+3) and exact swing start.
				// At tick t before a swing, oppSwing is 0 and oppSword is ~0.96-1.00; on t+1 oppSwing turns 1 and oppSword resets to 0.
				if ((!p0.oppSwing && pP1.oppSwing) || (pP1.oppSword < p0.oppSword - 0.15f && p0.oppSword >= 0.85f)) {
					pred[ActionSpace.ATTACK] = 1;
				}
				boolean knockedUp = p0.aiDealt || pM1.aiDealt;
				float futureUp = Math.max(pP1.oppUp, Math.max(pP2.oppUp, pP3.oppUp));
				boolean tookOff = (p0.oppGround && !pP1.oppGround) || (pM1.oppUp < -0.10f && p0.oppUp > pM1.oppUp + 0.08f);
				if (!knockedUp && tookOff && futureUp > 0.05f) pred[ActionSpace.JUMP] = 1;
				// Vanilla Minecraft only clears ClientSprint mid-air when W (FORWARD) is released; kinematic IDM misses 1-tick air W-releases.
				if (!p0.oppGround && !p0.oppSprint && (pM1.oppSprint || p0.oppUp < 0.08f)) {
					if (pred[ActionSpace.FORWARD] == 1) pred[ActionSpace.FORWARD] = 0;
					pred[ActionSpace.SPRINT] = 0;
				}
				pred[ActionSpace.YAW] = InverseDynamics.nearestBin(wrapDeg(pP1.oppFacing - p0.oppFacing), ActionSpace.YAW_BINS);
				pred[ActionSpace.PITCH] = InverseDynamics.nearestBin(pP1.oppPitch - p0.oppPitch, ActionSpace.PITCH_BINS);
				byte[] ab = new byte[ActionSpace.HEADS];
				for (int h = 0; h < ActionSpace.HEADS; h++) ab[h] = (byte) pred[h];

				float[] st = new float[STATE_DIM];
				double rad = Math.toRadians(p0.oppFacing);
				float ef = (float) (p0.oppToward * Math.cos(rad) + p0.oppSideS * Math.sin(rad));
				float er = (float) (-p0.oppToward * Math.sin(rad) + p0.oppSideS * Math.cos(rad));
				HumanPolicy.packLiveState(p0.dist, p0.oppSword, p0.mySword, p0.oppGround, p0.myGround, p0.oppSprint,
						ef, er, p0.oppUp, p0.myToward, 0f, p0.oppHp / 20f, p0.myHp / 20f, sinceD, sinceT, ownC, oppC,
						-p0.oppFacing, p0.oppPitch, pM1.dist, st);
				for (int c = 0; c < copies; c++) {
					byX.get(cl).add(st); byY.get(cl).add(ab);
					byX.get("all-humans").add(st); byY.get("all-humans").add(ab);
				}
			}
		}
		for (String name : CLUSTERS) {
			List<float[]> xs = byX.get(name);
			if (xs.size() >= MIN_TICKS || ("all-humans".equals(name) && !xs.isEmpty())) {
				HumanPolicy hp = new HumanPolicy(name, xs.size(), rng.nextLong());
				hp.train(xs, byY.get(name), 14, 128, 2e-3f, rng);
				opponents.add(hp);
			}
		}
	}

	/** Returns [uppercutSpacerScore, critPressureScore, deflectComboScore] for strong human rounds, or null. */
	private static float[] styleScores(Round r) {
		int n = r.ticks.size(); if (n < 40) return null;
		int dealt = 0, taken = 0, crits = 0, upcuts = 0, defl = 0, close = 0, air = 0, retreat = 0, strafe = 0;
		float distSum = 0f;
		for (int i = 0; i < n; i++) {
			RoundTick t = r.ticks.get(i);
			if (t.aiDealt) dealt++;
			if (t.dist < 5.5f) {
				close++; distSum += t.dist;
				if (!t.oppGround) air++;
				if (t.oppToward < -0.05f) retreat++;
				if (Math.abs(t.oppSideS) > 0.08f) strafe++;
			}
			if (t.aiTaken) {
				taken++;
				if (t.aiTakenCrit) crits++;
				for (int j = Math.max(0, i - 3); j <= i; j++) {
					RoundTick p = r.ticks.get(j);
					if (!p.oppGround && p.oppSprint && p.oppUp > -0.10f) { upcuts++; break; }
				}
				for (int j = Math.max(0, i - 10); j < i; j++) {
					if (r.ticks.get(j).aiDealt) { defl++; break; }
				}
			}
		}
		if (close < 20) return null;
		boolean strong = r.humanWon || (taken >= 15 && taken >= 0.85f * dealt);
		if (!strong) return null;
		float tk = Math.max(1, taken), cl = close;
		float upcutScore = (upcuts / tk) + (retreat / cl) + Math.max(0f, (distSum / cl - 2.3f) * 0.5f);
		float critScore = 2.0f * (crits / tk) + 0.5f * (air / cl);
		float deflScore = 1.5f * (defl / tk) + 0.5f * (strafe / cl);
		return new float[] {upcutScore, critScore, deflScore};
	}

	private static int[] assignPlaystyles(List<float[]> scores) {
		int n = scores.size();
		double[] mean = new double[3], std = new double[3];
		for (float[] s : scores) for (int k = 0; k < 3; k++) mean[k] += s[k];
		for (int k = 0; k < 3; k++) mean[k] /= Math.max(1, n);
		for (float[] s : scores) for (int k = 0; k < 3; k++) { double d = s[k] - mean[k]; std[k] += d * d; }
		for (int k = 0; k < 3; k++) std[k] = Math.sqrt(std[k] / Math.max(1, n)) + 1e-6;
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			int best = 0; double bestZ = Double.NEGATIVE_INFINITY;
			for (int k = 0; k < 3; k++) {
				double z = (scores.get(i)[k] - mean[k]) / std[k];
				if (z > bestZ) { bestZ = z; best = k; }
			}
			out[i] = best;
		}
		return out;
	}

	private static void packKin(RoundTick tk, float[] dst) {
		InverseDynamics.packTick(tk.dist, tk.oppToward, tk.oppSideS, tk.oppUp,
				tk.oppGround, tk.oppSprint, tk.oppSword, tk.oppSwing, tk.oppFacing, tk.oppPitch, dst, 0);
	}

	public static Round parseRound(Path file) throws IOException {
		String fn = file.getFileName().toString(), opponent = "unknown";
		boolean humanWon = fn.contains("-lost.txt.gz"), inCsv = false;
		Round round = null;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (round == null && (line.startsWith("WON vs ") || line.startsWith("LOST vs ") || line.startsWith("OPEN vs "))) {
					humanWon = line.startsWith("LOST vs ");
					int vs = line.indexOf(" vs ") + 4, end = line.indexOf(" (", vs);
					if (end < 0) end = line.indexOf(':', vs);
					opponent = end > vs ? line.substring(vs, end).trim() : "unknown";
					round = new Round(opponent, humanWon);
				} else if (line.startsWith("tick,dist,mySword,")) {
					if (round == null) round = new Round(opponent, humanWon);
					inCsv = true;
				} else if (inCsv && !line.isEmpty()) {
					String[] p = line.split(",", -1); if (p.length < 18) continue;
					RoundTick tk = new RoundTick();
					tk.dist = pf(p[1]); tk.mySword = pf(p[2]); tk.oppSword = pf(p[3]); tk.myHp = pf(p[4]); tk.oppHp = pf(p[5]);
					tk.myGround = p[6].startsWith("g"); tk.oppGround = p[7].startsWith("g"); tk.oppSprint = p[7].contains("s");
					tk.oppUp = pf(p[8]); tk.oppToward = pf(p[9]); tk.myToward = pf(p[11]);
					tk.oppSideS = pf(p[14]); tk.oppFacing = pf(p[15]); tk.oppPitch = pf(p[16]); tk.oppSwing = "1".equals(p[17]);
					String ev = p.length > 18 ? p[18] : "";
					tk.aiDealt = ev.contains("DEALT"); tk.aiTaken = ev.contains("TAKEN"); tk.aiTakenCrit = ev.contains("TAKEN:crit");
					round.ticks.add(tk);
				}
			}
		}
		return round;
	}

	private static float pf(String s) { try { return Float.parseFloat(s); } catch (NumberFormatException e) { return 0f; } }
	private static float wrapDeg(float d) { while (d > 180f) d -= 360f; while (d < -180f) d += 360f; return d; }
	private static void relu(float[] a) { for (int i = 0; i < a.length; i++) if (a[i] < 0f) a[i] = 0f; }
	private static void gate(float[] g, float[] a) { for (int i = 0; i < g.length; i++) if (a[i] <= 0f) g[i] = 0f; }
}
