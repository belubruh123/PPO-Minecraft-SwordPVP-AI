package dev.swordai.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sword's recharge clock, so that a drill can begin with the opponent's sword all the way back; see ArenaServer. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("attackStrengthTicker")
	void swordai$setAttackStrengthTicker(int ticks);
}
