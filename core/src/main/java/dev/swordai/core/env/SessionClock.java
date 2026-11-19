package dev.swordai.core.env;

/** Slow-changing values of a training session that the observation needs; owned and updated by the session. */
public final class SessionClock {
	public int ticksSinceHitDealt = 1000;
	public int ticksSinceHitTaken = 1000;
	/** The opponent's combo on the agent, as the agent is shown it. */
	public final TakenStreak takenStreak = new TakenStreak();
	public float episodeFraction;
	public float botSkill;
	public int combo;
	public boolean botAttacks;

	public void resetEpisode() {
		ticksSinceHitDealt = 1000;
		ticksSinceHitTaken = 1000;
		takenStreak.reset();
		episodeFraction = 0f;
		combo = 0;
	}

	public void reset() {
		resetEpisode();
	}

	public void tick() {
		if (ticksSinceHitDealt < 1000) ticksSinceHitDealt++;
		if (ticksSinceHitTaken < 1000) ticksSinceHitTaken++;
		takenStreak.tick(0, 0);
	}

	public void hitDealt() {
		ticksSinceHitDealt = 0;
		combo++;
		takenStreak.tick(1, 0);
	}

	public void hitTaken() {
		ticksSinceHitTaken = 0;
		combo = 0;
		takenStreak.tick(0, 1);
	}
}
