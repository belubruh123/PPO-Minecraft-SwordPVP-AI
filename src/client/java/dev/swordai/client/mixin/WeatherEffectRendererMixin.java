package dev.swordai.client.mixin;

import dev.swordai.client.KeyedSkin;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves rain and snow out of the agent's picture. The arena it learns in has clear weather; on a
 * server the streaks would run across the opponent's colour key. Not required: if this cannot be
 * applied, the weather is simply drawn.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	@Inject(
			method = "render(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/WeatherRenderState;)V",
			at = @At("HEAD"),
			cancellable = true,
			require = 0)
	private void swordai$noWeatherInTheAgentsPicture(MultiBufferSource buffers, Vec3 camera, WeatherRenderState state, CallbackInfo ci) {
		if (KeyedSkin.hidesWeather()) {
			ci.cancel();
		}
	}
}
