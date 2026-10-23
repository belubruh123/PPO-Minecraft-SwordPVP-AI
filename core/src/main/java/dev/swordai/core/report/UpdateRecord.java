package dev.swordai.core.report;

import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.UpdateStats;

import java.util.Locale;

/** One row of training history: what the last rollout looked like and how the update went. */
public final class UpdateRecord {
	public static final String CSV_HEADER = "epoch_ms,total_steps,updates,stage,bot_skill,steps,reward_per_step,"
			+ "on_head,on_target,aim_error,in_range,click_accuracy,full_charge,hits_per_min,sprint_hits,"
			+ "combo,tap_rate,hit_ratio,win_rate,hits_dealt,hits_taken,policy_loss,value_loss,kl,clip_fraction,"
			+ "explained_variance,entropy_yaw,entropy_pitch,entropy_move,entropy_attack,update_ms,fps,stale_frames,capture_ms,"
			+ "hold_ticks,find_ticks,lost_rate,visible,late_frames,still,mix_strafe,mix_timing,spam_rate,recharge_calm,ready_on_target,crit_share,punish_rate,uppercut_share,sweep_share,break_rate,escape_rate,close_sprint,draw_rate,combos_taken,speed,headless,answer_rate,opponent,back_rate,out_rate,jumps_per_min,jump_crit_rate";
	private static final int COLUMNS = CSV_HEADER.split(",").length;
	/**
	 * Columns are only ever added at the end. A file from 0.1.3 or later has at least this many, and
	 * is still read: the ones it lacks stay at zero.
	 */
	private static final int COLUMNS_BEFORE = 40;

	/** True for the header of this version or of an earlier one whose history can still be read. */
	public static boolean readableHeader(String header) {
		int columns = header.split(",").length;
		return header.equals(CSV_HEADER) || (columns >= COLUMNS_BEFORE && CSV_HEADER.startsWith(header + ","));
	}

	public long epochMillis;
	public long totalSteps;
	public long updates;
	/** Zero-based stage index. */
	public int stage;
	public float botSkill;
	/** Steps of this rollout played on the current stage (the rest were rehearsal). */
	public long steps;
	public float rewardPerStep;
	public float onHead;
	public float onTarget;
	public float aimError;
	public float inRange;
	public float clickAccuracy;
	public float fullCharge;
	public float hitsPerMinute;
	public float sprintHits;
	public float combo;
	public float tapRate;
	public float hitRatio;
	public float winRate;
	public long hitsDealt;
	public long hitsTaken;
	public float policyLoss;
	public float valueLoss;
	public float kl;
	public float clipFraction;
	public float explainedVariance;
	public float entropyYaw;
	public float entropyPitch;
	/** Mean over the forward, strafe, sprint and jump heads. */
	public float entropyMove;
	public float entropyAttack;
	public long updateMillis;
	public float fps;
	/** Fraction of decisions made on a frame that had already been used. */
	public float staleFrames;
	public float captureMillis;
	/** Mean length of an unbroken stretch on the opponent, ticks. */
	public float holdTicks;
	/** Mean ticks to find the opponent again after losing sight of it. */
	public float findTicks;
	/** Share of look-arounds that never found the opponent. */
	public float lostRate;
	public float visible;
	/** Fraction of decisions made on a frame taken before the camera's last move. */
	public float lateFrames;
	/** Share of the time on the opponent that the crosshair rested there. */
	public float still;
	/** Share of the time with a strafe plan that it was followed, and share of swings held as planned. */
	public float mixStrafe;
	public float mixTiming;
	/** Share of swings made before the weapon had recovered to 60% charge. */
	public float spamRate;
	/** Share of the recharge time the camera did not move, and share of the ready time it was on the opponent. */
	public float rechargeCalm;
	public float readyOnTarget;
	/**
	 * Share of landed hits that were critical hits, and punish crits landed per chance of one (up to
	 * 0.12.3: per critical hit taken).
	 */
	public float critShare;
	public float punishRate;
	/** Share of landed hits that were uppercuts. */
	public float uppercutShare;
	/** Share of landed hits that were sweeps. */
	public float sweepShare;
	/** Share of the opponent's combos on the agent that it ended by hitting back. */
	public float breakRate;
	/** Share of the opponent's combos on the agent that it backed out of and then answered with a charged hit. */
	public float escapeRate;
	/** Share of the hits landed from very close that were sprint-knockback hits. */
	public float closeSprint;
	/** Share of the fights to the knockout that ran out of time instead. */
	public float drawRate;
	/** Times the opponent had a combo going on the agent (two hits in a row or more), combo drills included. */
	public long combosTaken;
	/** How many times its normal speed the world ran at while this was gathered. */
	public float speed = 1f;
	/** Gathered without the screen: the agent was a fake player with a drawn picture. */
	public boolean headless;
	/** Share of the hits it took that it answered with a swing within a second, hit or miss; combo drills included. */
	public float answerRate;
	/** The stage's own opponent: the scripted bot, or a copy of itself. */
	public static final int OPPONENT_STAGE = 0;
	/** A person, in {@code /swordai spar}. */
	public static final int OPPONENT_SPAR = 1;
	/** Another player, in {@code /swordai fight}. */
	public static final int OPPONENT_FIGHT = 2;
	/** Who these steps were fought against. Fights against people count for nothing in the curriculum. */
	public int opponent = OPPONENT_STAGE;
	/** Share of the ticks just after a hit of the opponent's combo that it held S; combo drills included. */
	public float backRate;
	/** Share of the hits of the opponent's combos after which no further hit followed; combo drills included. */
	public float outRate;
	/** Times a minute it left the ground by its own jump. */
	public float jumpsPerMinute;
	/** Share of its jumps that ended in a critical hit. */
	public float jumpCritRate;

