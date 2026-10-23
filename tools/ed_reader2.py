import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'

e = Ed(R + 'rl/Learner.java')
e.rep("""	/** Whether the checkpoint loaded last had a tactics model saved in it; see {@link #playsTactics}. */
	private boolean tacticsSaved;
""", """	/** Whether the checkpoint loaded last had a tactics model saved in it; see {@link #playsTactics}. */
	private boolean tacticsSaved;
	/**
	 * The opponent reader ({@link OpponentReader}, {@code Config.opponentReader}); null without it. With
	 * copies for the stand-in and the proven network, like the tactics model.
	 */
	private final OpponentReader reader;
	private final OpponentReader.Workspace readerWs;
	private OpponentReader standInReader;
	private OpponentReader.Workspace standInReaderWs;
	private OpponentReader baseReader;
	private OpponentReader.Workspace baseReaderWs;
	private boolean readerSaved;
	private volatile String readerLine = "off";
	/** Marks the reader's part of a checkpoint, after the tactics model's. */
	private static final int READER_MARK = 0x4F505052; // "OPPR"
""")
e.rep("""		this.tactics = cfg.tacticModel ? new TacticNet(seed + 5) : null;""",
"""		this.reader = cfg.tacticModel && cfg.opponentReader ? new OpponentReader(seed + 7) : null;
		this.readerWs = reader == null ? null : reader.newWorkspace();
		this.tactics = cfg.tacticModel ? new TacticNet(seed + 5, reader != null) : null;""")
e.rep("""				cfg.tacticModel ? Tactic.COUNT : 0);""", """				cfg.tacticModel ? Tactic.COUNT : 0, cfg.tacticModel && cfg.opponentReader ? OpponentReader.Z : 0);""")
e.rep("""		actor.forward(actWs, obs.peri, obs.fovea, obs.vec, obs.tactic);""", """		actor.forward(actWs, obs.peri, obs.fovea, obs.vec, obs.tactic, reader == null ? null : obs.opp);""")
e.rep("""			standInTactics.params.copyWeightsFrom(tactics.params);
		}
	}""", """			standInTactics.params.copyWeightsFrom(tactics.params);
		}
		if (reader != null) {
			if (standInReader == null) {
				standInReader = new OpponentReader(0L);
				standInReaderWs = standInReader.newWorkspace();
			}
			standInReader.params.copyWeightsFrom(reader.params);
		}
	}""")
e.rep("""		standIn.forward(standInWs, obs.peri, obs.fovea, obs.vec, obs.tactic);""", """		standIn.forward(standInWs, obs.peri, obs.fovea, obs.vec, obs.tactic, reader == null ? null : obs.opp);""")
e.rep("""			baseTactics.params.copyWeightsFrom(tactics.params);
		}
	}""", """			baseTactics.params.copyWeightsFrom(tactics.params);
		}
		if (reader != null) {
			if (baseReader == null) {
				baseReader = new OpponentReader(0L);
				baseReaderWs = baseReader.newWorkspace();
			}
			baseReader.params.copyWeightsFrom(reader.params);
		}
	}""")
