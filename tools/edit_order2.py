import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/main/java/dev/swordai/server/EpisodePlan.java', [
    ('''	/**
	 * Against a copy of itself: the agent makes its move of each tick before the copy does, and not
	 * after it. Whoever moves second has seen what the first did on that tick, a hit included, and
	 * can answer it before either of them has moved; drawn per fight, so that neither has that for
	 * good.
	 */
	public boolean agentFirst;
''',
     '''	/**
	 * Against a copy of itself: which of the two makes its move first is drawn on every tick. Two
	 * copies of one fighter trade hit for hit on the same tick, and the hit that is dealt first is
	 * the one that counts when both are a hit from the end: with the copy always first, as up to the
	 * first runs of 0.14.0, a fighter lost 12 fights of 14 on equal terms to a copy of itself, and
	 * with the fighter always first it won 16 of 23, landing as many hits as it took either way. On
	 * a server it is whichever player's click arrives first, tick by tick.
	 */
	public boolean mixedTurns;
''', 1),
])

edit('src/main/java/dev/swordai/server/ArenaServer.java', [
    ('''	/** In the running fight the agent moves before the copy on each tick; see {@link EpisodePlan#agentFirst}. */
	private boolean agentFirst;
''',
     '''	/** In the running fight who moves first is drawn on every tick; see {@link EpisodePlan#mixedTurns}. */
	private boolean mixedTurns;
''', 1),
    ('''		s.agentFirst = plan.agentFirst;
''',
     '''		s.mixedTurns = plan.mixedTurns;
''', 1),
    ('''		if (rivalPlays && !idle && driver != null && agentFirst) {
			// The agent's move comes first in this fight, and the copy's after it.
''',
     '''		if (rivalPlays && !idle && driver != null && mixedTurns && rng.nextBoolean()) {
			// The agent's move comes first on this tick, and the copy's after it.
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	/** The fights on equal terms against a copy of itself since training began, by who moved first on each tick; see {@code EpisodePlan.agentFirst}. */
	final PingCount fairFirst = new PingCount();
	final PingCount fairSecond = new PingCount();
	int fairFirstWon;
	int fairFirstLost;
	int fairSecondWon;
	int fairSecondLost;
	boolean episodeAgentFirst;
''',
     '''	/** The fights on equal terms against a copy of itself since this training was started; see {@code StageSpec.fairShare}. */
	final PingCount fairCount = new PingCount();
	int fairWon;
	int fairLost;
''', 1),
    ('''					if (episodeFair) {
						(episodeAgentFirst ? fairFirst : fairSecond).add(facts, reward);
					}
''',
     '''					if (episodeFair) {
						fairCount.add(facts, reward);
					}
''', 1),
    ('''						if (episodeFair && facts.koDealt) {
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
''',
     '''						if (episodeFair && facts.koDealt) {
							fairWon++;
						} else if (episodeFair && facts.koTaken) {
							fairLost++;
						}
''', 1),
    ('''		// Who moves first on each tick is drawn per fight; see EpisodePlan.agentFirst.
		episodeAgentFirst = againstItself && cfg.selfPlayTurns && rng.nextBoolean();
		plan.agentFirst = episodeAgentFirst;
''',
     '''		// Against a copy, who moves first is drawn on every tick; see EpisodePlan.mixedTurns.
		plan.mixedTurns = againstItself && cfg.selfPlayTurns;
''', 1),
    ('''		if (curriculum.selfPlay() && fairFirst.steps + fairSecond.steps > 0) {
			out.add(String.format(Locale.ROOT, "fights on equal terms since training was started, by who moves first on each tick: the AI first %d won, %d lost (%s); the copy first %d won, %d lost (%s)",
					fairFirstWon, fairFirstLost, fairFirst.brief(), fairSecondWon, fairSecondLost, fairSecond.brief()));
		}
''',
     '''		if (curriculum.selfPlay() && fairCount.steps > 0) {
			out.add(String.format(Locale.ROOT, "fights on equal terms against a copy of itself since this training was started: %d won, %d lost (%s)",
					fairWon, fairLost, fairCount.brief()));
		}
''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = s[s.index('	/**\n	 * Against a copy of itself, who makes its move first'):s.index('	public boolean selfPlayTurns = true;\n') + len('	public boolean selfPlayTurns = true;\n')]
b = '''	/**
	 * Against a copy of itself, who makes its move first is drawn on every tick. Up to the first runs
	 * of 0.14.0 the copy always moved first and the fighter after it, and that alone decided most
	 * fights between equals; see {@code EpisodePlan.mixedTurns}. False is that.
	 */
	public boolean selfPlayTurns = true;
'''
s = s.replace(a, b)
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 52', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
