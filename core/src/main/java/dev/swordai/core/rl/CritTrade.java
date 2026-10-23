package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * The trade the fighter loses to people (the user, 23 October, from fights against their friends with the installed
 * network): an opponent jumps in at it with its sword ready, meaning to crit on the way down. The fighter hits it first,
 * to knock it away. But the hit does not spend the opponent's swing, and the knockback does not take it out of reach at
 * once: it halves the opponent's own speed and pushes it back, and on a server the push comes a few ticks late. So the
 * opponent, still in reach and now coming down, swings right after the hit, and that swing is a critical one: 10.5 for
 * the fighter's 7, and the fighter is the one thrown away.
 *
 * <p>In the fight traces of 23 October (1,500 fights): 11,579 hits on an opponent in the air with its sword ready, and
 * 47% of them had a critical hit back within four ticks (against the spacer 70%), one to four ticks after the hit. At
 * the top of its jump, from 2.5 to 3.0 off, 86%. Over the thirty ticks around such a crit taken, the fighter came out
 * 10.4 down where it had hit first and 3.4 down where it had kept its swing.
 *
 * <p>The user's answers to it, in their order. First, as players open a fight: jump in too, and crit for crit; their crit's
 * knockback comes late on a server, so a fighter whose own sword is back is still in reach for its crit (the clash jump,
 * {@code JumpIn.CLASH}). Where it is not in that jump: the swing is not made ({@link #holds}); the crit lands, the
 * fighter's sword is still back and the opponent's is spent, and the fighter makes its punish crit on its way down from
 * that hit, at once ({@link #punish}): wait, and the opponent's crit has thrown it out of reach. Knocking it away is only
 * for when that is certain ({@link #knockAwayCertain}): "on server kb isnt instant unless you are certainly sure it will
 * then knock or else pcrit is main solution".
 *
 * <p>Not thread-safe; one per fighter.
 */
public final class CritTrade {
	/** Within this, eye to front, the opponent coming in is held for: a little more than a sword's reach. */
	static final float HOLD_REACH = 3.5f;
	/** And within this it is coming in ({@link #incoming}): no running at it to knock it away (the meet rule) from here. */
	static final float INCOMING_WITHIN = 6f;
	/** Its sword ready, as the fighter sees it. */
	static final float OPP_READY = 0.9f;
	/** And not already flying away: its own speed away from the fighter no more than this, blocks a tick. */
	static final float AWAY_MOST = 0.1f;
	/** Off the ground: going up or down faster than this, or within this many ticks of having been (the top of a jump). */
	static final int AIR_MEMORY_TICKS = 2;
	/** Knocked away for certain only if, by the time it could come down on the fighter, it is this far off at least. */
	static final float CLEAR_OF_REACH = 3.3f;
	/** Going up faster than this, blocks a tick, the opponent is on its way up; see {@link #holdOnRising}. */
	static final float RISING_MOST = 0.1f;
	/** On its way up it is jumping IN only coming at the fighter at this much a tick or more (not a hop in place, not one off to the side). */
	static final float IN_TOWARD = 0.05f;
	/** What gravity takes off a speed upwards each tick, blocks a tick. */
	static final float GRAVITY = 0.08f;

	/** What {@link #punish} has the keys do. */
	public static final int NONE = 0;
	/** No swing; W and the sprint key let go of, which ends the sprint (in the air it cannot start again without the sprint key). */
	public static final int LET_GO = 1;
	/** No swing; W held with the sprint key up: drifting back in on the opponent against the knockback. */
	public static final int WALK = 2;
	/** The swing: coming down, the sprint off, the sword back: a critical hit. */
	public static final int SWING = 3;
	/** No swing yet: still on the ground after the crit, its knockback about to lift it (late on a server); the punish crit is for the way down. */
	public static final int WAIT = 4;
	/**
	 * On the ground after a jumper's crit the swing is kept this many ticks at most for the knockback to lift the fighter.
	 * 23 Oct drill with its own knockback one or two ticks late: the answer rule swung from the ground at once, a plain hit,
	 * 7 for the crit's 10.5, in 58% of the fights; with the knockback at once (lifted, then the punish crit) 71% went 10.5 for 10.5.
	 * 3 ticks was one too few: in T9's first minutes all 110 punishes that were plain hits were made on the ground 3 ticks
	 * after the crit, and the lift shows 3 ticks after it (one or two late, one for the motion, one for the picture).
	 */
	static final int GROUND_WAIT_TICKS = 5;

	private final int knockbackDelay;
	/** See {@link #holdOnRising}. */
	private boolean onRising = true;
	private int oppAirTicks = AIR_MEMORY_TICKS + 1;
	private boolean incoming;
	private boolean jumpingIn;
	private boolean knockAway;
	private int sinceHeld = 1000;
	/** The hit it has not answered yet came from an opponent in the air: a jumper's crit. */
	private boolean jumperHit;

	/** @param knockbackDelay how many ticks late a hit's knockback is taken to reach the opponent; see {@link #knockAwayCertain} */
	public CritTrade(int knockbackDelay) {
		this.knockbackDelay = Math.max(0, knockbackDelay);
	}

	/** Whether the swing is held for an opponent still on its way up too ({@code Config.holdOnRising}); false: from the top of its jump. */
	public void holdOnRising(boolean on) {
		onRising = on;
	}

	/** A new fight. */
	public void reset() {
		oppAirTicks = AIR_MEMORY_TICKS + 1;
		incoming = false;
		jumpingIn = false;
		knockAway = false;
		sinceHeld = 1000;
		jumperHit = false;
	}

	/**
	 * Once per decision: whether a swing at the opponent is to be held back on this tick. Its jump is followed from tick
	 * to tick, so that the top of it, where it is going neither up nor down, still counts as in the air.
	 */
	public boolean holds(float[] vec) {
		boolean held = judge(vec);
		sinceHeld = held ? 0 : Math.min(sinceHeld + 1, 1000);
		return held;
	}

	private boolean judge(float[] vec) {
		incoming = false;
		jumpingIn = false;
		knockAway = false;
		boolean seen = vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
		float oppUp = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		int airWas = oppAirTicks;
		oppAirTicks = seen && Math.abs(oppUp) >= Tactic.AIR_SPEED ? 0 : Math.min(oppAirTicks + 1, 1000);
		if (sinceHitTaken(vec) == 0) {
			// The tick it learns of a hit: was the one that made it in the air (as it was last seen)?
			jumperHit = Math.min(airWas, oppAirTicks) <= AIR_MEMORY_TICKS + 1;
		} else if (!AnswerRule.owesAnAnswer(vec)) {
			jumperHit = false;
		}
		if (!seen || oppAirTicks > AIR_MEMORY_TICKS) {
			return false;
		}
		float d = JumpIn.distance(vec);
		if (scalar(vec, ObsSpec.SC_OPP_CHARGE) < OPP_READY || d > INCOMING_WITHIN) {
			return false;
		}
		float away = (scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
		if (away > AWAY_MOST) {
			return false;
		}
		incoming = true;
		boolean rising = oppUp > RISING_MOST;
		if (d > HOLD_REACH || rising && -away < IN_TOWARD) {
			return false;
		}
		// In reach and jumping in: a hit first now is the one the user means ("hit first is BAD" when it is in the air jumping
		// in, 23 October); see jumpingIn.
		jumpingIn = true;
		if (rising && !onRising) {
			return false;
		}
		// Unless its own swing now is a critical hit too: then it is a crit for a crit, and its own comes first.
		if (JumpNudge.inAir(vec) && JumpNudge.critSwingReady(vec)) {
			return false;
		}
		knockAway = knockAwayCertain(d, -away, oppUp, scalar(vec, ObsSpec.SC_SPRINTING) >= 0.5f, knockbackDelay);
		return !knockAway;
	}

	/** Whether, at the last {@link #holds}, an opponent was coming in through the air with its sword ready, near or not. */
	public boolean incoming() {
		return incoming;
	}

	/**
	 * Whether, at the last {@link #holds}, the opponent was jumping in at it within reach with its sword ready: in the air
	 * (on its way up only if coming at the fighter), whatever the hold made of it. A plain hit first on it is what the
	 * training takes points for ({@code RewardWeights.hitIntoJumpIn}).
	 */
	public boolean jumpingIn() {
		return jumpingIn;
	}

	/** Whether, at the last {@link #holds}, a hit would certainly have knocked it out of reach in time: the one case for the hit. */
	public boolean knockAway() {
		return knockAway;
	}

	/** Whether the swing was held in the last {@code ticks} decisions: a hit taken now is the crit it was held for. */
	public boolean heldWithin(int ticks) {
		return sinceHeld <= ticks;
	}

	/** Whether the hit it owes an answer for came from an opponent in the air: a jumper's crit, to be punished. */
	public boolean jumperHit() {
		return jumperHit;
	}

	/** Ticks since the last hit taken, as the fighter's own clock shows it (0: this tick). */
	static int sinceHitTaken(float[] vec) {
		return Math.round((1f - scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN)) * BodyState.EVENT_FADE_TICKS);
	}

	/**
	 * Whether a hit now certainly knocks the opponent out of reach before it can crit: the knockback reaches it
	 * {@code delay} ticks late, and until then it keeps coming at {@code toward} a tick. If its jump turns down within
	 * those ticks it comes down in reach: not certain. Otherwise the knockback (half its own speed, and 0.4 of a block a
	 * tick back; a sprint hit halves that again and adds 0.5) carries it for the rest of its way up, and it is certain
	 * only if that leaves it {@link #CLEAR_OF_REACH} off. A jump goes up for five ticks at most, so with a delay of four
	 * this is almost never.
	 */
	static boolean knockAwayCertain(float d, float toward, float oppUp, boolean sprintHit, int delay) {
		float upTicks = Math.max(0f, oppUp) / GRAVITY;
		if (upTicks <= delay) {
			return false;
		}
		float after = toward / 2f - 0.4f;
		if (sprintHit) {
			after = after / 2f - 0.5f;
		}
		float atTop = d - toward * delay - after * (upTicks - delay);
		return atTop >= CLEAR_OF_REACH;
	}

	/**
	 * The punish crit, made at once: hit (and not yet having hit back) with its sword back, on the tick it learns of the
	 * hit the sprint is let go of; on the way up W is held without it, so that it drifts back in instead of being carried
	 * off; on the first tick of the way down the swing. On the ground after a jumper's crit, for {@link #GROUND_WAIT_TICKS}:
	 * {@link #WAIT}, the knockback is about to lift it. Otherwise on the ground: {@link #NONE}, the rule about answering a
	 * hit has it.
	 */
	public int punish(float[] vec) {
		if (!JumpNudge.inAir(vec)) {
			return jumperHit && sinceHitTaken(vec) < GROUND_WAIT_TICKS ? WAIT : NONE;
		}
		return punishInAir(vec);
	}

	/** {@link #punish} in the air, where it does not matter what hit it. */
	public static int punishInAir(float[] vec) {
		if (!JumpNudge.inAir(vec)) {
			return NONE;
		}
		if (scalar(vec, ObsSpec.SC_SPRINTING) >= 0.5f) {
			return LET_GO;
		}
		return scalar(vec, ObsSpec.SC_VEL_UP) < 0f ? SWING : WALK;
	}

	private static float scalar(float[] vec, int index) {
		return vec[ObsSpec.VEC_SCALARS + index];
	}
}
