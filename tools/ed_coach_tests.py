import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/JumpIn.java')
e.rep("""	static final float FLEEING = 0.2f;""", """	static final float FLEEING = 0.24f;""")
e.save()

T = 'D:/swordai/core/src/test/java/dev/swordai/core/rl/'
e = Ed(T + 'JumpInTest.java')
e.rep("""	private static JumpIn fresh() {""", """	/**
	 * With {@code Config.jumpAtReadyOnlyFleeing}: no chase and no hop at an opponent that stands its ground with its
	 * sword ready (it hits a running jump out of the air), but at one whose sword is spent, or that runs away, as before.
	 */
	@Test
	void atAnOpponentStandingItsGroundWithItsSwordReadyItDoesNotJump() {
		// The chase: four blocks off, backing off at a walk.
		for (float opp : new float[] {0f, 1f}) {
			JumpIn rule = new JumpIn(true, true, 0f, 1);
			rule.onlyAtSpentOrFleeing(true);
			boolean jumped = false;
			for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
				float[] v = running(3.5f, 0.065f);
				v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = opp;
				jumped |= has(rule.plan(v, ALL, false, false, 0), JumpIn.JUMP);
			}
			assertEquals(opp < 0.5f, jumped, "its sword at " + opp);
		}
		// Running away from it at a run, sword ready or not: after it.
		JumpIn after = new JumpIn(true, true, 0f, 1);
		after.onlyAtSpentOrFleeing(true);
		boolean jumped = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2 && !jumped; tick++) {
			float[] v = running(3.5f, 0f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			jumped = has(after.plan(v, ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(jumped, "at one running away");
		// The hop from seven blocks off at one that stands with its sword ready: not made; with it spent, made.
		for (float opp : new float[] {0f, 1f}) {
			JumpIn rule = new JumpIn(true, true, 0f, 1);
			rule.approachHops(true);
			rule.onlyAtSpentOrFleeing(true);
			boolean hopped = false;
			for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
				float[] v = running(7.0f, 0.28f);
				v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = opp;
				hopped |= has(rule.plan(v, ALL, false, false, 0), JumpIn.JUMP);
			}
			assertEquals(opp < 0.5f, hopped, "hop, its sword at " + opp);
		}
		// Not set: at the ready one too, as before.
		JumpIn old = new JumpIn(true, true, 0f, 1);
		boolean oldJumped = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = running(3.5f, 0.065f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			oldJumped |= has(old.plan(v, ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(oldJumped);
	}

	private static JumpIn fresh() {""")
e.save()

e = Ed(T + 'AnswerRuleTest.java')
e.rep("""	private static StepFacts facts() {""", """	/**
	 * With {@code Config.keepWithin}: thrown up by a hit, the swing is kept for the way down only with the opponent
	 * near; farther off it is made at once, while the opponent is still under the crosshair.
	 */
	@Test
	void thrownUpWithTheOpponentFartherThanKeepWithinTheSwingIsMadeAtOnce() {
		Config cfg = new Config();
		cfg.keepWithin = 2.3f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			fighter.crosshairOnOpponent(true);
			// Near: kept for the way down, as before.
			fighter.hitTaken();
			for (int tick = 0; tick < 4; tick++) {
				assertEquals(0, fighter.decide(seen(2.0), inTheAir(0.3f, true))[ActionSpace.ATTACK], "near, tick " + tick);
				assertFalse(fighter.answerForced());
			}
			// Carried out to three blocks on the way up: the swing now.
			assertEquals(1, fighter.decide(seen(3.0), inTheAir(0.25f, true))[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
		}
		float[] far = new float[ObsSpec.VEC];
		MaskFrame frame = seen(3.0);
		ObsBuilder.writeStats(far, frame.stats, frame.stats);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		assertEquals(AnswerRule.KEEP, AnswerRule.when(far, true, false, false, 0f), "0: kept, as before");
		assertEquals(AnswerRule.NOW, AnswerRule.when(far, true, false, false, 2.3f));
	}

	private static StepFacts facts() {""")
e.save()
