ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


J = 'core/src/main/java/dev/swordai/core/rl/JumpIn.java'
edit(J, [
    ('''	public static final int UPPER = 3;
''', '''	public static final int UPPER = 3;
	/** A running jump that closes on an opponent five blocks off or more; see {@link #approachHops}. (4 is {@link JumpReset#KIND}.) */
	public static final int HOP = 5;
''', 1),
    ('''	static final float HOP_FROM = 5.0f;
	static final float HOP_CLOSING_MOST = 0.4f;
''', '''	static final float HOP_FROM = 5.0f;
	static final float HOP_CLOSING_MOST = 0.4f;
	/**
	 * Hit in the air of one of these, it leaves them alone for ten seconds, and for a minute after
	 * the third time. They have a reckoning of their own: hit out of the air at the end of a jump at
	 * an opponent in its reach, the chase jump is left alone for five minutes, and up to 14 October
	 * these went with it, so that after two such hits it never ran in with a jump at all.
	 */
	static final int HOP_OFF_TICKS = 200;
	static final int HOP_OFF_LONG_TICKS = 1200;
	static final int HOP_BURNS_LONG = 3;
''', 1),
    ('''	private int upperOff;
	private int upperBurns;
''', '''	private int upperOff;
	private int upperBurns;
	private int hopOff;
	private int hopBurns;
''', 1),
    ('''		upperOff = 0;
		upperBurns = 0;
		unhitTicks = FACE_UNHIT_TICKS;''', '''		upperOff = 0;
		upperBurns = 0;
		hopOff = 0;
		hopBurns = 0;
		unhitTicks = FACE_UNHIT_TICKS;''', 1),
    ('''		int upperWas = upperOff;
		int upperBurnsWere = upperBurns;
		reset();''', '''		int upperWas = upperOff;
		int upperBurnsWere = upperBurns;
		int hopWas = hopOff;
		int hopBurnsWere = hopBurns;
		reset();
		hopOff = hopWas;
		hopBurns = hopBurnsWere;''', 1),
    ('''		if (upperOff > 0 && --upperOff == 0 && upperBurns >= 2) {
			upperBurns = 0;
		}''', '''		if (upperOff > 0 && --upperOff == 0 && upperBurns >= 2) {
			upperBurns = 0;
		}
		if (hopOff > 0 && --hopOff == 0 && hopBurns >= HOP_BURNS_LONG) {
			hopBurns = 0;
		}''', 1),
    ('''				} else if (kind == UPPER) {''', '''				} else if (kind == HOP) {
					hopOff = ++hopBurns >= HOP_BURNS_LONG ? HOP_OFF_LONG_TICKS : HOP_OFF_TICKS;
				} else if (kind == UPPER) {''', 1),
    ('''				if (airborne && kind == CHASE) {''', '''				if (airborne && (kind == CHASE || kind == HOP)) {''', 1),
    ('''		boolean approach = hops && closing <= HOP_CLOSING_MOST && d >= HOP_FROM && d <= PURSUIT_TO;
		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit || approach)) {
			start(CHASE);
			return JUMP | RUN | NO_SWING;
		}''', '''		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit)) {
			start(CHASE);
			return JUMP | RUN | NO_SWING;
		}
		if (chase && hops && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE && hopOff <= 0
				&& closing <= HOP_CLOSING_MOST && d >= HOP_FROM && d <= PURSUIT_TO) {
			start(HOP);
			return JUMP | RUN | NO_SWING;
		}''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''					if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.UPPER) {
						tally.chaseRuleCrits++;''', '''					if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.UPPER || jumpRule == JumpIn.HOP) {
						tally.chaseRuleCrits++;''', 1),
    ('''			if (jumpRule == JumpIn.CHASE) {
				tally.chaseRuleJumps++;''', '''			if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.HOP) {
				tally.chaseRuleJumps++;''', 1),
])
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN), "at one that stands, seven blocks off");
		assertEquals(JumpIn.CHASE, rule.tookOff());'''
assert s.count(a) == 1
s = s.replace(a, '''		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN), "at one that stands, seven blocks off");
		assertEquals(JumpIn.HOP, rule.tookOff());''')
a = '''		// Not at one that is coming in at a run itself, nor without being asked to.'''
assert s.count(a) == 1
s = s.replace(a, '''		// Hit out of the air at the end of one, it leaves them alone for a few seconds only.
		rule.plan(air(4.0f, 0.3f, true, 1f), ALL, false, false, 0);
		float[] hitInAir = air(3.0f, -0.1f, true, 1f);
		hitInAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		rule.plan(hitInAir, ALL, false, true, 0);
		for (int tick = 0; tick < JumpIn.HOP_OFF_TICKS - 1; tick++) {
			assertEquals(0, rule.plan(running(7.0f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP, "tick " + tick);
		}
		boolean again = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS && !again; tick++) {
			again = has(rule.plan(running(7.0f, 0.28f), ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(again, "and then again");
		assertEquals(JumpIn.HOP, rule.tookOff());
		// Not at one that is coming in at a run itself, nor without being asked to.''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('hopkind ok')

T = 'core/src/main/java/dev/swordai/core/curriculum/Tally.java'
edit(T, [
    ('''	public long upperRuleHits;
	public long ruleJumpsHit;''', '''	public long upperRuleHits;
	public long ruleJumpsHit;
	/** Of the chase jumps a rule made, how many were running jumps from five blocks off or more ({@code JumpIn.HOP}). */
	public long hopRuleJumps;''', 1),
    ('''		upperRuleHits += t.upperRuleHits;
''', '''		upperRuleHits += t.upperRuleHits;
		hopRuleJumps += t.hopRuleJumps;
''', 2),
    ('''		upperRuleHits = 0;
''', '''		upperRuleHits = 0;
		hopRuleJumps = 0;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''			if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.HOP) {
				tally.chaseRuleJumps++;''', '''			if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.HOP) {
				tally.chaseRuleJumps++;
				if (jumpRule == JumpIn.HOP) {
					tally.hopRuleJumps++;
				}''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''resets %d, step back ticks %d, hit after its own hit %d, crits taken %d, own combos %d of %d hits",''',
     '''resets %d, step back ticks %d, hit after its own hit %d, crits taken %d, own combos %d of %d hits, hops %d",''', 1),
    ('''t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken, t.combos, t.comboHits);''',
     '''t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken, t.combos, t.comboHits, t.hopRuleJumps);''', 1),
    ('''		if (ruleJumpAtAction == JumpIn.CHASE) {
			chaseWatch = true;''', '''		if (ruleJumpAtAction == JumpIn.CHASE || ruleJumpAtAction == JumpIn.HOP) {
			chaseWatch = true;''', 1),
])
print('tally ok')
