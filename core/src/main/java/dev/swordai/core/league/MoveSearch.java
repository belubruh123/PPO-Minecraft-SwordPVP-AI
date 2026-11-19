package dev.swordai.core.league;

import dev.swordai.core.rl.*;

import java.util.*;

/** AlphaZero-style arena outcome search (Follow-up #3) + key-moment scene pool (Follow-up #1). */
public final class MoveSearch {
	public enum Kind { AFTER_PUNISH_CRIT, COMBO_START, HOLD_COMBO, COUNTER_CRIT }

	public enum Candidate {
		CURRENT_POLICY(-1, -1, -1, -1, -1),
		SPRINT_HIT_STRAFE_L(1, 1, 1, 0, 1),
		SPRINT_HIT_STRAFE_R(1, 2, 1, 0, 1),
		JUMP_CRIT_FORWARD(1, 0, 0, 1, 1),
		W_TAP_HOLD(0, 1, 0, 0, 0),
		S_BACK_COUNTER(2, 0, 0, 0, 1),
		JUMP_RESET_SWING(1, 0, 1, 1, 1);

		public final int forward, strafe, sprint, jump, attack;
		Candidate(int f, int st, int sp, int j, int a) { forward = f; strafe = st; sprint = sp; jump = j; attack = a; }

		public void apply(int[] action, int tick, boolean ready, boolean falling) {
			if (forward < 0) return;
			action[ActionSpace.FORWARD] = forward;
			action[ActionSpace.STRAFE] = strafe;
			action[ActionSpace.SPRINT] = sprint;
			action[ActionSpace.JUMP] = (jump == 1 && tick == 0) ? 1 : 0;
			action[ActionSpace.ATTACK] = (this == JUMP_CRIT_FORWARD ? (falling && ready) : (attack == 1 && ready)) ? 1 : 0;
		}
	}

	public static final int MACRO_TICKS = 4, ROLLOUT_TICKS = 32, REPEATS = 3;
	public static final float MIN_MARGIN = 1.0f;

	public static final class Moment {
		public final Kind kind;
		public final double[] arenaState;
		public final byte[] peri, fovea;
		public final float[] vec, hist, seen, hPrev;
		Moment(Kind kind, double[] state, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
			this.kind = kind; this.arenaState = state.clone();
			this.peri = obs.peri.clone(); this.fovea = obs.fovea.clone(); this.vec = obs.vec.clone();
			this.hist = histIn.clone(); this.seen = seenIn.clone(); this.hPrev = hIn.clone();
		}
	}

	public static final class SearchJob {
		public final Moment moment;
		private final Candidate[] cands = Candidate.values();
		private final double[] scoreSum = new double[Candidate.values().length];
		private final int[] trials = new int[Candidate.values().length];
		private final byte[][] firstAct = new byte[Candidate.values().length][ActionSpace.HEADS];
		private final boolean[] hasFirst = new boolean[Candidate.values().length];
		private int ci, ri;

		SearchJob(Moment m) { this.moment = m; }
		public boolean finished() { return ci >= cands.length; }
		public Candidate currentCandidate() { return cands[Math.min(ci, cands.length - 1)]; }

		public void noteFirstAction(int[] a) {
			if (!hasFirst[ci]) { for (int h = 0; h < ActionSpace.HEADS; h++) firstAct[ci][h] = (byte) a[h]; hasFirst[ci] = true; }
		}

		public void finishTrial(float dealt, float taken, boolean koD, boolean koT, int ownC, int oppC) {
			scoreSum[ci] += (dealt - taken) + (koD ? 10f : 0f) - (koT ? 10f : 0f) + 1.5f * (ownC - oppC);
			trials[ci]++;
			if (++ri >= REPEATS) { ri = 0; ci++; }
		}

		public int winningCandidate() {
			if (trials[0] == 0) return -1;
			double bestMean = scoreSum[0] / trials[0] + MIN_MARGIN; int best = -1;
			for (int i = 1; i < cands.length; i++) {
				if (trials[i] > 0 && hasFirst[i]) {
					double m = scoreSum[i] / trials[i];
					if (m > bestMean) { bestMean = m; best = i; }
				}
			}
			return best;
		}
		public byte[] candidateAction(int i) { return firstAct[i]; }
	}

