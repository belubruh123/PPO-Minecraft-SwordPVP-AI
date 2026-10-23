package dev.swordai.core.pvp;

import java.util.Arrays;

/**
 * What one fighter has learned of the other in this fight: how often the other has done each thing the
 * techniques file says to watch for (part 8, "cues the agent can read"), and the last few exchanges.
 * A person predicts from exactly this ("every technique wins only if you predicted what they would
 * do"); the network gets it as inputs ({@link #write}), and the adapting bot turns to the counters of
 * whatever the other does most ({@link PvpPlayer}).
 *
 * <p>Read only from what the watching fighter can see: its own body, the other's body as it moves,
 * and the hits either way. Each habit is a running rate, successes over chances, both fading with a
 * time constant of {@link #FADE_TICKS} (about twenty seconds), so a change of style shows within a
 * fight.
 */
public final class Habits {
	public static final int JUMP_RESET = 0;
	public static final int PUNISH_CRIT = 1;
	public static final int BACK_OUT = 2;
	public static final int DEFLECT = 3;
	public static final int DOUBLE_CRIT = 4;
	public static final int STRAIGHT_IN = 5;
	public static final int JUMP_IN = 6;
	public static final int HOP = 7;
	public static final int MISS = 8;
	public static final int STUN = 9;
	public static final int LAND_HIT = 10;
	public static final int SPRINT_HITS = 11;
	public static final int COUNT = 12;
	public static final String[] NAMES = {"jump reset (T9)", "punish crit (B1)", "backs out with S (C3)", "deflects (T4)",
			"double crit (T1)", "straight in (T7)", "jumps in (T8/T11a)", "hops (T11b)", "misses (T6)", "its hits stun (T3)",
			"hits as I land (T9a)", "sprint hits"};
	/** What a habit is taken to be before anything has been seen of it. */
	private static final float[] PRIOR = {0.15f, 0.25f, 0.3f, 0.2f, 0.15f, 0.3f, 0.2f, 0.05f, 0.2f, 0.4f, 0.2f, 0.5f};

	public static final float FADE_TICKS = 400f;
	private static final float FADE = (float) Math.exp(-1.0 / FADE_TICKS);

	/** Exchanges remembered, newest first, and the numbers kept of each. */
	public static final int EXCHANGES = 6;
	public static final int PER_EXCHANGE = 8;
	/** Ticks after a hit in which the other side's answer to it is watched. */
	static final int ANSWER_TICKS = 10;
	/** An exchange fades out of the table over this many ticks. */
	static final float EXCHANGE_FADE = 100f;

	/** Inputs written by {@link #write}: each habit's rate and how much it rests on, two rhythm numbers, combos either way, the table. */
	public static final int SIZE = 2 * COUNT + 2 + 2 + EXCHANGES * PER_EXCHANGE;

	// Jump-reset judgement: after a knockback hit of mine on them standing, how fast they go away 2 ticks on.
	/** Slower than this away from me two ticks after my hit on them standing: the knockback was taken with a jump. */
	public static final double RESET_AWAY = 0.55;

	private final float[] chances = new float[COUNT];
	private final float[] successes = new float[COUNT];
	/** The same, not faded: every chance seen this fight and how often it was taken. For the logs. */
	private final int[] seen = new int[COUNT];
	private final int[] did = new int[COUNT];

	private int tick;
	// What is being watched now.
	private int myHitAt = -1000;
	private boolean myHitOnGround;
	private int myHitKind;
	private boolean resetJudged = true;
	private boolean punishWatch;
	private boolean backJudged = true;
	private double backAway;
	private int backSamples;
	private int mySwingAt = -1000;
	private boolean deflectWatch;
	private int theirCritAt = -1000;
	private boolean doubleWatch;
	private int theirSwingAt = -1000;
	private boolean missWatch;
	private int theirSprintHitAt = -1000;
	private boolean stunWatch;
	private int theirHitAt = -1000;
	private int theirHitsInRow;
	private int myHitsInRow;
	private int iLandedAt = -1000;
	private int theyLeftGroundAt = -1000;
	private double theyLeftGroundReach;
	private boolean theyWereOut = true;
	private int inReachTicks;
	private boolean theyWereOnGround = true;
	private boolean iWasOnGround = true;
	/** Rhythm of their combos: mean ticks between their hits in a row, and its spread. */
	private float rhythmMean = 0.5f;
	private float rhythmSpread = 0.5f;

