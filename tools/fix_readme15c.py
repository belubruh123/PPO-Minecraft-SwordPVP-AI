p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()
a = '''one that stands in a corner, one that takes no knockback, and the odd ones (see 14. under *What 0.14.0 found*).'''
assert s.count(a) == 1
s = s.replace(a, '''one that stands in a corner, one that takes no knockback, the odd ones (see 14. under *What 0.14.0 found*), and the three of 15.: one that combos and comes after it with a running jump and a critical hit, one that answers each of its hits with a critical hit, and one that backs out of combos with S.''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
