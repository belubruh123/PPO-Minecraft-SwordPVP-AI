package dev.swordai.core.pvp;

/**
 * What happened between two fighters on one tick, as one of them knows it: hits either way and what
 * kind they were, and swings begun. Filled by whatever drives a fighter, cleared every tick.
 */
public final class Events {
	public static final int NONE = 0;
	/** A charged hit made sprinting: the knockback hit. */
	public static final int SPRINT = 1;
	/** A charged hit made falling, not sprinting. */
	public static final int CRIT = 2;
	/** Any other hit. */
	public static final int PLAIN = 3;

	/** My hit landed on them this tick: one of the kinds above, or {@link #NONE}. */
	public int iHit;
	/** Their hit landed on me this tick. */
	public int theyHit;
	/** My arm began a swing this tick (hit or miss). */
	public boolean iSwung;
	/** Their arm began a swing this tick (hit or miss). */
	public boolean theySwung;

	public void clear() {
		iHit = NONE;
		theyHit = NONE;
		iSwung = false;
		theySwung = false;
	}

	public void copyFrom(Events o) {
		iHit = o.iHit;
		theyHit = o.theyHit;
		iSwung = o.iSwung;
		theySwung = o.theySwung;
	}

	public void mirrorOf(Events o) {
		iHit = o.theyHit;
		theyHit = o.iHit;
		iSwung = o.theySwung;
		theySwung = o.iSwung;
	}

	/** The kind of a hit from how it was made, as the game decides it. */
	public static int kindOf(float charge, boolean sprinting, boolean crit) {
		if (crit) {
			return CRIT;
		}
		return charge > Body.CHARGED && sprinting ? SPRINT : PLAIN;
	}
}
