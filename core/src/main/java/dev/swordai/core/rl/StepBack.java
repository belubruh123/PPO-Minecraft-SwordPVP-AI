package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * After a hit of its own that has not sent the opponent away, it steps back out of that opponent's
 * reach until its sword is nearly back, and only then comes in again.
 *
 * <p>What that is for. A hit throws the opponent up for four or five ticks and it comes down for as
 * many. If it was thrown back as well, it comes down three or four blocks off and there is nothing
 * it can do. If it was not (a server that hands out part of the knockback, a jump made with the
 * hit, a wall behind it, a critical hit, which knocks back little), it comes down within a sword's
 * reach of a fighter whose own sword is still on its way back, and the hit it makes on the way down
 * is a critical one: the punish crit, which this fighter makes itself ({@link AnswerRule}). There
 * is no swing to answer it with, and none is made early. What there is: not to be there. Walking
 * backwards covers a block and a half in the time the opponent is in the air, and an opponent in
 * the air cannot follow.
 *
 * <p>Which opponent that is, it finds out by being hit so ({@link #wary}): hit three to thirteen
 * ticks after a hit of its own, with its sword on the way back, twice within five seconds. From then
 * on, for as long as that goes on, it steps back from every hit on the tick it lands, before there
 * is anything to read, and goes after the opponent again if the hit turns out to have sent it
 * flying. Against anyone else it does not step back at all: that costs the ticks its own combos,
 * and its chase of an opponent that runs, are made of.
 *
 * <p>What was measured, the network not learning, against the opponent that answers every hit on
 * its way down: without this, 77 hits landed and 77 taken a minute, 62 of those taken such answers;
 * with it, 59 landed and 52 taken, 23 of them such answers. (Only stepping back from an opponent
 * known to answer, and not on what can be read, was tried for half an hour of training: 67 and 73.)
 *
 * <p>Backing off is what a player who follows with a running jump is waiting for ({@link JumpReset}).
 * Here the opponent is in the air from the hit and cannot take one; by the time it can, the sword
 * is back, and an opponent that jumps then is run at and hit on its way up ({@link MeetJump}).
 *
 * <p>Read from what the network is shown. Not thread-safe; one per fighter.
 */
public final class StepBack {
	/** S is held, the sprint key up. */
	public static final int BACK = 1;
	/** W, S and the sprint key are up: it stays where it is. */
	public static final int STAND = 2;

	static final int JUDGE_FROM_TICK = 2;
	static final int JUDGE_TO_TICK = 5;
	/** As {@link JumpIn#distance} reads it. */
	static final float IN = 3.4f;
	/**
	 * How fast the opponent itself is going away, blocks a tick. A sprint hit at its full knockback
	 * has it going at 0.55 two ticks on; half of that, or a hit without the sprint, at 0.3.
	 */
	static final float AWAY_MOST = 0.4f;
	/**
	 * It backs off until it is this far away: a sword's reach and what a player on a server sees a
	 * fighter that is backing off nearer than it is, four ticks of a fifth of a block.
	 */
	static final float OUT = 4.0f;
	/** And does not come in from within this. */
	static final float STAND_WITHIN = 4.5f;
	/** Until the sword is this far back: what is left of it is the way in. */
	static final float CHARGE_BACK = 0.8f;
	static final int OVER_TICKS = 13;
	static final int OPPONENT_HITS_BACK_TICKS = 100;
	/** Hit this long after a hit of its own, the opponent has answered that hit. */
	static final int ANSWERED_FROM_TICK = 3;
	static final int ANSWERED_TO_TICK = 13;
	/**
	 * Each such answer counts for this long, ten seconds, and it takes two within about five seconds
	 * for the opponent to be one that answers its hits ({@link #WARY_FROM}): one that does it now and
	 * then, as anyone who is fighting back will, is not.
	 */
	static final int ANSWER_COUNTS_TICKS = 200;
	static final int WARY_FROM = 300;
	static final int WARY_MOST = 600;
	/** Still going away at this much a tick four ticks after the hit, it is not thrown but running: after it. */
	static final float RUNNING = 0.25f;
	static final int RUNNING_FROM_TICK = 4;
	/**
	 * Whether it also steps back on what it can read of a hit, against an opponent it knows nothing
	 * of. Off: measured against the opponent that keeps running out, with the network not learning,
	 * that was 43 hits landed a minute for 61 without, and as many taken.
	 */
	static final boolean READ = false;
	/**
	 * Never against an opponent that comes after it through the air: one that has hit it from the air
	 * within a second of its stepping back, or followed a hit of a combo with one from the air
	 * ({@link #chasedBy}). For a minute after that, kept across rounds. The user, 18 October: S "is
	 * ok as long as opponent didn't chase you, so it needs to know if they will chase you with crit.
	 * But still be careful most".
	 */
	static final int CHASER_TICKS = 1200;
	static final int CHASE_AFTER_STEP_TICKS = 20;
	/** Moving up or down at this much a tick or more, the opponent is off the ground; seen so within this many ticks before a hit, the hit came from the air. */
	static final float AIR_SPEED = 0.06f;
	static final int AIR_SEEN_TICKS = 6;

	private int sinceDealt = -1;
	/** Ticks since its last hit, whether or not it is still stepping back from it. */
	private int dealtAge = 1000;
	private int unhit = 10_000;
	private int wary;
	private boolean on;
	private int chaser;
	private int sinceStep = 1000;
	private int airSeen;

	public void reset() {
		wary = 0;
		chaser = 0;
		newRound();
	}

	/** A new round against the same opponent: what it has found out about how that one answers its hits is kept. */
	public void newRound() {
		sinceDealt = -1;
		dealtAge = 1000;
		unhit = 10_000;
		on = false;
		sinceStep = 1000;
		airSeen = 0;
	}

	/** Whether it is stepping back from the hit it last landed. */
	public boolean on() {
		return on;
	}

	/** Whether the opponent has lately answered one of its hits while its sword was on the way back. */
	public boolean wary() {
		return wary >= WARY_FROM;
	}

	/** Whether the opponent has lately come after it through the air; see {@link #CHASER_TICKS}. */
	public boolean chaser() {
		return chaser > 0;
	}

	/** Another rule has found the opponent to follow its hits with a jump; see {@code JumpReset.chased}. */
	public void chasedBy(boolean chased) {
		if (chased) {
			chaser = CHASER_TICKS;
		}
	}

	/**
	 * One decision; to be called on every tick, whether or not what it says is done.
	 *
	 * @return {@link #BACK}, {@link #STAND} or 0, which leaves W and S to the network
	 */
	public int plan(float[] vec) {
		return plan(vec, false);
	}

	/**
	 * @param always step back after every hit of its own, as from an opponent that answers its hits (the
	 *               tactic {@link Tactic#BACK}); never from one that chases, all the same
	 */
	public int plan(float[] vec, boolean always) {
		float fresh = 1f - 0.5f / BodyState.EVENT_FADE_TICKS;
		if (wary > 0) {
			wary--;
		}
		if (dealtAge < 1000) {
			dealtAge++;
		}
		if (chaser > 0) {
			chaser--;
		}
		sinceStep = on ? 0 : Math.min(sinceStep + 1, 1000);
		boolean fromAir = airSeen > 0;
		if (airSeen > 0) {
			airSeen--;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f
				&& Math.abs(scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE >= AIR_SPEED) {
			airSeen = AIR_SEEN_TICKS;
		}
		if (scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fresh) {
			if (fromAir && sinceStep <= CHASE_AFTER_STEP_TICKS) {
				// Stepped back from, it came after the fighter through the air: never again, for a while.
				chaser = CHASER_TICKS;
			}
			// Hit: what follows is the answer to that, which has rules of its own.
			if (dealtAge >= ANSWERED_FROM_TICK && dealtAge <= ANSWERED_TO_TICK) {
				// With its sword on the way back from a hit of its own: an answer to that hit.
				wary = Math.min(WARY_MOST, wary + ANSWER_COUNTS_TICKS);
			}
			unhit = 0;
			sinceDealt = -1;
			on = false;
			return 0;
		}
		unhit = Math.min(unhit + 1, 10_000);
		boolean seen = vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
		float d = seen ? JumpIn.distance(vec) : Float.MAX_VALUE;
		if (scalar(vec, ObsSpec.SC_SINCE_HIT_DEALT) >= fresh) {
			sinceDealt = 0;
			dealtAge = 0;
			// From an opponent that answers its hits it is on its way out before there is anything to read.
			on = (always || wary()) && chaser <= 0 && d <= IN;
			return on ? BACK : 0;
		}
		if (sinceDealt < 0) {
			return 0;
		}
		sinceDealt++;
		if (sinceDealt > OVER_TICKS || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) >= CHARGE_BACK) {
			sinceDealt = -1;
			on = false;
			return 0;
		}
		if (!seen) {
			return 0;
		}
		float away = (scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
		if (on && sinceDealt >= RUNNING_FROM_TICK && away >= RUNNING) {
			sinceDealt = -1;
			on = false;
			return 0;
		}
		if (sinceDealt >= JUDGE_FROM_TICK && sinceDealt <= JUDGE_TO_TICK) {
			if (on && (always || wary()) && away > AWAY_MOST && d > IN) {
				// Sent flying after all: after it.
				sinceDealt = -1;
				on = false;
				return 0;
			}
			if (READ && !on && unhit <= OPPONENT_HITS_BACK_TICKS && d <= IN) {
				on = away <= AWAY_MOST;
			}
		}
		if (!on) {
			return 0;
		}
		return d <= OUT ? BACK : d <= STAND_WITHIN ? STAND : 0;
	}

	private static float scalar(float[] vec, int index) {
		return vec[ObsSpec.VEC_SCALARS + index];
	}
}
