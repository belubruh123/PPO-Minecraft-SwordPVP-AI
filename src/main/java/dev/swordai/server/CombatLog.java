package dev.swordai.server;

import dev.swordai.core.env.StepFacts;

/** Synchronised bridge passing server-thread combat events to the client thread each tick. */
public final class CombatLog {
	private static final float KNOCKBACK_CHARGE = 0.9f;
	private static float pendingCharge;
	private static boolean pendingSprint, pendingCrit, pendingBotCrit;
	private static int hitsDealt, hitsTaken, critsTaken;
	private static float hitCharge, damageDealt, damageTaken;
	private static boolean sprintHit, critDealt, koDealt, koTaken;

	private CombatLog() {
	}

	public static synchronized void agentSwung(float charge, boolean sprinting, boolean crit, boolean rising, boolean sweep) {
		pendingCharge = charge;
		pendingSprint = sprinting;
		pendingCrit = crit;
	}

	public static synchronized void botSwung(boolean crit, boolean sprintHit) {
		pendingBotCrit = crit;
	}

	public static synchronized void agentDamagedBot(float amount) {
		hitsDealt++;
		damageDealt += amount;
		hitCharge = pendingCharge;
		sprintHit |= pendingSprint && pendingCharge > KNOCKBACK_CHARGE;
		critDealt |= pendingCrit;
		pendingCrit = false;
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
		if (botDown) koDealt = true;
		else koTaken = true;
	}

	public static synchronized void drain(StepFacts facts) {
		facts.hitsDealt = hitsDealt;
		facts.hitCharge = hitCharge;
		facts.sprintHit = sprintHit;
		facts.critDealt = critDealt;
		facts.damageDealt = damageDealt;
		facts.hitsTaken = hitsTaken;
		facts.critsTaken = critsTaken;
		facts.damageTaken = damageTaken;
		facts.koDealt = koDealt;
		facts.koTaken = koTaken;
		clear();
	}

	public static synchronized void clear() {
		hitsDealt = hitsTaken = critsTaken = 0;
		hitCharge = damageDealt = damageTaken = 0f;
		sprintHit = critDealt = koDealt = koTaken = false;
	}
}
