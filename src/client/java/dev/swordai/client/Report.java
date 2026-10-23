package dev.swordai.client;

import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.Curriculum;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.report.ReportWriter;
import dev.swordai.core.report.UpdateRecord;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Assembles the text behind {@code /swordai report}: enough to tune the training from, short enough to paste. */
final class Report {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private Report() {
	}

	/**
	 * @param session       the training session of this launch, or null to report from what is on disk
	 * @param other         what else is running instead (a fight or sparring), or null
	 * @param lastSelfTest  result of the most recent self-test this launch, or null
	 */
	static String build(Minecraft mc, Trainer session, String other, String lastSelfTest) {
		Config cfg;
		Curriculum curriculum;
		List<UpdateRecord> history;
		if (session != null) {
			cfg = session.config();
			curriculum = session.curriculum();
			history = session.history();
		} else {
			cfg = SwordFiles.loadConfig();
			curriculum = new Curriculum(cfg.stages);
			if (Files.exists(SwordFiles.curriculum())) {
				try {
					curriculum.load(SwordFiles.curriculum());
				} catch (IOException e) {
					// Reported below through the header; the rest of the report is still useful.
				}
			}
			history = SwordFiles.loadHistory();
		}

		List<String> header = new ArrayList<>();
		header.add(String.format(Locale.ROOT, "SwordAI %s report, Minecraft %s, %s", SwordFiles.modVersion(),
				SharedConstants.getCurrentVersion().name(), LocalDateTime.now().format(TIME)));
		header.add(String.format(Locale.ROOT, "env: Java %s, %d CPU threads (%d for training), window %dx%d, %d fps",
				System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), cfg.resolvedTrainerThreads(),
				mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getFps()));
		header.add(String.format(Locale.ROOT, "fov: screen %d while it plays, AI picture %d", cfg.fov, Math.min(cfg.visionFov, cfg.fov)));
		header.add(other != null ? "state: " + other
				: session == null || session.stopped() ? "state: not running" : "state: " + session.describe());

		StageSpec stage = curriculum.current();
		String blocker = curriculum.promotionBlocker();
		header.add(String.format(Locale.ROOT, "stage %d/%d \"%s\": %,d steps here, next: %s", curriculum.stageIndex() + 1,
				curriculum.stageCount(), stage.name, curriculum.stepsInStage(), blocker == null ? "ready to promote" : blocker));

