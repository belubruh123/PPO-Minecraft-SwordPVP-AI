package dev.swordai.core.rl;

/**
 * What a person playing did, as the action the network would have had to choose to do the same: for
 * learning from the user's own fights ({@code /swordai demo}, {@link DemoFile}). The keys map one to
 * one. The camera does not: the network picks a turning speed that the hand model ({@link AimMotor})
 * takes up a part of each tick, so the speed it would have had to pick for the turn the person made
 * is worked back through that model, and the nearest bin to it taken.
 */
public final class HumanAction {
	private HumanAction() {
	}

	/** The bin whose speed is nearest to {@code degrees}. */
	public static int nearest(float[] bins, float degrees) {
		int best = 0;
		float bestGap = Float.MAX_VALUE;
		for (int i = 0; i < bins.length; i++) {
			float gap = Math.abs(bins[i] - degrees);
			if (gap < bestGap) {
				bestGap = gap;
				best = i;
			}
		}
		return best;
	}

	/**
	 * The yaw or pitch bin for a turn of {@code turned} degrees this tick, with the hand carrying
	 * {@code push} degrees a tick before it and closing {@code response} of the gap to the wanted speed
	 * each tick: the wanted speed that, taken up from that push, gives that turn.
	 */
	public static int turnBin(float[] bins, float push, float turned, float response) {
		float r = Math.max(0.05f, Math.min(1f, response));
		return nearest(bins, push + (turned - push) / r);
	}

	/** Writes the keys the person held into {@code action}: W over S, A over D when both are down. */
	public static void keys(int[] action, boolean w, boolean s, boolean a, boolean d, boolean sprint, boolean jump, boolean attack) {
		action[ActionSpace.FORWARD] = w ? 1 : s ? 2 : 0;
		action[ActionSpace.STRAFE] = a ? 1 : d ? 2 : 0;
		action[ActionSpace.SPRINT] = sprint ? 1 : 0;
		action[ActionSpace.JUMP] = jump ? 1 : 0;
		action[ActionSpace.ATTACK] = attack ? 1 : 0;
	}
}
