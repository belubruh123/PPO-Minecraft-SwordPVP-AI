exec(open('after_hit.py').read().split("nd=0")[0])
import collections
C=collections.Counter()
for rr in rows_all:
    byt={int(r['tick']):r for r in rr}
    taken=[int(r['tick']) for r in rr if 'TAKEN' in r['event']]
    for r in rr:
        if 'swing:' in r['event']:
            t=int(r['tick']); ty=r['event'].split('swing:')[1].split()[0]
            if ty not in('sprint','crit'): continue
            os=float(r['oppSword']); ready=os>=0.8
            nt=[x for x in taken if x>=t]; a=nt[0]-t if nt else 99
            C[(ty,'opp ready' if ready else 'opp empty')]+=1
            C[(ty,'opp ready' if ready else 'opp empty','answered<=6')]+= a<=6
            C[(ty,'dist<=1.5')]+= float(r['dist'])<=1.5
            C[(ty,'n')]+=1
for k in sorted(C,key=str):print(k,C[k])
