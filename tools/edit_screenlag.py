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
    ('''		// Against a copy, who moves first is drawn on every tick; see EpisodePlan.mixedTurns.
		plan.mixedTurns = againstItself && cfg.selfPlayTurns;
''',
     '''		// Against a copy, who moves first is drawn on every tick; see EpisodePlan.mixedTurns.
		plan.mixedTurns = againstItself && cfg.selfPlayTurns;
		if (againstItself || episodeDrill) {
			// On the screen the copy is given the delay the screen costs the fighter; see Config.screenCopyLagTicks.
			plan.botLag = Math.min(ReplicaBody.MAX_LAG_TICKS, plan.botLag + copyLagTicks());
		}
''', 1),
    ('''	/** Whether a fight against a copy of itself can be on equal terms at all in this kind of training; not on the screen. */
''',
     '''	/** How many ticks later than drawn a copy of itself gets to see the fighter in this kind of training; see {@code Config.screenCopyLagTicks}. */
	int copyLagTicks() {
		return 0;
	}

	/** Whether a fight against a copy of itself can be on equal terms at all in this kind of training; not on the screen. */
''', 1),
])

edit('src/client/java/dev/swordai/client/TrainingSession.java', [
    ('''	@Override
	boolean judgesCopyFights() {
		return false;
	}
''',
     '''	@Override
	boolean judgesCopyFights() {
		return false;
	}

	@Override
	int copyLagTicks() {
		return Math.max(0, cfg.screenCopyLagTicks);
	}
''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = '''	public boolean selfPlayTurns = true;
'''
b = '''	public boolean selfPlayTurns = true;
	/**
	 * Training on the screen against a copy of itself: the copy sees the fighter this many ticks
	 * later than it otherwise would. The user's character sees the copy a tick late and its clicks
	 * reach the server a tick late, as a player's do; the copy plays on the server and has neither
	 * delay, and without this the fight is one against an opponent that is always two ticks ahead
	 * (0.4 to 0.6 hits landed for each taken, against a copy it is level with off the screen).
	 */
	public int screenCopyLagTicks = 2;
'''
assert s.count(a) == 1
s = s.replace(a, b)
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 54', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
