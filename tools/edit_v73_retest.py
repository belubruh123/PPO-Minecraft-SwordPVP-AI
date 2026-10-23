"""19 Oct 22:20: beaten lost fights are kept and fought again (the user: once all are beaten, fight them all again
and find the ones it loses); a dev-only inbox pass-through so a script can spawn a Carpet fake player to test
safe learning in /swordai fight."""
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


LC = 'core/src/main/java/dev/swordai/core/curriculum/LostCases.java'
edit(LC, [(
    ''' * a row. Then it is beaten and let go. In training terms: prioritised replay of the levels it fails.
''', ''' * a row. Then it is beaten, and kept apart: once none of a stage's lost fights are left, and in a
 * small share of the replays before that, the beaten ones are fought again in turn, since a fight won
 * twice can still be forgotten; one lost again goes back on the list. In training terms: prioritised
 * replay of the levels it fails.
'''), (
    ''' * round my friend finds to beat this".
''', ''' * round my friend finds to beat this". And at 22:10: once it has learned all of them, "let it learn
 * all the cases again and find cases it loses".
'''), (
    '''	static final float PRIORITY_FLOOR = 20f;
''', '''	static final float PRIORITY_FLOOR = 20f;
	/** At most this many beaten ones are kept to be fought again; past that the oldest goes. */
	public static final int MOST_BEATEN = 400;
	/** While a stage still has lost fights kept, this share of its replays are beaten ones fought again. */
	static final float RETEST_SHARE = 0.15f;
'''), (
    '''		public int replays;

		float priority() {''', '''		public int replays;
		/** Beaten: won twice in a row, and kept apart to be fought again. */
		public boolean beaten;
		/** Times fought again since it was beaten, and whether the last of those was won. */
		public int retests;
		public boolean retestWon;

		float priority() {'''), (
    '''	private final List<Case> cases = new ArrayList<>();
	/** Since {@link #takeCounts}: added, replayed, won in a replay, beaten for good. */
	private int added;
	private int replayed;
	private int replayWins;
	private int beaten;
	public int beatenEver;

	public List<Case> cases() {
		return cases;
	}
''', '''	private final List<Case> cases = new ArrayList<>();
	private final List<Case> beatenCases = new ArrayList<>();
	/** Since {@link #takeCounts}: added, replayed, won in a replay, beaten for good, beaten ones fought again, won, lost. */
	private int added;
	private int replayed;
	private int replayWins;
	private int beaten;
	private int retested;
	private int retestWins;
	private int lostAgain;
	public int beatenEver;

	public List<Case> cases() {
		return cases;
	}

	/** The beaten ones, kept to be fought again. */
	public List<Case> beatenCases() {
		return beatenCases;
	}

	/** Puts a beaten one with the others, making room by letting the oldest go. */
	public void keepBeaten(Case c) {
		c.beaten = true;
		while (beatenCases.size() >= MOST_BEATEN) {
			beatenCases.remove(0);
		}
		beatenCases.add(c);
	}

	/** True once none are left to beat and every beaten one has been fought again and won. */
	public boolean allBeaten() {
		if (!cases.isEmpty() || beatenCases.isEmpty()) {
			return false;
		}
		for (Case c : beatenCases) {
			if (c.retests == 0 || !c.retestWon) {
				return false;
			}
		}
		return true;
	}
'''), (
    '''	public boolean replayed(Case c, float dealt, float taken, int ticks) {
		if (ticks < SHORTEST_TICKS) {
			return false;
		}
		replayed++;''', '''	public boolean replayed(Case c, float dealt, float taken, int ticks) {
		if (ticks < SHORTEST_TICKS) {
			return false;
		}
		if (c.beaten) {
			retestedOne(c, dealt, taken, ticks);
			return false;
		}
		replayed++;'''), (
    '''			if (c.wonInRow >= WINS_TO_BEAT) {
				cases.remove(c);
				beaten++;
				beatenEver++;
				return true;
			}''', '''			if (c.wonInRow >= WINS_TO_BEAT) {
				cases.remove(c);
				beaten++;
				beatenEver++;
				c.wonInRow = 0;
				c.retests = 0;
				c.retestWon = false;
				keepBeaten(c);
				return true;
			}'''), (
    '''	/** One of the kept fights of this stage, the worse it went the likelier; null if there are none. */
	public Case pick(Random rng, int stage) {
		double total = 0;''', '''	/** A beaten one was fought again: won, it stays beaten; lost, it goes back on the list. */
	private void retestedOne(Case c, float dealt, float taken, int ticks) {
		retested++;
		c.replays++;
		c.retests++;
		if (dealt > taken) {
			retestWins++;
			c.wins++;
			c.retestWon = true;
			return;
		}
		lostAgain++;
		beatenCases.remove(c);
		c.beaten = false;
		c.retestWon = false;
		c.losses++;
		c.wonInRow = 0;
		c.lostBy = -net(dealt, taken, ticks);
		if (cases.size() >= MOST) {
			cases.remove(makeRoom());
		}
		cases.add(c);
	}

	/**
	 * One of the kept fights of this stage, the worse it went the likelier; or, once none are left
	 * (and now and then before that), a beaten one fought again, the one fought again least first.
	 * Null if there are neither.
	 */
	public Case pick(Random rng, int stage) {
		if (rng.nextFloat() < RETEST_SHARE) {
			Case again = pickBeaten(rng, stage);
			if (again != null) {
				return again;
			}
		}
		Case c = pickLost(rng, stage);
		return c != null ? c : pickBeaten(rng, stage);
	}

	private Case pickBeaten(Random rng, int stage) {
		int least = Integer.MAX_VALUE;
		int n = 0;
		for (Case c : beatenCases) {
			if (c.stage != stage) {
				continue;
			}
			if (c.retests < least) {
				least = c.retests;
				n = 0;
			}
			if (c.retests == least) {
				n++;
			}
		}
		if (n == 0) {
			return null;
		}
		int at = rng.nextInt(n);
		for (Case c : beatenCases) {
			if (c.stage == stage && c.retests == least && at-- == 0) {
				return c;
			}
		}
		return null;
	}

	private Case pickLost(Random rng, int stage) {
		double total = 0;'''), (
    '''		String line = String.format(Locale.ROOT, "lost fights kept %d (%s), new %d, replayed %d of which won %d, beaten for good %d (%d in all)",
				cases.size(), byKind(name), added, replayed, replayWins, beaten, beatenEver);
		added = 0;''', '''		String line = String.format(Locale.ROOT, "lost fights kept %d (%s), new %d, replayed %d of which won %d, beaten for good %d (%d in all); "
				+ "beaten ones kept %d, fought again %d of which won %d, lost again %d%s",
				cases.size(), byKind(name), added, replayed, replayWins, beaten, beatenEver,
				beatenCases.size(), retested, retestWins, lostAgain, allBeaten() ? "; ALL BEATEN" : "");
		retested = 0;
		retestWins = 0;
		lostAgain = 0;
		added = 0;''')])

