import io


def load(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    return s.replace('\r\n', '\n'), nl


def save(p, s, nl):
    io.open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', nl))


def rep(s, a, b, n=1):
    assert s.count(a) == n, (s.count(a), a[:90])
    return s.replace(a, b)


root = 'D:/swordai/'

# ---- Config
p = root + 'core/src/main/java/dev/swordai/core/config/Config.java'
s, nl = load(p)
s = rep(s, '''	public int swingExtraTicksOnline = 0;
''', '''	public int swingExtraTicksOnline = 0;
	/**
	 * Hit, and not yet having hit back, it swings as soon as its sword is back and the opponent is
	 * under its crosshair and near enough; see {@code AnswerRule}. A rule, in training and in every
	 * fight, so that it never takes a combo without swinging back where a swing was there to be made.
	 * False leaves that to the network, as up to the first runs of 0.14.0.
	 */
	public boolean answerRule = true;
''')
s = rep(s, 'CURRENT_VERSION = 44;', 'CURRENT_VERSION = 45;')
save(p, s, nl)

# ---- Fighter
p = root + 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
s, nl = load(p)
s = rep(s, '''	private int swingExtraTicks;
''', '''	private int swingExtraTicks;
	private final boolean answerRule;
''')
s = rep(s, '''		this.swingMinCharge = cfg.swingMinCharge;
''', '''		this.swingMinCharge = cfg.swingMinCharge;
		this.answerRule = cfg.answerRule;
''')
s = rep(s, '''		learner.act(obs, SwingGate.keys(headMask, waiting ? 0f : body.charge, swingMinCharge), false, false, temperature, action);
		if (assist) {
			idea.assist(action, headMask, visible);
		}
''', '''		int keys = SwingGate.keys(headMask, waiting ? 0f : body.charge, swingMinCharge);
		// And it is pressed for it where it owes the opponent an answer and one is there to be made; see AnswerRule.
		boolean answer = answerRule && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK) && AnswerRule.applies(obs.vec);
		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		learner.act(obs, keys, false, false, temperature, action);
		if (assist) {
			idea.assist(action, headMask, visible);
		}
		if (answer) {
			action[ActionSpace.ATTACK] = 1;
		}
''')
save(p, s, nl)

# ---- Trainer
p = root + 'src/client/java/dev/swordai/client/Trainer.java'
s, nl = load(p)
s = rep(s, '''		if (pendingUpdate != null) {
			// The network is busy learning; the copy kept when that began chooses, as the network would have.
			learner.actStandIn(obs, episodeKeys(), false, action);
		} else {
			learner.act(obs, episodeKeys(), false, mode == Mode.TRAIN, cfg.playTemperature, action);
		}
''', '''		int keys = episodeKeys();
		// Hit and not yet having hit back, with the sword back and the opponent under the crosshair and
		// near: the swing is not the network's to leave out. The key is taken from it for the tick, as
		// by the rule about the sword's charge, and pressed. See AnswerRule.
		boolean answer = cfg.answerRule && swingMinCharge() > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK) && AnswerRule.applies(obs.vec);
		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (pendingUpdate != null) {
			// The network is busy learning; the copy kept when that began chooses, as the network would have.
			learner.actStandIn(obs, keys, false, action);
		} else {
			learner.act(obs, keys, false, mode == Mode.TRAIN, cfg.playTemperature, action);
		}
		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}
''')
s = rep(s, '''	boolean episodeSlow;
''', '''	boolean episodeSlow;
	/** Swings the rule made since the last update: hit, sword back, opponent under the crosshair and near; see {@code AnswerRule}. */
	long forcedAnswers;
''')
s = rep(s, '''		SwordAi.LOGGER.info("fights since the last update ({} steps in total): own {} | traders {} | crit drill 1 {} | punish held {} | punish free {} | slow connection {} | ordinary connection {} | combo drills {}",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally),
				slowCount.brief(), quickCount.brief(), brief(drillTally));''', '''		SwordAi.LOGGER.info("fights since the last update ({} steps in total): own {} | traders {} | crit drill 1 {} | punish held {} | punish free {} | slow connection {} | ordinary connection {} | combo drills {} | swings made by the answer rule {}",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally),
				slowCount.brief(), quickCount.brief(), brief(drillTally), forcedAnswers);
		forcedAnswers = 0;''')
s = rep(s, '''sweeps %d, early %d, swings %d, air swings %d (%d at nothing), hits answered %d/%d, combos on it %d of which a third hit %d",''', '''sweeps %d, early %d, swings %d, air swings %d (%d at nothing), hits answered %d/%d, combos on it %d of which a third hit %d, combos over %d of which without a swing back %d",''')
s = rep(s, '''				t.sweepHits, t.spamClicks, t.clicks, t.airSwings, t.airWhiffs, t.answers, t.hitsToAnswer, t.twoHitCombos, t.threeHitCombos);''', '''				t.sweepHits, t.spamClicks, t.clicks, t.airSwings, t.airWhiffs, t.answers, t.hitsToAnswer, t.twoHitCombos, t.threeHitCombos,
				t.combosOver, t.combosUnanswered);''')
if 'import dev.swordai.core.rl.AnswerRule;' not in s:
    s = rep(s, 'import dev.swordai.core.rl.JumpNudge;\n', 'import dev.swordai.core.rl.AnswerRule;\nimport dev.swordai.core.rl.JumpNudge;\n')
save(p, s, nl)

# ---- Tally
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Tally.java'
s, nl = load(p)
s = rep(s, '''	public long twoHitCombos;
	public long threeHitCombos;
''', '''	public long twoHitCombos;
	public long threeHitCombos;
	/**
	 * The opponent's combos on the agent (two hits in a row or more) that are over, and how many of
	 * them it sat through without one swing of its own from the first hit to the end. That number is
	 * meant to be nought.
	 */
	public long combosOver;
	public long combosUnanswered;
''')
s = rep(s, '''		twoHitCombos += t.twoHitCombos;
		threeHitCombos += t.threeHitCombos;
''', '''		twoHitCombos += t.twoHitCombos;
		threeHitCombos += t.threeHitCombos;
		combosOver += t.combosOver;
		combosUnanswered += t.combosUnanswered;
''', 2)
s = rep(s, '''		twoHitCombos = 0;
		threeHitCombos = 0;
''', '''		twoHitCombos = 0;
		threeHitCombos = 0;
		combosOver = 0;
		combosUnanswered = 0;
''')
save(p, s, nl)

# ---- RewardModel: whether it swung at all during a run of hits
p = root + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java'
s, nl = load(p)
s = rep(s, '''	private boolean handedLong;
''', '''	private boolean handedLong;
	/** The most hits in a row the run it is taking has come to, and whether it has swung since the run began. */
	private int runHits;
	private boolean runSwung;
''')
# a click at any time during a run counts
s = rep(s, '''		if (f.clicked) {
			tally.clicks++;
''', '''		if (f.clicked) {
			if (takenStreak > 0) {
				runSwung = true;
			}
			tally.clicks++;
''')
s = rep(s, '''			int before = takenStreak;
			takenStreak += f.hitsTaken;
''', '''			int before = takenStreak;
			takenStreak += f.hitsTaken;
			if (before == 0) {
				// A new run of hits: what it swung before does not answer it. (A swing on the tick of the hit does not either.)
				runSwung = false;
			}
			runHits = takenStreak;
''')
# the three places a run ends
s = rep(s, '''			takenStreak = 0;
			handedLong = false;
			clearTicks = 0;
			backLeft = 0;
			if (c >= FULL_CHARGE) {''', '''			endRun(tally);
			takenStreak = 0;
			handedLong = false;
			clearTicks = 0;
			backLeft = 0;
			if (c >= FULL_CHARGE) {''')
s = rep(s, '''				escapeHeld = w.comboEscape;
				escapeArmed = false;
			}
			takenStreak = 0;
			handedLong = false;
''', '''				escapeHeld = w.comboEscape;
				escapeArmed = false;
			}
			endRun(tally);
			takenStreak = 0;
			handedLong = false;
''')
s = rep(s, '''	/** Closes anything still open and resets for the next episode. */
	public void endEpisode(Tally tally, boolean won, boolean lost) {
		closeTapWindow(tally);
		endCombo(tally);
''', '''	/** A run of hits taken is over: counted, if it was a combo, and whether it was sat through without a swing. */
	private void endRun(Tally tally) {
		if (runHits >= 2) {
			tally.combosOver++;
			if (!runSwung) {
				tally.combosUnanswered++;
			}
		}
		runHits = 0;
		runSwung = false;
	}

	/** Closes anything still open and resets for the next episode. */
	public void endEpisode(Tally tally, boolean won, boolean lost) {
		closeTapWindow(tally);
		endCombo(tally);
		endRun(tally);
''')
s = rep(s, '''		takenStreak = 0;
		handedLong = false;
		ticksSinceTaken = 0;
		escapeArmed = true;''', '''		takenStreak = 0;
		handedLong = false;
		runHits = 0;
		runSwung = false;
		ticksSinceTaken = 0;
		escapeArmed = true;''')
# a drill starts some hits in
s = rep(s, '''		handedLong = hits >= 3;
''', '''		handedLong = hits >= 3;
		runHits = hits;
		runSwung = false;
''')
save(p, s, nl)
print('ok')
