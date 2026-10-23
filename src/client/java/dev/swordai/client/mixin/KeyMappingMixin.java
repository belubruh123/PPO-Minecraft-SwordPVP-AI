package dev.swordai.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.swordai.client.SwordAiClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the agent plays, presses on the real keyboard and mouse do not reach the key bindings, so
 * clicking back into the window or leaning on a key cannot swing, drop the sword or change the
 * hotbar slot. The chat and command keys still work; Esc and the function keys never go through
 * here. The agent sets its own bindings directly and is not affected.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
	@Inject(method = "click", at = @At("HEAD"), cancellable = true)
	private static void swordai$ignoreClick(InputConstants.Key key, CallbackInfo ci) {
		if (SwordAiClient.ignoresPlayerKey(key)) {
			// While the user is recorded, their swing is held to be made at its moment in the tick.
			SwordAiClient.heldClick(key);
			ci.cancel();
		}
	}

	@Inject(method = "set", at = @At("HEAD"), cancellable = true)
	private static void swordai$ignorePress(InputConstants.Key key, boolean held, CallbackInfo ci) {
		// Releases go through, so a key the player was holding when the agent took over does not stick.
		if (held && SwordAiClient.ignoresPlayerKey(key)) {
			ci.cancel();
		}
	}
}
