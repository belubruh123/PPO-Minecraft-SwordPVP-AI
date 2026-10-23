ROOT = 'D:/swordai/'
p = ROOT + 'core/src/main/java/dev/swordai/core/rl/StepBack.java'
s = open(p, encoding='utf-8').read()
i = s.index('	/**\n	 * Each such answer counts for this long')
j = s.index('	/** Still going away at this much a tick four ticks after the hit')
s = s[:i] + '''	/**
	 * Each such answer counts for this long, ten seconds, and it takes two within about five seconds
	 * for the opponent to be one that answers its hits ({@link #WARY_FROM}): one that does it now and
	 * then, as anyone who is fighting back will, is not.
	 */
	static final int ANSWER_COUNTS_TICKS = 200;
	static final int WARY_FROM = 300;
	static final int WARY_MOST = 600;
''' + s[j:]
a = 'three times within seven seconds. From then'
assert s.count(a) == 1
s = s.replace(a, 'twice within five seconds. From then')
open(p, 'w', encoding='utf-8', newline='').write(s)
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = """		// Hit six ticks after a hit of its own, once or twice: anyone who fights back does that now and then.
		answered(rule, 6);
		answered(rule, 6);
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		// And a third time within a few seconds: this one answers its hits.
		answered(rule, 6);
		assertTrue(rule.wary());"""
assert s.count(a) == 1
s = s.replace(a, """		// Hit six ticks after a hit of its own, once: anyone who fights back does that now and then.
		answered(rule, 6);
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		// And again within a few seconds: this one answers its hits.
		answered(rule, 6);
		assertTrue(rule.wary());""")
open(p, 'w', encoding='utf-8', newline='').write(s)
p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = "(three times within\n	 * seven seconds), it walks"
assert s.count(a) == 1
s = s.replace(a, "(twice within\n	 * five seconds), it walks")
open(p, 'w', encoding='utf-8', newline='').write(s)
print('heat3 ok')
