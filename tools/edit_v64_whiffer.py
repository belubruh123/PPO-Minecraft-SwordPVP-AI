ROOT = 'D:/swordai/'
S = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


C = 'core/src/main/java/dev/swordai/core/curriculum/'
edit(C + 'StageSpec.java', [
('''	public float spacerShare = 0f;
''', '''	public float spacerShare = 0f;
	/**
	 * And against the player spacing beats: it walks in and swings the moment its sword is back, on
	 * rhythm, whether or not the fighter is quite in reach. Held off at the edge of the reach it misses,
	 * and its cooldown is the opening. Where the fighter learns to space others, not only to get out
	 * of being spaced. The user, 18 October: "I WANT IT TO LEARN TO ALSO kill some ppl with this method".
	 */
	public float whifferShare = 0f;
''', 1)])
edit(C + 'Stages.java', [
('''		st.jumperShare = 0.13f;''', '''		st.jumperShare = 0.11f;''', 1),
('''		st.backerShare = 0.06f;''', '''		st.backerShare = 0.05f;''', 1),
('''		st.timerShare = 0.14f;''', '''		st.timerShare = 0.12f;''', 1),
('''		st.spacerShare = 0.08f;
''', '''		st.spacerShare = 0.08f;
		// And the one that swings on rhythm at the edge of its reach, which spacing beats; see StageSpec.whifferShare.
		st.whifferShare = 0.05f;
''', 1),
])
edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
('''				&& crit.spacerShare >= 0.05f);''', '''				&& crit.spacerShare >= 0.05f && crit.whifferShare >= 0.05f);''', 1),
('''crit.timerShare + crit.spacerShare + crit.peopleShare <= 0.97f,''', '''crit.timerShare + crit.spacerShare + crit.whifferShare + crit.peopleShare <= 0.97f,''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	final Tally standerTally = new Tally();
''', '''	final Tally standerTally = new Tally();
	final Tally whifferTally = new Tally();
''', 1),
('''	static final int KIND_SPACER = 12;
''', '''	static final int KIND_SPACER = 12;
	/** The one that swings on rhythm at the edge of its reach, which spacing beats; see {@code StageSpec.whifferShare}. */
	static final int KIND_WHIFFER = 13;
''', 1),
('''stage.timerShare, stage.peopleShare, stage.spacerShare};''', '''stage.timerShare, stage.peopleShare, stage.spacerShare, stage.whifferShare};''', 1),
('''				&& episodeKind != KIND_SPACER
''', '''				&& episodeKind != KIND_SPACER && episodeKind != KIND_WHIFFER
''', 1),
('''		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', '''		} else if (episodeKind == KIND_WHIFFER) {
			// It walks in and swings the moment its sword is back, on rhythm, whether or not the fighter is
			// quite in reach (up to half a block to a block and a fifth short): held off at the edge it misses,
			// and its cooldown is the opening. Half of them run straight in, the others come to about 2.7.
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.sprint = true;
			plan.bot.rushChance = rng.nextBoolean() ? 1f : 0f;
			plan.bot.critChance = 0.2f * rng.nextFloat();
			plan.bot.critPress = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.antic = "";
			plan.bot.reactionTicks = rng.nextInt(3);
			plan.bot.aimNoiseDeg = 1f + 2f * rng.nextFloat();
			plan.bot.turnRateDeg = 40f;
			plan.bot.attackChargeMin = 0.9f + 0.1f * rng.nextFloat();
			plan.bot.followUpTicks = Integer.MAX_VALUE / 4;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0.5f + 0.7f * rng.nextFloat();
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = 3.5f;
			plan.bot.maxDistance = 7f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', 1),
('''		if (episodeKind == KIND_SPACER) {
			return episodeStander ? standerTally : spacerTally;
		}''', '''		if (episodeKind == KIND_SPACER) {
			return episodeStander ? standerTally : spacerTally;
		}
		if (episodeKind == KIND_WHIFFER) {
			return whifferTally;
		}''', 1),
('''			pendingTally.add(standerTally);
''', '''			pendingTally.add(standerTally);
			pendingTally.add(whifferTally);
''', 1),
('''		standerTally.clear();
''', '''		standerTally.clear();
		whifferTally.clear();
''', 1),
('''| standers {} | swings made''', '''| standers {} | whiffers {} | swings made''', 1),
('''brief(standerTally), forcedAnswers''', '''brief(standerTally), brief(whifferTally), forcedAnswers''', 1),
])
edit('agg2.py', [
("""('standers', 'STANDER')]""", """('standers', 'STANDER'), ('whiffers', 'WHIFFER')]""", 1),
("""'SPACER', 'STANDER', 'face', 'older'""", """'SPACER', 'STANDER', 'WHIFFER', 'face', 'older'""", 1),
("""'SPACER', 'STANDER', 'older'""", """'SPACER', 'STANDER', 'WHIFFER', 'older'""", 1),
], root=S)
edit('abrun.sh', [('TIMER,SPACER,STANDER > ', 'TIMER,SPACER,STANDER,WHIFFER > ', 1)], root=S)
print('whiffer ok')
