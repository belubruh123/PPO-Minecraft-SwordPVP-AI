package dev.swordai.core.league;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsSpec;

import java.util.Arrays;
import java.util.Random;

/**
 * AlphaZero-style arena outcome search (Follow-up #3) + key-moment scene pool (Follow-up #1):
 * <ol>
 *   <li>Captures decisive moments during fights matching the four user goals:
 *       {@link Kind#AFTER_PUNISH_CRIT}, {@link Kind#COMBO_START}, {@link Kind#HOLD_COMBO},
 *       and {@link Kind#COUNTER_CRIT}.</li>
 *   <li>Restores a saved scene in the live arena via {@code SceneRecorder}, tries each candidate
 *       short opening macro {@link #REPEATS} times followed by rollout under the current
 *       {@link SwordNet}, and scores each trial strictly by actual Minecraft exchange outcome
 *       (never an LLM's or hand-written label).</li>
 *   <li>Whenever an opening beats the current policy's mean outcome by {@link #MIN_MARGIN}, stores
 *       the winning first-tick action in a supervised ring buffer trained by {@link LeaguePpo}.</li>
 * </ol>
 */
public final class MoveSearch {
	public enum Kind {
		AFTER_PUNISH_CRIT,
		COMBO_START,
		HOLD_COMBO,
		COUNTER_CRIT
	}

	/**
	 * Candidate 4-tick openings evaluated from the exact restored scene. Candidate 0 is always
	 * the current policy itself (baseline for comparison); candidates 1..6 perturb body keys while
	 * keeping the network's own camera aim.
	 */
	public enum Candidate {
		CURRENT_POLICY(-1, -1, -1, -1, -1),
		SPRINT_HIT_STRAFE_L(1, 1, 1, 0, 1),
		SPRINT_HIT_STRAFE_R(1, 2, 1, 0, 1),
		JUMP_CRIT_FORWARD(1, 0, 0, 1, 1),
		W_TAP_HOLD(0, 1, 0, 0, 0),
		S_BACK_COUNTER(2, 0, 0, 0, 1),
		JUMP_RESET_SWING(1, 0, 1, 1, 1);

		public final int forward, strafe, sprint, jump, attack;

		Candidate(int forward, int strafe, int sprint, int jump, int attack) {
			this.forward = forward;
			this.strafe = strafe;
			this.sprint = sprint;
			this.jump = jump;
			this.attack = attack;
		}

		public boolean isPolicy() {
			return forward < 0;
		}

		/** Applies this candidate's body keys onto {@code action} (preserving the network's camera). */
		public void apply(int[] action, int tickInMacro, boolean swordReady, boolean falling) {
			if (isPolicy()) {
				return;
			}
			action[ActionSpace.FORWARD] = forward;
			action[ActionSpace.STRAFE] = strafe;
			action[ActionSpace.SPRINT] = sprint;
			action[ActionSpace.JUMP] = (jump == 1 && tickInMacro == 0) ? 1 : 0;
			if (this == JUMP_CRIT_FORWARD) {
				// Jump on tick 0, swing once falling (or ready on later ticks).
				action[ActionSpace.ATTACK] = (falling && swordReady) ? 1 : 0;
			} else {
				action[ActionSpace.ATTACK] = (attack == 1 && swordReady) ? 1 : 0;
			}
		}
	}

	public static final int MACRO_TICKS = 4;
	public static final int ROLLOUT_TICKS = 32;
	public static final int REPEATS = 3;
	public static final float MIN_MARGIN = 1.0f;

	/** Saved decisive fight moment with both arena physics state and exact actor input snapshot. */
	public static final class Moment {
		public final Kind kind;
		public final double[] arenaState;
		public final byte[] peri;
		public final byte[] fovea;
		public final float[] vec;
		public final float[] hist;
		public final float[] seen;
		public final float[] hPrev;

		Moment(Kind kind, double[] arenaState, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
			this.kind = kind;
			this.arenaState = arenaState.clone();
			this.peri = obs.peri.clone();
			this.fovea = obs.fovea.clone();
			this.vec = obs.vec.clone();
			this.hist = histIn.clone();
			this.seen = seenIn.clone();
			this.hPrev = hIn.clone();
		}
	}

