ROOT = 'D:/swordai/'

p = ROOT + 'core/src/main/java/dev/swordai/core/rl/StepBack.java'
s = open(p, encoding='utf-8').read()
a = 'static final float FALLING = -0.03f;'
assert s.count(a) == 1
s = s.replace(a, 'static final float FALLING = -0.06f;')
open(p, 'w', encoding='utf-8', newline='').write(s)

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
i = s.index('	/** After a hit of its own that has not sent the opponent away it steps back until its sword is nearly back. */')
j = s.index('''	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {''')
new = '''	/** The opponent as the fighter sees it while it is coming down. */
	private static float[] falling(float[] v) {
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = -0.2f * BodyState.VELOCITY_SCALE;
		return v;
	}

	/**
	 * Against an opponent that has answered one of its hits with a hit made on the way down, it steps
	 * back from every hit of its own until its sword is nearly back. Against anyone else it does not.
	 */
	@Test
	void fromAnOpponentThatAnswersItsHitsOnTheWayDownItStepsBack() {
		StepBack rule = new StepBack();
		// Its own hit lands, and the opponent stays where it is: nothing, as long as nothing has come of that.
		assertEquals(0, rule.plan(after(vec(true, 2.6f, 0f), 0, 100)));
		for (int tick = 1; tick <= 12; tick++) {
			assertEquals(0, rule.plan(after(vec(true, 2.8f, 0.08f * tick), tick, 100 + tick)), "tick " + tick);
		}
		assertFalse(rule.wary());
		// Hit a moment after a hit of its own by an opponent that only stood there: a trade, and nothing learned.
		rule.plan(after(vec(true, 2.6f, 0f), 0, 100));
		for (int tick = 1; tick <= 5; tick++) {
			rule.plan(after(vec(true, 2.8f, 0.08f * tick), tick, 100 + tick));
		}
		assertEquals(0, rule.plan(after(vec(true, 2.8f, 0.5f), 6, 0)));
		assertFalse(rule.wary());
		// Hit six ticks after a hit of its own by an opponent that was coming down: that one answers its hits.
		for (int tick = 1; tick < 30; tick++) {
			rule.plan(after(vec(true, 3.0f, 1f), 100, tick));
		}
		rule.plan(after(vec(true, 2.6f, 0f), 0, 30));
		for (int tick = 1; tick <= 5; tick++) {
			assertEquals(0, rule.plan(falling(after(vec(true, 2.8f, 0.08f * tick), tick, 30 + tick))));
		}
		assertEquals(0, rule.plan(falling(after(vec(true, 2.8f, 0.5f), 6, 0))));
		assertTrue(rule.wary());
		// From the next hit it lands it is on its way out at once.
		for (int tick = 1; tick < 30; tick++) {
			assertEquals(0, rule.plan(after(vec(true, 3.0f, 1f), 100, tick)));
		}
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
		// Another opponent: nothing is known of it. And twenty seconds on, nothing of this one either.
		rule.reset();
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
	}

'''
s = s[:i] + new + s[j:]
open(p, 'w', encoding='utf-8', newline='').write(s)

T = ROOT + 'src/client/java/dev/swordai/client/Trainer.java'
s = open(T, encoding='utf-8').read()
a = '''		int step = cfg.stepBackRule && !episodeLockedDrill && stage.unlocked(ActionSpace.JUMP) ? stepBack.plan(obs.vec) : 0;
'''
assert s.count(a) == 1
s = s.replace(a, '''		boolean wasWary = stepBack.wary();
		int step = cfg.stepBackRule && !episodeLockedDrill && stage.unlocked(ActionSpace.JUMP) ? stepBack.plan(obs.vec) : 0;
		if (stepBack.wary() && !wasWary) {
			waryBegan++;
		}
''')
a = '''	private int hitNowSame;
'''
assert s.count(a) == 1
s = s.replace(a, '''	/** How many times it found an opponent to be one that answers its hits on the way down; see {@link StepBack#wary}. */
	private int waryBegan;
	private int hitNowSame;
''')
a = '''its own game learned of a hit: on the tick it was shown {}, later {}, without {}",'''
assert s.count(a) == 1
s = s.replace(a, '''its own game learned of a hit: on the tick it was shown {}, later {}, without {} | found to answer its hits on the way down {} times",''')
a = '''chaseEndFar, hitNowSame, hitNowLater, hitNowAlone);
		hitNowSame = 0;'''
assert s.count(a) == 1
s = s.replace(a, '''chaseEndFar, hitNowSame, hitNowLater, hitNowAlone, waryBegan);
		waryBegan = 0;
		hitNowSame = 0;''')
open(T, 'w', encoding='utf-8', newline='').write(s)

R = ROOT + 'src/client/java/dev/swordai/client/Report.java'
s = open(R, encoding='utf-8').read()
a = '''add(on, cfg.stepBackRule, "after a hit of its own that has not sent the opponent away it steps back until its sword is back (stepBackRule)");'''
assert s.count(a) == 1
s = s.replace(a, '''add(on, cfg.stepBackRule, "from an opponent that has answered one of its hits with a critical hit on the way down it steps back after each hit until its sword is back (stepBackRule)");''')
open(R, 'w', encoding='utf-8', newline='').write(s)
C = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(C, encoding='utf-8').read()
a = '''	 * After a hit of its own that has not sent the opponent away (part of the knockback, or none), it
	 * walks backwards out of that opponent's reach until its sword is nearly back; see
	 * {@code StepBack}. What it keeps clear of is the critical hit such an opponent makes on its way
	 * down, which there is no swing to answer with.'''
assert s.count(a) == 1
s = s.replace(a, '''	 * Against an opponent that has answered one of its hits with a critical hit made on the way down
	 * (in the last twenty seconds), it walks backwards out of that opponent's reach after each hit of
	 * its own until its sword is nearly back; see {@code StepBack}. What it keeps clear of is that
	 * critical hit, which there is no swing to answer with.''')
open(C, 'w', encoding='utf-8', newline='').write(s)
print('wary2 ok')
