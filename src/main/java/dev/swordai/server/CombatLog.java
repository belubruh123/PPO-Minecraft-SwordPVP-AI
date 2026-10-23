package dev.swordai.server;

import dev.swordai.core.env.StepFacts;

/**
 * Collects combat events on the server thread until the client thread's next decision picks them
 * up. All access is synchronised; the amounts involved are a handful of numbers per tick.
 */
public final class CombatLog {
	/** Vanilla turns a sprinting swing into a knockback hit only above this charge. */
	private static final float KNOCKBACK_CHARGE = 0.9f;

	private static float pendingCharge;
	private static boolean pendingSprint;
	private static boolean pendingCrit;
	private static boolean pendingRising;
	private static boolean pendingSweep;
	private static boolean pendingBotCrit;

	private static int hitsDealt;
	private static float hitCharge;
	private static boolean sprintHit;
	private static boolean critDealt;
	private static boolean risingSprintHit;
	private static boolean sweepDealt;
	private static float damageDealt;
	private static int hitsTaken;
	private static int critsTaken;
	private static float damageTaken;
	private static boolean koDealt;
	private static boolean koTaken;

	private CombatLog() {
	}

	/**
	 * The agent has started an attack; remember how charged it was, whether it was sprinting,
	 * whether vanilla will make it a critical hit or a sweep, and whether it was in the air and still
	 * going up.
	 */
	public static synchronized void agentSwung(float charge, boolean sprinting, boolean crit, boolean rising, boolean sweep) {
		pendingCharge = charge;
		pendingSprint = sprinting;
		pendingCrit = crit;
		pendingRising = rising;
		pendingSweep = sweep;
	}

	/** The bot has started an attack. */
	public static synchronized void botSwung(boolean crit) {
		pendingBotCrit = crit;
	}

	public static synchronized void agentDamagedBot(float amount) {
		hitsDealt++;
		damageDealt += amount;
		hitCharge = pendingCharge;
		sprintHit |= pendingSprint && pendingCharge > KNOCKBACK_CHARGE;
		critDealt |= pendingCrit;
		risingSprintHit |= pendingRising && pendingSprint && pendingCharge > KNOCKBACK_CHARGE;
		sweepDealt |= pendingSweep;
		pendingCrit = false;
		pendingRising = false;
		pendingSweep = false;
	}

	public static synchronized void botDamagedAgent(float amount) {
		hitsTaken++;
		damageTaken += amount;
		if (pendingBotCrit) {
			critsTaken++;
			pendingBotCrit = false;
		}
	}

	public static synchronized void knockout(boolean botDown) {
		if (botDown) {
			koDealt = true;
		} else {
			koTaken = true;
		}
	}

	/** Hits the agent has taken since the log was last emptied; the log is left as it is. */
	public static synchronized int hitsTakenSoFar() {
		return hitsTaken;
	}

	/** Empties the log and returns how many hits the agent had taken. */
	public static synchronized int clearCountingTaken() {
		int taken = hitsTaken;
		clear();
		return taken;
	}

	/** Moves everything gathered so far into {@code facts} and starts over. */
	public static synchronized void drain(StepFacts facts) {
		facts.hitsDealt = hitsDealt;
		facts.hitCharge = hitCharge;
		facts.sprintHit = sprintHit;
		facts.critDealt = critDealt;
		// Whether the rise came from a jump of its own is for the client to say.
		facts.uppercut = risingSprintHit;
		facts.sweep = sweepDealt;
		facts.damageDealt = damageDealt;
		facts.hitsTaken = hitsTaken;
		facts.critsTaken = critsTaken;
		facts.damageTaken = damageTaken;
		facts.koDealt = koDealt;
		facts.koTaken = koTaken;
		clear();
	}

	public static synchronized void clear() {
		hitsDealt = 0;
		hitCharge = 0f;
		sprintHit = false;
		critDealt = false;
		risingSprintHit = false;
		sweepDealt = false;
		damageDealt = 0f;
		hitsTaken = 0;
		critsTaken = 0;
		damageTaken = 0f;
		koDealt = false;
		koTaken = false;
	}
}
