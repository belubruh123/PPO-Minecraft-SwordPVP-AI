package dev.swordai.core.league;

import dev.swordai.core.rl.*;

import java.util.*;

/** AlphaZero-style arena outcome search + key-moment scene pool (Sword + SMP Shield/Axe/Gap macros). */
public final class MoveSearch {
	public enum Kind { AFTER_PUNISH_CRIT, COMBO_START, HOLD_COMBO, COUNTER_CRIT, OPP_SHIELD_UP, LOW_HP_GAP }

	/** Supervised heads: movement, sprint, jump, attack, use-item, pointer hotbar (never disturbs YAW/PITCH aim). */
	public static final int SUPERVISE_HEADS = HumanImitation.HumanPolicy.STYLE_HEADS
			| (1 << ActionSpace.USE_ITEM) | (1 << ActionSpace.HOTBAR_SLOT);

	public enum Candidate {
		CURRENT_POLICY(-1, -1, -1, -1, -1, 0),
		SPRINT_HIT_STRAFE_L(1, 1, 1, 0, 1, 4),
		SPRINT_HIT_STRAFE_R(1, 2, 1, 0, 1, 4),
		JUMP_CRIT_FORWARD(1, 0, 0, 1, 1, 10),
		WAIT_DEFLECT(0, 0, 0, 0, 0, 4),
		S_FLY_OUT(2, 0, 0, 1, 1, 4),
		UPPERCUT_CHASE(1, 0, 1, 1, 1, 6),
		// SMP Phase 1 macros (only tested when kit contains the required item; evaluated purely by arena dmg/KO outcome)
		RAISE_SHIELD(2, 0, 0, 0, 0, 10),
		AXE_BREAK_SWORD_CRIT(1, 0, 1, 0, 1, 18),
		BACK_AND_EAT_GAP(2, 0, 0, 1, 0, 48);

		public final int forward, strafe, sprint, jump, attack, macroTicks;
		Candidate(int f, int st, int sp, int j, int a, int mt) {
			forward = f; strafe = st; sprint = sp; jump = j; attack = a; macroTicks = mt;
		}

		public boolean isSmpMacro() {
			return this == RAISE_SHIELD || this == AXE_BREAK_SWORD_CRIT || this == BACK_AND_EAT_GAP;
		}

		/** Legacy sword-only helper (no item slots). */
		public void apply(int[] action, int tick, boolean canHit, boolean falling) {
			apply(action, tick, canHit, canHit, falling, -1, -1, -1, -1, false, false, -1);
		}

