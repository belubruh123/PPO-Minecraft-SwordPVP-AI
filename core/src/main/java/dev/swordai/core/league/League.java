package dev.swordai.core.league;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** AlphaStar-style opponent league: 50% latest self, 30% PFSP snapshots, 20% varied (humans main, exploiter, tiny SparBot). */
public final class League {
	public enum OpponentKind { LATEST_SELF, PAST_SNAPSHOT, EXPLOITER, HUMAN_CLONE, SPAR_BOT }
	public enum SparStyle { CRIT_SPAMMER, JUMP_IN_CRITTER, COMBO_PLAYER, MIXER }

	public static final class Snapshot {
		public final String id;
		public final SwordNet net;
		public float winRate = 0.5f;
		public int games;
		Snapshot(String id, SwordNet net) { this.id = id; this.net = net; }
	}

	public static final class Match {
		public final OpponentKind kind;
		public final String label;
		public final Snapshot snapshot;
		public final HumanImitation.HumanPolicy human;
		public final SparStyle sparStyle;
		public final MoveSearch.Moment startMoment;
		Match(OpponentKind k, String l, Snapshot s, HumanImitation.HumanPolicy h, SparStyle sp, MoveSearch.Moment m) {
			kind = k; label = l; snapshot = s; human = h; sparStyle = sp; startMoment = m;
		}
	}

	public static final int MAX_SNAPSHOTS = 24;
	public static final float SELF_SHARE = 0.45f, SNAPSHOT_SHARE = 0.25f, KEY_MOMENT_START_SHARE = 0.25f;
	private static final float WR_ALPHA = 0.05f;

	private final List<Snapshot> snapshots = new ArrayList<>();
	private final List<HumanImitation.HumanPolicy> humans = new ArrayList<>();
	private final float[] sparWr = {0.5f, 0.5f, 0.5f, 0.5f};
	private float selfWr = 0.5f, exploiterWr = 0.5f, humanWr = 0.5f, overallWr = 0.5f;
	private long totalRounds;

	public void setHumanOpponents(List<HumanImitation.HumanPolicy> list) {
		humans.clear();
		if (list != null) humans.addAll(list);
	}

	public int addSnapshot(String id, SwordNet current) {
		SwordNet copy = new SwordNet(snapshots.size() + 101L);
		LeagueLearner.copyWeights(current, copy);
		copy.gruEnabled = current.gruEnabled;
		Snapshot snap = new Snapshot(id, copy);
		if (snapshots.size() < MAX_SNAPSHOTS) { snapshots.add(snap); return snapshots.size() - 1; }
		int easiest = 0;
		for (int i = 1; i < snapshots.size(); i++) if (snapshots.get(i).winRate > snapshots.get(easiest).winRate) easiest = i;
		snapshots.set(easiest, snap);
		return easiest;
	}

	public float overallWinRate() { return overallWr; }
	public int snapshotCount() { return snapshots.size(); }

	/** Persists a single updated PFSP snapshot slot + pool metadata so multi-week runs never lose frozen opponents across restarts. */
	public void saveSnapshotSlot(Path dir, int idx) throws IOException {
		if (idx < 0 || idx >= snapshots.size()) return;
		Files.createDirectories(dir);
		LeagueLearner.saveActorOnly(snapshots.get(idx).net, dir.resolve(String.format(Locale.ROOT, "snap-%02d.bin", idx)));
		savePoolMeta(dir);
	}

	public void savePool(Path dir) throws IOException {
		Files.createDirectories(dir);
		for (int i = 0; i < snapshots.size(); i++) {
			LeagueLearner.saveActorOnly(snapshots.get(i).net, dir.resolve(String.format(Locale.ROOT, "snap-%02d.bin", i)));
		}
		savePoolMeta(dir);
	}

	public void savePoolMeta(Path dir) throws IOException {
		Files.createDirectories(dir);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < snapshots.size(); i++) {
			Snapshot s = snapshots.get(i);
			String cleanId = s.id.replace('\t', '_').replace('\n', '_').replace('\r', '_');
			sb.append(String.format(Locale.ROOT, "%d\t%s\t%.5f\t%d\t%d%n", i, cleanId, s.winRate, s.games, s.net.gruEnabled ? 1 : 0));
		}
		Path tmp = dir.resolve("meta.txt.tmp"), meta = dir.resolve("meta.txt");
		Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
		Files.move(tmp, meta, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public int loadPool(Path dir) throws IOException {
		Path meta = dir.resolve("meta.txt");
		if (!Files.exists(meta)) return 0;
		snapshots.clear();
		for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
			if (line.isBlank() || snapshots.size() >= MAX_SNAPSHOTS) continue;
			String[] parts = line.split("\t");
			if (parts.length < 4) continue;
			try {
				int idx = Integer.parseInt(parts[0].trim());
				Path f = dir.resolve(String.format(Locale.ROOT, "snap-%02d.bin", idx));
				if (!Files.exists(f)) continue;
				SwordNet net = LeagueLearner.loadActorOnly(f);
				net.gruEnabled = parts.length >= 5 ? "1".equals(parts[4].trim()) : net.gruWeightNorm() > 0f;
				Snapshot s = new Snapshot(parts[1].trim(), net);
				s.winRate = Float.parseFloat(parts[2].trim());
				s.games = Integer.parseInt(parts[3].trim());
				snapshots.add(s);
			} catch (NumberFormatException | IOException ignored) {}
		}
		return snapshots.size();
	}

