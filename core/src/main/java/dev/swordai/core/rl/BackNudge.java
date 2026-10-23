package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.TakenStreak;

/**
 * The same kind of push as {@link JumpNudge}, for another choice in another situation: S, in the
 * ticks right after a hit of a combo it is taking. That is what gets a fighter out of a combo: the
 * hit has thrown it back, and backing on from there puts it beyond the next one.
 *
 * <p>Why it is needed: by the time a fighter meets combos it has spent a long while learning to
 * hold W, and S has become something it all but never presses. A key that is never pressed is never
 * found to help, however well it would be paid.
 */
public final class BackNudge {
	// Only ever a push, in this one situation. For two versions (0.9.0 to 0.10.0) each update also
	// pulled the network's own choice towards S there, to make the move its own. The network could
	// not tell "there" from "everywhere" fast enough: it came to hold S all the time and stopped
	// pressing W. A push ends where its situation ends; that kind of teaching did not.
	/** A combo is on from this many hits taken in a row, as the reward counts it ({@code RewardWeights.comboEscapeAfter}). */
	public static final int MIN_HITS = 2;
	/** The ticks after each such hit that decide it ({@code RewardWeights.comboBackTicks}). */
	public static final int TICKS = 12;
	private static final float HITS_SHOWN = (MIN_HITS - 0.5f) / TakenStreak.SHOWN_MAX;
	private static final float SINCE_SHOWN = 1f - TICKS / BodyState.EVENT_FADE_TICKS;
	/** The option of the forward head that is S. */
	public static final int BACK = 2;
	private static final int S = ActionSpace.OFFSETS[ActionSpace.FORWARD] + BACK;

	private BackNudge() {
	}

	/**
	 * Whether the fighter is in the opponent's combo, as it can tell itself: it has taken that many
	 * hits in a row without landing one, the last of them not long ago.
	 */
	public static boolean inCombo(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] >= HITS_SHOWN;
	}

	/**
	 * Whether this is the situation the push is for, read from what the network itself is shown:
	 * in a combo, and hit less than {@link #TICKS} ticks ago. In these ticks W and S are the escape
	 * model's to press (see {@code ActorNet}).
	 */
	public static boolean applies(float[] vec) {
		return inCombo(vec) && vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] > SINCE_SHOWN;
	}

	/** Adds the push to the network's output for this observation. */
	public static void apply(float[] logits, float[] vec, float strength) {
		if (strength > 0f && applies(vec)) {
			logits[S] += strength;
		}
	}
}
