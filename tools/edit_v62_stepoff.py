ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


C = 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(ROOT + C, encoding='utf-8').read()
i = s.index('	public boolean stepBackRule = true;')
j = s.rindex('	/**', 0, i)
doc = s[j:i]
assert 'StepBack' in doc, doc[:200]
s = s[:i] + '''	public boolean stepBackRule = false;''' + s[i + len('	public boolean stepBackRule = true;'):]
s = s.replace(doc, doc.rstrip().rstrip('*/').rstrip() + '''
	 *
	 * <p>Off since the evening of 14 October. Against the scripted opponents it took fewer hits for
	 * as many landed; against people it was the S habit again in another place: after its own hit it
	 * walked backwards, an opponent who came straight on had the first hit, and what the user saw was
	 * a fighter that hardly combos and loses to weak players. In ten fights against four players it
	 * took 50 hits a minute where it had been taking 39, and landed as many as before.
	 */
''', 1)
open(ROOT + C, 'w', encoding='utf-8', newline='').write(s)

edit('core/src/test/java/dev/swordai/core/rl/JumpInTest.java', [
    ('''		assertTrue(cfg.jumpResetChance > 0f && cfg.stepBackRule && cfg.chaseInCombo);''',
     '''		assertTrue(cfg.jumpResetChance > 0f && !cfg.stepBackRule && cfg.chaseInCombo);''', 1),
])
edit('README.md', [
    ('''    - **It steps back from an opponent that answers its hits** (`stepBackRule`; `StepBack`). Hit''',
     '''    - **It steps back from an opponent that answers its hits** (`stepBackRule`; `StepBack`; **off
      since 14 October**, see below). Hit''', 1),
    ('''    **Not built:** turning round and running out of a combo with jumps, which the user named as
    the better way to run if it runs at all. It runs at the opponent now instead. **Not tried
    against a person.**''', '''    **Not built:** turning round and running out of a combo with jumps, which the user named as
    the better way to run if it runs at all. It runs at the opponent now instead.

    **Then it met people again (14 October), and the step back went.** Fifteen fights against six
    players: 357 hits landed and 333 taken, where the evening before had been 1016 and 846; it
    landed as many hits a minute as before and took 50 where it had taken 39. The user's word for
    it: "it can hardly combo and loses against weak opponents as well". What a person sees of the
    step back is a fighter that hits and walks away, and what a person does about it is what was
    done about S: come straight on, and have the first hit. Against the scripted opponents it had
    looked like a gain, and put against ordinary ones that jump with a third of the hits they take,
    with the network not learning, the three ways came out alike (64 hits landed and 32 taken a
    minute with everything on, 70 and 42 without the step back, 68 and 36 with yesterday's rules).
    The scripts do not press in on a fighter that backs off the way people do, and that is the part
    of it that counts. `stepBackRule` is now false; the jump with a hit, the run at a jumper and the
    chase jump in a combo stay.''', 1),
])
print('stepoff ok')
