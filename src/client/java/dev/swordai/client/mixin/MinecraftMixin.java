package dev.swordai.client.mixin;

import dev.swordai.client.SwordAiClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla reads the keys only while no screen is open, and opening one lets go of all of them.
 * This runs right after that point of the tick and before the player moves, so the agent keeps
 * its keys and its swing while the chat is open. The swing itself is vanilla's own attack: same
 * crosshair pick, same reach, same cooldown.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	public int missTime;

	@Shadow
	private boolean startAttack() {
		throw new AssertionError();
	}

	@Shadow
	private void continueAttack(boolean held) {
		throw new AssertionError();
	}

	/**
	 * The game lets a server tick faster than twenty times a second but never its own client: a
	 * player's movement is the client's, and vanilla keeps that at normal speed. Training faster
	 * than real time needs both to speed up together, so while it does the client's tick is as short
	 * as the server's.
	 */
	@Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
	private void swordai$fasterTicks(float normal, CallbackInfoReturnable<Float> cir) {
		float millis = SwordAiClient.tickMillis();
		if (millis > 0f) {
			cir.setReturnValue(millis);
		}
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"))
	private void swordai$agentInput(CallbackInfo ci) {
		if (!SwordAiClient.beforePlayerMoves()) {
			return;
		}
		// This lock keeps a mouse click that closed a menu from also swinging; it is not meant for the agent.
		this.missTime = 0;
		this.startAttack();
		// What vanilla does after a click whose button is not held: no miss delay, no block breaking.
		this.continueAttack(false);
	}
}
