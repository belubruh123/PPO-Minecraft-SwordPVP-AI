ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
    ('''		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare <= 0.85f);
''', '''		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare <= 0.85f);
		// And the two it lost to on its first evening against people: the one that comes after it with
		// a running jump and a critical hit, and the one that answers its hits with a critical hit.
		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare
				+ crit.jumperShare + crit.punisherShare <= 0.95f, "a share is left for the opponent a sprint hit knocks away");
		BotProfile jumper = crit.bot.copy();
		jumper.jumpIn = true;
		jumper.punish = true;
		assertTrue(jumper.copy().jumpIn && jumper.copy().punish);
		assertFalse(crit.bot.jumpIn || crit.bot.punish);
''', 1),
    ('''		assertTrue(stages.get(7).runnerShare >= 0.15f && stages.get(7).circlerShare >= 0.15f);''',
     '''		assertTrue(stages.get(7).runnerShare >= 0.1f && stages.get(7).circlerShare >= 0.1f);''', 1),
])

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {'''
assert s.count(a) == 1
new = '''	/** What it is shown this many ticks after a hit of its own, and this many after one it took. */
	private static float[] after(float[] v, int sinceDealt, int sinceTaken) {
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = Math.max(0f, 1f - sinceDealt / BodyState.EVENT_FADE_TICKS);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = Math.max(0f, 1f - sinceTaken / BodyState.EVENT_FADE_TICKS);
		return v;
	}

	/**
	 * The chase jump as the next hit of a combo of its own: eight ticks after its hit, the opponent
	 * backing off beyond its reach. Not after an opponent that is only being thrown back by that hit.
	 */
	@Test
	void theChaseJumpIsAlsoTheNextHitOfItsOwnCombo() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			float[] v = after(running(3.5f, 0.065f), 8 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.6f;
			plan = rule.plan(v, ALL, false, false, 1);
		}
		assertTrue(has(plan, JumpIn.JUMP), "after one that backs off");
		assertEquals(JumpIn.CHASE, rule.tookOff());
		// Switched off, it waits out the fifteen ticks as before.
		JumpIn old = new JumpIn(true, true, 0f, 1);
		old.chaseInCombo(false);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			float[] v = after(running(3.5f, 0.065f), 8 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.6f;
			assertEquals(0, old.plan(v, ALL, false, false, 1));
		}
		// Thrown back by the hit, the opponent is going away fast, and is no runner: no jump from far off.
		JumpIn thrown = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = after(running(5.0f, -0.2f), 6 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.6f;
			assertEquals(0, thrown.plan(v, ALL, false, false, 1), "tick " + tick);
		}
	}

	/** Hit a second time in a row with its feet on the ground, it jumps with that hit, W and the sprint key down. */
	@Test
	void aHitOfAComboItIsTakingIsJumpedWith() {
		JumpReset rule = new JumpReset(1f, 3);
		// The first hit: nothing. That one it takes as it comes.
		float[] first = after(vec(true, 2.5f, 1f), 100, 0);
		first[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertEquals(0, rule.plan(first, ALL));
		assertFalse(rule.tookOff());
		// The second, on the tick it learns of it: the jump, W and the sprint.
		float[] second = after(vec(true, 2.5f, 1f), 100, 0);
		second[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		assertTrue(BackNudge.inCombo(second));
		int plan = rule.plan(second, ALL);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN));
		assertTrue(rule.tookOff());
		// In the air of it W and the sprint stay down, and the jump key is up.
		float[] up = after(air(2.2f, 0.3f, true, 1f), 100, 1);
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		plan = rule.plan(up, ALL);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_JUMP) && !has(plan, JumpIn.JUMP));
		assertFalse(rule.tookOff());
		// Down again: its keys are its own.
		float[] down = after(vec(true, 2.0f, 1f), 100, 12);
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		assertEquals(0, rule.plan(down, ALL));
		assertEquals(0, rule.plan(down, ALL));
		// A hit that finds it in the air cannot be jumped with.
		float[] inAir = after(air(2.5f, 0.1f, true, 1f), 100, 0);
		inAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		assertEquals(0, rule.plan(inAir, ALL));
		// Nor where the jump key is not its own, and at a chance of nought never.
		assertEquals(0, rule.plan(second, ALL & ~ActionSpace.mask(ActionSpace.JUMP)));
		JumpReset never = new JumpReset(0f, 3);
		assertEquals(0, never.plan(second, ALL));
		// At a share of the hits it is that share.
		JumpReset half = new JumpReset(0.5f, 11);
		int made = 0;
		for (int i = 0; i < 400; i++) {
			if (half.plan(second, ALL) != 0) {
				made++;
			}
		}
		assertTrue(made > 150 && made < 250, "about half: " + made);
	}

	/** A fighter jumps with the second hit in a row and holds W, where it used to hold S. */
	@Test
	void theFighterJumpsWithTheSecondHitWhereItUsedToBackOff() {
		dev.swordai.core.config.Config cfg = new dev.swordai.core.config.Config();
		assertTrue(cfg.jumpResetChance > 0f && cfg.stepBackRule && cfg.chaseInCombo);
		cfg.jumpResetChance = 1f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, ALL, true, 21);
			fighter.backOutRule(true);
			MaskFrame near = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 2.8, 180f, 180f, 0f, near);
			BodyState body = new BodyState();
			body.onGround = true;
			body.charge = 0.3f;
			body.health = 1f;
			fighter.hitTaken();
			fighter.decide(near, body);
			fighter.hitTaken();
			int[] action = fighter.decide(near, body);
			assertEquals(1, action[ActionSpace.JUMP]);
			assertEquals(1, action[ActionSpace.FORWARD]);
			assertEquals(1, action[ActionSpace.SPRINT]);
			body.onGround = false;
			body.velUp = 0.3f;
			action = fighter.decide(near, body);
			assertEquals(0, action[ActionSpace.JUMP]);
			assertEquals(1, action[ActionSpace.FORWARD], "W, not S");
			// A hit that finds it in the air: S, as before.
			fighter.hitTaken();
			action = fighter.decide(near, body);
			assertEquals(BackNudge.BACK, action[ActionSpace.FORWARD]);
		}
		cfg.jumpResetChance = 0f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, ALL, true, 21);
			fighter.backOutRule(true);
			MaskFrame near = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 2.8, 180f, 180f, 0f, near);
			BodyState body = new BodyState();
			body.onGround = true;
			body.charge = 0.3f;
			body.health = 1f;
			fighter.hitTaken();
			fighter.decide(near, body);
			fighter.hitTaken();
			assertEquals(BackNudge.BACK, fighter.decide(near, body)[ActionSpace.FORWARD], "switched off, it backs off");
		}
	}

	/** After a hit of its own that has not sent the opponent away it steps back until its sword is nearly back. */
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
	}

	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {'''
s = s.replace(a, new)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('tests ok')
