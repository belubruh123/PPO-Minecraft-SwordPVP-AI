package dev.swordai.core.league;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AlphaStar-style opponent league (Step B + Follow-up #1):
 * <ul>
 *   <li><b>50% Latest self</b>: current {@link SwordNet} weights fighting itself on {@code ReplicaBody}.</li>
 *   <li><b>30% Past snapshots (PFSP)</b>: Prioritized Fictitious Self-Play over frozen checkpoints,
 *       sampling proportional to {@code (1 - winRate)^2 + 0.05} so hard past selves are revisited.</li>
 *   <li><b>20% Varied opponents</b>: co-evolving learned <b>Exploiter</b> hunting weaknesses,
 *       behaviour-cloned <b>Real Human</b> opponents ({@link HumanImitation.HumanPolicy}) from lost
 *       duels, and varied full-fight sparring styles ({@link SparStyle}: crit spammer, jump-in
 *       critter, combo player, mixer).</li>
 * </ul>
 * Also drives key-moment start states (25% of fights) and fading kickstarting weight.
 */
public final class League {
	public enum OpponentKind {
		LATEST_SELF,
		PAST_SNAPSHOT,
		EXPLOITER,
		HUMAN_CLONE,
		SPAR_BOT
	}

	/** Full-fight opponent archetypes (never single-technique scripted bots). */
	public enum SparStyle {
		CRIT_SPAMMER,
		JUMP_IN_CRITTER,
		COMBO_PLAYER,
		MIXER
	}

	public static final class Snapshot {
		public final String id;
		public final SwordNet net;
		public float winRate = 0.5f;
		public int games;

		Snapshot(String id, SwordNet net) {
			this.id = id;
			this.net = net;
		}
	}

	public static final class Match {
		public final OpponentKind kind;
		public final String label;
		public final Snapshot snapshot;
		public final HumanImitation.HumanPolicy human;
		public final SparStyle sparStyle;
		public final MoveSearch.Moment startMoment;

		Match(OpponentKind kind, String label, Snapshot snapshot, HumanImitation.HumanPolicy human,
				SparStyle sparStyle, MoveSearch.Moment startMoment) {
			this.kind = kind;
			this.label = label;
			this.snapshot = snapshot;
			this.human = human;
			this.sparStyle = sparStyle;
			this.startMoment = startMoment;
		}
	}

	public static final int MAX_SNAPSHOTS = 12;
	public static final float SELF_SHARE = 0.50f;
	public static final float SNAPSHOT_SHARE = 0.30f;
	public static final float KEY_MOMENT_START_SHARE = 0.25f;
	private static final float WR_ALPHA = 0.05f;

	private final List<Snapshot> snapshots = new ArrayList<>();
	private final List<HumanImitation.HumanPolicy> humans = new ArrayList<>();
	private final float[] sparWr = {0.5f, 0.5f, 0.5f, 0.5f};
	private float selfWr = 0.5f;
	private float exploiterWr = 0.5f;
	private float overallWr = 0.5f;
	private long totalRounds;

	public void setHumanOpponents(List<HumanImitation.HumanPolicy> list) {
		humans.clear();
		if (list != null) {
			humans.addAll(list);
		}
	}

	public int snapshotCount() {
		return snapshots.size();
	}

	public int humanCount() {
		return humans.size();
	}

	public float overallWinRate() {
		return overallWr;
	}

	public float exploiterWinRate() {
		return 1f - exploiterWr;
	}

	/** Adds a frozen copy of {@code current} into the PFSP snapshot pool. */
	public void addSnapshot(String id, SwordNet current) {
		SwordNet copy = new SwordNet(snapshots.size() + 101L);
		LeagueLearner.copyWeights(current, copy);
		Snapshot snap = new Snapshot(id, copy);
		if (snapshots.size() < MAX_SNAPSHOTS) {
			snapshots.add(snap);
			return;
		}
		// Replace the easiest (highest win-rate) old snapshot so the pool stays sharp.
		int easiest = 0;
		for (int i = 1; i < snapshots.size(); i++) {
			if (snapshots.get(i).winRate > snapshots.get(easiest).winRate) {
				easiest = i;
			}
		}
		snapshots.set(easiest, snap);
	}

	/** Samples the next training match according to the 50/30/20 league schedule. */
	public Match sampleMatch(Random rng, MoveSearch moments, boolean hasExploiter) {
		MoveSearch.Moment start = (moments != null && moments.totalMoments() > 0
				&& rng.nextFloat() < KEY_MOMENT_START_SHARE)
				? moments.sampleMoment(rng) : null;

		float u = rng.nextFloat();
		if (u < SELF_SHARE) {
			return new Match(OpponentKind.LATEST_SELF, "self-latest", null, null, null, start);
		}
		if (u < SELF_SHARE + SNAPSHOT_SHARE && !snapshots.isEmpty()) {
			Snapshot s = samplePfsp(rng);
			return new Match(OpponentKind.PAST_SNAPSHOT, "snap-" + s.id, s, null, null, start);
		}
		// Remaining 20% varied opponents: real-human clones are the main non-self opponents (60% of
		// this bucket = 12% overall), learned Exploiter 30% (6% overall), archetype SparBot only 10% (2%).
		float v = rng.nextFloat();
		if (!humans.isEmpty() && v < 0.60f) {
			HumanImitation.HumanPolicy h = humans.get(rng.nextInt(humans.size()));
			return new Match(OpponentKind.HUMAN_CLONE, "human-" + h.name, null, h, null, start);
		}
		if (hasExploiter && (humans.isEmpty() ? v < 0.75f : v < 0.90f)) {
			return new Match(OpponentKind.EXPLOITER, "exploiter", null, null, null, start);
		}
		SparStyle style = sampleSparStyle(rng);
		return new Match(OpponentKind.SPAR_BOT, "spar-" + style.name().toLowerCase(), null, null, style, start);
	}

	/** Records a completed round outcome for PFSP weighting and kickstarting fade. */
	public void recordOutcome(Match match, boolean mainAgentWon) {
		float y = mainAgentWon ? 1f : 0f;
		totalRounds++;
		overallWr += WR_ALPHA * (y - overallWr);
		switch (match.kind) {
			case LATEST_SELF -> selfWr += WR_ALPHA * (y - selfWr);
			case PAST_SNAPSHOT -> {
				if (match.snapshot != null) {
					match.snapshot.games++;
					match.snapshot.winRate += WR_ALPHA * (y - match.snapshot.winRate);
				}
			}
			case EXPLOITER -> exploiterWr += WR_ALPHA * (y - exploiterWr);
			case SPAR_BOT -> {
				if (match.sparStyle != null) {
					int i = match.sparStyle.ordinal();
					sparWr[i] += WR_ALPHA * (y - sparWr[i]);
				}
			}
			case HUMAN_CLONE -> { }
		}
	}

	/**
	 * Fading demonstration / kickstarting weight (Schmitt et al. 2018):
	 * strong early ({@code 0.35}) so rare skills (combos, double crits, crit-back chains) are
	 * discovered quickly, fading smoothly toward {@code 0.02} as league win rate rises.
	 */
	public float demoWeight() {
		float progress = Math.max(0f, Math.min(1f, (overallWr - 0.45f) / 0.30f));
		float roundFade = Math.max(0f, Math.min(1f, totalRounds / 4000f));
		float fade = Math.max(progress, 0.5f * roundFade);
		return 0.35f * (1f - fade) + 0.02f * fade;
	}

	private Snapshot samplePfsp(Random rng) {
		double sum = 0;
		double[] w = new double[snapshots.size()];
		for (int i = 0; i < snapshots.size(); i++) {
			double loss = 1.0 - snapshots.get(i).winRate;
			w[i] = loss * loss + 0.05;
			sum += w[i];
		}
		double r = rng.nextDouble() * sum;
		double acc = 0;
		for (int i = 0; i < snapshots.size(); i++) {
			acc += w[i];
			if (r <= acc) {
				return snapshots.get(i);
			}
		}
		return snapshots.get(snapshots.size() - 1);
	}

	private SparStyle sampleSparStyle(Random rng) {
		SparStyle[] all = SparStyle.values();
		double sum = 0;
		double[] w = new double[all.length];
		for (int i = 0; i < all.length; i++) {
			double loss = 1.0 - sparWr[i];
			w[i] = loss * loss + 0.10;
			sum += w[i];
		}
		double r = rng.nextDouble() * sum;
		double acc = 0;
		for (int i = 0; i < all.length; i++) {
			acc += w[i];
			if (r <= acc) {
				return all[i];
			}
		}
		return all[all.length - 1];
	}

	public String summary() {
		return String.format(java.util.Locale.ROOT,
				"league rounds=%d wr=%.0f%% (self=%.0f%% vsExp=%.0f%% snaps=%d humans=%d demoW=%.2f)",
				totalRounds, overallWr * 100f, selfWr * 100f, exploiterWr * 100f,
				snapshots.size(), humans.size(), demoWeight());
	}
}
