ROOT = 'D:/swordai/'

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
i = s.index('	/** The opponent as the fighter sees it while it is coming down. */')
j = s.index('''	/** An opponent on its way up and coming, with the sword back and its feet on the ground: it is run at and hit. */''')
new = '''	/** After a hit of its own that has not sent the opponent away it steps back until its sword is nearly back. */
	@Test
	void afterAHitThatDidNotSendTheOpponentAwayItStepsBack() {
		StepBack rule = new StepBack();
		// It has been hit a second ago: an opponent that hits back.
		assertEquals(0, rule.plan(after(vec(true, 2.5f, 1f), 100, 0)));
		for (int tick = 1; tick < 20; tick++) {
			assertEquals(0, rule.plan(after(vec(true, 2.5f, 1f), 100, tick)));
		}
		// Its own hit lands.
		assertEquals(0, rule.plan(after(vec(true, 2.6f, 0f), 0, 20)));
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0.08f), 1, 21)), "too soon to say");
		// Two ticks on the opponent is still within a step of its reach and hardly moving: back.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.9f, 0.16f), 2, 22)));
		assertTrue(rule.on());
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.6f, 0.3f), 4, 24)));
		// Out of its reach: it stays where it is, and does not come in.
		assertEquals(StepBack.STAND, rule.plan(after(vec(true, 4.2f, 0.5f), 6, 26)));
		assertEquals(0, rule.plan(after(vec(true, 4.8f, 0.6f), 7, 27)), "far enough off its keys are its own");
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.8f, 0.7f), 8, 28)), "and back again if the opponent comes after it");
		// The sword nearly back: in it goes.
		assertEquals(0, rule.plan(after(vec(true, 3.8f, 0.85f), 10, 30)));
		assertFalse(rule.on());
		assertEquals(0, rule.plan(after(vec(true, 3.0f, 0.9f), 11, 31)));
		assertFalse(rule.wary());

		// An opponent the hit has sent flying: nothing.
		StepBack sent = new StepBack();
		sent.plan(after(vec(true, 2.5f, 1f), 100, 0));
		sent.plan(after(vec(true, 2.6f, 0f), 0, 5));
		for (int tick = 1; tick <= 10; tick++) {
			float[] v = after(vec(true, 2.6f + 0.45f * tick, 0.08f * tick), tick, 5 + tick);
			assertEquals(0, sent.plan(v), "tick " + tick);
		}
		// One that is near but going away fast (hit from very close): nothing either.
		StepBack close = new StepBack();
		close.plan(after(vec(true, 2.0f, 1f), 100, 0));
		close.plan(after(vec(true, 1.8f, 0f), 0, 5));
		close.plan(after(vec(true, 2.3f, 0.08f), 1, 6));
		float[] fast = after(vec(true, 2.9f, 0.16f), 2, 7);
		fast[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.55f * BodyState.VELOCITY_SCALE;
		assertEquals(0, close.plan(fast));
		// And one that has not hit it for five seconds and more: that one is jumped at, not stepped back from.
		StepBack idle = new StepBack();
		idle.plan(after(vec(true, 2.6f, 0f), 0, 1000));
		idle.plan(after(vec(true, 2.7f, 0.08f), 1, 1000));
		assertEquals(0, idle.plan(after(vec(true, 2.8f, 0.16f), 2, 1000)));
		// Hit while it is stepping back: that is over, and the answer has rules of its own.
		StepBack hit = new StepBack();
		hit.plan(after(vec(true, 2.5f, 1f), 100, 0));
		hit.plan(after(vec(true, 2.6f, 0f), 0, 5));
		hit.plan(after(vec(true, 2.7f, 0.08f), 1, 6));
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.8f, 0.16f), 2, 7)));
		assertEquals(0, hit.plan(after(vec(true, 2.8f, 0.24f), 3, 0)));
		assertFalse(hit.on());
		assertEquals(0, hit.plan(after(vec(true, 2.8f, 0.3f), 4, 1)));
		// And that was an opponent answering its hit: from the next hit it lands it is on its way out
		// at once, before there is anything to read.
		assertTrue(hit.wary());
		for (int tick = 2; tick < 30; tick++) {
			hit.plan(after(vec(true, 3.0f, 1f), 100, tick));
		}
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.8f, 0.08f), 1, 31)));
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 3.3f, 0.16f), 2, 32)));
		// A new round against the same opponent: still wary. Another opponent: not.
		hit.newRound();
		assertTrue(hit.wary());
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		// If the hit turns out to have sent the opponent flying, it goes after it.
		hit.plan(after(vec(true, 3.0f, 0.08f), 1, 1000));
		float[] flying = after(vec(true, 3.7f, 0.16f), 2, 1000);
		flying[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.6f * BodyState.VELOCITY_SCALE;
		assertEquals(0, hit.plan(flying));
		assertFalse(hit.on());
		assertEquals(0, hit.plan(after(vec(true, 4.0f, 0.24f), 3, 1000)));
		hit.reset();
		assertFalse(hit.wary());
		assertEquals(0, hit.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
	}

'''
s = s[:i] + new + s[j:]
open(p, 'w', encoding='utf-8', newline='').write(s)

for path, a, b in (
    ('src/client/java/dev/swordai/client/Report.java',
     '''"from an opponent that has answered one of its hits with a critical hit on the way down it steps back after each hit until its sword is back (stepBackRule)"''',
     '''"after a hit of its own that has not sent the opponent away it steps back until its sword is back (stepBackRule)"'''),
    ('core/src/main/java/dev/swordai/core/config/Config.java',
     '''	 * Against an opponent that has answered one of its hits with a critical hit made on the way down
	 * (in the last twenty seconds), it walks backwards out of that opponent's reach after each hit of
	 * its own until its sword is nearly back; see {@code StepBack}. What it keeps clear of is that
	 * critical hit, which there is no swing to answer with.''',
     '''	 * After a hit of its own that has not sent the opponent away (part of the knockback, or none), and
	 * after every hit against an opponent that has lately answered one, it walks backwards out of that
	 * opponent's reach until its sword is nearly back; see {@code StepBack}. What it keeps clear of is
	 * the critical hit such an opponent makes on its way down, which there is no swing to answer with.'''),
    ('src/client/java/dev/swordai/client/Trainer.java',
     '''found to answer its hits on the way down {} times |''', '''found to answer its hits {} times |'''),
):
    full = ROOT + path
    t = open(full, encoding='utf-8').read()
    assert t.count(a) == 1, (path, a[:60])
    t = t.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(t)
print('step3 ok')
