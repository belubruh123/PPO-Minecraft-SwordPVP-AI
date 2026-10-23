package dev.swordai.client;

import java.util.List;

/**
 * Something SwordAI is doing in the world from a command until it is stopped: training, a fight
 * with the network playing the user's character, or sparring against it. One runs at a time, and
 * everything here is called on the client thread.
 */
interface AgentSession {
	boolean stopped();

	/** True while the network, not the player, is playing the player's character. */
	boolean inControl();

	/** Whether the network's pictures are being taken: while it plays, and while the user plays and is recorded. */
	default boolean wantsPictures() {
		return inControl();
	}

	/** The user plays and is recorded ({@code /swordai demo}): their clicks of the attack key are held for the session. */
	default boolean takesClicks() {
		return false;
	}

	/** A click of the attack key held for the session; see {@link #takesClicks}. */
	default void heldClick() {
	}

	/** The user, not the network, plays the fights of this session and is recorded. */
	default boolean personPlays() {
		return false;
	}

	/** Start of every client tick. */
	void tick();

	/** End of every client tick. */
	void endTick();

	/** Once per rendered frame, when the world is drawn and the hand and HUD are not yet. */
	void worldDrawn();

	/** Middle of the client tick, after vanilla's key handling. Returns whether the network swings now. */
	boolean beforePlayerMoves();

	/** Degrees to add to the drawn view so that this tick's turn glides in instead of snapping. */
	float viewYawLag(float partialTick);

	float viewPitchLag(float partialTick);

	/** Stops cleanly and gives everything back. Safe to call more than once. */
	void stop(String reason);

	/** Lines for {@code /swordai status}. */
	List<String> statusLines();

	/** Asks for the next frame the network uses to be saved. Returns false when this session has no frames. */
	boolean requestDebugDump();

	/** One line for the report: what is running and in which state. */
	String describe();
}
