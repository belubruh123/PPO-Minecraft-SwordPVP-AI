ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


# ---- 1. The swing is no longer held on the ground after the tick of the hit -------------------------
edit('core/src/main/java/dev/swordai/core/rl/AnswerRule.java', [
    ('''	public static int onTheGround(float[] vec, boolean punishable) {
		return punishable && vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] > 0f ? KEEP : NOW;
	}
''', '''	public static int onTheGround(float[] vec, boolean punishable) {
		return onTheGround(vec, punishable, false);
	}

	/**
	 * @param wait keep the swing for as long as the hit is fresh, as up to 14 October; false keeps it
	 *             on the tick of the hit only. With the jump made with a hit ({@link JumpReset}) the
	 *             opponent is in reach on nearly every fall, so it was nearly always "one that stays",
	 *             and a second after each hit it stood in reach with its sword back and did not swing:
	 *             what a person saw was a fighter that is close and does not hit.
	 */
	public static int onTheGround(float[] vec, boolean punishable, boolean wait) {
		float sinceTaken = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN];
		return punishable && (wait ? sinceTaken > 0f : sinceTaken >= 1f - 0.5f / dev.swordai.core.env.BodyState.EVENT_FADE_TICKS) ? KEEP : NOW;
	}
''', 1),
    ('''	public static int when(float[] vec, boolean punishable, boolean proven) {
		if (BackNudge.inCombo(vec) && !proven) {
			return NOW;
		}
		return JumpNudge.inAir(vec) ? inTheAir(vec) : onTheGround(vec, punishable);
	}''', '''	public static int when(float[] vec, boolean punishable, boolean proven) {
		return when(vec, punishable, proven, false);
	}

	/** @param wait see {@link #onTheGround(float[], boolean, boolean)} */
	public static int when(float[] vec, boolean punishable, boolean proven, boolean wait) {
		if (BackNudge.inCombo(vec) && !proven) {
			return NOW;
		}
		return JumpNudge.inAir(vec) ? inTheAir(vec) : onTheGround(vec, punishable, wait);
	}''', 1),
])
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven())''',
     '''AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven(), punishWait)''', 1),
    ('''	private final boolean meetRule;
''', '''	private final boolean meetRule;
	private final boolean punishWait;
''', 1),
    ('''		this.meetRule = cfg.meetJumpRule''', '''		this.punishWait = cfg.punishWaitOnGround;
		this.meetRule = cfg.meetJumpRule''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven())''',
     '''AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven(), cfg.punishWaitOnGround)''', 1),
])
edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean punishRule = true;
''', '''	public boolean punishRule = true;
	/**
	 * On the ground, the swing kept for the way down is kept for as long as the hit is fresh (a
	 * second), as up to 14 October; false keeps it on the tick of the hit only, and from the next
	 * tick on a swing that reaches is made. See {@code AnswerRule.onTheGround}.
	 */
	public boolean punishWaitOnGround = false;
	/**
	 * A running jump, and another as it lands, at an opponent five blocks off or more that is not
	 * itself coming in at a run: it closes the distance faster than a run, and the hit at the end of
	 * the last one is a critical hit if the way down brings the opponent into reach; see
	 * {@code JumpIn.approachHops}. Hit in the air of such a jump, it leaves them alone for a while.
	 */
	public boolean hopRule = true;
''', 1),
])

# ---- 2. Sprint-jumps at an opponent five blocks off or more -----------------------------------------
edit('core/src/main/java/dev/swordai/core/rl/JumpIn.java', [
    ('''	private final boolean chase;
	/** See {@link #chaseInCombo}. */
	private boolean inCombo = true;''', '''	/**
	 * From this far off it takes a running jump at an opponent that is not itself coming in at a run,
	 * and another each time it lands while it is still this far ({@link #approachHops}).
	 */
	static final float HOP_FROM = 5.0f;
	static final float HOP_CLOSING_MOST = 0.4f;

	private final boolean chase;
	/** See {@link #chaseInCombo}. */
	private boolean inCombo = true;
	/** See {@link #approachHops}. */
	private boolean hops;''', 1),
    ('''	public void chaseInCombo(boolean on) {
		inCombo = on;
	}
''', '''	public void chaseInCombo(boolean on) {
		inCombo = on;
	}

	/**
	 * Whether it closes on an opponent five blocks off or more with running jumps, one after the
	 * other, instead of a run: they carry it a quarter faster, and the last one ends in a critical
	 * hit if its way down brings the opponent into reach. Not at an opponent that is coming in at a
	 * run itself: the two would meet with the fighter in the air.
	 */
	public void approachHops(boolean on) {
		hops = on;
	}
''', 1),
    ('''		boolean longAfterHit = sinceDealt <= fade(CHASE_AFTER_HIT_TICKS);
		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& closing <= closingMost && d >= CHASE_FROM && chaseOff <= 0
				&& ((inCombo || longAfterHit) && d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))
						|| longAfterHit && closing <= PURSUIT_CLOSING_MOST && away >= PURSUIT_SPEED && d <= PURSUIT_TO)) {''',
     '''		boolean longAfterHit = sinceDealt <= fade(CHASE_AFTER_HIT_TICKS);
		boolean window = (inCombo || longAfterHit) && closing <= closingMost
				&& d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing));
		boolean pursuit = longAfterHit && closing <= PURSUIT_CLOSING_MOST && away >= PURSUIT_SPEED && d <= PURSUIT_TO;
		boolean approach = hops && closing <= HOP_CLOSING_MOST && d >= HOP_FROM && d <= PURSUIT_TO;
		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit || approach)) {''', 1),
])
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''		if (jumpIn != null) {
			jumpIn.chaseInCombo(cfg.chaseInCombo);
		}''', '''		if (jumpIn != null) {
			jumpIn.chaseInCombo(cfg.chaseInCombo);
			jumpIn.approachHops(cfg.hopRule);
		}''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		made.chaseInCombo(cfg.chaseInCombo);
