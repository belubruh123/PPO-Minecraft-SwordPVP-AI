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
    ('''own combos %d of %d hits, hops %d",''', '''own combos %d of %d hits, hops %d, damage %.0f dealt / %.0f taken",''', 1),
    ('''t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken, t.combos, t.comboHits, t.hopRuleJumps);''',
     '''t.resetJumps, t.stepBackTicks, t.counterHits, t.critsTaken, t.combos, t.comboHits, t.hopRuleJumps, t.damageDealt, t.damageTaken);''', 1),
])
print('damage ok')
