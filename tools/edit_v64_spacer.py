ROOT = 'D:/swordai/'


def edit(path, pairs):
    full = ROOT + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


C = 'core/src/main/java/dev/swordai/core/curriculum/'
edit(C + 'BotProfile.java', [
('''	public boolean timer = false;
''', '''	public boolean timer = false;
	/**
	 * With {@link #holdGround}: it does not walk at all, not even back to where a hit took it from.
	 * It stands, turns to face the agent and swings the moment the agent is in its reach with its
	 * sword back, with nothing late and nothing off. The user, 18 October: a "PERFECT 3 block not
	 * moving" player, "just pure perfect combo".
	 */
	public boolean spacer = false;
''', 1),
('''		b.timer = timer;
''', '''		b.timer = timer;
		b.spacer = spacer;
''', 1)])

edit(C + 'StageSpec.java', [
('''	public float timerShare = 0f;
''', '''	public float timerShare = 0f;
	/**
	 * And against one that stands still and swings, perfectly, the moment the fighter is in its
	 * reach with its sword back ({@code BotProfile.spacer}). What beats it is coming straight back
	 * after its hit, while its sword is on the way back: it deals only the knockback of a standing
	 * swing and does not follow.
	 */
	public float spacerShare = 0f;
''', 1)])

edit(C + 'Stages.java', [
('''		st.traderShare = 0.07f;''', '''		st.traderShare = 0.06f;''', 1),
('''		st.cornerShare = 0.06f;
		st.noKnockShare = 0.07f;
		st.oddShare = 0.06f;''', '''		st.cornerShare = 0.05f;
		st.noKnockShare = 0.05f;
		st.oddShare = 0.05f;''', 1),
('''		st.jumperShare = 0.16f;''', '''		st.jumperShare = 0.13f;''', 1),
('''		st.timerShare = 0.14f;
''', '''		st.timerShare = 0.14f;
		// And the one that stands still and hits perfectly at the edge of its reach; see StageSpec.spacerShare.
		st.spacerShare = 0.08f;
''', 1)])

edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
('''		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f && crit.backerShare >= 0.05f && crit.timerShare >= 0.1f);''',
 '''		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f && crit.backerShare >= 0.05f && crit.timerShare >= 0.1f
				&& crit.spacerShare >= 0.05f);''', 1),
('''				+ crit.jumperShare + crit.punisherShare + crit.backerShare + crit.timerShare <= 0.97f, "a share is left for the opponent a sprint hit knocks away");''',
 '''				+ crit.jumperShare + crit.punisherShare + crit.backerShare + crit.timerShare + crit.spacerShare + crit.peopleShare <= 0.97f,
				"a share is left for the opponent a sprint hit knocks away");''', 1),
('''		jumper.timer = true;
		assertTrue(jumper.copy().jumpIn && jumper.copy().punish && jumper.copy().timer);
		assertFalse(crit.bot.jumpIn || crit.bot.punish || crit.bot.timer);''', '''		jumper.timer = true;
		jumper.spacer = true;
		assertTrue(jumper.copy().jumpIn && jumper.copy().punish && jumper.copy().timer && jumper.copy().spacer);
		assertFalse(crit.bot.jumpIn || crit.bot.punish || crit.bot.timer || crit.bot.spacer);''', 1)])

edit('src/main/java/dev/swordai/server/BotBrain.java', [
('''					// It stands where it was put, and walks back there if a hit has moved it.
					backoffTicks = 0;
					double px = postX - bot.getX();
					double pz = postZ - bot.getZ();
					if (px * px + pz * pz > POST_SLACK * POST_SLACK && critJump < 0) {''', '''					// It stands where it was put, and walks back there if a hit has moved it. One that holds
					// whatever spot it is on (BotProfile.spacer) does not walk at all.
					backoffTicks = 0;
					double px = postX - bot.getX();
					double pz = postZ - bot.getZ();
					if (!profile.spacer && px * px + pz * pz > POST_SLACK * POST_SLACK && critJump < 0) {''', 1)])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	final Tally personTally = new Tally();
''', '''	final Tally personTally = new Tally();
	final Tally spacerTally = new Tally();
''', 1),
('''	static final int KIND_PERSON = 11;
''', '''	static final int KIND_PERSON = 11;
	/** The one that stands still and hits perfectly at the edge of its reach; see {@code BotProfile.spacer}. */
	static final int KIND_SPACER = 12;
''', 1),
('''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare, stage.peopleShare};''',
 '''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare, stage.peopleShare, stage.spacerShare};''', 1),
('''				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && episodeKind != KIND_TIMER && episodeKind != KIND_PERSON
''', '''				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && episodeKind != KIND_TIMER && episodeKind != KIND_PERSON
				&& episodeKind != KIND_SPACER
''', 1),
('''		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', '''		} else if (episodeKind == KIND_SPACER) {
			// It stands where it is and never walks, not even back to where a hit took it from; it turns to
			// face the fighter and swings the moment the fighter is in its reach with its sword back, with
			// nothing late and nothing off, and never jumps. The user, 18 October: "PERFECT 3 block not
			// moving", "just pure perfect combo".
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.holdGround = true;
			plan.bot.spacer = true;
			plan.bot.sprint = false;
			plan.bot.critChance = 0f;
			plan.bot.critPress = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.antic = "";
			plan.bot.reactionTicks = 0;
			plan.bot.aimNoiseDeg = 0.3f + 0.7f * rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 0.92f + 0.08f * rng.nextFloat();
			plan.bot.followUpTicks = dev.swordai.core.env.TakenStreak.COMBO_TICKS;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.jumpResetChance = 0.5f * rng.nextFloat();
			plan.bot.farChance = 0f;
			plan.bot.minDistance = 3.5f;
			plan.bot.maxDistance = 6f;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', 1),
('''		if (episodeKind == KIND_TIMER) {
			// The user's connection''', '''		if (episodeKind == KIND_TIMER || episodeKind == KIND_SPACER) {
			// The user's connection''', 1),
('''		if (episodeKind == KIND_PERSON) {
			return personTally;
		}
		return episodeStageIndex''', '''		if (episodeKind == KIND_PERSON) {
			return personTally;
		}
		if (episodeKind == KIND_SPACER) {
			return spacerTally;
		}
		return episodeStageIndex''', 1),
('''			pendingTally.add(personTally);
''', '''			pendingTally.add(personTally);
			pendingTally.add(spacerTally);
''', 1),
('''		personTally.clear();
		if (slowCount''', '''		personTally.clear();
		spacerTally.clear();
		if (slowCount''', 1),
('''| timers {} | people {} | swings made''', '''| timers {} | people {} | spacers {} | swings made''', 1),
('''brief(timerTally), brief(personTally), forcedAnswers''', '''brief(timerTally), brief(personTally), brief(spacerTally), forcedAnswers''', 1),
])
print('spacer ok')