	private static final int PER_KIND = 48, TARGET_CAP = 2048, BATCH = 32;
	private final Moment[][] moments = new Moment[Kind.values().length][PER_KIND];
	private final int[] mSize = new int[Kind.values().length], mNext = new int[Kind.values().length];
	private final byte[][] tPeri = new byte[TARGET_CAP][ObsSpec.PERI_SIZE], tFovea = new byte[TARGET_CAP][ObsSpec.FOVEA_SIZE];
	private final float[][] tVec = new float[TARGET_CAP][ObsSpec.VEC], tHist = new float[TARGET_CAP][ObsMemory.HIST];
	private final float[][] tSeen = new float[TARGET_CAP][ObsMemory.SEEN], tHPrev = new float[TARGET_CAP][ObsMemory.GRU_HIDDEN];
	private final byte[][] tAction = new byte[TARGET_CAP][ActionSpace.HEADS];
	private final byte[][] bPeri = new byte[BATCH][], bFovea = new byte[BATCH][], bAct = new byte[BATCH][];
	private final float[][] bVec = new float[BATCH][], bHist = new float[BATCH][], bSeen = new float[BATCH][], bHPrev = new float[BATCH][];
	private int tSize, tNext;
	private long searchesDone, targetsFound;

	public void recordMoment(Kind kind, double[] state, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
		if (state == null) return;
		int k = kind.ordinal();
		moments[k][mNext[k]] = new Moment(kind, state, obs, histIn, seenIn, hIn);
		mNext[k] = (mNext[k] + 1) % PER_KIND;
		mSize[k] = Math.min(mSize[k] + 1, PER_KIND);
	}

	public int totalMoments() { int s = 0; for (int c : mSize) s += c; return s; }
	public int targetCount() { return tSize; }
	public long searchesDone() { return searchesDone; }
	public long targetsFound() { return targetsFound; }

	public Moment sampleMoment(Random rng) {
		int nonEmpty = 0; for (int c : mSize) if (c > 0) nonEmpty++;
		if (nonEmpty == 0) return null;
		int pick = rng.nextInt(nonEmpty);
		for (int k = 0; k < mSize.length; k++) if (mSize[k] > 0 && pick-- == 0) return moments[k][rng.nextInt(mSize[k])];
		return null;
	}

	public SearchJob startSearch(Random rng) { Moment m = sampleMoment(rng); return m == null ? null : new SearchJob(m); }

	public boolean commitSearch(SearchJob job) {
		if (job == null || !job.finished()) return false;
		searchesDone++;
		int win = job.winningCandidate();
		if (win < 0) return false;
		Moment m = job.moment; int i = tNext;
		System.arraycopy(m.peri, 0, tPeri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(m.fovea, 0, tFovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(m.vec, 0, tVec[i], 0, ObsSpec.VEC);
		System.arraycopy(m.hist, 0, tHist[i], 0, ObsMemory.HIST);
		System.arraycopy(m.seen, 0, tSeen[i], 0, ObsMemory.SEEN);
		System.arraycopy(m.hPrev, 0, tHPrev[i], 0, ObsMemory.GRU_HIDDEN);
		System.arraycopy(job.candidateAction(win), 0, tAction[i], 0, ActionSpace.HEADS);
		tNext = (tNext + 1) % TARGET_CAP; tSize = Math.min(tSize + 1, TARGET_CAP); targetsFound++;
		return true;
	}

	public float trainActor(SwordNet actor, LeaguePpo ppo, int steps, float lr, Random rng) {
		if (tSize < 8) return 0f;
		int n = Math.min(BATCH, tSize); float ceSum = 0f;
		for (int s = 0; s < steps; s++) {
			for (int i = 0; i < n; i++) {
				int idx = rng.nextInt(tSize);
				bPeri[i] = tPeri[idx]; bFovea[i] = tFovea[idx]; bVec[i] = tVec[idx];
				bHist[i] = tHist[idx]; bSeen[i] = tSeen[idx]; bHPrev[i] = tHPrev[idx]; bAct[i] = tAction[idx];
			}
			ceSum += ppo.supervisedStep(actor, bPeri, bFovea, bVec, bHist, bSeen, bHPrev, bAct, n, lr);
		}
		return ceSum / steps;
	}

	public void addTarget(Obs obs, ObsMemory mem, float[] hIn, int[] action) {
		int i = tNext;
		System.arraycopy(obs.peri, 0, tPeri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(obs.fovea, 0, tFovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(obs.vec, 0, tVec[i], 0, ObsSpec.VEC);
		System.arraycopy(mem.hist, 0, tHist[i], 0, ObsMemory.HIST);
		System.arraycopy(mem.seen, 0, tSeen[i], 0, ObsMemory.SEEN);
		if (hIn != null) System.arraycopy(hIn, 0, tHPrev[i], 0, ObsMemory.GRU_HIDDEN); else Arrays.fill(tHPrev[i], 0f);
		for (int h = 0; h < ActionSpace.HEADS; h++) tAction[i][h] = (byte) action[h];
		tNext = (tNext + 1) % TARGET_CAP; tSize = Math.min(tSize + 1, TARGET_CAP); targetsFound++;
	}
}