	public Match sampleMatch(Random rng, MoveSearch moments, boolean hasExploiter) {
		MoveSearch.Moment start = (moments != null && moments.totalMoments() > 0 && rng.nextFloat() < KEY_MOMENT_START_SHARE)
				? moments.sampleMoment(rng) : null;
		float u = rng.nextFloat();
		if (u < SELF_SHARE) return new Match(OpponentKind.LATEST_SELF, "self-latest", null, null, null, start);
		if (u < SELF_SHARE + SNAPSHOT_SHARE && !snapshots.isEmpty()) {
			Snapshot s = samplePfsp(rng);
			return new Match(OpponentKind.PAST_SNAPSHOT, "snap-" + s.id, s, null, null, start);
		}
		float v = rng.nextFloat();
		if (!humans.isEmpty() && v < 0.55f) {
			HumanImitation.HumanPolicy h = humans.get(rng.nextInt(humans.size()));
			return new Match(OpponentKind.HUMAN_CLONE, "human-" + h.name, null, h, null, start);
		}
		if (hasExploiter && (humans.isEmpty() ? v < 0.833f : v < 0.85f)) {
			return new Match(OpponentKind.EXPLOITER, "exploiter", null, null, null, start);
		}
		SparStyle st = sampleSpar(rng);
		return new Match(OpponentKind.SPAR_BOT, "spar-" + st.name().toLowerCase(), null, null, st, start);
	}

	public void recordOutcome(Match m, boolean won) {
		float y = won ? 1f : 0f;
		totalRounds++; overallWr += WR_ALPHA * (y - overallWr);
		switch (m.kind) {
			case LATEST_SELF -> selfWr += WR_ALPHA * (y - selfWr);
			case PAST_SNAPSHOT -> { if (m.snapshot != null) { m.snapshot.games++; m.snapshot.winRate += WR_ALPHA * (y - m.snapshot.winRate); } }
			case EXPLOITER -> exploiterWr += WR_ALPHA * (y - exploiterWr);
			case SPAR_BOT -> { if (m.sparStyle != null) { int i = m.sparStyle.ordinal(); sparWr[i] += WR_ALPHA * (y - sparWr[i]); } }
			case HUMAN_CLONE -> humanWr += WR_ALPHA * (y - humanWr);
		}
	}

	/** Exploiter's own EMA win rate against Main (1 - Main's win rate vs Exploiter). */
	public float exploiterWinRate() { return 1f - exploiterWr; }
	public void resetExploiterStats() { exploiterWr = 0.5f; }
	/** Human-KL RL challenger's own EMA win rate against Main. */
	public float humanChallengerWinRate() { return 1f - humanWr; }
	public void resetHumanChallengerStats() { humanWr = 0.5f; }
	/** Returns a random frozen snapshot from PFSP (for strong Exploiter resets), or null if empty. */
	public SwordNet randomSnapshot(Random rng) { return snapshots.isEmpty() ? null : snapshots.get(rng.nextInt(snapshots.size())).net; }

	/** Hardest frozen Human-KL RL challengers in the PFSP pool (lowest Main win rate first), for honest evaluation. */
	public List<Snapshot> hardestHumanSnapshots(int maxCount) {
		List<Snapshot> out = new ArrayList<>();
		for (Snapshot s : snapshots) if (s.id.startsWith("hum-")) out.add(s);
		out.sort(Comparator.comparingDouble(s -> s.winRate));
		return out.size() <= maxCount ? out : out.subList(0, maxCount);
	}

	public float demoWeight() {
		float progress = Math.max(0f, Math.min(1f, (overallWr - 0.45f) / 0.30f));
		float fade = Math.max(progress, 0.5f * Math.min(1f, totalRounds / 4000f));
		return 0.35f * (1f - fade) + 0.02f * fade;
	}

	private Snapshot samplePfsp(Random rng) {
		double sum = 0; double[] w = new double[snapshots.size()];
		for (int i = 0; i < w.length; i++) { double l = 1.0 - snapshots.get(i).winRate; w[i] = l * l + 0.05; sum += w[i]; }
		double r = rng.nextDouble() * sum, acc = 0;
		for (int i = 0; i < w.length; i++) { acc += w[i]; if (r <= acc) return snapshots.get(i); }
		return snapshots.get(snapshots.size() - 1);
	}

	private SparStyle sampleSpar(Random rng) {
		SparStyle[] all = SparStyle.values(); double sum = 0; double[] w = new double[all.length];
		for (int i = 0; i < all.length; i++) { double l = 1.0 - sparWr[i]; w[i] = l * l + 0.10; sum += w[i]; }
		double r = rng.nextDouble() * sum, acc = 0;
		for (int i = 0; i < all.length; i++) { acc += w[i]; if (r <= acc) return all[i]; }
		return all[all.length - 1];
	}

	public String summary() {
		return String.format(Locale.ROOT, "league r=%d wr=%.0f%% (self=%.0f%% exp=%.0f%% hum=%.0f%% snaps=%d humans=%d)",
				totalRounds, overallWr * 100f, selfWr * 100f, exploiterWr * 100f, humanWr * 100f, snapshots.size(), humans.size());
	}
}
