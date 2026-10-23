package dev.swordai.client;

import dev.swordai.core.rl.ActionSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/**
 * Turns the network's choice into the same inputs a person produces: the camera is turned through
 * the mouse-look path and keys are held through their key bindings. A swing is vanilla's own
 * attack, made from the client tick (see {@code MinecraftMixin}), so the crosshair pick, the reach
 * check and the cooldown decide what happens.
 */
final class AgentController {
	/** {@code Entity.turn} multiplies its arguments by this; dividing first makes the bins exact degrees. */
	private static final double TURN_SCALE = 0.15;

	private AgentController() {
	}

	/**
	 * Start of the tick: turn first, so this tick's crosshair pick already uses the new view.
	 *
	 * @param yaw   degrees to turn this tick (what the hand model made of the chosen speed)
	 * @param pitch degrees to look down this tick
	 */
	static void apply(Minecraft mc, int[] action, float yaw, float pitch) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (yaw != 0f || pitch != 0f) {
			player.turn(yaw / TURN_SCALE, pitch / TURN_SCALE);
		}
		hold(mc, action);
	}

	/** Presses exactly the keys this action holds. Safe to repeat within a tick. */
	static void hold(Minecraft mc, int[] action) {
		Options o = mc.options;
		o.keyUp.setDown(action[ActionSpace.FORWARD] == 1);
		o.keyDown.setDown(action[ActionSpace.FORWARD] == 2);
		o.keyLeft.setDown(action[ActionSpace.STRAFE] == 1);
		o.keyRight.setDown(action[ActionSpace.STRAFE] == 2);
		o.keySprint.setDown(action[ActionSpace.SPRINT] == 1);
		o.keyJump.setDown(action[ActionSpace.JUMP] == 1);
	}

	/** Lets go of everything the agent might be holding. */
	static void release(Minecraft mc) {
		if (SwordAiClient.personPlays()) {
			// The user plays and is recorded: the keys are theirs.
			return;
		}
		Options o = mc.options;
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keySprint.setDown(false);
		o.keyJump.setDown(false);
		o.keyAttack.setDown(false);
	}
}