edit('core/src/test/java/dev/swordai/core/curriculum/LostCasesTest.java', [(
    '''		assertEquals(0, lost.size());
		assertEquals(1, lost.beatenEver);
	}
''', '''		assertEquals(0, lost.size());
		assertEquals(1, lost.beatenEver);
		assertEquals(1, lost.beatenCases().size());
		assertTrue(c.beaten);
	}

	@Test
	void onceAllAreBeatenTheyAreFoughtAgainAndOneLostGoesBack() {
		LostCases lost = new LostCases();
		LostCases.Case a = setUp(7, 1);
		LostCases.Case b = setUp(7, 12);
		lost.freshFight(a, 200, 300, 600);
		lost.freshFight(b, 200, 300, 600);
		for (LostCases.Case c : new LostCases.Case[] {a, b}) {
			lost.replayed(c, 300, 200, 600);
			lost.replayed(c, 300, 200, 600);
		}
		assertEquals(0, lost.size());
		assertFalse(lost.allBeaten(), "not fought again yet");
		Random rng = new Random(5);
		// Both are fought again in turn, the one fought again least first.
		LostCases.Case first = lost.pick(rng, 7);
		assertNotNull(first);
		lost.replayed(first, 300, 200, 600);
		LostCases.Case second = lost.pick(rng, 7);
		assertNotNull(second);
		assertTrue(second != first);
		lost.replayed(second, 300, 200, 600);
		assertTrue(lost.allBeaten());
		// Lost again: back on the list, and it is what comes up.
		lost.replayed(first, 100, 300, 600);
		assertEquals(1, lost.size());
		assertFalse(first.beaten);
		assertEquals(1, lost.beatenCases().size());
		assertFalse(lost.allBeaten());
		assertNull(lost.pick(rng, 4));
	}
''')])

edit('src/client/java/dev/swordai/client/SwordFiles.java', [(
    '''						lost.beatenEver = f.beatenEver;''', '''						if (f.beaten != null) {
							for (dev.swordai.core.curriculum.LostCases.Case c : f.beaten) {
								if (c != null && c.bot != null) {
									lost.keepBeaten(c);
								}
							}
						}
						lost.beatenEver = f.beatenEver;'''), (
    '''		f.beatenEver = lost.beatenEver;
		try {''', '''		f.beaten = new ArrayList<>(lost.beatenCases());
		f.beatenEver = lost.beatenEver;
		try {'''), (
    '''		List<dev.swordai.core.curriculum.LostCases.Case> cases;
		int beatenEver;''', '''		List<dev.swordai.core.curriculum.LostCases.Case> cases;
		List<dev.swordai.core.curriculum.LostCases.Case> beaten;
		int beatenEver;''')])

edit('src/client/java/dev/swordai/client/SwordAiClient.java', [(
    '''				mc.player.connection.sendCommand(command);
			}
		}
	}''', '''				mc.player.connection.sendCommand(command);
			} else if (!command.isEmpty() && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				// The development client only: any command, so a script can set up a test (a Carpet fake player to fight).
				dev.swordai.SwordAi.LOGGER.info("inbox (development): /{}", command);
				mc.player.connection.sendCommand(command);
			}
		}
	}''')])

edit('gradle.properties', [('mod_version=0.15.2', 'mod_version=0.15.3')])
