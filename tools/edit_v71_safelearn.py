"""19 Oct 21:30: learning in safety in /swordai fight (FightLearnSession + SafeLearn)."""
import io

p = 'D:/swordai/src/client/java/dev/swordai/client/FightLearnSession.java'
s = io.open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:70], s.count(old))
    s = s.replace(old, new)


rep('import dev.swordai.core.rl.Obs;\n', 'import dev.swordai.core.rl.Obs;\nimport dev.swordai.core.rl.SafeLearn;\n')
rep('import java.io.IOException;\n', 'import java.io.IOException;\nimport java.io.Reader;\nimport java.io.Writer;\nimport java.nio.file.Files;\nimport java.nio.file.Path;\n')
rep('import java.util.Locale;\n', 'import java.util.Locale;\nimport java.util.Properties;\n')
rep('''	private static final float LEARN_FROM_EXPLAINED = 0.2f;
''', '''	private static final float LEARN_FROM_EXPLAINED = 0.2f;
	/**
	 * Learning in safety ({@code Config.safeLearn}): each update moves the learning copy at most this
	 * share of the way a training update may (learning rate), over at most {@link #SAFE_EPOCHS} passes,
	 * and stops early at {@link #SAFE_KL_STOP}. Up to 0.13.3 an update from a fight moved the network
	 * three to seven times as far as a training update.
	 */
	private static final float SAFE_LR_SHARE = 0.25f;
	private static final int SAFE_EPOCHS = 4;
	private static final float SAFE_KL_TARGET = 0.005f;
	private static final float SAFE_KL_STOP = 0.01f;
''')
rep('''	/** A copy of the network from before it learned from people was kept when this began. */
	private final boolean keptCopy;
''', '''	/** A copy of the network from before it learned from people was kept when this began. */
	private final boolean keptCopy;
	/** Learning in safety: the proven network and a learning copy take turns; see SafeLearn. */
	private final boolean safe;
	private final SafeLearn score = new SafeLearn();
''')
rep('''		this.keptCopy = SwordFiles.keepBeforePeople(history, this::say);
''', '''		this.keptCopy = SwordFiles.keepBeforePeople(history, this::say);
		this.safe = cfg.safeLearn;
		if (safe) {
			startSafely();
		}
''')
rep('''	boolean keptCopy() {
		return keptCopy;
	}
''', '''	boolean keptCopy() {
		return keptCopy;
	}

	boolean safe() {
		return safe;
	}

	/** For the message shown when the fight starts. */
	String safeLine() {
		return score.fights(true) + score.fights(false) == 0 ? "No fights counted yet." : "So far: " + score.line() + ".";
	}

	// ---- learning in safety ---------------------------------------------------------------------

	/**
	 * The network as it was trained (or as it last took over) is the proven one and is kept as it is;
	 * the learning copy carries on from where the last session left it, if that was a copy of this
	 * same proven network.
	 */
	private void startSafely() {
		learner.keepBase();
		cfg.epochs = Math.min(cfg.epochs, SAFE_EPOCHS);
		cfg.klTarget = Math.min(cfg.klTarget, SAFE_KL_TARGET);
		cfg.klStop = cfg.klStop > 0f ? Math.min(cfg.klStop, SAFE_KL_STOP) : SAFE_KL_STOP;
		cfg.maxActorLr = Math.min(cfg.maxActorLr, cfg.actorLr * SAFE_LR_SHARE);
		try {
			Properties p = new Properties();
			if (Files.exists(SwordFiles.learningScore())) {
				try (Reader in = Files.newBufferedReader(SwordFiles.learningScore())) {
					p.load(in);
				}
			}
			if (Files.exists(SwordFiles.learning()) && provenStamp().equals(p.getProperty("proven"))) {
				long steps = learner.totalSteps;
				long updates = learner.updates;
				learner.load(SwordFiles.learning());
				learner.totalSteps = Math.max(steps, learner.totalSteps);
				learner.updates = Math.max(updates, learner.updates);
				score.load(p);
			} else {
				// No copy yet, or one of another network: the copy starts as the proven network.
				score.restart();
				score.promotions = Integer.parseInt(p.getProperty("promotions", "0"));
				score.rollbacks = Integer.parseInt(p.getProperty("rollbacks", "0"));
			}
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not read the learning copy", e);
			say("The learning copy could not be read (" + e.getMessage() + "); it starts again from the proven network.");
			learner.restoreBase();
			score.restart();
		}
		learner.capActorLr(cfg.actorLr * SAFE_LR_SHARE);
		baseActs = score.nextIsBase();
	}

	/** Which proven network the learning copy belongs to: the checkpoint as it is on disk. */
	private static String provenStamp() throws IOException {
		Path c = SwordFiles.checkpoint();
		return Files.getLastModifiedTime(c).toMillis() + ":" + Files.size(c);
	}

	private void saveScore() {
		try {
			Properties p = new Properties();
			score.save(p);
			p.setProperty("proven", provenStamp());
			try (Writer out = Files.newBufferedWriter(SwordFiles.learningScore())) {
				p.store(out, "SwordAI: the proven network and the learning copy in their fights (see SafeLearn)");
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not save how the learning copy is doing", e);
		}
	}

	/**
	 * A fight is over: counted for whichever played it, and the other plays the next. Acted on here,
	 * between fights, and only with no update running: the copy takes over, or is thrown away.
	 */
	private void safeFightOver() {
		score.fightOver(byBase);
		SafeLearn.Verdict verdict = pendingUpdate == null ? score.verdict() : SafeLearn.Verdict.NONE;
		if (verdict == SafeLearn.Verdict.PROMOTE) {
			String line = score.line();
			try {
				SwordFiles.keepBeforePromotion();
				learner.save(SwordFiles.checkpoint());
				learner.save(SwordFiles.learning());
				learner.keepBase();
				score.promotions++;
				score.restart();
				say("The learning copy did better than the proven network (" + line + "), so it takes its place and is the proven network now. /swordai undo brings back the one it replaced.");
			} catch (IOException e) {
				say("The learning copy did better (" + line + ") but could not be saved (" + e.getMessage() + "); the proven network stays.");
			}
		} else if (verdict == SafeLearn.Verdict.ROLLBACK) {
			String line = score.line();
			learner.restoreBase();
			score.rollbacks++;
			score.restart();
			try {
				learner.save(SwordFiles.learning());
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not save the learning copy", e);
			}
			say("The learning copy did no better than the proven network (" + line + "): what it learned is thrown away and it starts again from the proven network, which was never changed.");
		} else {
			say("Learning safely: " + score.line() + ". Next fight: " + (score.nextIsBase() ? "the proven network." : "the learning copy."));
		}
		baseActs = score.nextIsBase();
		saveScore();
	}

	@Override
	Path learnedFile() {
		return safe ? SwordFiles.learning() : SwordFiles.checkpoint();
	}
''')
rep('''	private void endFight(boolean won, boolean lost) {
		if (handedBack || !hasLastAction || pendingUpdate != null || opponent == null) {
			return;
		}
		pendKoDealt = won;
		pendKoTaken = lost;
		fightOver = true;
		cutNow = true;
		try {
			step(Truth.INACTIVE);
		} finally {
			cutNow = false;
			fightOver = false;
		}
	}
''', '''	private void endFight(boolean won, boolean lost) {
		if (handedBack || !hasLastAction || opponent == null) {
			return;
		}
		if (pendingUpdate == null) {
			pendKoDealt = won;
			pendKoTaken = lost;
			fightOver = true;
			cutNow = true;
			try {
				step(Truth.INACTIVE);
			} finally {
				cutNow = false;
				fightOver = false;
			}
		}
		if (safe) {
			safeFightOver();
		}
	}
''')
rep('''	private int fightRollout() {
		return Math.max(64, Math.min(curriculum.current().rolloutSteps, cfg.fightRolloutSteps));''', '''	private int fightRollout() {
		if (safe) {
			// Learning in safety: from as much at a time as a training update, not from a minute or less.
			return Math.max(64, curriculum.current().rolloutSteps);
		}
		return Math.max(64, Math.min(curriculum.current().rolloutSteps, cfg.fightRolloutSteps));''')