		if (curriculum.fixedCopy()) {
			header.add("duel: the opponent is a saved copy of itself from when the duel began");
		} else if (curriculum.selfPlay()) {
			header.add(String.format(Locale.ROOT, "self-play: generation %d, %d won / %d lost against the current copy, %d won / %d lost against the %d before it",
					curriculum.generation(), curriculum.selfWins(), curriculum.selfLosses(), curriculum.oldWins(), curriculum.oldLosses(),
					SwordFiles.olderChampions().size()));
		}
		String pace = session == null ? null : session.paceLine();
		if (pace != null) {
			header.add(pace);
		}
		if (stage.drillShare > 0f) {
			// Whether they are waiting can only be said while training runs: it goes by the recent fights.
			String waiting = session == null || !session.stageFights() ? null : curriculum.drillBlocker();
			header.add(waiting != null
					? "combo drills: not yet, they start once it lands combos of its own against the stage's opponent (" + waiting + ")"
					: String.format(Locale.ROOT, "combo drills: about %.0f%% of this stage's fights are short ones (%d s) against a saved copy of itself that starts on top of it "
							+ "and lands its first %d to %d hits before the agent may move; they count in tkn, ans, brk, esc, bck and out only",
							stage.drillShare * 100f, stage.drillTicks / 20, Math.min(stage.drillMinHits, stage.drillHits), stage.drillHits));
		}
		String jumpHelp = jumpLine(curriculum);
		if (jumpHelp != null) {
			header.add(jumpHelp);
		}
		// The crit drills are not in the table below: what they have come to is said here.
		List<String> critLines = session != null ? session.critLines() : List.of();
		if (critLines.isEmpty() && stage.critDrillShare > 0f) {
			// Not training now (stopped, or a fight against a person): what was written down when it last was.
			critLines = keptCritLines();
			if (critLines.isEmpty() && curriculum.critDrillGoal() != null) {
				critLines = List.of("now " + curriculum.critDrillGoal());
			}
		}
		header.addAll(critLines);
		String backHelp = backLine(cfg, curriculum);
		if (backHelp != null) {
			header.add(backHelp);
		}
		String rules = rulesLine(cfg, curriculum);
		if (rules != null) {
			header.add(rules);
		}
		if (stage.hitLagTicks > 0 || stage.knockbackMin > 0f) {
			header.add(String.format(Locale.ROOT, "the opponent's knockback in training: each fight draws its own. A hit knocks it back 0 to %d ticks late%s (%,d knockbacks held back so far)",
					stage.hitLagTicks,
					stage.knockbackMin > 0f && stage.knockbackMin < 1f
							? String.format(Locale.ROOT, ", and in every other fight only %.0f%% to 100%% as far", stage.knockbackMin * 100f) : "",
					dev.swordai.server.KnockbackLag.held()));
		}
		if (stage.lagTicks > 0 || stage.seenLagTicks > 0) {
			header.add(String.format(Locale.ROOT, "lag in training: each fight of this stage has its own. The AI sees its opponent 0 to %d ticks (%d ms) late%s; "
							+ "the scripted bot and the copies see the AI 0 to %d ticks late. "
							+ "On screen, %,d of the opponent's moves have been held back since the game started",
					stage.seenLagTicks, stage.seenLagTicks * 50,
					stage.lagStutter > 0f ? String.format(Locale.ROOT, ", and in every other fight in fits and starts (nothing new for %d to %d ticks, then all at once)",
							StageSpec.LAG_STALL_MIN_TICKS, StageSpec.LAG_STALL_MAX_TICKS) : "",
					stage.lagTicks, SeenLag.heldBack()));
		}
		long totalSteps = history.isEmpty() ? 0 : history.get(history.size() - 1).totalSteps;
		long updates = history.isEmpty() ? 0 : history.get(history.size() - 1).updates;
		header.add(String.format(Locale.ROOT, "total: %,d steps (%s of play), %d updates, bot skill %.0f%%", totalSteps,
				ReportWriter.duration(totalSteps / 20), updates, curriculum.botSkill() * 100f));

		long sparSteps = 0;
		long fightSteps = 0;
		for (UpdateRecord r : history) {
			if (r.opponent == UpdateRecord.OPPONENT_SPAR) {
				sparSteps += r.steps;
			} else if (r.opponent == UpdateRecord.OPPONENT_FIGHT) {
				fightSteps += r.steps;
			}
		}
		header.add(String.format(Locale.ROOT, "learning from people: %s; so far %,d steps from sparring against you and %,d from fights against other players "
						+ "(rows marked s and f below; none of them count towards a stage)",
				cfg.learnFromPeople ? "on (/swordai fight and /swordai spar teach it)" : "off", sparSteps, fightSteps));

