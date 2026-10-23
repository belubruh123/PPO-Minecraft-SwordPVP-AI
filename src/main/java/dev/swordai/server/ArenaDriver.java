package dev.swordai.server;

import net.minecraft.server.level.ServerPlayer;

/**
 * Something that plays the agent's body from the server thread instead of from a game client:
 * training without the screen, where the agent is a fake player too.
 */
public interface ArenaDriver {
	/**
	 * End of every server tick while the arena stands, after the opponent has chosen its move.
	 *
	 * @param agent the agent's body, or null while it has not been brought in yet
	 * @param bot   the opponent's body, or null likewise
	 */
	void arenaTick(ServerPlayer agent, ServerPlayer bot, double floorY);

	/** The arena is being taken down (stopped, the world is closing, or it failed): save and let go. */
	void arenaClosed(String error);
}
