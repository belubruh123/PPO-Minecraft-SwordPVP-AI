ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	public float dodgeChance = 0f;
''', '''	public float dodgeChance = 0f;
	/** For how many ticks it then backs off: a quarter of a second, or with twelve, the way out of a combo that S is. */
	public int dodgeTicks = 5;
''', 1),
    ('''		b.dodgeChance = dodgeChance;
''', '''		b.dodgeChance = dodgeChance;
		b.dodgeTicks = dodgeTicks;
''', 1),
])
edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = BACKOFF_TICKS;
		}
		if (profile.fleeUntilHit) {''', '''		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = Math.max(1, profile.dodgeTicks);
		}
		if (profile.fleeUntilHit) {''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float jumperShare = 0f;
	public float punisherShare = 0f;
''', '''	public float jumperShare = 0f;
	public float punisherShare = 0f;
	/**
	 * And against one that does what this fighter did until then: hit, it walks backwards for twelve
	 * ticks with its eyes on the fighter and its sword ready. That is the opponent the running jump
	 * and the critical hit on the way down are for ({@code JumpIn.chaseInCombo}): where the fighter
	 * practises on someone else what was done to it.
	 */
	public float backerShare = 0f;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.jumperShare = 0.2f;
		st.punisherShare = 0.15f;
''', '''		st.jumperShare = 0.2f;
		st.punisherShare = 0.15f;
		st.backerShare = 0.08f;
''', 1),
])
edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
    ('''		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare
				+ crit.jumperShare + crit.punisherShare <= 0.95f, "a share is left for the opponent a sprint hit knocks away");''',
     '''		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f && crit.backerShare >= 0.05f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare
				+ crit.jumperShare + crit.punisherShare + crit.backerShare <= 0.97f, "a share is left for the opponent a sprint hit knocks away");
		assertEquals(5, crit.bot.dodgeTicks);
		BotProfile backer = crit.bot.copy();
		backer.dodgeTicks = 12;
		assertEquals(12, backer.copy().dodgeTicks);''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	static final int KIND_PUNISHER = 8;
''', '''	static final int KIND_PUNISHER = 8;
	/** The one that backs out of a combo with S, as this fighter did; see {@code StageSpec.backerShare}. */
	static final int KIND_BACKER = 9;
''', 1),
    ('''	final Tally punisherTally = new Tally();
''', '''	final Tally punisherTally = new Tally();
	final Tally backerTally = new Tally();
''', 1),
    ('''					stage.jumperShare, stage.punisherShare};''', '''					stage.jumperShare, stage.punisherShare, stage.backerShare};''', 1),
    ('''		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''',
     '''		} else if (episodeKind == KIND_BACKER) {
			// Hit, it walks backwards with its eyes on the fighter and its sword ready, for twelve ticks:
			// what this fighter did about a combo until a player showed what that is good for.
			plan.bot.dodgeChance = 1f;
			plan.bot.dodgeTicks = 12;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.critChance = 0f;
			plan.bot.sprint = true;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', 1),
    ('''		if (episodeKind == KIND_PUNISHER) {
			return punisherTally;
		}
''', '''		if (episodeKind == KIND_PUNISHER) {
			return punisherTally;
		}
		if (episodeKind == KIND_BACKER) {
			return backerTally;
		}
''', 1),
    ('''			pendingTally.add(punisherTally);
		}''', '''			pendingTally.add(punisherTally);
			pendingTally.add(backerTally);
		}''', 1),
    ('''		punisherTally.clear();
		if (slowCount.steps > 0) {''', '''		punisherTally.clear();
		backerTally.clear();
		if (slowCount.steps > 0) {''', 1),
    ('''| jump critters {} | punishers {} | swings made by the rules {}''', '''| jump critters {} | punishers {} | backers {} | swings made by the rules {}''', 1),
    ('''brief(jumperTally), brief(punisherTally), forcedAnswers, keptAnswers,''', '''brief(jumperTally), brief(punisherTally), brief(backerTally), forcedAnswers, keptAnswers,''', 1),
    ('''+ jumperTally.punishCrits + punisherTally.punishCrits,''', '''+ jumperTally.punishCrits + punisherTally.punishCrits + backerTally.punishCrits,''', 1),
])
# The randomised draw must not undo what makes a backer one.
s = open(ROOT + T, encoding='utf-8').read()
a = '''		if (scripted && episodeKind != KIND_CIRCLER && episodeKind != KIND_CORNER && episodeKind != KIND_NO_KNOCK
				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && rng.nextFloat() < stage.randomShare) {'''
assert s.count(a) == 1
open(ROOT + T, 'w', encoding='utf-8', newline='').write(s)

p = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'
s = open(p + 'agg2.py', encoding='utf-8').read()
a = "('jump critters', 'JUMPER'), ('punishers', 'PUNISHER')]"
assert s.count(a) == 1
s = s.replace(a, "('jump critters', 'JUMPER'), ('punishers', 'PUNISHER'), ('backers', 'BACKER')]")
s = s.replace("'JUMPER', 'PUNISHER', 'face', 'older')))", "'JUMPER', 'PUNISHER', 'BACKER', 'face', 'older')))")
s = s.replace("'JUMPER', 'PUNISHER', 'older'):", "'JUMPER', 'PUNISHER', 'BACKER', 'older'):")
open(p + 'agg2.py', 'w', encoding='utf-8', newline='').write(s)
s = open(p + 'ab.py', encoding='utf-8').read()
s = s.replace("ODD,JUMPER,PUNISHER,older", "ODD,JUMPER,PUNISHER,BACKER,older")
open(p + 'ab.py', 'w', encoding='utf-8', newline='').write(s)
s = open(p + 'reqs.py', encoding='utf-8').read()
s = s.replace("'NOKB', 'ODD', 'older', 'combo')", "'NOKB', 'ODD', 'JUMPER', 'PUNISHER', 'BACKER', 'older', 'combo')")
open(p + 'reqs.py', 'w', encoding='utf-8', newline='').write(s)
s = open(p + 'setcfg.py', encoding='utf-8').read()
a = "        target[parts[-1]] = conv(target[parts[-1]], value)"
assert s.count(a) == 1
s = s.replace(a, "        target[parts[-1]] = conv(target.get(parts[-1], 0.0), value)")
open(p + 'setcfg.py', 'w', encoding='utf-8', newline='').write(s)
print('backer ok')
