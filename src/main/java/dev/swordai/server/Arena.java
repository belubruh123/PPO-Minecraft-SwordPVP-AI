package dev.swordai.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * A walled stone platform high above the world. Plain grey surfaces and nothing else in sight, so
 * the only strongly coloured pixels the agent ever sees are the colour-keyed opponent.
 *
 * <p>Once built it is left standing between runs and used again by the next one that starts near
 * it; it comes down when another is needed elsewhere or the world closes (see {@code ArenaServer}).
 */
final class Arena {
	private static final int WALL_HEIGHT = 5;
	/** Invisible barrier above the wall so nobody can be knocked or pillar-jumped out. */
	private static final int BARRIER_TOP = 12;

	final ServerLevel level;
	final double centerX;
	final double centerZ;
	/** Y of the surface players stand on. */
	final int floorY;
	final int radius;

	private final int blockX;
	private final int blockZ;

	Arena(ServerLevel level, int blockX, int floorY, int blockZ, int radius) {
		this.level = level;
		this.blockX = blockX;
		this.blockZ = blockZ;
		this.floorY = floorY;
		this.radius = radius;
		this.centerX = blockX + 0.5;
		this.centerZ = blockZ + 0.5;
	}

	/** How far outside the walls someone may stand and still have this arena used again instead of a new one built above them. */
	private static final double NEAR_MARGIN = 32.0;

	/**
	 * Puts the structure up, or puts right whatever of it is not as it should be. On an arena that is
	 * already standing this changes nothing, and anything left lying about inside it is cleared away.
	 */
	void build() {
		fill(true);
		AABB inside = new AABB(blockX - radius - 1, floorY - 1, blockZ - radius - 1, blockX + radius + 2, floorY + BARRIER_TOP + 1, blockZ + radius + 2);
		for (Entity stray : level.getEntities((Entity) null, inside, e -> !(e instanceof Player))) {
			stray.discard();
		}
	}

	/** Takes the whole structure down again, pillars included (they sit inside the cleared volume). */
	void remove() {
		fill(false);
	}

	/** Whether someone standing at these coordinates, at any height, is at or close by this arena. */
	boolean near(double x, double z) {
		return Math.abs(x - centerX) <= radius + NEAR_MARGIN && Math.abs(z - centerZ) <= radius + NEAR_MARGIN;
	}

	private void fill(boolean build) {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState floor = build ? Blocks.SMOOTH_STONE.defaultBlockState() : air;
		BlockState wall = build ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : air;
		BlockState barrier = build ? Blocks.BARRIER.defaultBlockState() : air;
		int r = radius;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -r - 1; dx <= r + 1; dx++) {
			for (int dz = -r - 1; dz <= r + 1; dz++) {
				boolean edge = Math.abs(dx) == r + 1 || Math.abs(dz) == r + 1;
				set(pos.set(blockX + dx, floorY - 1, blockZ + dz), floor);
				for (int dy = 0; dy <= BARRIER_TOP; dy++) {
					BlockState state = !edge ? air : dy < WALL_HEIGHT ? wall : barrier;
					set(pos.set(blockX + dx, floorY + dy, blockZ + dz), state);
				}
			}
		}
	}

	/**
	 * Raises a stone column on the floor.
	 *
	 * @param placed receives the blocks that were set, so the caller can take the column down again
	 * @return the Y a player standing on top has
	 */
	int buildPillar(double x, double z, int height, List<BlockPos> placed) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		for (int dy = 0; dy < height; dy++) {
			BlockPos pos = new BlockPos(bx, floorY + dy, bz);
			set(pos, Blocks.STONE.defaultBlockState());
			placed.add(pos);
		}
		return floorY + height;
	}

	void clearPillar(List<BlockPos> placed) {
		for (BlockPos pos : placed) {
			set(pos, Blocks.AIR.defaultBlockState());
		}
		placed.clear();
	}

	/** Keeps a coordinate far enough from the walls to stand and fight. */
	double clampX(double x) {
		return Math.max(centerX - radius + 1.0, Math.min(centerX + radius - 1.0, x));
	}

	double clampZ(double z) {
		return Math.max(centerZ - radius + 1.0, Math.min(centerZ + radius - 1.0, z));
	}

	boolean inside(double x, double z, double margin) {
		return Math.abs(x - centerX) <= radius - margin && Math.abs(z - centerZ) <= radius - margin;
	}

	private void set(BlockPos pos, BlockState state) {
		if (!level.getBlockState(pos).equals(state)) {
			// Clients are told, neighbours are not: nothing here needs block updates.
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}
}
