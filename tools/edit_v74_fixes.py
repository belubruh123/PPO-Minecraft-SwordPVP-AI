"""19 Oct 22:35: lost-fight replay fixes.
1. Replays were tallied under their opponent kind (the replay check sat after the kind checks), so the per-kind
   damage table and the hard-opponent weights included the replays of the worst fights. Now counted apart.
2. A fight decided by a knockout counts however short it was (stage 8 and 9 fights end at a knockout).
3. Every kept fight gets its turn: 30% of the picks are the one picked longest ago (PLR staleness), the rest by
   how badly it was lost. On 19 Oct three no-knockback/jumper fights lost by 250-300 a minute took most replays
   while timer and spacer fights waited unplayed."""
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


T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [(
    '''		if (episodeVsOldModel) {
			return oldModelTally;
		}
		// The fights against traders''', '''		if (episodeVsOldModel) {
			return oldModelTally;
		}
		// Before the kinds: a replay is the worst of a kind, picked for that, not a fair draw of it.
		if (episodeReplay) {
			return replayTally;
		}
		// The fights against traders'''), (
    '''		if (episodeReplay) {
			return replayTally;
		}
		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		return episodeStageIndex''', '''		if (episodeKind == KIND_OUTSIDE) {
			return outsideTally;
		}
		return episodeStageIndex'''), (
    '''						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);
						noteCase();''', '''						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);
						noteCase(terminal && !facts.timedOut);'''), (
    '''	private void noteCase() {
		if (episodeCase == null || lostCases == null) {
			return;
		}
		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted);
		} else {
			lostCases.freshFight(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted);
		}''', '''	private void noteCase(boolean knockedOut) {
		if (episodeCase == null || lostCases == null) {
			return;
		}
		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		} else {
			lostCases.freshFight(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		}''')])

LC = 'core/src/main/java/dev/swordai/core/curriculum/LostCases.java'
edit(LC, [(
    '''	/** Shorter fights than this (in ticks) say nothing either way. */''', '''	/** Shorter fights than this (in ticks) say nothing either way, unless a knockout decided them. */'''), (
    '''	static final float RETEST_SHARE = 0.15f;
''', '''	static final float RETEST_SHARE = 0.15f;
	/**
	 * This share of the picks is the kept fight picked longest ago, so that every one gets its turn; the
	 * rest go by how badly it was lost. Without it a few fights lost by 250-300 a minute took most of
	 * the replays while others waited (19 October). Prioritised level replay mixes in staleness the same way.
	 */
	static final float STALEST_SHARE = 0.3f;
'''), (
    '''		public boolean retestWon;
''', '''		public boolean retestWon;
		/** When it was last picked, counted in picks. */
		public long lastPicked;
'''), (
    '''	private int lostAgain;
	public int beatenEver;
''', '''	private int lostAgain;
	public int beatenEver;
	/** Picks made, for {@link Case#lastPicked}; carried on from the file's. */
	private long picks = -1;
'''), (
    '''	public boolean freshFight(Case setUp, float dealt, float taken, int ticks) {
		if (ticks < SHORTEST_TICKS || dealt >= taken) {''', '''	public boolean freshFight(Case setUp, float dealt, float taken, int ticks) {
		return freshFight(setUp, dealt, taken, ticks, false);
	}

	/** As above; a fight a knockout decided counts however short it was. */
	public boolean freshFight(Case setUp, float dealt, float taken, int ticks, boolean knockedOut) {
		if (ticks <= 0 || ticks < SHORTEST_TICKS && !knockedOut || dealt >= taken) {'''), (
    '''	public boolean replayed(Case c, float dealt, float taken, int ticks) {
		if (ticks < SHORTEST_TICKS) {
			return false;
		}''', '''	public boolean replayed(Case c, float dealt, float taken, int ticks) {
		return replayed(c, dealt, taken, ticks, false);
	}

	/** As above; a fight a knockout decided counts however short it was. */
	public boolean replayed(Case c, float dealt, float taken, int ticks, boolean knockedOut) {
		if (ticks <= 0 || ticks < SHORTEST_TICKS && !knockedOut) {
			return false;
		}'''), (
    '''	public Case pick(Random rng, int stage) {
		if (rng.nextFloat() < RETEST_SHARE) {
			Case again = pickBeaten(rng, stage);
			if (again != null) {
				return again;
			}
		}
		Case c = pickLost(rng, stage);
		return c != null ? c : pickBeaten(rng, stage);
	}
''', '''	public Case pick(Random rng, int stage) {
		Case c = null;
		if (rng.nextFloat() < RETEST_SHARE) {
			c = pickBeaten(rng, stage);
		}
		if (c == null) {
			c = rng.nextFloat() < STALEST_SHARE ? pickStalest(stage) : pickLost(rng, stage);
		}
		if (c == null) {
			c = pickBeaten(rng, stage);
		}
		if (c != null) {
			if (picks < 0) {
				picks = 0;
				for (Case k : cases) {
					picks = Math.max(picks, k.lastPicked);
				}
				for (Case k : beatenCases) {
					picks = Math.max(picks, k.lastPicked);
				}
			}
			c.lastPicked = ++picks;
		}
		return c;
	}

	/** The kept fight of this stage picked longest ago (never picked: the first kept). */
	private Case pickStalest(int stage) {
		Case best = null;
		for (Case c : cases) {
			if (c.stage == stage && (best == null || c.lastPicked < best.lastPicked)) {
				best = c;
			}
		}
		return best;
	}
''')])

edit('core/src/test/java/dev/swordai/core/curriculum/LostCasesTest.java', [(
    '''		// Priorities 520 against 30.
		assertTrue(badPicks > 900, "picked " + badPicks);''', '''		// Priorities 520 against 30, and every third pick or so the one picked longest ago.
		assertTrue(badPicks > 600 && badPicks < 800, "picked " + badPicks);'''), (
    '''	@Test
	void aFullListMakesRoomWithTheOneNearestToBeingBeaten() {''', '''	@Test
	void aKnockoutDecidesAFightHoweverShort() {
		LostCases lost = new LostCases();
		assertTrue(lost.freshFight(setUp(8, 7), 0, 20, 90, true));
		LostCases.Case c = lost.cases().get(0);
		assertTrue(lost.replayed(c, 20, 0, 80, true) == false && c.wonInRow == 1);
		assertTrue(lost.replayed(c, 20, 4, 120, true));
	}

	@Test
	void everyKeptFightGetsItsTurn() {
		LostCases lost = new LostCases();
		LostCases.Case bad = setUp(7, 5);
		lost.freshFight(bad, 0, 600, 1200);
		for (int i = 0; i < 9; i++) {
			lost.freshFight(setUp(7, 10), 290, 300, 1200);
		}
		Random rng = new Random(11);
		java.util.Set<LostCases.Case> seen = new java.util.HashSet<>();
		for (int i = 0; i < 60; i++) {
			seen.add(lost.pick(rng, 7));
		}
		assertEquals(10, seen.size());
	}

	@Test
	void aFullListMakesRoomWithTheOneNearestToBeingBeaten() {''')])
