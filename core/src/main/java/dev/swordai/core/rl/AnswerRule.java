package dev.swordai.core.rl;

/**
 * A rule the fighter is held to, like the one about the sword's charge ({@link SwingGate}): hit, and
 * not yet having hit back, it swings the moment its sword is back and the game has the opponent
 * under its crosshair within reach. Not left to the network: taking hit after hit with the sword
 * back and a hit there to be made is something it must not do at all, and what it had learned of
 * when to swing back was, some rounds, never.
 *
 * <p>What it goes by is the fighter's own count of the hits it has taken in a row, and the game's
 * own pick of what is under the crosshair: the thing the attack indicator shows a player, and what
 * a click is decided by. So a swing made by this rule is one that reaches. (The first version went
 * by how tall the opponent stood in the picture. Hit, a fighter is flying backwards, the picture is
 * a tick old, and nearly every one of those swings met air: 98 in 100 of its swings in the air, and
 * each of them cost it the sword for the next exchange.)
 *
 * <p>The pick is taken twice. Once when the step is decided: if the rule applies, the attack key is
 * not the network's on that tick, as with the rule about the sword's charge. And again when the
 * click is made, after the camera has turned: if the opponent is no longer under the crosshair the
 * swing is not made at all, and the sword stays back.
 *
 * <p>Made on the tick it learns of the opponent's hit, before the knockback has carried it out of
 * reach, such a swing is what players call a hit-select: a sprint hit takes four tenths off the
 * attacker's own speed, so it is thrown less far than the opponent it has just hit, and the next
 * hit is its own.
 */
public final class AnswerRule {
	private AnswerRule() {
	}

	/** What the rule does with the swing it owes: makes it now. */
	public static final int NOW = 0;
	/** Keeps it: no swing on this tick. */
	public static final int KEEP = 1;
	/** Keeps it, and lets go of W and of the sprint key on this tick, so that the next is a critical hit. */
	public static final int LET_GO = 2;

