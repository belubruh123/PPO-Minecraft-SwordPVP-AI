ROOT = 'D:/swordai/'
T = ROOT + 'src/client/java/dev/swordai/client/Trainer.java'
s = open(T, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:70], s.count(a))
    s = s.replace(a, b)


rep('''		learner.setBackNudge(ownStage && !backRuleNow ? curriculum.backNudge() : 0f);''',
    '''		// Nor where it jumps with the hit instead: S is then for the hit that finds it in the air, or for nothing.
		learner.setBackNudge(ownStage && !backRuleNow && cfg.jumpResetChance <= 0f ? curriculum.backNudge() : 0f);''')
rep('''		if (!episodeLockedDrill && backRuleNow && cfg.jumpResetChance > 0f && stage.unlocked(ActionSpace.JUMP)) {''',
    '''		if (!episodeLockedDrill && stage.backNudgeKeep > 0f && cfg.jumpResetChance > 0f && stage.unlocked(ActionSpace.JUMP)) {''')
open(T, 'w', encoding='utf-8', newline='').write(s)
print('nos ok')
