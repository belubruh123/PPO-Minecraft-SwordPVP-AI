package dev.swordai.core.env;

import dev.swordai.core.vision.PlayerPose;
import dev.swordai.core.vision.ViewGeometry;

/**
 * Another player as a game client shows it. A client never has the other player's true position:
 * the server sends it every second tick, and the client glides towards the last one it was sent
 * over three ticks. What is drawn, and what a click is tested against, is that gliding copy. It
 * trails the real player by a few ticks and moves smoothly even when the real one does not.
 *
 * <p>A fighter that learned on a real client has only ever seen such a copy, so a body without a
 * client is shown one too, built here by the same rules (1.21.11: players are sent every 2 ticks,
 * {@code InterpolationHandler} takes 3 steps, {@code RemotePlayer} animates its walk from how far
 * the copy itself moved).
 */
public final class RemoteView {
	/** The server sends a player's position every this many ticks. */
	public static final int SEND_INTERVAL = 2;
	/** The client reaches a position it was sent in this many ticks. */
	public static final int GLIDE_TICKS = 3;

	private final PlayerPose now = new PlayerPose();
	private final PlayerPose before = new PlayerPose();
	private double targetX;
	private double targetY;
	private double targetZ;
	private float targetBodyYaw;
	private float targetPitch;
	private int steps;
	private float targetHeadYaw;
	private int headSteps;
	private float walkSpeedBefore;
	private int ticks;
	private boolean started;

	/** Forgets the player; the next {@link #tick} shows it where it is. */
	public void reset() {
		started = false;
	}

	/**
	 * Once per tick, with the player as the server has it now.
	 *
	 * @param attackTime progress of its sword swing, 0 to 1
	 */
	public void tick(double x, double y, double z, float bodyYaw, float headYaw, float headPitch,
			float attackTime, boolean crouching, boolean holdsItem, boolean rightHanded) {
		if (!started) {
			started = true;
			ticks = 0;
			steps = 0;
			headSteps = 0;
			now.standing(x, y, z, bodyYaw, headYaw, headPitch);
			targetX = x;
			targetY = y;
			targetZ = z;
			targetBodyYaw = bodyYaw;
			targetPitch = headPitch;
			targetHeadYaw = headYaw;
			walkSpeedBefore = 0f;
			now.attackTime = attackTime;
			now.crouching = crouching;
			now.holdsItem = holdsItem;
			now.rightHanded = rightHanded;
			before.copyFrom(now);
			return;
		}
		before.copyFrom(now);
		ticks++;
		if (ticks % SEND_INTERVAL == 0) {
			// The same spot again while still on the way there does not start the glide over.
			boolean sameSpot = steps > 0 && x == targetX && y == targetY && z == targetZ
					&& bodyYaw == targetBodyYaw && headPitch == targetPitch;
			if (!sameSpot) {
				targetX = x;
				targetY = y;
				targetZ = z;
				targetBodyYaw = bodyYaw;
				targetPitch = headPitch;
				steps = GLIDE_TICKS;
			}
		}
		// The head's direction is sent whenever it changes.
		if (headYaw != targetHeadYaw) {
			targetHeadYaw = headYaw;
			headSteps = GLIDE_TICKS;
		}
		if (steps > 0) {
			double part = 1.0 / steps;
			now.x += (targetX - now.x) * part;
			now.y += (targetY - now.y) * part;
			now.z += (targetZ - now.z) * part;
			now.bodyYaw = turn(now.bodyYaw, targetBodyYaw, (float) part);
			now.headPitch += (targetPitch - now.headPitch) * (float) part;
			steps--;
		}
		if (headSteps > 0) {
			now.headYaw = turn(now.headYaw, targetHeadYaw, 1f / headSteps);
			headSteps--;
		}
		// The walk cycle follows how far the copy itself moved over the ground.
		float moved = (float) Math.hypot(now.x - before.x, now.z - before.z);
		walkSpeedBefore = now.walkSpeed;
		now.walkSpeed += (Math.min(moved * 4f, 1f) - now.walkSpeed) * 0.4f;
		now.walkPos += now.walkSpeed;
		now.age += 1f;
		now.attackTime = attackTime;
		now.crouching = crouching;
		now.holdsItem = holdsItem;
		now.rightHanded = rightHanded;
	}

	/** The copy as it stands at this tick: what a click is tested against. */
	public PlayerPose now() {
		return now;
	}

	/** How far the copy moved over the last tick. */
	public double movedX() {
		return now.x - before.x;
	}

	public double movedY() {
		return now.y - before.y;
	}

	public double movedZ() {
		return now.z - before.z;
	}

	/**
	 * The copy as a frame drawn part-way through the last tick shows it.
	 *
	 * @param partial 0 is the tick before, 1 is now
	 */
	public void pose(float partial, PlayerPose out) {
		float t = Math.max(0f, Math.min(1f, partial));
		out.x = before.x + (now.x - before.x) * t;
		out.y = before.y + (now.y - before.y) * t;
		out.z = before.z + (now.z - before.z) * t;
		out.bodyYaw = turn(before.bodyYaw, now.bodyYaw, t);
		out.headYaw = turn(before.headYaw, now.headYaw, t);
		out.headPitch = before.headPitch + (now.headPitch - before.headPitch) * t;
		out.walkPos = now.walkPos - now.walkSpeed * (1f - t);
		out.walkSpeed = Math.min(1f, walkSpeedBefore + (now.walkSpeed - walkSpeedBefore) * t);
		// A swing that has just ended runs on to its end rather than backwards.
		float swing = now.attackTime - before.attackTime;
		if (swing < 0f) {
			swing += 1f;
		}
		out.attackTime = before.attackTime + swing * t;
		out.age = now.age - (1f - t);
		out.crouching = now.crouching;
		out.holdsItem = now.holdsItem;
		out.rightHanded = now.rightHanded;
	}

	private static float turn(float from, float to, float part) {
		return from + ViewGeometry.wrapDegrees(to - from) * part;
	}
}
