import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/JumpIn.java')
e.rep("""	static final float FLEEING = 0.24f;""", """	static final float FLEEING = 0.24f;
	/**
	 * And it has been on the ground (moving up or down at less than {@link Tactic#AIR_SPEED}) for this many ticks: one
	 * flying back from a hit is still going off that fast fifteen ticks after it (22 October, second trial: all 25
	 * chases and hops at a ready sword were taken 15 or 16 ticks after the fighter's hit, the opponent still in the air).
	 */
	static final int FLEE_GROUND_TICKS = 3;""")
e.rep("""	private boolean spentOrFleeing;
""", """	private boolean spentOrFleeing;
	/** Ticks the opponent has been on the ground, as its speed up or down shows it. */
	private int oppGroundTicks;
""")
e.rep("""		counterOff = 0;
		counterBurns = 0;
		unhitTicks = FACE_UNHIT_TICKS;""", """		counterOff = 0;
		counterBurns = 0;
		oppGroundTicks = 0;
		unhitTicks = FACE_UNHIT_TICKS;""")
e.rep("""		unhitTicks = scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fade(1) ? 0 : Math.min(unhitTicks + 1, 10_000);""",
      """		unhitTicks = scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fade(1) ? 0 : Math.min(unhitTicks + 1, 10_000);
		float oppUp = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		oppGroundTicks = Math.abs(oppUp) < Tactic.AIR_SPEED ? Math.min(oppGroundTicks + 1, 10_000) : 0;""")
e.rep("""		boolean jumpable = !spentOrFleeing || scalar(vec, ObsSpec.SC_OPP_CHARGE) <= OPP_SPENT || away >= FLEEING && longAfterHit;""",
      """		boolean jumpable = !spentOrFleeing || scalar(vec, ObsSpec.SC_OPP_CHARGE) <= OPP_SPENT
				|| away >= FLEEING && longAfterHit && oppGroundTicks >= FLEE_GROUND_TICKS;""")
e.save()
e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/JumpInTest.java')
e.rep("""			assertEquals(0, thrown.plan(v, ALL, false, false, 1) & JumpIn.JUMP, "thrown back, tick " + tick);
		}""", """			assertEquals(0, thrown.plan(v, ALL, false, false, 1) & JumpIn.JUMP, "thrown back, tick " + tick);
		}
		// Long after the hit, but still in the air from it, going off fast: no jump either.
		JumpIn flying = new JumpIn(true, true, 0f, 1);
		flying.onlyAtSpentOrFleeing(true);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = running(3.5f, 0f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = (tick % 2 == 0 ? -0.2f : 0.15f) * BodyState.VELOCITY_SCALE;
			assertEquals(0, flying.plan(v, ALL, false, false, 1) & JumpIn.JUMP, "in the air, tick " + tick);
		}""")
e.save()
