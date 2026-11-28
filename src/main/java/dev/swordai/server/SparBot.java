package dev.swordai.server;

import dev.swordai.core.league.InverseDynamics;
import dev.swordai.core.league.League;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Random;

/**
 * Full-fight sparring opponent supporting BOTH pure Sword styles (crit spammer, jump-in critter,
 * combo player, mixer) AND SMP Phase 1 combat (shield blocking, axe 5 s disable -> sword crit,
 * running away to eat Golden Apples when low HP, and jumping 1-block terraces).
 */
public final class SparBot {
	private final ReplicaBody body = new ReplicaBody(0.5f);
	private final AimMotor motor = new AimMotor(0.6f);
	private final int[] action = new int[ActionSpace.HEADS];
	private final InverseDynamics.LaggedRecorder idmRec = new InverseDynamics.LaggedRecorder();

	private League.SparStyle style = League.SparStyle.MIXER;
	private Random rng = new Random(1L);
	private int strafeDir = 1, strafeTimer;
	private int sinceHitDealt = 100, sinceHitTaken = 100, ownCombo, critChain;
	private int shieldHoldTicks, gapRetreatTicks;

	public void setInverseDynamics(InverseDynamics idm) { this.idmRec.idm = idm; }

	public void start(League.SparStyle style, long seed, int lagTicks) {
		this.style = style == null ? League.SparStyle.MIXER : style;
		this.rng = new Random(seed == 0L ? System.nanoTime() : seed);
		this.body.reset(); this.body.setLag(lagTicks); this.motor.reset();
		this.strafeDir = rng.nextBoolean() ? 1 : 2;
		this.strafeTimer = 10 + rng.nextInt(20);
		this.sinceHitDealt = 100; this.sinceHitTaken = 100;
		this.ownCombo = 0; this.critChain = 0;
		this.shieldHoldTicks = 0; this.gapRetreatTicks = 0;
		this.idmRec.reset();
	}

	public void idle(ServerPlayer bot) { body.idle(bot); }
	public void forceShield(int ticks) { this.shieldHoldTicks = Math.max(this.shieldHoldTicks, ticks); }
	public void hitDealt() { sinceHitDealt = 0; ownCombo++; }
	public void hitTaken() { sinceHitTaken = 0; ownCombo = 0; idmRec.tookHit = true; }

