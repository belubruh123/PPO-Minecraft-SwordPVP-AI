package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * How it fights for the next half second: the choice the tactics model ({@link TacticNet}) makes, and
 * what each choice turns on. Since 21 October, at the user's suggestion: a model of its own whose one
 * job is to choose the way to fight, its choice handed to the acting network as an input.
 *
 * <p>Up to then the three rules here were on or off for every fight by the config, and each was
 * measured over all opponents at once. The crit back ({@link JumpIn#COUNTER}) took the jump-crit
 * chaser from -126 to -38 damage a minute in its first half hour of training and the timer from +133
 * to +102; holding at the edge ({@link EdgeHold}) and stepping back after a hit ({@link StepBack}) are
 * right against some opponents and wrong against others in the same way. A choice made per opponent
 * and per moment can keep each where it pays. In the language of hierarchical reinforcement learning
 * this is a manager choosing among options at its own pace (the options framework, Sutton, Precup and
 * Singh 1999; Option-Critic, Bacon et al. AAAI 2017; FeUdal Networks, Vezhnevets et al. ICML 2017),
 * each option here being one of these rule sets with the acting network told which.
 */
public final class Tactic {
	/** In and combo: none of the three rules. */
	public static final int PRESS = 0;
	/** Crit back in the opponent's cooldown ({@link JumpIn#COUNTER}). */
	public static final int COUNTER = 1;
	/** Space: wait at the edge of the reach while the opponent's sword is ready ({@link EdgeHold}), and crit back once it has swung. */
	public static final int SPACE = 2;
	/** Out after its own hit that has not sent the opponent away ({@link StepBack}). */
	public static final int BACK = 3;
	public static final int COUNT = 4;

	private static final String[] NAMES = {"press", "crit back", "space", "back off"};

	/** Ticks between two choices; the choice stands in between. Half a second, about the time a sword takes to come back. */
	public static final int EVERY = 10;
	/** With choices on events too ({@link Clock#due(float[], boolean)}), a choice stands at least this many ticks. */
	public static final int LEAST = 3;
	/** The opponent moving up or down at this much a tick or more is off the ground (as {@link StepBack} reads it). */
	static final float AIR_SPEED = 0.06f;

	private Tactic() {
	}

	public static String name(int tactic) {
		return tactic >= 0 && tactic < COUNT ? NAMES[tactic] : "none";
	}

	/**
	 * Whether it crits back under this tactic.
	 *
	 * @param tactic     the tactic in force, or -1 without the tactics model
	 * @param configured what the config says, which is what holds without the model
	 */
	public static boolean counters(int tactic, boolean configured) {
		return tactic < 0 ? configured : tactic == COUNTER || tactic == SPACE;
	}

	/** Whether it holds at the edge of the reach; see {@link #counters} for the arguments. */
	public static boolean holdsEdge(int tactic, boolean configured) {
		return tactic < 0 ? configured : tactic == SPACE;
	}

	/** Whether it steps back after its own hit; see {@link #counters} for the arguments. */
	public static boolean stepsBack(int tactic, boolean configured) {
		return tactic < 0 ? configured : tactic == BACK;
	}

	/** When the next choice is due, for one fighter. */
	public static final class Clock {
		private int ticks = EVERY;
		private int current = -1;
		private float lastOppCharge = 1f;
		private boolean oppOffGround;

		/** A new fight or round: the next tick chooses afresh. */
		public void reset() {
			ticks = EVERY;
			current = -1;
			lastOppCharge = 1f;
			oppOffGround = false;
		}

		/**
		 * Whether a choice is to be made on this tick, every {@link #EVERY} ticks and, with {@code onEvents},
		 * also on a tick where something has just happened that can change which way pays: a hit landed or
		 * taken, the opponent swinging (hit or miss), the opponent leaving the ground. A sword comes back in
		 * twelve and a half ticks and a fixed half second drifts against that; an exchange is better met
		 * with a choice made as it starts (in the options framework, an option ends where its subgoal
		 * changes; the review the user passed on on 21 October asked for exactly these interrupts). Counts the tick.
		 *
		 * @param vec the actor's input on this tick
		 */
		public boolean due(float[] vec, boolean onEvents) {
			boolean event = event(vec);
			if (ticks >= EVERY || current < 0 || onEvents && event && ticks >= LEAST) {
				ticks = 1;
				return true;
			}
			ticks++;
			return false;
		}

		private boolean event(float[] vec) {
			float fresh = 1f - 0.5f / BodyState.EVENT_FADE_TICKS;
			boolean hit = scalar(vec, ObsSpec.SC_SINCE_HIT_DEALT) >= fresh || scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fresh;
			float charge = scalar(vec, ObsSpec.SC_OPP_CHARGE);
			boolean swung = charge < lastOppCharge - 0.05f;
			lastOppCharge = charge;
			boolean seen = vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
			boolean off = seen && Math.abs(scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE >= AIR_SPEED;
			boolean jumped = off && !oppOffGround;
			oppOffGround = off;
			return hit || swung || jumped;
		}

		private static float scalar(float[] vec, int index) {
			return vec[ObsSpec.VEC_SCALARS + index];
		}

		/** Whether a choice is to be made on this tick; counts the tick. */
		public boolean due() {
			if (ticks >= EVERY || current < 0) {
				ticks = 1;
				return true;
			}
			ticks++;
			return false;
		}

		public int current() {
			return current;
		}

		public void set(int tactic) {
			current = tactic;
		}
	}
}
