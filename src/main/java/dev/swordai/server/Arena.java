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
 * A walled neutral-grey stone arena high above the world (zero colourful background pixels so the
 * colour-keyed opponent mask stays pure). Supports toggling clean SMP/UHC 1-block raised/lowered
 * flat stone terraces ({@link #setTerraced}) for terrain-curriculum generalization (Rudin et al., CoRL 2022).
 */
final class Arena {
	private static final int WALL_HEIGHT = 5;
	private static final int BARRIER_TOP = 12;

	final ServerLevel level;
	final double centerX;
	final double centerZ;
	/** Base Y of the flat surface players stand on. */
	final int floorY;
	final int radius;

	private final int blockX;
	private final int blockZ;
	private boolean terraced;

	Arena(ServerLevel level, int blockX, int floorY, int blockZ, int radius) {
		this.level = level;
		this.blockX = blockX;
		this.blockZ = blockZ;
		this.floorY = floorY;
		this.radius = radius;
		this.centerX = blockX + 0.5;
		this.centerZ = blockZ + 0.5;
	}

	private static final double NEAR_MARGIN = 32.0;

	void build() {
		fill(true);
		terraced = false;
		AABB inside = new AABB(blockX - radius - 1, floorY - 2, blockZ - radius - 1, blockX + radius + 2, floorY + BARRIER_TOP + 1, blockZ + radius + 2);
		for (Entity stray : level.getEntities((Entity) null, inside, e -> !(e instanceof Player))) {
			stray.discard();
		}
	}

	void remove() {
		fill(false);
		terraced = false;
	}

	boolean near(double x, double z) {
		return Math.abs(x - centerX) <= radius + NEAR_MARGIN && Math.abs(z - centerZ) <= radius + NEAR_MARGIN;
	}

	/**
	 * Toggles realistic SMP/UHC 1-block ascend (+1) and descend (-1) wide flat platforms using the
	 * exact same neutral grey {@code SMOOTH_STONE}. No-op if already in the requested state.
	 */
	void setTerraced(boolean want) {
		if (want == terraced) return;
		terraced = want;
		BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// Two clean wide +1 flat terraces (NE & SW) + one wide -1 sunken flat terrace (NW) inside the arena
		int half = Math.max(3, Math.min(5, radius / 3));
		for (int dx = 2; dx <= 2 + half; dx++) {
			for (int dz = -half / 2 - 1; dz <= half / 2 + 1; dz++) {
				if (Math.abs(dx) < radius - 1 && Math.abs(dz) < radius - 1) {
					set(pos.set(blockX + dx, floorY, blockZ + dz), want ? stone : air);
				}
			}
		}
		for (int dx = -2 - half; dx <= -2; dx++) {
			for (int dz = 2; dz <= 2 + half; dz++) {
				if (Math.abs(dx) < radius - 1 && Math.abs(dz) < radius - 1) {
					set(pos.set(blockX + dx, floorY, blockZ + dz), want ? stone : air);
				}
			}
		}
		for (int dx = -2 - half; dx <= -2; dx++) {
			for (int dz = -2 - half; dz <= -2; dz++) {
				if (Math.abs(dx) < radius - 1 && Math.abs(dz) < radius - 1) {
					set(pos.set(blockX + dx, floorY - 2, blockZ + dz), stone);
					set(pos.set(blockX + dx, floorY - 1, blockZ + dz), want ? air : stone);
				}
			}
		}
	}

	/** Exact standing Y at world (x, z), accounting for +1/-1 terraces when enabled. */
	double surfaceY(double x, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = floorY + 2; y >= floorY - 2; y--) {
			BlockState st = level.getBlockState(pos.set(bx, y, bz));
			if (!st.isAir() && !st.getCollisionShape(level, pos).isEmpty()) return y + 1.0;
		}
		return floorY;
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
				set(pos.set(blockX + dx, floorY - 2, blockZ + dz), floor);
				set(pos.set(blockX + dx, floorY - 1, blockZ + dz), floor);
				for (int dy = 0; dy <= BARRIER_TOP; dy++) {
					BlockState state = !edge ? air : dy < WALL_HEIGHT ? wall : barrier;
					set(pos.set(blockX + dx, floorY + dy, blockZ + dz), state);
				}
			}
		}
	}

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
		for (BlockPos pos : placed) set(pos, Blocks.AIR.defaultBlockState());
		placed.clear();
	}

	double clampX(double x) { return Math.max(centerX - radius + 1.0, Math.min(centerX + radius - 1.0, x)); }
	double clampZ(double z) { return Math.max(centerZ - radius + 1.0, Math.min(centerZ + radius - 1.0, z)); }
	boolean inside(double x, double z, double margin) {
		return Math.abs(x - centerX) <= radius - margin && Math.abs(z - centerZ) <= radius - margin;
	}

	private void set(BlockPos pos, BlockState state) {
		if (!level.getBlockState(pos).equals(state)) level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}
}
