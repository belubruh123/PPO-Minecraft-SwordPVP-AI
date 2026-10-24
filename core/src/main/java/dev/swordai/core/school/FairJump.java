package dev.swordai.core.school;

import dev.swordai.core.rl.ActionSpace;

import java.util.Random;

/**
 * Keeps its jump resets human (the user, 24 Oct: "just don't make it 100% consistent", so that a strong player can
 * still beat it "in a normal way"). In a fight that is not training, a jump pressed on the ground while it is being
 * comboed (a hit taken lately, with one or more in a row) is pressed a tick late {@link #MISS} of the time: a jump reset
 * has to come on the tick of the hit, so a tick late it is mistimed, and it is thrown as far as without one. Training
 * keeps the whole skill; only its use in a fight misses now and then.
 *
 * <p>One per fighter; not thread-safe.
 */
public final class FairJump {
	/** The share of its combo jumps pressed a tick late. */
	public static final float MISS = 0.35f;
	/** Ticks since a hit taken in which it counts as being comboed. */
	public static final int COMBO_WINDOW = 20;

	private final Random rng;
	private boolean late;
	public int delayed;
	public int seen;

	public FairJump(long seed) {
		rng = new Random(seed);
	}

	public void reset() {
		late = false;
	}

	/**
	 * Applies to {@code action} (its keys this tick).
	 *
	 * @param sinceHitTaken ticks since it last took a hit
	 * @param hitsInRow     hits it has taken in a row without landing one
	 */
	public void apply(int[] action, boolean onGround, int sinceHitTaken, int hitsInRow) {
		if (late) {
			late = false;
			if (onGround) {
				action[ActionSpace.JUMP] = 1;
			}
			return;
		}
		if (action[ActionSpace.JUMP] != 1 || !onGround || sinceHitTaken > COMBO_WINDOW || hitsInRow < 1) {
			return;
		}
		seen++;
		if (rng.nextFloat() < MISS) {
			action[ActionSpace.JUMP] = 0;
			late = true;
			delayed++;
		}
	}
}
