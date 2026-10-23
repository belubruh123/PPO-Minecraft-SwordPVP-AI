p = r'D:\swordai\src\client\java\dev\swordai\client\Trainer.java'
s = open(p, encoding='utf-8').read()


def rep(old, new, cnt=1):
    global s
    assert s.count(old) == cnt, (old[:200], s.count(old))
    s = s.replace(old, new)


rep('''	/**
	 * The jump-in drill since the last update ({@link #KIND_JUMPIN}): fights, ones in which it hit the opponent first,
	 * critical hits back after that, crits it let land, hits back after them and how many of those were critical, and
	 * fights that ran out with no crit come.
	 */
	private final long[] jumpInDrill = new long[7];
	/** The episode tick of the first hit taken in a jump-in drill, or -1; and whether it had hit first. */
	private int jumpInTakenTick = -1;
	private boolean jumpInHitFirst;
''', '''	/** The clash jumps taken since the last update ({@code JumpIn.CLASH}). */
	private long clashJumps;
	private static final String[] JI_NAMES = {"fights", "crit for crit (same tick)", "trade (same tick, not both crits)",
			"hit first, hit back", "of those crit back", "hit first, no answer", "let land, punished", "of those punish crits",
			"let land, no answer", "taken twice", "nothing", "clash jumps"};
	private static final int JI_FIGHTS = 0;
	private static final int JI_CRIT_FOR_CRIT = 1;
	private static final int JI_TRADE = 2;
	private static final int JI_HIT_BACK = 3;
	private static final int JI_CRIT_BACK = 4;
	private static final int JI_CLEAN = 5;
	private static final int JI_PUNISHED = 6;
	private static final int JI_PUNISH_CRIT = 7;
	private static final int JI_UNANSWERED = 8;
	private static final int JI_TWICE = 9;
	private static final int JI_NOTHING = 10;
	private static final int JI_CLASH = 11;
	/** The jump-in drill since the last update ({@link #KIND_JUMPIN}), by how the exchange went; see {@link #JI_NAMES}. */
	private final long[] jumpInDrill = new long[JI_NAMES.length];
	private double jumpInDealtSum;
	private double jumpInTakenSum;
	/** In a jump-in drill: the episode ticks of the first hit taken and dealt (-1: none yet), whether each was a crit, and a second hit taken. */
	private int jumpInTakenTick = -1;
	private int jumpInDealtTick = -1;
	private boolean jumpInTakenCrit;
	private boolean jumpInDealtCrit;
	private boolean jumpInTakenTwice;
	/** One side's hit unanswered for this many ticks: the exchange is over. */
	private static final int JUMPIN_SETTLE_TICKS = 15;
''')
rep('''			"jump drill", "punish drill", "free drill", "0.14.0", "trader"};''',
    '''			"jump drill", "punish drill", "free drill", "0.14.0", "trader", "jump-in drill"};''')
rep('''	final Tally outsideTally = new Tally();
''', '''	final Tally outsideTally = new Tally();
	/** The jump-in drill ({@link #KIND_JUMPIN}): kept apart, and no part of passing a stage. */
	final Tally jumpInTally = new Tally();
''')
rep('''			"punisher", "backer", "timer", "people", "spacer", "whiffer", "outside"};''',
    '''			"punisher", "backer", "timer", "people", "spacer", "whiffer", "outside", "jump-in"};''')
rep('''		float[] f = new float[KIND_OUTSIDE + 1];''', '''		float[] f = new float[KIND_JUMPIN + 1];''')
rep('''			case KIND_OUTSIDE -> outsideTally;
			default -> null;''', '''			case KIND_OUTSIDE -> outsideTally;
			case KIND_JUMPIN -> jumpInTally;
			default -> null;''')
rep('''					outsideTally, replayTally, drillTally, jumpDrillTally, punishDrillTally, freeDrillTally, oldModelTally, traderTally};''',
    '''					outsideTally, replayTally, drillTally, jumpDrillTally, punishDrillTally, freeDrillTally, oldModelTally, traderTally, jumpInTally};''')
rep('''		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		return episodeStageIndex''', '''		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		if (episodeKind == KIND_JUMPIN) {
			return jumpInTally;
		}
		return episodeStageIndex''')
rep('''		outsideTally.clear();
		replayTally.clear();''', '''		outsideTally.clear();
		jumpInTally.clear();
		replayTally.clear();''')
# setUpCase: no replays, nothing kept
rep('''		if (cfg.replayLostShare > 0f && rng.nextFloat() < cfg.replayLostShare) {''',
    '''		if (episodeKind != KIND_JUMPIN && cfg.replayLostShare > 0f && rng.nextFloat() < cfg.replayLostShare) {''')
rep('''		if (cfg.mutateShare > 0f && rng.nextFloat() < cfg.mutateShare) {''',
    '''		if (episodeKind != KIND_JUMPIN && cfg.mutateShare > 0f && rng.nextFloat() < cfg.mutateShare) {''')
rep('''		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		} else {''', '''		if (episodeKind == KIND_JUMPIN) {
			// A drill of one exchange: nothing to keep.
		} else if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		} else {''')