	/** Active multi-trial search over one saved {@link Moment}. */
	public static final class SearchJob {
		public final Moment moment;
		private final Candidate[] cands = Candidate.values();
		private final double[] scoreSum = new double[Candidate.values().length];
		private final int[] trials = new int[Candidate.values().length];
		private final byte[][] firstTickAction = new byte[Candidate.values().length][ActionSpace.HEADS];
		private final boolean[] hasFirstAction = new boolean[Candidate.values().length];
		private int candIdx;
		private int repeatIdx;

		SearchJob(Moment moment) {
			this.moment = moment;
		}

		public boolean finished() {
			return candIdx >= cands.length;
		}

		public Candidate currentCandidate() {
			return cands[Math.min(candIdx, cands.length - 1)];
		}

		public void noteFirstAction(int[] action) {
			if (!hasFirstAction[candIdx]) {
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					firstTickAction[candIdx][h] = (byte) action[h];
				}
				hasFirstAction[candIdx] = true;
			}
		}

		/** Records the game outcome of one completed trial and advances to the next trial/candidate. */
		public void finishTrial(float damageDealt, float damageTaken, boolean koDealt, boolean koTaken,
				int ownMaxCombo, int oppMaxCombo) {
			float score = (damageDealt - damageTaken)
					+ (koDealt ? 10f : 0f) - (koTaken ? 10f : 0f)
					+ 1.5f * (ownMaxCombo - oppMaxCombo);
			scoreSum[candIdx] += score;
			trials[candIdx]++;
			repeatIdx++;
			if (repeatIdx >= REPEATS) {
				repeatIdx = 0;
				candIdx++;
			}
		}

		/** Returns winning candidate index if it beats baseline by {@link #MIN_MARGIN}, else -1. */
		public int winningCandidate() {
			if (trials[0] == 0) {
				return -1;
			}
			double baseMean = scoreSum[0] / trials[0];
			int best = -1;
			double bestMean = baseMean + MIN_MARGIN;
			for (int i = 1; i < cands.length; i++) {
				if (trials[i] > 0 && hasFirstAction[i]) {
					double m = scoreSum[i] / trials[i];
					if (m > bestMean) {
						bestMean = m;
						best = i;
					}
				}
			}
			return best;
		}

		public byte[] candidateAction(int idx) {
			return firstTickAction[idx];
		}

