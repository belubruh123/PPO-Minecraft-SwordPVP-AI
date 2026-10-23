package dev.swordai.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.swordai.client.SwordAiClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla drops to ten frames a second when the window is minimised or nobody has touched the
 * keyboard for a while. The agent needs a fresh frame for every decision, so while it plays the
 * game keeps drawing at the normal rate wherever the window is.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
	private void swordai$keepDrawing(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
		if (SwordAiClient.agentInControl()) {
			cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
		}
	}
}
