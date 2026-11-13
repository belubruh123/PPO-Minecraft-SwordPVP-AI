import gzip,glob,csv,io,sys,collections
D="C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
files=sorted(glob.glob(D+"20261113-224*.txt.gz")+glob.glob(D+"20261113-2248*.txt.gz"))
files=sorted(set(files))
rows_all=[]
for f in files:
    t=gzip.open(f,'rt',encoding='utf8',errors='replace').read().split('\n')
    i=[k for k,l in enumerate(t) if l.startswith('tick,dist')]
    if not i: continue
    rows=list(csv.reader(t[i[0]:]))
    h=rows[0]; rr=[dict(zip(h,r)) for r in rows[1:] if len(r)>=len(h)-1]
    rows_all.append(rr)
nd=0;res=collections.Counter();nxt=[];sn=0
keyc=collections.Counter();dist=[];air=0;tot=0;gap=[]
runs=collections.Counter()
for rr in rows_all:
    ev=[(int(r['tick']),r['event']) for r in rr]
    byt={int(r['tick']):r for r in rr}
    hits=[t for t,e in ev if 'DEALT' in e]; taken=[t for t,e in ev if 'TAKEN' in e]
    # my hit runs: consecutive my hits with no TAKEN between
    allh=sorted([(t,'D') for t in hits]+[(t,'T') for t in taken])
    run=0
    for t,k in allh:
        if k=='D': run+=1
        else:
            if run: runs[run]+=1
            run=0
    if run: runs[run]+=1
    for t in hits:
        nd+=1
        nt=[x for x in taken if x>=t]; nh=[x for x in hits if x>t]
        a=nt[0]-t if nt else 99; b=nh[0]-t if nh else 99
        # outcome in 20 ticks
        if a<=20 and a<=b: res['opp hit next(<=20t)']+=1
        elif b<=20 : res['my 2nd hit in 20t first']+=1
        else: res['nothing in 20t']+=1
        if b<=20: gap.append(b)
        # keys in following ticks 1..10
        for d in range(1,11):
            r=byt.get(t+d)
            if not r: continue
            k=r['keys'];tot+=1
            if k[0]=='W':keyc['W']+=1
            if k[0]=='S':keyc['S']+=1
            if k[2]=='r':keyc['sprintkey']+=1
            if k[3]=='j':keyc['jump']+=1
            if k[4]=='x':keyc['attack']+=1
            if r['me']=='a':air+=1
            dist.append(float(r['dist']))
            if d<=3 and 'TAKEN' in r['event']: keyc['taken<=3']+=1
        # was I in air at the hit, jump pressed in 1-5 ticks after
        j=any((byt.get(t+d) or {'keys':'-----'})['keys'][3]=='j' for d in range(1,6))
        res['jump in 1-5t after hit']+=j
        sw=any((byt.get(t+d) or {'oppSword':'0'})['oppSword'] not in('0.00','0') and float(byt[t+d]['oppSword'])>=0.9 for d in range(1,11) if byt.get(t+d))
        res['opp sword ready>=0.9 within 10t']+=sw
        r1=byt.get(t+1); 
        if r1:
            res['dist@t+5 >3.0']+= (float(byt[t+5]['dist'])>3.0) if byt.get(t+5) else 0
            res['dist@t+5 <1.0']+= (float(byt[t+5]['dist'])<1.0) if byt.get(t+5) else 0
print("rounds",len(rows_all),"my hits",nd)
print(dict(res))
print("key ticks (10 after hit)",tot,{k:round(v/tot,2) for k,v in keyc.items()}, "air",round(air/tot,2))
import statistics
print("dist median 1-10t after",statistics.median(dist))
print("runs of my hits (no taken between)",sorted(runs.items()))
print("gap to my next hit",collections.Counter(gap).most_common(8))
