package dev.swordai.client.mixin;

import dev.swordai.client.SwordAiClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the frame grabber the moment the world is complete in the main render target: the hand
 * pass starts by flushing everything the level left queued, and nothing of the hand, the screen
 * effects, the HUD or an open screen has been drawn yet. The agent therefore sees the same picture
 * whether or not the crosshair, the hotbar or the chat is showing.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * The level is drawn, its depth still in place, and nothing of the hand yet: with the opponent's own
	 * skin shown, the agent's picture is taken here and the real skin drawn over the key.
	 */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", shift = At.Shift.AFTER))
	private void swordai$levelDrawn(DeltaTracker deltaTracker, CallbackInfo ci) {
		SwordAiClient.levelDrawn();
	}

	@Inject(
			method = "renderItemInHand",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
					shift = At.Shift.AFTER))
	private void swordai$worldDrawn(float partialTick, boolean sleeping, Matrix4f modelView, CallbackInfo ci) {
		SwordAiClient.worldDrawn();
	}
}
