package dev.swordai.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** What a fight in someone's world needs to know about the players around, whether or not it is learned from. */
final class Opponents {
	private static final int HOTBAR_SLOTS = 9;

	private Opponents() {
	}

	/**
	 * The player to fight, or null when they are not there.
	 *
	 * @param wantedName the player asked for, or null for whoever is nearest
	 * @param fighting   the player already being fought, or null: without a name it stays with them,
	 *                   so that a bystander never becomes the target
	 */
	static Player find(Minecraft mc, LocalPlayer me, String wantedName, UUID fighting) {
		Player nearest = null;
		double nearestDist = Double.MAX_VALUE;
		for (AbstractClientPlayer p : mc.level.players()) {
			if (p == me || p.isRemoved() || p.isSpectator()) {
				continue;
			}
			if (wantedName != null) {
				if (p.getScoreboardName().equalsIgnoreCase(wantedName)) {
					return p;
				}
				continue;
			}
			if (fighting != null) {
				if (p.getUUID().equals(fighting)) {
					return p;
				}
				continue;
			}
			double d = p.distanceToSqr(me);
			if (d < nearestDist) {
				nearestDist = d;
				nearest = p;
			}
		}
		return nearest;
	}

	/**
	 * How far each fighter is from the server, and the weather: what a fight on a server has that the
	 * arena does not. For the summary; empty in a singleplayer world.
	 */
	static String connectionLine(Minecraft mc, UUID opponent) {
		if (mc.getConnection() == null || mc.player == null || mc.hasSingleplayerServer()) {
			return "";
		}
		PlayerInfo mine = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		PlayerInfo theirs = opponent == null ? null : mc.getConnection().getPlayerInfo(opponent);
		String weather = mc.level != null && mc.level.isRaining() ? ", raining" : "";
		return String.format(java.util.Locale.ROOT, "; ping: you %s, opponent %s%s", ms(mine), ms(theirs), weather);
	}

	private static String ms(PlayerInfo info) {
		return info == null ? "?" : info.getLatency() + " ms";
	}

	/** First person, and the sword in hand if there is one on the hotbar. */
	static void keepView(Minecraft mc, LocalPlayer me) {
		if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		Inventory inv = me.getInventory();
		for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
			if (inv.getItem(slot).is(ItemTags.SWORDS)) {
				if (inv.getSelectedSlot() != slot) {
					inv.setSelectedSlot(slot);
				}
				return;
			}
		}
	}
}