e.rep("""		base.forward(baseWs, obs.peri, obs.fovea, obs.vec, obs.tactic);""", """		base.forward(baseWs, obs.peri, obs.fovea, obs.vec, obs.tactic, reader == null ? null : obs.opp);""")
e.rep("""		if (tactics != null && baseTactics != null) {
			tactics.params.copyWeightsFrom(baseTactics.params);
			for (dev.swordai.core.nn.Param p : tactics.params.params()) {
				java.util.Arrays.fill(p.m, 0f);
				java.util.Arrays.fill(p.v, 0f);
			}
		}
	}""", """		if (tactics != null && baseTactics != null) {
			tactics.params.copyWeightsFrom(baseTactics.params);
			for (dev.swordai.core.nn.Param p : tactics.params.params()) {
				java.util.Arrays.fill(p.m, 0f);
				java.util.Arrays.fill(p.v, 0f);
			}
		}
		if (reader != null && baseReader != null) {
			reader.params.copyWeightsFrom(baseReader.params);
			for (dev.swordai.core.nn.Param p : reader.params.params()) {
				java.util.Arrays.fill(p.m, 0f);
				java.util.Arrays.fill(p.v, 0f);
			}
		}
	}

	/** Whether there is an opponent reader ({@code Config.opponentReader}): for training, one starts as it is made. */
	public boolean hasReader() {
		return reader != null;
	}

	/** Whether the network loaded is to be played with its opponent reader: only one trained with it. */
	public boolean playsReader() {
		return reader != null && readerSaved;
	}

	/**
	 * Reads {@code obs.oppWindow} into {@code obs.opp} ({@link OpponentReader}); by the copy kept for the
	 * stand-in or for the proven network when one of those plays the step. Without a reader, nothing.
	 */
	public void readOpponent(Obs obs, boolean byStandIn, boolean byBase) {
		if (reader == null) {
			return;
		}
		if (byBase && baseReader != null) {
			baseReader.read(baseReaderWs, obs.oppWindow, obs.opp);
		} else if (byStandIn && standInReader != null) {
			standInReader.read(standInReaderWs, obs.oppWindow, obs.opp);
		} else {
			reader.read(readerWs, obs.oppWindow, obs.opp);
		}
	}

	/** What the opponent reader learned at the last update, for the log. */
	public String readerLine() {
		return readerLine;
	}""")
e.rep("""		int t = tactics.choose(tacticWs, obs.vec, tacticRng);""", """		int t = tactics.choose(tacticWs, obs.vec, reader == null ? null : obs.opp, tacticRng);""")
e.rep("""		return standInTactics == null ? chooseTactic(obs, false) : standInTactics.choose(standInTacticWs, obs.vec, tacticRng);""",
"""		return standInTactics == null ? chooseTactic(obs, false) : standInTactics.choose(standInTacticWs, obs.vec, reader == null ? null : obs.opp, tacticRng);""")
e.rep("""		return baseTactics == null ? chooseTactic(obs, false) : baseTactics.choose(baseTacticWs, obs.vec, tacticRng);""",
"""		return baseTactics == null ? chooseTactic(obs, false) : baseTactics.choose(baseTacticWs, obs.vec, reader == null ? null : obs.opp, tacticRng);""")
e.rep("""		tactics.forward(tacticWs, obs.vec);
		return tacticWs.probs.clone();""", """		tactics.forward(tacticWs, obs.vec, reader == null ? null : obs.opp);
		return tacticWs.probs.clone();""")
e.rep("""		} else if (tactics != null) {
			tacticLine = "not learning this update";
		}
""", """		} else if (tactics != null) {
			tacticLine = "not learning this update";
		}
		if (reader != null && ppo.actorLr() > 0f && cfg.readerLr > 0f) {
			// Taught to name the kind of opponent of each step; see OpponentReader.
			lastReaderStats = reader.learn(rollout, cfg.readerLr, Math.max(1, cfg.readerEpochs), 128, cfg.maxGradNorm, rng);
		}
""")
e.rep("""	private volatile String readerLine = "off";
""", """	private volatile String readerLine = "off";
	private volatile OpponentReader.Stats lastReaderStats;
""")
e.rep("""	/** What the opponent reader learned at the last update, for the log. */
	public String readerLine() {
		return readerLine;
	}""", """	/** What the opponent reader did at the last update, for the log, with the kinds' names by class; null before any. */
	public String readerLine(String[] names) {
		OpponentReader.Stats s = lastReaderStats;
		return s == null ? (reader == null ? "off" : "not learning yet") : s.line(names);
	}""")
e.rep("""			// After everything an older version reads, which therefore loads the file as it did.
			if (tactics != null) {
				tactics.params.write(out);
			}""", """			// After everything an older version reads, which therefore loads the file as it did.
			if (tactics != null) {
				tactics.params.write(out);
				if (reader != null) {
					out.writeInt(READER_MARK);
					reader.params.write(out);
				}
			}""")