		if (session != null && !session.stopped() && session.stageFights()) {
			Tally t = curriculum.recent();
			header.add(String.format(Locale.ROOT, "recent window (%,d steps): on-target %.0f%%, steady %.0f%%, on-head %.0f%%, visible %.0f%%, aim err %.1f, "
							+ "in-range %.0f%%, click acc %.0f%%, spam clicks %.0f%%, full-charge %.0f%%, hits/min %.1f, sprint hits %.0f%%, combo %.1f, W-tap %.0f%%, dealt:taken %.1f",
					t.steps, t.onTarget() * 100f, t.still() * 100f, t.onHead() * 100f, t.visible() * 100f, t.aimError(), t.inRange() * 100f,
					t.clickAccuracy() * 100f, t.spamRate() * 100f, t.fullCharge() * 100f, t.hitsPerMinute(), t.sprintHitFraction() * 100f,
					t.meanCombo(), t.tapRate() * 100f, Math.min(t.hitRatio(), 999f)));
			header.add(String.format(Locale.ROOT, "actor learning rate %.1e, %,d parameters", session.learner().actorLr(), session.learner().actorParameters()));
		} else if (session != null && !session.stopped()) {
			// Against a person there is no window of stage fights; what the session says of itself stands in for it.
			List<String> lines = session.statusLines();
			for (int i = 1; i < lines.size(); i++) {
				header.add(lines.get(i));
			}
		}
		String selfTest = session != null && session.selfTestSummary() != null ? session.selfTestSummary() : lastSelfTest;
		header.add("self-test: " + (selfTest == null ? "not run this launch" : selfTest));
		String eyeCheck = SwordFiles.readEyeCheck();
		header.add(String.format(Locale.ROOT, "picture: frames are %.0f ms old when decided on; a fake player's picture vs the real one: %s",
				SwordFiles.readFrameAgeMillis(), eyeCheck == null ? "not compared yet (run /swordai train or play for a minute)" : eyeCheck));
		header.add(String.format(Locale.ROOT, "play, fight and spar pick their actions at temperature %.2f (1 = exactly as in training)", cfg.playTemperature));
		String lastFight = SwordFiles.readLastFight();
		if (lastFight != null) {
			header.add("last fight: " + lastFight);
		}

