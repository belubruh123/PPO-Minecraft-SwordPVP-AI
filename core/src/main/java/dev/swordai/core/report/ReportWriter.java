package dev.swordai.core.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the compact text summary the player pastes back for tuning. */
public final class ReportWriter {
	private static final int RECENT_ROWS = 10;
	private static final int OLDER_ROWS = 10;

	private ReportWriter() {
	}

	/**
	 * @param headerLines free-form context lines (versions, hardware, current stage, self-test result)
	 * @param history     every update so far, oldest first
	 * @param stageNames  stage names by index
	 * @param lastError   null when nothing went wrong
	 */
	public static String build(List<String> headerLines, List<UpdateRecord> history, List<String> stageNames, String lastError) {
		StringBuilder sb = new StringBuilder(4096);
		for (String line : headerLines) {
			sb.append(line).append('\n');
		}

		sb.append('\n').append("time per stage:\n");
		appendStageSummary(sb, history, stageNames);

		sb.append('\n');
		sb.append(" upd   steps st  rew/st tgt% head%  hold still seen% find lost% aimErr rng% clk% spam% full% hit/m spr% cls% swp% combo tap% mix% tim% calm% rdy% jmp/m  jc% crit% pun% upc% tkn ans% brk% esc% bck% out%  d:t  win% drw%   kl    ev  ent(y/p/m/a)      fps stale late   spd\n");
		// A rollout that happened to be all rehearsal of an earlier stage has no numbers for this one.
		List<UpdateRecord> scored = new ArrayList<>();
		for (UpdateRecord r : history) {
			if (r.steps > 0) {
				scored.add(r);
			}
		}
		for (UpdateRecord r : sample(scored)) {
			sb.append(String.format(Locale.ROOT,
					"%4d %7d %2d %7.3f %4.0f  %4.0f %5.1f  %4.0f  %4.0f %4.1f %4.0f %6.1f %4.0f %4.0f  %4.0f %4.0f  %5.1f %4.0f %4.0f %4.0f %5.1f %4.0f %4.0f %4.0f  %4.0f %4.0f %5.1f %4.0f  %4.0f %4.0f %4.0f %3d %4.0f %4.0f %4.0f %4.0f %4.0f %5.1f %4.0f %4.0f %6.3f %5.2f %.2f/%.2f/%.2f/%.2f %4.0f %4.1f%% %3.0f%% %5s\n",
					r.updates, r.totalSteps, r.stage + 1, r.rewardPerStep, r.onTarget * 100f, r.onHead * 100f,
					r.holdTicks, r.still * 100f, r.visible * 100f, r.findTicks, r.lostRate * 100f,
					r.aimError, r.inRange * 100f, r.clickAccuracy * 100f, r.spamRate * 100f, r.fullCharge * 100f, r.hitsPerMinute,
					r.sprintHits * 100f, r.closeSprint * 100f, r.sweepShare * 100f, r.combo, r.tapRate * 100f, r.mixStrafe * 100f, r.mixTiming * 100f,
					r.rechargeCalm * 100f, r.readyOnTarget * 100f, r.jumpsPerMinute, r.jumpCritRate * 100f, r.critShare * 100f, r.punishRate * 100f, r.uppercutShare * 100f, r.combosTaken, r.answerRate * 100f, r.breakRate * 100f, r.escapeRate * 100f, r.backRate * 100f, r.outRate * 100f,
					Math.min(r.hitRatio, 999f), r.winRate * 100f, r.drawRate * 100f,
					r.kl, r.explainedVariance, r.entropyYaw, r.entropyPitch, r.entropyMove, r.entropyAttack,
					r.fps, r.staleFrames * 100f, r.lateFrames * 100f,
					String.format(Locale.ROOT, "%s%.1f", mark(r), r.speed)));
		}
		sb.append("  (tgt = crosshair on the opponent; hold = ticks it stays on at a time; still = share of that time it rests without sliding;\n");
		sb.append("   aimErr = degrees off the opponent; find = ticks to see the opponent again; late = decisions on a frame from before the camera's last move;\n");
		sb.append("   clk = swings with the opponent under the crosshair (not scored); spam = swings before the weapon was back to 60% charge;\n");
		sb.append("   mix = strafed the way its random plan said (out to a side and back, or round the opponent), counted within 3.6 blocks (farther off it runs straight in);\n");
		sb.append("   (mix is not counted while it is getting out of a combo); tim = swings not made before the plan allowed (no plan holds a swing back since 0.6.1: reads 100);\n");
		sb.append("   calm = camera left alone while the sword recharges; rdy = on the opponent while the sword is ready;\n");
		sb.append("   jmp/m = jumps a minute (0 before 0.8.0; about 90 a minute is one after every swing);\n");
		sb.append("   jc = share of its jumps that ended in a critical hit; the rest came to nothing (0 before 0.13.0);\n");
		sb.append("   crit = share of its hits that were critical hits (falling, sprint off, sword charged);\n");
		sb.append("   pun = punish crits landed per chance of one: a hit that knocked it up and left the opponent in its reach as it came down\n");
		sb.append("   (before 0.13.0: per critical hit it took, whether or not anything could be done about it);\n");
		sb.append("   upc = share of its hits that were uppercuts; swp = share that were sweeps: slow hits with no sprint behind them;\n");
		sb.append("   tkn = times the opponent had a combo going on it (two hits in a row or more), most of them in the combo drills,\n");
		sb.append("   where a copy of itself starts on top of it with the first hits already landed; brk = share of those it ended by hitting back;\n");
		sb.append("   ans = share of the hits it took that it answered with a swing within a second, whether that swing landed or not;\n");
		sb.append("   esc = share of the combos on it (two hits in a row or more; four or more before 0.6.1) that it backed out of and then answered with a charged hit;\n");
		sb.append("   bck = share of the 12 ticks after each hit of a combo on it, with the opponent within 6 blocks, that it held S (0 before 0.7.0);\n");
		sb.append("   out = share of the hits of combos on it after which it got out: no further hit followed, by backing away or by hitting back first;\n");
		sb.append("   cls = share of its hits from very close (under 2.4 blocks) that were sprint hits;\n");
		sb.append("   win = fights won out of those that ended in a knockout (in self-play: against the newest copy); drw = fights that ran out of time;\n");
		sb.append("   only tkn, ans, brk, esc, bck and out include the combo drills: every other number is from the stage's own fights;\n");
		sb.append("   spd = how many times normal speed the game ran at; an h in front means that update was trained without the screen (headless),\n");
		sb.append("   where fps, stale and late say nothing about the AI's picture, which is drawn for it;\n");
		sb.append("   an s in front means that update was learned from /swordai spar against a person, an f from /swordai fight against another player:\n");
		sb.append("   those rows are not the stage's own fights and count for nothing towards passing it. In f rows the kind of each hit\n");
		sb.append("   (spr, crit, swp, upc) is what the game client made of its own swing, and a hit counts once the opponent is seen to flinch)\n");
		if (history.isEmpty()) {
			sb.append("  (no updates yet)\n");
		}

		sb.append('\n').append("last error: ").append(lastError == null ? "none" : lastError).append('\n');
		return sb.toString();
	}

