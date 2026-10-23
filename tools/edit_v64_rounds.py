ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


C = 'core/src/main/java/dev/swordai/core/curriculum/'
edit(C + 'Tally.java', [
('''	public double damageTaken;
''', '''	public double damageTaken;
	/**
	 * Rounds of a life each (20 health): every time 20 has been dealt or taken since the last round
	 * ended, the round goes to whoever dealt more in it. The opponents in training cannot die, so this
	 * is what says how often it would have won; see {@code RewardModel.ROUND_HEALTH}.
	 */
	public long roundsWon;
	public long roundsLost;
''', 1),
('''		damageTaken += t.damageTaken;
''', '''		damageTaken += t.damageTaken;
		roundsWon += t.roundsWon;
		roundsLost += t.roundsLost;
''', 1),
('''		damageTaken = 0;
''', '''		damageTaken = 0;
		roundsWon = 0;
		roundsLost = 0;
''', 1),
])
edit(C + 'RewardModel.java', [
('''		tally.damageDealt += f.damageDealt;
		tally.damageTaken += f.damageTaken;
		tally.rewardSum += r;''', '''		tally.damageDealt += f.damageDealt;
		tally.damageTaken += f.damageTaken;
		roundDealt += f.damageDealt;
		roundTaken += f.damageTaken;
		if (roundDealt >= ROUND_HEALTH || roundTaken >= ROUND_HEALTH) {
			if (roundDealt >= roundTaken) {
				tally.roundsWon++;
			} else {
				tally.roundsLost++;
			}
			roundDealt = 0f;
			roundTaken = 0f;
		}
		tally.rewardSum += r;''', 1),
('''	public void reset() {
		combo = 0;''', '''	public void reset() {
		combo = 0;
		roundDealt = 0f;
		roundTaken = 0f;''', 1),
('''	public void endEpisode(Tally tally, boolean won, boolean lost) {''', '''	/** A life: a round is over once this much has been dealt or taken in it; see {@link Tally#roundsWon}. */
	public static final float ROUND_HEALTH = 20f;
	private float roundDealt;
	private float roundTaken;

	public void endEpisode(Tally tally, boolean won, boolean lost) {''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
('''own combos %d of %d hits, hops %d, damage %.0f dealt / %.0f taken",''', '''own combos %d of %d hits, hops %d, damage %.0f dealt / %.0f taken, rounds %d won %d lost",''', 1),
('''t.hopRuleJumps, t.damageDealt, t.damageTaken);''', '''t.hopRuleJumps, t.damageDealt, t.damageTaken, t.roundsWon, t.roundsLost);''', 1),
])
S = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'
edit('agg2.py', [
('''(?:, damage (?P<dd>\\d+) dealt / (?P<dt>\\d+) taken)?''', '''(?:, damage (?P<dd>\\d+) dealt / (?P<dt>\\d+) taken(?:, rounds (?P<rw>\\d+) won (?P<rl>\\d+) lost)?)?''', 1),
], root=S)
edit('ab.py', [
('''+ '  S-held  chase(crit)/m  sweeps  own combos: /min, hits each   damage/min dealt/taken')''',
 '''+ '  S-held  chase(crit)/m  sweeps  own combos: /min, hits each   damage/min dealt/taken   rounds won')''', 1),
('''          + '   %5.1f / %-5.1f' % (a.get('dd', 0) / m, a.get('dt', 0) / m))''',
 '''          + '   %5.1f / %-5.1f' % (a.get('dd', 0) / m, a.get('dt', 0) / m)
          + '   %d/%d = %.0f%%' % (a.get('rw', 0), a.get('rw', 0) + a.get('rl', 0), agg2.pct(a.get('rw', 0), a.get('rw', 0) + a.get('rl', 0))))''', 1),
], root=S)
print('rounds ok')
