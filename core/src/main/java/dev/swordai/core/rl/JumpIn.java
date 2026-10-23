package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.ViewGeometry;

/**
 * Three jumps that are made for the fighter, and what its keys do in the air of each. Like the rule
 * about answering a hit ({@link AnswerRule}) this is not the network's to leave out: the keys it
 * takes are taken from the network for the tick, and what the network learns it learns around them.
 *
 * <ul>
 * <li><b>The chase.</b> It is at a run after an opponent a step or two beyond its reach, and the
 * run is hardly catching it: the opponent is backing off or running. From where the way down of a
 * jump will bring that opponent into reach, it jumps, keeps W and the sprint key down on the way
 * up, and on the way down, once the opponent is in its reach and under its crosshair, lets go of
 * both for a tick and swings on the next: a critical hit, half as much damage again as the sprint
 * hit the run would have come to, and one that leaves the opponent where it is instead of sending
 * it on its way. If the opponent is never in reach it lands still running, and nothing was spent
 * but the jump. After an opponent that runs with its back turned, as fast as the fighter itself,
 * it makes one running jump after the other from any distance: a run never gets nearer to that
 * one, and jumps taken in a row are carried a quarter faster than a run.</li>
 * <li><b>The crit in its face.</b> Its last two hits did not send the opponent away: each time, by
 * the time the sword was back, the opponent was still on top of it. And now it has landed a hit again
 * and the opponent is in its face three to five ticks later. That is one a hit does not send away,
 * because it takes no knockback, has a wall behind it or keeps pushing in. A sprint hit gets nothing
 * there that a critical hit does not, and a critical hit does half as much damage again. So it
 * jumps as the sword recharges, with W and the sprint key up, and swings on the way down, and goes
 * on like that for as long as the opponent stays. Against an opponent that a sprint hit sends away
 * this never comes up. Nor against one on a slow connection, which stands where it was hit for a
 * quarter to half a second and then goes flying: by the time the sword is back that one is gone,
 * and a jump made at it would have come down on nothing.</li>
 * <li><b>The uppercut.</b> In a combo of its own, after two hits or more, now and then: a running jump at the opponent as
 * the sword comes back, and the sprint hit made while it is still going up. The jump carries it in
 * faster than a run, so the hit reaches an opponent that is backing out of the combo, and it comes
 * off the rhythm the others came on.</li>
 * </ul>
 *
 * <p>Everything it goes by is what the network itself is shown: the fighter's own body, and how
 * large and where the opponent is on screen. How far away the opponent is, is read off its height
 * on screen.
 *
 * <p>Why these are rules: a jump that is tried and comes to nothing costs, a jump at random makes a
 * fighter worse, and so in five runs of 0.14.0 the first thing learned about jumping in a fight was
 * not to. Against a person that keeps running out, it ran after them and never caught them.
 *
 * <p>Not thread-safe; one per fighter.
 */
public final class JumpIn {
	public static final int NONE = 0;
	public static final int CHASE = 1;
	public static final int FACE = 2;
	public static final int UPPER = 3;
	/** A running jump that closes on an opponent five blocks off or more; see {@link #approachHops}. (4 is {@link JumpReset#KIND}.) */
	public static final int HOP = 5;
	/**
	 * Crit back: the opponent has just swung (hit or miss) and its sword is on its way back, the
	 * fighter's is nearly back, and the opponent is at the edge of the reach. A running jump at it,
	 * and the critical hit on the way down lands before that sword is back; see {@link #counterCrit}.
	 */
	public static final int COUNTER = 6;
	/**
	 * The crit in: a running jump at an opponent standing still on the ground, taken from where its way down brings the
	 * fighter into reach in the last ticks of the fall, the sprint let go of just before, and the critical hit made as it
	 * comes into reach; see {@link #edgeCrit}.
	 */
	public static final int EDGE = 7;
	/**
	 * The combo jump: a few ticks after a hit of its own has landed, with the sword a third of the way back, a sprint-jump
	 * after the opponent that hit has thrown back, W and the sprint key down all the way, and the next hit on the way
	 * down; see {@link #comboJump}.
	 */
	public static final int COMBO = 8;
	/**
	 * The clash: an opponent coming in at it through the air with its sword ready, near, and the fighter on the ground with
	 * its own nearly back. It jumps too, the sprint let go of, and makes its crit on the way down; see {@link #clashJump}.
	 */
	public static final int CLASH = 9;

	// What a tick is to do, as bit flags.
	/** The jump key is pressed. */
	public static final int JUMP = 1;
	/** W and the sprint key are held. */
	public static final int RUN = 2;
	/** W and the sprint key are let go of. */
	public static final int LET_GO = 4;
	/** It swings; only to be carried out with the opponent still under the crosshair. */
	public static final int SWING = 8;
	/** The swing is kept. */
	public static final int NO_SWING = 16;
	/** The jump key is up. */
	public static final int NO_JUMP = 32;
	/** W down and the sprint key up: walking, or drifting in in the air without the sprint that would spoil a crit. */
	public static final int WALK = 64;

