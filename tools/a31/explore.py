from load import *
H,checks=segments(HITS)
print(len(checks),[len(c) for c in checks[-8:]])
F=fights(H,checks[-1])
k=list(F)[0]
for r in F[k][:25]: print(dict(zip(H,r)) if False else ','.join(r))
import collections
c=collections.Counter()
for r in checks[-1]:
    if float(r[H['taken']])>0 and int(r[H['critsTaken']])>0: c[(r[H['onGround']],r[H['falling']],r[H['situation']])]+=1
print(c.most_common(20))