	public static UpdateRecord of(long epochMillis, long totalSteps, long updates, int stage, float botSkill, Tally t, UpdateStats s) {
		UpdateRecord r = new UpdateRecord();
		r.epochMillis = epochMillis;
		r.totalSteps = totalSteps;
		r.updates = updates;
		r.stage = stage;
		r.botSkill = botSkill;
		r.steps = t.steps;
		r.rewardPerStep = t.rewardPerStep();
		r.onHead = t.onHead();
		r.onTarget = t.onTarget();
		r.aimError = t.aimError();
		r.inRange = t.inRange();
		r.clickAccuracy = t.clickAccuracy();
		r.fullCharge = t.fullCharge();
		r.hitsPerMinute = t.hitsPerMinute();
		r.sprintHits = t.sprintHitFraction();
		r.combo = t.meanCombo();
		r.tapRate = t.tapRate();
		r.hitRatio = t.hitRatio();
		r.winRate = t.winRate();
		r.hitsDealt = t.hitsDealt;
		r.hitsTaken = t.hitsTaken;
		r.policyLoss = s.policyLoss;
		r.valueLoss = s.valueLoss;
		r.kl = s.approxKl;
		r.clipFraction = s.clipFraction;
		r.explainedVariance = s.explainedVariance;
		r.entropyYaw = s.entropy[ActionSpace.YAW];
		r.entropyPitch = s.entropy[ActionSpace.PITCH];
		r.entropyMove = (s.entropy[ActionSpace.FORWARD] + s.entropy[ActionSpace.STRAFE]
				+ s.entropy[ActionSpace.SPRINT] + s.entropy[ActionSpace.JUMP]) / 4f;
		r.entropyAttack = s.entropy[ActionSpace.ATTACK];
		r.updateMillis = s.millis;
		r.holdTicks = t.meanHold();
		r.findTicks = t.meanReacquire();
		r.lostRate = t.lostRate();
		r.visible = t.visible();
		r.still = t.still();
		r.mixStrafe = t.mixStrafe();
		r.mixTiming = t.mixTiming();
		r.spamRate = t.spamRate();
		r.rechargeCalm = t.rechargeCalm();
		r.readyOnTarget = t.readyOnTarget();
		r.critShare = t.critShare();
		r.punishRate = t.punishRate();
		r.uppercutShare = t.uppercutShare();
		r.sweepShare = t.sweepShare();
		r.breakRate = t.breakRate();
		r.escapeRate = t.escapeRate();
		r.closeSprint = t.closeSprint();
		r.drawRate = t.drawRate();
		r.combosTaken = t.combosTaken;
		r.answerRate = t.answerRate();
		r.backRate = t.backRate();
		r.outRate = t.outRate();
		r.jumpsPerMinute = t.jumpsPerMinute();
		r.jumpCritRate = t.jumpCritRate();
		return r;
	}