''', '''		made.chaseInCombo(cfg.chaseInCombo);
		made.approachHops(cfg.hopRule);
''', 1),
    # How often it stands with the opponent under its crosshair and its sword back, and does not swing.
    ('''		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}''', '''		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}
		if (crosshairOnOpponent() && chargeAtClick >= 1f && !episodeLockedDrill) {
			pickTicks++;
			if (action[ActionSpace.ATTACK] == 0) {
				pickNoSwing++;
				if (keep) {
					pickKept++;
				}
			}
		}''', 1),
    ('''	private int waryBegan;
''', '''	private int waryBegan;
	/** Ticks with the opponent under the crosshair within reach and the sword all the way back; on how many of them no swing was made, and of those how many by the rule that keeps it for the way down. */
	private int pickTicks;
	private int pickNoSwing;
	private int pickKept;
''', 1),
    ('''ran in at an opponent on its way up on {} ticks, swings {} (ticks with one on its way up within range {}: its own feet off the ground {}, sword not back {}, not closing {})",''',
     '''ran in at an opponent on its way up on {} ticks, swings {} (ticks with one on its way up within range {}: its own feet off the ground {}, sword not back {}, not closing {}) | opponent under the crosshair with the sword back on {} ticks, no swing on {} of them, {} of those kept for the way down",''', 1),
    ('''				meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3]);''',
     '''				meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3], pickTicks, pickNoSwing, pickKept);
		pickTicks = 0;
		pickNoSwing = 0;
		pickKept = 0;''', 1),
])

# ---- tests ---------------------------------------------------------------------------------------
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		// And for as long as that hit is fresh: down again without having had a swing, it waits for
		// this opponent's next hit, which is never long in coming, and the fall after it.
		float[] still = vec(true, 2.0f, 1f);
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 12f / BodyState.EVENT_FADE_TICKS;
		assertEquals(AnswerRule.KEEP, AnswerRule.when(still, watch.punishable(), watch.proven()));
		// A second without another hit, and it swings from the ground after all.
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 0f;
		assertEquals(AnswerRule.NOW, AnswerRule.when(still, watch.punishable(), watch.proven()));'''
assert s.count(a) == 1
s = s.replace(a, '''		// Down again without having had a swing, with the opponent there: it swings. (Up to 14 October it
		// waited for that opponent's next hit for as long as the last one was fresh; asked for, it still does.)
		float[] still = vec(true, 2.0f, 1f);
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 12f / BodyState.EVENT_FADE_TICKS;
		assertEquals(AnswerRule.NOW, AnswerRule.when(still, watch.punishable(), watch.proven()));
		assertEquals(AnswerRule.KEEP, AnswerRule.when(still, watch.punishable(), watch.proven(), true));
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 0f;
		assertEquals(AnswerRule.NOW, AnswerRule.when(still, watch.punishable(), watch.proven(), true));''')
a = '''	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {'''
assert s.count(a) == 1
s = s.replace(a, '''	/** At a run towards an opponent five blocks off or more, it closes with running jumps, one as it lands from the last. */
	@Test
	void fromFiveBlocksOffItClosesWithRunningJumps() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		rule.approachHops(true);
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			plan = rule.plan(running(7.0f, 0.28f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN), "at one that stands, seven blocks off");
		assertEquals(JumpIn.CHASE, rule.tookOff());
		rule.plan(air(6.0f, 0.3f, true, 1f), ALL, false, false, 0);
		rule.plan(air(5.0f, -0.2f, true, 1f), ALL, false, false, 0);
		// Down again, still more than five blocks off: straight into the next one.
		plan = rule.plan(running(5.5f, 0.28f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.JUMP), "the next one as it lands");
		// Not at one that is coming in at a run itself, nor without being asked to.
		JumpIn meeting = new JumpIn(true, true, 0f, 1);
		meeting.approachHops(true);
		JumpIn plain = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			assertEquals(0, meeting.plan(running(8.0f, 0.56f), ALL, false, false, 0));
			assertEquals(0, plain.plan(running(7.0f, 0.28f), ALL, false, false, 0));
		}
	}

	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('swing+hop ok')
