ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	public boolean punish = false;
''', '''	public boolean punish = false;
	/**
	 * The share of the hits it takes on the ground that it jumps with, as a player does to be thrown
	 * less far and stay in the fight (a jump reset). Most players who fight back do it some of the
	 * time.
	 */
	public float jumpResetChance = 0f;
''', 1),
    ('''		b.punish = punish;
''', '''		b.punish = punish;
		b.jumpResetChance = jumpResetChance;
''', 1),
])
edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''	void tookHit() {
''', '''	void tookHit() {
		pendingReset = profile.jumpResetChance > 0f && rng.nextFloat() < profile.jumpResetChance;
''', 1),
    ('''	private int lagTicks;
''', '''	private int lagTicks;
	/** It has just been hit and means to jump with it on its next tick; see {@link BotProfile#jumpResetChance}. */
	private boolean pendingReset;
''', 1),
    ('''		float jumpChance = antic(BotProfile.ANTIC_HOP) ? Math.max(0.5f, profile.jumpChance) : profile.jumpChance;''',
     '''		if (pendingReset) {
			// The jump made with a hit: W and the sprint held, on the tick after it.
			pendingReset = false;
			if (bot.onGround() && critJump < 0) {
				pack.setForward(1f);
				pack.setSprinting(true);
				pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
			}
		}
		float jumpChance = antic(BotProfile.ANTIC_HOP) ? Math.max(0.5f, profile.jumpChance) : profile.jumpChance;''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float backerShare = 0f;
''', '''	public float backerShare = 0f;
	/** Each scripted opponent of this stage jumps with this share of the hits it takes; see {@code BotProfile.jumpResetChance}. */
	public float opponentJumpReset = 0f;
''', 1),
])
T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''		boolean beside = false;
		if (episodeTrader) {''', '''		boolean beside = false;
		if (scripted && stage.opponentJumpReset > 0f) {
			plan.bot.jumpResetChance = stage.opponentJumpReset * rng.nextFloat() * 2f;
		}
		if (episodeTrader) {''', 1),
    ('''						+ "resets %d, step back ticks %d, hit after its own hit %d, crits taken %d",''',
     '''						+ "resets %d, step back ticks %d, hit after its own hit %d, crits taken %d, own combos %d of %d hits",''', 1),
    ('''				t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken);''', '''				t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken, t.combos, t.comboHits);''', 1),
])
p = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'
s = open(p + 'agg2.py', encoding='utf-8').read()
a = r'''(?:, resets (?P<rs>\d+), step back ticks (?P<sbt>\d+), hit after its own hit (?P<ch>\d+), crits taken (?P<ct>\d+))?'''
assert s.count(a) == 1
s = s.replace(a, r'''(?:, resets (?P<rs>\d+), step back ticks (?P<sbt>\d+), hit after its own hit (?P<ch>\d+), crits taken (?P<ct>\d+)(?:, own combos (?P<oc>\d+) of (?P<och>\d+) hits)?)?''')
open(p + 'agg2.py', 'w', encoding='utf-8', newline='').write(s)
s = open(p + 'ab.py', encoding='utf-8').read()
a = """'  S-held  chase(crit)/m  sweeps')"""
assert s.count(a) == 1
s = s.replace(a, """'  S-held  chase(crit)/m  sweeps  own combos: /min, hits each')""")
a = """agg2.pct(a['sw'], a['h']), a['early']))"""
assert s.count(a) == 1
s = s.replace(a, """agg2.pct(a['sw'], a['h']), a['early'])
          + '   %4.1f  %4.2f' % (a.get('oc', 0) / m, a.get('och', 0) / a['oc'] if a.get('oc', 0) > 0 else 0))""")
open(p + 'ab.py', 'w', encoding='utf-8', newline='').write(s)
print('resetter ok')
