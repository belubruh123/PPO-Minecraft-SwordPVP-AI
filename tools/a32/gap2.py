exec(open('after_hit.py').read().split("nd=0")[0])
import collections
A=collections.Counter();Hd=[];kind=collections.Counter()
for rr in rows_all:
    byt={int(r['tick']):r for r in rr}
    hits=[t for t,e in [(int(r['tick']),r['event']) for r in rr] if 'DEALT' in e]
    taken=[int(r['tick']) for r in rr if 'TAKEN' in r['event']]
    sw={}
    for r in rr:
        if r['event'].startswith('swing:') or ' swing:' in r['event']:
            sw[int(r['tick'])]=r['event'].split('swing:')[1].split()[0]
    for t in hits:
        nt=[x for x in taken if x>=t]
        a=nt[0]-t if nt else 99
        A[min(a,25)]+=1
        prev=[s for s in sw if t-4<=s<t]
        kind[sw[max(prev)] if prev else '?']+=1
        # dist at hit tick
        Hd.append(float(byt[t]['dist']))
        # is opponent hit simultaneous (same exchange)?
print("ticks from my hit to the opp's next hit",sorted(A.items()))
print("my hit type",kind)
import statistics;print("dist at my hit median",statistics.median(Hd), "q",statistics.quantiles(Hd,n=4))
