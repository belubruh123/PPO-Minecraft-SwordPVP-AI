package dev.swordai.core.pvp;

/**
 * One fighter at one tick, as someone sees it: where it stands, how it moved over the last tick, which
 * way it looks, and the few things about it a technique turns on (on the ground or not, sprinting or
 * not, how far its sword is back). Plain fields, so the server, the client and the tests fill it the
 * same way; nothing here knows about Minecraft classes.
 *
 * <p>Units are the game's: blocks and blocks a tick, degrees with yaw 0 facing +Z.
 */
public final class Body {
	/** A player's hitbox: this wide either way of the middle, this tall; and the eyes this high. */
	public static final double HALF_WIDTH = 0.3;
	public static final double HEIGHT = 1.8;
	public static final double EYE = 1.62;
	/** A sword is back again this many ticks after a swing (attack speed 1.6). */
	public static final float SWORD_TICKS = 12.5f;
	/** A swing counts as charged from here (the game's own line for a knockback or critical hit). */
	public static final float CHARGED = 0.9f;

	/** Feet. */
	public double x;
	public double y;
	public double z;
	/** Movement over the last tick. */
	public double vx;
	public double vy;
	public double vz;
	public float yaw;
	public float pitch;
	public boolean onGround = true;
	public boolean sprinting;
	/** How far the sword is back, 0 just after a swing to 1. */
	public float charge = 1f;
	/** Ticks since this fighter's arm last began a swing (a hit or a miss); 1000 for long ago. */
	public int sinceSwing = 1000;
	/** Ticks since this fighter last took damage; 1000 for long ago. */
	public int sinceHurt = 1000;
	/** Ticks since its feet last left the ground by a jump of its own (its jump key on the ground); 1000 for long ago. */
	public int sinceJump = 1000;
	/** Ticks it has been in the air; 0 on the ground. */
	public int airTicks;
	public float health = 20f;

	public void copyFrom(Body o) {
		x = o.x;
		y = o.y;
		z = o.z;
		vx = o.vx;
		vy = o.vy;
		vz = o.vz;
		yaw = o.yaw;
		pitch = o.pitch;
		onGround = o.onGround;
		sprinting = o.sprinting;
		charge = o.charge;
		sinceSwing = o.sinceSwing;
		sinceHurt = o.sinceHurt;
		sinceJump = o.sinceJump;
		airTicks = o.airTicks;
		health = o.health;
	}

	public Body copy() {
		Body b = new Body();
		b.copyFrom(this);
		return b;
	}

	/** In the air and on the way up. */
	/**
	 * A take-off, seen by the vertical speed alone: from not going up to going up. A client sees another player glide between
	 * the positions it is sent, smaller than its jump and not on the tick its ground flag changes.
	 */
	public static boolean tookOff(double lastVy, double vy) {
		return lastVy <= 0.02 && vy > 0.06;
	}

	public boolean rising() {
		return !onGround && vy > 0.0;
	}

	/** In the air and on the way down: a charged swing now, not sprinting, is a critical hit. */
	public boolean falling() {
		return !onGround && vy < 0.0;
	}

	public boolean swordReady() {
		return charge >= CHARGED;
	}

	public double eyeY() {
		return y + EYE;
	}

	public double flatSpeed() {
		return Math.sqrt(vx * vx + vz * vz);
	}

	/** Unit look direction on the ground plane. */
	public double lookX() {
		return -Math.sin(Math.toRadians(yaw));
	}

	public double lookZ() {
		return Math.cos(Math.toRadians(yaw));
	}
}
