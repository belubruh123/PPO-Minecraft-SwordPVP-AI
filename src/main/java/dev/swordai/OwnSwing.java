package dev.swordai;

/**
 * The last attack the player's own character made, as their game client made it out: how charged
 * the weapon was, and whether the game will call the hit a sprint hit, a critical hit or a sweep.
 * A client works these out for itself, by the game's own rules, before the server answers.
 *
 * <p>Only needed where there is no server to ask: when the network plays the user's character on
 * someone else's server and learns from it. Written and read on the client thread.
 */
public final class OwnSwing {
	private static int count;
	private static float charge;
	private static boolean sprinting;
	private static boolean crit;
	private static boolean rising;
	private static boolean sweep;

	private OwnSwing() {
	}

	public static void note(float charge, boolean sprinting, boolean crit, boolean rising, boolean sweep) {
		count++;
		OwnSwing.charge = charge;
		OwnSwing.sprinting = sprinting;
		OwnSwing.crit = crit;
		OwnSwing.rising = rising;
		OwnSwing.sweep = sweep;
	}

	/** Goes up by one with every attack; compare with an earlier value to tell whether one was made since. */
	public static int count() {
		return count;
	}

	public static float charge() {
		return charge;
	}

	public static boolean sprinting() {
		return sprinting;
	}

	public static boolean crit() {
		return crit;
	}

	/** In the air and still going up when the attack was made. */
	public static boolean rising() {
		return rising;
	}

	public static boolean sweep() {
		return sweep;
	}
}
