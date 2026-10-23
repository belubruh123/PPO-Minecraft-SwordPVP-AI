"""19 Oct 21:45: fights it lost are kept and fought again until won twice in a row (LostCases, Config.replayLostShare)."""
import io

ROOT = 'D:/swordai/'


def edit(path, pairs):
    p = ROOT + path
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:80], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('edited', path)


edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [(
    '''	public BotProfile copy() {
		BotProfile b = new BotProfile();
		b.minDistance = minDistance;''', '''	public BotProfile copy() {
		BotProfile b = new BotProfile();
		copyInto(b);
		return b;
	}

	/** Sets every field of {@code b} to this profile's. */
	public void copyInto(BotProfile b) {
		b.minDistance = minDistance;'''), (
    '''		b.scalesWithSkill = scalesWithSkill;
		return b;
	}
''', '''		b.scalesWithSkill = scalesWithSkill;
	}
''')])

edit('core/src/main/java/dev/swordai/core/config/Config.java', [(
    '''	public boolean safeLearn = true;
''', '''	public boolean safeLearn = true;
	/**
	 * In training, the share of the fights against a stage's scripted opponents that are fights it
	 * lost before, set up exactly as they were, until each has been won twice in a row; see
	 * {@code LostCases}. Since 19 October: a fight it still loses is very likely the one a person finds.
	 */
	public float replayLostShare = 0.5f;
''')])

edit('src/client/java/dev/swordai/client/SwordFiles.java', [(
    '''	/** How that copy and the proven network have done in their fights so far. */''', '''	/** The fights it lost in training, kept to be fought again; see {@code LostCases}. */
	static Path lostCases() {
		return dir().resolve("lost-cases.json");
	}

	static dev.swordai.core.curriculum.LostCases loadLostCases() {
		dev.swordai.core.curriculum.LostCases lost = new dev.swordai.core.curriculum.LostCases();
		Path file = lostCases();
		if (Files.exists(file)) {
			try {
				LostCasesFile f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LostCasesFile.class);
				if (f != null && f.cases != null) {
					for (dev.swordai.core.curriculum.LostCases.Case c : f.cases) {
						if (c != null && c.bot != null && lost.size() < dev.swordai.core.curriculum.LostCases.MOST) {
							lost.cases().add(c);
						}
					}
					lost.beatenEver = f.beatenEver;
				}
			} catch (IOException | RuntimeException e) {
				SwordAi.LOGGER.warn("swordai/lost-cases.json could not be read; starting a new list", e);
			}
		}
		return lost;
	}

	static void saveLostCases(dev.swordai.core.curriculum.LostCases lost) {
		LostCasesFile f = new LostCasesFile();
		f.cases = new ArrayList<>(lost.cases());
		f.beatenEver = lost.beatenEver;
		try {
			Path tmp = lostCases().resolveSibling("lost-cases.json.tmp");
			Files.writeString(tmp, GSON.toJson(f), StandardCharsets.UTF_8);
			Files.move(tmp, lostCases(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not save the lost fights", e);
		}
	}

	private static final class LostCasesFile {
		List<dev.swordai.core.curriculum.LostCases.Case> cases;
		int beatenEver;
	}

	/** How that copy and the proven network have done in their fights so far. */''')])

