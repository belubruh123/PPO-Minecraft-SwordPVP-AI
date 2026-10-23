package dev.swordai.mixin;

import dev.swordai.OwnSwing;
import dev.swordai.server.ArenaServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records how charged the weapon was, whether the attacker was sprinting and whether it was falling
 * at the instant an attack starts. Vanilla decides "sprint-knockback hit" and "critical hit" from
 * exactly these, then clears them, so they cannot be read back once the damage event fires.
 *
 * <p>The same is noted for the attacks the player's own game client makes ({@link OwnSwing}).
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {
	/** Vanilla makes a hit critical only above this charge, the same bar as for a sprint hit. */
	private static final float SWORDAI_CRIT_CHARGE = 0.9f;

	@Shadow
	private boolean canCriticalAttack(Entity target) {
		throw new AssertionError();
	}

	/** Vanilla's own test: charged, neither critical nor a sprint-knockback hit, on the ground, slower than a sprint, sword in hand. */
	@Shadow
	private boolean isSweepAttack(boolean charged, boolean critical, boolean knockback) {
		throw new AssertionError();
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void swordai$noteAttack(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		// On the server, and on the game client for the player's own character: a client makes the
		// same attack out for itself, which is all there is to go by on someone else's server.
		boolean server = self instanceof ServerPlayer;
		if (server || self.isLocalPlayer()) {
			float charge = self.getAttackStrengthScale(0.5f);
			boolean charged = charge > SWORDAI_CRIT_CHARGE;
			boolean crit = charged && this.canCriticalAttack(target);
			boolean sweep = this.isSweepAttack(charged, crit, charged && self.isSprinting());
			// The last movement the client reported: in the air and going up is what an uppercut is made from.
			boolean rising = !self.onGround() && self.getKnownMovement().y > 0.0;
			if (server) {
				ArenaServer.onAttack(self, charge, self.isSprinting(), crit, rising, sweep);
			} else {
				OwnSwing.note(charge, self.isSprinting(), crit, rising, sweep);
			}
		}
	}
}
