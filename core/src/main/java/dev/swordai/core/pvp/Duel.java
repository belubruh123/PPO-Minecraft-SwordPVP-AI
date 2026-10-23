package dev.swordai.core.pvp;

/**
 * Two fighters as one of them sees the fight: {@link #me} and {@link #them}. Everything a technique
 * asks about the two together is worked out here, once a tick, by {@link #measure}.
 */
public final class Duel {
	/** A sword's reach, eye to hitbox. */
	public static final double REACH = 3.0;

	public final Body me = new Body();
	public final Body them = new Body();

	/** From my eyes to their hitbox. */
	public double reach;
	/** From their eyes to my hitbox. */
	public double theirReach;
	/** Between our middles, on the ground plane. */
	public double flat;
	/** Unit vector from me to them on the ground plane. */
	public double ux;
	public double uz;
	/** How fast they come at me (towards me positive), and how fast I go at them, blocks a tick. */
	public double theyClose;
	public double iClose;
	/** How fast they go sideways across my line to them, blocks a tick (either way). */
	public double theySideways;

	public void measure() {
		double dx = them.x - me.x;
		double dz = them.z - me.z;
		flat = Math.sqrt(dx * dx + dz * dz);
		ux = flat > 1e-6 ? dx / flat : me.lookX();
		uz = flat > 1e-6 ? dz / flat : me.lookZ();
		reach = eyeToBox(me, them);
		theirReach = eyeToBox(them, me);
		theyClose = -(them.vx * ux + them.vz * uz);
		iClose = me.vx * ux + me.vz * uz;
		theySideways = Math.abs(-them.vx * uz + them.vz * ux);
	}

	/** Distance from {@code a}'s eyes to {@code b}'s hitbox, as the game measures reach. */
	public static double eyeToBox(Body a, Body b) {
		double ex = a.x;
		double ey = a.eyeY();
		double ez = a.z;
		double dx = Math.max(0.0, Math.abs(ex - b.x) - Body.HALF_WIDTH);
		double dz = Math.max(0.0, Math.abs(ez - b.z) - Body.HALF_WIDTH);
		double dy = ey < b.y ? b.y - ey : ey > b.y + Body.HEIGHT ? ey - (b.y + Body.HEIGHT) : 0.0;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** I can hit them now, as far as distance goes. */
	public boolean inReach() {
		return reach <= REACH;
	}

	/** They can hit me now, as far as distance goes. */
	public boolean inTheirReach() {
		return theirReach <= REACH;
	}

	/** They are in the air coming at me from a jump of their own: a jump in. */
	public boolean theyJumpIn() {
		return !them.onGround && them.sinceJump < 14 && theyClose > 0.05;
	}

	public void copyFrom(Duel o) {
		me.copyFrom(o.me);
		them.copyFrom(o.them);
		measure();
	}

	/** The same fight seen from the other side. */
	public void mirrorOf(Duel o) {
		me.copyFrom(o.them);
		them.copyFrom(o.me);
		measure();
	}
}
