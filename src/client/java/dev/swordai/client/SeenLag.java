package dev.swordai.client;

import dev.swordai.core.env.LagLink;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Lag for the opponent on the screen. In a singleplayer world the game is told where the opponent
 * is the moment it moves; on a server that news comes late, and from a lagging player in fits and
 * starts. While a training fight is on, the news about the one opponent is held back here for as
 * long as the fight's lag says and then handed to the game unchanged, so everything the game makes
 * of it (the glide towards the new place, the picture, what a click hits) is what it would be on a
 * server. Nothing else is touched: not the agent's own body, not any other entity.
 *
 * <p>Client thread only, apart from {@link #hold}, which is asked about every entity that moves on
 * either side and turns the server's away first.
 */
public final class SeenLag {
	/** News of a move, exactly as the game was about to take it in. */
	private record Move(Entity entity, Optional<Vec3> pos, Optional<Float> yRot, Optional<Float> xRot) {
	}

	private static final LagLink<Move> LINK = new LagLink<>();
	private static final Random RNG = new Random();
	/** Null: nobody's moves are held back. */
	private static volatile UUID opponent;
	private static volatile boolean fighting;
	private static boolean delivering;
	private static long heldBack;

	private SeenLag() {
	}

	/** Whose moves are held back from now on; null for nobody. What was still on its way arrives at once. */
	static void follow(UUID player) {
		if (!Objects.equals(opponent, player)) {
			LINK.flush(SeenLag::deliver);
			opponent = player;
		}
	}

	/** The lag of the fight about to begin; see {@link LagLink#set}. */
	static void set(int ticks, float stallChance, int stallTicks) {
		LINK.flush(SeenLag::deliver);
		LINK.set(ticks, stallChance, stallTicks);
	}

	/**
	 * Once per client tick.
	 *
	 * @param fightOn false between fights: then the opponent is shown without any delay of this class's making
	 */
	static void tick(boolean fightOn) {
		if (!fightOn) {
			if (fighting) {
				fighting = false;
				LINK.flush(SeenLag::deliver);
			}
			return;
		}
		fighting = true;
		LINK.tick(RNG, SeenLag::deliver);
	}

	/** Nobody's moves are held back any more. */
	static void off() {
		fighting = false;
		LINK.flush(SeenLag::deliver);
		LINK.set(0, 0f, 0);
		opponent = null;
	}

	/** For the report: how many of the opponent's moves have been held back since the game started. */
	static long heldBack() {
		return heldBack;
	}

	/**
	 * Asked just before the game takes in news of where an entity has moved to. True: the news has
	 * been kept and will be handed in later, and the game is to do nothing with it now.
	 */
	public static boolean hold(Entity entity, Optional<Vec3> pos, Optional<Float> yRot, Optional<Float> xRot) {
		UUID who = opponent;
		if (who == null || !fighting || !entity.level().isClientSide()) {
			return false;
		}
		if (delivering || !who.equals(entity.getUUID()) || LINK.straight() || !LINK.holding()) {
			return false;
		}
		LINK.send(new Move(entity, pos, yRot, xRot));
		heldBack++;
		return true;
	}

	private static void deliver(Move move) {
		Minecraft mc = Minecraft.getInstance();
		if (move.entity.isRemoved() || move.entity.level() != mc.level) {
			return;
		}
		delivering = true;
		try {
			move.entity.moveOrInterpolateTo(move.pos, move.yRot, move.xRot);
		} finally {
			delivering = false;
		}
	}
}
