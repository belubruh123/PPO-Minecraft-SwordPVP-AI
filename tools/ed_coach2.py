import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/JumpIn.java')
e.rep("""	/** The opponent's sword counts as ready from here on, for {@link #onlyAtSpentOrFleeing}. */
	static final float OPP_READY = 0.9f;
	/** And the opponent counts as running away going off at this much a tick or more, blocks. */
	static final float FLEEING = 0.24f;""", """	/**
	 * For {@link #onlyAtSpentOrFleeing}: the opponent's sword, as the fighter sees it, counts as spent up to here. The fighter
	 * sees its swing two or three ticks late, and a jump takes six to eight ticks to come down: a sword read higher than
	 * this is back by then (22 October, the first trial with 0.9 here: all 48 chases and hops of the first 27 fights
	 * were still made at a sword the server had back).
	 */
	static final float OPP_SPENT = 0.45f;
	/**
	 * And the opponent counts as running away going off at this much a tick or more, blocks, with no hit of the fighter's
	 * in the last {@link #CHASE_AFTER_HIT_TICKS} ticks: an opponent flying back from that hit is not running.
	 */
	static final float FLEEING = 0.24f;""")
e.rep("""		boolean jumpable = !spentOrFleeing || scalar(vec, ObsSpec.SC_OPP_CHARGE) < OPP_READY || away >= FLEEING;""",
      """		boolean jumpable = !spentOrFleeing || scalar(vec, ObsSpec.SC_OPP_CHARGE) <= OPP_SPENT || away >= FLEEING && longAfterHit;""")
e.rep("""	 * Whether the chase and the hop are made only at an opponent whose sword is not ready ({@link #OPP_READY}) or
	 * that is running away ({@link #FLEEING}); see {@code Config.jumpAtReadyOnlyFleeing}. An opponent standing its
	 * ground with its sword ready hits a running jump out of the air before it comes down.""", """	 * Whether the chase and the hop are made only at an opponent whose sword is spent ({@link #OPP_SPENT}) or that is
	 * running away ({@link #FLEEING}); see {@code Config.jumpAtReadyOnlyFleeing}. An opponent standing its ground with
	 * its sword ready hits a running jump out of the air before it comes down.""")
e.save()
e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/JumpInTest.java')
e.rep("""		for (float opp : new float[] {0f, 1f}) {
			JumpIn rule = new JumpIn(true, true, 0f, 1);
			rule.onlyAtSpentOrFleeing(true);
			boolean jumped = false;
			for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
				float[] v = running(3.5f, 0.065f);""", """		for (float opp : new float[] {0f, 0.3f, 0.6f, 1f}) {
			JumpIn rule = new JumpIn(true, true, 0f, 1);
			rule.onlyAtSpentOrFleeing(true);
			boolean jumped = false;
			for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
				float[] v = running(3.5f, 0.065f);""")
e.rep("""		// Running away from it at a run, sword ready or not: after it.
		JumpIn after = new JumpIn(true, true, 0f, 1);
		after.onlyAtSpentOrFleeing(true);
		boolean jumped = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2 && !jumped; tick++) {
			float[] v = running(3.5f, 0f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			jumped = has(after.plan(v, ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(jumped, "at one running away");""", """		// Running away from it at a run, sword ready or not, long after a hit of its own: after it.
		JumpIn after = new JumpIn(true, true, 0f, 1);
		after.onlyAtSpentOrFleeing(true);
		boolean jumped = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2 && !jumped; tick++) {
			float[] v = running(3.5f, 0f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			jumped = has(after.plan(v, ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(jumped, "at one running away");
		// Going off as fast, but flying back from its own hit a few ticks ago: no jump at that ready sword.
		JumpIn thrown = new JumpIn(true, true, 0f, 1);
		thrown.onlyAtSpentOrFleeing(true);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = after(running(3.5f, 0f), 3 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			assertEquals(0, thrown.plan(v, ALL, false, false, 1) & JumpIn.JUMP, "thrown back, tick " + tick);
		}""")
e.save()