	static final float PLAYER_HEIGHT = 1.8f;
	/**
	 * Distances are as {@link #distance} reads them: to the front of the opponent, which is within a
	 * tenth of a block of what the game measures a sword's reach to. Just beyond that reach:
	 */
	static final float CHASE_FROM = 3.2f;
	/**
	 * The farthest it jumps at an opponent that keeps its distance from it entirely. A running jump
	 * gains little on a run: its first tick covers 0.48 of a block instead of 0.28, and after that
	 * the air carries it at 0.29 a tick. By the tick it is coming down with the sprint let go of, that
	 * is a quarter of a block more than the run would have made. (The first version of this took it
	 * for a whole block, jumped from 3.8 to 4.3 blocks at an opponent backing off, and came down out
	 * of reach: one critical hit from 29 such jumps.)
	 */
	static final float CHASE_TO = 3.1f;
	/** And this many ticks' worth of what its run is closing on top of that: it goes on closing in the air. */
	static final float CHASE_TICKS_CLOSING = 8f;
	static final float CHASE_TO_MOST = 4.3f;
	/** Closing by more than this a tick, the opponent is coming in itself, and the hit to make is the sprint hit; see {@code Config.chaseClosingMost}. */
	static final float CLOSING_MOST = 0.31f;
	/**
	 * Closing by no more than this on an opponent that is going away at a sprint
	 * ({@link #PURSUIT_SPEED}), which is only ever forwards, so with its back turned and nothing it
	 * can swing at: about what a string of running jumps gains on a sprint. After that one it jumps
	 * from any distance up to {@link #PURSUIT_TO}, one running jump after the other, which is the
	 * only way it gets any nearer; whichever of them brings the opponent into reach ends in the hit.
	 * (At one that backs off with its eyes on the fighter it jumps only from where the way down
	 * brings it into reach: coming in on the way up, it would be hit out of the air.)
	 */
	static final float PURSUIT_CLOSING_MOST = 0.12f;
	/**
	 * And going away at no less than this itself: faster than anyone walks backwards (0.22), so at a
	 * sprint and with its back turned. Without this a fighter that was itself slow for a moment took
	 * an opponent backing off with its sword ready for one that was running, and jumped at it from
	 * anywhere: against a copy of itself that was three jumps a minute, more than half of them hit
	 * out of the air, and none of it held back by having been hit before.
	 */
	static final float PURSUIT_SPEED = 0.25f;
	static final float PURSUIT_TO = 14f;
	/** At a run for this many ticks before it jumps. */
	static final int RUN_TICKS = 4;
	static final float RUN_SPEED = 0.2f;
	/** The sword at least this far back when it takes off, so that it is back when it comes down. */
	static final float CHASE_CHARGE = 0.45f;
	/** The opponent no farther off the crosshair than this, in half heights of the picture. */
	static final float SIDE_MOST = 0.25f;
	static final float FACE_SIDE_MOST = 0.4f;
	/** In its face: no farther off than this. */
	static final float FACE_WITHIN = 2.0f;
	static final int FACE_AFTER_HIT_TICKS = 6;
	/**
	 * And it has not been hit for this long: two seconds. An opponent that is hitting it is answered
	 * with the punish crit, which needs no jump; one that jumps at such an opponent is hit out of
	 * the air (in the first numbers, in half of these jumps). This jump is for the one that stays in
	 * its face and does nothing about it.
	 */
	static final int FACE_UNHIT_TICKS = 40;
	/** Whether a hit has sent the opponent away is judged this many ticks after it landed: as the sword comes back. */
	static final int STAY_JUDGED_TICKS = 11;
	/** Still no farther off than this by then, it has stayed. */
	static final float STAY_WITHIN = 2.4f;
	/**
	 * After this many hits in a row that it stayed through, the opponent is one that stays. Two: a
	 * knockback that comes a full half second late has the opponent still standing there as the sword
	 * comes back, once; the hit after that it is gone.
	 */
	static final int STAYS_NEEDED = 2;
	/** Falling faster than this (blocks a tick) it is down on the next tick: too late to let go of the sprint for a critical hit. */
	static final float LAND_SOON = -0.31f;
	/** An uppercut takes off with the sword this far back, so that it is back while the jump is still going up. */
	static final float UPPER_CHARGE_MIN = 0.6f;
	static final float UPPER_CHARGE_MAX = 0.97f;
	/** A sword takes this many ticks to come back. */
	static final float SWORD_TICKS = 12.5f;
	/**
	 * How far off the opponent may be when it takes off: within a sword's reach and the first tick of
	 * the jump, and a quarter of a block more for every tick the sword still needs, which the jump
	 * goes on covering. Farther than that the sword is back before the opponent is in reach, the way
	 * up is over, and no uppercut comes of it: with a flat five blocks, as at first, one of these
	 * jumps in four ended in one.
	 */
	static final float UPPER_REACH = 3.2f;
	static final float UPPER_PER_TICK = 0.25f;
	/** Not at an opponent that is running in itself: that one is in reach on the ground. */
	static final float UPPER_CLOSING_MOST = 0.4f;
	/** An uppercut follows a combo of at least this many hits: it is the next one. */
	static final int UPPER_COMBO = 2;
	static final float UPPER_FROM = 2.6f;
	static final int NOT_AFTER_HIT_TAKEN_TICKS = 12;
	/**
	 * Hit in the air of a jump made for it, that kind of jump is not made again for this long, thirty
	 * seconds: the opponent may be one that hits a fighter out of the air. Hit out of the air a second
	 * time, it is one, and that kind of jump is not made at it again for five minutes
	 * ({@link #OFF_FOR_GOOD_TICKS}), from one round to the next against the same opponent
	 * ({@link #newRound}); after that it finds out again.
	 *
	 * <p>Fifteen seconds and no more at first. Against a copy of itself, which swings at whatever
	 * comes into its reach, more than half of these jumps were hit out of the air, again and again in
	 * the same fight: about one in four ended in a critical hit.
	 */
	static final int CHASE_OFF_TICKS = 600;
	static final int FACE_OFF_TICKS = 600;
	static final int OFF_FOR_GOOD_TICKS = 6000;
	/**
	 * A jump after an opponent that is running is not made sooner than this after a hit of its own:
	 * an opponent that a hit has just thrown back is going away fast too, and is no runner.
	 */
	static final int CHASE_AFTER_HIT_TICKS = 15;

