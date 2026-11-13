from load import *
import collections
H,checks=segments(HITS)
c=collections.Counter()
for r in checks[-1]:
    A=r[H['side']]
    d=float(r[H['dealt']])>0; t=float(r[H['taken']])>0
    c[(A,'dealt' if d else 'taken' if t else 'none',r[H['punishCrit']],r[H['situation']] if d else '')]+=1
for k,v in sorted(c.items()): print(k,v)
