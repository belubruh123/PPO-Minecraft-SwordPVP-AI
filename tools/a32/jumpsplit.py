exec(open('after_hit.py').read().split("nd=0")[0])
import collections
C=collections.defaultdict(collections.Counter)
for rr in rows_all:
    byt={int(r['tick']):r for r in rr}
    taken=[int(r['tick']) for r in rr if 'TAKEN' in r['event']]
    hits=[int(r['tick']) for r in rr if 'DEALT' in r['event']]
    for t in hits:
        nt=[x for x in taken if x>t]; a=nt[0]-t if nt else 99
        nh=[x for x in hits if x>t]; b=nh[0]-t if nh else 99
        # skip trades (opp hit same exchange <=3t)
        j=any((byt.get(t+d) or {'keys':'-----'})['keys'][3]=='j' for d in range(1,6))
        s=any((byt.get(t+d) or {'keys':'-----'})['keys'][0]=='S' for d in range(1,6))
        wl=sum((byt.get(t+d) or {'keys':'-----'})['keys'][0]=='W' for d in range(1,6))
        k='jump' if j else ('S' if s else ('W<=2' if wl<=2 else 'Wheld'))
        c=C[k];c['n']+=1
        if a<=3:c['trade<=3']+=1
        elif b<a and b<=20:c['my2nd']+=1
        elif a<=20:c['opp4-20']+=1
        else:c['none']+=1
for k,c in C.items():print(k,dict(c))
