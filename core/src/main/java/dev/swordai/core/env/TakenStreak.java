package dev.swordai.core.env;

/**
 * The opponent's combo as a fighter can count it itself: the hits it has taken in a row without
 * landing one in between. Nothing about the opponent goes into it, only what happened to its own
 * body, so it may be shown to the acting network: being hit once and being hit for the third time
 * running call for different things, and the time since the last hit alone does not tell them apart.
 *
 * <p>Counted exactly as the reward counts it (see {@code RewardModel}): a hit of its own ends the
 * combo, and so does a stretch of {@link #COMBO_TICKS} ticks without another hit.
 */
public final class TakenStreak {
	/** The opponent's combo is over once this many ticks pass without it landing another hit. */
	public static final int COMBO_TICKS = 25;
	/** Shown to the network up to this many hits; a longer combo looks the same. */
	public static final int SHOWN_MAX = 3;

	private int hits;
	private int ticksSince;

	public int hits() {
		return hits;
	}

	/** As the network is shown it, 0..1. */
	public float shown() {
		return Math.min(hits, SHOWN_MAX) / (float) SHOWN_MAX;
	}

	public void reset() {
		hits = 0;
		ticksSince = 0;
	}

	/** Starts some hits into the opponent's combo, the last of them just landed. */
	public void start(int hits) {
		this.hits = Math.max(0, hits);
		this.ticksSince = 0;
	}

	/**
	 * One tick.
	 *
	 * @param dealt hits it has landed since the last call; these end the opponent's combo
	 * @param taken hits it has taken since the last call; taken on the same tick as one it landed,
	 *              they begin a new combo
	 */
	public void tick(int dealt, int taken) {
		if (dealt > 0) {
			hits = 0;
		}
		if (taken > 0) {
			hits += taken;
			ticksSince = 0;
		} else if (hits > 0 && ++ticksSince > COMBO_TICKS) {
			hits = 0;
		}
	}
}
