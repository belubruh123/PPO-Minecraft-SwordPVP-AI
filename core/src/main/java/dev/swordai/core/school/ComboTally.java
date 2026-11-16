package dev.swordai.core.school;

import dev.swordai.core.pvp.Events;

import java.util.Locale;

/**
 * Run 123 (agent 57), counters for its own combos as the evidence counted them in the user's real rounds: a combo is its hits in a row
 * with no hit taken between (a trade ends it); its hits by kind and distance (eye to their box when the swing was made): sprint hits
 * under {@link #NEAR}, {@link #NEAR} to {@link #EDGE}, {@link #EDGE}+, crits, other hits; and after each of its hits, which came next
 * within {@link #NEXT_WITHIN} ticks: its own hit or theirs (their hit on the same tick, a trade, counts as theirs next). The real rounds
 * (mostly run35): 2+ 44, 3+ 18, 5+ 5, 10+ 1, mean of 2+ 3.0; their hit next after its sprint hit under 2.0 72% (110), 2.0-2.6 54%
 * (103), 2.6+ 15% (26), after its crits 62-76%. Fed by SchoolSession (training, and each side of a check) and RoundRecorder (the user's
 * real rounds). Not thread-safe.
 */
public final class ComboTally {
	/** The bands: sprint hits near / mid / at the edge, crits, other hits. */
	public static final int SPRINT_NEAR = 0;
	public static final int SPRINT_MID = 1;
	public static final int SPRINT_EDGE = 2;
	public static final int CRIT = 3;
	public static final int OTHER = 4;
	public static final int BANDS = 5;
	public static final String[] BAND_NAMES = {"sprint <2.0", "sprint 2.0-2.6", "sprint 2.6+", "crit", "other"};
	public static final double NEAR = 2.0;
	public static final double EDGE = 2.6;
	/** The evidence's window: "followed within 20 ticks by another hit" (SchoolSession.COMBO_GAP). */
	public static final int NEXT_WITHIN = 20;

	/** Its combos (runs of 2+) reaching 2, 3, 5, 10 hits; the longest; the sum of their lengths; those ended by their hit. */
	private int c2, c3, c5, c10, longest, endedByTheirs;
	private long sum2;
	private int run;
	/** Its hits by band; of those, followed within NEXT_WITHIN by a hit at all, and by theirs. */
	private final int[] hits = new int[BANDS];
	private final int[] followed = new int[BANDS];
	private final int[] theirsNext = new int[BANDS];
	/** The last hit's band waiting for what comes next (-1 none), and its age. */
	private int pending = -1;
	private int pendingAge;

	/** The band of a hit of this kind (Events.SPRINT, CRIT, PLAIN) from this far. */
	public static int band(int kind, double dist) {
		if (kind == Events.SPRINT) {
			return dist < NEAR ? SPRINT_NEAR : dist < EDGE ? SPRINT_MID : SPRINT_EDGE;
		}
		return kind == Events.CRIT ? CRIT : OTHER;
	}

	/** A tick went by (call once a tick, before its events). */
	public void tick() {
		if (pending >= 0 && ++pendingAge > NEXT_WITHIN) {
			pending = -1;
		}
	}

	/** Its hit landed: of this kind (Events.SPRINT, CRIT, PLAIN), from this far (eye to their box at the swing). */
	public void ownHit(int kind, double dist) {
		if (pending >= 0) {
			followed[pending]++;
		}
		int b = band(kind, dist);
		hits[b]++;
		pending = b;
		pendingAge = 0;
		run++;
	}

	/** Their hit landed (after its own on the same tick, for a trade). */
	public void theirHit() {
		if (pending >= 0) {
			followed[pending]++;
			theirsNext[pending]++;
			pending = -1;
		}
		closeRun(true);
	}

	/** The fight (or round) is over: its combo still running is counted, no hit comes next. */
	public void endFight() {
		pending = -1;
		closeRun(false);
	}

	private void closeRun(boolean byTheirs) {
		if (run >= 2) {
			c2++;
			c3 += run >= 3 ? 1 : 0;
			c5 += run >= 5 ? 1 : 0;
			c10 += run >= 10 ? 1 : 0;
			sum2 += run;
			endedByTheirs += byTheirs ? 1 : 0;
		}
		longest = Math.max(longest, run);
		run = 0;
	}

	public int combos(int least) {
		return least >= 10 ? c10 : least >= 5 ? c5 : least >= 3 ? c3 : c2;
	}

	public int longest() {
		return longest;
	}

	/** The mean length of its combos of 2+ (0 with none). */
	public double mean() {
		return c2 == 0 ? 0.0 : sum2 / (double) c2;
	}

	public int hits(int band) {
		return hits[band];
	}

	public int followed(int band) {
		return followed[band];
	}

	public int theirsNext(int band) {
		return theirsNext[band];
	}

	/** Of its sprint hits, the share from EDGE or farther (0 with none). */
	public double edgeShare() {
		int all = hits[SPRINT_NEAR] + hits[SPRINT_MID] + hits[SPRINT_EDGE];
		return all == 0 ? 0.0 : hits[SPRINT_EDGE] / (double) all;
	}

	/**
	 * "combos 2+ 12, 3+ 5, 5+ 1, 10+ 0, longest 6, mean 2.9, ended by their hit 9; its hits (their hit next of those followed within 20):
	 * sprint <2.0 10 (6/8), ...; sprint hits from 2.6+ 31%".
	 */
	public String line() {
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "combos 2+ %d, 3+ %d, 5+ %d, 10+ %d, longest %d, mean %.1f, ended by their hit %d;"
				+ " its hits (their hit next / followed within %d):", c2, c3, c5, c10, longest, mean(), endedByTheirs, NEXT_WITHIN));
		for (int i = 0; i < BANDS; i++) {
			b.append(String.format(Locale.ROOT, " %s %d (%d/%d%s)%s", BAND_NAMES[i], hits[i], theirsNext[i], followed[i],
					followed[i] > 0 ? String.format(Locale.ROOT, ", %.0f%%", 100.0 * theirsNext[i] / followed[i]) : "", i + 1 < BANDS ? "," : ""));
		}
		b.append(String.format(Locale.ROOT, "; sprint hits from %.1f+ %.0f%%", EDGE, 100.0 * edgeShare()));
		return b.toString();
	}

	/** Counts start again (a running combo and a pending hit are kept). */
	public void clear() {
		c2 = c3 = c5 = c10 = longest = endedByTheirs = 0;
		sum2 = 0;
		java.util.Arrays.fill(hits, 0);
		java.util.Arrays.fill(followed, 0);
		java.util.Arrays.fill(theirsNext, 0);
	}
}
