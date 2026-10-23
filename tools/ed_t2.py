import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'

e = Ed(R + 'config/Config.java')
e.rep("""	public float anchorBoostWeight = 0f;
""", """	public float anchorBoostWeight = 0f;
	/**
	 * At 0 or above: on a step whose action did better than the critic expected (advantage above 0), the
	 * anchor's weight is at most this. The anchor then holds the network back where it does worse than
	 * expected and not where it finds something better: a floor, not a ceiling. Below 0, off: the anchor
	 * pulls the same on every step. Since 21 October evening (suggested in a review the user passed on).
	 */
	public float anchorGainWeight = -1f;
""")
e.rep("""	/** Its entropy bonus: keeps it trying every way now and then. */
	public float tacticEntropy = 0.01f;
""", """	/** Its entropy bonus: keeps it trying every way now and then. */
	public float tacticEntropy = 0.01f;
	/**
	 * The tactics model also chooses again right after a hit either way, a swing of the opponent's and a
	 * jump of the opponent's (at the least {@code rl.Tactic.LEAST} ticks after its last choice), not only
	 * every half second; see {@code rl.Tactic.Clock}.
	 */
	public boolean tacticOnEvents = true;
""")
e.save()

e = Ed(R + 'rl/Ppo.java')
e.rep("""					float anchorW = anchorWeight(r.task[i]);
""", """					float anchorW = anchorWeight(r.task[i]);
					if (cfg.anchorGainWeight >= 0f && r.advantage[i] > 0f) {
						// Better than expected: held back by the anchor no more than this; see Config.anchorGainWeight.
						anchorW = Math.min(anchorW, cfg.anchorGainWeight);
					}
""")
e.save()

e = Ed(R + 'rl/Tactic.java')
e.rep("""package dev.swordai.core.rl;
""", """package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
""")
e.rep("""	/** Ticks between two choices; the choice stands in between. Half a second, about the time a sword takes to come back. */
	public static final int EVERY = 10;
""", """	/** Ticks between two choices; the choice stands in between. Half a second, about the time a sword takes to come back. */
	public static final int EVERY = 10;
	/** With choices on events too ({@link Clock#due(float[])}), a choice stands at least this many ticks. */
	public static final int LEAST = 3;
	/** The opponent moving up or down at this much a tick or more is off the ground (as {@link StepBack} reads it). */
	static final float AIR_SPEED = 0.06f;
""")
e.rep("""	/** When the next choice is due, for one fighter. */
	public static final class Clock {
		private int ticks = EVERY;
		private int current = -1;

		/** A new fight or round: the next tick chooses afresh. */
		public void reset() {
			ticks = EVERY;
			current = -1;
		}
""", """	/** When the next choice is due, for one fighter. */
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
""")
e.save()

e = Ed(R + 'rl/Fighter.java')
e.rep("""	private final boolean stepBackRule;
""", """	private final boolean stepBackRule;
	private final boolean tacticOnEvents;
""")
e.rep("""		this.stepBackRule = cfg.stepBackRule;
""", """		this.stepBackRule = cfg.stepBackRule;
		this.tacticOnEvents = cfg.tacticOnEvents;
""")
e.rep("""			if (tacticClock.due()) {
				tacticClock.set(learner.chooseTactic(obs, false));""", """			if (tacticClock.due(obs.vec, tacticOnEvents)) {
				tacticClock.set(learner.chooseTactic(obs, false));""")
e.save()

e = Ed('D:/swordai/src/client/java/dev/swordai/client/Trainer.java')
e.rep("""			if (tacticClock.due()) {
				int chosen;""", """			if (tacticClock.due(obs.vec, cfg.tacticOnEvents)) {
				int chosen;""")
e.save()
