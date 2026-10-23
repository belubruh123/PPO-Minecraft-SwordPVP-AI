package dev.swordai.client.mixin;

import dev.swordai.client.SwordAiClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A person's mouse moves the view every frame; the agent turns once per game tick. Drawn as it is,
 * that is twenty jumps a second. Here the drawn view is spread across the tick instead, so the
 * camera glides. Only what is drawn changes: at the tick itself (partial tick 1) the view is the
 * player's real rotation, so aiming, reach and hits are exactly as before.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "getViewYRot", at = @At("RETURN"), cancellable = true)
	private void swordai$glideYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
		float lag = SwordAiClient.viewYawLag(partialTick);
		if (lag != 0f) {
			cir.setReturnValue(cir.getReturnValueF() + lag);
		}
	}

	@Inject(method = "getViewXRot", at = @At("RETURN"), cancellable = true)
	private void swordai$glidePitch(float partialTick, CallbackInfoReturnable<Float> cir) {
		float lag = SwordAiClient.viewPitchLag(partialTick);
		if (lag != 0f) {
			cir.setReturnValue(Math.max(-90f, Math.min(90f, cir.getReturnValueF() + lag)));
		}
	}
}