edit('src/client/java/dev/swordai/client/Trainer.java', [(
    '''	final Tally outsideTally = new Tally();
''', '''	final Tally outsideTally = new Tally();
	/** Fights it lost before, fought again; see LostCases. Counted apart, so the kinds' own numbers stay a fair draw. */
	final Tally replayTally = new Tally();
	/** The fights it lost, kept to be fought again; see Config.replayLostShare. */
	private dev.swordai.core.curriculum.LostCases lostCases;
	/** This fight as it was set up, kept if it is lost; or the kept fight it is a replay of. */
	private dev.swordai.core.curriculum.LostCases.Case episodeCase;
	private boolean episodeReplay;
	private float episodeDealt;
	private float episodeTaken;
	private int episodeTicksCounted;
'''), (
    '''		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;''', '''		// A share of these fights are ones it lost before, set up exactly as they were; see LostCases.
		setUpCase(plan, scripted);
		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;'''), (
    '''				float reward = rewards.step(stage, facts, target);
''', '''				float reward = rewards.step(stage, facts, target);
				episodeDealt += facts.damageDealt;
				episodeTaken += facts.damageTaken;
				episodeTicksCounted++;
'''), (
    '''					} else {
						boolean counts = onEqualTerms();
						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);''', '''					} else {
						boolean counts = onEqualTerms();
						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);
						noteCase();'''), (
    '''		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		return episodeStageIndex != curriculum.stageIndex() ? rehearsalTally : episodeTrader ? traderTally : tally;''', '''		if (episodeReplay) {
			return replayTally;
		}
		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		return episodeStageIndex != curriculum.stageIndex() ? rehearsalTally : episodeTrader ? traderTally : tally;'''), (
    '''			pendingTally.add(outsideTally);
''', '''			pendingTally.add(outsideTally);
			pendingTally.add(replayTally);
'''), (
    '''		outsideTally.clear();
''', '''		outsideTally.clear();
		replayTally.clear();
'''), (
    '''		noteKindResults();
''', '''		noteKindResults();
		if (lostCases != null) {
			// And how the fights it lost before are going: kept, fought again, beaten for good.
			SwordAi.LOGGER.info("lost fights: {}", lostCases.takeCounts(Trainer::kindName));
			SwordFiles.saveLostCases(lostCases);
		}
'''), (
    '''				learner.save(learnedFile());
			} catch (IOException e) {''', '''				learner.save(learnedFile());
				if (lostCases != null) {
					SwordFiles.saveLostCases(lostCases);
				}
			} catch (IOException e) {'''), (
    '''| 0.14.0 model {} | outside bot {} | swings made by the rules {}''', '''| 0.14.0 model {} | outside bot {} | replays of lost fights {} | swings made by the rules {}'''), (
    '''brief(oldModelTally), brief(outsideTally), forcedAnswers, keptAnswers,''', '''brief(oldModelTally), brief(outsideTally), brief(replayTally), forcedAnswers, keptAnswers,'''), (
    '''	/** The chance a scripted fight is against the outside bot: none unless the game runs on Carpet PvP. */''', '''	/**
	 * Called once the fight's plan is drawn: keeps a copy of how this fight was set up, or, for a share of
	 * the fights against a stage's scripted opponents, sets it up as one it lost before; see LostCases.
	 */
	private void setUpCase(EpisodePlan plan, boolean scripted) {
		episodeCase = null;
		episodeReplay = false;
		episodeDealt = 0f;
		episodeTaken = 0f;
		episodeTicksCounted = 0;
		if (mode != Mode.TRAIN || !scripted || episodeKind == KIND_OUTSIDE || episodeKind == KIND_PERSON || episodeDrill || episodeCritDrill) {
			return;
		}
		if (lostCases == null) {
			lostCases = SwordFiles.loadLostCases();
		}
		if (cfg.replayLostShare > 0f && rng.nextFloat() < cfg.replayLostShare) {
			dev.swordai.core.curriculum.LostCases.Case c = lostCases.pick(rng, episodeStageIndex);
			if (c != null) {
				episodeCase = c;
				episodeReplay = true;
				c.bot.copyInto(plan.bot);
				plan.agentLag = c.agentLag;
				plan.botLag = c.botLag;
				plan.botPing = c.botPing;
				plan.agentStall = c.agentStall;
				plan.agentStallTicks = c.agentStallTicks;
				plan.botKnockback = c.botKnockback;
				plan.cornerStart = c.cornerStart;
				episodeKind = c.kind;
				episodeStander = c.stander;
				episodeSlow = c.slow;
				episodeTrader = episodeKind == KIND_TRADER;
				episodeRunner = episodeKind == KIND_RUNNER;
				episodeCircler = episodeKind == KIND_CIRCLER;
				return;
			}
		}
		dev.swordai.core.curriculum.LostCases.Case c = new dev.swordai.core.curriculum.LostCases.Case();
		c.stage = episodeStageIndex;
		c.kind = episodeKind;
		c.stander = episodeStander;
		c.slow = episodeSlow;
		c.bot = plan.bot.copy();
		c.agentLag = plan.agentLag;
		c.botLag = plan.botLag;
		c.botPing = plan.botPing;
		c.agentStall = plan.agentStall;
		c.agentStallTicks = plan.agentStallTicks;
		c.botKnockback = plan.botKnockback;
		c.cornerStart = plan.cornerStart;
		episodeCase = c;
	}

	/** A scripted fight is over: a fresh one it lost is kept, a kept one is counted; see LostCases. */
	private void noteCase() {
		if (episodeCase == null || lostCases == null) {
			return;
		}
		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted);
		} else {
			lostCases.freshFight(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted);
		}
		episodeCase = null;
	}

	/** The name a kind of opponent has in the log. */
	static String kindName(int kind) {
		return kind >= 0 && kind < KIND_NAMES.length ? KIND_NAMES[kind] : "plain";
	}

	/** The chance a scripted fight is against the outside bot: none unless the game runs on Carpet PvP. */''')])