	public String toCsv() {
		return String.format(Locale.ROOT,
				"%d,%d,%d,%d,%.2f,%d,%.4f,%.4f,%.4f,%.2f,%.4f,%.4f,%.4f,%.2f,%.4f,%.2f,%.4f,%.2f,%.4f,%d,%d,"
						+ "%.4f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.1f,%.4f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%.2f,%d,%.4f,%d,%.4f,%.4f,%.2f,%.4f",
				epochMillis, totalSteps, updates, stage, botSkill, steps, rewardPerStep,
				onHead, onTarget, aimError, inRange, clickAccuracy, fullCharge, hitsPerMinute, sprintHits,
				combo, tapRate, hitRatio, winRate, hitsDealt, hitsTaken, policyLoss, valueLoss, kl, clipFraction,
				explainedVariance, entropyYaw, entropyPitch, entropyMove, entropyAttack, updateMillis, fps,
				staleFrames, captureMillis, holdTicks, findTicks, lostRate, visible, lateFrames, still, mixStrafe, mixTiming, spamRate, rechargeCalm, readyOnTarget, critShare, punishRate, uppercutShare, sweepShare, breakRate, escapeRate, closeSprint, drawRate, combosTaken, speed, headless ? 1 : 0, answerRate, opponent, backRate, outRate, jumpsPerMinute, jumpCritRate);
	}

	/** Parses a line written by {@link #toCsv()}; returns null for the header or anything malformed. */
	public static UpdateRecord fromCsv(String line) {
		String[] c = line.trim().split(",");
		if (c.length < COLUMNS_BEFORE || c.length > COLUMNS) {
			return null;
		}
		try {
			UpdateRecord r = new UpdateRecord();
			int i = 0;
			r.epochMillis = Long.parseLong(c[i++]);
			r.totalSteps = Long.parseLong(c[i++]);
			r.updates = Long.parseLong(c[i++]);
			r.stage = Integer.parseInt(c[i++]);
			r.botSkill = Float.parseFloat(c[i++]);
			r.steps = Long.parseLong(c[i++]);
			r.rewardPerStep = Float.parseFloat(c[i++]);
			r.onHead = Float.parseFloat(c[i++]);
			r.onTarget = Float.parseFloat(c[i++]);
			r.aimError = Float.parseFloat(c[i++]);
			r.inRange = Float.parseFloat(c[i++]);
			r.clickAccuracy = Float.parseFloat(c[i++]);
			r.fullCharge = Float.parseFloat(c[i++]);
			r.hitsPerMinute = Float.parseFloat(c[i++]);
			r.sprintHits = Float.parseFloat(c[i++]);
			r.combo = Float.parseFloat(c[i++]);
			r.tapRate = Float.parseFloat(c[i++]);
			r.hitRatio = Float.parseFloat(c[i++]);
			r.winRate = Float.parseFloat(c[i++]);
			r.hitsDealt = Long.parseLong(c[i++]);
			r.hitsTaken = Long.parseLong(c[i++]);
			r.policyLoss = Float.parseFloat(c[i++]);
			r.valueLoss = Float.parseFloat(c[i++]);
			r.kl = Float.parseFloat(c[i++]);
			r.clipFraction = Float.parseFloat(c[i++]);
			r.explainedVariance = Float.parseFloat(c[i++]);
			r.entropyYaw = Float.parseFloat(c[i++]);
			r.entropyPitch = Float.parseFloat(c[i++]);
			r.entropyMove = Float.parseFloat(c[i++]);
			r.entropyAttack = Float.parseFloat(c[i++]);
			r.updateMillis = Long.parseLong(c[i++]);
			r.fps = Float.parseFloat(c[i++]);
			r.staleFrames = Float.parseFloat(c[i++]);
			r.captureMillis = Float.parseFloat(c[i++]);
			r.holdTicks = Float.parseFloat(c[i++]);
			r.findTicks = Float.parseFloat(c[i++]);
			r.lostRate = Float.parseFloat(c[i++]);
			r.visible = Float.parseFloat(c[i++]);
			r.lateFrames = Float.parseFloat(c[i++]);
			r.still = Float.parseFloat(c[i++]);
			if (i < c.length) {
				r.mixStrafe = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.mixTiming = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.spamRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.rechargeCalm = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.readyOnTarget = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.critShare = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.punishRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.uppercutShare = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.sweepShare = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.breakRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.escapeRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.closeSprint = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.drawRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.combosTaken = Long.parseLong(c[i++]);
			}
			if (i < c.length) {
				r.speed = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.headless = Integer.parseInt(c[i++]) != 0;
			}
			if (i < c.length) {
				r.answerRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.opponent = Integer.parseInt(c[i++]);
			}
			if (i < c.length) {
				r.backRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.outRate = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.jumpsPerMinute = Float.parseFloat(c[i++]);
			}
			if (i < c.length) {
				r.jumpCritRate = Float.parseFloat(c[i]);
			}
			return r;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
