ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.drillShare = 0.35f;
		st.drillMinHits = 1;
		st.drillHits = 2;
		st.reward.comboBack = 0.07f;
		st.reward.comboEscape = 1.0f;''', '''		st.drillShare = 0.35f;
		st.drillMinHits = 1;
		st.drillHits = 2;
		// S in a combo is no longer paid from here on (0.07 a tick up to the first evening against
		// people). Backing off is what a player who follows with a running jump wants of it: that is
		// the opponent that beat it, and what it does about a combo now is jump with the hit and swing
		// (JumpReset). A third hit still costs, and so does every hit of a combo.
		st.reward.comboBack = 0f;
		st.reward.comboEscape = 1.0f;''', 1),
    ('''		st.traderShare = 0.1f;
''', '''		st.traderShare = 0.08f;
''', 1),
    ('''		st.runnerShare = 0.14f;
		st.circlerShare = 0.12f;
''', '''		st.runnerShare = 0.12f;
		st.circlerShare = 0.1f;
''', 1),
    ('''		st.cornerShare = 0.08f;
		st.noKnockShare = 0.1f;
		st.oddShare = 0.1f;
		// And the two it lost to on the first evening against people; see StageSpec.jumperShare.
		st.jumperShare = 0.15f;
		st.punisherShare = 0.12f;
''', '''		st.cornerShare = 0.07f;
		st.noKnockShare = 0.08f;
		st.oddShare = 0.08f;
		// And the two it lost to on the first evening against people; see StageSpec.jumperShare.
		st.jumperShare = 0.2f;
		st.punisherShare = 0.15f;
''', 1),
])
s = open(ROOT + 'core/src/main/java/dev/swordai/core/curriculum/Stages.java', encoding='utf-8').read()
a = '''		st.reward.comboBack = 0.04f;
'''
assert s.count(a) == 1, s.count(a)
s = s.replace(a, '''		// Not paid from the crit stage on; see there.
		st.reward.comboBack = 0f;
''')
open(ROOT + 'core/src/main/java/dev/swordai/core/curriculum/Stages.java', 'w', encoding='utf-8', newline='').write(s)

edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
    ('''		assertEquals(w.comboBack, stage("Crits").reward.comboBack, 0f);
		for (String name : new String[] {"Duel", "Self-play"}) {
			assertTrue(stage(name).reward.comboBack > 0f && stage(name).reward.comboBack < w.comboBack, name);
		}''', '''		// (Up to the first evening against people it stayed paid after it. Backing off is what a player
		// who follows with a running jump wants of it; from the crit stage on it is not paid.)
		for (String name : new String[] {"Crits", "Duel", "Self-play"}) {
			assertEquals(0f, stage(name).reward.comboBack, name);
			assertTrue(stage(name).reward.comboThird > 0f && stage(name).reward.comboedHit > 0f, name + ": a combo still costs");
		}''', 1),
    ('''				assertTrue(stages.get(fight).reward.crit > 0f && stages.get(fight).reward.comboBack > 0f, stages.get(fight).name);''',
     '''				assertTrue(stages.get(fight).reward.crit > 0f && stages.get(fight).reward.comboThird > 0f, stages.get(fight).name);''', 1),
    ('''		assertTrue(stages.get(7).traderShare >= 0.1f && stages.get(7).traderShare <= 0.5f);''',
     '''		assertTrue(stages.get(7).traderShare >= 0.05f && stages.get(7).traderShare <= 0.5f);''', 1),
    ('''		assertTrue(crit.cornerShare >= 0.05f && crit.noKnockShare >= 0.1f && crit.oddShare >= 0.1f);''',
     '''		assertTrue(crit.cornerShare >= 0.05f && crit.noKnockShare >= 0.05f && crit.oddShare >= 0.05f);''', 1),
])
print('stages ok')
