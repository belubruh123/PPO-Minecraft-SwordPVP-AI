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
}
