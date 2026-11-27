package dev.swordai.server;

import dev.swordai.core.rl.ObsSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Shared human-observable inventory slot packer (for AlphaStar {@code SlotEncoder}) and
 * ANYmal-style 5x5 egocentric yaw-aligned terrain heightmap + hazard scanner (Rudin et al., CoRL 2022).
 * Uses only information visible on the player's HUD/screen; runs identically on real clients and ReplicaBody.
 */
public final class InventoryObs {
	private static final ItemStack SHIELD_PROBE = new ItemStack(Items.SHIELD);
	private static final double LOG_65 = Math.log(65.0);

	private InventoryObs() {}

	public static boolean isSword(ItemStack s) { return !s.isEmpty() && s.is(ItemTags.SWORDS); }
	public static boolean isAxe(ItemStack s) { return !s.isEmpty() && s.is(ItemTags.AXES); }
	public static boolean isShield(ItemStack s) { return !s.isEmpty() && s.is(Items.SHIELD); }
	public static boolean isGap(ItemStack s) { return !s.isEmpty() && (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)); }

	/** Returns 0..8 hotbar slot index holding the first item matching category {@code sfCat}, or -1. */
	public static int findHotbarSlot(Player p, int sfCat) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < ObsSpec.HOTBAR_SLOTS; i++) {
			if (categoryOf(inv.getItem(i)) == sfCat) return i;
		}
		return -1;
	}

	public static boolean hasShield(Player p) {
		if (isShield(p.getOffhandItem())) return true;
		return findHotbarSlot(p, ObsSpec.SF_SHIELD) >= 0;
	}

	public static float shieldCooldown(Player p) {
		return p.getCooldowns().getCooldownPercent(SHIELD_PROBE, 0f);
	}

	public static boolean hasShieldReady(Player p) {
		return hasShield(p) && !p.getCooldowns().isOnCooldown(SHIELD_PROBE);
	}

	/** Packs all 37 slots (9 hotbar + 1 offhand + 27 backpack) into {@code out[0..ObsSpec.SLOTS_SIZE)}. */
	public static void fillSlots(Player p, float[] out) {
		Arrays.fill(out, 0f);
		Inventory inv = p.getInventory();
		int sel = inv.getSelectedSlot();
		ItemCooldowns cds = p.getCooldowns();
		for (int i = 0; i < ObsSpec.HOTBAR_SLOTS; i++) {
			writeSlot(inv.getItem(i), cds, i == sel ? 1f : 0f, out, i * ObsSpec.SLOT_FEATS);
		}
		writeSlot(p.getOffhandItem(), cds, 0.5f, out, ObsSpec.OFFHAND_SLOT * ObsSpec.SLOT_FEATS);
		for (int b = 0; b < ObsSpec.BACKPACK_SLOTS; b++) {
			writeSlot(inv.getItem(9 + b), cds, 0f, out, (ObsSpec.OFFHAND_SLOT + 1 + b) * ObsSpec.SLOT_FEATS);
		}
	}

	private static void writeSlot(ItemStack s, ItemCooldowns cds, float selected, float[] out, int off) {
		if (s == null || s.isEmpty()) {
			out[off + ObsSpec.SF_SELECTED] = selected;
			return;
		}
		out[off + ObsSpec.SF_PRESENT] = 1f;
		int cat = categoryOf(s);
		if (cat >= ObsSpec.SF_SWORD && cat <= ObsSpec.SF_OTHER) out[off + cat] = 1f;
		out[off + ObsSpec.SF_TIER] = tierOf(s);
		out[off + ObsSpec.SF_COUNT] = (float) (Math.log1p(s.getCount()) / LOG_65);
		out[off + ObsSpec.SF_DURABILITY] = s.isDamageableItem()
				? Math.max(0f, 1f - (float) s.getDamageValue() / Math.max(1, s.getMaxDamage())) : 1f;
		out[off + ObsSpec.SF_COOLDOWN] = cds.getCooldownPercent(s, 0f);
		out[off + ObsSpec.SF_SELECTED] = selected;
	}

	public static int categoryOf(ItemStack s) {
		if (s == null || s.isEmpty()) return 0;
		if (s.is(ItemTags.SWORDS)) return ObsSpec.SF_SWORD;
		if (s.is(ItemTags.AXES)) return ObsSpec.SF_AXE;
		if (s.is(Items.SHIELD)) return ObsSpec.SF_SHIELD;
		if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) return ObsSpec.SF_GAP;
		if (s.getItem() instanceof PotionItem) return ObsSpec.SF_POTION;
		if (s.is(Items.ENDER_PEARL)) return ObsSpec.SF_PEARL;
		if (s.is(Items.COBWEB)) return ObsSpec.SF_WEB;
		if (s.is(Items.TOTEM_OF_UNDYING)) return ObsSpec.SF_TOTEM;
		if (s.is(Items.EXPERIENCE_BOTTLE) || s.is(ItemTags.HEAD_ARMOR) || s.is(ItemTags.CHEST_ARMOR)
				|| s.is(ItemTags.LEG_ARMOR) || s.is(ItemTags.FOOT_ARMOR)) return ObsSpec.SF_ARMOR_XP;
		return ObsSpec.SF_OTHER;
	}

	private static float tierOf(ItemStack s) {
		Item item = s.getItem();
		if (item == Items.NETHERITE_SWORD || item == Items.NETHERITE_AXE || item == Items.ENCHANTED_GOLDEN_APPLE) return 1.0f;
		if (item == Items.DIAMOND_SWORD || item == Items.DIAMOND_AXE) return 0.85f;
		if (item == Items.IRON_SWORD || item == Items.IRON_AXE || item == Items.GOLDEN_APPLE) return 0.65f;
		if (item == Items.STONE_SWORD || item == Items.STONE_AXE) return 0.50f;
		if (item == Items.WOODEN_SWORD || item == Items.WOODEN_AXE || item == Items.GOLDEN_SWORD || item == Items.GOLDEN_AXE) return 0.35f;
		return 0.5f;
	}

	/**
	 * Scans an egocentric yaw-aligned 5x5 grid around {@code p}'s feet (forward -2..+2, right -2..+2 blocks).
	 * Channel 0: relative step height clamp((groundY - myY) / 2, -1..+1). Channel 1: cobweb/water/lava hazard.
	 *
	 * @return the exact solid ground Y directly beneath {@code p} (for platform-invariant {@code BodyState.height}).
	 */
	public static double fillTerrain(Player p, float[] out) {
		Arrays.fill(out, 0f);
		Level level = p.level();
		if (level == null) return p.getY();
		double px = p.getX(), py = p.getY(), pz = p.getZ();
		double rad = Math.toRadians(p.getYRot());
		double sy = Math.sin(rad), cy = Math.cos(rad);
		// Matches ViewGeometry: forward = (-sy, cy), right = (-cy, -sy)
		double fx = -sy, fz = cy, rx = -cy, rz = -sy;
		int baseY = (int) Math.floor(py + 0.05);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double centerGround = py;

		int idx = 0;
		for (int df = -2; df <= 2; df++) {
			for (int dr = -2; dr <= 2; dr++) {
				int bx = (int) Math.floor(px + df * fx + dr * rx);
				int bz = (int) Math.floor(pz + df * fz + dr * rz);
				boolean headWall = isSolid(level, pos.set(bx, baseY + 1, bz)) || isSolid(level, pos.set(bx, baseY + 2, bz));
				float dh;
				if (headWall) {
					dh = 1.0f;
				} else {
					int gy = baseY - 3;
					for (int y = baseY; y >= baseY - 2; y--) {
						if (isSolid(level, pos.set(bx, y, bz))) { gy = y + 1; break; }
					}
					if (df == 0 && dr == 0 && gy >= baseY - 2) centerGround = gy;
					dh = Math.max(-1f, Math.min(1f, (float) ((gy - py) * 0.5)));
				}
				out[idx] = dh;
				out[ObsSpec.TERRAIN_CELLS + idx] = hazardAt(level, pos, bx, baseY, bz);
				idx++;
			}
		}
		return centerGround;
	}

	private static boolean isSolid(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		return !st.isAir() && !st.getCollisionShape(level, pos).isEmpty();
	}

	private static float hazardAt(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		for (int dy = 0; dy <= 1; dy++) {
			BlockState st = level.getBlockState(pos.set(x, y + dy, z));
			if (st.is(Blocks.COBWEB) || st.is(Blocks.POWDER_SNOW)) return 1.0f;
			if (st.is(Blocks.LAVA) || st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) return -1.0f;
			if (st.is(Blocks.WATER)) return 0.5f;
		}
		return 0f;
	}

	/** Compact human-readable mainhand tag for RoundRecorder logs. */
	public static String itemTag(ItemStack s) {
		return switch (categoryOf(s)) {
			case ObsSpec.SF_SWORD -> "sw";
			case ObsSpec.SF_AXE -> "ax";
			case ObsSpec.SF_SHIELD -> "sh";
			case ObsSpec.SF_GAP -> "gp";
			case ObsSpec.SF_POTION -> "pt";
			case ObsSpec.SF_PEARL -> "pl";
			case ObsSpec.SF_WEB -> "wb";
			case ObsSpec.SF_TOTEM -> "tt";
			default -> "--";
		};
	}
}