	/** What goes in front of the speed: how the update's steps were gathered, when not in the stage's own fights on the screen. */
	private static String mark(UpdateRecord r) {
		if (r.opponent == UpdateRecord.OPPONENT_SPAR) {
			return "s";
		}
		if (r.opponent == UpdateRecord.OPPONENT_FIGHT) {
			return "f";
		}
		return r.headless ? "h" : "";
	}

	private static void appendStageSummary(StringBuilder sb, List<UpdateRecord> history, List<String> stageNames) {
		long[] steps = new long[stageNames.size()];
		for (UpdateRecord r : history) {
			if (r.stage >= 0 && r.stage < steps.length) {
				steps[r.stage] += r.steps;
			}
		}
		for (int i = 0; i < steps.length; i++) {
			if (steps[i] == 0) {
				continue;
			}
			// Twenty decisions a second.
			sb.append(String.format(Locale.ROOT, "  %d %-22s %,9d steps  (%s)\n", i + 1, stageNames.get(i), steps[i], duration(steps[i] / 20)));
		}
	}

	/** The newest rows in full, plus an even sample of the older ones so the whole curve is visible. */
	private static List<UpdateRecord> sample(List<UpdateRecord> history) {
		int n = history.size();
		if (n <= RECENT_ROWS + OLDER_ROWS) {
			return history;
		}
		List<UpdateRecord> out = new ArrayList<>(RECENT_ROWS + OLDER_ROWS);
		int older = n - RECENT_ROWS;
		for (int i = 0; i < OLDER_ROWS; i++) {
			out.add(history.get((int) ((long) i * older / OLDER_ROWS)));
		}
		out.addAll(history.subList(older, n));
		return out;
	}

	public static String duration(long seconds) {
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		if (h > 0) {
			return String.format(Locale.ROOT, "%dh %02dm", h, m);
		}
		if (m > 0) {
			return String.format(Locale.ROOT, "%dm %02ds", m, s);
		}
		return s + "s";
	}
}
