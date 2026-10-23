package dev.swordai.core.school;

import dev.swordai.core.pvp.Habits;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsSpec;

/**
 * One observation of the technique school: what the network of the stages before saw ({@link Obs}: the
 * picture and its own body, kept exactly so the installed network can read it too, as a teacher), plus
 * the memory of this fight ({@link Habits}), plus what only the critic gets of the opponent (its
 * technique now, its style, which bot it is).
 */
public final class SchoolObs {
	/** The memory: what the opponent has been doing this fight, as {@link Habits#write} writes it. */
	public static final int MEM = Habits.SIZE;
	/** The critic's extra: the bot's technique now (one-hot), its chances, and which bot it is (one-hot). */
	public static final int OPP_TRUTH = Technique.COUNT + Technique.COUNT + Specialists.KINDS;
	public static final int CRITIC_IN = ObsSpec.VEC + ObsSpec.PRIV + MEM + OPP_TRUTH;

	public final Obs base = new Obs();
	public final float[] mem = new float[MEM];
	public final float[] oppTruth = new float[OPP_TRUTH];

	public void copyFrom(SchoolObs o) {
		base.copyFrom(o.base);
		System.arraycopy(o.mem, 0, mem, 0, MEM);
		System.arraycopy(o.oppTruth, 0, oppTruth, 0, OPP_TRUTH);
	}

	/** Writes the critic's truth of the opponent: its technique now ({@link Technique} ordinal, or -1), its chances, which bot. */
	public void setOpponent(int technique, float[] chances, int bot) {
		java.util.Arrays.fill(oppTruth, 0f);
		if (technique >= 0 && technique < Technique.COUNT) {
			oppTruth[technique] = 1f;
		}
		if (chances != null) {
			System.arraycopy(chances, 0, oppTruth, Technique.COUNT, Technique.COUNT);
		}
		if (bot >= 0 && bot < Specialists.KINDS) {
			oppTruth[2 * Technique.COUNT + bot] = 1f;
		}
	}
}
