import sys,json,re,collections
from load import *
H,checks=segments(HITS)
pj=json.load(open('pairs.json'))
steps=[]
for line in open(CHECKLOG):
    m=re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps',line)
    if m: steps.append((m.group(1),m.group(3)))
steps=[s for s in steps if pj.get(s[1])]
K=int(sys.argv[1]); C=collections.defaultdict(lambda:[0,0.0,0])
for i in range(1,K+1):
    F=fights(H,checks[-i])
    for (p,s),rows in F.items():
        last=-99; lastwho=None
        for r in rows:
            t=int(r[H['tick']]); d=float(r[H['dealt']]); tk=float(r[H['taken']])
            if tk>0 and (int(r[H['critsTaken']])>0 or tk>=10) and t-last>20:
                a=C[(r[H['bot']],s)]; a[0]+=1; a[1]+=tk; a[2]+= 1-int(r[H['onGround']])
            if d>0 or tk>0: last=t
bots=sorted({k[0] for k in C})
for b in bots: print('%-18s A n %2d dmg %4.0f air %2d | B n %2d dmg %4.0f air %2d'%(b,*C[(b,'A')],*C[(b,'B')]))
