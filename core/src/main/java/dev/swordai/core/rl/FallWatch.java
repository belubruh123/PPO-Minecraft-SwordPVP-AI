package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;

/**
 * What the fighter remembers of the last time a hit threw it into the air: whether the opponent
 * was still within a sword's reach while it came down. That says which hit this opponent makes. A
 * sprint hit sends the fighter three blocks off, and there is nothing in reach on the way down; a
 * critical hit, or a hit from one that keeps coming, leaves the opponent right there, and the way
 * down is where a punish crit is made.
 *
 * <p>It decides one thing ({@link AnswerRule#onTheGround}): hit with the opponent under its
 * crosshair and its feet still on the ground, which they are for a tick, does it swing at once or
 * keep the swing for the way down. At once is a trade, hit for hit. Kept, it is a critical hit, if
 * the opponent is there when it comes down, and nothing if not. So it is kept against an opponent
 * that was there the last time, and made at once against any other.
 *
 * <p>And it remembers one thing more ({@link #proven}): whether the last such fall on which it had
 * its sword back ended in a hit of its own, made in the air. That is an opponent a punish crit has
 * been made on, not only one that looked near enough for it. Against that one the swing is kept
 * for the way down even in the middle of its combo ({@link AnswerRule#when}); the first fall that
 * comes to nothing with the sword back, and it is no longer.
 *
 * <p>Read from what the network is shown. Not thread-safe; one per fighter.
 */
public final class FallWatch {
	/** Within this of the opponent, as {@link JumpIn#distance} reads it, a sword reaches. */
	static final float NEAR = 3.0f;

	private boolean watching;
	private boolean fell;
	private boolean sawNear;
	/** On the way down the opponent was in reach with the sword back: a punish crit was there to be made. */
	private boolean sawReady;
	private boolean lastNear;
	private boolean proven;

	public void reset() {
		watching = false;
		fell = false;
		sawNear = false;
		sawReady = false;
		lastNear = false;
		proven = false;
	}

	/** To be called with every decision's observation. */
	public void tick(float[] vec) {
		float sinceTaken = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN];
		boolean ground = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f;
		if (sinceTaken >= 1f - 0.5f / BodyState.EVENT_FADE_TICKS) {
			// Hit on this very tick: a new fall to watch.
			watching = true;
			fell = false;
			sawNear = false;
			sawReady = false;
			return;
		}
		if (!watching) {
			return;
		}
		if (fell && vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] >= 1f - 0.5f / BodyState.EVENT_FADE_TICKS) {
			// It has just landed a hit of its own, made on the way down: that was the punish crit, and
			// this opponent can be punished. (A hit made from the ground on the tick it was hit says
			// nothing, and the fall that follows it is still watched for where the opponent is.)
			proven = true;
			lastNear = true;
			watching = false;
			return;
		}
		if (!ground) {
			if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] < 0f) {
				fell = true;
				boolean near = JumpIn.distance(vec) <= NEAR;
				sawNear |= near;
				sawReady |= near && vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] >= SwingGate.MIN_CHARGE;
			}
		} else if (fell || sinceTaken <= 1f - 6f / BodyState.EVENT_FADE_TICKS) {
			// Down again (or it was never thrown up at all).
			lastNear = fell && sawNear;
			if (fell && sawReady) {
				// The sword was back, the opponent was there, and nothing came of it.
				proven = false;
			}
			watching = false;
		}
	}

	/** Whether the opponent was in reach on the way down from its last hit. */
	public boolean punishable() {
		return lastNear || proven;
	}

	/** Whether the last fall on which it had its sword back ended in a hit of its own, made in the air. */
	public boolean proven() {
		return proven;
	}
}
