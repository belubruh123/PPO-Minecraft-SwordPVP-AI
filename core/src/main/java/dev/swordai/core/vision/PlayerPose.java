package dev.swordai.core.vision;

/**
 * Everything about a player that decides how the game draws it at one moment: where it stands,
 * which way it faces, and how far its limbs are into their animations. These are the same values
 * the game's own renderer reads.
 */
public final class PlayerPose {
	/** Feet. */
	public double x;
	public double y;
	public double z;
	/** Degrees, Minecraft angles. The head's yaw is absolute, not relative to the body. */
	public float bodyYaw;
	public float headYaw;
	public float headPitch;
	/** The walk cycle: how far along it is and how strongly the limbs swing (0 standing, 1 running). */
	public float walkPos;
	public float walkSpeed;
	/** Progress of a sword swing, 0 to 1; 0 when not swinging. */
	public float attackTime;
	/** Ticks lived; drives the slow sway of the arms. */
	public float age;
	public boolean crouching;
	/** Something is in the main hand, which is held slightly forward. */
	public boolean holdsItem = true;
	public boolean rightHanded = true;

	/** A player standing still at a spot, facing one way. */
	public PlayerPose standing(double x, double y, double z, float bodyYaw, float headYaw, float headPitch) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.bodyYaw = bodyYaw;
		this.headYaw = headYaw;
		this.headPitch = headPitch;
		walkPos = 0f;
		walkSpeed = 0f;
		attackTime = 0f;
		age = 0f;
		crouching = false;
		return this;
	}

	public void copyFrom(PlayerPose o) {
		x = o.x;
		y = o.y;
		z = o.z;
		bodyYaw = o.bodyYaw;
		headYaw = o.headYaw;
		headPitch = o.headPitch;
		walkPos = o.walkPos;
		walkSpeed = o.walkSpeed;
		attackTime = o.attackTime;
		age = o.age;
		crouching = o.crouching;
		holdsItem = o.holdsItem;
		rightHanded = o.rightHanded;
	}
}