# episode limit
rep('''		return episodeDrill ? stage.drillTicks : episodeCritDrill ? CRIT_DRILL_TICKS : stage.episodeTicks;''',
    '''		return episodeDrill ? stage.drillTicks : episodeCritDrill ? CRIT_DRILL_TICKS : episodeKind == KIND_JUMPIN ? cfg.jumpInTicks : stage.episodeTicks;''')
# stage pick
rep('''		episodeStageIndex = mode == Mode.TRAIN
				? curriculum.pickEpisodeStage(rng, cfg.rehearsalProbability, cfg.selfCritShare)
				: curriculum.stageIndex();''', '''		// A share of the fights are the jump-in drill, played in the crit stage; see Config.jumpInShare.
		int critsStage = jumpInStage();
		boolean jumpInFight = mode == Mode.TRAIN && demo == null && critsStage >= 0 && cfg.jumpInShare > 0f && rng.nextFloat() < cfg.jumpInShare;
		episodeStageIndex = jumpInFight ? critsStage
				: mode == Mode.TRAIN ? curriculum.pickEpisodeStage(rng, cfg.rehearsalProbability, cfg.selfCritShare)
				: curriculum.stageIndex();''')
rep('''		if (episodeCritDrill) {
			episodeDrill = false;
		}''', '''		if (episodeCritDrill) {
			episodeDrill = false;
		}
		if (jumpInFight) {
			episodeDrill = false;
			episodeCritDrill = false;
		}''')
rep('''		if (scripted && rng.nextFloat() < outsideChance()) {
			episodeKind = KIND_OUTSIDE;
		}''', '''		if (scripted && rng.nextFloat() < outsideChance()) {
			episodeKind = KIND_OUTSIDE;
		}
		if (jumpInFight && scripted) {
			episodeKind = KIND_JUMPIN;
		}''')
rep('''				&& episodeKind != KIND_SPACER && episodeKind != KIND_WHIFFER && episodeKind != KIND_OUTSIDE
				&& rng.nextFloat() < stage.randomShare) {''', '''				&& episodeKind != KIND_SPACER && episodeKind != KIND_WHIFFER && episodeKind != KIND_OUTSIDE && episodeKind != KIND_JUMPIN
				&& rng.nextFloat() < stage.randomShare) {''')
