import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/JumpIn.java')
e.rep("""	public static final int NO_JUMP = 32;
""", """	public static final int NO_JUMP = 32;
	/** W down and the sprint key up: walking, or drifting in in the air without the sprint that would spoil a crit. */
	public static final int WALK = 64;
""")
e.rep("""	static final int EDGE_OFF_TICKS = 100;
	static final int EDGE_BURNS = 4;""", """	static final int EDGE_OFF_TICKS = 100;
	static final int EDGE_BURNS = 4;
	/**
	 * The crit in from a stand: just outside the reach of a still opponent, no faster than {@link #EDGE_STAND_SPEED}, a jump
	 * in place, W from the top of it, which drifts it a third of a block in by the time it lands: into reach low in the fall.
	 */
	static final float EDGE_STAND_FROM = 3.05f;
	static final float EDGE_STAND_TO = 3.4f;
	static final float EDGE_STAND_SPEED = 0.06f;
	/** Its own sword this far back at least for that: all the way back ten ticks on. */
	static final float EDGE_STAND_CHARGE = 0.6f;
	/** The opponent's sword, as it is seen, at least this far back: the one whose sword is spent is gone in at at once. */
	static final float EDGE_OPP_READY = 0.9f;
	/**
	 * Coming up to that edge from no farther than {@link #EDGE_APPROACH}: W let go of where the fighter would come to a stop
	 * at {@link #EDGE_STOP_AT} (on the ground it stops in about 1.2 ticks' worth of its speed), walked in from a stand.
	 */
	static final float EDGE_APPROACH = 4.6f;
	static final float EDGE_STOP_AT = 3.25f;
	static final float EDGE_STOP_FACTOR = 1.2f;""")
e.rep("""	private boolean edge;
	private int edgeOff;
	private int edgeBurns;
""", """	private boolean edge;
	private int edgeOff;
	private int edgeBurns;
	/** The crit in it is in was taken from a stand ({@link #EDGE_STAND_FROM}). */
	private boolean edgeStand;
""")
e.rep("""			} else {
				return kind == FACE ? LET_GO | NO_SWING : RUN | NO_SWING;
			}""", """			} else {
				return kind == FACE || kind == EDGE && edgeStand ? LET_GO | NO_SWING : RUN | NO_SWING;
			}""")
e.rep("""		// The crit in: at an opponent standing still, from where the way down brings it into reach late in the fall.
		if (edge && edgeOff <= 0 && notHitLately && side <= SIDE_MOST && charge >= EDGE_CHARGE && oppGroundTicks >= FLEE_GROUND_TICKS
				&& Math.abs(away) <= EDGE_STILL && d <= EDGE_TO) {""", """		// The crit in from a stand: just outside the reach of a still opponent whose sword is ready, a jump in place (W from
		// the top of it, in the air); and coming up to that edge, W let go of where the fighter will stop there.
		if (edge && edgeOff <= 0 && notHitLately && side <= SIDE_MOST && charge >= EDGE_STAND_CHARGE && oppGroundTicks >= FLEE_GROUND_TICKS
				&& Math.abs(away) <= EDGE_STILL && scalar(vec, ObsSpec.SC_OPP_CHARGE) >= EDGE_OPP_READY) {
			if (d >= EDGE_STAND_FROM && d <= EDGE_STAND_TO && Math.abs(closing) <= EDGE_STAND_SPEED) {
				start(EDGE);
				edgeStand = true;
				return JUMP | LET_GO | NO_SWING;
			}
			if (d > EDGE_STAND_TO && d <= EDGE_APPROACH) {
				return d - EDGE_STOP_FACTOR * Math.max(0f, closing) <= EDGE_STOP_AT ? LET_GO : WALK;
			}
		}
		// The crit in: at an opponent standing still, from where the way down brings it into reach late in the fall.
		if (edge && edgeOff <= 0 && notHitLately && side <= SIDE_MOST && charge >= EDGE_CHARGE && oppGroundTicks >= FLEE_GROUND_TICKS
				&& Math.abs(away) <= EDGE_STILL && d <= EDGE_TO) {""")
e.rep("""	private void start(int what) {
		kind = what;""", """	private void start(int what) {
		kind = what;
		edgeStand = false;""")
e.rep("""		if (kind == EDGE) {
			if (!falling) {
				return NO_JUMP | RUN | NO_SWING;
			}""", """		if (kind == EDGE && edgeStand) {
			// Up in place, W and the sprint key up; from the top W alone, drifting in, and the swing the moment it is in reach.
			if (!falling) {
				return NO_JUMP | LET_GO | NO_SWING;
			}
			return NO_JUMP | WALK | (onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
		}
		if (kind == EDGE) {
			if (!falling) {
				return NO_JUMP | RUN | NO_SWING;
			}""")
e.rep("""		if ((plan & (RUN | LET_GO)) != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}""", """		if ((plan & (RUN | LET_GO | WALK)) != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}""")
e.rep("""		} else if ((plan & LET_GO) != 0) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}""", """		} else if ((plan & LET_GO) != 0) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		} else if ((plan & WALK) != 0) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 0;
		}""")
e.save()

e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/JumpInTest.java')
e.rep("""	private static JumpIn fresh() {""", """	/**
	 * The crit in from a stand: coming up to a still opponent with its sword ready, W is let go of where the fighter stops
	 * just outside its reach; standing there, a jump in place, and W alone from the top of it, the swing as it is in reach.
	 */
	@Test
	void atTheEdgeOfAStillOpponentItJumpsInPlaceAndDriftsInForTheCrit() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.edgeCrit(true);
		// From a stand 4.4 off: walked in. Coming in at a run from 3.55: W let go of, so that it stops at the edge.
		float[] far = running(4.4f, 0.05f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.05f * BodyState.VELOCITY_SCALE;
		int plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS; tick++) {
			plan = rule.plan(far, ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.WALK), "walked in from a stand");
		float[] coming = running(3.55f, 0.28f);
		coming[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		assertTrue(has(rule.plan(coming, ALL, false, false, 0), JumpIn.LET_GO), "W let go of: it stops at the edge");
		// Standing at 3.2: the jump in place.
		float[] edge = running(3.2f, 0f);
		edge[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		edge[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		edge[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0f;
		plan = rule.plan(edge, ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING), "jump in place");
		assertEquals(JumpIn.EDGE, rule.tookOff());
		// Up: W and the sprint up. Down: W alone; in reach, the swing.
		plan = rule.plan(air(3.2f, 0.3f, false, 0.9f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(3.1f, -0.1f, false, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.WALK) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(3.0f, -0.3f, false, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.WALK) && has(plan, JumpIn.SWING));
		// The keys: W down, the sprint up.
		int[] action = new int[ActionSpace.HEADS];
		action[ActionSpace.SPRINT] = 1;
		JumpIn.apply(action, JumpIn.WALK);
		assertEquals(1, action[ActionSpace.FORWARD]);
		assertEquals(0, action[ActionSpace.SPRINT]);
		// Not at an opponent whose sword is spent (that one is gone in at at once), nor one that is moving.
		float[] spent = running(3.2f, 0f);
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.3f;
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0f;
		JumpIn other = new JumpIn(false, false, 0f, 1);
		other.edgeCrit(true);
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(0, other.plan(spent, ALL, false, false, 0));
		}
	}

	private static JumpIn fresh() {""")
e.save()