	/**
	 * From this far off it takes a running jump at an opponent that is not itself coming in at a run,
	 * and another each time it lands while it is still this far ({@link #approachHops}).
	 */
	static final float HOP_FROM = 5.0f;
	static final float HOP_CLOSING_MOST = 0.4f;
	/**
	 * Hit in the air of one of these, it leaves them alone for ten seconds, and for a minute after
	 * the third time. They have a reckoning of their own: hit out of the air at the end of a jump at
	 * an opponent in its reach, the chase jump is left alone for five minutes, and up to 14 October
	 * these went with it, so that after two such hits it never ran in with a jump at all.
	 */
	static final int HOP_OFF_TICKS = 200;
	static final int HOP_OFF_LONG_TICKS = 1200;
	static final int HOP_BURNS_LONG = 3;
	/**
	 * A running jump from five blocks off does not wait for the run: it is made once the fighter has
	 * been going at the opponent for this many ticks at this speed, sprint key or not, and the rule
	 * presses W and the sprint key with it. On 14 October, with the opponent that far and nothing
	 * else in the way, it was waiting for the run on 80 ticks in 100 (340 of 425), and made 9.
	 */
	static final int HOP_HEADING_TICKS = 2;
	/** Crit back only with the opponent's sword this far back at most: swung five ticks ago or less. */
	static final float COUNTER_OPP_MOST = 0.4f;
	/** Its own sword this far back at least: all the way back by the way down, seven ticks on. */
	static final float COUNTER_CHARGE = 0.55f;
	/** Not from nearer than this: there the hit to make is the one in reach now. */
	static final float COUNTER_FROM = 2.6f;
	static final float HOP_HEADING_SPEED = 0.08f;
	/**
	 * For {@link #onlyAtSpentOrFleeing}: the opponent's sword, as the fighter sees it, counts as spent up to here. The fighter
	 * sees its swing two or three ticks late, and a jump takes six to eight ticks to come down: a sword read higher than
	 * this is back by then (22 October, the first trial with 0.9 here: all 48 chases and hops of the first 27 fights
	 * were still made at a sword the server had back).
	 */
	static final float OPP_SPENT = 0.45f;
	/**
	 * And the opponent counts as running away going off at this much a tick or more, blocks, with no hit of the fighter's
	 * in the last {@link #CHASE_AFTER_HIT_TICKS} ticks: an opponent flying back from that hit is not running.
	 */
	static final float FLEEING = 0.24f;
	/**
	 * And it has been on the ground (moving up or down at less than {@link Tactic#AIR_SPEED}) for this many ticks: one
	 * flying back from a hit is still going off that fast fifteen ticks after it (22 October, second trial: all 25
	 * chases and hops at a ready sword were taken 15 or 16 ticks after the fighter's hit, the opponent still in the air).
	 */
	static final int FLEE_GROUND_TICKS = 3;
	/**
	 * The crit in ({@link #EDGE}): the reach, to the opponent's front as {@link #distance} reads it. Measured in the traces
	 * of 22 October: of 3,917 hits landed, none farther than 3.0 and all but 54 nearer than 2.95; misses begin at 2.9.
	 */
	static final float EDGE_REACH = 2.95f;
	/** It comes into reach this many ticks after the take-off at the soonest and the latest: low in the fall. */
	static final float EDGE_K_MIN = 9f;
	static final float EDGE_K_MAX = 11.5f;
	/**
	 * How a running jump carries it, measured in the traces of 22 October: this much on the first tick, then on each tick
	 * the speed it closed at before plus {@link #EDGE_PUSH}, between {@link #EDGE_AIR_LEAST} and {@link #EDGE_AIR_MOST}
	 * (from a stand 0.38, then 0.21-0.25 a tick; at a run of 0.3 a tick 0.65, then 0.43-0.45). The first version took
	 * 0.2 a tick from a stand and took off from 4.8 blocks: in reach at the top of the jump, hit first.
	 */
	static final float EDGE_FIRST = 0.15f;
	static final float EDGE_PUSH = 0.15f;
	static final float EDGE_AIR_LEAST = 0.22f;
	static final float EDGE_AIR_MOST = 0.45f;
	/** The opponent moving towards or away from it at no more than this, blocks a tick: standing. */
	static final float EDGE_STILL = 0.06f;
	/** Not from farther than this. */
	static final float EDGE_TO = 8f;
	/** Its own sword this far back at least: all the way back by the swing, ten ticks on. */
	static final float EDGE_CHARGE = 0.3f;
	/** On the way down, W and the sprint key are let go of from this near: the sprint is off by the tick it comes into reach. */
	static final float EDGE_LET_GO = 3.4f;
	/** Hit in the air before its own swing: left alone for this long, and for good after {@link #EDGE_BURNS} of those. */
	static final int EDGE_OFF_TICKS = 100;
	static final int EDGE_BURNS = 4;
	/**
	 * The crit in from a stand: just outside the reach of a still opponent, no faster than {@link #EDGE_STAND_SPEED}, a jump
	 * in place, W from the top of it, which drifts it a third of a block in by the time it lands: into reach low in the fall.
	 */
	static final float EDGE_STAND_FROM = 2.98f;
	static final float EDGE_STAND_TO = 3.3f;
	static final float EDGE_STAND_SPEED = 0.08f;
	/** Its own sword this far back at least for that: all the way back ten ticks on. */
	static final float EDGE_STAND_CHARGE = 0.6f;
	/** The opponent's sword, as it is seen, at least this far back: the one whose sword is spent is gone in at at once. */
	static final float EDGE_OPP_READY = 0.9f;
	/**
	 * Coming up to that edge from no farther than {@link #EDGE_APPROACH}: W let go of where the fighter would come to a stop
	 * at {@link #EDGE_STOP_AT} (on the ground it stops in about 1.2 ticks' worth of its speed), walked in from a stand.
	 */
	static final float EDGE_APPROACH = 4.6f;
	static final float EDGE_STOP_AT = 3.12f;
	static final float EDGE_STOP_FACTOR = 1.2f;
	/**
	 * The combo jump ({@link #COMBO}), as the user makes it in their 29 won fights against the spacer (recorded fights,
	 * 23 October): 16.5 jumps of their own a fight, taken a median 3 ticks after their own hit, the sword at 0.32, the
	 * opponent 2.5 blocks off (10-90%: 1.3 to 3.9) and going away at 0.14 a tick; W and the sprint key down all the way;
	 * 72% of them land the next hit, on the way down. The fighter made 2.3 jumps of its own a fight against the same
	 * opponent and landed 29.6 hits to their 38.2.
	 */
	static final int COMBO_AFTER_MIN = 1;
	static final int COMBO_AFTER_MAX = 8;
	static final float COMBO_FROM = 1.6f;
	static final float COMBO_TO = 3.8f;
	/**
	 * The sword at least this far back at the take-off ({@link #comboJumpChargeMin}), so that it is back in the middle of the
	 * way down, and no further than {@link #COMBO_CHARGE_MAX}: a sword that is back already is for a hit from the ground.
	 * The user takes off at a median 0.32. The first trial (23 October) took off from 0.15, one or two ticks after the hit:
	 * the sword came back only as the fighter landed, in front of an opponent whose sword was ready, which hit it first on
	 * 49% of the jumps (13% landed a hit in the air); against the spacer -60 net a minute.
	 */
	static final float COMBO_CHARGE_MIN = 0.3f;
	static final float COMBO_CHARGE_MAX = 0.75f;
	/** Not hit for this many ticks. */
	static final int COMBO_UNHIT_TICKS = 3;
	/** Hit in the air of one, left alone for five seconds, and for half a minute after three of those. */
	static final int COMBO_OFF_TICKS = 100;
	static final int COMBO_OFF_LONG_TICKS = 600;
	static final int COMBO_BURNS_LONG = 3;
	/**
	 * The clash ({@link #CLASH}): the opponent within this, eye to front, its sword at least {@link #CLASH_OPP_READY} back,
	 * off the ground and coming at the fighter at {@link #CLASH_TOWARD} a tick or more; the fighter's own sword at least
	 * {@link #CLASH_CHARGE} back, which is all the way back by the top of its jump.
	 */
	static final float CLASH_TO = 4.5f;
	static final float CLASH_OPP_READY = 0.9f;
	static final float CLASH_TOWARD = 0.05f;
	static final float CLASH_CHARGE = 0.55f;