		/**
		 * Applies macro on movement/item heads (leaves aim untouched except retreat-turn during BACK_AND_EAT_GAP).
		 * {@code curSlot, swordSlot, axeSlot, gapSlot} are 0..8 hotbar indices (or -1 if absent in current randomized kit).
		 */
		public void apply(int[] action, int tick, boolean canHit, boolean inReachAimed, boolean falling,
				int curSlot, int swordSlot, int axeSlot, int gapSlot, boolean hasShield, boolean oppShieldUp, int awayYawBin) {
			if (forward < 0) return;
			action[ActionSpace.USE_ITEM] = 0;
			action[ActionSpace.HOTBAR_SLOT] = 0;
			action[ActionSpace.OFFHAND_SWAP] = 0;
			action[ActionSpace.INVENTORY] = 0;

			if (this == RAISE_SHIELD) {
				action[ActionSpace.FORWARD] = tick < 2 ? 2 : 0;
				action[ActionSpace.STRAFE] = 0;
				action[ActionSpace.SPRINT] = 0;
				action[ActionSpace.JUMP] = 0;
				action[ActionSpace.ATTACK] = 0;
				action[ActionSpace.USE_ITEM] = hasShield ? 1 : 0;
				return;
			}
			if (this == AXE_BREAK_SWORD_CRIT) {
				action[ActionSpace.FORWARD] = 1;
				action[ActionSpace.STRAFE] = 0;
				action[ActionSpace.SPRINT] = 1;
				action[ActionSpace.JUMP] = 0;
				if (oppShieldUp && axeSlot >= 0) {
					if (curSlot != axeSlot) {
						action[ActionSpace.HOTBAR_SLOT] = axeSlot + 1;
						action[ActionSpace.ATTACK] = 0;
					} else {
						// Vanilla Minecraft disables a raised shield on ANY axe hit (no 0.90 charge wait needed!).
						action[ActionSpace.ATTACK] = inReachAimed ? 1 : 0;
					}
				} else {
					if (swordSlot >= 0 && curSlot != swordSlot) {
						action[ActionSpace.HOTBAR_SLOT] = swordSlot + 1;
						action[ActionSpace.ATTACK] = 0;
					} else {
						action[ActionSpace.ATTACK] = canHit ? 1 : 0;
					}
				}
				return;
			}
			if (this == BACK_AND_EAT_GAP) {
				if (tick < 12 && awayYawBin >= 0) {
					// Sprint-jump away first to create safe Golden Apple eating distance (like SparBot / real SMP).
					action[ActionSpace.YAW] = awayYawBin;
					action[ActionSpace.FORWARD] = 1;
					action[ActionSpace.STRAFE] = 0;
					action[ActionSpace.SPRINT] = 1;
					action[ActionSpace.JUMP] = (tick % 3 == 0) ? 1 : 0;
					action[ActionSpace.ATTACK] = 0;
					if (tick == 0 && gapSlot >= 0 && curSlot != gapSlot) action[ActionSpace.HOTBAR_SLOT] = gapSlot + 1;
				} else {
					action[ActionSpace.FORWARD] = 2;
					action[ActionSpace.STRAFE] = 0;
					action[ActionSpace.SPRINT] = 0;
					action[ActionSpace.JUMP] = 0;
					action[ActionSpace.ATTACK] = 0;
					if (gapSlot >= 0 && curSlot != gapSlot) {
						action[ActionSpace.HOTBAR_SLOT] = gapSlot + 1;
						action[ActionSpace.USE_ITEM] = 0;
					} else {
						action[ActionSpace.USE_ITEM] = gapSlot >= 0 ? 1 : 0;
					}
				}
				return;
			}

			// If spawned/stuck holding a non-weapon (Gap/Empty) during a melee macro, draw Sword on tick 0 cleanly.
			if (tick == 0 && swordSlot >= 0 && curSlot >= 0 && curSlot != swordSlot && curSlot != axeSlot) {
				action[ActionSpace.HOTBAR_SLOT] = swordSlot + 1;
				action[ActionSpace.FORWARD] = forward;
				action[ActionSpace.STRAFE] = strafe;
				action[ActionSpace.SPRINT] = sprint;
				action[ActionSpace.JUMP] = 0;
				action[ActionSpace.ATTACK] = 0;
				return;
			}
			action[ActionSpace.FORWARD] = (this == JUMP_CRIT_FORWARD && tick >= 1) ? 0 : forward;
			action[ActionSpace.STRAFE] = strafe;
			action[ActionSpace.SPRINT] = sprint;
			action[ActionSpace.JUMP] = (jump == 1 && tick == 0) ? 1 : 0;
			action[ActionSpace.ATTACK] = (this == JUMP_CRIT_FORWARD ? (falling && canHit) : (attack == 1 && canHit)) ? 1 : 0;
		}
	}

	public static final int MACRO_TICKS = 4, ROLLOUT_TICKS = 32, GAP_ROLLOUT_TICKS = 64, REPEATS = 3, MAX_KEYFRAMES = 4;
	public static final float MIN_MARGIN = 1.0f;

	public static final class Moment {
		public final Kind kind;
		public final boolean smpKit;
		public final double[] arenaState;
		public final byte[] peri, fovea;
		public final float[] vec, slots, terrain, hist, seen, hPrev;
		Moment(Kind kind, boolean smpKit, double[] state, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
			this.kind = kind; this.smpKit = smpKit; this.arenaState = state.clone();
			this.peri = obs.peri.clone(); this.fovea = obs.fovea.clone(); this.vec = obs.vec.clone();
			this.slots = obs.slots.clone(); this.terrain = obs.terrain.clone();
			this.hist = histIn.clone(); this.seen = seenIn.clone(); this.hPrev = hIn.clone();
		}
	}

	public static final class SearchJob {
		public final Moment moment;
		public final int lag;
		public final SwordNet opponent;
		private final Candidate[] cands;
		private final double[] scoreSum;
		private final int[] trials;
		private final int[] kfCount;
		private final byte[][][] kfPeri, kfFovea, kfAct;
		private final float[][][] kfVec, kfSlots, kfTerrain, kfHist, kfSeen, kfHPrev;
		private int ci, ri;

		SearchJob(Moment m, int lag, SwordNet opponent) {
			this.moment = m; this.lag = lag; this.opponent = opponent;
			this.cands = candidatesFor(m);
			int nc = cands.length;
			this.scoreSum = new double[nc];
			this.trials = new int[nc];
			this.kfCount = new int[nc];
			this.kfPeri = new byte[nc][MAX_KEYFRAMES][];
			this.kfFovea = new byte[nc][MAX_KEYFRAMES][];
			this.kfVec = new float[nc][MAX_KEYFRAMES][];
			this.kfSlots = new float[nc][MAX_KEYFRAMES][];
			this.kfTerrain = new float[nc][MAX_KEYFRAMES][];
			this.kfHist = new float[nc][MAX_KEYFRAMES][];
			this.kfSeen = new float[nc][MAX_KEYFRAMES][];
			this.kfHPrev = new float[nc][MAX_KEYFRAMES][];
			this.kfAct = new byte[nc][MAX_KEYFRAMES][ActionSpace.HEADS];
		}

