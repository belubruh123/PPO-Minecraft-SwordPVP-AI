package dev.swordai.client.mixin;

import dev.swordai.client.SeenLag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Run 63 (10 Nov): every packet about the training opponent is held back together, as a connection holds it: its moves (and
 * with them its ground flag), its arm swing, its hurt flash and damage, its data (sprinting, health) and its motion. Before,
 * only the moves were held ({@link EntityMixin}), so the opponent's arm and hit flash showed 1-4 ticks before its body (agent 4:
 * real flag-to-rise 1 tick in 99% of hits, the arena 2-5). Not required: a handler that cannot be hooked simply gets its packet
 * at once, as before. Held only on the client thread (a handler's first call from the network thread hands it over).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketLagMixin {
	private ClientPacketListener swordai$self() {
		return (ClientPacketListener) (Object) this;
	}

	@Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagMove(ClientboundMoveEntityPacket p, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread() && mc.level != null && SeenLag.holdPacket(p.getEntity(mc.level), () -> swordai$self().handleMoveEntity(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleEntityPositionSync", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagSync(ClientboundEntityPositionSyncPacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.id(), () -> swordai$self().handleEntityPositionSync(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagTeleport(ClientboundTeleportEntityPacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.id(), () -> swordai$self().handleTeleportEntity(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleRotateMob", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagHead(ClientboundRotateHeadPacket p, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread() && mc.level != null && SeenLag.holdPacket(p.getEntity(mc.level), () -> swordai$self().handleRotateMob(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleAnimate", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagArm(ClientboundAnimatePacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.getId(), () -> swordai$self().handleAnimate(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleHurtAnimation", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagHurt(ClientboundHurtAnimationPacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.id(), () -> swordai$self().handleHurtAnimation(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagEvent(ClientboundEntityEventPacket p, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread() && mc.level != null && SeenLag.holdPacket(p.getEntity(mc.level), () -> swordai$self().handleEntityEvent(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleDamageEvent", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagDamage(ClientboundDamageEventPacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.entityId(), () -> swordai$self().handleDamageEvent(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleSetEntityData", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagData(ClientboundSetEntityDataPacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.id(), () -> swordai$self().handleSetEntityData(p))) {
			ci.cancel();
		}
	}

	@Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true, require = 0)
	private void swordai$lagMotion(ClientboundSetEntityMotionPacket p, CallbackInfo ci) {
		if (SeenLag.holdPacket(p.getId(), () -> swordai$self().handleSetEntityMotion(p))) {
			ci.cancel();
		}
	}
}
