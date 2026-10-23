ROOT = 'D:/swordai/'
p = ROOT + 'core/src/main/java/dev/swordai/core/rl/StepBack.java'
s = open(p, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    assert s.count(a) == n, (a[:70], s.count(a))
    s = s.replace(a, b)


rep('''	/**
	 * Each such answer counts for this long, ten seconds, and it takes two that still count for the
	 * opponent to be one that answers its hits ({@link #WARY_FROM}): one that does it once in twenty
	 * seconds, as anyone who is fighting back will, is not.
	 */
	static final int ANSWER_COUNTS_TICKS = 200;
	static final int WARY_FROM = 300;
	static final int WARY_MOST = 600;
''', '''	/**
	 * Each such answer counts for this long, ten seconds, and it takes three within about seven
	 * seconds for the opponent to be one that answers its hits ({@link #WARY_FROM}): one that does it
	 * now and then, as anyone who is fighting back will, is not. (Two were enough at first. The
	 * opponent that keeps running out trades a hit or two each time it is caught, and was then
	 * stepped back from while it ran: 47 hits landed on it a minute, where it is 61 without this
	 * rule.)
	 */
	static final int ANSWER_COUNTS_TICKS = 200;
	static final int WARY_FROM = 450;
	static final int WARY_MOST = 700;
	/** Still going away at this much a tick four ticks after the hit, it is not thrown but running: after it. */
	static final float RUNNING = 0.25f;
	static final int RUNNING_FROM_TICK = 4;
''')
rep('''		if (sinceDealt >= JUDGE_FROM_TICK && sinceDealt <= JUDGE_TO_TICK) {
			float away = (scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
''', '''		float away = (scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;
		if (on && sinceDealt >= RUNNING_FROM_TICK && away >= RUNNING) {
			sinceDealt = -1;
			on = false;
			return 0;
		}
		if (sinceDealt >= JUDGE_FROM_TICK && sinceDealt <= JUDGE_TO_TICK) {
''')
rep(''' * ticks after a hit of its own, with its sword on the way back, twice within ten seconds. From then''',
    ''' * ticks after a hit of its own, with its sword on the way back, three times within seven seconds. From then''')
open(p, 'w', encoding='utf-8', newline='').write(s)

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
rep('''		// Hit six ticks after a hit of its own, once: anyone who fights back does that now and then.
		answered(rule, 6);
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		// And again within ten seconds: this one answers its hits.
		answered(rule, 6);
		assertTrue(rule.wary());''', '''		// Hit six ticks after a hit of its own, once or twice: anyone who fights back does that now and then.
		answered(rule, 6);
		answered(rule, 6);
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		// And a third time within a few seconds: this one answers its hits.
		answered(rule, 6);
		assertTrue(rule.wary());''')
rep('''		assertEquals(0, rule.plan(after(vec(true, 4.0f, 0.24f), 3, 1000)));
		// Hit while it is stepping back: that is over, and the answer has rules of its own.''', '''		assertEquals(0, rule.plan(after(vec(true, 4.0f, 0.24f), 3, 1000)));
		// Nor does it back away from one that is running from it.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		for (int tick = 1; tick <= 3; tick++) {
			assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f + 0.1f * tick, 0.08f * tick), tick, 1000)));
		}
		float[] runs = after(vec(true, 3.2f, 0.32f), 4, 1000);
		runs[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.3f * BodyState.VELOCITY_SCALE;
		assertEquals(0, rule.plan(runs));
		assertFalse(rule.on());
		// Hit while it is stepping back: that is over, and the answer has rules of its own.''')
rep('''		// Another opponent: nothing is known of it.
		answered(rule, 6);
		answered(rule, 6);
		assertTrue(rule.wary());''', '''		// Another opponent: nothing is known of it.
		answered(rule, 6);
		answered(rule, 6);
		answered(rule, 6);
		assertTrue(rule.wary());''')
rep('''		for (int tick = 1; tick < 700; tick++) {
			rule.plan(after(vec(true, 6.0f, 1f), 100, Math.min(tick, 100)));
		}''', '''		for (int tick = 1; tick < 800; tick++) {
			rule.plan(after(vec(true, 6.0f, 1f), 100, Math.min(tick, 100)));
		}''')
open(p, 'w', encoding='utf-8', newline='').write(s)
for path, a, b in (
    ('core/src/main/java/dev/swordai/core/config/Config.java',
     "	 * Against an opponent that answers its hits while its sword is on the way back (twice within ten\n	 * seconds), it walks",
     "	 * Against an opponent that answers its hits while its sword is on the way back (three times within\n	 * seven seconds), it walks"),
):
    full = ROOT + path
    t = open(full, encoding='utf-8').read()
    assert t.count(a) == 1, (path, a[:60])
    t = t.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(t)
print('heat2 ok')
