ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''import dev.swordai.core.rl.JumpNudge;
''', '''import dev.swordai.core.rl.JumpNudge;
import dev.swordai.core.rl.JumpReset;
''', 1),
    ('''import dev.swordai.core.rl.SwingGate;
''', '''import dev.swordai.core.rl.StepBack;
import dev.swordai.core.rl.SwingGate;
''', 1),
    ('''		return new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, cfg.chaseClosingMost, rng.nextLong());
''', '''		JumpIn made = new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, cfg.chaseClosingMost, rng.nextLong());
		made.chaseInCombo(cfg.chaseInCombo);
		return made;
''', 1),
    ('''		// Where backing out of a combo is a rule there is nothing left to push for; see Config.backRule.
		backRuleNow = ownStage && Report.backRule(cfg, curriculum);
''', '''		// Where backing out of a combo is a rule there is nothing left to push for; see Config.backRule.
		// A rule in every fight of a stage that has it, also one replayed from a later stage: that is
		// how a fight against a person is fought. (Up to the first evening against people it was a rule
		// in the current stage's own fights only, so in self-play every scripted opponent was met
		// without it, and with it for the first time by whoever it then played against.)
		backRuleNow = cfg.backRule && stage != null && stage.backNudgeKeep > 0f;
''', 1),
    ('''						+ "rule jumps: chase %d (crit %d) face %d (crit %d) upper %d (hit %d) hit in the air %d, uppercuts %d, far: ticks %d still %d flips %d turned %d, found again %d times in %d ticks, in view %d, falls: crit %d plain %d noaim %d nosword %d other %d far %d",''',
     '''						+ "rule jumps: chase %d (crit %d) face %d (crit %d) upper %d (hit %d) hit in the air %d, uppercuts %d, far: ticks %d still %d flips %d turned %d, found again %d times in %d ticks, in view %d, falls: crit %d plain %d noaim %d nosword %d other %d far %d, "
						+ "resets %d, step back ticks %d, hit after its own hit %d, crits taken %d",''', 1),
    ('''				t.fallCrits, t.fallPlain, t.fallNoAim, t.fallNoSword, t.fallOther, t.fallFar);
	}''', '''				t.fallCrits, t.fallPlain, t.fallNoAim, t.fallNoSword, t.fallOther, t.fallFar,
				t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken);
	}''', 1),
])

edit('src/client/java/dev/swordai/client/Report.java', [
    ('''		add(on, cfg.chaseRule, "a running jump and a critical hit on the way down at an opponent that is backing off or running (chaseRule)");
''', '''		add(on, cfg.chaseRule, "a running jump and a critical hit on the way down at an opponent that is backing off or running (chaseRule)");
		add(on, cfg.chaseRule && cfg.chaseInCombo, "also as the next hit of a combo of its own (chaseInCombo)");
		add(on, cfg.jumpResetChance > 0f && backRule(cfg, curriculum), String.format(Locale.ROOT,
				"hit a second time in a row, it jumps with %.0f%% of those hits, W held, and is thrown half as far (jumpResetChance)", cfg.jumpResetChance * 100f));
		add(on, cfg.stepBackRule, "after a hit of its own that has not sent the opponent away it steps back until its sword is back (stepBackRule)");
''', 1),
    ('''			return "S rule: on; in the twelve ticks after each hit of a combo it is taking, from the second hit in a row, S is held for it, "
					+ "in training and in every fight (backRule in config.json)";''', '''			return "S rule: on; in the twelve ticks after each hit of a combo it is taking, from the second hit in a row, S is held for it"
					+ (cfg.jumpResetChance >= 1f ? " where that hit finds it in the air (on the ground it jumps with it instead)"
					: cfg.jumpResetChance > 0f ? " where it does not jump with that hit instead" : "")
					+ ", in training and in every fight (backRule in config.json)";''', 1),
])

# ---- the scratchpad's log reader ----------------------------------------------------------------
p = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/agg2.py'
s = open(p, encoding='utf-8').read()
a = r'''(?:, falls: crit (?P<fc>\d+) plain (?P<fp>\d+) noaim (?P<fna>\d+) nosword (?P<fns>\d+) other (?P<fo>\d+) far (?P<ffar>\d+))?)?)?')'''
assert s.count(a) == 1
s = s.replace(a, r'''(?:, falls: crit (?P<fc>\d+) plain (?P<fp>\d+) noaim (?P<fna>\d+) nosword (?P<fns>\d+) other (?P<fo>\d+) far (?P<ffar>\d+)'''
              r'''(?:, resets (?P<rs>\d+), step back ticks (?P<sbt>\d+), hit after its own hit (?P<ch>\d+), crits taken (?P<ct>\d+))?)?)?)?')''')
a = "('circlers', 'CIRCLER'), ('corner', 'CORNER'), ('no knockback', 'NOKB'), ('odd', 'ODD')]"
assert s.count(a) == 1
s = s.replace(a, "('circlers', 'CIRCLER'), ('corner', 'CORNER'), ('no knockback', 'NOKB'), ('odd', 'ODD'),\n         ('jump critters', 'JUMPER'), ('punishers', 'PUNISHER')]")
s = s.replace("for s in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'face', 'older')))",
              "for s in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'JUMPER', 'PUNISHER', 'face', 'older')))")
s = s.replace("for s in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'older'):",
              "for s in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'JUMPER', 'PUNISHER', 'older'):")
open(p, 'w', encoding='utf-8', newline='').write(s)
print('more ok')
