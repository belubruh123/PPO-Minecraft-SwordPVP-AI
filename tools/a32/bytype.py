exec(open('after_hit.py').read().split("nd=0")[0])
import collections
C=collections.defaultdict(collections.Counter)
for rr in rows_all:
    byt={int(r['tick']):r for r in rr}
    taken=[int(r['tick']) for r in rr if 'TAKEN' in r['event']]
    hits=[int(r['tick']) for r in rr if 'DEALT' in r['event']]
    sw={}
    for r in rr:
        if 'swing:' in r['event']: sw[int(r['tick'])]=r['event'].split('swing:')[1].split()[0]
    for t in hits:
        prev=[s for s in sw if t-4<=s<t]
        ty=sw[max(prev)] if prev else '?'
        nt=[x for x in taken if x>=t]; a=nt[0]-t if nt else 99
        nh=[x for x in hits if x>t]; b=nh[0]-t if nh else 99
        c=C[ty]; c['n']+=1
        c['opp<=3t']+= a<=3; c['opp<=6t']+=a<=6
        c['myNext<=20 before opp']+= (b<=20 and b<a)
        r5=byt.get(t+5)
        if r5: c['dist@+5']+=float(r5['dist']); c['n5']+=1
        c['rules_k_before']+= any('k' in (byt.get(s) or {'rules':''})['rules'] for s in range(t-8,t))
        c['jump_key_before(8t)']+= any((byt.get(s) or {'keys':'-----'})['keys'][3]=='j' for s in range(t-8,t))
for k,c in C.items():
    n=c['n'];print(k,n,{x:(round(v/n,2) if x!='dist@+5' else round(v/max(1,c['n5']),2)) for x,v in c.items() if x not in('n','n5')})
