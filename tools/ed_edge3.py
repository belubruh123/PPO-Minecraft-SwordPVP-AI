import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/JumpIn.java')
e.rep("""	static final float EDGE_K_MIN = 8.5f;
	static final float EDGE_K_MAX = 11f;
	/**
	 * How fast a running jump carries it, blocks a tick: the speed it closes at plus this, between these two. (A running
	 * jump at a run covers 2.8 blocks in nine ticks; one from a stand gets the jump's push alone.)
	 */
	static final float EDGE_PUSH = 0.08f;
	static final float EDGE_AIR_LEAST = 0.2f;
	static final float EDGE_AIR_MOST = 0.33f;""", """	static final float EDGE_K_MIN = 9f;
	static final float EDGE_K_MAX = 11.5f;
	/**
	 * How a running jump carries it, measured in the traces of 22 October: this much on the first tick, then on each tick
	 * the speed it closed at before plus {@link #EDGE_PUSH}, between {@link #EDGE_AIR_LEAST} and {@link #EDGE_AIR_MOST}
	 * (from a stand 0.38, then 0.21-0.25 a tick; at a run of 0.3 a tick 0.65, then 0.43-0.45). The first version took
	 * 0.2 a tick from a stand and took off from 4.8 blocks: in reach at the top of the jump, hit first.
	 */
	static final float EDGE_FIRST = 0.15f;
	static final float EDGE_PUSH = 0.15f;
	static final float EDGE_AIR_LEAST = 0.22f;
	static final float EDGE_AIR_MOST = 0.45f;""")
e.rep("""			float carried = Math.max(EDGE_AIR_LEAST, Math.min(EDGE_AIR_MOST, Math.max(0f, closing) + EDGE_PUSH));
			float ticks = (d - EDGE_REACH) / carried;""", """			float carried = Math.max(EDGE_AIR_LEAST, Math.min(EDGE_AIR_MOST, Math.max(0f, closing) + EDGE_PUSH));
			float ticks = (d - EDGE_REACH - EDGE_FIRST) / carried;""")
e.rep("""	static final float EDGE_TO = 7f;""", """	static final float EDGE_TO = 8f;""")
e.save()
e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/JumpInTest.java')
e.rep("""		// At a run (0.28 a tick, closing as fast: the opponent stands): from 6.2 blocks, about ten ticks to the reach.
		int plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = rule.plan(running(6.2f, 0.28f), ALL, false, false, 0);
		}""", """		// At a run (0.28 a tick, closing as fast: the opponent stands): from 7.4 blocks, about ten ticks to the reach.
		int plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = rule.plan(running(7.4f, 0.28f), ALL, false, false, 0);
		}""")
e.rep("""		for (float[] v : new float[][] {running(4.0f, 0.28f), running(6.2f, 0.1f)}) {""", """		for (float[] v : new float[][] {running(4.8f, 0.28f), running(7.4f, 0.1f)}) {""")
e.rep("""		JumpIn off = new JumpIn(false, false, 0f, 1);
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(0, off.plan(running(6.2f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP);
		}""", """		JumpIn off = new JumpIn(false, false, 0f, 1);
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(0, off.plan(running(7.4f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP);
		}""")
e.rep("""		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = burnt.plan(running(6.2f, 0.28f), ALL, false, false, 0);
		}""", """		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = burnt.plan(running(7.4f, 0.28f), ALL, false, false, 0);
		}""")
e.rep("""			assertEquals(0, burnt.plan(running(6.2f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP, "tick " + tick);""",
      """			assertEquals(0, burnt.plan(running(7.4f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP, "tick " + tick);""")
e.save()
