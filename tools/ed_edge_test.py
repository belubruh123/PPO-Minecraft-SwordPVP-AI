import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/JumpInTest.java')
e.rep("""	private static JumpIn fresh() {""", """	/**
	 * The crit in: at an opponent standing still, a running jump from where the way down brings it into reach late in the
	 * fall; on the way down W and the sprint are let go of near the reach, and the swing made as it comes into reach.
	 */
	@Test
	void atAnOpponentStandingStillItCritsInLateInTheFall() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.edgeCrit(true);
		// At a run (0.28 a tick, closing as fast: the opponent stands): from 6.2 blocks, about ten ticks to the reach.
		int plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = rule.plan(running(6.2f, 0.28f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING), "takes off");
		assertEquals(JumpIn.EDGE, rule.tookOff());
		// Going up: at a run, no swing.
		plan = rule.plan(air(5.0f, 0.3f, true, 0.8f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		// Coming down, still well out of reach: at a run.
		plan = rule.plan(air(4.0f, -0.1f, true, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		// Near the reach: W and the sprint let go of, no swing yet (nothing in reach, and still sprinting).
		plan = rule.plan(air(3.3f, -0.2f, true, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		// In reach, the sprint off: the crit.
		plan = rule.plan(air(3.0f, -0.3f, false, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.SWING));
		// Not from too near (it would come into reach going up), nor at one that is moving, nor when not asked to.
		for (float[] v : new float[][] {running(4.0f, 0.28f), running(6.2f, 0.1f)}) {
			JumpIn other = new JumpIn(false, false, 0f, 1);
			other.edgeCrit(true);
			for (int tick = 0; tick < 6; tick++) {
				assertEquals(0, other.plan(v, ALL, false, false, 0) & JumpIn.JUMP);
			}
		}
		JumpIn off = new JumpIn(false, false, 0f, 1);
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(0, off.plan(running(6.2f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP);
		}
		// Hit in the air of one before its own swing: left alone for a while.
		JumpIn burnt = new JumpIn(false, false, 0f, 1);
		burnt.edgeCrit(true);
		plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = burnt.plan(running(6.2f, 0.28f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP));
		burnt.plan(after(air(4.5f, 0.2f, true, 1f), 100, 0), ALL, false, false, 0);
		assertEquals(JumpIn.NONE, burnt.kind());
		for (int tick = 0; tick < JumpIn.EDGE_OFF_TICKS - 2; tick++) {
			assertEquals(0, burnt.plan(running(6.2f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP, "tick " + tick);
		}
	}

	private static JumpIn fresh() {""")
e.save()