		private static Candidate[] candidatesFor(Moment m) {
			if (!m.smpKit) {
				return new Candidate[] {
						Candidate.CURRENT_POLICY, Candidate.SPRINT_HIT_STRAFE_L, Candidate.SPRINT_HIT_STRAFE_R,
						Candidate.JUMP_CRIT_FORWARD, Candidate.WAIT_DEFLECT, Candidate.S_FLY_OUT, Candidate.UPPERCUT_CHASE
				};
			}
			if (m.kind == Kind.OPP_SHIELD_UP) {
				return new Candidate[] {
						Candidate.CURRENT_POLICY, Candidate.AXE_BREAK_SWORD_CRIT, Candidate.RAISE_SHIELD, Candidate.WAIT_DEFLECT
				};
			}
			if (m.kind == Kind.LOW_HP_GAP) {
				return new Candidate[] {
						Candidate.CURRENT_POLICY, Candidate.BACK_AND_EAT_GAP, Candidate.RAISE_SHIELD, Candidate.S_FLY_OUT
				};
			}
			return new Candidate[] {
					Candidate.CURRENT_POLICY, Candidate.SPRINT_HIT_STRAFE_L, Candidate.JUMP_CRIT_FORWARD,
					Candidate.WAIT_DEFLECT, Candidate.RAISE_SHIELD, Candidate.AXE_BREAK_SWORD_CRIT
			};
		}

		public int rolloutTicks() {
			return currentCandidate() == Candidate.BACK_AND_EAT_GAP ? GAP_ROLLOUT_TICKS : ROLLOUT_TICKS;
		}
		public boolean finished() { return ci >= cands.length; }
		public Candidate currentCandidate() { return cands[Math.min(ci, cands.length - 1)]; }

		public void noteFirstStep(Obs obs, float[] histIn, float[] seenIn, float[] hIn, int[] a) {
			noteStep(0, obs, histIn, seenIn, hIn, a);
		}

		/** Captures tick-0 plus multi-step SMP transition keyframes (slot switch, axe break swing, gap/shield use sustain). */
		public void noteStep(int tick, Obs obs, float[] histIn, float[] seenIn, float[] hIn, int[] a) {
			if (ci >= cands.length || ri > 0 || kfCount[ci] >= MAX_KEYFRAMES || obs == null) return;
			Candidate c = cands[ci];
			boolean take = (tick == 0)
					|| (tick > 0 && a[ActionSpace.HOTBAR_SLOT] != 0)
					|| (tick > 0 && c == Candidate.AXE_BREAK_SWORD_CRIT && a[ActionSpace.ATTACK] == 1)
					|| (a[ActionSpace.USE_ITEM] == 1 && (tick == 2 || tick == 14));
			if (!take) return;
			int k = kfCount[ci]++;
			kfPeri[ci][k] = obs.peri.clone();
			kfFovea[ci][k] = obs.fovea.clone();
			kfVec[ci][k] = obs.vec.clone();
			kfSlots[ci][k] = obs.slots.clone();
			kfTerrain[ci][k] = obs.terrain.clone();
			kfHist[ci][k] = histIn.clone();
			kfSeen[ci][k] = seenIn.clone();
			kfHPrev[ci][k] = hIn.clone();
			for (int h = 0; h < ActionSpace.HEADS; h++) kfAct[ci][k][h] = (byte) a[h];
		}

		public void finishTrial(float netHpGain, boolean koD, boolean koT) {
			scoreSum[ci] += netHpGain + (koD ? 10f : 0f) - (koT ? 10f : 0f);
			trials[ci]++;
			if (++ri >= REPEATS) { ri = 0; ci++; }
		}

		public void finishTrial(float dealt, float taken, boolean koD, boolean koT) {
			finishTrial(dealt - taken, koD, koT);
		}

		public int winningCandidate() {
			if (trials[0] == 0) return -1;
			double bestMean = scoreSum[0] / trials[0] + MIN_MARGIN; int best = -1;
			for (int i = 1; i < cands.length; i++) {
				if (trials[i] > 0 && kfCount[i] > 0) {
					double m = scoreSum[i] / trials[i];
					if (m > bestMean) { bestMean = m; best = i; }
				}
			}
			return best;
		}
		public byte[] candidateAction(int i) { return kfAct[i][0]; }
	}