		List<String> stageNames = new ArrayList<>();
		for (int i = 0; i < curriculum.stageCount(); i++) {
			stageNames.add(curriculum.stage(i).name);
		}
		String error = session != null && session.lastError() != null ? session.lastError() : SwordFiles.readLastError();
		return ReportWriter.build(header, history, stageNames, error);
	}

	static void write(String text) throws IOException {
		Files.createDirectories(SwordFiles.dir());
		Files.writeString(SwordFiles.report(), text, StandardCharsets.UTF_8);
	}

	/** What the crit drills had come to when training last ran (see {@code Trainer.keepCritLines}); empty if nothing was written down. */
	private static List<String> keptCritLines() {
		try {
			return Files.exists(SwordFiles.critNotes()) ? Files.readAllLines(SwordFiles.critNotes(), StandardCharsets.UTF_8) : List.of();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** What the current stage is doing about the jump key right now, or null when it does nothing. */
	static String jumpLine(Curriculum curriculum) {
		StageSpec stage = curriculum.current();
		if (stage.jumpNudgeRate <= 0f) {
			return null;
		}
		float target = curriculum.jumpNudgeTarget();
		if (target <= 0f) {
			return String.format(Locale.ROOT, "jump help: over (it lasted the first %,d steps of the stage); from here it jumps of its own accord or not at all", stage.jumpNudgeSteps);
		}
		return String.format(Locale.ROOT, "jump help: on, strength %.1f of at most %.0f; it is being pushed to press jump on %.0f%% of the ticks "
						+ "three to five ticks after a swing (held until step %,d of the stage, gone by step %,d)",
				curriculum.jumpNudge(), 12f, target * 100f, stage.jumpNudgeHoldSteps, stage.jumpNudgeSteps);
	}

	/** Whether backing out of a combo is a rule in the stage it is in; see {@code Config.backRule}. */
	static boolean backRule(Config cfg, Curriculum curriculum) {
		return cfg.backRule && curriculum.current().backNudgeKeep > 0f;
	}

	/**
	 * The things that are done for the fighter in a fight whatever the network says, as the config
	 * has them; null before the stage that gives it the jump key, where none of them apply.
	 */
	static String rulesLine(Config cfg, Curriculum curriculum) {
		if (!curriculum.current().unlocked(dev.swordai.core.rl.ActionSpace.JUMP)) {
			return null;
		}
		StringBuilder on = new StringBuilder();
		add(on, cfg.answerRule, "a hit is answered with a swing where one reaches (answerRule)");
		add(on, cfg.answerRule && cfg.punishRule, "in the air that swing is kept for the way down, a punish crit (punishRule)");
		add(on, cfg.answerRule && cfg.aimRule, "after a hit the camera is brought onto the opponent (aimRule)");
		add(on, cfg.farRestRule, "on an opponent far away the camera is held steady (farRestRule)");
		add(on, cfg.chaseRule, "a running jump and a critical hit on the way down at an opponent that is backing off or running (chaseRule)");
		add(on, cfg.chaseRule && cfg.chaseInCombo, "also as the next hit of a combo of its own (chaseInCombo)");
		add(on, cfg.jumpResetChance > 0f && backRule(cfg, curriculum), String.format(Locale.ROOT,
				"hit a second time in a row, it jumps with %.0f%% of those hits, W held, and is thrown half as far (jumpResetChance)%s", cfg.jumpResetChance * 100f,
				(cfg.jumpResetCounterChance > 0f ? String.format(Locale.ROOT, "; and with %.0f%% of the hits that find its sword on the way back from a hit of its own (jumpResetCounterChance)",
						cfg.jumpResetCounterChance * 100f) : "")
						+ (cfg.jumpResetChasers ? "; with the first hit too against an opponent that has just followed a hit with a jump and a critical hit (jumpResetChasers)" : "")));
		add(on, cfg.meetJumpRule && cfg.answerRule, "an opponent jumping at it is run at and hit while it is still on its way up (meetJumpRule)");
		add(on, cfg.stepBackRule, "from an opponent that keeps answering its hits while its sword is on the way back it steps back after each hit until the sword is back (stepBackRule)");
		add(on, cfg.faceRule, "a jump and a critical hit at an opponent that its hits do not send away (faceRule)");
		add(on, cfg.uppercutChance > 0f, String.format(Locale.ROOT, "an uppercut after %.0f%% of the hits of a combo, from the third on (uppercutChance)", cfg.uppercutChance * 100f));
		return on.length() == 0 ? "rules in a fight: none are switched on in config.json" : "rules in a fight, in training and in every fight (config.json): " + on;
	}

	private static void add(StringBuilder to, boolean on, String what) {
		if (on) {
			to.append(to.length() == 0 ? "" : "; ").append(what);
		}
	}

	/** What the current stage is doing about S while it is comboed right now, or null when it does nothing. */
	static String backLine(Config cfg, Curriculum curriculum) {
		StageSpec stage = curriculum.current();
		if (stage.backNudgeRate <= 0f && stage.backNudgeKeep <= 0f) {
			return null;
		}
		if (backRule(cfg, curriculum)) {
			return "S rule: on; in the twelve ticks after each hit of a combo it is taking, from the second hit in a row, S is held for it"
					+ (cfg.jumpResetChance >= 1f ? " where that hit finds it in the air (on the ground it jumps with it instead)"
					: cfg.jumpResetChance > 0f ? " where it does not jump with that hit instead" : "")
					+ ", in training and in every fight (backRule in config.json)";
		}
		float target = curriculum.backNudgeTarget();
		if (stage.backNudgeKeep > 0f && (stage.backNudgeRate <= 0f || curriculum.stepsInStage() >= stage.backNudgeSteps)) {
			return String.format(Locale.ROOT, "S help: kept on, strength %.1f of at most %.0f; it is pushed to hold S on %.0f%% of the ticks right after a hit of a combo, "
							+ "in training and in every fight (left to itself it stops backing out of combos altogether)",
					curriculum.backNudge(), 12f, target * 100f);
		}
		if (target <= 0f) {
			return String.format(Locale.ROOT, "S help: over (it lasted the first %,d steps of the stage); from here it backs out of a combo of its own accord or not at all", stage.backNudgeSteps);
		}
		return String.format(Locale.ROOT, "S help: on, strength %.1f of at most %.0f; it is being pushed to hold S on %.0f%% of the ticks right after a hit of a combo "
						+ "(held until step %,d of the stage, gone by step %,d)",
				curriculum.backNudge(), 12f, target * 100f, stage.backNudgeHoldSteps, stage.backNudgeSteps);
	}
}
