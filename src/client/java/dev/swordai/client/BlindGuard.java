package dev.swordai.client;

import dev.swordai.core.env.StepFacts;

/**
 * Watches for a picture that has no opponent in it although the opponent is right in front of the
 * camera. That is not the agent looking away: it is the picture being wrong, and an agent that is
 * fed it fights blind, and learns from being blind if it is learning.
 *
 * <p>It goes by where the opponent is, which the picture is never allowed to. A wall between the
 * two looks the same to it, so it is only acted on where there is something to switch off that can
 * cause it: drawing the frame twice to show the player the opponent's own skin.
 */
final class BlindGuard {
	/** Within this of the crosshair the opponent is well inside any picture the agent gets. */
	private static final float NEAR_CROSSHAIR_YAW = 20f;
	private static final float NEAR_CROSSHAIR_PITCH = 15f;
	/** And no farther off than this it is too large to be missed. */
	private static final float NEAR_BLOCKS = 16f;
	/** This many decisions in a row like that (two seconds) is not a blink. */
	private static final int BLIND_DECISIONS = 40;

	private int blind;

	/**
	 * One decision's worth of where the opponent is and whether the picture had it.
	 *
	 * @return true when the picture has now been missing it for too long
	 */
	boolean note(StepFacts facts) {
		boolean inFront = Math.abs(facts.yawErrDeg) <= NEAR_CROSSHAIR_YAW && Math.abs(facts.pitchErrDeg) <= NEAR_CROSSHAIR_PITCH
				&& facts.reachDist <= NEAR_BLOCKS;
		if (!inFront || facts.enemyVisible) {
			blind = 0;
			return false;
		}
		if (++blind < BLIND_DECISIONS) {
			return false;
		}
		blind = 0;
		return true;
	}

	void reset() {
		blind = 0;
	}
}
