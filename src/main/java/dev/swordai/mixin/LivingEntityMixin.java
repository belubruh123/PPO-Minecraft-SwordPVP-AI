package dev.swordai.mixin;

import dev.swordai.server.KnockbackLag;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a training fight knock its opponent back a few ticks late, as a player with a connection of
 * its own is. Not required: if this cannot be applied the opponent is simply knocked back at once.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "knockback(DDD)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$theOpponentIsKnockedBackLate(double strength, double x, double z, CallbackInfo ci) {
		if (KnockbackLag.hold((LivingEntity) (Object) this, strength, x, z)) {
			ci.cancel();
		}
	}
}
