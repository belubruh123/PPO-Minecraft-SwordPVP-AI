import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


# The rule is the default, and it does not hang on the strength of the push.
p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = s[s.index('	/**\n	 * In the twelve ticks after each hit of a combo it is taking'):s.index('	public boolean backRule = false;\n') + len('	public boolean backRule = false;\n')]
b = '''	/**
	 * In the twelve ticks after each hit of a combo it is taking (from the second hit in a row), S is
	 * held for it: it backs out, and comes in again for the first hit. A rule, from the stage on that
	 * keeps the push for S for good ({@code StageSpec.backNudgeKeep}), in training and in every fight.
	 * It is what the push at its full strength came to anyway (S on 94 to 98 ticks in 100 of those),
	 * said outright, and nothing the network learns can wear it down. False leaves it to the push.
	 */
	public boolean backRule = true;
'''
s = s.replace(a, b)
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 48', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

edit('core/src/main/java/dev/swordai/core/curriculum/Tally.java', [
    ('''	public float backRate() {
		return ratio(backHeld, backTicks);
	}
''',
     '''	public float backRate() {
		return ratio(backHeld, backTicks);
	}

	/** The share of the ticks in which backing out is asked of it that it held S. */
	public float pushRate() {
		return ratio(pushHeld, pushTicks);
	}
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	private void setLessons(boolean ownStage) {
		learner.setJumpNudge(ownStage ? curriculum.jumpNudge() : 0f);
		learner.setBackNudge(ownStage ? curriculum.backNudge() : 0f);
	}
''',
     '''	private void setLessons(boolean ownStage) {
		learner.setJumpNudge(ownStage ? curriculum.jumpNudge() : 0f);
		// Where backing out of a combo is a rule there is nothing left to push for; see Config.backRule.
		backRuleNow = ownStage && Report.backRule(cfg, curriculum);
		learner.setBackNudge(ownStage && !backRuleNow ? curriculum.backNudge() : 0f);
	}
''', 1),
    ('''		boolean backOut = cfg.backRule && curriculum.current().backNudgeKeep > 0f && learner.backNudge() > 0f
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
''',
     '''		boolean backOut = backRuleNow && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
''', 1),
    ('''	boolean answerAtAction;
''',
     '''	boolean answerAtAction;
	/** In the fight that is on, S is held for it in the ticks after a hit of a combo; see {@code Config.backRule}. */
	private boolean backRuleNow;
''', 1),
    ('''		String backHelp = Report.backLine(curriculum);
		if (backHelp != null) {
			out.add(backHelp + String.format(Locale.ROOT, "; since the last update it held S on %.0f%% of those ticks", tally.backRate() * 100f));
		}
''',
     '''		String backHelp = Report.backLine(cfg, curriculum);
		if (backHelp != null) {
			out.add(backHelp + String.format(Locale.ROOT, "; since the last update it held S on %.0f%% of those ticks (%d of them)", tally.pushRate() * 100f, tally.pushTicks));
		}
''', 1),
    ('''drillTally.combosTaken, drillTally.comboEscapes, drillTally.comboBreaks, drillTally.backRate() * 100f,''',
     '''drillTally.combosTaken, drillTally.comboEscapes, drillTally.comboBreaks, drillTally.pushRate() * 100f,''', 1),
])

edit('src/client/java/dev/swordai/client/Report.java', [
    ('''		String backHelp = backLine(curriculum);
''',
     '''		String backHelp = backLine(cfg, curriculum);
''', 1),
    ('''	static String backLine(Curriculum curriculum) {
		StageSpec stage = curriculum.current();
		if (stage.backNudgeRate <= 0f && stage.backNudgeKeep <= 0f) {
			return null;
		}
		float target = curriculum.backNudgeTarget();
''',
     '''	static String backLine(Config cfg, Curriculum curriculum) {
		StageSpec stage = curriculum.current();
		if (stage.backNudgeRate <= 0f && stage.backNudgeKeep <= 0f) {
			return null;
		}
		if (backRule(cfg, curriculum)) {
			return "S rule: on; in the twelve ticks after each hit of a combo it is taking, from the second hit in a row, S is held for it, "
					+ "in training and in every fight (backRule in config.json)";
		}
		float target = curriculum.backNudgeTarget();
''', 1),
    ('''	/** What the current stage is doing about S while it is comboed right now, or null when it does nothing. */
''',
     '''	/** Whether backing out of a combo is a rule in the stage it is in; see {@code Config.backRule}. */
	static boolean backRule(Config cfg, Curriculum curriculum) {
		return cfg.backRule && curriculum.current().backNudgeKeep > 0f;
	}

	/** What the current stage is doing about S while it is comboed right now, or null when it does nothing. */
''', 1),
])

edit('src/client/java/dev/swordai/client/LoadedFighter.java', [
    ('''		learner.setBackNudge(curriculum.backNudge());
		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, System.nanoTime());
		fighter.backOutRule(cfg.backRule && curriculum.current().backNudgeKeep > 0f);
''',
     '''		learner.setBackNudge(Report.backRule(cfg, curriculum) ? 0f : curriculum.backNudge());
		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, System.nanoTime());
		fighter.backOutRule(Report.backRule(cfg, curriculum));
''', 1),
    ('''		learner.setBackNudge(curriculum.backNudge());
		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, 1f, System.nanoTime());
		fighter.backOutRule(cfg.backRule && curriculum.current().backNudgeKeep > 0f);
''',
     '''		learner.setBackNudge(Report.backRule(cfg, curriculum) ? 0f : curriculum.backNudge());
		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, 1f, System.nanoTime());
		fighter.backOutRule(Report.backRule(cfg, curriculum));
''', 1),
])
print('ok')
