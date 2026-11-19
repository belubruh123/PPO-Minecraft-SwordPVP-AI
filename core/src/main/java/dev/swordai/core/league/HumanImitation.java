package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Learns from recorded real duels (Follow-up #2): labels human keys via {@link InverseDynamics},
 * behaviour-clones losing human opponents into {@link HumanPolicy} league opponents, and extracts
 * outcome-filtered winning human moments rendered through {@link SyntheticEye}.
 */
public final class HumanImitation {
	public static final int STATE_DIM = 20;
	private static final int MIN_TICKS = 80, MAX_DEMOS = 2048;

	public static final class RoundTick {
		public float dist, mySword, oppSword, myHp, oppHp, oppUp, oppToward, oppSideS, myToward, oppFacing, oppPitch;
		public boolean myGround, oppGround, oppSprint, oppSwing, aiDealt, aiTaken;
	}

	public static final class Round {
		public final String opponent;
		public final boolean humanWon;
		public final List<RoundTick> ticks = new ArrayList<>();
		public Round(String opponent, boolean humanWon) { this.opponent = opponent; this.humanWon = humanWon; }
	}

	public static final class HumanPolicy {
		private static final int HIDDEN = 96;
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

		public synchronized void act(float[] state, float temperature, Random rng, int[] actionOut) {
			l1.forward(state, 0, h1, 0); relu(h1);
			l2.forward(h1, 0, h2, 0); relu(h2);
			out.forward(h2, 0, logits, 0);
			Policy.sample(logits, ActionSpace.ALL_HEADS, false, temperature, rng, probs, actionOut);
		}

		synchronized void train(List<float[]> xs, List<byte[]> ys, int epochs, int batchSize, float lr, Random rng) {
			int n = xs.size(); if (n == 0) return;
			int[] order = new int[n]; for (int i = 0; i < n; i++) order[i] = i;
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
							for (int i = 0; i < sz; i++) dLogits[off + i] = probs[off + i] - (i == a ? 1f : 0f);
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

	public static final class WinningDemos {
		public final byte[][] peri, fovea, action;
		public final float[][] vec, hist, seen;
		public final int size;
		WinningDemos(int n) {
			size = n; peri = new byte[n][ObsSpec.PERI_SIZE]; fovea = new byte[n][ObsSpec.FOVEA_SIZE];
			vec = new float[n][ObsSpec.VEC]; hist = new float[n][ObsMemory.HIST]; seen = new float[n][ObsMemory.SEEN];
			action = new byte[n][ActionSpace.HEADS];
		}
	}

	private final List<Round> rounds = new ArrayList<>();
	private final List<HumanPolicy> opponents = new ArrayList<>();
	private WinningDemos winningDemos = new WinningDemos(0);

	public int roundCount() { return rounds.size(); }
	public List<HumanPolicy> opponents() { return opponents; }
	public WinningDemos winningDemos() { return winningDemos; }

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
		if (rounds.isEmpty()) { winningDemos = new WinningDemos(0); return; }
		Random rng = new Random(seed);
		Map<String, List<float[]>> byX = new HashMap<>();
		Map<String, List<byte[]>> byY = new HashMap<>();
		List<float[]> allX = new ArrayList<>();
		List<byte[]> allY = new ArrayList<>();
		List<Round> dRounds = new ArrayList<>();
		List<Integer> dIdx = new ArrayList<>();
		List<byte[]> dAct = new ArrayList<>();

		float[] t0 = new float[InverseDynamics.TICK_DIM], t1 = new float[InverseDynamics.TICK_DIM];
		float[] t2 = new float[InverseDynamics.TICK_DIM], win = new float[InverseDynamics.IN_DIM];
		int[] pred = new int[ActionSpace.HEADS];

		for (Round r : rounds) {
			int n = r.ticks.size(); if (n < 4) continue;
			int sinceD = 1000, sinceT = 1000, ownC = 0, oppC = 0;
			for (int t = 1; t < n - 1; t++) {
				RoundTick p0 = r.ticks.get(t - 1), p1 = r.ticks.get(t), p2 = r.ticks.get(t + 1);
				if (p1.aiTaken) { sinceD = 0; ownC++; oppC = 0; } else sinceD = Math.min(1000, sinceD + 1);
				if (p1.aiDealt) { sinceT = 0; oppC++; ownC = 0; } else sinceT = Math.min(1000, sinceT + 1);

				packKin(p0, t0); packKin(p1, t1); packKin(p2, t2);
				InverseDynamics.packWindow(t0, t1, t2, p1.aiDealt || p0.aiDealt, win);
				idm.predict(win, pred);
				pred[ActionSpace.YAW] = InverseDynamics.nearestBin(wrapDeg(p2.oppFacing - p1.oppFacing), ActionSpace.YAW_BINS);
				pred[ActionSpace.PITCH] = InverseDynamics.nearestBin(p2.oppPitch - p1.oppPitch, ActionSpace.PITCH_BINS);
				byte[] ab = new byte[ActionSpace.HEADS];
				for (int h = 0; h < ActionSpace.HEADS; h++) ab[h] = (byte) pred[h];

				float[] st = new float[STATE_DIM];
				double rad = Math.toRadians(p1.oppFacing);
				float ef = (float) (p1.oppToward * Math.cos(rad) + p1.oppSideS * Math.sin(rad));
				float er = (float) (-p1.oppToward * Math.sin(rad) + p1.oppSideS * Math.cos(rad));
				HumanPolicy.packLiveState(p1.dist, p1.oppSword, p1.mySword, p1.oppGround, p1.myGround, p1.oppSprint,
						ef, er, p1.oppUp, p1.myToward, 0f, p1.oppHp / 20f, p1.myHp / 20f, sinceD, sinceT, ownC, oppC,
						-p1.oppFacing, p1.oppPitch, p0.dist, st);
				if (r.humanWon) {
					byX.computeIfAbsent(r.opponent, k -> new ArrayList<>()).add(st);
					byY.computeIfAbsent(r.opponent, k -> new ArrayList<>()).add(ab);
					allX.add(st); allY.add(ab);
				}
				if (dAct.size() < MAX_DEMOS && humanWinsExchange(r.ticks, t)) { dRounds.add(r); dIdx.add(t); dAct.add(ab); }
			}
		}
		for (var e : byX.entrySet()) {
			if (e.getValue().size() >= MIN_TICKS) {
				HumanPolicy hp = new HumanPolicy(e.getKey(), e.getValue().size(), rng.nextLong());
				hp.train(e.getValue(), byY.get(e.getKey()), 12, 64, 1e-3f, rng);
				opponents.add(hp);
			}
		}
		if (!allX.isEmpty()) {
			HumanPolicy agg = new HumanPolicy("real-humans", allX.size(), rng.nextLong());
			agg.train(allX, allY, 10, 64, 1e-3f, rng);
			opponents.add(agg);
		}
		winningDemos = renderDemos(dRounds, dIdx, dAct);
	}

	private static boolean humanWinsExchange(List<RoundTick> ticks, int t) {
		int end = Math.min(ticks.size(), t + 20), hHits = 0, aHits = 0; boolean soon = false;
		for (int k = t; k < end; k++) {
			if (ticks.get(k).aiTaken) { hHits++; if (k <= t + 10) soon = true; }
			if (ticks.get(k).aiDealt) aHits++;
		}
		return soon && hHits > aHits;
	}

	private static WinningDemos renderDemos(List<Round> dRounds, List<Integer> dIdx, List<byte[]> dAct) {
		int n = dAct.size();
		WinningDemos out = new WinningDemos(n);
		if (n == 0) return out;
		SyntheticEye eye = new SyntheticEye(); MaskFrame cur = new MaskFrame(), prev = new MaskFrame(); Obs obs = new Obs();
		for (int i = 0; i < n; i++) {
			Round r = dRounds.get(i); int t = dIdx.get(i);
			RoundTick p0 = r.ticks.get(Math.max(0, t - 1)), p1 = r.ticks.get(t);
			eye.look(0, 1.62, 0, p0.oppFacing, p0.oppPitch, 0, 0, Math.max(0.6, p0.dist), 180f, 180f, 0f, prev);
			eye.look(0, 1.62, 0, p1.oppFacing, p1.oppPitch, 0, 0, Math.max(0.6, p1.dist), 180f, 180f, 0f, cur);
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
			System.arraycopy(dAct.get(i), 0, out.action[i], 0, ActionSpace.HEADS);
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
					tk.aiDealt = ev.contains("DEALT"); tk.aiTaken = ev.contains("TAKEN");
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