rep('''		f.koTaken = pendKoTaken;
		clearPending();
	}
''', '''		f.koTaken = pendKoTaken;
		clearPending();
		if (safe && !handedBack) {
			// What this step came to, for whichever of the two chose it.
			score.tick(byBase, f.damageDealt, f.damageTaken);
		}
	}
''')
rep('''		try {
			saveProgress(was);
		} finally {
			closeLearning();''', '''		try {
			saveProgress(was);
			if (safe) {
				saveScore();
			}
		} finally {
			closeLearning();''')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('edited FightLearnSession')

# The messages when learning is switched on and when a fight that learns begins.
p = 'D:/swordai/src/client/java/dev/swordai/client/Commands.java'
s = io.open(p, encoding='utf-8').read()
rep('''				say(source, "It learns from each minute or two of fighting and fights on while it does; /swordai stop also learns from what is left."
						+ (session.keptCopy() ? " The network as it was before this was kept: /swordai undo brings it back." : ""));
				say(source, "Your mouse and keys are ignored while it fights''', '''				if (session.safe()) {
					say(source, "It learns safely: the proven network and a learning copy take turns, a fight each, and the copy takes over only once it has dealt more damage and taken less over 8 fights each; "
							+ "if it does worse it is thrown away. The proven network is never changed otherwise. " + session.safeLine());
				} else {
					say(source, "It learns from each minute or two of fighting and fights on while it does; /swordai stop also learns from what is left."
							+ (session.keptCopy() ? " The network as it was before this was kept: /swordai undo brings it back." : ""));
				}
				say(source, "Your mouse and keys are ignored while it fights''')
rep('''				say(source, "From the next /swordai fight or /swordai spar the AI learns from the fight as well, with the rewards of the stage it is in. It fights on while it learns, so nothing stops.");''', '''				say(source, "From the next /swordai fight or /swordai spar the AI learns from the fight as well, with the rewards of the stage it is in. It fights on while it learns, so nothing stops.");
				if (cfg.safeLearn) {
					say(source, "In /swordai fight it learns safely: the trained network and a learning copy take turns a fight each; the copy only replaces it after doing better (damage dealt minus taken) over 8 fights each, and is thrown away if it does worse.");
				}''')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('edited Commands')
