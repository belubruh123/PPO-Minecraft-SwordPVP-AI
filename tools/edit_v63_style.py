ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


F = 'src/client/java/dev/swordai/client/FightSession.java'
edit(F, [
    ('''	private boolean deathCounted;
	private boolean killCounted;
''', '''	private boolean deathCounted;
	private boolean killCounted;
	/** How the opponent fights, counted for training; see {@link StyleWatch}. */
	private final StyleWatch style = new StyleWatch();
	/** A round against the current opponent is being counted. */
	private boolean styleOpen;
''', 1),
    ('''			handBack("the respawn");
			if (!deathCounted) {
				deathCounted = true;
				deaths++;
			}''', '''			handBack("the respawn");
			if (!deathCounted) {
				deathCounted = true;
				deaths++;
				closeStyle(true);
			}''', 1),
    ('''			if (!killCounted) {
				killCounted = true;
				kills++;
			}''', '''			if (!killCounted) {
				killCounted = true;
				kills++;
				closeStyle(false);
			}''', 1),
    ('''		if (me.hurtTime > lastOwnHurt) {
			fighter.hitTaken();
			hitsTaken++;
		}
		lastOwnHurt = me.hurtTime;
		if (opp.hurtTime > lastOpponentHurt && ticksSinceConnect <= HIT_CREDIT_TICKS) {
			// Counted for the summary here, when the opponent is seen to flinch. The network was told of
			// the hit when the swing was made: see endTick.
			hitsDealt++;
			ticksSinceConnect = 1000;
		}
		lastOpponentHurt = opp.hurtTime;''', '''		boolean takenNow = me.hurtTime > lastOwnHurt;
		if (takenNow) {
			fighter.hitTaken();
			hitsTaken++;
		}
		lastOwnHurt = me.hurtTime;
		boolean dealtNow = opp.hurtTime > lastOpponentHurt && ticksSinceConnect <= HIT_CREDIT_TICKS;
		if (dealtNow) {
			// Counted for the summary here, when the opponent is seen to flinch. The network was told of
			// the hit when the swing was made: see endTick.
			hitsDealt++;
			ticksSinceConnect = 1000;
		}
		lastOpponentHurt = opp.hurtTime;
		if (loaded.cfg.watchStyles) {
			if (!styleOpen) {
				style.start(opponentName);
				styleOpen = true;
			}
			style.tick(me, opp, dealtNow, takenNow);
		}''', 1),
    ('''		stopped = true;
		try {
			AgentController.release(mc);
		} finally {''', '''		stopped = true;
		closeStyle(false);
		try {
			AgentController.release(mc);
		} finally {''', 1),
    ('''	/** The game's own pick: the opponent is under the crosshair and within reach. */''', '''	/** The round against the opponent is over: what was counted of how it fights is saved. */
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
	}

	/** The game's own pick: the opponent is under the crosshair and within reach. */''', 1),
    ('''			// A new opponent, or the same one in a new body after respawning.
			opponent = opp;''', '''			// A new opponent, or the same one in a new body after respawning.
			closeStyle(false);
			opponent = opp;''', 1),
])
edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean punishInCombo = false;
''', '''	public boolean punishInCombo = false;
	/**
	 * In {@code /swordai fight}, how the opponent fights is counted and kept in
	 * {@code swordai/people/<name>.json} (a few hundred bytes a player), and training fights scripted
	 * opponents made from those it has lost to or nearly; see {@code PersonStyle} and
	 * {@code StageSpec.peopleShare}.
	 */
	public boolean watchStyles = true;
''', 1),
])
print('style ok')
