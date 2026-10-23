ROOT = 'D:/swordai/'
p = ROOT + 'core/src/main/java/dev/swordai/core/rl/StepBack.java'
s = open(p, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    assert s.count(a) == n, (a[:70], s.count(a))
    s = s.replace(a, b)


rep('''	/** And for this long after, it steps back from every hit at once. */
	static final int WARY_TICKS = 400;
''', '''	/**
	 * Each such answer counts for this long, ten seconds, and it takes two that still count for the
	 * opponent to be one that answers its hits ({@link #WARY_FROM}): one that does it once in twenty
	 * seconds, as anyone who is fighting back will, is not.
	 */
	static final int ANSWER_COUNTS_TICKS = 200;
	static final int WARY_FROM = 300;
	static final int WARY_MOST = 600;
	/**
	 * Whether it also steps back on what it can read of a hit, against an opponent it knows nothing
	 * of. Off: measured against the opponent that keeps running out, with the network not learning,
	 * that was 43 hits landed a minute for 61 without, and as many taken.
	 */
	static final boolean READ = false;
''')
rep('''			if (dealtAge >= ANSWERED_FROM_TICK && dealtAge <= ANSWERED_TO_TICK) {
				// With its sword on the way back from a hit of its own: this opponent answers its hits.
				wary = WARY_TICKS;
			}''', '''			if (dealtAge >= ANSWERED_FROM_TICK && dealtAge <= ANSWERED_TO_TICK) {
				// With its sword on the way back from a hit of its own: an answer to that hit.
				wary = Math.min(WARY_MOST, wary + ANSWER_COUNTS_TICKS);
			}''')
rep('''	public boolean wary() {
		return wary > 0;
	}''', '''	public boolean wary() {
		return wary >= WARY_FROM;
	}''')
rep('''			on = wary > 0 && d <= IN;
			return on ? BACK : 0;''', '''			on = wary() && d <= IN;
			return on ? BACK : 0;''')
rep('''			if (on && wary > 0 && away > AWAY_MOST && d > IN) {''', '''			if (on && wary() && away > AWAY_MOST && d > IN) {''')
rep('''			if (!on && unhit <= OPPONENT_HITS_BACK_TICKS && d <= IN) {''', '''			if (READ && !on && unhit <= OPPONENT_HITS_BACK_TICKS && d <= IN) {''')
rep(''' * <p>Whether a hit has sent the opponent away is read two to five ticks after it landed: still
 * within {@link #IN} and not going away at more than {@link #AWAY_MOST}, it has not. Only against an
 * opponent that has hit the fighter in the last five seconds; one that only stands there is jumped
 * at ({@link JumpIn}).
 *
 * <p>An opponent that has answered one of its hits (hit it three to thirteen ticks after one, with
 * its sword on the way back) is one that does ({@link #wary}): for the next twenty seconds it steps
 * back from every hit on the tick it lands, before there is anything to read, and goes after the
 * opponent again if the hit turns out to have sent it flying. Read at leisure, the step back begins
 * two or three ticks in, and an opponent that is hardly thrown up at all is down again and
 * swinging by then.
 *''', ''' * <p>Which opponent that is, it finds out by being hit so ({@link #wary}): hit three to thirteen
 * ticks after a hit of its own, with its sword on the way back, twice within ten seconds. From then
 * on, for as long as that goes on, it steps back from every hit on the tick it lands, before there
 * is anything to read, and goes after the opponent again if the hit turns out to have sent it
 * flying. Against anyone else it does not step back at all: that costs the ticks its own combos,
 * and its chase of an opponent that runs, are made of.
 *''')
open(p, 'w', encoding='utf-8', newline='').write(s)

# The test: the reading half is off, and wary takes two answers.
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
i = s.index('	/** After a hit of its own that has not sent the opponent away it steps back until its sword is nearly back. */')
j = s.index('	/** An opponent on its way up and coming, with the sword back and its feet on the ground: it is run at and hit. */')
new = '''	/** Its own hit lands, and so many ticks later it is hit: an answer to that hit. */
	private static void answered(StepBack rule, int ticksLater) {
		rule.plan(after(vec(true, 2.6f, 0f), 0, 100));
		for (int tick = 1; tick < ticksLater; tick++) {
			rule.plan(after(vec(true, 2.8f, 0.08f * tick), tick, 100 + tick));
		}
		rule.plan(after(vec(true, 2.8f, 0.08f * ticksLater), ticksLater, 0));
		for (int tick = 1; tick < 20; tick++) {
			rule.plan(after(vec(true, 3.0f, 1f), 100, tick));
		}
	}

	/**
	 * From an opponent that answers its hits while its sword is on the way back, it steps back after
	 * each hit of its own until the sword is nearly back. Against anyone else it does not.
	 */
	@Test
	void fromAnOpponentThatAnswersItsHitsItStepsBack() {
		StepBack rule = new StepBack();
		// Its own hit lands, and the opponent stays where it is: nothing, for nothing is known of it.
		assertEquals(0, rule.plan(after(vec(true, 2.6f, 0f), 0, 5)));
		for (int tick = 1; tick <= 12; tick++) {
			assertEquals(0, rule.plan(after(vec(true, 2.8f, 0.08f * tick), tick, 5 + tick)), "tick " + tick);
		}
		// Hit on the tick after its own hit: a trade, and nothing learned.
		answered(rule, 1);
		answered(rule, 1);
		assertFalse(rule.wary());
		// Hit six ticks after a hit of its own, once: anyone who fights back does that now and then.
		answered(rule, 6);
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		// And again within ten seconds: this one answers its hits.
		answered(rule, 6);
		assertTrue(rule.wary());
		// From the next hit it lands it is on its way out at once.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		assertTrue(rule.on());
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.8f, 0.08f), 1, 31)));
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.3f, 0.16f), 2, 32)));
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.8f, 0.4f), 5, 35)));
		// Out of its reach: it stays where it is, and does not come in.
		assertEquals(StepBack.STAND, rule.plan(after(vec(true, 4.2f, 0.5f), 6, 36)));
		assertEquals(0, rule.plan(after(vec(true, 4.8f, 0.6f), 7, 37)), "far enough off its keys are its own");
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.8f, 0.7f), 8, 38)), "and back again if the opponent comes after it");
		// The sword nearly back: in it goes.
		assertEquals(0, rule.plan(after(vec(true, 3.8f, 0.85f), 10, 40)));
		assertFalse(rule.on());
		assertEquals(0, rule.plan(after(vec(true, 3.0f, 0.9f), 11, 41)));
		// A new round against the same opponent: still wary.
		rule.newRound();
		assertTrue(rule.wary());
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		// If the hit turns out to have sent the opponent flying, it goes after it.
		rule.plan(after(vec(true, 3.0f, 0.08f), 1, 1000));
		float[] flying = after(vec(true, 3.7f, 0.16f), 2, 1000);
		flying[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.6f * BodyState.VELOCITY_SCALE;
		assertEquals(0, rule.plan(flying));
		assertFalse(rule.on());
		assertEquals(0, rule.plan(after(vec(true, 4.0f, 0.24f), 3, 1000)));
		// Hit while it is stepping back: that is over, and the answer has rules of its own.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.8f, 0.08f), 1, 1000)));
		assertEquals(0, rule.plan(after(vec(true, 2.8f, 0.16f), 2, 0)));
		assertFalse(rule.on());
		// Ten seconds and more without another answer, and it is no longer taken for one that does.
		for (int tick = 1; tick < 700; tick++) {
			rule.plan(after(vec(true, 6.0f, 1f), 100, Math.min(tick, 100)));
		}
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		// Another opponent: nothing is known of it.
		answered(rule, 6);
		answered(rule, 6);
		assertTrue(rule.wary());
		rule.reset();
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
	}

'''
s = s[:i] + new + s[j:]
open(p, 'w', encoding='utf-8', newline='').write(s)
for path, a, b in (
    ('src/client/java/dev/swordai/client/Report.java',
     '"after a hit of its own that has not sent the opponent away it steps back until its sword is back (stepBackRule)"',
     '"from an opponent that keeps answering its hits while its sword is on the way back it steps back after each hit until the sword is back (stepBackRule)"'),
    ('core/src/main/java/dev/swordai/core/config/Config.java',
     """	 * After a hit of its own that has not sent the opponent away (part of the knockback, or none), and
	 * after every hit against an opponent that has lately answered one, it walks backwards out of that
	 * opponent's reach until its sword is nearly back; see {@code StepBack}. What it keeps clear of is
	 * the critical hit such an opponent makes on its way down, which there is no swing to answer with.""",
     """	 * Against an opponent that answers its hits while its sword is on the way back (twice within ten
	 * seconds), it walks backwards out of that opponent's reach after each hit of its own until its
	 * sword is nearly back; see {@code StepBack}. What it keeps clear of is the critical hit such an
	 * opponent makes on its way down, which there is no swing to answer with."""),
):
    full = ROOT + path
    t = open(full, encoding='utf-8').read()
    assert t.count(a) == 1, (path, a[:60])
    t = t.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(t)
print('heat ok')
