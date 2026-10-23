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
    ('''	static final int HOP_OFF_TICKS = 200;
	static final int HOP_OFF_LONG_TICKS = 1200;
	static final int HOP_BURNS_LONG = 3;
''', '''	static final int HOP_OFF_TICKS = 200;
	static final int HOP_OFF_LONG_TICKS = 1200;
	static final int HOP_BURNS_LONG = 3;
	/**
	 * A running jump from five blocks off does not wait for the run: it is made once the fighter has
	 * been going at the opponent for this many ticks at this speed, sprint key or not, and the rule
	 * presses W and the sprint key with it. On 14 October, with the opponent that far and nothing
	 * else in the way, it was waiting for the run on 80 ticks in 100 (340 of 425), and made 9.
	 */
	static final int HOP_HEADING_TICKS = 2;
	static final float HOP_HEADING_SPEED = 0.08f;
''', 1),
    ('''	private int runTicks;
''', '''	private int runTicks;
	private int headingTicks;
''', 1),
    ('''		runTicks = 0;
''', '''		runTicks = 0;
		headingTicks = 0;
''', 1),
    ('''		int ranFor = runTicks;
		runTicks = atARun ? runTicks + 1 : 0;
''', '''		int ranFor = runTicks;
		runTicks = atARun ? runTicks + 1 : 0;
		int headedFor = headingTicks;
		headingTicks = ground && seen && scalar(vec, ObsSpec.SC_VEL_FORWARD) / BodyState.VELOCITY_SCALE >= HOP_HEADING_SPEED ? headingTicks + 1 : 0;
''', 1),
    ('''			hopWhy = ranFor < RUN_TICKS ? 1 : !notHitLately ? 2 : side > SIDE_MOST ? 3 : charge < CHASE_CHARGE ? 4''',
     '''			hopWhy = ranFor < RUN_TICKS && headedFor < HOP_HEADING_TICKS ? 1 : !notHitLately ? 2 : side > SIDE_MOST ? 3 : charge < CHASE_CHARGE ? 4''', 1),
    ('''		if (chase && hops && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE && hopOff <= 0''',
     '''		if (chase && hops && notHitLately && (ranFor >= RUN_TICKS || headedFor >= HOP_HEADING_TICKS) && side <= SIDE_MOST
				&& charge >= CHASE_CHARGE && hopOff <= 0''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''jumped {}, not at a run {}, hit lately {}''', '''jumped {}, not going at it {}, hit lately {}''', 1),
])
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		// Not at one that is coming in at a run itself, nor without being asked to.'''
assert s.count(a) == 1
s = s.replace(a, '''		// Going at it at a walk is enough: the rule runs and jumps for it.
		JumpIn walker = new JumpIn(true, true, 0f, 1);
		walker.approachHops(true);
		float[] walking = running(7.0f, 0.15f);
		walking[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		walking[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.15f * BodyState.VELOCITY_SCALE;
		assertEquals(0, walker.plan(walking, ALL, false, false, 0) & JumpIn.JUMP, "not on the first tick");
		assertEquals(0, walker.plan(walking, ALL, false, false, 0) & JumpIn.JUMP, "nor the second");
		plan = walker.plan(walking, ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN), "then it runs and jumps");
		assertEquals(JumpIn.HOP, walker.tookOff());
		// Not standing, nor going the other way.
		JumpIn stander = new JumpIn(true, true, 0f, 1);
		stander.approachHops(true);
		float[] standing = running(7.0f, 0f);
		standing[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		standing[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0f;
		for (int tick = 0; tick < 10; tick++) {
			assertEquals(0, stander.plan(standing, ALL, false, false, 0) & JumpIn.JUMP);
		}
		// Not at one that is coming in at a run itself, nor without being asked to.''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('hopstart ok')
