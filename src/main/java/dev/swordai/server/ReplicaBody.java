package dev.swordai.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.ClientSprint;
import dev.swordai.core.env.LagLink;
import dev.swordai.core.env.RemoteView;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.PlayerPose;
import dev.swordai.core.vision.SyntheticEye;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A fake player run with exact client physics (RemoteView glide, SyntheticEye vision, ClientSprint,
 * vanilla Shield blocking / Axe 5 s disable / Golden Apple eating / Hotbar switching / Offhand swap).
 * Used by the server thread only.
 */
public final class ReplicaBody {
	private static final float KNOCKBACK_CHARGE = 0.9f;
	private static final float MAX_BODY_TWIST_DEG = 50f;
	private static final double SERVER_REACH_SLACK = 3.0;
	private static final float STRAIGHT_INPUT = 0.98f;
	private static final float DIAGONAL_INPUT = 0.70710677f;
	public static final int MAX_LAG_TICKS = 8;

	private final float framePartial;
	private final SyntheticEye eye = new SyntheticEye();
	private final MaskFrame frame = new MaskFrame();
	private final BodyState state = new BodyState();
	private final float[] slots = new float[ObsSpec.SLOTS_SIZE];
	private final float[] terrain = new float[ObsSpec.TERRAIN_SIZE];
	private final RemoteView shown = new RemoteView();
	private final PlayerPose drawn = new PlayerPose();
	private final ClientSprint sprint = new ClientSprint();
	private final LagLink<Seen> lag = new LagLink<>();
	private final Random lagRng = new Random();
	private Seen latest;
	private final Consumer<Seen> arrived = seen -> latest = seen;

	private record Seen(double x, double y, double z, float bodyYaw, float headYaw, float headPitch, float attackAnim,
			boolean crouching, boolean holdsItem, boolean rightHanded, boolean swinging, int swingTime, int hurtTime,
			boolean oppSword, boolean oppAxe, boolean oppUsing) {
	}

	private boolean hasLast;
	private double lastX, lastY, lastZ, lastEyeY;
	private float lastYaw, lastPitch;
	private int clientCooldown;
	private float cooldownTicks = 1f;
	private boolean clickOnTarget, swingLeftOut;
	private float yawTurned, pitchTurned;
	private int swings, reached, intoBlock;

	public ReplicaBody(float frameAgeTicks) {
		this.framePartial = 1f - Mth.clamp(frameAgeTicks, 0f, 1f);
	}

	public void setLag(int ticks) { setLag(ticks, 0f, 0); }
	public void setLag(int ticks, float stallChance, int stallTicks) {
		lag.set(Math.min(ticks, MAX_LAG_TICKS), stallChance, stallTicks);
	}

	public void reset() {
		lag.clear(); latest = null; state.resetOpponentSwing();
		state.setItemReactionTicks(3 + lagRng.nextInt(2));
		shown.reset(); sprint.reset(); hasLast = false; clientCooldown = 1000;
	}

	public void idle(ServerPlayer body) {
		letGo(body); lag.clear(); latest = null; shown.reset(); hasLast = false;
	}

	public void hold(ServerPlayer body, ServerPlayer opponent) {
		letGo(body); watch(opponent);
		remember(body, body.getEyePosition(), body.getYRot(), body.getXRot());
		clientCooldown++;
	}

	public void letGo(ServerPlayer body) {
		EntityPlayerActionPack pack = ((ServerPlayerInterface) body).getActionPack();
		pack.setForward(0f); pack.setStrafing(0f);
		body.setSprinting(false); body.stopUsingItem();
		sprint.reset();
	}

	public MaskFrame frame() { return frame; }
	public BodyState state() { return state; }
	public float[] slots() { return slots; }
	public float[] terrain() { return terrain; }
	public boolean clickOnTarget() { return clickOnTarget; }
	public boolean swingLeftOut() { return swingLeftOut; }
	public float yawTurned() { return yawTurned; }
	public float pitchTurned() { return pitchTurned; }
	public boolean serverSprinting() { return sprint.server(); }

