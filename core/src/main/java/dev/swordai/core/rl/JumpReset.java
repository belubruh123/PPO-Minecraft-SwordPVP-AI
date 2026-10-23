package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

import java.util.Random;

/**
 * The way out of a combo that gives the opponent no room: on the tick it learns of a hit of a combo
 * it is taking (the second in a row or a later one), with its feet still on the ground, which they
 * are for that one tick, it jumps with W and the sprint key held. A running jump adds a fifth of a
 * block a tick towards where it is looking, against a knockback of four tenths (seven with a sprint
 * behind the hit): it is thrown half as far, or two thirds. It comes down within a sword's reach of
 * the opponent instead of beyond it, and the swing it owes ({@link AnswerRule}) is there to be made.
 * What players call a jump reset.
 *
 * <p>Why it is needed. Up to the first evening of 0.14.0 against people, the way out of a combo was
 * S alone ({@link BackNudge}): back off, and come in again for the first hit. Against a player who
 * answers that with a running jump it is the way into the next hit. A running jump covers ground
 * faster than S does, the one who comes in sees the one who backs off where it was a moment ago,
 * which is nearer, and the hit at the end of the jump is a critical one; that throws the fighter
 * back again, S again, and so on round. Backing off is what that player's combo is made of.
 *
 * <p>Only ever in a combo, never on the first hit: a fighter that takes no hit at its full
 * knockback cannot be comboed at all, by anyone, and that is not a fight. And only this share of
 * those hits ({@code Config.jumpResetChance}).
	 *
 * <p>And a hit that finds it with its sword on the way back from a hit of its own
 * ({@link #counters}): the opponent's answer to that hit, made before there is a swing to answer
 * it with. Jumped with, the fighter is thrown half as far and is in the air beside the opponent as
 * its sword comes back, and the swing it owes is made on the way down, a critical hit. Without
 * that, an opponent that follows its answer with a running jump has the fighter twice for each
 * hit the fighter lands.
 *
 * <p>With one exception more ({@link #chasers}): an opponent that has followed a hit with a second one
 * made from the air, which is the running jump and the critical hit, is one that does. Against
 * that opponent, for the next twenty seconds, the first hit is jumped with as well. Its second hit
 * is on its way the moment the first has landed, and a fighter that has jumped with the first is
 * coming down on it, sword back, while it is still going up.
 *
 * <p>Not thread-safe; one per fighter.
 */
public final class JumpReset {
	/** What {@code StepFacts.ruleJump} says of a jump made by this rule; the others are {@link JumpIn}'s. */
	public static final int KIND = 4;
	/** W and the sprint key are held for the jump: this long at the most. */
	static final int AIR_TICKS = 14;
	/** Moving up or down at this much a tick or more, the opponent is off the ground. */
	static final float AIR_SPEED = 0.06f;
	/** A hit was made from the air if the opponent was seen off the ground within this many ticks before it. */
	static final int AIR_SEEN_TICKS = 6;
	/** How long an opponent that has done it is taken for one that follows its hits through the air: twenty seconds. */
	static final int CHASED_TICKS = 400;

	/** Its own hit no longer ago than this, and the sword no farther back than this: there is no swing to answer with. */
	static final int COUNTER_TICKS = 13;
	static final float COUNTER_CHARGE = 0.9f;

	private final float chance;
	private final Random rng;
	private boolean chasers;
	private float counters;
	private int left;
	private boolean airborne;
	private boolean tookOff;
	private int airSeen;
	private int chased;

	public JumpReset(float chance, long seed) {
		this.chance = chance;
		this.rng = new Random(seed);
	}

	/** Whether what the network is shown says it was hit on this very tick. */
	public static boolean hitOnThisTick(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] >= 1f - 0.5f / BodyState.EVENT_FADE_TICKS;
	}

	/**
	 * Whether the first hit is jumped with too against an opponent that has lately followed a hit
	 * with one made from the air; see {@code Config.jumpResetChasers}.
	 */
	public void chasers(boolean on) {
		chasers = on;
	}

	/**
	 * The share of the hits that find it with its sword on the way back from a hit of its own that
	 * are jumped with too; see {@code Config.jumpResetCounterChance}.
	 */
	public void counters(float share) {
		counters = share;
	}

	/** Whether the opponent has lately followed a hit with one made from the air. */
	public boolean chased() {
		return chased > 0;
	}

	public void reset() {
		chased = 0;
		newRound();
	}

	/** A new round against the same opponent: what it has found out about how that one follows its hits is kept. */
	public void newRound() {
		left = 0;
		airborne = false;
		tookOff = false;
		airSeen = 0;
	}

	/** Whether the last call of {@link #plan} took off. */
	public boolean tookOff() {
		return tookOff;
	}

	/**
	 * One decision.
	 *
	 * @param vec    what the network is shown on this tick
	 * @param keys   the keys that are the network's on this tick
	 * @param hitNow the body's own game has learned of a hit on it since the last decision: it has the
	 *               knockback and its feet are still on the ground. On a server that is the tick the
	 *               player is seen to flinch.
	 * @return what this tick is to do, as {@link JumpIn}'s flags; 0 leaves everything to the network
	 *         and to the other rules
	 */
	public int plan(float[] vec, int keys, boolean hitNow) {
		tookOff = false;
		boolean ground = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f;
		boolean fromAir = airSeen > 0;
		if (airSeen > 0) {
			airSeen--;
		}
		if (chased > 0) {
			chased--;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
			// The opponent's own speed up or down: what the fighter sees of it, less its own.
			float up = (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] + vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP]) / BodyState.VELOCITY_SCALE;
			if (Math.abs(up) >= AIR_SPEED) {
				airSeen = AIR_SEEN_TICKS;
			}
		}
		if (hitNow) {
			left = 0;
			boolean combo = BackNudge.inCombo(vec);
			if (combo && fromAir) {
				// A second hit in a row, made from the air: this one comes after its hits with a jump.
				chased = CHASED_TICKS;
			}
			float sinceDealt = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT];
			boolean counter = counters > 0f && sinceDealt >= 1f - (COUNTER_TICKS + 0.5f) / BodyState.EVENT_FADE_TICKS
					&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] < COUNTER_CHARGE && rng.nextFloat() < counters;
			if (chance > 0f && ground && (combo || counter || chasers && chased > 0) && ActionSpace.unlocked(keys, ActionSpace.JUMP)
					&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)
					&& rng.nextFloat() < chance) {
				left = AIR_TICKS;
				airborne = false;
				tookOff = true;
				return JumpIn.JUMP | JumpIn.RUN;
			}
			return 0;
		}
		if (left <= 0) {
			return 0;
		}
		left--;
		if (!ground) {
			airborne = true;
			return JumpIn.NO_JUMP | JumpIn.RUN;
		}
		if (airborne) {
			// Down again.
			left = 0;
			return 0;
		}
		return JumpIn.RUN;
	}
}
