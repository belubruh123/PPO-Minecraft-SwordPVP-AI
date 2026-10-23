package dev.swordai.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.ClientSprint;
import dev.swordai.core.env.LagLink;
import dev.swordai.core.env.RemoteView;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.PlayerPose;
import dev.swordai.core.vision.SyntheticEye;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A fake player run as if it had a game client of its own. The network learned on a real client, so
 * a body without one is given everything a client would have made of the same keys. Each tick goes
 * the way a client's does, in the same order:
 *
 * <ol>
 * <li>The opponent is where a client would show it ({@link RemoteView}), not where the server has it.</li>
 * <li>The picture is the frame a client would have drawn last: part-way through the tick before,
 * from where the body and the camera were then ({@link SyntheticEye}).</li>
 * <li>The camera turns, and a click is judged straight away with the new view, against the opponent
 * as shown, before the body moves.</li>
 * <li>Then the keys move the body, and sprinting is kept the way a client keeps it and the server
 * hears of it ({@link ClientSprint}).</li>
 * </ol>
 *
 * <p>{@link #sense} is the first two steps and {@link #act} the last two; whoever decides in between
 * (a trained network that only plays, or one that is learning) is not this class's business.
 *
 * <p>Used by the server thread only.
 */
public final class ReplicaBody {
	/** Vanilla's sprint hit needs more charge than this. */
	private static final float KNOCKBACK_CHARGE = 0.9f;
	/** A body turns at most this far away from its head. */
	private static final float MAX_BODY_TWIST_DEG = 50f;
	/** The server accepts a hit from this much farther away than the attacker's reach. */
	private static final double SERVER_REACH_SLACK = 3.0;
	/** A client scales its movement keys like this: straight, and each of two keys held together. */
	private static final float STRAIGHT_INPUT = 0.98f;
	private static final float DIAGONAL_INPUT = 0.70710677f;
	/** The longest delay {@link #setLag} takes. */
	public static final int MAX_LAG_TICKS = 8;

	/** How far through the tick before the picture was drawn: 1 is the newest possible frame. */
	private final float framePartial;
	private final SyntheticEye eye = new SyntheticEye();
	private final MaskFrame frame = new MaskFrame();
	private final BodyState state = new BodyState();
	private final RemoteView shown = new RemoteView();
	private final PlayerPose drawn = new PlayerPose();
	private final ClientSprint sprint = new ClientSprint();
	/** The opponent reaches this body's eyes late and perhaps in fits and starts, as it would over a network; see {@link #setLag}. */
	private final LagLink<Seen> lag = new LagLink<>();
	private final Random lagRng = new Random();
	/** The opponent as it last arrived; null until the first has. */
	private Seen latest;
	private final Consumer<Seen> arrived = seen -> latest = seen;

	/** Everything about the opponent that decides how it is shown, as of one tick. */
	private record Seen(double x, double y, double z, float bodyYaw, float headYaw, float headPitch, float attackAnim,
			boolean crouching, boolean holdsItem, boolean rightHanded) {
	}

	private boolean hasLast;
	private double lastX;
	private double lastY;
	private double lastZ;
	private double lastEyeY;
	private float lastYaw;
	private float lastPitch;
	/** The weapon's cooldown as the client counts it; the server keeps its own on the body. */
	private int clientCooldown;
	private float cooldownTicks = 1f;

	// What the last action came to.
	private boolean clickOnTarget;
	/** The swing asked for in the last {@link #act} was one to be made only if it reaches, and it would not have. */
	private boolean swingLeftOut;
	private float yawTurned;
	private float pitchTurned;

	// What its swings came to, for the summary after a spar.
	private int swings;
	private int reached;
	private int intoBlock;

	/**
	 * @param frameAgeTicks how old the frame is that a client decides on, in ticks (measured while
	 *                      the network plays the user's own character; about half a tick)
	 */
	public ReplicaBody(float frameAgeTicks) {
		this.framePartial = 1f - Mth.clamp(frameAgeTicks, 0f, 1f);
	}

	/**
	 * How many ticks late this body gets to see its opponent, on top of what a client's own smoothing
	 * adds: none in a singleplayer world, a few on a server. Everything it does goes by the opponent
	 * as it sees it, a click included, exactly as for a real client.
	 */
	public void setLag(int ticks) {
		setLag(ticks, 0f, 0);
	}

	/**
	 * The same, over a connection that also stutters: on each tick there is this chance that nothing
	 * of the opponent arrives for one to {@code stallTicks} ticks, and then all of it at once. It is
	 * then seen standing still and jumping on, as a lagging player is.
	 */
	public void setLag(int ticks, float stallChance, int stallTicks) {
		lag.set(Math.min(ticks, MAX_LAG_TICKS), stallChance, stallTicks);
	}

	/** Start of a round: nothing is remembered and the weapon is ready. */
	public void reset() {
		lag.clear();
		latest = null;
		shown.reset();
		sprint.reset();
		hasLast = false;
		clientCooldown = 1000;
	}

	/** Between rounds: stands still and waits, seeing nothing. */
	public void idle(ServerPlayer body) {
		letGo(body);
		lag.clear();
		latest = null;
		shown.reset();
		hasLast = false;
	}

	/**
	 * Stands still with the keys let go, but goes on seeing: the opponent stays where a client would
	 * have it and the weapon goes on charging, as for a player who has taken their hands off the keys.
	 */
	public void hold(ServerPlayer body, ServerPlayer opponent) {
		letGo(body);
		watch(opponent);
		remember(body, body.getEyePosition(), body.getYRot(), body.getXRot());
		clientCooldown++;
	}

	/** Takes its hands off the keys: no movement, no sprint. */
	public void letGo(ServerPlayer body) {
		EntityPlayerActionPack pack = ((ServerPlayerInterface) body).getActionPack();
		pack.setForward(0f);
		pack.setStrafing(0f);
		body.setSprinting(false);
		sprint.reset();
	}

	/** The picture it decided on last. */
	public MaskFrame frame() {
		return frame;
	}

	/** Its own body as of the last {@link #sense}; the hit clocks are the caller's to fill in. */
	public BodyState state() {
		return state;
	}

	/** Whether the last action's click, if it made one, had the opponent under the crosshair and in reach. */
	public boolean clickOnTarget() {
		return clickOnTarget;
	}

	/** Whether the swing asked for in the last {@link #act} was left out because it would not have reached the opponent. */
	public boolean swingLeftOut() {
		return swingLeftOut;
	}

	/**
	 * What a client's crosshair would be on right now: whether a click made this instant, with the
	 * view as it is, would be on the opponent and within reach. The game's own pick, as in
	 * {@link #click}: the opponent where the client shows it, and no block in the way.
	 */
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

	/** How far the last action really turned the camera, degrees. */
	public float yawTurned() {
		return yawTurned;
	}

	public float pitchTurned() {
		return pitchTurned;
	}

	/** Whether the server has it sprinting: what its next hit is judged with. */
	public boolean serverSprinting() {
		return sprint.server();
	}

	/** Swings made, swings that reached the opponent, and swings that ended on a block. */
	public String swingSummary() {
		return String.format(Locale.ROOT, "%d swings, %d reached you, %d missed", swings, reached, swings - reached)
				+ (intoBlock == 0 ? "" : " (" + intoBlock + " into the floor or a wall)");
	}

	public void clearSummary() {
		swings = 0;
		reached = 0;
		intoBlock = 0;
	}

	private void watch(ServerPlayer opponent) {
		float headYaw = opponent.getYHeadRot();
		float bodyYaw = headYaw + Mth.clamp(ViewGeometry.wrapDegrees(opponent.yBodyRot - headYaw), -MAX_BODY_TWIST_DEG, MAX_BODY_TWIST_DEG);
		Seen sent = new Seen(opponent.getX(), opponent.getY(), opponent.getZ(), bodyYaw, headYaw, opponent.getXRot(),
				opponent.getAttackAnim(1f), opponent.isCrouching(), !opponent.getMainHandItem().isEmpty(),
				opponent.getMainArm() == HumanoidArm.RIGHT);
		lag.send(sent);
		lag.tick(lagRng, arrived);
		if (latest == null) {
			// Nothing has come through yet: it is shown where it stood when the round began.
			latest = sent;
		}
		Seen seen = latest;
		shown.tick(seen.x, seen.y, seen.z, seen.bodyYaw, seen.headYaw, seen.headPitch,
				seen.attackAnim, seen.crouching, seen.holdsItem, seen.rightHanded);
	}

	/**
	 * The first half of its tick: what it sees and what it knows of its own body. Afterwards
	 * {@link #frame} and {@link #state} hold both.
	 */
	public void sense(ServerPlayer body, ServerPlayer opponent, double floorY) {
		Vec3 eyePos = body.getEyePosition();
		float yaw = body.getYRot();
		float pitch = body.getXRot();

		// ---- the opponent as a client shows it --------------------------------------------------
		watch(opponent);

		if (!hasLast) {
			remember(body, eyePos, yaw, pitch);
		}

		// ---- the picture: the last frame a client would have drawn before this tick ---------------
		float t = framePartial;
		double eyeX = lastX + (eyePos.x - lastX) * t;
		double eyeY = lastEyeY + (eyePos.y - lastEyeY) * t;
		double eyeZ = lastZ + (eyePos.z - lastZ) * t;
		float viewYaw = lastYaw + ViewGeometry.wrapDegrees(yaw - lastYaw) * t;
		float viewPitch = lastPitch + (pitch - lastPitch) * t;
		shown.pose(t, drawn);
		eye.look(eyeX, eyeY, eyeZ, viewYaw, viewPitch, drawn, frame);

		// ---- its own body -----------------------------------------------------------------------
		cooldownTicks = body.getCurrentItemAttackStrengthDelay();
		state.yaw = yaw;
		state.pitch = pitch;
		state.height = (float) (body.getY() - floorY);
		state.onGround = body.onGround();
		state.setMotion(body.getX() - lastX, body.getY() - lastY, body.getZ() - lastZ);
		state.setOpponentMotion(shown.movedX(), shown.movedY(), shown.movedZ());
		state.charge = Mth.clamp(clientCooldown / cooldownTicks, 0f, 1f);
		state.setHealth(body.getHealth() / Math.max(1f, body.getMaxHealth()));
		state.sprinting = sprint.client();
		state.stale = false;
		remember(body, eyePos, yaw, pitch);
	}

	/**
	 * Ground truth about where its crosshair is, for scoring: measured against the opponent as its
	 * client shows it, which is what the picture shows too. Call between {@link #sense} and {@link #act}.
	 */
	public void fillGeometry(StepFacts facts, ServerPlayer body, ServerPlayer opponent) {
		PlayerPose there = shown.now();
		Sightline.fill(facts, body.getEyePosition(), body.getYRot(), body.getXRot(), body.getLookAngle(),
				shownBox(opponent), there.x, there.y + opponent.getEyeHeight(), there.z);
		facts.enemyVisible = frame.enemyVisible();
		// The game's own test for the fall a critical hit is made from.
		facts.falling = !body.onGround() && body.fallDistance > 0;
	}

	/** The opponent's eyes as shown, relative to this body's, in world axes: for the critic only. */
	public Vec3 shownEyesFrom(ServerPlayer body, ServerPlayer opponent) {
		PlayerPose there = shown.now();
		Vec3 eyePos = body.getEyePosition();
		return new Vec3(there.x - eyePos.x, there.y + opponent.getEyeHeight() - eyePos.y, there.z - eyePos.z);
	}

	/** The opponent's hitbox where the client shows it. */
	private AABB shownBox(ServerPlayer opponent) {
		PlayerPose there = shown.now();
		double half = opponent.getBbWidth() * 0.5;
		return new AABB(there.x - half, there.y, there.z - half, there.x + half, there.y + opponent.getBbHeight(), there.z + half);
	}

	/**
	 * The second half of its tick: the camera turns, then the click, then the keys.
	 *
	 * @param yawRate degrees to turn this tick (what the hand model made of the chosen speed)
	 */
	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate) {
		act(body, opponent, action, yawRate, pitchRate, false);
	}

	/**
	 * @param swingOnlyIfItReaches the swing of this action was not the network's own but made for it by
	 *                             the rule about answering a hit ({@code AnswerRule}): it is only made
	 *                             if the opponent is under the crosshair once the camera has turned
	 */
	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate, boolean swingOnlyIfItReaches) {
		EntityPlayerActionPack pack = ((ServerPlayerInterface) body).getActionPack();
		float yaw = body.getYRot();
		float pitch = body.getXRot();

		// ---- the camera turns first --------------------------------------------------------------
		float newYaw = yaw + yawRate;
		float newPitch = Mth.clamp(pitch + pitchRate, -90f, 90f);
		pack.look(newYaw, newPitch);
		body.setYHeadRot(newYaw);
		yawTurned = yawRate;
		// Less than asked when the view is already straight up or down.
		pitchTurned = newPitch - pitch;

		// ---- then the click, with the new view and before the body moves --------------------------
		clickOnTarget = false;
		swingLeftOut = false;
		if (action[ActionSpace.ATTACK] == 1) {
			if (swingOnlyIfItReaches && !aimedAt(body, opponent)) {
				swingLeftOut = true;
			} else {
				click(body, opponent);
			}
		}

		// ---- then the keys ------------------------------------------------------------------------
		int forward = ActionSpace.forwardAxis(action);
		int strafe = ActionSpace.strafeAxis(action);
		sprint.move(forward > 0, forward < 0, action[ActionSpace.SPRINT] == 1, body.horizontalCollision);
		float input = forward != 0 && strafe != 0 ? DIAGONAL_INPUT : STRAIGHT_INPUT;
		pack.setForward(forward * input);
		// Carpet's strafing input is positive to the left.
		pack.setStrafing(-strafe * input);
		// The body moves the way the client would move it; what the server believes only matters for hits.
		body.setSprinting(sprint.client());
		if (action[ActionSpace.JUMP] == 1 && body.onGround()) {
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
		}
		clientCooldown++;
	}

	private void remember(ServerPlayer body, Vec3 eyePos, float yaw, float pitch) {
		lastX = body.getX();
		lastY = body.getY();
		lastZ = body.getZ();
		lastEyeY = eyePos.y;
		lastYaw = yaw;
		lastPitch = pitch;
		hasLast = true;
	}

	/**
	 * A left click, as a client resolves it: whatever is under the crosshair decides. The opponent
	 * within reach is hit; empty air is a miss and the weapon's charge starts over; a block in the
	 * way swallows the click, and then only the server starts the charge over (the client never
	 * notices, which is the game's own quirk and is kept).
	 */
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
		// The opponent's box is where the client shows it.
		AABB target = shownBox(opponent).inflate(opponent.getPickRadius());
		Vec3 to = from.add(body.getLookAngle().scale(range));
		Optional<Vec3> entry = target.contains(from) ? Optional.of(from) : target.clip(from, to);
		boolean onOpponent = entry.isPresent() && entry.get().distanceToSqr(from) < limitSqr
				&& entry.get().closerThan(from, entityRange);

		if (onOpponent) {
			reached++;
			clickOnTarget = true;
			boolean clientCharged = (clientCooldown + 0.5f) / cooldownTicks > KNOCKBACK_CHARGE;
			// The server only refuses a hit from far beyond reach.
			double slack = entityRange + SERVER_REACH_SLACK;
			if (opponent.getBoundingBox().distanceToSqr(from) < slack * slack) {
				// The hit counts as the server would count it: with the sprint the server knows of. The
				// game also calls a slow hit a sweep by comparing the attacker's movement with its speed
				// value, which for a player the server has not got sprinting is the walking one.
				Vec3 motion = body.getDeltaMovement();
				body.setSprinting(sprint.server());
				body.setSpeed((float) body.getAttributeValue(Attributes.MOVEMENT_SPEED));
				body.attack(opponent);
				sprint.serverHit(body.isSprinting());
				// How the body moves on is the client's business: it slows down if it took the hit for a
				// sprint hit, whatever the server made of it.
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
		// The swing itself is what resets the cooldown on the server, exactly as a real player's packet does.
		body.swing(InteractionHand.MAIN_HAND);
	}
}