	public boolean aimedAt(ServerPlayer body, ServerPlayer opponent) {
		Vec3 from = body.getEyePosition();
		double entityRange = body.entityInteractionRange();
		double range = Math.max(entityRange, body.blockInteractionRange());
		HitResult block = body.pick(range, 1f, false);
		double limitSqr = range * range;
		if (block.getType() != HitResult.Type.MISS) {
			limitSqr = block.getLocation().distanceToSqr(from);
			range = Math.sqrt(limitSqr);
		}
		AABB target = shownBox(opponent).inflate(opponent.getPickRadius());
		Vec3 to = from.add(body.getLookAngle().scale(range));
		Optional<Vec3> entry = target.contains(from) ? Optional.of(from) : target.clip(from, to);
		return entry.isPresent() && entry.get().distanceToSqr(from) < limitSqr && entry.get().closerThan(from, entityRange);
	}

	public String swingSummary() {
		return String.format(Locale.ROOT, "%d swings, %d reached you, %d missed", swings, reached, swings - reached)
				+ (intoBlock == 0 ? "" : " (" + intoBlock + " into the floor or a wall)");
	}

	public void clearSummary() { swings = reached = intoBlock = 0; }

	private void watch(ServerPlayer opponent) {
		float headYaw = opponent.getYHeadRot();
		float bodyYaw = headYaw + Mth.clamp(ViewGeometry.wrapDegrees(opponent.yBodyRot - headYaw), -MAX_BODY_TWIST_DEG, MAX_BODY_TWIST_DEG);
		ItemStack oppMain = opponent.getMainHandItem();
		Seen sent = new Seen(opponent.getX(), opponent.getY(), opponent.getZ(), bodyYaw, headYaw, opponent.getXRot(),
				opponent.getAttackAnim(1f), opponent.isCrouching(), !oppMain.isEmpty(),
				opponent.getMainArm() == HumanoidArm.RIGHT, opponent.swinging, opponent.swingTime, opponent.hurtTime,
				InventoryObs.isSword(oppMain), InventoryObs.isAxe(oppMain), opponent.isUsingItem());
		lag.send(sent);
		lag.tick(lagRng, arrived);
		if (latest == null) latest = sent;
		Seen seen = latest;
		shown.tick(seen.x, seen.y, seen.z, seen.bodyYaw, seen.headYaw, seen.headPitch,
				seen.attackAnim, seen.crouching, seen.holdsItem, seen.rightHanded);
		state.noteOpponentSwing(seen.swinging, seen.swingTime);
		state.noteOpponentItem(seen.oppSword, seen.oppAxe, seen.oppUsing);
		state.oppHurt = Math.min(1f, seen.hurtTime / 10f);
	}

	public void sense(ServerPlayer body, ServerPlayer opponent, double floorY) {
		Vec3 eyePos = body.getEyePosition();
		float yaw = body.getYRot();
		float pitch = body.getXRot();
		watch(opponent);
		if (!hasLast) remember(body, eyePos, yaw, pitch);

		float t = framePartial;
		double eyeX = lastX + (eyePos.x - lastX) * t;
		double eyeY = lastEyeY + (eyePos.y - lastEyeY) * t;
		double eyeZ = lastZ + (eyePos.z - lastZ) * t;
		float viewYaw = lastYaw + ViewGeometry.wrapDegrees(yaw - lastYaw) * t;
		float viewPitch = lastPitch + (pitch - lastPitch) * t;
		shown.pose(t, drawn);
		eye.look(eyeX, eyeY, eyeZ, viewYaw, viewPitch, drawn, frame);

		InventoryObs.fillSlots(body, slots);
		double localGroundY = InventoryObs.fillTerrain(body, terrain);

		cooldownTicks = body.getCurrentItemAttackStrengthDelay();
		state.yaw = yaw;
		state.pitch = pitch;
		state.height = (float) (body.getY() - localGroundY);
		state.onGround = body.onGround();
		state.oppOnGround = opponent.onGround();
		state.setMotion(body.getX() - lastX, body.getY() - lastY, body.getZ() - lastZ);
		state.setOpponentMotion(shown.movedX(), shown.movedY(), shown.movedZ());
		state.charge = Mth.clamp(clientCooldown / Math.max(1f, cooldownTicks), 0f, 1f);
		state.setHealth((body.getHealth() + body.getAbsorptionAmount()) / Math.max(1f, body.getMaxHealth()));
		state.sprinting = sprint.client();
		state.stale = false;
		state.onTarget = aimedAt(body, opponent);
		state.ownUsingItem = body.isUsingItem();
		state.ownUseTicks = body.isUsingItem() ? Math.min(1f, body.getTicksUsingItem() / 20f) : 0f;
		state.ownShieldCooldown = InventoryObs.shieldCooldown(body);
		state.ownHeldSword = InventoryObs.isSword(body.getMainHandItem());
		state.ownHeldAxe = InventoryObs.isAxe(body.getMainHandItem());
		remember(body, eyePos, yaw, pitch);
	}