	private final boolean chase;
	/** See {@link #chaseInCombo}. */
	private boolean inCombo = true;
	/** See {@link #approachHops}. */
	private boolean hops;
	/** See {@link #counterCrit}. */
	private boolean counter;
	/** See {@link #onlyAtSpentOrFleeing}. */
	private boolean spentOrFleeing;
	/** Ticks the opponent has been on the ground, as its speed up or down shows it. */
	private int oppGroundTicks;
	/** See {@link #edgeCrit}. */
	private boolean edge;
	private int edgeOff;
	private int edgeBurns;
	/** The crit in it is in was taken from a stand ({@link #EDGE_STAND_FROM}). */
	private boolean edgeStand;
	/** See {@link #comboJump}. */
	private boolean comboJumps;
	private float comboChargeMin = COMBO_CHARGE_MIN;
	private int comboOff;
	private int comboBurns;
	/** See {@link #clashJump}. */
	private boolean clash;
	private int counterOff;
	private int counterBurns;
	private final boolean face;
	private final float upperChance;
	private final float closingMost;
	private final java.util.Random rng;

	private int kind = NONE;
	private boolean airborne;
	private boolean letGone;
	private int groundTicks;
	private int runTicks;
	private int headingTicks;
	/** Whether the hit it last landed is one an uppercut is to follow; drawn once per hit. */
	private boolean upperDrawn;
	private boolean upperNext;
	/** How many of its hits in a row have not sent the opponent away; see {@link #STAY_JUDGED_TICKS}. */
	private int stays;
	private int chaseOff;
	private int faceOff;
	private int chaseBurns;
	private int faceBurns;
	private int upperOff;
	private int upperBurns;
	private int hopOff;
	private int hopBurns;
	private int hopWhy = -1;
	private int unhitTicks = FACE_UNHIT_TICKS;
	private boolean stayJudged;
	/** The kind of jump the last decision took off on, or {@link #NONE}. */
	private int tookOff;

	public JumpIn(boolean chase, boolean face, float upperChance, long seed) {
		this(chase, face, upperChance, CLOSING_MOST, seed);
	}

	/** @param closingMost see {@code Config.chaseClosingMost} */
	public JumpIn(boolean chase, boolean face, float upperChance, float closingMost, long seed) {
		this.closingMost = closingMost;
		this.chase = chase;
		this.face = face;
		this.upperChance = upperChance;
		this.rng = new java.util.Random(seed);
	}

	/**
	 * Whether the chase jump is also the next hit of a combo of its own: it has just landed a hit,
	 * the opponent is beyond its reach and backing off, and a run would catch it only slowly. Then
	 * the jump is made as soon as the way down will bring the opponent into reach, and the hit is a
	 * critical one; and from there again. Against a player who backs out of combos with S that is a
	 * combo of critical hits, and it is what the players of the first evening of 0.14.0 did to this
	 * fighter. False: no jump at an opponent it has hit in the last fifteen ticks, as before.
	 */
	public void chaseInCombo(boolean on) {
		inCombo = on;
	}

	/**
	 * Whether it closes on an opponent five blocks off or more with running jumps, one after the
	 * other, instead of a run: they carry it a quarter faster, and the last one ends in a critical
	 * hit if its way down brings the opponent into reach. Not at an opponent that is coming in at a
	 * run itself: the two would meet with the fighter in the air.
	 */
	public void approachHops(boolean on) {
		hops = on;
	}

	/**
	 * Whether it crits back ({@link #COUNTER}): with the opponent's sword just spent and its own nearly
	 * back, a running jump at an opponent at the edge of the reach and the critical hit on the way down.
	 * Players who space it crit it from the edge and sprint-hit it, and before 19 October nothing it
	 * did came back at them in their cooldown with a crit. Hit in the air of one, it leaves them alone
	 * for a while, as with the chase.
	 */
	public void counterCrit(boolean on) {
		counter = on;
	}

