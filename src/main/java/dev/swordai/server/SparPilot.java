package dev.swordai.server;

import net.minecraft.server.level.ServerPlayer;

/**
 * Whatever plays the fake player while a person spars against it: the trained network as it is
 * ({@link BotPilot}), or the network while it also learns from the fight.
 *
 * <p>Handed over from the client thread, then used by the server thread only.
 */
public interface SparPilot {
	/** Start of a round, when the stand-off is over. */
	void reset();

	void hitDealt();

	void hitTaken();

	/** Between rounds: stands still and waits. */
	void idle(ServerPlayer bot);

	/** One tick of a round. */
	void tick(ServerPlayer bot, ServerPlayer opponent, double floorY);

	/** A round has just ended in a knockout; both fighters are still where they were. */
	default void roundOver(ServerPlayer bot, ServerPlayer opponent, double floorY) {
	}

	/** Swings made, swings that reached the opponent, and swings that ended on a block. */
	String swingSummary();

	/**
	 * Whether it learns from the fight. If so, who hit whom is logged for it in {@link CombatLog},
	 * from its side: it is the agent there and the person is the opponent.
	 */
	default boolean learns() {
		return false;
	}

	/** The arena is being taken down (stopped, the world is closing, or it failed): save and let go. */
	default void arenaClosed(String error) {
	}
}
