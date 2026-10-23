ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/JumpReset.java', [
    (''' * <p>With one exception ({@link #chasers}):''', ''' * <p>And a hit that finds it with its sword on the way back from a hit of its own
 * ({@link #counters}): the opponent's answer to that hit, made before there is a swing to answer
 * it with. Jumped with, the fighter is thrown half as far and is in the air beside the opponent as
 * its sword comes back, and the swing it owes is made on the way down, a critical hit. Without
 * that, an opponent that follows its answer with a running jump has the fighter twice for each
 * hit the fighter lands.
 *
 * <p>With one exception more ({@link #chasers}):''', 1),
    ('''	private final float chance;
	private final Random rng;
	private boolean chasers;''', '''	/** Its own hit no longer ago than this, and the sword no farther back than this: there is no swing to answer with. */
	static final int COUNTER_TICKS = 13;
	static final float COUNTER_CHARGE = 0.9f;

	private final float chance;
	private final Random rng;
	private boolean chasers;
	private float counters;''', 1),
    ('''	/** Whether the opponent has lately followed a hit with one made from the air. */''', '''	/**
	 * The share of the hits that find it with its sword on the way back from a hit of its own that
	 * are jumped with too; see {@code Config.jumpResetCounterChance}.
	 */
	public void counters(float share) {
		counters = share;
	}

	/** Whether the opponent has lately followed a hit with one made from the air. */''', 1),
    ('''			if (chance > 0f && ground && (combo || chasers && chased > 0) && ActionSpace.unlocked(keys, ActionSpace.JUMP)''',
     '''			float sinceDealt = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT];
			boolean counter = counters > 0f && sinceDealt >= 1f - (COUNTER_TICKS + 0.5f) / BodyState.EVENT_FADE_TICKS
					&& vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] < COUNTER_CHARGE && rng.nextFloat() < counters;
			if (chance > 0f && ground && (combo || counter || chasers && chased > 0) && ActionSpace.unlocked(keys, ActionSpace.JUMP)''', 1),
])
edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	/**
	 * Against an opponent that has followed a hit with a second one made from the air in the last''', '''	/**
	 * The share of the hits that find it with its sword still on the way back from a hit of its own
	 * (no more than thirteen ticks after it) that it jumps with as well: the opponent's answer to its
	 * hit, which it has no swing to answer; see {@code JumpReset}. 0: only ever in a combo.
	 */
	public float jumpResetCounterChance = 1f;
	/**
	 * Against an opponent that has followed a hit with a second one made from the air in the last''', 1),
])
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''			jumpReset.chasers(cfg.jumpResetChasers);
''', '''			jumpReset.chasers(cfg.jumpResetChasers);
			jumpReset.counters(cfg.jumpResetCounterChance);
''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''				jumpReset.chasers(cfg.jumpResetChasers);
''', '''				jumpReset.chasers(cfg.jumpResetChasers);
				jumpReset.counters(cfg.jumpResetCounterChance);
''', 1),
])
edit('src/client/java/dev/swordai/client/Report.java', [
    ('''				cfg.jumpResetChasers ? "; with the first hit too against an opponent that has just followed a hit with a jump and a critical hit (jumpResetChasers)" : ""));''',
     '''				(cfg.jumpResetCounterChance > 0f ? String.format(Locale.ROOT, "; and with %.0f%% of the hits that find its sword on the way back from a hit of its own (jumpResetCounterChance)",
						cfg.jumpResetCounterChance * 100f) : "")
						+ (cfg.jumpResetChasers ? "; with the first hit too against an opponent that has just followed a hit with a jump and a critical hit (jumpResetChasers)" : "")));''', 1),
])
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		// At a share of the hits it is that share.
		JumpReset half = new JumpReset(0.5f, 11);'''
assert s.count(a) == 1
s = s.replace(a, '''		// A hit that finds its sword on the way back from a hit of its own, six ticks before: the
		// opponent's answer to that hit. Jumped with, if that is asked for; a first hit otherwise is not.
		JumpReset answers = new JumpReset(1f, 5);
		float[] countered = after(vec(true, 2.5f, 0.5f), 6, 0);
		countered[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertEquals(0, answers.plan(countered, ALL, true), "not asked for");
		answers.counters(1f);
		assertTrue(has(answers.plan(countered, ALL, true), JumpIn.JUMP));
		assertEquals(0, answers.plan(first, ALL, true), "its sword was back: that one it takes as it comes");
		float[] longAgo = after(vec(true, 2.5f, 0.5f), 16, 0);
		longAgo[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertEquals(0, answers.plan(longAgo, ALL, true));
		// At a share of the hits it is that share.
		JumpReset half = new JumpReset(0.5f, 11);''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('counter ok')