	/**
	 * Whether the chase and the hop are made only at an opponent whose sword is spent ({@link #OPP_SPENT}) or that is
	 * running away ({@link #FLEEING}); see {@code Config.jumpAtReadyOnlyFleeing}. An opponent standing its ground with
	 * its sword ready hits a running jump out of the air before it comes down.
	 */
	public void onlyAtSpentOrFleeing(boolean on) {
		spentOrFleeing = on;
	}

	/**
	 * Whether it crits in ({@link #EDGE}): at an opponent standing still on the ground, a running jump taken from where the
	 * way down brings it into reach low in the fall, and the critical hit as it comes into reach; see {@code Config.edgeCritRule}.
	 * Against an opponent that stands and hits whatever comes into its reach the exchange is otherwise a sprint hit for a
	 * hit, 7 for 7 (fight traces, 22 October: 16.6 trades a fight, 0.3 crits); come down into its reach late in a jump
	 * and it is a critical hit for a hit, 10.5 for 7. Not near the top of the jump: there the fighter's eye is high above
	 * the opponent's head and its reach to it shorter than the opponent's to it, which is how every hop at that opponent
	 * was hit out of the air.
	 */
	public void edgeCrit(boolean on) {
		edge = on;
	}

	/**
	 * Whether it makes the combo jump ({@link #COMBO}): a few ticks after its own hit, a sprint-jump after the opponent and
	 * the next hit on the way down, as the user does; see {@code Config.comboJumpRule}. Where the fighter let an opponent
	 * its hit had thrown back go and waited, the user goes after it in the air and keeps the combo going.
	 */
	public void comboJump(boolean on) {
		comboJumps = on;
	}

	/**
	 * Whether it jumps with an opponent that jumps in at it ({@link #CLASH}); see {@code Config.clashJumpRule}. The user,
	 * 23 October: a player's first move is the jump in for the crit, and the other's is the same, so the two jump in
	 * together, and close in the crit for crit is the better of it: the opponent's crit throws the fighter back, but late,
	 * and its own sword is back, so it is still in reach for its own. Before this the fighter met that jump from the ground
	 * with a hit to knock it away (the meet rule), and that hit did not stop the crit coming down on it.
	 */
	public void clashJump(boolean on) {
		clash = on;
	}

	/** Whether it makes the clash jump; see {@link #clashJump}. */
	public boolean clashes() {
		return clash;
	}

	/** How far back the sword must be for the combo jump to take off; see {@link #COMBO_CHARGE_MIN} and {@code Config.comboJumpChargeMin}. */
	public void comboJumpChargeMin(float charge) {
		comboChargeMin = charge;
	}

	public void reset() {
		kind = NONE;
		airborne = false;
		letGone = false;
		groundTicks = 0;
		runTicks = 0;
		headingTicks = 0;
		upperDrawn = false;
		upperNext = false;
		stays = 0;
		chaseOff = 0;
		faceOff = 0;
		chaseBurns = 0;
		faceBurns = 0;
		upperOff = 0;
		upperBurns = 0;
		hopOff = 0;
		hopBurns = 0;
		counterOff = 0;
		counterBurns = 0;
		edgeOff = 0;
		edgeBurns = 0;
		comboOff = 0;
		comboBurns = 0;
		oppGroundTicks = 0;
		unhitTicks = FACE_UNHIT_TICKS;
		stayJudged = false;
		tookOff = NONE;
	}

	/** Whether its last hits left the opponent on top of it: the crit in its face is on. */
	public boolean stayed() {
		return stays >= STAYS_NEEDED;
	}

	/**
	 * A new round against the same opponent: everything starts over but what it has found out about
	 * how that opponent answers its jumps. A player who hit it out of the air in the last round will
	 * do it again in this one.
	 */
	public void newRound() {
		int chaseWas = chaseOff;
		int faceWas = faceOff;
		int chaseBurnsWere = chaseBurns;
		int faceBurnsWere = faceBurns;
		int upperWas = upperOff;
		int upperBurnsWere = upperBurns;
		int hopWas = hopOff;
		int hopBurnsWere = hopBurns;
		int counterWas = counterOff;
		int counterBurnsWere = counterBurns;
		int edgeWas = edgeOff;
		int edgeBurnsWere = edgeBurns;
		int comboWas = comboOff;
		int comboBurnsWere = comboBurns;
		reset();
		comboOff = comboWas;
		comboBurns = comboBurnsWere;
		counterOff = counterWas;
		counterBurns = counterBurnsWere;
		edgeOff = edgeWas;
		edgeBurns = edgeBurnsWere;
		hopOff = hopWas;
		hopBurns = hopBurnsWere;
		chaseOff = chaseWas;
		faceOff = faceWas;
		chaseBurns = chaseBurnsWere;
		faceBurns = faceBurnsWere;
		upperOff = upperWas;
		upperBurns = upperBurnsWere;
	}

	/** The jump it is in, or {@link #NONE}. */
	public int kind() {
		return kind;
	}

	/**
	 * For the log: on a tick on the ground with the opponent in view five to {@code PURSUIT_TO} blocks
	 * off, what kept the last call of {@link #plan} from a running jump at it (0: nothing, 1: not at a
	 * run yet, 2: hit lately, 3: off to the side, 4: sword not back, 5: left alone after a hit in the
	 * air, 6: the opponent coming in fast); -1 on any other tick.
	 */
	public int hopWhy() {
		return hopWhy;
	}

	/** The kind of jump the last call of {@link #plan} took off on, or {@link #NONE}. */
	public int tookOff() {
		return tookOff;
	}

