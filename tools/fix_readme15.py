p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:60], s.count(a))
    s = s.replace(a, b)


rep('''sparingly ("it is really not fair"). As it is, a quarter to a third of the hits it takes are
      jumped with. S in a combo''', '''sparingly ("it is really not fair"). As it is, one hit in eight that it takes is jumped with,
      and two in seven against the opponent this is for. S in a combo''')
rep('''fight stayed where the rules had put them in the first minutes, and of four networks put side
    by side at the end, each for 45 minutes of fighting with its learning off, the one that had not''',
    '''fight stayed where the rules had put them in the first minutes, and of three networks put side
    by side at the end (the last, the one of half past two, and the one it began from), each for 45
    minutes of fighting with its learning off, the one that had not''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('fixed')