	// The exchanges: newest at index 0.
	private final float[][] table = new float[EXCHANGES][PER_EXCHANGE];
	private final int[] tableAt = new int[EXCHANGES];
	private final boolean[] tableMine = new boolean[EXCHANGES];
	private int filled;

	public void reset() {
		Arrays.fill(chances, 0f);
		Arrays.fill(successes, 0f);
		Arrays.fill(seen, 0);
		Arrays.fill(did, 0);
		tick = 0;
		myHitAt = -1000;
		resetJudged = true;
		punishWatch = false;
		backJudged = true;
		mySwingAt = -1000;
		deflectWatch = false;
		theirCritAt = -1000;
		doubleWatch = false;
		theirSwingAt = -1000;
		missWatch = false;
		theirSprintHitAt = -1000;
		stunWatch = false;
		theirHitAt = -1000;
		theirHitsInRow = 0;
		myHitsInRow = 0;
		iLandedAt = -1000;
		theyLeftGroundAt = -1000;
		theyWereOut = true;
		inReachTicks = 0;
		theyWereOnGround = true;
		iWasOnGround = true;
		rhythmMean = 0.5f;
		rhythmSpread = 0.5f;
		for (float[] row : table) {
			Arrays.fill(row, 0f);
		}
		Arrays.fill(tableAt, -1000);
		filled = 0;
	}

	public Habits() {
		reset();
	}

	/** How often the other does this, 0 to 1, leaning on {@link #PRIOR} while little has been seen. */
	public float rate(int habit) {
		return (successes[habit] + PRIOR[habit]) / (chances[habit] + 1f);
	}

	/** How much {@link #rate} rests on: 0 for nothing seen, towards 1 with a few chances. */
	public float evidence(int habit) {
		return 1f - (float) Math.exp(-chances[habit] / 3f);
	}

	/** Chances counted for a habit so far (faded). */
	public float chances(int habit) {
		return chances[habit];
	}

	/** Hits they have landed in a row without one of mine between. */
	public int theirCombo() {
		return theirHitsInRow;
	}

	public int myCombo() {
		return myHitsInRow;
	}

	private void chance(int habit, boolean success) {
		chances[habit] += 1f;
		seen[habit]++;
		if (success) {
			successes[habit] += 1f;
			did[habit]++;
		}
	}

	/** Chances of {@code habit} seen this fight, not faded. */
	public int seen(int habit) {
		return seen[habit];
	}

	/** And how many of them were taken. */
	public int did(int habit) {
		return did[habit];
	}

