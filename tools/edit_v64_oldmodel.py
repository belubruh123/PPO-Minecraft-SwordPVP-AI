ROOT = 'D:/swordai/'
S = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/config/Config.java', [
('''	public boolean recordRounds = true;
''', '''	public boolean recordRounds = true;
	/**
	 * The share of the fights against copies of itself that are against the old model, where one is
	 * kept ({@code swordai/old-model.bin}, with the settings it played with in {@code old-model-config.json}).
	 * It plays as it did: its sword all the way back every time, the rules it had then. The user,
	 * 18 October: the old model "can be spaced", so a network that spaces it, finds the timing
	 * loophole and combos it has learned to choose its timing. Since 18 October.
	 */
	public float oldModelShare = 0.3f;
''', 1)])

edit('src/client/java/dev/swordai/client/SwordFiles.java', [
('''	/** Where the rounds of {@code /swordai fight} are kept; see {@link RoundRecorder}. */''', '''	/** An old network kept to fight in self-play as it played then; see {@code Config.oldModelShare}. */
	static Path oldModel() {
		return dir().resolve("old-model.bin");
	}

	static Path oldModelConfig() {
		return dir().resolve("old-model-config.json");
	}

	/**
	 * The settings the old model played with: its own config file where there is one (else this
	 * session's), with what it did not have yet turned off and its sword all the way back for a rule's
	 * swing as for its own.
	 */
	static Config loadOldModelConfig(Config fallback) {
		Config c = null;
		try {
			if (Files.exists(oldModelConfig())) {
				c = GSON.fromJson(Files.readString(oldModelConfig(), StandardCharsets.UTF_8), Config.class);
			}
		} catch (IOException | JsonParseException e) {
			SwordAi.LOGGER.warn("old-model-config.json could not be read; the old model plays with this session's settings", e);
		}
		if (c == null) {
			c = GSON.fromJson(GSON.toJson(fallback), Config.class);
		}
		c.ruleSwingCharge = c.swingMinCharge;
		c.punishInCombo = true;
		c.lostRule = false;
		c.straightInRule = false;
		c.hopRule = false;
		c.recordRounds = false;
		return c;
	}

	/** Where the rounds of {@code /swordai fight} are kept; see {@link RoundRecorder}. */''', 1)])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	private boolean episodeVsOlder;
''', '''	private boolean episodeVsOlder;
	/** Where the old model is among the rivals, or -1; see {@code Config.oldModelShare}. */
	private int oldModelIndex = -1;
	private boolean episodeVsOldModel;
	final Tally oldModelTally = new Tally();
	private int oldModelWins;
	private int oldModelLosses;
	/** The old model's sword: all the way back, every time, as it played on a server. */
	private static final float OLD_MODEL_CHARGE = 1f;