		public double meanScore(int idx) {
			return trials[idx] > 0 ? scoreSum[idx] / trials[idx] : 0.0;
		}
	}

	// Per-kind ring buffers of key moments (shared by key-state starts and MoveSearch).
	private static final int PER_KIND = 48;
	private final Moment[][] moments = new Moment[Kind.values().length][PER_KIND];
	private final int[] mSize = new int[Kind.values().length];
	private final int[] mNext = new int[Kind.values().length];

	// Ring buffer of game-verified supervised targets produced by completed searches.
	private static final int TARGET_CAP = 2048;
	private final byte[][] tPeri = new byte[TARGET_CAP][ObsSpec.PERI_SIZE];
	private final byte[][] tFovea = new byte[TARGET_CAP][ObsSpec.FOVEA_SIZE];
	private final float[][] tVec = new float[TARGET_CAP][ObsSpec.VEC];
	private final float[][] tHist = new float[TARGET_CAP][ObsMemory.HIST];
	private final float[][] tSeen = new float[TARGET_CAP][ObsMemory.SEEN];
	private final float[][] tHPrev = new float[TARGET_CAP][ObsMemory.GRU_HIDDEN];
	private final byte[][] tAction = new byte[TARGET_CAP][ActionSpace.HEADS];
	private int tSize;
	private int tNext;
	private long searchesDone;
	private long targetsFound;

	// Scratch batch buffers for LeaguePpo.supervisedStep.
	private static final int BATCH = 32;
	private final byte[][] bPeri = new byte[BATCH][];
	private final byte[][] bFovea = new byte[BATCH][];
	private final float[][] bVec = new float[BATCH][];
	private final float[][] bHist = new float[BATCH][];
	private final float[][] bSeen = new float[BATCH][];
	private final float[][] bHPrev = new float[BATCH][];
	private final byte[][] bAct = new byte[BATCH][];

	/** Saves a decisive moment if {@code arenaState} is non-null. */
	public void recordMoment(Kind kind, double[] arenaState, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
		if (arenaState == null) {
			return;
		}
		int k = kind.ordinal();
		moments[k][mNext[k]] = new Moment(kind, arenaState, obs, histIn, seenIn, hIn);
		mNext[k] = (mNext[k] + 1) % PER_KIND;
		mSize[k] = Math.min(mSize[k] + 1, PER_KIND);
	}

	public int totalMoments() {
		int s = 0;
		for (int c : mSize) {
			s += c;
		}
		return s;
	}

	public int targetCount() {
		return tSize;
	}

	public long searchesDone() {
		return searchesDone;
	}

	public long targetsFound() {
		return targetsFound;
	}

	/** Samples a balanced key moment across all populated kinds (for key-state starts or search). */
	public Moment sampleMoment(Random rng) {
		Kind[] kinds = Kind.values();
		int nonEmpty = 0;
		for (int k = 0; k < kinds.length; k++) {
			if (mSize[k] > 0) {
				nonEmpty++;
			}
		}
		if (nonEmpty == 0) {
			return null;
		}
		int pick = rng.nextInt(nonEmpty);
		for (int k = 0; k < kinds.length; k++) {
			if (mSize[k] > 0) {
				if (pick == 0) {
					return moments[k][rng.nextInt(mSize[k])];
				}
				pick--;
			}
		}
		return null;
	}

	/** Starts a new outcome-search job on a sampled key moment, or null if none are saved yet. */
	public SearchJob startSearch(Random rng) {
		Moment m = sampleMoment(rng);
		return m == null ? null : new SearchJob(m);
	}

	/** Commits the result of a finished {@link SearchJob} into the supervised target buffer. */
	public boolean commitSearch(SearchJob job) {
		if (job == null || !job.finished()) {
			return false;
		}
		searchesDone++;
		int winner = job.winningCandidate();
		if (winner < 0) {
			return false;
		}
		Moment m = job.moment;
		int i = tNext;
		System.arraycopy(m.peri, 0, tPeri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(m.fovea, 0, tFovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(m.vec, 0, tVec[i], 0, ObsSpec.VEC);
		System.arraycopy(m.hist, 0, tHist[i], 0, ObsMemory.HIST);
		System.arraycopy(m.seen, 0, tSeen[i], 0, ObsMemory.SEEN);
		System.arraycopy(m.hPrev, 0, tHPrev[i], 0, ObsMemory.GRU_HIDDEN);
		System.arraycopy(job.candidateAction(winner), 0, tAction[i], 0, ActionSpace.HEADS);
		tNext = (tNext + 1) % TARGET_CAP;
		tSize = Math.min(tSize + 1, TARGET_CAP);
		targetsFound++;
		return true;
	}

	/** Runs {@code steps} supervised updates on game-verified search targets. Returns mean CE. */
	public float trainActor(SwordNet actor, LeaguePpo ppo, int steps, float lr, Random rng) {
		if (tSize < 8) {
			return 0f;
		}
		int n = Math.min(BATCH, tSize);
		float ceSum = 0f;
		for (int s = 0; s < steps; s++) {
			for (int i = 0; i < n; i++) {
				int idx = rng.nextInt(tSize);
				bPeri[i] = tPeri[idx];
				bFovea[i] = tFovea[idx];
				bVec[i] = tVec[idx];
				bHist[i] = tHist[idx];
				bSeen[i] = tSeen[idx];
				bHPrev[i] = tHPrev[idx];
				bAct[i] = tAction[idx];
			}
			ceSum += ppo.supervisedStep(actor, bPeri, bFovea, bVec, bHist, bSeen, bHPrev, bAct, n, lr);
		}
		return ceSum / steps;
	}

	/** Utility: directly injects a verified target (also used in unit tests). */
	public void addTarget(Obs obs, ObsMemory mem, float[] hIn, int[] action) {
		int i = tNext;
		System.arraycopy(obs.peri, 0, tPeri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(obs.fovea, 0, tFovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(obs.vec, 0, tVec[i], 0, ObsSpec.VEC);
		System.arraycopy(mem.hist, 0, tHist[i], 0, ObsMemory.HIST);
		System.arraycopy(mem.seen, 0, tSeen[i], 0, ObsMemory.SEEN);
		if (hIn != null) {
			System.arraycopy(hIn, 0, tHPrev[i], 0, ObsMemory.GRU_HIDDEN);
		} else {
			Arrays.fill(tHPrev[i], 0f);
		}
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			tAction[i][h] = (byte) action[h];
		}
		tNext = (tNext + 1) % TARGET_CAP;
		tSize = Math.min(tSize + 1, TARGET_CAP);
		targetsFound++;
	}
}
