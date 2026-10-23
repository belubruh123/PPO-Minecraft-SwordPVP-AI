package dev.swordai.core.rl;

import java.util.Random;

/**
 * A plan the fighter is handed for its strafe keys, so that it fights with its feet as well: which
 * side to go round the opponent on.
 *
 * <p>This is what players call AD strafing. One of A and D is held, and kept held: through the
 * swing, through the recharge, while the weapon is ready, and through the next swings too, for
 * {@link #MIN_CIRCLE_SWINGS} to {@link #MAX_CIRCLE_SWINGS} swings on end. The fighter goes round the
 * opponent while its combo lands and has to keep turning to keep it in front; the opponent has to
 * keep turning to hit back at all. Then comes another circle, to either side, or a straight run.
	 *
 * <p>A straight run is {@link #MIN_STRAIGHT_SWINGS} to {@link #MAX_STRAIGHT_SWINGS} swings with no
 * strafe at all: the W-tap combo, where the distance is kept with W alone (let go of after the hit,
 * pressed again to come back to the edge of reach as the sword comes back). The user, 18 October:
 * the strafe "dont need to be circle", "W tap combo as well cause like high tier player does it",
 * though "circle combo does do really well". About a quarter of the swings; up to then one swing
 * in seven between circles was made straight in, and nothing was asked of the keys on it.
 *
 * <p>(Up to 0.11.3 four plans in ten were something else: out to a side right after the swing and
 * back the other way before the next. That is not how the keys are used in a fight.)
 *
 * <p>A plan can also say how long to hold the next swing back once the weapon is ready
 * ({@link #allowHolds}). No stage uses that any more: a fighter that waits on a ready weapon gives
 * hits away, and it proved not to be something that can be learned from a penalty.
 *
 * <p>Nothing here says anything about the opponent. The plan is an input (see
 * {@link ObsSpec#SC_IDEA_STRAFE}) the network learns to follow; until it has, {@link #assist} applies
 * it to the chosen action directly.
 */
public final class MixIdea {
	/** The longest a swing is held back once the weapon is ready, in ticks. */
	public static final int MAX_HOLD_TICKS = 6;
	/** The weapon counts as ready from here: the charge above which a sprint hit knocks back. */
	public static final float READY_CHARGE = 0.9f;
	/** After a circle or a straight run, the chance that a straight run comes next (a quarter of the swings). */
	private static final float STRAIGHT_CHANCE = 0.35f;
	/** A straight run lasts this many swings. */
	public static final int MIN_STRAIGHT_SWINGS = 2;
	public static final int MAX_STRAIGHT_SWINGS = 5;
	/** A circle lasts this many swings, the one that starts it included. */
	public static final int MIN_CIRCLE_SWINGS = 3;
	public static final int MAX_CIRCLE_SWINGS = 8;
	/** With holds allowed: the share of swings that are not held back at all. */
	private static final float NO_HOLD_CHANCE = 0.4f;

	private boolean on;
	private boolean holds;
	private int side;
	/** The side is kept, not gone out to and come back from. */
	private boolean circling;
	/** Swings the running circle, or straight run, still has to come after the current one. */
	private int circleLeft;
	private int delay;
	/** Ticks in a row the weapon has been ready. */
	private int ready;
	private float charge = 1f;

	/** No plan: everything reads zero until {@link #start}. */
	public void clear() {
		on = false;
		side = 0;
		circling = false;
		circleLeft = 0;
		delay = 0;
		ready = 0;
		charge = 1f;
	}

	/**
	 * Whether a plan may also hold a swing back once the weapon is ready. Off unless set; takes effect
	 * from the next swing.
	 */
	public void allowHolds(boolean value) {
		holds = value;
	}

	/** A fresh plan for a new fight. */
	public void start(Random rng) {
		on = true;
		ready = 0;
		charge = 1f;
		circling = false;
		circleLeft = 0;
		roll(rng);
	}

	/** Once per decision, before the plan is read. */
	public void tick(Random rng, float charge) {
		if (!on) {
			start(rng);
		}
		this.charge = charge;
		ready = charge >= READY_CHARGE ? ready + 1 : 0;
	}

	/** The fighter has swung; the next swing gets a plan of its own. */
	public void swung(Random rng) {
		if (on) {
			roll(rng);
			ready = 0;
		}
	}

	private void roll(Random rng) {
		if (circleLeft > 0) {
			// The circle, or the straight run, goes on: the same for this swing too.
			circleLeft--;
		} else if (rng.nextFloat() < STRAIGHT_CHANCE) {
			side = 0;
			circling = false;
			circleLeft = MIN_STRAIGHT_SWINGS - 1 + rng.nextInt(MAX_STRAIGHT_SWINGS - MIN_STRAIGHT_SWINGS + 1);
		} else {
			side = rng.nextBoolean() ? 1 : -1;
			circling = true;
			circleLeft = MIN_CIRCLE_SWINGS - 1 + rng.nextInt(MAX_CIRCLE_SWINGS - MIN_CIRCLE_SWINGS + 1);
		}
		delay = !holds || rng.nextFloat() < NO_HOLD_CHANCE ? 0 : 1 + rng.nextInt(MAX_HOLD_TICKS);
	}

	public boolean on() {
		return on;
	}

	/** Whether the plan right now is to go round the opponent: one side, kept. */
	public boolean circling() {
		return on && circling && side != 0;
	}

	/** The strafe wanted right now: -1 left, +1 right, 0 none. */
	public int strafe() {
		return on ? side : 0;
	}

	/** Ticks the next swing should still be held back; counts down once the weapon is ready. */
	public int hold() {
		if (!on) {
			return 0;
		}
		return ready == 0 ? delay : Math.max(0, delay - ready + 1);
	}

	public void write(float[] vec) {
		ObsBuilder.writeScalar(vec, ObsSpec.SC_IDEA_STRAFE, strafe());
		ObsBuilder.writeScalar(vec, ObsSpec.SC_IDEA_HOLD, hold() / (float) MAX_HOLD_TICKS);
	}

	/**
	 * Carries the plan out on an action the network chose without knowing about it: the strafe key
	 * is set while the opponent is in view, and a swing that comes too early is dropped.
	 */
	public void assist(int[] action, int headMask, boolean enemyVisible) {
		if (!on) {
			return;
		}
		int strafe = strafe();
		if (strafe != 0 && enemyVisible && ActionSpace.unlocked(headMask, ActionSpace.STRAFE)) {
			action[ActionSpace.STRAFE] = strafe < 0 ? 1 : 2;
		}
		if (hold() > 0) {
			action[ActionSpace.ATTACK] = 0;
		}
	}
}
