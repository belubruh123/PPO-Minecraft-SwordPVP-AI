package dev.swordai.client.mixin;

import dev.swordai.client.KeyedSkin;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets {@link KeyedSkin} repaint other players after vanilla has decided how to draw them. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void swordai$keyOpponent(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		KeyedSkin.apply(avatar, state);
	}
}
