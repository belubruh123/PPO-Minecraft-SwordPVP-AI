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
 * <p>Which way to strafe is the teacher's coin toss, so any strafe counts as its strafe. And where the file lets a
 * technique stop or hold S alike (T6: "stop, S or strafe"), the teacher tosses for that too: there either counts as
 * the other. Only where S is the technique itself (C2, C3, C4: holding S is how they get out) must it be S, on time.
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
	/** Why moments fail: per technique and head, ticks it taught that head and ticks the network pressed something else; and its jumps, swings, S not made in time. */
	public final int[][] taughtTicks = new int[Technique.COUNT][ActionSpace.HEADS];
	public final int[][] offTicks = new int[Technique.COUNT][ActionSpace.HEADS];
	public final int[][] missedKeys = new int[Technique.COUNT][3];

	/** What the stages are told of each moment as it closes; may be null. */
	public interface Listener {
		void moment(int technique, boolean done, boolean paid);
	}

	private final Listener listener;
	/**
	 * For the technique reward: on the last tick, the share of the heads the teacher taught that the network pressed
	 * as it would have (-1 off a technique's moment, and on the ordinary fight's); and the moments done since
	 * {@link #takeDone} last asked, per technique.
	 */
	public float lastMatch = -1f;
	public int lastTechnique = -1;
	private final int[] doneSince = new int[Technique.COUNT];
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
	/** Keys the closed moment asked for on its last tick: it still has this tick to press them. */
	private int closingOwed;

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
		for (int[] row : taughtTicks) {
			java.util.Arrays.fill(row, 0);
		}
		for (int[] row : offTicks) {
			java.util.Arrays.fill(row, 0);
		}
		for (int[] row : missedKeys) {
			java.util.Arrays.fill(row, 0);
		}
	}

	private static final String[] HEAD_NAMES = {"yaw", "pitch", "W/S", "A/D", "sprint", "jump", "swing"};

	/** Per technique with moments: on which keys the network went its own way, and which keys it missed: " T7 off W/S 12% A/D 30%, missed swing 4;". */
	public String whyLine() {
		StringBuilder b = new StringBuilder();
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (moments[i] == 0) {
				continue;
			}
			b.append(' ').append(t.id).append(" off");
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if (taughtTicks[i][h] > 0) {
					b.append(' ').append(HEAD_NAMES[h]).append(' ').append(Math.round(100f * offTicks[i][h] / taughtTicks[i][h])).append('%');
				}
			}
			b.append(", missed jump ").append(missedKeys[i][0]).append(" swing ").append(missedKeys[i][1]).append(" S ").append(missedKeys[i][2]).append(';');
		}
		return b.toString();
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
		lastMatch = -1f;
		lastTechnique = -1;
		java.util.Arrays.fill(doneSince, 0);
	}

	/** The moments done of technique {@code t} since the last call for it; counts them as taken. */
	public int takeDone(int t) {
		int n = doneSince[t];
		doneSince[t] = 0;
		return n;
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
			if (closingOwed != 0) {
				int pressedNow = (action[ActionSpace.JUMP] == 1 ? K_JUMP : 0) | (action[ActionSpace.ATTACK] == 1 ? K_ATTACK : 0)
						| (action[ActionSpace.FORWARD] == 2 ? K_BACK : 0);
				if ((closingOwed & ~pressedNow) != 0) {
					closingDone = false;
				}
				closingOwed = 0;
			}
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
			lastMatch = -1f;
			lastTechnique = -1;
			return;
		}
		if (current == Technique.BASE.ordinal()) {
			// The ordinary fight: its keys count as off the technique moments, and it is done only without a single jump.
			outsideTicks++;
			outsideKeys[0] += jumped ? 1 : 0;
			outsideKeys[1] += swung ? 1 : 0;
			outsideKeys[2] += back ? 1 : 0;
			keyMissed |= jumped;
		} else {
			insideTicks++;
			insideKeys[0] += jumped ? 1 : 0;
			insideKeys[1] += swung ? 1 : 0;
			insideKeys[2] += back ? 1 : 0;
		}
		if (closing < 0) {
			dealt += dealtNow;
			taken += takenNow;
		}
		boolean all = true;
		int wanted = 0;
		int pressed = 0;
		int counted = 0;
		int same0 = 0;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if ((heads & (1 << h)) == 0 || !ActionSpace.unlocked(mask, h)) {
				continue;
			}
			int want = argmax(target, h);
			int got = action[h];
			boolean same = h == ActionSpace.STRAFE ? (want == 0) == (got == 0)
					: h == ActionSpace.FORWARD && !sNeeded(current) ? (want == 1) == (got == 1) : want == got;
			all &= same;
			counted++;
			same0 += same ? 1 : 0;
			taughtTicks[current][h]++;
			offTicks[current][h] += same ? 0 : 1;
			if (h == ActionSpace.JUMP) {
				wanted |= want == 1 ? K_JUMP : 0;
				pressed |= got == 1 ? K_JUMP : 0;
			} else if (h == ActionSpace.ATTACK) {
				wanted |= want == 1 ? K_ATTACK : 0;
				pressed |= got == 1 ? K_ATTACK : 0;
			} else if (h == ActionSpace.FORWARD && sNeeded(current)) {
				wanted |= want == 2 ? K_BACK : 0;
				pressed |= got == 2 ? K_BACK : 0;
			}
		}
		ticks++;
		matched += all ? 1 : 0;
		boolean special = current != Technique.BASE.ordinal();
		lastMatch = special && counted > 0 ? same0 / (float) counted : -1f;
		lastTechnique = special ? current : -1;
		// A key owed from last tick and still not pressed is missed; a key wanted now may still come next tick.
		int missed = owedKeys & ~pressed;
		if (missed != 0) {
			keyMissed = true;
			missedKeys[current][0] += (missed & K_JUMP) != 0 ? 1 : 0;
			missedKeys[current][1] += (missed & K_ATTACK) != 0 ? 1 : 0;
			missedKeys[current][2] += (missed & K_BACK) != 0 ? 1 : 0;
		}
		owedKeys = wanted & ~pressed;
	}

	private void close() {
		boolean ok = ticks > 0 && matched >= MATCH * ticks && !keyMissed;
		closing = current;
		closingDone = ok;
		// A key asked for on the moment's last tick may still come on the next.
		closingOwed = owedKeys;
		closingLeft = AFTER;
		current = -1;
	}

	private void finish() {
		int t = closing;
		closing = -1;
		boolean gain = closingDone && dealt - taken >= 0f;
		moments[t]++;
		done[t] += closingDone ? 1 : 0;
		doneSince[t] += closingDone ? 1 : 0;
		paid[t] += gain ? 1 : 0;
		if (listener != null) {
			listener.moment(t, closingDone, gain);
		}
	}

	/** The techniques where holding S is the technique itself, not one way among others of not going in. */
	static boolean sNeeded(int technique) {
		return technique == Technique.C2.ordinal() || technique == Technique.C3.ordinal() || technique == Technique.C4.ordinal();
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