e.rep("""				} catch (java.io.EOFException e) {
					TacticNet fresh = new TacticNet(0L);
					tactics.params.copyWeightsFrom(fresh.params);
					tacticsSaved = false;
				}""", """				} catch (java.io.EOFException e) {
					TacticNet fresh = new TacticNet(0L, reader != null);
					tactics.params.copyWeightsFrom(fresh.params);
					tacticsSaved = false;
				}
				readerSaved = false;
				if (reader != null && tacticsSaved) {
					// Saved without a reader, the file ends after the tactics model and the reader starts as it is made.
					try {
						if (in.readInt() == READER_MARK) {
							reader.params.read(in);
							readerSaved = true;
						}
					} catch (java.io.EOFException e) {
						readerSaved = false;
					}
				}
				if (reader != null && !readerSaved) {
					reader.params.copyWeightsFrom(new OpponentReader(0L).params);
				}""")
e.save()

e = Ed(R + 'rl/Fighter.java')
e.rep("""	private final boolean tacticOnEvents;
""", """	private final boolean tacticOnEvents;
	/** The window of the fight the opponent reader reads; null without one. See {@link OpponentReader}. */
	private final OpponentReader.Window oppWindow;
""")
e.rep("""		this.tacticOnEvents = cfg.tacticOnEvents;
""", """		this.tacticOnEvents = cfg.tacticOnEvents;
		this.oppWindow = learner.playsReader() ? new OpponentReader.Window() : null;
""")
e.rep("""		if (tacticClock != null) {
			tacticClock.reset();
		}
		System.arraycopy""", """		if (tacticClock != null) {
			tacticClock.reset();
		}
		if (oppWindow != null) {
			oppWindow.reset();
		}
		System.arraycopy""")
e.rep("""		// How it fights for the next half second, chosen by the tactics model; see Tactic.
		int tactic = -1;
		if (tacticClock != null) {""", """		// What kind of opponent this is, read off the last seconds of the fight; see OpponentReader.
		if (oppWindow != null) {
			oppWindow.push(obs.vec);
			oppWindow.write(obs.oppWindow);
			learner.readOpponent(obs, false, false);
		}
		// How it fights for the next half second, chosen by the tactics model; see Tactic.
		int tactic = -1;
		if (tacticClock != null) {""")
e.save()

e = Ed('D:/swordai/src/client/java/dev/swordai/client/Trainer.java')
e.rep("""	private final dev.swordai.core.rl.Tactic.Clock tacticClock = new dev.swordai.core.rl.Tactic.Clock();
""", """	private final dev.swordai.core.rl.Tactic.Clock tacticClock = new dev.swordai.core.rl.Tactic.Clock();
	/** The window of the fight the opponent reader reads; see {@code Config.opponentReader}. */
	private final dev.swordai.core.rl.OpponentReader.Window oppWindow = new dev.swordai.core.rl.OpponentReader.Window();
""")
e.rep("""		int tactic = -1;
		if (learner.hasTactics() && !episodeLockedDrill) {""", """		// What kind of opponent this is, read off the last seconds of the fight; see OpponentReader.
		if (learner.hasReader()) {
			oppWindow.push(obs.vec);
			oppWindow.write(obs.oppWindow);
			learner.readOpponent(obs, pendingUpdate != null, baseActs);
		}
		int tactic = -1;
		if (learner.hasTactics() && !episodeLockedDrill) {""")
e.rep("""		edgeHold.reset();
		tacticClock.reset();""", """		edgeHold.reset();
		tacticClock.reset();
		oppWindow.reset();""")
e.rep("""		stepBack.newRound();
		tacticClock.reset();""", """		stepBack.newRound();
		tacticClock.reset();
		oppWindow.reset();""")
e.rep("""			SwordAi.LOGGER.info("tactics chosen: {}", tacticPicksLine());
		}""", """			SwordAi.LOGGER.info("tactics chosen: {}", tacticPicksLine());
		}
		if (learner.hasReader()) {
			SwordAi.LOGGER.info("opponent reader: {}", learner.readerLine(TASK_NAMES));
		}""")
e.save()
