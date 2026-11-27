package dev.swordai.client.mixin;

import dev.swordai.client.SwordAiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs the agent's exact vanilla left-click (attack) and right-click (Shield / Golden Apple use)
 * right after vanilla's key handling and before player movement, using vanilla's own reach, cooldown,
 * and item-use paths.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	public int missTime;

	@Shadow
	public LocalPlayer player;

	@Shadow
	public MultiPlayerGameMode gameMode;

	@Shadow
	private boolean startAttack() {
		throw new AssertionError();
	}

	@Shadow
	private void continueAttack(boolean held) {
		throw new AssertionError();
	}

	@Shadow
	private void startUseItem() {
		throw new AssertionError();
	}

	@Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
	private void swordai$fasterTicks(float normal, CallbackInfoReturnable<Float> cir) {
		float millis = SwordAiClient.tickMillis();
		if (millis > 0f) {
			cir.setReturnValue(millis);
		}
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"))
	private void swordai$agentInput(CallbackInfo ci) {
		boolean wantAttack = SwordAiClient.beforePlayerMoves();
		if (SwordAiClient.agentInControl() && this.player != null && this.gameMode != null) {
			boolean wantUse = SwordAiClient.agentWantsUseItem() && !wantAttack;
			if (wantUse) {
				if (!this.player.isUsingItem()) {
					this.startUseItem();
				}
			} else if (this.player.isUsingItem()) {
				this.gameMode.releaseUsingItem(this.player);
			}
		}
		if (!wantAttack || (this.player != null && this.player.isUsingItem())) {
			return;
		}
		this.missTime = 0;
		this.startAttack();
		this.continueAttack(false);
	}
}