	public void tick(ServerPlayer bot, ServerPlayer opp, double floorY) {
		body.sense(bot, opp, floorY);
		sinceHitDealt = Math.min(1000, sinceHitDealt + 1);
		sinceHitTaken = Math.min(1000, sinceHitTaken + 1);
		Arrays.fill(action, 0);

		Vec3 eye = bot.getEyePosition();
		double dx = opp.getX() - eye.x;
		double dy = opp.getEyeY() - 0.25 - eye.y;
		double dz = opp.getZ() - eye.z;
		float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(eye));
		float yawErr = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(dx, dz) - bot.getYRot());
		float pitchErr = ViewGeometry.pitchTo(dx, dy, dz) - bot.getXRot();

		int swordSlot = InventoryObs.findHotbarSlot(bot, ObsSpec.SF_SWORD);
		int axeSlot = InventoryObs.findHotbarSlot(bot, ObsSpec.SF_AXE);
		int gapSlot = InventoryObs.findHotbarSlot(bot, ObsSpec.SF_GAP);
		int shieldHotbar = InventoryObs.findHotbarSlot(bot, ObsSpec.SF_SHIELD);
		boolean hasOffhandShield = InventoryObs.isShield(bot.getOffhandItem());
		boolean shieldReady = hasOffhandShield && InventoryObs.hasShieldReady(bot);
		boolean smpKit = axeSlot >= 0 || gapSlot >= 0 || hasOffhandShield || shieldHotbar >= 0;
		int selSlot = bot.getInventory().getSelectedSlot();
		float effHp = bot.getHealth() + bot.getAbsorptionAmount();

		// If shield spawned in hotbar, swap it to offhand once at fight start (just like a human pressing F)
		if (!hasOffhandShield && shieldHotbar >= 0) {
			if (selSlot != shieldHotbar) {
				action[ActionSpace.HOTBAR_SLOT] = shieldHotbar + 1;
			} else {
				action[ActionSpace.OFFHAND_SWAP] = 1;
			}
		}

		// SMP Gap-retreat behavior: when low HP and holding golden apples, turn away to sprint-jump create space then eat!
		if (smpKit && gapSlot >= 0 && effHp <= 11f && gapRetreatTicks == 0 && !bot.isUsingItem() && sinceHitTaken <= 10) {
			gapRetreatTicks = 52;
		}
		if (gapRetreatTicks > 0) {
			gapRetreatTicks--;
			if (gapSlot < 0 || effHp >= 17f) {
				gapRetreatTicks = 0;
			} else {
				boolean eating = bot.isUsingItem() && InventoryObs.isGap(bot.getUseItem());
				if (!eating && dist < 5.5f && gapRetreatTicks > 34) {
					// Run away 180 deg + sprint-jump to create safe eating distance
					float awayYawErr = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(-dx, -dz) - bot.getYRot());
					action[ActionSpace.YAW] = InverseDynamics.nearestBin(awayYawErr, ActionSpace.YAW_BINS);
					action[ActionSpace.PITCH] = InverseDynamics.nearestBin(-bot.getXRot(), ActionSpace.PITCH_BINS);
					action[ActionSpace.FORWARD] = 1;
					action[ActionSpace.SPRINT] = 1;
					action[ActionSpace.JUMP] = bot.onGround() ? 1 : 0;
					if (selSlot != gapSlot) action[ActionSpace.HOTBAR_SLOT] = gapSlot + 1;
				} else {
					// Eat the Golden Apple while walking back/away
					action[ActionSpace.YAW] = InverseDynamics.nearestBin(yawErr, ActionSpace.YAW_BINS);
					action[ActionSpace.PITCH] = InverseDynamics.nearestBin(pitchErr, ActionSpace.PITCH_BINS);
					action[ActionSpace.FORWARD] = 2;
					if (selSlot != gapSlot) action[ActionSpace.HOTBAR_SLOT] = gapSlot + 1;
					else action[ActionSpace.USE_ITEM] = 1;
				}
				motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
				body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
				return;
			}
		}

		yawErr += (rng.nextFloat() - 0.5f) * 3f;
		pitchErr += (rng.nextFloat() - 0.5f) * 2f;
		action[ActionSpace.YAW] = InverseDynamics.nearestBin(yawErr, ActionSpace.YAW_BINS);
		action[ActionSpace.PITCH] = InverseDynamics.nearestBin(pitchErr, ActionSpace.PITCH_BINS);

		float charge = bot.getAttackStrengthScale(0f);
		boolean onGround = bot.onGround();
		boolean falling = !onGround && bot.fallDistance > 0.0;
		boolean serverSprint = body.serverSprinting();
		boolean aimed = body.aimedAt(bot, opp);
		boolean ready = charge >= 0.92f;
		boolean oppAir = !opp.onGround();
		boolean oppBlocking = opp.isBlocking() || (opp.isUsingItem() && InventoryObs.isShield(opp.getUseItem()));

		if (--strafeTimer <= 0) {
			strafeDir = rng.nextInt(3);
			strafeTimer = 8 + rng.nextInt(20);
		}

		// SMP Axe-break / Shield-block mindgames when SMP kit is active
		if (smpKit && action[ActionSpace.OFFHAND_SWAP] == 0 && action[ActionSpace.HOTBAR_SLOT] == 0) {
			if (oppBlocking && axeSlot >= 0 && dist < 4.5f) {
				shieldHoldTicks = 0;
				if (selSlot != axeSlot) action[ActionSpace.HOTBAR_SLOT] = axeSlot + 1;
			} else if (shieldReady && shieldHoldTicks <= 0 && dist < 3.6f && !oppBlocking
					&& ((oppAir && opp.fallDistance > 0.0 && charge < 0.85f) || (charge < 0.50f && rng.nextFloat() < 0.14f))) {
				shieldHoldTicks = 7 + rng.nextInt(6);
			} else if (swordSlot >= 0 && selSlot != swordSlot && (!oppBlocking || axeSlot < 0)) {
				action[ActionSpace.HOTBAR_SLOT] = swordSlot + 1;
			}
		} else if (!smpKit && swordSlot >= 0 && selSlot != swordSlot) {
			action[ActionSpace.HOTBAR_SLOT] = swordSlot + 1;
		}

		if (shieldHoldTicks > 0 && shieldReady && !oppBlocking) {
			shieldHoldTicks--;
			action[ActionSpace.FORWARD] = dist < 2.2f ? 2 : 0;
			action[ActionSpace.STRAFE] = strafeDir;
			action[ActionSpace.SPRINT] = 0;
			action[ActionSpace.JUMP] = 0;
			action[ActionSpace.ATTACK] = 0;
			action[ActionSpace.USE_ITEM] = 1;
			motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
			body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
			return;
		}
		shieldHoldTicks = 0;

		int fwd = 1, strafe = dist < 4.5f ? strafeDir : 0, sprint = 1, jump = 0, attack = 0;
		League.SparStyle activeStyle = style == League.SparStyle.MIXER
				? League.SparStyle.values()[(sinceHitDealt + sinceHitTaken + strafeTimer) % 3] : style;

		switch (activeStyle) {
			case CRIT_SPAMMER -> {
				if (dist < 3.1f && onGround && (charge >= 0.55f || sinceHitDealt <= 4)) {
					jump = 1; fwd = dist > 2.3f ? 1 : 0; sprint = 0; critChain++;
				} else if (dist < 1.9f) { fwd = 0; sprint = 0; }
				if (!onGround) {
					sprint = 0;
					if (bot.isSprinting() || serverSprint || falling || dist < 2.6f) fwd = 0;
					attack = (falling && ready && aimed && !serverSprint) ? 1 : 0;
				} else {
					attack = (ready && aimed && (dist > 2.7f || charge >= 0.98f)) ? 1 : 0;
				}
			}
			case JUMP_IN_CRITTER -> {
				if (onGround && dist >= 3.0f && dist <= 4.6f && charge >= 0.65f) {
					jump = 1; fwd = 1; sprint = 1;
				} else if (!onGround) {
					sprint = 0;
					fwd = (bot.isSprinting() || serverSprint || falling || dist < 2.9f) ? 0 : 1;
					attack = (falling && ready && aimed && !serverSprint) ? 1 : 0;
				} else {
					if (sinceHitDealt <= 4 && dist < 2.8f) { fwd = 2; sprint = 0; }
					attack = (ready && aimed) ? 1 : 0;
				}
			}
			case COMBO_PLAYER, MIXER -> {
				boolean risingOrPeak = !onGround && !falling;
				if (sinceHitTaken == 1 && onGround && dist < 3.4f && rng.nextFloat() < 0.70f) {
					jump = 1;
				} else if (ownCombo >= 1 && sinceHitDealt >= 2 && sinceHitDealt <= 5 && onGround && dist >= 2.6f && dist <= 4.2f) {
					jump = 1; fwd = 1; sprint = 1;
				} else if (ownCombo >= 1 && dist < 2.5f && charge < 0.85f) {
					fwd = dist < 2.15f ? 2 : 0; sprint = 0;
				} else if (sinceHitDealt <= 2) {
					fwd = 0; sprint = 0;
				} else if (oppAir && dist > 2.7f) {
					fwd = 1; sprint = 1;
				}
				if (!onGround && risingOrPeak && ownCombo >= 1 && ready && aimed) {
					attack = 1;
				} else if (!onGround && falling && ready && aimed) {
					fwd = (bot.isSprinting() || serverSprint) ? 0 : fwd; sprint = 0;
					attack = !serverSprint ? 1 : 0;
				} else if (onGround && ready && aimed) {
					attack = 1;
				}
			}
		}

		// When opponent is holding up a shield and we hold an axe, swing right away to disable it!
		if (oppBlocking && InventoryObs.isAxe(bot.getMainHandItem()) && aimed && charge >= 0.55f) {
			attack = 1;
		}
		// Step up 1-block terraces cleanly when walking into a ledge
		if (onGround && body.terrain()[3 * ObsSpec.TERRAIN_GRID + 2] >= 0.4f && fwd == 1) {
			jump = 1;
		}

		action[ActionSpace.FORWARD] = fwd;
		action[ActionSpace.STRAFE] = strafe;
		action[ActionSpace.SPRINT] = sprint;
		action[ActionSpace.JUMP] = jump;
		action[ActionSpace.ATTACK] = attack;

		if (!smpKit) {
			idmRec.record(bot.getX(), bot.getY(), bot.getZ(), bot.yBodyRot, bot.getYHeadRot(), bot.getXRot(),
					bot.onGround(), bot.isSprinting(), bot.swinging, bot.swingTime, opp.getX(), opp.getY(), opp.getZ(), dist, action);
		}
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
	}
}