''', 1),
('''		int rivalIndex = 0;
		if (againstItself && !episodeDrill && !stage.bot.fixedCopy && rivals.size() > 1 && rng.nextFloat() < OLDER_COPY_SHARE) {
			rivalIndex = 1 + rng.nextInt(rivals.size() - 1);
		}
		episodeVsOlder = rivalIndex > 0;''', '''		int rivalIndex = 0;
		// The old model, where there is one, is the last of them and has a share of its own.
		boolean copies = againstItself && !episodeDrill && !stage.bot.fixedCopy;
		boolean oldThere = oldModelIndex > 0 && oldModelIndex < rivals.size();
		int older = rivals.size() - 1 - (oldThere ? 1 : 0);
		episodeVsOldModel = copies && oldThere && rng.nextFloat() < cfg.oldModelShare;
		if (episodeVsOldModel) {
			rivalIndex = oldModelIndex;
		} else if (copies && older > 0 && rng.nextFloat() < OLDER_COPY_SHARE) {
			rivalIndex = 1 + rng.nextInt(older);
		}
		episodeVsOlder = rivalIndex > 0 && !episodeVsOldModel;''', 1),
('''					} else if (episodeVsOlder) {
						// Counted on its own: beating an older copy says something else than beating the newest.''', '''					} else if (episodeVsOldModel) {
						// Its own count: whether it has found the old model's timing, and wins.
						rewards.endEpisode(target, false, false);
						if (facts.koDealt) {
							oldModelWins++;
						} else if (facts.koTaken) {
							oldModelLosses++;
						}
					} else if (episodeVsOlder) {
						// Counted on its own: beating an older copy says something else than beating the newest.''', 1),
('''		if (!curriculum.fixedCopy()) {
			for (Path older : SwordFiles.olderChampions()) {
				try {
					next.add(LoadedFighter.loadRival(older, cfg, curriculum, headMask, frameAgeTicks));
				} catch (IOException | RuntimeException e) {
					// From a version whose network had another shape: it cannot play any more.
					SwordAi.LOGGER.warn("self-play: skipping the older copy " + older.getFileName() + ": " + e);
				}
			}
		}''', '''		if (!curriculum.fixedCopy()) {
			for (Path older : SwordFiles.olderChampions()) {
				try {
					next.add(LoadedFighter.loadRival(older, cfg, curriculum, headMask, frameAgeTicks));
				} catch (IOException | RuntimeException e) {
					// From a version whose network had another shape: it cannot play any more.
					SwordAi.LOGGER.warn("self-play: skipping the older copy " + older.getFileName() + ": " + e);
				}
			}
		}
		// And the old model, as it played; see Config.oldModelShare.
		int oldAt = -1;
		if (!curriculum.fixedCopy() && cfg.oldModelShare > 0f && Files.exists(SwordFiles.oldModel())) {
			try {
				LoadedFighter old = LoadedFighter.loadRival(SwordFiles.oldModel(), SwordFiles.loadOldModelConfig(cfg), curriculum, headMask, frameAgeTicks);
				old.fighter.swingNoEarlierThan(OLD_MODEL_CHARGE, 0);
				old.fighter.rulesNoEarlierThan(OLD_MODEL_CHARGE);
				next.add(old);
				oldAt = next.size() - 1;
			} catch (IOException | RuntimeException e) {
				SwordAi.LOGGER.warn("self-play: skipping the old model: " + e);
			}
		}
		oldModelIndex = oldAt;''', 1),
('''		Path file = SwordFiles.drillCopy();
		learner.save(file);''', '''		Path file = SwordFiles.drillCopy();
		learner.save(file);
		oldModelIndex = -1;''', 1),
('''		if (episodeCritDrill) {
			// The first drill is counted as itself, held or not; the second apart when it has all its keys.
			return critDrillKind == Curriculum.DRILL_RANGE ? jumpDrillTally : episodeLockedDrill ? punishDrillTally : freeDrillTally;
		}''', '''		if (episodeCritDrill) {
			// The first drill is counted as itself, held or not; the second apart when it has all its keys.
			return critDrillKind == Curriculum.DRILL_RANGE ? jumpDrillTally : episodeLockedDrill ? punishDrillTally : freeDrillTally;
		}
		if (episodeVsOldModel) {
			return oldModelTally;
		}''', 1),
('''		whifferTally.clear();
''', '''		whifferTally.clear();
		oldModelTally.clear();
''', 1),
('''| whiffers {} | swings made''', '''| whiffers {} | 0.14.0 model {} | swings made''', 1),
('''brief(whifferTally), forcedAnswers''', '''brief(whifferTally), brief(oldModelTally), forcedAnswers''', 1),
('''on its way back {} over {} ticks | straight runs {} ticks",''', '''on its way back {} over {} ticks | straight runs {} ticks | fights against the 0.14.0 model: won {} lost {}",''', 1),
('''spaceCoolTicks, straightTicks);
		straightTicks = 0;''', '''spaceCoolTicks, straightTicks, oldModelWins, oldModelLosses);
		straightTicks = 0;
		oldModelWins = 0;
		oldModelLosses = 0;''', 1),
])
edit('agg2.py', [
("""('whiffers', 'WHIFFER')]""", """('whiffers', 'WHIFFER'), ('0.14.0 model', 'OLD014')]""", 1),
("""'STANDER', 'WHIFFER', 'face', 'older'""", """'STANDER', 'WHIFFER', 'OLD014', 'face', 'older'""", 1),
("""'STANDER', 'WHIFFER', 'older'""", """'STANDER', 'WHIFFER', 'OLD014', 'older'""", 1),
], root=S)
edit('abrun.sh', [('TIMER,SPACER,STANDER,WHIFFER > ', 'TIMER,SPACER,STANDER,WHIFFER,OLD014 > ', 1)], root=S)
print('oldmodel ok')
