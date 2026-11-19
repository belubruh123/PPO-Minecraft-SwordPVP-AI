package dev.swordai.core.league;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;

import java.util.Arrays;

/**
 * Per-episode short-term history and recurrent GRU state for one fighter:
 * - last {@link #HIST_TICKS} ticks of own body keys (10 ticks = 0.5 s),
 * - last {@link #SEEN_FRAMES} ticks of seen opponent state (8 ticks = 0.4 s),
 * - recurrent GRU hidden state ({@link #GRU_HIDDEN} units) carrying arbitrary-horizon fight timing
 *   (stuns, combos, sword cooldowns, crit-back chains).
 */
public final class ObsMemory {
	public static final int HIST_TICKS = 10;
	public static final int HIST_PER = 9;
	public static final int HIST = HIST_TICKS * HIST_PER;

	public static final int SEEN_FRAMES = 8;
	public static final int SEEN_PER = 10;
	public static final int SEEN = SEEN_FRAMES * SEEN_PER;

	public static final int GRU_HIDDEN = 128;

	public final float[] hist = new float[HIST];
	public final float[] seen = new float[SEEN];
	public final float[] hidden = new float[GRU_HIDDEN];

	public void reset() {
		Arrays.fill(hist, 0f);
		Arrays.fill(seen, 0f);
		Arrays.fill(hidden, 0f);
	}

	/** Pushes this tick's opponent-view summary in front of the seen history before acting. */
	public void pushSeen(float[] vec) {
		System.arraycopy(seen, 0, seen, SEEN_PER, SEEN - SEEN_PER);
		int s = ObsSpec.VEC_STATS;
		int c = ObsSpec.VEC_SCALARS;
		seen[0] = vec[s + ObsSpec.ST_HEAD_VISIBLE];
		seen[1] = vec[s + ObsSpec.ST_HEAD_X];
		seen[2] = vec[s + ObsSpec.ST_HEAD_Y];
		seen[3] = vec[s + ObsSpec.ST_HEAD_AREA];
		seen[4] = vec[s + ObsSpec.ST_ANY_AREA];
		seen[5] = vec[c + ObsSpec.SC_REL_VEL_FORWARD];
		seen[6] = vec[c + ObsSpec.SC_REL_VEL_UP];
		seen[7] = vec[c + ObsSpec.SC_OPP_CHARGE];
		seen[8] = vec[c + ObsSpec.SC_TURNED_YAW];
		seen[9] = vec[c + ObsSpec.SC_TURNED_PITCH];
	}

	/** Pushes this tick's executed keys in front of the key history after acting. */
	public void pushAction(int[] action) {
		System.arraycopy(hist, 0, hist, HIST_PER, HIST - HIST_PER);
		Arrays.fill(hist, 0, HIST_PER, 0f);
		hist[action[ActionSpace.FORWARD]] = 1f;
		hist[3 + action[ActionSpace.STRAFE]] = 1f;
		hist[6] = action[ActionSpace.SPRINT];
		hist[7] = action[ActionSpace.JUMP];
		hist[8] = action[ActionSpace.ATTACK];
	}
}