	/**
	 * When the swing it owes is made, with the opponent under its crosshair. On the ground: now. In
	 * the air it is kept for the way down, where it is a critical hit, the punish crit: half as much
	 * damage again. A hit throws a fighter up for four or five ticks and it comes down for as many;
	 * on the way up the swing is not made, on the first tick of the way down the sprint is let go of
	 * (the game makes no critical hit of a sprinting player's swing, and hears of the sprint ending at
	 * the end of that tick), and on the next the swing is made.
	 *
	 * <p>Up to the first fights of 0.14.0 against a person the swing was made the moment the rule
	 * applied, which in the air is on the way up: a hit, and the sword gone for the fall. The punish
	 * crit was then made only where the rule had not applied first, and a player who goes round the
	 * fighter making critical hits, each of which leaves it in the air with that player in reach, got
	 * plain hits back for them.
	 */
	public static int inTheAir(float[] vec) {
		if (!JumpNudge.inAir(vec)) {
			return NOW;
		}
		if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] >= 0f) {
			return KEEP;
		}
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] >= 0.5f ? LET_GO : NOW;
	}

	/**
	 * When the swing it owes is made while its feet are on the ground. Against most opponents: now,
	 * which is a trade, hit for hit. Against one that was still in reach the last time it came down
	 * from a hit ({@link FallWatch}), it is kept for as long as the hit is fresh (the twenty ticks its
	 * clock runs): on the tick of the hit itself, so that it is still there for the way down, where
	 * it is a critical hit; and after a fall on which no swing was to be had, until the next hit,
	 * which from such an opponent is never long in coming.
	 *
	 * <p>Why not at once there too: an opponent that stays on top of the fighter hits it every
	 * fifteen to twenty ticks. A swing made from the ground a moment before that hit is a plain hit
	 * or a sweep, and it leaves the sword on its way back for the whole of the next fall. Measured
	 * against the opponent that takes no knockback, an hour into the first rules: on 46% of its falls
	 * with that opponent in reach the sword was not back, eight of its hits in ten were sweeps, and
	 * the share that were critical had gone from three in four to four in ten.
	 */
	public static int onTheGround(float[] vec, boolean punishable) {
		return onTheGround(vec, punishable, false);
	}

	/**
	 * @param wait keep the swing for as long as the hit is fresh, as up to 14 October; false keeps it
	 *             on the tick of the hit only. With the jump made with a hit ({@link JumpReset}) the
	 *             opponent is in reach on nearly every fall, so it was nearly always "one that stays",
	 *             and a second after each hit it stood in reach with its sword back and did not swing:
	 *             what a person saw was a fighter that is close and does not hit.
	 */
	public static int onTheGround(float[] vec, boolean punishable, boolean wait) {
		float sinceTaken = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN];
		return punishable && (wait ? sinceTaken > 0f : sinceTaken >= 1f - 0.5f / dev.swordai.core.env.BodyState.EVENT_FADE_TICKS) ? KEEP : NOW;
	}

	/**
	 * When the swing it owes is made, on the ground or in the air; see {@link #onTheGround} and
	 * {@link #inTheAir}.
	 *
	 * <p>Keeping the swing is for the first hit. Hit a second time in a row without having hit
	 * back, it is being comboed, and then the hit that is there is the hit that is made: now, on the
	 * ground or on the way up, critical or not. What it is held to first is that no combo goes by
	 * without a swing back, and a swing kept for a fall on which the opponent is then out of reach is
	 * a combo sat through. (With the swing kept whenever the last fall had the opponent near, one
	 * combo in seven went by without a swing, where it had been one in twenty-five.)
	 *
	 * <p>Unless a punish crit has been made on this opponent: the last fall on which the sword was
	 * back ended in one ({@link FallWatch#proven}). Then the fall is where the next one is made too,
	 * second hit or not. (Without that, against the opponents that stay on top of it and make
	 * critical hits, a fall with the sword still on its way back was followed by a plain hit made on
	 * the way up of the next: three falls in four ending in a critical hit became six in ten.)
	 *
	 * @param punishable the opponent was in reach on the way down the last time
	 * @param proven     and a punish crit has been made on it
	 */
	public static int when(float[] vec, boolean punishable, boolean proven) {
		return when(vec, punishable, proven, false);
	}

	/** @param wait see {@link #onTheGround(float[], boolean, boolean)} */
	public static int when(float[] vec, boolean punishable, boolean proven, boolean wait) {
		return when(vec, punishable, proven, wait, 0f);
	}

	/**
	 * @param keepWithin the swing is kept for the way down only with the opponent nearer than this ({@code Config.keepWithin});
	 *                   farther off it is made now, while there is a hit there to be made. 0: always kept.
	 */
	public static int when(float[] vec, boolean punishable, boolean proven, boolean wait, float keepWithin) {
		if (BackNudge.inCombo(vec) && !proven) {
			return NOW;
		}
		if (!JumpNudge.inAir(vec)) {
			return onTheGround(vec, punishable, wait);
		}
		int air = inTheAir(vec);
		return air == KEEP && keepWithin > 0f && JumpIn.distance(vec) >= keepWithin ? NOW : air;
	}

	/** Whether the fighter has been hit and has not hit back since, as it can tell itself. */
	public static boolean owesAnAnswer(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] > 0f;
	}

	/**
	 * Whether the rule asks for a swing on this tick, the sword being back.
	 *
	 * @param crosshairOnOpponent the game's pick: the opponent is under the crosshair and within reach
	 */
	public static boolean applies(float[] vec, boolean crosshairOnOpponent) {
		return crosshairOnOpponent && owesAnAnswer(vec);
	}

	/** The opponent moving away from the fighter at this much a tick or more is backing out after its hit. */
	static final float BACKING = 0.08f;

	/**
	 * Whether the opponent is backing straight out after a single hit: in view, its own speed away from
	 * the fighter at least {@link #BACKING}, and not in a combo (the second hit in a row is always
	 * answered). That is the spacer's crit, S: the answer then trades at its landing and leaves the
	 * sword down for its next jump in, which comes on its own clock (20 October).
	 */
	public static boolean opponentBacking(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || BackNudge.inCombo(vec)) {
			return false;
		}
		float away = (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] + vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD])
				/ dev.swordai.core.env.BodyState.VELOCITY_SCALE;
		return away >= BACKING;
	}

	/** @param spareBacking no swing is owed to an opponent backing out after one hit; see {@link #opponentBacking} */
	public static boolean applies(float[] vec, boolean crosshairOnOpponent, boolean spareBacking) {
		return applies(vec, crosshairOnOpponent) && !(spareBacking && opponentBacking(vec));
	}
}