	/**
	 * One tick, after it has happened: the fight as this fighter sees it now, and what happened on the tick.
	 * Call once a tick, in order.
	 */
	public void observe(Duel d, Events e) {
		tick++;
		for (int h = 0; h < COUNT; h++) {
			chances[h] *= FADE;
			successes[h] *= FADE;
		}
		Body them = d.them;
		Body me = d.me;
		double away = -d.theyClose;
		boolean theyTookOff = theyWereOnGround && !them.onGround && them.vy > 0.3;
		if (theyTookOff) {
			theyLeftGroundAt = tick;
			theyLeftGroundReach = d.reach;
		}
		if (!iWasOnGround && me.onGround) {
			iLandedAt = tick;
		}

		// ---- my hits ----
		if (e.iHit != Events.NONE) {
			myHitAt = tick;
			myHitKind = e.iHit;
			myHitOnGround = theyWereOnGround;
			resetJudged = !(myHitOnGround && e.iHit == Events.SPRINT);
			punishWatch = myHitOnGround;
			backJudged = false;
			backAway = 0;
			backSamples = 0;
			myHitsInRow++;
			theirHitsInRow = 0;
			stunWatch = false;
			addExchange(true, e.iHit);
		}
		int sinceMyHit = tick - myHitAt;
		if (!resetJudged && sinceMyHit == 2) {
			// A jump with the hit takes most of the knockback away: they are still close and slow.
			chance(JUMP_RESET, away < RESET_AWAY);
			resetJudged = true;
		}
		if (punishWatch) {
			if (e.theyHit == Events.CRIT && sinceMyHit <= 18) {
				chance(PUNISH_CRIT, true);
				punishWatch = false;
			} else if (sinceMyHit > 3 && them.onGround || sinceMyHit > 18 || e.theyHit != Events.NONE) {
				chance(PUNISH_CRIT, false);
				punishWatch = false;
			}
		}
		if (!backJudged && sinceMyHit >= 6 && sinceMyHit <= 12) {
			backAway += away;
			backSamples++;
			if (sinceMyHit == 12 || e.theyHit != Events.NONE) {
				chance(BACK_OUT, backAway / Math.max(1, backSamples) > 0.15);
				backJudged = true;
			}
		}

		// ---- my swings: are they answered with a sprint hit at once ----
		if (e.iSwung && d.reach < 4.5) {
			if (deflectWatch) {
				chance(DEFLECT, false);
			}
			mySwingAt = tick;
			deflectWatch = true;
		}
		if (deflectWatch) {
			int since = tick - mySwingAt;
			if (e.theyHit == Events.SPRINT && since >= 1 && since <= 9) {
				chance(DEFLECT, true);
				deflectWatch = false;
			} else if (since > 9) {
				chance(DEFLECT, false);
				deflectWatch = false;
			}
		}

		// ---- their hits ----
		if (e.theyHit != Events.NONE) {
			if (theirHitsInRow > 0 && tick - theirHitAt < 30) {
				float gap = Math.min(1f, (tick - theirHitAt) / 20f);
				float diff = Math.abs(gap - rhythmMean);
				rhythmMean += 0.3f * (gap - rhythmMean);
				rhythmSpread += 0.3f * (diff - rhythmSpread);
				chance(LAND_HIT, tick - iLandedAt <= 2);
			}
			theirHitAt = tick;
			theirHitsInRow++;
			myHitsInRow = 0;
			chance(SPRINT_HITS, e.theyHit == Events.SPRINT);
			if (doubleWatch) {
				chance(DOUBLE_CRIT, e.theyHit == Events.CRIT && tick - theirCritAt <= 24);
				doubleWatch = false;
			}
			if (e.theyHit == Events.CRIT) {
				theirCritAt = tick;
				doubleWatch = true;
			}
			if (e.theyHit == Events.SPRINT) {
				theirSprintHitAt = tick;
				stunWatch = true;
			}
			missWatch = false;
			addExchange(false, e.theyHit);
		}
		if (doubleWatch && tick - theirCritAt > 24) {
			chance(DOUBLE_CRIT, false);
			doubleWatch = false;
		}
		if (stunWatch && tick - theirSprintHitAt == 6) {
			// Sent out of their reach by it: a stun.
			chance(STUN, d.theirReach > Duel.REACH + 0.3);
			stunWatch = false;
		}

		// ---- their swings: do they miss ----
		if (missWatch && tick - theirSwingAt > 3) {
			chance(MISS, true);
			missWatch = false;
		}
		if (e.theySwung && d.flat < 5.0 && e.theyHit == Events.NONE) {
			theirSwingAt = tick;
			missWatch = true;
		}

		// ---- how they come into reach ----
		boolean out = d.reach > Duel.REACH + 0.6;
		if (theyWereOut && !out && d.theyClose > 0.05) {
			boolean jumped = tick - theyLeftGroundAt <= 14 && theyLeftGroundReach > 2.8;
			chance(JUMP_IN, jumped);
			boolean straight = !jumped && tick - theyLeftGroundAt > 15 && d.theyClose > 0.18 && d.theySideways < 0.08;
			chance(STRAIGHT_IN, straight);
		}
		theyWereOut = out;
		if (d.reach < 4.5) {
			if (++inReachTicks % 10 == 0) {
				chance(HOP, tick - theyLeftGroundAt <= 10 && them.flatSpeed() < 0.18);
			}
		} else {
			inReachTicks = 0;
		}

		// ---- the answers to the open exchanges ----
		for (int i = 0; i < filled; i++) {
			int age = tick - tableAt[i];
			if (age > 0 && age <= ANSWER_TICKS) {
				float[] row = table[i];
				// The other side's answer: they answer my hit, I answer theirs; only theirs are watched (mine are known).
				if (tableMine[i]) {
					if (theyTookOff) {
						row[4] = 1f;
					}
					if (age >= 6 && away > 0.15) {
						row[5] = 1f;
					}
					if (e.theySwung) {
						row[6] = 1f;
					}
				} else {
					if (iWasOnGround && !me.onGround && me.vy > 0.3) {
						row[4] = 1f;
					}
					if (age >= 6 && -d.iClose > 0.15) {
						row[5] = 1f;
					}
					if (e.iSwung) {
						row[6] = 1f;
					}
				}
			}
		}
		theyWereOnGround = them.onGround;
		iWasOnGround = me.onGround;
	}