rep('''		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', '''		} else if (episodeKind == KIND_JUMPIN) {
			// It does one thing: from four and a half to six and a half blocks off it runs and jumps in at the fighter with
			// its sword ready, and makes its crit on the way down, wherever the fighter's hit has put it (the user's friends,
			// 23 October). The fight is that exchange; see jumpInStep.
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.jumpIn = true;
			plan.bot.jumpInAlways = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;
			plan.bot.critPress = 1f;
			plan.bot.punish = false;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.jumpResetChance = 0f;
			plan.bot.antic = "";
			plan.bot.reactionTicks = 0;
			plan.bot.aimNoiseDeg = 0.5f + rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 0.95f;
			plan.bot.preferredRange = 2.9f;
			plan.bot.followUpTicks = 0;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = 4.5f;
			plan.bot.maxDistance = 6.5f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''')
rep('''		if (episodeKind == KIND_TIMER || episodeKind == KIND_SPACER || episodeKind == KIND_OUTSIDE) {''',
    '''		if (episodeKind == KIND_TIMER || episodeKind == KIND_SPACER || episodeKind == KIND_OUTSIDE || episodeKind == KIND_JUMPIN) {''')
rep('''			plan.botKnockback = episodePerson.knockback();''', '''			plan.botKnockback = episodePerson.knockback();
		} else if (episodeKind == KIND_JUMPIN) {
			// A server's full knockback, late by the connection drawn above.
			plan.botKnockback = 1f;''')
# a timeout in the drill is no draw
rep('''				facts.timedOut = !episodeDrill && !episodeCritDrill && stage.koEndsEpisode''',
    '''				facts.timedOut = !episodeDrill && !episodeCritDrill && episodeKind != KIND_JUMPIN && stage.koEndsEpisode''')
rep('''				boolean terminal = stage.koEndsEpisode && (facts.koDealt || facts.koTaken || facts.timedOut);
				boolean cut = episodeTick >= episodeTicks''', '''				boolean terminal = stage.koEndsEpisode && (facts.koDealt || facts.koTaken || facts.timedOut);
				boolean exchanged = episodeKind == KIND_JUMPIN && jumpInStep(facts);
				boolean cut = episodeTick >= episodeTicks || exchanged''')
rep('''				if (terminal || cut) {
					if (paired != null && !episodeDrill && !episodeCritDrill) {''', '''				if (terminal || cut) {
					if (episodeKind == KIND_JUMPIN) {
						jumpInEnd();
					}
					if (paired != null && !episodeDrill && !episodeCritDrill) {''')
# helper methods, before kindName
rep('''	/** The name a kind of opponent has in the log. */
	static String kindName(int kind) {''', '''	/** The crit stage, where the jump-in drill is played; -1 if there is none before the current one. */
	private int jumpInStage() {
		for (int i = 0; i < curriculum.stageCount() && i <= curriculum.stageIndex(); i++) {
			if ("Crits".equals(curriculum.stage(i).name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * One step of the jump-in drill: whether its exchange is over. That is once both have hit (the fighter's crit for its
	 * crit, a hit and the crit back, or the crit let land and the punish), once one side's hit has gone unanswered for
	 * {@link #JUMPIN_SETTLE_TICKS}, or at a second hit taken without one dealt.
	 */
	private boolean jumpInStep(StepFacts f) {
		if (f.hitsTaken > 0) {
			if (jumpInTakenTick < 0) {
				jumpInTakenTick = episodeTick;
				jumpInTakenCrit = f.critsTaken > 0;
			} else if (jumpInDealtTick < 0) {
				jumpInTakenTwice = true;
				return true;
			}
		}
		if (f.hitsDealt > 0 && jumpInDealtTick < 0) {
			jumpInDealtTick = episodeTick;
			jumpInDealtCrit = f.critDealt;
		}
		if (jumpInTakenTick >= 0 && jumpInDealtTick >= 0) {
			return true;
		}
		int first = Math.max(jumpInTakenTick, jumpInDealtTick);
		return first >= 0 && episodeTick - first >= JUMPIN_SETTLE_TICKS;
	}

	/** The jump-in drill's fight is over: how its exchange went is counted. */
	private void jumpInEnd() {
		jumpInDrill[JI_FIGHTS]++;
		jumpInDealtSum += episodeDealt;
		jumpInTakenSum += episodeTaken;
		boolean dealt = jumpInDealtTick >= 0;
		boolean taken = jumpInTakenTick >= 0;
		if (dealt && taken && jumpInDealtTick == jumpInTakenTick) {
			jumpInDrill[jumpInDealtCrit && jumpInTakenCrit ? JI_CRIT_FOR_CRIT : JI_TRADE]++;
		} else if (dealt && taken && jumpInDealtTick < jumpInTakenTick) {
			jumpInDrill[JI_HIT_BACK]++;
			if (jumpInTakenCrit) {
				jumpInDrill[JI_CRIT_BACK]++;
			}
		} else if (dealt && taken) {
			jumpInDrill[JI_PUNISHED]++;
			if (jumpInDealtCrit) {
				jumpInDrill[JI_PUNISH_CRIT]++;
			}
		} else if (dealt) {
			jumpInDrill[JI_CLEAN]++;
		} else if (jumpInTakenTwice) {
			jumpInDrill[JI_TWICE]++;
		} else if (taken) {
			jumpInDrill[JI_UNANSWERED]++;
		} else {
			jumpInDrill[JI_NOTHING]++;
		}
		jumpInTakenTick = -1;
		jumpInDealtTick = -1;
		jumpInTakenCrit = false;
		jumpInDealtCrit = false;
		jumpInTakenTwice = false;
	}

	/** For the log at each update: the jump-in drill and the rules about an opponent coming in; the counts are cleared. */
	private String jumpInLine() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < JI_NAMES.length; i++) {
			b.append(i == 0 ? "" : ", ").append(JI_NAMES[i]).append(' ').append(i == JI_CLASH ? clashJumps : jumpInDrill[i]);
		}
		long n = jumpInDrill[JI_FIGHTS];
		b.append(String.format(Locale.ROOT, " | a drill fight: dealt %.1f, taken %.1f", n == 0 ? 0.0 : jumpInDealtSum / n, n == 0 ? 0.0 : jumpInTakenSum / n));
		b.append(String.format(Locale.ROOT, " | swing held for one coming in %d ticks, knock-away certain %d ticks, instant punish %d ticks",
				holdCritTicks, holdCritKnockAways, instantPunishTicks));
		java.util.Arrays.fill(jumpInDrill, 0);
		jumpInDealtSum = 0;
		jumpInTakenSum = 0;
		clashJumps = 0;
		holdCritTicks = 0;
		holdCritKnockAways = 0;
		instantPunishTicks = 0;
		return b.toString();
	}

	/** The name a kind of opponent has in the log. */
	static String kindName(int kind) {''')
# log line at update, next to the combo strafe line
rep('''		if (cfg.comboStrafeRule) {
			SwordAi.LOGGER.info("out of the line of combos''', '''		if (cfg.jumpInShare > 0f || cfg.holdOnFallingJumper || cfg.clashJumpRule || cfg.instantPunish) {
			SwordAi.LOGGER.info("jump-in drill and crit trades: {}", jumpInLine());
		}
		if (cfg.comboStrafeRule) {
			SwordAi.LOGGER.info("out of the line of combos''')
# count clash take-offs
rep('''			keys = JumpIn.keys(keys, jump);
			ruleJumpAtAction = jumpIn.tookOff();''', '''			keys = JumpIn.keys(keys, jump);
			ruleJumpAtAction = jumpIn.tookOff();
			if (ruleJumpAtAction == JumpIn.CLASH) {
				clashJumps++;
			}''')
open(p, 'w', encoding='utf-8').write(s)
print('ok')
