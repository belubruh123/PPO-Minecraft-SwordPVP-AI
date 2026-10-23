package dev.swordai.core.rl;

/**
 * A push towards one choice in one situation, added to what the network itself says: jump, a few
 * ticks after it has swung, standing on the ground with the opponent on screen. A jump lasts about
 * as long as the weapon takes to come back, so one made then has the fighter coming down as the
 * next swing is ready, which is what a critical hit is made from.
 *
 * <p>The moment matters to the tick. A jump is on its way down from its 7th tick to its 11th, and
 * the weapon is back 11 ticks after the swing. Taken off 3 to 5 ticks after the swing, the fighter
 * has four or five ticks in which a swing is a critical hit; taken off with the swing or the tick
 * after, it has one, and has landed before it can use it.
 *
 * <p>Why it is needed: a control that has only just been unlocked is used at random, and a random
 * jump makes a fighter worse. So the first thing learned about jumping is not to, everywhere, long
 * before a jump at the right moment has been tried often enough to be told apart. Rewards alone do
 * not get past that.
 *
 * <p>The push is part of the policy, not a correction of its actions: it is added to the network's
 * output before an action is drawn and again, with the strength it had on that step, when the step
 * is learned from. So what is learned is exactly about the policy that was played, and the network
 * still decides: it can lean against the push or with it. The strength is set from outside (see
 * {@code Curriculum.jumpNudge}) and fades to nothing, at which point the network jumps on its own
 * or not at all.
 */
public final class JumpNudge {
	/** From this much recharge on (three ticks after the swing) a jump has not landed again before the weapon is back. */
	public static final float MIN_CHARGE = 0.2f;
	/** Up to this much recharge (five ticks after the swing) it is coming down by the time the weapon is back. */
	public static final float MAX_CHARGE = 0.45f;
	/**
	 * From this much recharge on, a swing is one the game can make a critical hit of: it asks for more
	 * than nine tenths half a tick on, which is so eleven ticks after the last swing.
	 */
	public static final float CRIT_CHARGE = 0.87f;
	private static final int YES = ActionSpace.OFFSETS[ActionSpace.JUMP] + 1;

	private JumpNudge() {
	}

	/**
	 * Whether the fighter is in the air by its own doing: off the ground, and not hit in the last
	 * twelve ticks (in the air after a hit is the opponent's doing).
	 */
	public static boolean inOwnJump(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] < 0.5f
				&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] <= 1f - BackNudge.TICKS / dev.swordai.core.env.BodyState.EVENT_FADE_TICKS;
	}

	/**
	 * Whether the fighter is off the ground, whoever put it there. For as long as it is, the swing is
	 * the crit model's (see {@code ActorNet}): also on the way up after the opponent's hit, where the
	 * swing has to be kept for the way down if a punish crit is to come of it.
	 */
	public static boolean inAir(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] < 0.5f;
	}

	/**
	 * Whether a critical hit can be in the making: the fighter is in a jump of its own, or it is off
	 * the ground and coming down, whoever put it there. Coming down after the opponent's hit has
	 * knocked it up is where a punish crit is made. In these ticks the sprint key is the crit model's,
	 * and W/S too unless it is busy getting out of a combo (see {@code ActorNet}).
	 */
	public static boolean critMoment(float[] vec) {
		return inAir(vec) && (inOwnJump(vec) || vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] < 0f);
	}

	/**
	 * Whether a swing made now, in the air, would be a critical hit as far as the fighter can tell
	 * from its own body: it is coming down, the weapon is back, and it is not sprinting. (The game
	 * asks for more than nine tenths of the charge half a tick on; eleven ticks after the last swing
	 * that is so.)
	 *
	 * <p>The sprint is part of it since 0.13.0. The game makes no critical hit of a swing by a
	 * sprinting player, and a fighter knocked into the air by a hit is sprinting more often than not.
	 * The crit model lets go of the sprint on its first tick coming down; the server hears of that at
	 * the end of the tick. A swing on that same tick is a sprint hit made in the air and uses the
	 * weapon up; one tick later it is the punish crit.
	 */
	public static boolean critSwingReady(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] < 0f
				&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] >= CRIT_CHARGE
				&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] < 0.5f;
	}

	/** Whether this is the situation the push is for, read from what the network itself is shown. */
	public static boolean applies(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f
				&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] >= MIN_CHARGE
				&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] <= MAX_CHARGE
				&& vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f;
	}

	/** Adds the push to the network's output for this observation. */
	public static void apply(float[] logits, float[] vec, float strength) {
		if (strength > 0f && applies(vec)) {
			logits[YES] += strength;
		}
	}
}
