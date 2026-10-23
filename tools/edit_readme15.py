p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()
draft = open('C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/readme15_draft.md', encoding='utf-8').read().rstrip('\n') + '\n'
a = '''    **Not tried against a person.** All of it is from training fights, on the screen, against
    scripted opponents and copies of itself.
'''
assert s.count(a) == 1
assert '15. *Backing out of a combo was how to beat it.*' not in s
s = s.replace(a, a + '\n' + draft)
a = '''come in again for the first hit. Jumping as the hit lands, which the same page lists, is not
    used.'''
assert s.count(a) == 1, s.count(a)
s = s.replace(a, '''come in again for the first hit. Jumping as the hit lands, which the same page lists, was not
    used until the first evening against people showed what backing out is worth; see 15.''')
a = '''    - **Backing out is a rule from stage 8 on**, in training and in every fight: in the twelve ticks
      after each hit of a combo it is taking, from the second hit in a row, S is held for it. Then
      W and S are its own again, and it comes back in for the first hit. `backRule` in
      `config.json`; false leaves it to the push that stage 7 teaches it with.
'''
assert s.count(a) == 1, s.count(a)
s = s.replace(a, a + '''      (Since 15. this is what it does only where the hit finds it in the air. On the ground it
      jumps with the hit instead: against a player who follows with a running jump, backing out
      was the way into the next hit.)
''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('readme ok', len(s))
