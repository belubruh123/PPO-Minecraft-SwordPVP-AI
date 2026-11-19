package dev.swordai.core.env;

/** Training-only ground truth for one tick (geometry for critic + server combat events). */
public final class StepFacts {
	public float yawErrDeg;
	public float pitchErrDeg;
	public float missDeg;
	public float yawMissDeg;
	public boolean yawOnHead;
	public boolean onHead;
	public boolean onBody;
	public boolean enemyVisible;
	public float reachDist;
	public boolean falling;

	public int hitsDealt;
	public float hitCharge;
	public boolean sprintHit;
	public boolean critDealt;
	public float damageDealt;
	public int hitsTaken;
	public int critsTaken;
	public float damageTaken;
	public boolean koDealt;
	public boolean koTaken;
}
