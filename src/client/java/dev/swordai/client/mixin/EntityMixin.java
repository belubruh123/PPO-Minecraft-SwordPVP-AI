package dev.swordai.client.mixin;

import dev.swordai.client.SeenLag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Lets a training fight show its opponent late, as a server would: every move the game is told of
 * comes through here, and the opponent's are kept back for a few ticks. Not required: if this
 * cannot be applied the opponent is simply shown without lag, and the report says that none of its
 * moves were held back.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(
			method = "moveOrInterpolateTo(Ljava/util/Optional;Ljava/util/Optional;Ljava/util/Optional;)V",
			at = @At("HEAD"),
			cancellable = true,
			require = 0)
	private void swordai$theOpponentsMovesArriveLate(Optional<Vec3> pos, Optional<Float> yRot, Optional<Float> xRot, CallbackInfo ci) {
		if (SeenLag.hold((Entity) (Object) this, pos, yRot, xRot)) {
			ci.cancel();
		}
	}
}
