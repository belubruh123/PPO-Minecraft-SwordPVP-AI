package dev.swordai.core.env;

/**
 * Sprinting as a real game client keeps it, and what the server gets to hear of it. A body that has
 * no client of its own (a fake player) is run through this so that its sprint hits come out exactly
 * as they would if the same keys were pressed on a real client.
 *
 * <p>The rules are the game's (1.21.11, {@code LocalPlayer.aiStep} and {@code Player.attack}):
 * <ul>
 * <li>Sprinting starts with W held and the sprint key, or with W tapped twice within seven ticks.
 * It lasts until W is let go or the player runs into something.</li>
 * <li>A hit made while sprinting with the weapon charged knocks back, and stops the sprint: on the
 * server, and on the client. The client starts sprinting again in the same tick if the sprint key
 * is still held.</li>
 * <li>The server is only told when the client's sprint has changed by the end of a tick. A sprint
 * that was stopped by a hit and restarted in the same tick has not changed, so the server goes on
 * believing the sprint is over and the next hits do not knock back, until the sprint really stops
 * for a tick (W or the sprint key let go) and starts again.</li>
 * <li>A click is sent before the tick's movement, so a hit is judged with the sprint the server knew
 * of before this tick's keys.</li>
 * </ul>
 */
public final class ClientSprint {
	/** Two taps of W within this many ticks start a sprint (the game's default setting). */
	public static final int DOUBLE_TAP_TICKS = 7;

	private boolean sprinting;
	/** What the server was last told. */
	private boolean told;
	/** What the server believes; differs from {@link #told} after it ended the sprint for a hit. */
	private boolean server;
	private boolean forwardBefore;
	private int tapWindow;

	public void reset() {
		sprinting = false;
		told = false;
		server = false;
		forwardBefore = false;
		tapWindow = 0;
	}

	/** Whether the client is sprinting: this is what the body moves with. */
	public boolean client() {
		return sprinting;
	}

	/** Whether the server believes it is sprinting: this is what a hit is judged with. */
	public boolean server() {
		return server;
	}

	/**
	 * The client's side of a hit on the opponent, made before this tick's {@link #move}.
	 *
	 * @param charged whether the client saw its weapon charged enough to knock back
	 * @return true if the client takes this for a sprint hit, and so slows down and stops sprinting
	 */
	public boolean clientHit(boolean charged) {
		if (sprinting && charged) {
			sprinting = false;
			return true;
		}
		return false;
	}

	/** The server's side of the same hit: whether it still has the attacker sprinting afterwards. */
	public void serverHit(boolean stillSprinting) {
		server = stillSprinting;
	}

	/**
	 * The player's own tick, after any click: the keys held this tick decide the sprint, and the
	 * server is told if that changed it.
	 *
	 * @param collided whether the body ran into a wall on its last move
	 */
	public void move(boolean forward, boolean backward, boolean sprintKey, boolean collided) {
		if (tapWindow > 0) {
			tapWindow--;
		}
		if (backward) {
			tapWindow = 0;
		}
		if (!sprinting && forward) {
			if (!forwardBefore) {
				if (tapWindow > 0) {
					sprinting = true;
				} else {
					tapWindow = DOUBLE_TAP_TICKS;
				}
			}
			if (sprintKey) {
				sprinting = true;
			}
		}
		if (sprinting && (!forward || collided)) {
			sprinting = false;
		}
		forwardBefore = forward;
		if (sprinting != told) {
			told = sprinting;
			server = sprinting;
		}
	}
}
