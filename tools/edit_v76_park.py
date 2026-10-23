"""20 Oct 06:50: lost fights it never wins are parked (a tenth of the priority, last to be picked as stalest, first to
make room); the list is 200, not 400. Overnight the no-knockback (246 replays, 0 wins), trader (134, 0) and punisher
(109, 1) fights took a large share of the replays, and the frozen test showed it dealing less to the hardest kinds
(spacer 414 vs 489 a minute, punisher 546 vs 716): fights it cannot win teach it to give up."""
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
    '''	/** At most this many are kept; past that the one nearest to being beaten makes room. */
	public static final int MOST = 400;''', '''	/**
	 * At most this many are kept; past that a parked one makes room, else the mildest loss. 400 left each
	 * one fought again only every two hours or so (20 October).
	 */
	public static final int MOST = 200;
	/**
	 * Fought again this many times and never won: parked. It stays on the list, but comes up a tenth as
	 * often, last as the one waited longest, and first to make room. On 19-20 October fights it could not
	 * win (no-knockback 246 replays and none won) took the replays, and it learned to give up against the
	 * hardest kinds: less damage dealt to the spacer and the punisher in the frozen test.
	 */
	public static final int PARK_REPLAYS = 6;
	static final float PARKED_WEIGHT = 0.1f;'''), (
    '''		float priority() {''', '''		boolean parked() {
			return replays >= PARK_REPLAYS && wins == 0;
		}

		/** How likely it is picked: the worse it went the likelier, a parked one a tenth as likely. */
		float weight() {
			return parked() ? PARKED_WEIGHT * priority() : priority();
		}

		float priority() {'''), (
    '''	/** The kept fight of this stage picked longest ago (never picked: the first kept). */
	private Case pickStalest(int stage) {
		Case best = null;
		for (Case c : cases) {
			if (c.stage == stage && (best == null || c.lastPicked < best.lastPicked)) {
				best = c;
			}
		}
		return best;
	}''', '''	/** The kept fight of this stage picked longest ago (never picked: the first kept), a parked one last. */
	private Case pickStalest(int stage) {
		Case best = null;
		for (Case c : cases) {
			if (c.stage == stage && (best == null || c.parked() == best.parked() && c.lastPicked < best.lastPicked
					|| best.parked() && !c.parked())) {
				best = c;
			}
		}
		return best;
	}'''), (
    '''				total += c.priority();''', '''				total += c.weight();'''), (
    '''			at -= c.priority();''', '''			at -= c.weight();''')])

# makeRoom: a parked one first (the one fought most), else the mildest loss.
p = ROOT + LC
s = io.open(p, encoding='utf-8').read()
start = s.index('	/** The one to let go of when the list is full')
end = s.index('	}\n', s.index('private int makeRoom()')) + 3
new = '''	/** The one to let go of when the list is full: a parked one (the one fought most), else the mildest loss. */
	private int makeRoom() {
		int best = 0;
		for (int i = 1; i < cases.size(); i++) {
			Case a = cases.get(i);
			Case b = cases.get(best);
			if (a.parked() != b.parked() ? a.parked()
					: a.parked() ? a.replays > b.replays : a.priority() < b.priority()) {
				best = i;
			}
		}
		return best;
	}

	/** Brings a list read from a file down to {@link #MOST}. */
	public void trim() {
		while (cases.size() > MOST) {
			cases.remove(makeRoom());
		}
	}

	/** How many are parked; see {@link #PARK_REPLAYS}. */
	public int parked() {
		int n = 0;
		for (Case c : cases) {
			if (c.parked()) {
				n++;
			}
		}
		return n;
	}
'''
s = s[:start] + new + s[end:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('makeRoom replaced')

edit(LC, [(
    '''		String line = String.format(Locale.ROOT, "lost fights kept %d (%s), new %d, replayed %d of which won %d, beaten for good %d (%d in all); "''',
    '''		String line = String.format(Locale.ROOT, "lost fights kept %d (%s; parked %d), new %d, replayed %d of which won %d, beaten for good %d (%d in all); "'''), (
    '''				cases.size(), byKind(name), added, replayed''', '''				cases.size(), byKind(name), parked(), added, replayed''')])

edit('core/src/test/java/dev/swordai/core/curriculum/LostCasesTest.java', [(
    '''	@Test
	void aFullListMakesRoomWithTheOneNearestToBeingBeaten() {
		LostCases lost = new LostCases();
		LostCases.Case almost = setUp(7, 1);
		lost.freshFight(almost, 200, 900, 1200);
		lost.replayed(almost, 300, 200, 1200);
		for (int i = 1; i < LostCases.MOST; i++) {
			lost.freshFight(setUp(7, 1), 200, 300, 1200);
		}
		assertEquals(LostCases.MOST, lost.size());
		lost.freshFight(setUp(7, 1), 200, 300, 1200);
		assertEquals(LostCases.MOST, lost.size());
		assertFalse(lost.cases().contains(almost));
	}''', '''	@Test
	void aFullListMakesRoomWithAParkedOneElseTheMildestLoss() {
		LostCases lost = new LostCases();
		LostCases.Case hopeless = setUp(7, 5);
		lost.freshFight(hopeless, 0, 900, 1200);
		for (int i = 0; i < LostCases.PARK_REPLAYS; i++) {
			lost.replayed(hopeless, 0, 900, 1200);
		}
		assertTrue(hopeless.parked());
		LostCases.Case mild = setUp(7, 1);
		lost.freshFight(mild, 299, 300, 1200);
		for (int i = 2; i < LostCases.MOST; i++) {
			lost.freshFight(setUp(7, 1), 200, 300, 1200);
		}
		assertEquals(LostCases.MOST, lost.size());
		lost.freshFight(setUp(7, 1), 200, 300, 1200);
		assertEquals(LostCases.MOST, lost.size());
		assertFalse(lost.cases().contains(hopeless), "parked goes first");
		lost.freshFight(setUp(7, 1), 200, 300, 1200);
		assertFalse(lost.cases().contains(mild), "then the mildest loss");
		assertEquals(0, lost.parked());
	}

	@Test
	void aFightNeverWonIsParkedAndComesUpLess() {
		LostCases lost = new LostCases();
		LostCases.Case hopeless = setUp(7, 5);
		LostCases.Case other = setUp(7, 12);
		lost.freshFight(hopeless, 0, 600, 1200);
		lost.freshFight(other, 0, 600, 1200);
		for (int i = 0; i < LostCases.PARK_REPLAYS; i++) {
			lost.replayed(hopeless, 0, 600, 1200);
			lost.replayed(other, 0, 600, 1200);
		}
		lost.replayed(other, 600, 0, 1200);
		assertTrue(hopeless.parked());
		assertFalse(other.parked(), "won once");
		Random rng = new Random(2);
		int hopelessPicks = 0;
		for (int i = 0; i < 1000; i++) {
			if (lost.pick(rng, 7) == hopeless) {
				hopelessPicks++;
			}
		}
		assertTrue(hopelessPicks < 200, "picked " + hopelessPicks);
	}''')])

edit('src/client/java/dev/swordai/client/SwordFiles.java', [(
    '''							if (c != null && c.bot != null && lost.size() < dev.swordai.core.curriculum.LostCases.MOST) {
								lost.cases().add(c);
							}''', '''							if (c != null && c.bot != null) {
								lost.cases().add(c);
							}'''), (
    '''					lost.beatenEver = f.beatenEver;''', '''					lost.trim();
					lost.beatenEver = f.beatenEver;''')])