	public void fillGeometry(StepFacts facts, ServerPlayer body, ServerPlayer opponent) {
		PlayerPose there = shown.now();
		Sightline.fill(facts, body.getEyePosition(), body.getYRot(), body.getXRot(), body.getLookAngle(),
				shownBox(opponent), there.x, there.y + opponent.getEyeHeight(), there.z);
		facts.enemyVisible = frame.enemyVisible();
		facts.falling = !body.onGround() && body.fallDistance > 0;
	}

	public Vec3 shownEyesFrom(ServerPlayer body, ServerPlayer opponent) {
		PlayerPose there = shown.now();
		Vec3 eyePos = body.getEyePosition();
		return new Vec3(there.x - eyePos.x, there.y + opponent.getEyeHeight() - eyePos.y, there.z - eyePos.z);
	}

	private AABB shownBox(ServerPlayer opponent) {
		PlayerPose there = shown.now();
		double half = opponent.getBbWidth() * 0.5;
		return new AABB(there.x - half, there.y, there.z - half, there.x + half, there.y + opponent.getBbHeight(), there.z + half);
	}

	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate) {
		act(body, opponent, action, yawRate, pitchRate, false);
	}

	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate, boolean swingOnlyIfItReaches) {
		EntityPlayerActionPack pack = ((ServerPlayerInterface) body).getActionPack();
		float yaw = body.getYRot();
		float pitch = body.getXRot();

		// 1. Camera turns first
		float newYaw = yaw + yawRate;
		float newPitch = Mth.clamp(pitch + pitchRate, -90f, 90f);
		pack.look(newYaw, newPitch);
		body.setYHeadRot(newYaw);
		yawTurned = yawRate;
		pitchTurned = newPitch - pitch;

		// 2. Hotbar pointer selection & offhand swap (matches vanilla resetAttackStrengthTicker)
		int wantSlot = action[ActionSpace.HOTBAR_SLOT];
		if (wantSlot >= 1 && wantSlot <= ObsSpec.HOTBAR_SLOTS) {
			int idx = wantSlot - 1;
			if (idx != body.getInventory().getSelectedSlot()) {
				body.getInventory().setSelectedSlot(idx);
				if (body.isUsingItem() && body.getUsedItemHand() == InteractionHand.MAIN_HAND) body.stopUsingItem();
				body.resetAttackStrengthTicker();
				clientCooldown = 0;
			}
		}
		if (action[ActionSpace.OFFHAND_SWAP] == 1) {
			ItemStack mh = body.getMainHandItem().copy(), oh = body.getOffhandItem().copy();
			body.setItemInHand(InteractionHand.MAIN_HAND, oh);
			body.setItemInHand(InteractionHand.OFF_HAND, mh);
			body.stopUsingItem();
			body.resetAttackStrengthTicker();
			clientCooldown = 0;
		}

		// 3. Right-click USE_ITEM (vanilla Shield raise / Golden Apple eat)
		boolean wantsUse = action[ActionSpace.USE_ITEM] == 1 && action[ActionSpace.ATTACK] == 0;
		if (wantsUse) {
			if (!body.isUsingItem()) {
				ItemStack mh = body.getMainHandItem(), oh = body.getOffhandItem();
				if (InventoryObs.isGap(mh) || (InventoryObs.isShield(mh) && !body.getCooldowns().isOnCooldown(mh))) {
					body.startUsingItem(InteractionHand.MAIN_HAND);
				} else if (InventoryObs.isShield(oh) && !body.getCooldowns().isOnCooldown(oh)) {
					body.startUsingItem(InteractionHand.OFF_HAND);
				}
			}
		} else if (body.isUsingItem()) {
			body.releaseUsingItem();
		}

		// 4. Left-click ATTACK (blocked in vanilla while actively using shield/food)
		clickOnTarget = false;
		swingLeftOut = false;
		if (action[ActionSpace.ATTACK] == 1 && !body.isUsingItem()) {
			if (swingOnlyIfItReaches && !aimedAt(body, opponent)) {
				swingLeftOut = true;
			} else {
				click(body, opponent);
			}
		}

		// 5. Movement & sprint (vanilla 0.2x slow-walk & no sprint while using item)
		int forward = ActionSpace.forwardAxis(action);
		int strafe = ActionSpace.strafeAxis(action);
		boolean using = body.isUsingItem();
		if (using) {
			sprint.reset();
			body.setSprinting(false);
		} else {
			sprint.move(forward > 0, forward < 0, action[ActionSpace.SPRINT] == 1, body.horizontalCollision);
			body.setSprinting(sprint.client());
		}
		float baseIn = forward != 0 && strafe != 0 ? DIAGONAL_INPUT : STRAIGHT_INPUT;
		float input = using ? baseIn * 0.2f : baseIn;
		pack.setForward(forward * input);
		pack.setStrafing(-strafe * input);
		if (action[ActionSpace.JUMP] == 1 && body.onGround()) {
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
		}
		clientCooldown++;
	}

	private void remember(ServerPlayer body, Vec3 eyePos, float yaw, float pitch) {
		lastX = body.getX(); lastY = body.getY(); lastZ = body.getZ();
		lastEyeY = eyePos.y; lastYaw = yaw; lastPitch = pitch; hasLast = true;
	}

	private void click(ServerPlayer body, ServerPlayer opponent) {
		swings++;
		Vec3 from = body.getEyePosition();
		double entityRange = body.entityInteractionRange();
		double blockRange = body.blockInteractionRange();
		double range = Math.max(entityRange, blockRange);
		HitResult block = body.pick(range, 1f, false);
		boolean blockAhead = block.getType() != HitResult.Type.MISS;
		double limitSqr = range * range;
		if (blockAhead) {
			limitSqr = block.getLocation().distanceToSqr(from);
			range = Math.sqrt(limitSqr);
		}
		AABB target = shownBox(opponent).inflate(opponent.getPickRadius());
		Vec3 to = from.add(body.getLookAngle().scale(range));
		Optional<Vec3> entry = target.contains(from) ? Optional.of(from) : target.clip(from, to);
		boolean onOpponent = entry.isPresent() && entry.get().distanceToSqr(from) < limitSqr
				&& entry.get().closerThan(from, entityRange);

		if (onOpponent) {
			reached++;
			clickOnTarget = true;
			boolean clientCharged = (clientCooldown + 0.5f) / Math.max(1f, cooldownTicks) > KNOCKBACK_CHARGE;
			double slack = entityRange + SERVER_REACH_SLACK;
			if (opponent.getBoundingBox().distanceToSqr(from) < slack * slack) {
				boolean oppWasBlocking = opponent.isBlocking();
				boolean usedAxe = InventoryObs.isAxe(body.getMainHandItem());
				Vec3 motion = body.getDeltaMovement();
				body.setSprinting(sprint.server());
				body.setSpeed((float) body.getAttributeValue(Attributes.MOVEMENT_SPEED));
				body.attack(opponent);
				if (oppWasBlocking && usedAxe && !opponent.isBlocking()) {
					state.noteOpponentShieldBroken();
				}
				sprint.serverHit(body.isSprinting());
				boolean slowed = sprint.clientHit(clientCharged);
				body.setDeltaMovement(slowed ? motion.multiply(0.6, 1.0, 0.6) : motion);
				body.setSprinting(sprint.client());
				body.setSpeed((float) body.getAttributeValue(Attributes.MOVEMENT_SPEED));
			} else {
				sprint.clientHit(clientCharged);
			}
			clientCooldown = 0;
		} else if (blockAhead && block.getLocation().closerThan(from, blockRange)) {
			intoBlock++;
		} else {
			clientCooldown = 0;
		}
		body.swing(InteractionHand.MAIN_HAND);
	}
}
