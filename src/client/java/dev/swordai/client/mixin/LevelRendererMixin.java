package dev.swordai.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.swordai.client.ShownSkin;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps what the level is drawn with, so that {@link ShownSkin} can draw the opponent's own skin into it afterwards. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void swordai$levelBegins(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
			Matrix4f modelView, Matrix4f projection, Matrix4f cullingProjection, GpuBufferSlice fog, Vector4f fogColor, boolean renderSky,
			CallbackInfo ci) {
		ShownSkin.levelBegins(deltaTracker, modelView, fog);
	}
}
