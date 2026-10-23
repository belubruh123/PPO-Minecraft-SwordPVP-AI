import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:60], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('combos over %d of which without a swing back %d",',
     'combos over %d of which without a swing back %d (%d of them three hits or more), S held %d/%d",', 1),
    ('''				t.combosOver, t.combosUnanswered);
	}''',
     '''				t.combosOver, t.combosUnanswered, t.combosSatThrough, t.pushHeld, t.pushTicks);
	}''', 1),
    ('''		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (pendingUpdate != null) {''',
     '''		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		// In the ticks after a hit of a combo it is taking, S is held for it; see Config.backRule.
		boolean backOut = cfg.backRule && curriculum.current().backNudgeKeep > 0f && learner.backNudge() > 0f
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		if (pendingUpdate != null) {''', 1),
    ('''		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}
''',
     '''		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
''', 1),
])
p = ROOT + 'src/client/java/dev/swordai/client/Trainer.java'
s = open(p, encoding='utf-8').read()
if 'import dev.swordai.core.rl.BackNudge;' not in s:
    a = 'import dev.swordai.core.rl.AnswerRule;\n'
    assert a in s
    s = s.replace(a, a + 'import dev.swordai.core.rl.BackNudge;\n', 1)
    open(p, 'w', encoding='utf-8', newline='').write(s)

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = '''	public boolean answerRule = true;
'''
b = '''	public boolean answerRule = true;
	/**
	 * In the twelve ticks after each hit of a combo it is taking (from the second hit in a row), S is
	 * held for it: it backs out, and comes in again for the first hit. A rule in place of the push
	 * ({@code BackNudge}) from the stage on that keeps the push for good ({@code StageSpec.backNudgeKeep}).
	 * False leaves it to the push and to the network.
	 */
	public boolean backRule = false;
'''
assert s.count(a) == 1
s = s.replace(a, b)
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 47', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final boolean answerRule;
''',
     '''	private final boolean answerRule;
	/** S is held for it in the ticks after a hit of a combo it is taking; see {@code Config.backRule}. */
	private boolean backOutRule;
''', 1),
    ('''	/** Whether the swing of the last decision was made by that rule.''',
     '''	/**
	 * Whether backing out of a combo is a rule for it, as it is in training from the stage on that
	 * keeps the push for S; see {@code Config.backRule}.
	 */
	public void backOutRule(boolean on) {
		backOutRule = on;
	}

	/** Whether the swing of the last decision was made by that rule.''', 1),
    ('''		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		learner.act(obs, keys, false, false, temperature, action);
		if (assist) {
			idea.assist(action, headMask, visible);
		}
		if (answer) {
			action[ActionSpace.ATTACK] = 1;
		}
''',
     '''		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		boolean backOut = backOutRule && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		learner.act(obs, keys, false, false, temperature, action);
		if (assist) {
			idea.assist(action, headMask, visible);
		}
		if (answer) {
			action[ActionSpace.ATTACK] = 1;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
''', 1),
])

edit('src/client/java/dev/swordai/client/LoadedFighter.java', [
    ('''		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, System.nanoTime());
		fighter.planHolds(curriculum.current().reward.mixTiming > 0f);
''',
     '''		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, System.nanoTime());
		fighter.backOutRule(cfg.backRule && curriculum.current().backNudgeKeep > 0f);
		fighter.planHolds(curriculum.current().reward.mixTiming > 0f);
''', 1),
    ('''		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, 1f, System.nanoTime());
		fighter.planHolds(curriculum.current().reward.mixTiming > 0f);
''',
     '''		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, 1f, System.nanoTime());
		fighter.backOutRule(cfg.backRule && curriculum.current().backNudgeKeep > 0f);
		fighter.planHolds(curriculum.current().reward.mixTiming > 0f);
''', 1),
])

SP = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'
edit('agg.ps1', [
    ('''(?:, combos over (\\d+) of which without a swing back (\\d+))?)?)?') {''',
     '''(?:, combos over (\\d+) of which without a swing back (\\d+)(?: \\((\\d+) of them three hits or more\\), S held (\\d+)/(\\d+))?)?)?)?') {''', 1),
    ("""'over','noSwing')""", """'over','noSwing','sat','sHeld','sTicks')""", 1),
    ("over = 0.0; noSwing = 0.0 }", "over = 0.0; noSwing = 0.0; sat = 0.0; sHeld = 0.0; sTicks = 0.0 }", 2),
    ('''        if ($Matches[20] -ne $null -and $Matches[20] -ne '') { $o.over = [double]$Matches[20]; $o.noSwing = [double]$Matches[21] }
''',
     '''        if ($Matches[20] -ne $null -and $Matches[20] -ne '') { $o.over = [double]$Matches[20]; $o.noSwing = [double]$Matches[21] }
        if ($Matches[22] -ne $null -and $Matches[22] -ne '') { $o.sat = [double]$Matches[22]; $o.sHeld = [double]$Matches[23]; $o.sTicks = [double]$Matches[24] }
''', 1),
    ('''noSwingBack {11:N0}/{12:N0}' -f''', '''noSwingBack {11:N0}/{12:N0} (3+hits: {13:N0}) S {14}' -f''', 1),
    ('''$a.two, $out2, $a.noSwing, $a.over)''', '''$a.two, $out2, $a.noSwing, $a.over, $a.sat, $sh)''', 1),
    ('''    $out2 = '  -';''',
     '''    $sh = '  -'; if ($a.sTicks -gt 0) { $sh = ('{0,3:N0}%' -f (100 * $a.sHeld / $a.sTicks)) }
    $out2 = '  -';''', 1),
], root=SP)
print('ok')
