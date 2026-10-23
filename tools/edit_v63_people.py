ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float timerShare = 0f;
''', '''	public float timerShare = 0f;
	/**
	 * And against scripted copies of real players it has fought in {@code /swordai fight}: their
	 * habits, counted from those fights, played out by the script ({@code PersonStyle}). The players it
	 * lost to, or nearly, come up most. Where there are none yet, these fights are against the stage's
	 * own opponent.
	 */
	public float peopleShare = 0f;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.timerShare = 0.14f;''', '''		st.timerShare = 0.14f;
		// And copies of the players it has fought; see StageSpec.peopleShare.
		st.peopleShare = 0.05f;''', 1),
])
T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	static final int KIND_TIMER = 10;
''', '''	static final int KIND_TIMER = 10;
	/** A scripted copy of a player it has fought; see {@code PersonStyle}. */
	static final int KIND_PERSON = 11;
''', 1),
    ('''	final Tally timerTally = new Tally();
''', '''	final Tally timerTally = new Tally();
	final Tally personTally = new Tally();
''', 1),
    ('''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare};''',
     '''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare, stage.peopleShare};''', 1),
    ('''				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && episodeKind != KIND_TIMER && rng.nextFloat() < stage.randomShare) {''',
     '''				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && episodeKind != KIND_TIMER && episodeKind != KIND_PERSON
				&& rng.nextFloat() < stage.randomShare) {''', 1),
    ('''		} else if (episodeKind == KIND_TIMER) {
			// As sharp as the one that comes after it with a running jump, and it knows the fighter's rhythm.''', '''		} else if (episodeKind == KIND_PERSON) {
			episodePerson = pickPerson();
			if (episodePerson == null) {
				episodeKind = KIND_PLAIN;
			} else {
				episodePerson.applyTo(plan.bot, rng);
				plan.bot.minDistance = BotProfile.DRILL_MIN_DISTANCE;
				plan.bot.maxDistance = BotProfile.DRILL_MAX_DISTANCE;
				plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
				plan.bot.minBearingDeg = 0f;
				plan.bot.farChance = 0f;
				plan.bot.maxPillar = 0;
			}
		} else if (episodeKind == KIND_TIMER) {
			// As sharp as the one that comes after it with a running jump, and it knows the fighter's rhythm.''', 1),
    ('''		} else if (episodeKind == KIND_TIMER) {
			// On a server that hands out somewhat less.
			plan.botKnockback = 0.4f + 0.5f * rng.nextFloat();''', '''		} else if (episodeKind == KIND_TIMER) {
			// On a server that hands out somewhat less.
			plan.botKnockback = 0.4f + 0.5f * rng.nextFloat();
		} else if (episodeKind == KIND_PERSON) {
			// As far as that player was seen to go when hit.
			plan.botKnockback = episodePerson.knockback();''', 1),
    ('''		if (episodeKind == KIND_TIMER) {
			// The user's connection and his friend's''', '''		if (episodeKind == KIND_PERSON) {
			// That player's connection, as the server reported it.
			episodeSlow = false;
			int lag = episodePerson.lagTicks();
			plan.botPing = 2 * lag;
			plan.agentLag = Math.min(ReplicaBody.MAX_LAG_TICKS, lag + 1);
			plan.botLag = lag;
		}
		if (episodeKind == KIND_TIMER) {
			// The user's connection and his friend's''', 1),
    ('''		if (episodeKind == KIND_TIMER) {
			return timerTally;
		}''', '''		if (episodeKind == KIND_TIMER) {
			return timerTally;
		}
		if (episodeKind == KIND_PERSON) {
			return personTally;
		}''', 1),
    ('''			pendingTally.add(timerTally);
''', '''			pendingTally.add(timerTally);
			pendingTally.add(personTally);
''', 1),
    ('''		timerTally.clear();
''', '''		timerTally.clear();
		personTally.clear();
''', 1),
    ('''| backers {} | timers {} | swings made by the rules {}''', '''| backers {} | timers {} | people {} | swings made by the rules {}''', 1),
    ('''brief(backerTally), brief(timerTally), forcedAnswers, keptAnswers,''', '''brief(backerTally), brief(timerTally), brief(personTally), forcedAnswers, keptAnswers,''', 1),
    ('''	/** One of the counts of the scripted opponent that plays on its rhythm, cleared as it is read. */''', '''	/** The player the running fight is a copy of, if it is one; see {@link #KIND_PERSON}. */
	private dev.swordai.core.curriculum.PersonStyle episodePerson;
	/** The players there are copies of, read again now and then: the user may be fighting meanwhile. */
	private java.util.List<dev.swordai.core.curriculum.PersonStyle> people = java.util.List.of();
	private long peopleReadAt;

	/**
	 * A player to fight a copy of, or null if there is none: those it lost rounds to come up most, and
	 * those it beat by little next. Rounds lost count three times, and each player counts at least once.
	 */
	private dev.swordai.core.curriculum.PersonStyle pickPerson() {
		long now = System.currentTimeMillis();
		if (now - peopleReadAt > 600_000L) {
			people = StyleWatch.known();
			peopleReadAt = now;
		}
		if (people.isEmpty()) {
			return null;
		}
		double total = 0;
		double[] weight = new double[people.size()];
		for (int i = 0; i < weight.length; i++) {
			dev.swordai.core.curriculum.PersonStyle p = people.get(i);
			double close = p.hitsDealt > 0 ? Math.min(2.0, (double) p.hitsTaken / p.hitsDealt) : 1.0;
			weight[i] = 1.0 + 3.0 * p.losses + 2.0 * close;
			total += weight[i];
		}
		double r = rng.nextDouble() * total;
		for (int i = 0; i < weight.length; i++) {
			r -= weight[i];
			if (r <= 0) {
				return people.get(i);
			}
		}
		return people.get(people.size() - 1);
	}

	/** One of the counts of the scripted opponent that plays on its rhythm, cleared as it is read. */''', 1),
    ('''		if (!episodeDrill && episodeStageIndex != curriculum.stageIndex() && plan.bot.critChance > 0f) {''',
     '''		if (!episodeDrill && episodeStageIndex != curriculum.stageIndex() && plan.bot.critChance > 0f && episodeKind != KIND_PERSON) {''', 1),
])
print('people ok')
