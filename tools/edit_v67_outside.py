ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('build.gradle', [
('''	modImplementation "maven.modrinth:carpet:${project.carpet_version}"
	include "maven.modrinth:carpet:${project.carpet_version}"''', '''	// For training only, the game can run on Carpet PvP (a fork of Carpet whose fake players fight by
	// themselves, https://modrinth.com/mod/carpet-pvp-practice) instead, for an opponent not written
	// here: gradlew runClient -PcarpetPvp=<its jar>. The mod is still built against Carpet and ships with it.
	if (project.hasProperty('carpetPvp')) {
		modCompileOnly "maven.modrinth:carpet:${project.carpet_version}"
		modLocalRuntime files(project.property('carpetPvp'))
	} else {
		modImplementation "maven.modrinth:carpet:${project.carpet_version}"
	}
	include "maven.modrinth:carpet:${project.carpet_version}"''', 1),
])

edit('core/src/main/java/dev/swordai/core/config/Config.java', [
('''	public boolean hardOpponents = false;
''', '''	public boolean hardOpponents = false;
	/**
	 * In training, this share of the scripted fights is against the outside bot: Carpet PvP's own fighter
	 * (a fork of Carpet whose fake players fight by themselves, https://modrinth.com/mod/carpet-pvp-practice),
	 * where the game runs on it instead of Carpet. An opponent that was not written here, at the levels in
	 * {@link #outsideLevels}. Zero (off) unless set; without Carpet PvP in the game it never comes up.
	 * Since 18 October.
	 */
	public float outsideShare = 0f;
	/** The levels the outside bot plays at, one drawn per fight: beginner, casual, average, skilled, expert. */
	public String outsideLevels = "average,skilled,expert";
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
('''	public boolean spacer = false;
''', '''	public boolean spacer = false;
	/**
	 * Played by the outside bot (Carpet PvP's own fighter) at this level, not by the scripted brain;
	 * null for the brain. See {@code Config.outsideShare}.
	 */
	public String outside;
''', 1),
('''		b.spacer = spacer;
''', '''		b.spacer = spacer;
		b.outside = outside;
''', 1),
])

A = 'src/main/java/dev/swordai/server/ArenaServer.java'
edit(A, [
('''	private BotPilot rival;
	private boolean rivalPlays;
''', '''	private BotPilot rival;
	private boolean rivalPlays;
	/** The outside bot plays the bot in this episode; see {@link #setOutside}. */
	private boolean outsidePlays;
	/** The level it was last set to, or null. */
	private String outsideLevel;
''', 1),
('''		s.mixedTurns = plan.mixedTurns;
		if (s.rivalPlays) {''', '''		s.mixedTurns = plan.mixedTurns;
		s.setOutside(plan.bot.outside);
		if (s.rivalPlays) {''', 1),
('''		s.brain.setProfile(s.profile);
		s.rivalPlays = false;
		s.setIdleNow(true);''', '''		s.brain.setProfile(s.profile);
		s.rivalPlays = false;
		s.setOutside(null);
		s.setIdleNow(true);''', 1),
('''		if (rivalPlays && !idle) {
			rival.tick(bot, agent, arena.floorY);
		} else {''', '''		if (outsidePlays && !idle) {
			// Carpet PvP's own fighter has it; see setOutside.
		} else if (rivalPlays && !idle) {
			rival.tick(bot, agent, arena.floorY);
		} else {''', 1),
('''	private void setIdleNow(boolean value) {
		idle = value;
		brain.setIdle(value);''', '''	private void setIdleNow(boolean value) {
		idle = value;
		brain.setIdle(value);
		if (outsidePlays && bot != null) {
			// While the agent cannot move (an update, a pause), the outside bot does not fight either.
			botOption("combat " + !value);
		}''', 1),
('''	// ---- bot placement --------------------------------------------------------------------------
''', '''	// ---- the outside bot ------------------------------------------------------------------------

	/** The game runs on Carpet PvP, which has the {@code /bot} command, and not on Carpet. */
	public static boolean hasBotCommand(MinecraftServer server) {
		return server != null && server.getCommands().getDispatcher().getRoot().getChild("bot") != null;
	}

	/**
	 * The outside bot: Carpet PvP's own fighter plays the arena's bot at this level ("beginner" to
	 * "expert"), with the kit the arena gives it, or with null no longer does and the scripted brain has it
	 * again. Set through its {@code /bot option} command, so nothing of Carpet PvP is needed to build this.
	 * Where the game has no such command the brain plays on.
	 */
	private void setOutside(String level) {
		boolean on = level != null && bot != null && hasBotCommand(server);
		if (on) {
			if (!level.equals(outsideLevel)) {
				for (String option : new String[] {"combatStyle melee", "difficulty " + level, "autoTarget true", "targetPlayers true",
						"targetBots false", "targetMobs false", "revenge true", "targetRange 32", "retreatHealth 0", "autoTotem false",
						"autoShield false", "autoFood false"}) {
					botOption(option);
				}
				outsideLevel = level;
			}
			botOption("combat true");
		} else if (outsidePlays && bot != null) {
			botOption("combat false");
		}
		outsidePlays = on;
	}

	private void botOption(String option) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "bot option " + botName + " " + option);
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("SwordAI: the outside bot did not take '{}'", option, e);
		}
	}

	// ---- bot placement --------------------------------------------------------------------------
''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	static final int KIND_WHIFFER = 13;
''', '''	static final int KIND_WHIFFER = 13;
	/** Carpet PvP's own fighter, an opponent not written here; see {@code Config.outsideShare}. */
	static final int KIND_OUTSIDE = 14;
''', 1),
('''	final Tally whifferTally = new Tally();
''', '''	final Tally whifferTally = new Tally();
	final Tally outsideTally = new Tally();
''', 1),
('''		episodeTrader = episodeKind == KIND_TRADER;
		episodeStander = false;''', '''		// And a share against the outside bot (Carpet PvP's own fighter), where the game has it; see Config.outsideShare.
		if (scripted && rng.nextFloat() < outsideChance()) {
			episodeKind = KIND_OUTSIDE;
		}
		episodeTrader = episodeKind == KIND_TRADER;
		episodeStander = false;''', 1),
('''				&& episodeKind != KIND_SPACER && episodeKind != KIND_WHIFFER
''', '''				&& episodeKind != KIND_SPACER && episodeKind != KIND_WHIFFER && episodeKind != KIND_OUTSIDE
''', 1),
('''		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', '''		} else if (episodeKind == KIND_OUTSIDE) {
			// Carpet PvP's own fighter plays it (see ArenaServer.setOutside): it plans its fight some ticks
			// ahead and, at the higher levels, crits, strafes and W-taps. The brain only places it.
			plan.bot.outside = pickOutsideLevel();
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.fleeChance = 0f;
			plan.bot.minDistance = 4f;
			plan.bot.maxDistance = 9f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', 1),
('''		if (episodeKind == KIND_TIMER || episodeKind == KIND_SPACER) {''', '''		if (episodeKind == KIND_TIMER || episodeKind == KIND_SPACER || episodeKind == KIND_OUTSIDE) {''', 1),
('''		if (episodeKind == KIND_WHIFFER) {
			return whifferTally;
		}''', '''		if (episodeKind == KIND_WHIFFER) {
			return whifferTally;
		}
		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}''', 1),
('''			pendingTally.add(whifferTally);
''', '''			pendingTally.add(whifferTally);
			pendingTally.add(outsideTally);
''', 1),
('''		whifferTally.clear();
''', '''		whifferTally.clear();
		outsideTally.clear();
''', 1),
('''| whiffers {} | 0.14.0 model {} |''', '''| whiffers {} | 0.14.0 model {} | outside bot {} |''', 1),
('''brief(whifferTally), brief(oldModelTally), forcedAnswers''', '''brief(whifferTally), brief(oldModelTally), brief(outsideTally), forcedAnswers''', 1),
('''			"punisher", "backer", "timer", "people", "spacer", "whiffer"};''', '''			"punisher", "backer", "timer", "people", "spacer", "whiffer", "outside"};''', 1),
('''		float[] f = new float[KIND_WHIFFER + 1];''', '''		float[] f = new float[KIND_OUTSIDE + 1];''', 1),
('''		for (int k = KIND_TRADER; k <= KIND_WHIFFER; k++) {''', '''		for (int k = KIND_TRADER; k <= KIND_OUTSIDE; k++) {''', 1),
('''			case KIND_WHIFFER -> whifferTally;
''', '''			case KIND_WHIFFER -> whifferTally;
			case KIND_OUTSIDE -> outsideTally;
''', 1),
('''	/** At each update, before the tallies are cleared''', '''	/** The chance a scripted fight is against the outside bot: none unless the game runs on Carpet PvP. */
	private float outsideChance() {
		if (cfg.outsideShare <= 0f || !ArenaServer.hasBotCommand(mc.getSingleplayerServer())) {
			return 0f;
		}
		return Math.min(0.5f, cfg.outsideShare * (cfg.hardOpponents ? hardWeight(kindLoss[KIND_OUTSIDE]) : 1f));
	}

	/** One of {@code Config.outsideLevels}, drawn. */
	private String pickOutsideLevel() {
		String[] levels = cfg.outsideLevels == null ? new String[0] : cfg.outsideLevels.split(",");
		String level = levels.length == 0 ? "" : levels[rng.nextInt(levels.length)].trim().toLowerCase(Locale.ROOT);
		return level.isEmpty() ? "skilled" : level;
	}

	/** At each update, before the tallies are cleared''', 1),
])

G = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'
edit('agg2.py', [
("('whiffers', 'WHIFFER'), ('0.14.0 model', 'OLD014')]", "('whiffers', 'WHIFFER'), ('0.14.0 model', 'OLD014'), ('outside bot', 'OUTSIDE')]", 1),
], root=G)
edit('progress.py', [
("KINDS = ['OLD014', 'own', 'older', 'TIMER', 'SPACER', 'STANDER', 'WHIFFER', 'JUMPER', 'PUNISHER']", "KINDS = ['OLD014', 'OUTSIDE', 'own', 'older', 'TIMER', 'SPACER', 'STANDER', 'WHIFFER', 'JUMPER', 'PUNISHER']", 1),
], root=G)
print('outside ok')