	private static final int PER_KIND = 48, TARGET_CAP = 2048, BATCH = 32;
	private final Moment[][] moments = new Moment[Kind.values().length][PER_KIND];
	private final int[] mSize = new int[Kind.values().length], mNext = new int[Kind.values().length];
	private final byte[][] tPeri = new byte[TARGET_CAP][ObsSpec.PERI_SIZE], tFovea = new byte[TARGET_CAP][ObsSpec.FOVEA_SIZE];
	private final float[][] tVec = new float[TARGET_CAP][ObsSpec.VEC], tSlots = new float[TARGET_CAP][ObsSpec.SLOTS_SIZE];
	private final float[][] tTerrain = new float[TARGET_CAP][ObsSpec.TERRAIN_SIZE];
	private final float[][] tHist = new float[TARGET_CAP][ObsMemory.HIST], tSeen = new float[TARGET_CAP][ObsMemory.SEEN];
	private final float[][] tHPrev = new float[TARGET_CAP][ObsMemory.GRU_HIDDEN];
	private final byte[][] tAction = new byte[TARGET_CAP][ActionSpace.HEADS];
	private final byte[][] bPeri = new byte[BATCH][], bFovea = new byte[BATCH][], bAct = new byte[BATCH][];
	private final float[][] bVec = new float[BATCH][], bSlots = new float[BATCH][], bTerrain = new float[BATCH][];
	private final float[][] bHist = new float[BATCH][], bSeen = new float[BATCH][], bHPrev = new float[BATCH][];
	private int tSize, tNext;
	private long searchesDone, targetsFound;

	public void recordMoment(Kind kind, double[] state, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
		recordMoment(kind, false, state, obs, histIn, seenIn, hIn);
	}

	public void recordMoment(Kind kind, boolean smpKit, double[] state, Obs obs, float[] histIn, float[] seenIn, float[] hIn) {
		if (state == null) return;
		int k = kind.ordinal();
		moments[k][mNext[k]] = new Moment(kind, smpKit, state, obs, histIn, seenIn, hIn);
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

	public SearchJob startSearch(Random rng) { return startSearch(rng, 0, null); }
	public SearchJob startSearch(Random rng, int lag, SwordNet opponent) {
		Moment m = sampleMoment(rng);
		return m == null ? null : new SearchJob(m, lag, opponent);
	}

	public boolean commitSearch(SearchJob job) {
		if (job == null || !job.finished()) return false;
		searchesDone++;
		int win = job.winningCandidate();
		if (win < 0 || job.kfCount[win] <= 0) return false;
		for (int k = 0; k < job.kfCount[win]; k++) {
			int i = tNext;
			System.arraycopy(job.kfPeri[win][k], 0, tPeri[i], 0, ObsSpec.PERI_SIZE);
			System.arraycopy(job.kfFovea[win][k], 0, tFovea[i], 0, ObsSpec.FOVEA_SIZE);
			System.arraycopy(job.kfVec[win][k], 0, tVec[i], 0, ObsSpec.VEC);
			System.arraycopy(job.kfSlots[win][k], 0, tSlots[i], 0, ObsSpec.SLOTS_SIZE);
			System.arraycopy(job.kfTerrain[win][k], 0, tTerrain[i], 0, ObsSpec.TERRAIN_SIZE);
			System.arraycopy(job.kfHist[win][k], 0, tHist[i], 0, ObsMemory.HIST);
			System.arraycopy(job.kfSeen[win][k], 0, tSeen[i], 0, ObsMemory.SEEN);
			System.arraycopy(job.kfHPrev[win][k], 0, tHPrev[i], 0, ObsMemory.GRU_HIDDEN);
			System.arraycopy(job.kfAct[win][k], 0, tAction[i], 0, ActionSpace.HEADS);
			tNext = (tNext + 1) % TARGET_CAP; tSize = Math.min(tSize + 1, TARGET_CAP);
		}
		targetsFound++;
		return true;
	}

	public float trainActor(SwordNet actor, LeaguePpo ppo, int steps, float lr, Random rng) {
		if (tSize < 8) return 0f;
		int n = Math.min(BATCH, tSize); float ceSum = 0f;
		for (int s = 0; s < steps; s++) {
			for (int i = 0; i < n; i++) {
				int idx = rng.nextInt(tSize);
				bPeri[i] = tPeri[idx]; bFovea[i] = tFovea[idx]; bVec[i] = tVec[idx];
				bSlots[i] = tSlots[idx]; bTerrain[i] = tTerrain[idx];
				bHist[i] = tHist[idx]; bSeen[i] = tSeen[idx]; bHPrev[i] = tHPrev[idx]; bAct[i] = tAction[idx];
			}
			ceSum += ppo.supervisedStep(actor, bPeri, bFovea, bVec, bSlots, bTerrain, bHist, bSeen, bHPrev, bAct, n, lr, SUPERVISE_HEADS);
		}
		return ceSum / steps;
	}
}
