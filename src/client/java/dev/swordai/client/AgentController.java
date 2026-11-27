package dev.swordai.client;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

/**
 * Turns the network's choice into exact human Minecraft inputs: mouse-look turns, movement keys,
 * hotbar key presses (1..9), offhand swap (F), right-click item use (Shield/Gap), and vanilla attack.
 */
final class AgentController {
	private static final double TURN_SCALE = 0.15;

	private AgentController() {
	}

	static void apply(Minecraft mc, int[] action, float yaw, float pitch) {
		LocalPlayer player = mc.player;
		if (player == null) return;
		if (yaw != 0f || pitch != 0f) {
			player.turn(yaw / TURN_SCALE, pitch / TURN_SCALE);
		}
		int wantSlot = action[ActionSpace.HOTBAR_SLOT];
		if (wantSlot >= 1 && wantSlot <= ObsSpec.HOTBAR_SLOTS) {
			int idx = wantSlot - 1;
			if (idx != player.getInventory().getSelectedSlot()) {
				player.getInventory().setSelectedSlot(idx);
			}
		}
		if (action[ActionSpace.OFFHAND_SWAP] == 1 && mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundPlayerActionPacket(
					ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
		}
		hold(mc, action);
	}

	static void hold(Minecraft mc, int[] action) {
		Options o = mc.options;
		o.keyUp.setDown(action[ActionSpace.FORWARD] == 1);
		o.keyDown.setDown(action[ActionSpace.FORWARD] == 2);
		o.keyLeft.setDown(action[ActionSpace.STRAFE] == 1);
		o.keyRight.setDown(action[ActionSpace.STRAFE] == 2);
		o.keySprint.setDown(action[ActionSpace.SPRINT] == 1);
		o.keyJump.setDown(action[ActionSpace.JUMP] == 1);
		o.keyUse.setDown(action[ActionSpace.USE_ITEM] == 1 && action[ActionSpace.ATTACK] == 0);
	}

	static void release(Minecraft mc) {
		if (SwordAiClient.personPlays()) return;
		Options o = mc.options;
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keySprint.setDown(false);
		o.keyJump.setDown(false);
		o.keyAttack.setDown(false);
		o.keyUse.setDown(false);
		if (mc.player != null && mc.player.isUsingItem() && mc.gameMode != null) {
			mc.gameMode.releaseUsingItem(mc.player);
		}
	}
}
