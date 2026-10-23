package dev.swordai.server;

import net.minecraft.server.level.ServerPlayer;

/**
 * How far a fake player has fallen, counted here because the game does not count it.
 *
 * <p>The game makes a hit critical only if the attacker's fall distance is above zero. For a real
 * player that distance is counted from what its game client reports. A fake player has no client;
 * Carpet hands its moves to the game's own counting instead, and in 1.21.11 that hand-over comes
 * to nothing: measured on a fake player half a block into its fall, the fall distance was 0.0 on
 * every tick. So nothing a fake player did was ever a critical hit:
 *
 * <ul>
 * <li>the network's own body where it has no screen ({@code /swordai train headless} and
 * {@code /swordai spar}): a run spent a million steps of training without the screen in the first
 * crit drill and never passed it, a drill that training on the screen passed in 17,000;</li>
 * <li>and the scripted opponent that jumps in for critical hits, which waits for its fall distance
 * before it swings: it jumped, never swung in the air, and gave up for a plain hit, so the network
 * has never been hit by a critical hit in training at all.</li>
 * </ul>
 *
 * <p>Counted the way the game counts it: every block moved downwards while off the ground adds,
 * and standing on the ground puts it back to nothing. Call once per server tick, after the players
 * have moved and before anybody swings.
 */
final class FakeFall {
	private double lastY = Double.NaN;
	private double fallen;

	/** The fake player was put somewhere else: how far it had fallen before is forgotten. */
	void reset() {
		lastY = Double.NaN;
		fallen = 0.0;
	}

	void tick(ServerPlayer fake) {
		double y = fake.getY();
		if (fake.onGround() || fake.isInWater() || fake.onClimbable() || Double.isNaN(lastY)) {
			fallen = 0.0;
		} else if (y < lastY) {
			fallen += lastY - y;
		}
		lastY = y;
		fake.fallDistance = fallen;
	}
}
