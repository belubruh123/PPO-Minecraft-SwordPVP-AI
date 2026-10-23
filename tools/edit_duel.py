import re

ROOT = 'D:/swordai/'
SP = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	/**
	 * Whether the running fight is one on equal terms: the opponent's knockback is not late. Only such
	 * a fight counts as won or lost against a copy of itself. One whose knockback comes late stays in
	 * reach after it is hit and swings back, while the fighter is thrown clear by every hit it takes:
	 * measured against the copy it was a minute before, it landed 38 hits a minute and took 57 where
	 * the knockback came a quarter to half a second late. Those fights are there to be learned from.
	 * Who wins them says how much a slow connection is worth, not which of the two is the better.
	 */
	boolean onEqualTerms() {
		return episodeKnockLag == 0;
	}
''',
     '''	/**
	 * Whether the running fight is one on equal terms: the opponent's knockback is not late, and it is
	 * knocked back as far as the fighter is. Only such a fight counts as won or lost against a copy of
	 * itself. One whose knockback comes late stays in reach after it is hit and swings back, while the
	 * fighter is thrown clear by every hit it takes: measured against the copy it was a minute before,
	 * it landed 38 hits a minute and took 57 where the knockback came a quarter to half a second late.
	 * One that is knocked back less is back in reach sooner, which is the same advantage in small.
	 * Those fights are there to be learned from. Who wins them says how much a slow connection or a
	 * shorter knockback is worth, not which of the two is the better.
	 */
	boolean onEqualTerms() {
		return episodeKnockLag == 0 && episodeBotKnockback >= 1f;
	}
''', 1),
    ('''	int episodeKnockLag;
''',
     '''	int episodeKnockLag;
	/** The share of a hit's knockback the opponent of the running fight takes; see {@link #onEqualTerms}. */
	float episodeBotKnockback = 1f;
''', 1),
    ('''		episodeKnockLag = plan.botPing;
''',
     '''		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;
''', 1),
    ('''counted are the fights on equal terms, in which the copy's knockback is not late)",''',
     '''counted are the fights on equal terms, in which the copy's knockback is neither late nor shorter, about one in ten)",''', 1),
    ('''(those in which the copy's knockback is not late, about one in five; the rest are fought and learned from, and not counted)",''',
     '''(those in which the copy's knockback is neither late nor shorter, about one in ten; the rest are fought and learned from, and not counted)",''', 1),
    ('''slow connection {} | ordinary connection {} | combo drills {} | swings made by the answer rule {}",''',
     '''slow connection {} | ordinary connection {} | combo drills {} | scripted opponents of earlier stages {} | swings made by the answer rule {}",''', 1),
    ('''				slowCount.brief(), quickCount.brief(), brief(drillTally), forcedAnswers);''',
     '''				slowCount.brief(), quickCount.brief(), brief(drillTally), brief(rehearsalTally), forcedAnswers);''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.promote.minSteps = 60_000;
		st.promote.windowSteps = 30_000;
		st.promote.minFights = 20;
		st.promote.minWinRate = 0.80f;
''',
     '''		st.promote.minSteps = 60_000;
		// One fight in ten is on equal terms and counted (see Trainer.onEqualTerms), and a fight is a
		// few hundred steps: over 30,000 steps, as up to the first runs of 0.14.0, there were often
		// fewer than the twenty it takes to say anything, and the stage could not be passed however
		// the fights went.
		st.promote.windowSteps = 90_000;
		st.promote.minFights = 20;
		st.promote.minWinRate = 0.80f;
''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 49', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

edit('agg.ps1', [
    ('''(?: \\| combo drills (.*))?$') {''',
     '''(?: \\| combo drills (.*?))?(?: \\| scripted opponents of earlier stages (.*?))?(?: \\| swings made by the answer rule (\\d+))?$') {''', 1),
    ('''cd = (Parse ([string]$Matches[10])) })''',
     '''cd = (Parse ([string]$Matches[10])); reh = (Parse ([string]$Matches[11])) })''', 1),
    ('''$slow = Zero; $ord = Zero; $cd = Zero
''', '''$slow = Zero; $ord = Zero; $cd = Zero; $reh = Zero
''', 1),
    ('''Add $cd $parsed[$k].cd }''', '''Add $cd $parsed[$k].cd; Add $reh $parsed[$k].reh }''', 1),
    ('''| OWN {2} | TRADER {3} | COMBO DRILL {4}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (ShowSwings $own), (ShowSwings $tr), (ShowSwings $cd)))''',
     '''| OWN {2} | TRADER {3} | COMBO DRILL {4} | SCRIPTED {5}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (ShowSwings $own), (ShowSwings $tr), (ShowSwings $cd), (ShowSwings $reh)))''', 1),
    ('''| SLOW {6,-32} | ORD {7}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (Show $own $all), (Show $tr $all), (Show $d1 $all), $pp, (ShowPing $slow), (ShowPing $ord)))''',
     '''| SLOW {6,-32} | ORD {7,-32} | SCRIPTED {8}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (Show $own $all), (Show $tr $all), (Show $d1 $all), $pp, (ShowPing $slow), (ShowPing $ord), (ShowPing $reh)))''', 1),
], root=SP)
print('ok')
