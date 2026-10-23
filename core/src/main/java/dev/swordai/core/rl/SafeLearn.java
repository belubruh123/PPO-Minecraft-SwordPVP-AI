package dev.swordai.core.rl;

import java.util.Locale;
import java.util.Properties;

/**
 * Learning from fights against people without putting the trained network at risk. Two networks
 * take turns, a fight each: the proven one (what was trained, or last promoted), which nothing
 * changes, and a copy that learns from its own fights. What each of them does to the opponent is
 * counted the way the user judges a fighter: damage dealt minus damage taken, a minute.
 *
 * <ul>
 * <li>The copy takes the proven one's place only once it has done better by a clear margin over
 * enough fights of each ({@link #PROMOTE_FIGHTS}, {@link #PROMOTE_MARGIN}).</li>
 * <li>Doing clearly worse ({@link #ROLLBACK_MARGIN} after {@link #EARLY_FIGHTS} each), or no better
 * after {@link #STALE_FIGHTS} each, it is thrown away and starts again from the proven one.</li>
 * </ul>
 *
 * <p>Why. Up to 0.13.3 fights against people were learned from by the network itself, and nine
 * updates from a minute of fighting or less undid what training had taught (early swings from one in
 * twenty to seven in ten). Here the worst a bad update can do is lose the copy a few fights; the
 * network that fights for the user in the other half of the fights stays what it was.
 *
 * <p>Plain counting; the session that fights decides when to act on a verdict.
 */
public final class SafeLearn {
	/** Ticks in a minute. */
	static final float MINUTE = 1200f;
	/** Fights of each before the copy can be thrown away for doing worse. */
	public static final int EARLY_FIGHTS = 3;
	/** By this much damage a minute (dealt minus taken), worse than the proven one. */
	public static final float ROLLBACK_MARGIN = 60f;
	/** Fights, and minutes of fighting, of each before the copy can take over. */
	public static final int PROMOTE_FIGHTS = 8;
	public static final float PROMOTE_MINUTES = 3f;
	/** By this much damage a minute, better than the proven one. */
	public static final float PROMOTE_MARGIN = 30f;
	/** Fights of each after which a copy that is not better is started again. */
	public static final int STALE_FIGHTS = 16;

	public enum Verdict { NONE, PROMOTE, ROLLBACK }

	/** Index 0: the proven network; 1: the copy that learns. */
	private final double[] dealt = new double[2];
	private final double[] taken = new double[2];
	private final long[] ticks = new long[2];
	private final int[] fights = new int[2];
	private boolean nextBase;
	public int promotions;
	public int rollbacks;

	/** Damage dealt and taken on one tick of a fight played by the one named. */
	public void tick(boolean base, float dealtNow, float takenNow) {
		int i = base ? 0 : 1;
		dealt[i] += dealtNow;
		taken[i] += takenNow;
		ticks[i]++;
	}

	/** A fight played by the one named is over: the other plays the next. */
	public void fightOver(boolean base) {
		fights[base ? 0 : 1]++;
		nextBase = !base;
	}

	/** Whether the proven network plays the next fight. */
	public boolean nextIsBase() {
		return nextBase;
	}

	/** Damage dealt minus taken a minute by the one named; 0 before it has fought. */
	public float net(boolean base) {
		int i = base ? 0 : 1;
		return ticks[i] == 0 ? 0f : (float) ((dealt[i] - taken[i]) / (ticks[i] / MINUTE));
	}

	public int fights(boolean base) {
		return fights[base ? 0 : 1];
	}

	public float minutes(boolean base) {
		return ticks[base ? 0 : 1] / MINUTE;
	}

	/** What the fights so far say. */
	public Verdict verdict() {
		int n = Math.min(fights[0], fights[1]);
		if (n < EARLY_FIGHTS || ticks[0] == 0 || ticks[1] == 0) {
			return Verdict.NONE;
		}
		float gap = net(false) - net(true);
		if (gap <= -ROLLBACK_MARGIN) {
			return Verdict.ROLLBACK;
		}
		if (n >= PROMOTE_FIGHTS && minutes(true) >= PROMOTE_MINUTES && minutes(false) >= PROMOTE_MINUTES && gap >= PROMOTE_MARGIN) {
			return Verdict.PROMOTE;
		}
		if (n >= STALE_FIGHTS && gap <= 0f) {
			return Verdict.ROLLBACK;
		}
		return Verdict.NONE;
	}

	/** A verdict was acted on: the count starts again, with the copy playing first. */
	public void restart() {
		for (int i = 0; i < 2; i++) {
			dealt[i] = 0;
			taken[i] = 0;
			ticks[i] = 0;
			fights[i] = 0;
		}
		nextBase = false;
	}

	/** For the chat: the two side by side. */
	public String line() {
		return String.format(Locale.ROOT, "proven network %+.0f damage a minute over %d fights, learning copy %+.0f over %d",
				net(true), fights[0], net(false), fights[1]);
	}

	public void save(Properties p) {
		p.setProperty("baseDealt", Double.toString(dealt[0]));
		p.setProperty("baseTaken", Double.toString(taken[0]));
		p.setProperty("baseTicks", Long.toString(ticks[0]));
		p.setProperty("baseFights", Integer.toString(fights[0]));
		p.setProperty("copyDealt", Double.toString(dealt[1]));
		p.setProperty("copyTaken", Double.toString(taken[1]));
		p.setProperty("copyTicks", Long.toString(ticks[1]));
		p.setProperty("copyFights", Integer.toString(fights[1]));
		p.setProperty("nextBase", Boolean.toString(nextBase));
		p.setProperty("promotions", Integer.toString(promotions));
		p.setProperty("rollbacks", Integer.toString(rollbacks));
	}

	public void load(Properties p) {
		dealt[0] = Double.parseDouble(p.getProperty("baseDealt", "0"));
		taken[0] = Double.parseDouble(p.getProperty("baseTaken", "0"));
		ticks[0] = Long.parseLong(p.getProperty("baseTicks", "0"));
		fights[0] = Integer.parseInt(p.getProperty("baseFights", "0"));
		dealt[1] = Double.parseDouble(p.getProperty("copyDealt", "0"));
		taken[1] = Double.parseDouble(p.getProperty("copyTaken", "0"));
		ticks[1] = Long.parseLong(p.getProperty("copyTicks", "0"));
		fights[1] = Integer.parseInt(p.getProperty("copyFights", "0"));
		nextBase = Boolean.parseBoolean(p.getProperty("nextBase", "false"));
		promotions = Integer.parseInt(p.getProperty("promotions", "0"));
		rollbacks = Integer.parseInt(p.getProperty("rollbacks", "0"));
	}
}
