import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	final PingCount lastQuick = new PingCount();
''',
     '''	final PingCount lastQuick = new PingCount();
	/** The fights on equal terms against a copy of itself since training began, by who moved first on each tick; see {@code EpisodePlan.agentFirst}. */
	final PingCount fairFirst = new PingCount();
	final PingCount fairSecond = new PingCount();
	int fairFirstWon;
	int fairFirstLost;
	int fairSecondWon;
	int fairSecondLost;
	boolean episodeAgentFirst;
''', 1),
    ('''				if (target == tally || target == traderTally) {
					(episodeSlow ? slowCount : quickCount).add(facts, reward);
				}
''',
     '''				if (target == tally || target == traderTally) {
					(episodeSlow ? slowCount : quickCount).add(facts, reward);
					if (episodeFair) {
						(episodeAgentFirst ? fairFirst : fairSecond).add(facts, reward);
					}
				}
''', 1),
    ('''					} else {
						boolean counts = onEqualTerms();
						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);
					}
''',
     '''					} else {
						boolean counts = onEqualTerms();
						rewards.endEpisode(target, counts && facts.koDealt, counts && facts.koTaken);
						if (episodeFair && facts.koDealt) {
							if (episodeAgentFirst) {
								fairFirstWon++;
							} else {
								fairSecondWon++;
							}
						} else if (episodeFair && facts.koTaken) {
							if (episodeAgentFirst) {
								fairFirstLost++;
							} else {
								fairSecondLost++;
							}
						}
					}
''', 1),
    ('''		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;
		episodeStutter = plan.agentStall > 0f;
''',
     '''		// Who moves first on each tick is drawn per fight; see EpisodePlan.agentFirst.
		episodeAgentFirst = againstItself && cfg.selfPlayTurns && rng.nextBoolean();
		plan.agentFirst = episodeAgentFirst;
		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;
		episodeStutter = plan.agentStall > 0f;
''', 1),
    ('''		if (curriculum.selfPlay() && !curriculum.fixedCopy()) {
			out.add(String.format(Locale.ROOT, "self-play generation %d:''',
     '''		if (curriculum.selfPlay() && fairFirst.steps + fairSecond.steps > 0) {
			out.add(String.format(Locale.ROOT, "fights on equal terms since training was started, by who moves first on each tick: the AI first %d won, %d lost (%s); the copy first %d won, %d lost (%s)",
					fairFirstWon, fairFirstLost, fairFirst.brief(), fairSecondWon, fairSecondLost, fairSecond.brief()));
		}
		if (curriculum.selfPlay() && !curriculum.fixedCopy()) {
			out.add(String.format(Locale.ROOT, "self-play generation %d:''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = '''	public boolean backRule = true;
'''
b = '''	public boolean backRule = true;
	/**
	 * Against a copy of itself, who makes its move first on each tick is drawn per fight. Up to the
	 * first runs of 0.14.0 the copy always moved first and the fighter after it. False is that.
	 */
	public boolean selfPlayTurns = true;
'''
assert s.count(a) == 1
s = s.replace(a, b)
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 51', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