	private void addExchange(boolean mine, int kind) {
		for (int i = EXCHANGES - 1; i > 0; i--) {
			System.arraycopy(table[i - 1], 0, table[i], 0, PER_EXCHANGE);
			tableAt[i] = tableAt[i - 1];
			tableMine[i] = tableMine[i - 1];
		}
		float[] row = table[0];
		Arrays.fill(row, 0f);
		row[0] = mine ? 1f : -1f;
		row[1] = kind == Events.SPRINT ? 1f : 0f;
		row[2] = kind == Events.CRIT ? 1f : 0f;
		row[3] = kind == Events.PLAIN ? 1f : 0f;
		tableAt[0] = tick;
		tableMine[0] = mine;
		filled = Math.min(EXCHANGES, filled + 1);
	}

	/** Writes {@link #SIZE} inputs at {@code off}. */
	public void write(float[] out, int off) {
		int o = off;
		for (int h = 0; h < COUNT; h++) {
			out[o++] = rate(h);
			out[o++] = evidence(h);
		}
		out[o++] = rhythmMean;
		out[o++] = rhythmSpread;
		out[o++] = Math.min(5, theirHitsInRow) / 5f;
		out[o++] = Math.min(5, myHitsInRow) / 5f;
		for (int i = 0; i < EXCHANGES; i++) {
			if (i < filled) {
				float fade = Math.max(0f, 1f - (tick - tableAt[i]) / EXCHANGE_FADE);
				float[] row = table[i];
				for (int k = 0; k < PER_EXCHANGE - 1; k++) {
					out[o++] = row[k] * fade;
				}
				out[o++] = fade;
			} else {
				for (int k = 0; k < PER_EXCHANGE; k++) {
					out[o++] = 0f;
				}
			}
		}
	}

	/** The habits in words, for logs: only those with something seen. */
	public String describe() {
		StringBuilder b = new StringBuilder();
		for (int h = 0; h < COUNT; h++) {
			if (chances[h] >= 0.5f) {
				b.append(b.length() == 0 ? "" : ", ").append(NAMES[h]).append(String.format(java.util.Locale.ROOT, " %.0f%% of %.1f", 100f * rate(h), chances[h]));
			}
		}
		return b.length() == 0 ? "nothing seen yet" : b.toString();
	}
}