	/**
	 * How far away the opponent is, read off its height on screen; very far if it is not on screen.
	 * What is tall on screen is the side of it that faces the fighter, so this is the distance to
	 * its front: a quarter of a block less than to its middle.
	 */
	public static float distance(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f) {
			return Float.MAX_VALUE;
		}
		float height = vec[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] - vec[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM];
		return height <= 1e-4f ? Float.MAX_VALUE : ViewGeometry.apparentSize(PLAYER_HEIGHT, 1f) / height;
	}

	/**
	 * One decision.
	 *
	 * @param vec        what the network is shown on this tick
	 * @param keys       the keys that are the network's on this tick, after the rule about the sword's charge
	 * @param onOpponent whether the game has the opponent under the crosshair and within reach
	 * @param busy       the tick is another rule's: it owes the opponent a swing, or is backing out of a combo
	 * @param combo      hits it has landed in a row without taking one
	 * @return what this tick is to do, as flags; 0 leaves everything to the network
	 */
	public int plan(float[] vec, int keys, boolean onOpponent, boolean busy, int combo) {
		tookOff = NONE;
		hopWhy = -1;
		boolean ground = scalar(vec, ObsSpec.SC_ON_GROUND) >= 0.5f;
		boolean sprinting = scalar(vec, ObsSpec.SC_SPRINTING) >= 0.5f;
		boolean seen = vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
		float velUp = scalar(vec, ObsSpec.SC_VEL_UP) / BodyState.VELOCITY_SCALE;
		float charge = scalar(vec, ObsSpec.SC_ATTACK_CHARGE);
		boolean atARun = ground && sprinting && seen && scalar(vec, ObsSpec.SC_VEL_FORWARD) / BodyState.VELOCITY_SCALE >= RUN_SPEED;
		int ranFor = runTicks;
		runTicks = atARun ? runTicks + 1 : 0;
		int headedFor = headingTicks;
		headingTicks = ground && seen && scalar(vec, ObsSpec.SC_VEL_FORWARD) / BodyState.VELOCITY_SCALE >= HOP_HEADING_SPEED ? headingTicks + 1 : 0;
		float sinceDealt = scalar(vec, ObsSpec.SC_SINCE_HIT_DEALT);
		if (sinceDealt >= fade(1)) {
			stayJudged = false;
		} else if (!stayJudged && sinceDealt <= fade(STAY_JUDGED_TICKS)) {
			// The sword is coming back: has that hit sent the opponent anywhere?
			stayJudged = true;
			stays = sinceDealt > 0f && seen && distance(vec) <= STAY_WITHIN ? stays + 1 : 0;
		}
		if (sinceDealt < 0.9f) {
			upperDrawn = false;
		} else if (!upperDrawn) {
			// It has just landed a hit: whether an uppercut follows this one is drawn now.
			upperDrawn = true;
			upperNext = upperChance > 0f && rng.nextFloat() < upperChance;
		}

		unhitTicks = scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fade(1) ? 0 : Math.min(unhitTicks + 1, 10_000);
		float oppUp = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		oppGroundTicks = Math.abs(oppUp) < Tactic.AIR_SPEED ? Math.min(oppGroundTicks + 1, 10_000) : 0;
		// Once the time it leaves a jump alone for is up, it is ready to find out again.
		if (chaseOff > 0 && --chaseOff == 0 && chaseBurns >= 2) {
			chaseBurns = 0;
		}
		if (faceOff > 0 && --faceOff == 0 && faceBurns >= 2) {
			faceBurns = 0;
		}
		if (upperOff > 0 && --upperOff == 0 && upperBurns >= 2) {
			upperBurns = 0;
		}
		if (hopOff > 0 && --hopOff == 0 && hopBurns >= HOP_BURNS_LONG) {
			hopBurns = 0;
		}
		if (counterOff > 0 && --counterOff == 0 && counterBurns >= 2) {
			counterBurns = 0;
		}
		if (edgeOff > 0) {
			edgeOff--;
		}
		if (comboOff > 0 && --comboOff == 0 && comboBurns >= COMBO_BURNS_LONG) {
			comboBurns = 0;
		}
		if (kind == EDGE && (busy || unhitTicks == 0)) {
			// Hit in the air of it: a trade if its own hit has landed in the last three ticks (the crit in is a crit for a
			// hit), and too late if not.
			if (unhitTicks == 0 && sinceDealt < fade(3)) {
				edgeOff = ++edgeBurns >= EDGE_BURNS ? OFF_FOR_GOOD_TICKS : EDGE_OFF_TICKS;
			}
			kind = NONE;
			return 0;
		}
		if (kind != NONE) {
			// Hit in the air of it (whether or not there is a swing to be made back), or the tick is another rule's.
			if (busy || unhitTicks == 0) {
				// What follows is the swing it owes, which has a rule of its own. And this opponent is
				// one to be careful with. (Up to the last day of 0.14.0 this was only noticed when there
				// was a swing to be made back at once, which is the lesser part of the times: against a
				// copy of itself it went on being hit out of the air nearly twice a minute.)
				if (kind == FACE) {
					faceOff = ++faceBurns >= 2 ? OFF_FOR_GOOD_TICKS : FACE_OFF_TICKS;
				} else if (kind == HOP) {
					hopOff = ++hopBurns >= HOP_BURNS_LONG ? HOP_OFF_LONG_TICKS : HOP_OFF_TICKS;
				} else if (kind == COUNTER) {
					counterOff = ++counterBurns >= 2 ? OFF_FOR_GOOD_TICKS : CHASE_OFF_TICKS;
				} else if (kind == COMBO) {
					comboOff = ++comboBurns >= COMBO_BURNS_LONG ? COMBO_OFF_LONG_TICKS : COMBO_OFF_TICKS;
				} else if (kind == CLASH) {
					// Hit in the air of a clash is what a clash is: the crit back is the punish crit, the swing it owes.
				} else if (kind == UPPER) {
					// The uppercut is counted by itself: it is made in the middle of a combo, at an opponent
					// that has just been hit, and what that opponent does about it says nothing about a
					// jump made at it when it is ready.
					upperOff = ++upperBurns >= 2 ? OFF_FOR_GOOD_TICKS : CHASE_OFF_TICKS;
				} else {
					chaseOff = ++chaseBurns >= 2 ? OFF_FOR_GOOD_TICKS : CHASE_OFF_TICKS;
				}
				kind = NONE;
				return 0;
			}
			if (!ground) {
				airborne = true;
				return inTheAir(keys, onOpponent, velUp, sprinting, distance(vec));
			}
			if (airborne || ++groundTicks > 2) {
				// Down again, or it never left the ground.
				if (airborne && (kind == CHASE || kind == HOP)) {
					// Straight into the next one, if the opponent is still getting away.
					ranFor = RUN_TICKS;
				}
				kind = NONE;
			} else {
				return kind == FACE || kind == CLASH || kind == EDGE && edgeStand ? LET_GO | NO_SWING : RUN | NO_SWING;
			}
		}
		if (!ground || busy || !seen || !ActionSpace.unlocked(keys, ActionSpace.JUMP)) {
			return 0;
		}
		float d = distance(vec);
		float side = Math.abs(vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_X]);
		boolean comboed = BackNudge.inCombo(vec);
		// The clash: an opponent jumping in at it with its sword ready is jumped with, before anything else.
		float oppToward = -(scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
		if (clash && !comboed && oppGroundTicks == 0 && scalar(vec, ObsSpec.SC_OPP_CHARGE) >= CLASH_OPP_READY && charge >= CLASH_CHARGE
				&& oppToward >= CLASH_TOWARD && d <= CLASH_TO && side <= SIDE_MOST) {
			start(CLASH);
			return JUMP | LET_GO | NO_SWING;
		}
		if (face && faceOff <= 0 && unhitTicks >= FACE_UNHIT_TICKS && stayed() && !comboed && d <= FACE_WITHIN && side <= FACE_SIDE_MOST
				&& charge >= JumpNudge.MIN_CHARGE && charge <= JumpNudge.MAX_CHARGE
				&& sinceDealt >= fade(FACE_AFTER_HIT_TICKS)) {
			start(FACE);
			return JUMP | LET_GO | NO_SWING;
		}
		boolean notHitLately = scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) <= fade(NOT_AFTER_HIT_TAKEN_TICKS) && !comboed;
		// What the run is closing on the opponent, blocks a tick.
		float closing = -scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) / BodyState.VELOCITY_SCALE;
		if (upperNext && upperOff <= 0 && notHitLately && combo >= UPPER_COMBO && ranFor >= 1 && side <= SIDE_MOST
				&& charge >= UPPER_CHARGE_MIN && charge <= UPPER_CHARGE_MAX && closing <= UPPER_CLOSING_MOST
				&& d >= UPPER_FROM && d <= UPPER_REACH + UPPER_PER_TICK * (1f - charge) * SWORD_TICKS) {
			upperNext = false;
			start(UPPER);
			return JUMP | RUN | NO_SWING;
		}
		// The combo jump: a few ticks after its own hit, the sword on its way back, after the opponent that hit threw back.
		if (comboJumps && comboOff <= 0 && !comboed && unhitTicks >= COMBO_UNHIT_TICKS && side <= SIDE_MOST
				&& sinceDealt <= fade(COMBO_AFTER_MIN) && sinceDealt >= fade(COMBO_AFTER_MAX)
				&& charge >= comboChargeMin && charge <= COMBO_CHARGE_MAX && d >= COMBO_FROM && d <= COMBO_TO) {
			start(COMBO);
			return JUMP | RUN | NO_SWING;
		}
		// How fast the opponent itself is going away from the fighter, blocks a tick.
		float away =(scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
		boolean longAfterHit = sinceDealt <= fade(CHASE_AFTER_HIT_TICKS);
		// Not at an opponent that stands its ground with its sword ready, if so set.
		boolean jumpable = !spentOrFleeing || scalar(vec, ObsSpec.SC_OPP_CHARGE) <= OPP_SPENT
				|| away >= FLEEING && longAfterHit && oppGroundTicks >= FLEE_GROUND_TICKS;
		boolean window = (inCombo || longAfterHit) && closing <= closingMost
				&& d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing));
		boolean pursuit = longAfterHit && closing <= PURSUIT_CLOSING_MOST && away >= PURSUIT_SPEED && d <= PURSUIT_TO;
		if (chase && hops && d >= HOP_FROM && d <= PURSUIT_TO) {
			hopWhy = ranFor < RUN_TICKS && headedFor < HOP_HEADING_TICKS ? 1 : !notHitLately ? 2 : side > SIDE_MOST ? 3 : charge < CHASE_CHARGE ? 4
					: hopOff > 0 ? 5 : closing > HOP_CLOSING_MOST ? 6 : 0;
		}
		// Crit back: the opponent's sword has just been spent, its own is nearly back.
		if (counter && counterOff <= 0 && scalar(vec, ObsSpec.SC_OPP_CHARGE) <= COUNTER_OPP_MOST && charge >= COUNTER_CHARGE
				&& side <= SIDE_MOST && d >= COUNTER_FROM
				&& d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))) {
			start(COUNTER);
			return JUMP | RUN | NO_SWING;
		}
		// The crit in from a stand: just outside the reach of a still opponent whose sword is ready, a jump in place (W from
		// the top of it, in the air); and coming up to that edge, W let go of where the fighter will stop there.
		if (edge && edgeOff <= 0 && notHitLately && side <= SIDE_MOST && charge >= EDGE_STAND_CHARGE && oppGroundTicks >= FLEE_GROUND_TICKS
				&& Math.abs(away) <= EDGE_STILL && scalar(vec, ObsSpec.SC_OPP_CHARGE) >= EDGE_OPP_READY) {
			if (d >= EDGE_STAND_FROM && d <= EDGE_STAND_TO) {
				if (Math.abs(closing) <= EDGE_STAND_SPEED) {
					start(EDGE);
					edgeStand = true;
					return JUMP | LET_GO | NO_SWING;
				}
				// Still coasting in: let it come to a stop (22 October, first trial: W was handed back while it coasted,
				// and it walked on into the opponent's reach); going back: in again at a walk.
				return closing > 0f ? LET_GO : WALK;
			}
			if (d > EDGE_STAND_TO && d <= EDGE_APPROACH) {
				return d - EDGE_STOP_FACTOR * Math.max(0f, closing) <= EDGE_STOP_AT ? LET_GO : WALK;
			}
		}
		// The crit in: at an opponent standing still, from where the way down brings it into reach late in the fall.
		if (edge && edgeOff <= 0 && notHitLately && side <= SIDE_MOST && charge >= EDGE_CHARGE && oppGroundTicks >= FLEE_GROUND_TICKS
				&& Math.abs(away) <= EDGE_STILL && d <= EDGE_TO) {
			float carried = Math.max(EDGE_AIR_LEAST, Math.min(EDGE_AIR_MOST, Math.max(0f, closing) + EDGE_PUSH));
			float ticks = (d - EDGE_REACH - EDGE_FIRST) / carried;
			if (ticks >= EDGE_K_MIN && ticks <= EDGE_K_MAX) {
				start(EDGE);
				return JUMP | RUN | NO_SWING;
			}
		}
		if (chase && jumpable && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit)) {
			start(CHASE);
			return JUMP | RUN | NO_SWING;
		}
		if (chase && hops && jumpable && notHitLately && (ranFor >= RUN_TICKS || headedFor >= HOP_HEADING_TICKS) && side <= SIDE_MOST
				&& charge >= CHASE_CHARGE && hopOff <= 0
				&& closing <= HOP_CLOSING_MOST && d >= HOP_FROM && d <= PURSUIT_TO) {
			start(HOP);
			return JUMP | RUN | NO_SWING;
		}
		return 0;
	}

	private void start(int what) {
		kind = what;
		edgeStand = false;
		tookOff = what;
		airborne = false;
		letGone = false;
		groundTicks = 0;
	}

	private int inTheAir(int keys, boolean onOpponent, float velUp, boolean sprinting, float d) {
		boolean canSwing = ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		boolean falling = velUp < 0f;
		if (kind == EDGE && edgeStand) {
			// Up in place, W and the sprint key up; from the top W alone, drifting in, and the swing the moment it is in reach.
			if (!falling) {
				return NO_JUMP | LET_GO | NO_SWING;
			}
			return NO_JUMP | WALK | (onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
		}
		if (kind == EDGE) {
			if (!falling) {
				return NO_JUMP | RUN | NO_SWING;
			}
			if (letGone || onOpponent || d <= EDGE_LET_GO) {
				// Coming down near the reach: W and the sprint off, so that the swing, the moment it is in reach, is a crit.
				letGone = true;
				return NO_JUMP | LET_GO | (onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
			}
			return NO_JUMP | RUN | NO_SWING;
		}
		if (kind == FACE) {
			// W and the sprint key stay up from the take-off; the swing is for the way down.
			return NO_JUMP | LET_GO | (falling && onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
		}
		if (kind == CLASH) {
			// The sprint is off from the take-off; W alone holds it in against the hit that is coming, and the swing on the way down.
			return NO_JUMP | WALK | (falling && onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
		}
		if (kind == COMBO) {
			// At a run all the way, as the user goes; the hit on the way down, a sprint hit that throws the opponent back again.
			return NO_JUMP | RUN | (falling && onOpponent && canSwing ? SWING : NO_SWING);
		}
		if (!falling) {
			if (kind == UPPER && onOpponent && sprinting && canSwing) {
				// Still going up, at a sprint, the sword back and the opponent in reach: the uppercut.
				return NO_JUMP | RUN | SWING;
			}
			return NO_JUMP | RUN | NO_SWING;
		}
		if (letGone) {
			// The sprint is off, or will be by the next tick: from then on a swing is a critical hit.
			return NO_JUMP | LET_GO | (onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
		}
		if (onOpponent) {
			if (velUp > LAND_SOON) {
				letGone = true;
				return NO_JUMP | LET_GO | (!sprinting && canSwing ? SWING : NO_SWING);
			}
			// About to land with the opponent in reach: the sprint hit now, rather than a tick later from the ground.
			return NO_JUMP | RUN | (sprinting && canSwing ? SWING : NO_SWING);
		}
		return NO_JUMP | RUN | NO_SWING;
	}

	private static float scalar(float[] vec, int index) {
		return vec[ObsSpec.VEC_SCALARS + index];
	}

	/** What a clock in the observation shows this many ticks after the event. */
	private static float fade(int ticks) {
		return 1f - ticks / BodyState.EVENT_FADE_TICKS;
	}

	/** Takes from the network the keys this tick's plan decides. */
	public static int keys(int keys, int plan) {
		if ((plan & (JUMP | NO_JUMP)) != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.JUMP);
		}
		if ((plan & (RUN | LET_GO | WALK)) != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		if ((plan & (SWING | NO_SWING)) != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		return keys;
	}

	/** Writes this tick's plan into the action the network chose for the keys it was left. */
	public static void apply(int[] action, int plan) {
		if ((plan & JUMP) != 0) {
			action[ActionSpace.JUMP] = 1;
		} else if ((plan & NO_JUMP) != 0) {
			action[ActionSpace.JUMP] = 0;
		}
		if ((plan & RUN) != 0) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 1;
		} else if ((plan & LET_GO) != 0) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		} else if ((plan & WALK) != 0) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 0;
		}
		if ((plan & SWING) != 0) {
			action[ActionSpace.ATTACK] = 1;
		} else if ((plan & NO_SWING) != 0) {
			action[ActionSpace.ATTACK] = 0;
		}
	}
}
