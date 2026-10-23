package dev.swordai.client.mixin;

import dev.swordai.client.SwordAiClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the agent plays, moving the physical mouse must not turn the player. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void swordai$agentOwnsCamera(double movementTime, CallbackInfo ci) {
		if (SwordAiClient.agentInControl()) {
			ci.cancel();
		}
	}
}
