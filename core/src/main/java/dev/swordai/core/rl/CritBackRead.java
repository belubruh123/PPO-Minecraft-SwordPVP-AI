package dev.swordai.core.rl;

/**
 * Run 101 (agent 43): the opponent read for crit spammers. Against one that crits back after the AI's punish crits (the user's 7 lost
 * rounds of 14 Nov: 26 of 33 punish crits were hit back within {@link #WITHIN} ticks, 17 by a double crit), the rules do not force the
 * punish crit any more (Fighter#critBackRead): the document's C1, "When you expect a double crit, don't go for the punish crit.
 * Sprint-hit them", is then the network's to choose, with T2 and T1c, by the outcome. Everywhere else the rules' punish crit stays.
 * Per fight: {@link #reset()} at its start; {@link #tick} every tick.
 */
public final class CritBackRead {
	/** Ticks after its punish crit within which their crit counts as a crit back. */
	public static final int WITHIN = 12;
	/** Crit backs in the fight that switch the read on (the user: 2+ times). */
	public static final int NEEDED = 2;
	/**
	 * Master switch of the read: SchoolSession (training, checks) sets it from its own constant; the user's real game (FightSession)
	 * uses {@link #REAL_GAME}, which is the same as training here (on).
	 */
	public static final boolean REAL_GAME = true;

	private int sincePunish = 1000;
	private boolean counted = true;
	private int backs;
	private int punishes;

	public void reset() {
		sincePunish = 1000;
		counted = true;
		backs = 0;
		punishes = 0;
	}

	/** Its punish crit landed (a session that sees it later in the tick than the crits taken calls this instead of the flag). */
	public void punish() {
		sincePunish = 0;
		counted = false;
		punishes++;
	}

	/** One tick: punishCrit = its punish crit landed this tick; critTaken = a crit of theirs landed on it this tick. */
	public void tick(boolean punishCrit, boolean critTaken) {
		if (sincePunish < 1000) {
			sincePunish++;
		}
		if (punishCrit) {
			sincePunish = 0;
			counted = false;
			punishes++;
		}
		if (!counted && critTaken && sincePunish <= WITHIN) {
			counted = true;
			backs++;
		} else if (!counted && sincePunish > WITHIN) {
			counted = true;
		}
	}

	public int backs() {
		return backs;
	}

	public int punishes() {
		return punishes;
	}

	/** Whether the punish crit is left to the network against this opponent now. */
	public boolean on() {
		return backs >= NEEDED;
	}
}
