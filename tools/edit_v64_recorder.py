ROOT = 'D:/swordai/'


def edit(path, pairs):
    full = ROOT + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


F = 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
edit(F, [
('''	private boolean answerForced;
''', '''	private boolean answerForced;
	/** Which rules had keys on the last decision; see {@link #rules()}. */
	private int rules;
''', 1),
('''		if (action[ActionSpace.ATTACK] == 1) {
			idea.swung(rng);
		}
		motor.step(''', '''		if (action[ActionSpace.ATTACK] == 1) {
			idea.swung(rng);
		}
		rules = (answer ? 1 : 0) | (keep ? 1 << 1 : 0) | (meet ? 1 << 2 : 0) | (reset != 0 ? 1 << 3 : 0) | (backOut ? 1 << 4 : 0)
				| (step != 0 ? 1 << 5 : 0) | (jump != 0 ? 1 << 6 : 0) | (hitAim ? 1 << 7 : 0) | (lost ? 1 << 8 : 0) | (straight ? 1 << 9 : 0);
		motor.step(''', 1),
('''	/** The action of the last decision. */
	public int[] action() {''', '''	/**
	 * Which rules had keys on the last decision, a bit each: answer swing, swing kept for the way
	 * down, run at a jumper, jump reset, backing out, step back, a jump made for it, camera after a
	 * hit, looking for a lost opponent, straight in. For the record of a fight.
	 */
	public int rules() {
		return rules;
	}

	/** The action of the last decision. */
	public int[] action() {''', 1),
])

edit('core/src/main/java/dev/swordai/core/config/Config.java', [
('''	public boolean straightInRule = true;
''', '''	public boolean straightInRule = true;
	/**
	 * Every round of {@code /swordai fight} is written to {@code swordai/fights}, tick by tick, with what
	 * likely decided it; the newest 200 are kept. See {@code RoundRecorder}. Since 18 October.
	 */
	public boolean recordRounds = true;
''', 1)])

edit('src/client/java/dev/swordai/client/SwordFiles.java', [
('''	static Path lastFight() {''', '''	/** Where the rounds of {@code /swordai fight} are kept; see {@link RoundRecorder}. */
	static Path fightsDir() {
		return dir().resolve("fights");
	}

	static Path lastFight() {''', 1)])

FS = 'src/client/java/dev/swordai/client/FightSession.java'
edit(FS, [
('''	/** A round against the current opponent is being counted. */
	private boolean styleOpen;
''', '''	/** A round against the current opponent is being counted. */
	private boolean styleOpen;
	/** The round, tick by tick, for afterwards; see {@link RoundRecorder}. */
	private final RoundRecorder rounds = new RoundRecorder();
''', 1),
('''				deaths++;
				closeStyle(true);''', '''				deaths++;
				closeStyle(true);
				closeRound("lost");''', 1),
('''				kills++;
				closeStyle(false);''', '''				kills++;
				closeStyle(false);
				closeRound("won");''', 1),
('''			// A new opponent, or the same one in a new body after respawning.
			closeStyle(false);''', '''			// A new opponent, or the same one in a new body after respawning.
			closeStyle(false);
			closeRound("open");''', 1),
('''		stopped = true;
		closeStyle(false);''', '''		stopped = true;
		closeStyle(false);
		closeRound("open");''', 1),
('''		clicked = action[ActionSpace.ATTACK] == 1;
		clickResolved = false;''', '''		if (loaded.cfg.recordRounds) {
			if (!rounds.open()) {
				rounds.start(opponentName);
			}
			rounds.tick(me, opp, body, action, fighter.rules(), dealtNow, takenNow);
		}
		clicked = action[ActionSpace.ATTACK] == 1;
		clickResolved = false;''', 1),
('''		int made = OwnSwing.count();
		if (made != ownSwingsSeen) {
			// The swing reached someone. What it was, by the game's own rules, as this game saw it.
			ownSwingsSeen = made;
			if (OwnSwing.charge() <= 0.9f) {
				ownEarly++;
			} else if (OwnSwing.crit()) {
				ownCrit++;
			} else if (OwnSwing.sprinting()) {
				ownSprint++;
			} else if (OwnSwing.sweep()) {
				ownSweep++;
			} else {
				ownPlain++;
			}
		}''', '''		int made = OwnSwing.count();
		String kind = "miss";
		if (made != ownSwingsSeen) {
			// The swing reached someone. What it was, by the game's own rules, as this game saw it.
			ownSwingsSeen = made;
			if (OwnSwing.charge() <= 0.9f) {
				ownEarly++;
				kind = "early";
			} else if (OwnSwing.crit()) {
				ownCrit++;
				kind = "crit";
			} else if (OwnSwing.sprinting()) {
				ownSprint++;
				kind = "sprint";
			} else if (OwnSwing.sweep()) {
				ownSweep++;
				kind = "sweep";
			} else {
				ownPlain++;
				kind = "plain";
			}
		}
		rounds.swing(kind);''', 1),
('''	/** The round against the opponent is over: what was counted of how it fights is saved. */
	private void closeStyle(boolean lost) {
		if (!styleOpen) {
			return;
		}
		styleOpen = false;
		int ping = 0;
		if (opponentId != null && mc.getConnection() != null && mc.getConnection().getPlayerInfo(opponentId) != null) {
			ping = mc.getConnection().getPlayerInfo(opponentId).getLatency();
		}
		style.finish(lost, ping);
	}''', '''	/** The round against the opponent is over: what was counted of how it fights is saved. */
	private void closeStyle(boolean lost) {
		if (!styleOpen) {
			return;
		}
		styleOpen = false;
		style.finish(lost, opponentPing());
	}

	/** The round is over ({@code won}, {@code lost}, or {@code open}): it is written out, and a lost or won one is told in the chat. */
	private void closeRound(String result) {
		if (!rounds.open()) {
			return;
		}
		String line = rounds.finish(result, opponentPing());
		if (line != null) {
			SwordAi.LOGGER.info("round: {}", line);
			if (!"open".equals(result)) {
				say(line);
			}
		}
	}

	private int opponentPing() {
		if (opponentId != null && mc.getConnection() != null && mc.getConnection().getPlayerInfo(opponentId) != null) {
			return mc.getConnection().getPlayerInfo(opponentId).getLatency();
		}
		return 0;
	}''', 1),
])
print('recorder ok')
