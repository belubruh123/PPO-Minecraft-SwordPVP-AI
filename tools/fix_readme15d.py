p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()
a = '''opponent in its reach and the swing it owes (12.) there to be made. S is held only where the
      hit finds it in the air (`backRule`). **Never on the first hit**'''
assert s.count(a) == 1, s.count(a)
s = s.replace(a, '''opponent in its reach and the swing it owes (12.) there to be made. S is held only where the
      hit finds it in the air (`backRule`; false, and S is not held for it at all: against the jump
      critter that made no difference, 56 hits landed to 67 taken for 57 to 71). **Never on the
      first hit**''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
