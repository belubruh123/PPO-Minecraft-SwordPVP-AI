package dev.swordai.client.mixin;

import dev.swordai.client.KeyedSkin;
import dev.swordai.client.SwordAiClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
	@Shadow
	public abstract void renderLevel(DeltaTracker deltaTracker);

	/**
	 * When the player is to see the opponent as it really looks, the frame is drawn twice. This is the
	 * first time, just before the game's own: the world with the colour key, which the frame grabber
	 * takes (see below) and nobody else sees, since the game's own drawing of the level starts by
	 * clearing the picture. The game draws the level several times in a row itself, for its panorama
	 * screenshots, so a second time is nothing it is not built for.
	 */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
	private void swordai$keyedPassFirst(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		if (!KeyedSkin.twoPass()) {
			return;
		}
		// Paused, nobody needs the keyed picture; the player still gets the real one.
		if (SwordAiClient.picturesWanted()) {
			try {
				this.renderLevel(deltaTracker);
			} catch (RuntimeException e) {
				SwordAiClient.keyedPassFailed(e);
			}
		}
		KeyedSkin.setShowing(true);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER))
	private void swordai$shownPassDone(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		KeyedSkin.setShowing(false);
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
