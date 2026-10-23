package dev.swordai.core.school;

import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;

/**
 * Whether the network does a technique when its moment comes: one count per moment, from the tick its
 * teacher starts on it to the tick the teacher has moved on. A moment is <b>done</b> when, on its ticks,
 * the network pressed what the teacher would have on at least {@link #MATCH} of them, and on every tick
 * that makes the technique what it is (a jump, a swing, holding S) pressed the same within a tick. It
 * <b>paid</b> when it was done and the damage traded over it and the {@link #AFTER} ticks after was not a
 * loss.
 *
 * <p>Which way to strafe is the teacher's coin toss, so any strafe counts as its strafe.
 */
public final class Performance {
	/** Share of a moment's ticks on which the keys have to be the teacher's. */
	static final float MATCH = 0.7f;
	/** Ticks after a moment whose damage still counts for whether it paid. */
	static final int AFTER = 10;

	/** Per technique: moments, done, paid, since {@link #clear}. */
	public final int[] moments = new int[Technique.COUNT];
	public final int[] done = new int[Technique.COUNT];
	public final int[] paid = new int[Technique.COUNT];
	/**
	 * Against faking: ticks with no technique's moment on, and the jumps, swings and S held on them; the same on the
	 * ticks of a moment. A network that jumps all the time matches every jump a teacher asks for, and shows here.
	 */
	public long outsideTicks;
	public long insideTicks;
	public final long[] outsideKeys = new long[3];
	public final long[] insideKeys = new long[3];

	/** What the stages are told of each moment as it closes; may be null. */
	public interface Listener {
		void moment(int technique, boolean done, boolean paid);
	}

	private final Listener listener;
	private int current = -1;
	private int ticks;
	private int matched;
	private boolean keyMissed;
	/** A key the teacher pressed last tick that the network has not (yet): it has this tick to press it. */
	private int owedKeys;
	private float dealt;
	private float taken;
	/** The moment closed but still counting its damage: technique, whether done, ticks left. */
	private int closing = -1;
	private boolean closingDone;
	private int closingLeft;

	public Performance(Listener listener) {
		this.listener = listener;
	}

	public void clear() {
		java.util.Arrays.fill(moments, 0);
		java.util.Arrays.fill(done, 0);
		java.util.Arrays.fill(paid, 0);
		outsideTicks = 0;
		insideTicks = 0;
		java.util.Arrays.fill(outsideKeys, 0);
		java.util.Arrays.fill(insideKeys, 0);
	}

	/** Jumps, swings and ticks of S a minute, off the moments and on them: "jump 12/40 swing 50/70 S 30/90". */
	public String keysLine() {
		String[] names = {"jump", "swing", "S"};
		StringBuilder b = new StringBuilder();
		for (int k = 0; k < 3; k++) {
			b.append(k == 0 ? "" : " ").append(names[k]).append(' ')
					.append(Math.round(outsideKeys[k] * 1200.0 / Math.max(1, outsideTicks))).append('/')
					.append(Math.round(insideKeys[k] * 1200.0 / Math.max(1, insideTicks)));
		}
		return b.toString();
	}

	/** A new fight: what was open is dropped (a fight cut off says nothing). */
	public void reset() {
		current = -1;
		closing = -1;
	}

	private static final int K_JUMP = 1;
	private static final int K_ATTACK = 2;
	private static final int K_BACK = 4;

	/**
	 * One decision.
	 *
	 * @param technique the teacher's technique on this tick ({@link Technique} ordinal) where it teaches, else -1
	 * @param heads     the heads it taught (bits)
	 * @param target    its target over the heads' choices
	 * @param action    what the network chose
	 * @param mask      the heads the network could use (a swing before the sword is back cannot be made)
	 * @param dealtNow  damage dealt and taken since the last decision
	 */
	public void tick(int technique, int heads, float[] target, int[] action, int mask, float dealtNow, float takenNow) {
		if (closing >= 0) {
			dealt += dealtNow;
			taken += takenNow;
			if (--closingLeft <= 0) {
				finish();
			}
		}
		if (technique != current) {
			if (current >= 0) {
				close();
			}
			current = technique;
			if (technique >= 0) {
				if (closing >= 0) {
					finish();
				}
				ticks = 0;
				matched = 0;
				keyMissed = false;
				owedKeys = 0;
				dealt = 0f;
				taken = 0f;
			}
		}
		boolean jumped = action[ActionSpace.JUMP] == 1;
		boolean swung = action[ActionSpace.ATTACK] == 1;
		boolean back = action[ActionSpace.FORWARD] == 2;
		if (current < 0) {
			outsideTicks++;
			outsideKeys[0] += jumped ? 1 : 0;
			outsideKeys[1] += swung ? 1 : 0;
			outsideKeys[2] += back ? 1 : 0;
			return;
		}
		insideTicks++;
		insideKeys[0] += jumped ? 1 : 0;
		insideKeys[1] += swung ? 1 : 0;
		insideKeys[2] += back ? 1 : 0;
		if (closing < 0) {
			dealt += dealtNow;
			taken += takenNow;
		}
		boolean all = true;
		int wanted = 0;
		int pressed = 0;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if ((heads & (1 << h)) == 0 || !ActionSpace.unlocked(mask, h)) {
				continue;
			}
			int want = argmax(target, h);
			int got = action[h];
			boolean same = h == ActionSpace.STRAFE ? (want == 0) == (got == 0) : want == got;
			all &= same;
			if (h == ActionSpace.JUMP) {
				wanted |= want == 1 ? K_JUMP : 0;
				pressed |= got == 1 ? K_JUMP : 0;
			} else if (h == ActionSpace.ATTACK) {
				wanted |= want == 1 ? K_ATTACK : 0;
				pressed |= got == 1 ? K_ATTACK : 0;
			} else if (h == ActionSpace.FORWARD) {
				wanted |= want == 2 ? K_BACK : 0;
				pressed |= got == 2 ? K_BACK : 0;
			}
		}
		ticks++;
		matched += all ? 1 : 0;
		// A key owed from last tick and still not pressed is missed; a key wanted now may still come next tick.
		if ((owedKeys & ~pressed) != 0) {
			keyMissed = true;
		}
		owedKeys = wanted & ~pressed;
	}

	private void close() {
		if (owedKeys != 0) {
			keyMissed = true;
		}
		boolean ok = ticks > 0 && matched >= MATCH * ticks && !keyMissed;
		closing = current;
		closingDone = ok;
		closingLeft = AFTER;
		current = -1;
	}

	private void finish() {
		int t = closing;
		closing = -1;
		boolean gain = closingDone && dealt - taken >= 0f;
		moments[t]++;
		done[t] += closingDone ? 1 : 0;
		paid[t] += gain ? 1 : 0;
		if (listener != null) {
			listener.moment(t, closingDone, gain);
		}
	}

	private static int argmax(float[] target, int h) {
		int off = ActionSpace.OFFSETS[h];
		int best = 0;
		for (int k = 1; k < ActionSpace.SIZES[h]; k++) {
			if (target[off + k] > target[off + best]) {
				best = k;
			}
		}
		return best;
	}
}
