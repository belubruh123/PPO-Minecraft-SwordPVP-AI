p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:60], s.count(a))
    s = s.replace(a, b)


rep('''    **Six hours of training on the screen added nothing to this.** From the installed network,
    with the rules and the three new opponents, 0.7 million steps: the numbers''',
    '''    **Five hours of training on the screen added nothing to this.** From the installed network,
    with the rules and the three new opponents, a million steps: the numbers''')
open(p, 'w', encoding='utf-8', newline='').write(s)
i = s.index('8. **Crits** - ')
print(s[i:i + 1500])
