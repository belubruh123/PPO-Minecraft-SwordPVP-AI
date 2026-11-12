import sys,glob,gzip,collections,re
sys.path.insert(0,'D:/swordai/tools')
import bench
B='D:/swordai/run/swordai/bench/'
def load(tag):
    d={}
    for p in sorted(glob.glob(B+tag+'/*-h2h.txt.gz')):
        d[p.split('/')[-1][:4] if '/' in p else p[-16:-12]]=bench.Episode(p)
    return d
import os
def key(p): return os.path.basename(p)[:4]
def load(tag): return {key(p):bench.Episode(p) for p in sorted(glob.glob(B+tag+'/*-h2h.txt.gz'))}
import sys
C=load(sys.argv[1] if len(sys.argv)>1 else 'run83n0618');R=load(sys.argv[2] if len(sys.argv)>2 else 'run35w83')
def stats(ep):
    s=collections.Counter()
    n=ep.n
    s['ticks']=n
    s['dealt']=sum(ep.dealt);s['taken']=sum(ep.taken)
    myl=ep.lost(ep.myHp,1,n);opl=ep.lost(ep.oppHp,1,n)
    s['hpT']=myl;s['hpD']=opl
    s['won']=1 if ep.oppHp[-1]<=0 and ep.myHp[-1]>0 else 0
    ev=ep.ev
    for t in range(n):
        if ep.taken[t]:
            e=ev[t]
            s['T_crit']+= ep.takenCrit[t]
            s['T_jumpin']+= 'jumped-in' in e
            s['T_jumpin_crit']+= ('jumped-in' in e and ep.takenCrit[t])
            s['T_empty']+= 'my-sword-empty' in e
            s['T_ready']+= 'my-sword-ready' in e
            s['T_meair']+= ep.meAir[t]
            s['T_oppair']+= ep.oppAir[t]
            s['T_dist']+= ep.dist[t]
            s['T_firstminus']+= 'first-' in e
        if ep.dealt[t]:
            e=ev[t]
            s['D_meair']+= ep.meAir[t]
            s['D_oppair']+= ep.oppAir[t]
            s['D_dist']+= ep.dist[t]
            s['D_firstplus']+= 'first+' in e
    for h in ep.hits:
        s['D_'+str(h.kind)]+=1
    # trades: dealt & taken within 4 ticks
    dt=[t for t in range(n) if ep.dealt[t]];tk=[t for t in range(n) if ep.taken[t]]
    s['trades']=sum(1 for a in dt if any(abs(a-b)<=4 for b in tk))
    s['tradesTk']=sum(1 for b in tk if any(abs(a-b)<=4 for a in dt))
    # combos taken: 3+ taken with gaps <=15 ticks
    def combos(ts,gap=15):
        c=0;run=1
        for i in range(1,len(ts)):
            if ts[i]-ts[i-1]<=gap: run+=1
            else:
                if run>=3:c+=1
                run=1
        if len(ts)and run>=3:c+=1
        return c
    s['comboT']=combos(tk);s['comboD']=combos(dt)
    # hits in combos (3rd+)
    return s
def agg(D):
    t=collections.Counter()
    for k,ep in D.items():
        for a,b in stats(ep).items(): t[a]+=b
    return t
tc,tr=agg(C),agg(R)
keys=sorted(set(tc)|set(tr))
N=len(C)
print(N,len(R))
for k in keys: print('%-14s C %8.2f  R %8.2f  d %+7.2f   per fight'%(k,tc[k]/N,tr[k]/N,(tc[k]-tr[k])/N))
print('---- behaviour')
def beh(D):
    c=collections.Counter()
    rc=collections.Counter()
    for ep in D.values():
        n=ep.n
        c['ticks']+=n
        c['air']+=sum(ep.meAir)
        c['oppair']+=sum(ep.oppAir)
        for t in range(n):
            k=ep.keys[t]
            c['jkey']+= 'j' in k
            c['sprint']+= 'r' in k
            c['W']+= 'W' in k; c['S']+= 'S' in k
            c['x']+= 'x' in k
            c['near<3']+= ep.dist[t]<3
            c['dist']+=ep.dist[t]
        # takeoffs
        for t in range(1,n):
            if ep.meAir[t] and not ep.meAir[t-1]: c['takeoffs']+=1
        # rules column
        rows=ep.raw_rows
        for t in range(n):
            rl=rows[t][ep.header.index('rules')] if 'rules' in ep.header else ''
            for ch in set(rl): rc[ch]+=1
    return c,rc
bc,brc=beh(C);br,brr=beh(R)
for k in sorted(bc): print('%-10s C %.3f R %.3f (per tick)'%(k,bc[k]/bc['ticks'],br[k]/br['ticks']))
print('takeoffs/fight',bc['takeoffs']/60,br['takeoffs']/60)
print('rules per fight C',{k:round(v/60,1) for k,v in brc.items()});print('R',{k:round(v/60,1) for k,v in brr.items()})
print('---- takeoffs')
ri=C[next(iter(C))].header.index('rules')
def tk(D):
    c=collections.Counter();runs=[]
    for ep in D.values():
        n=ep.n;rows=ep.raw_rows
        st=None
        for t in range(1,n):
            if ep.meAir[t] and not ep.meAir[t-1]:
                # key j at t or t-1
                src='net'
                for u in (t-1,t,t+1):
                    if u<n and 'j' in ep.keys[u]:
                        rl=rows[u][ri]
                        src=rl if rl else 'net'
                        break
                else: src='nojkey'
                c[src]+=1
                # situation: opp dist at takeoff
                c['d<4' if ep.dist[t]<4 else 'd>=4']+=1
                c['oppSwordReady' if ep.oppSw[t]>=0.9 else 'oppSwordNot']+=1
                st=t
            if st is not None and not ep.meAir[t] and ep.meAir[t-1]:
                runs.append(t-st);st=None
    return c,runs
a,ra=tk(C);b,rb=tk(R)
print('C',dict(a),sum(ra)/len(ra));print('R',dict(b),sum(rb)/len(rb))
print('---- HP by takeoff source (window 16 ticks, taken/dealt hit counts)')
def tk2(D):
    out=collections.defaultdict(lambda:[0,0,0,0.0,0.0])
    for ep in D.values():
        n=ep.n;rows=ep.raw_rows
        for t in range(1,n):
            if ep.meAir[t] and not ep.meAir[t-1]:
                src=None
                for u in (t-1,t,t+1):
                    if u<n and 'j' in ep.keys[u]:
                        rl=rows[u][ri]; src=('net' if not rl else ('rc' if 'r' in rl and 'c' in rl and len(rl)<=3 and 'r' in rl else ('J' if 'J' in rl else 'other-'+rl)));break
                if src is None: src='knocked'
                if src.startswith('other'): src='other'
                o=out[src]
                o[0]+=1
                o[1]+=ep.n_taken(t,t+16);o[2]+=ep.n_dealt(t,t+16)
                o[3]+=ep.lost(ep.myHp,t,t+16);o[4]+=ep.lost(ep.oppHp,t,t+16)
    return out
for nm,D in (('C',C),('R',R)):
    for k,v in sorted(tk2(D).items()): print(nm,k,'n',v[0],'taken/jump %.2f dealt/jump %.2f netHP/jump %+.2f'%(v[1]/v[0],v[2]/v[0],(v[4]-v[3])/v[0]))
print('---- exchanges')
GAP=20
def exch(ep):
    ev=[(t,'D') for t in range(ep.n) if ep.dealt[t]]+[(t,'T') for t in range(ep.n) if ep.taken[t]]
    ev.sort()
    ex=[];cur=[]
    for t,k in ev:
        if cur and t-cur[-1][0]>GAP: ex.append(cur);cur=[]
        cur.append((t,k))
    if cur: ex.append(cur)
    return ex
def classify(ep,e):
    t0,k0=e[0]
    # trade: other kind within 4 ticks of first
    other=[t for t,k in e if k!=k0 and t-t0<=4]
    first='trade' if other else ('me' if k0=='D' else 'opp')
    # first-hit extras
    if first=='opp' and k0=='T':
        air=ep.meAir[t0]; 
        ji='jumped-in' in ep.ev[t0]
        cr=ep.takenCrit[t0]
        sub=('Cair' if air else 'Cgnd')+('-oppcrit' if cr else '-oppplain')
    elif first=='me':
        sub=('Cair' if ep.meAir[t0] else 'Cgnd')
    else: sub='x'
    nD=sum(1 for t,k in e if k=='D');nT=sum(1 for t,k in e if k=='T')
    # hp over exchange: from t0-1 to last+10
    a=t0;b=e[-1][0]+10
    mine=ep.lost(ep.myHp,a,b);th=ep.lost(ep.oppHp,a,b)
    return first,sub,nD,nT,th-mine
def table(D):
    T=collections.defaultdict(lambda:[0,0.0,0,0])
    for ep in D.values():
        for e in exch(ep):
            f,sub,nD,nT,net=classify(ep,e)
            for key in (f,f+':'+sub):
                x=T[key];x[0]+=1;x[1]+=net;x[2]+=nD;x[3]+=nT
    return T
tC,tR=table(C),table(R)
for k in sorted(set(tC)|set(tR)):
    a=tC.get(k,[0,0,0,0]);b=tR.get(k,[0,0,0,0])
    print('%-22s C n/f %.2f net/f %+.2f (D %.2f T %.2f) | R n/f %.2f net/f %+.2f (D %.2f T %.2f) | dnet %+.2f'%(k,a[0]/60,a[1]/60,a[2]/60,a[3]/60,b[0]/60,b[1]/60,b[2]/60,b[3]/60,(a[1]-b[1])/60))
print('---- long Cair exchanges')
for k,ep in sorted(C.items()):
    for e in exch(ep):
        f,sub,nD,nT,net=classify(ep,e)
        if f=='opp' and sub.startswith('Cair') and nT>=3:
            t0=e[0][0]
            print(k,'t0',t0,'nD',nD,'nT',nT,'net %+.1f'%net,'dur',e[-1][0]-t0,'myHp@start',ep.myHp[t0],'seq',''.join(x for _,x in e),'ticks end',ep.n)
print('---- streaks')
def streaks(D):
    out=[]
    for k,ep in D.items():
        run=0;best=0
        for t in range(ep.n):
            if ep.dealt[t]: run=0
            if ep.taken[t]:
                run+=1;best=max(best,run)
        out.append((k,best))
    return out
for nm,D in (('C',C),('R',R)):
    s=streaks(D); print(nm,'fights with >=6 taken in a row w/o dealing',sum(1 for k,b in s if b>=6),' >=8',sum(1 for k,b in s if b>=8),' max list',sorted([b for k,b in s],reverse=True)[:12])
print('---- combos taken (gap<=15, no DEALT between)')
def combs(D):
    L=collections.Counter();hp=collections.Counter();nfight=collections.Counter()
    for ep in D.values():
        tk=[t for t in range(ep.n) if ep.taken[t]]
        run=[]
        allr=[]
        for t in tk:
            if run and (t-run[-1]>15 or ep.n_dealt(run[-1]+1,t-1)>0): allr.append(run);run=[]
            run.append(t)
        if run: allr.append(run)
        for r in allr:
            l=min(len(r),12)
            L[l]+=1;hp[l]+=ep.lost(ep.myHp,r[0],r[-1]+1)
    return L,hp
lc,hc=combs(C);lr,hr=combs(R)
for l in range(1,13): print(l,'C n',lc[l],'hp',hc[l],'| R n',lr[l],'hp',hr[l])
print('C hits in combos>=3 per fight',sum(l*n for l,n in lc.items() if l>=3)/60,'R',sum(l*n for l,n in lr.items() if l>=3)/60)
print('C hits in combos>=6 per fight',sum(l*n for l,n in lc.items() if l>=6)/60,'R',sum(l*n for l,n in lr.items() if l>=6)/60)
print('hp lost in combos>=3 per fight C',sum(h for l,h in hc.items() if l>=3)/60,'R',sum(h for l,h in hr.items() if l>=3)/60)
print('hp lost in combos>=6 per fight C',sum(h for l,h in hc.items() if l>=6)/60,'R',sum(h for l,h in hr.items() if l>=6)/60)
print('---- inside combos >=3 (2nd+ hits taken)')
def inside(D):
    c=collections.Counter()
    for ep in D.values():
        tk=[t for t in range(ep.n) if ep.taken[t]]
        run=[];allr=[]
        for t in tk:
            if run and (t-run[-1]>15 or ep.n_dealt(run[-1]+1,t-1)>0): allr.append(run);run=[]
            run.append(t)
        if run: allr.append(run)
        for r in allr:
            if len(r)<3: continue
            for i,t in enumerate(r):
                if i==0: continue
                c['hit']+=1
                c['air']+=ep.meAir[t]
                c['sw_ready']+= ep.mySw[t]>=0.9
                c['dist']+=ep.dist[t]
                c['oppsprint']+=ep.oppSprint[t]
                c['oppair']+=ep.oppAir[t]
                c['crit']+=ep.takenCrit[t]
                c['swung_before12']+= ep.n_swings(t-12,t-1)>0
                c['jkey_pm1']+= any('j' in ep.keys[u] for u in range(t-1,min(ep.n,t+2)))
                c['S3']+= any('S' in ep.keys[u] for u in range(max(0,t-3),t))
                c['W3']+= any('W' in ep.keys[u] for u in range(max(0,t-3),t))
                c['x3']+= any('x' in ep.keys[u] for u in range(max(0,t-3),t))
                rl=ep.raw_rows[t][ri]; c['rule_'+('R' if 'r' in rl else ('none' if not rl.replace('c','').replace('s','') else rl))]+=1
            # ticks between 1st and last
            for t in range(r[0],r[-1]+1):
                c['ticks']+=1; c['x']+= 'x' in ep.keys[t]; c['S']+= 'S' in ep.keys[t]; c['W']+='W' in ep.keys[t]; c['air_t']+=ep.meAir[t]
                c['A/D']+= ('A' in ep.keys[t] or 'D' in ep.keys[t])
    return c
for nm,D in (('C',C),('R',R)):
    c=inside(D);h=c['hit'];tt=c['ticks']
    print(nm,'hits',h,{k:round(v/h,2) for k,v in c.items() if k not in('hit','ticks','x','S','W','air_t','A/D')},'| per tick',{k:round(c[k]/tt,2) for k in ('x','S','W','air_t','A/D')})
print('---- after each taken hit in a combo (hit#>=2 with >=3 in chain): next 11 ticks, escaped (no hit in 15) vs continued')
def after(D):
    c=collections.defaultdict(collections.Counter)
    for ep in D.values():
        tk=[t for t in range(ep.n) if ep.taken[t]]
        run=[];allr=[]
        for t in tk:
            if run and (t-run[-1]>15 or ep.n_dealt(run[-1]+1,t-1)>0): allr.append(run);run=[]
            run.append(t)
        if run: allr.append(run)
        for r in allr:
            if len(r)<2: continue
            for i,t in enumerate(r):
                if i<1: continue
                last=(i==len(r)-1)
                if last and t+15>=ep.n: continue
                kk='escape' if last else 'cont'
                o=c[kk];o['n']+=1
                w=range(t+1,min(ep.n,t+12))
                if not len(w): continue
                for u in w:
                    k=ep.keys[u]
                    o['S']+='S' in k;o['W']+='W' in k;o['x']+='x' in k;o['j']+='j' in k;o['AD']+=('A' in k or 'D' in k);o['t']+=1;o['air']+=ep.meAir[u]
                    rl=ep.raw_rows[u][ri];o['rb']+='b' in rl;o['rr']+='r' in rl;o['rk']+='k' in rl;o['ra']+='a' in rl
                    o['dist']+=ep.dist[u]
                o['ended_dealt']+=ep.n_dealt(t+1,t+15)>0
    return c
for nm,D in (('C',C),('R',R)):
    for kk,o in after(D).items():
        t=o['t']; print(nm,kk,'n',o['n'],{k:round(v/t,2) for k,v in o.items() if k not in('n','t','ended_dealt')},'dealt-in-15 %.2f'%(o['ended_dealt']/o['n']))
print('---- S/b/r rule by tick after hit (hit#>=2 of chain>=3), C vs R')
def hist(D):
    S=[0]*14;Bb=[0]*14;Rr=[0]*14;N=0;Aa=[0]*14
    for ep in D.values():
        tk=[t for t in range(ep.n) if ep.taken[t]]
        run=[];allr=[]
        for t in tk:
            if run and (t-run[-1]>15 or ep.n_dealt(run[-1]+1,t-1)>0): allr.append(run);run=[]
            run.append(t)
        if run: allr.append(run)
        for r in allr:
            if len(r)<3: continue
            for t in r[1:]:
                N+=1
                for d in range(14):
                    u=t+d
                    if u<ep.n:
                        S[d]+='S' in ep.keys[u];rl=ep.raw_rows[u][ri];Bb[d]+='b' in rl;Rr[d]+='r' in rl;Aa[d]+=ep.meAir[u]
    return N,S,Bb,Rr,Aa
for nm,D in (('C',C),('R',R)):
    N,S_,B_,R_,A_=hist(D);print(nm,N);print(' S ',[round(x/N,2) for x in S_]);print(' b ',[round(x/N,2) for x in B_]);print(' r ',[round(x/N,2) for x in R_]);print(' air',[round(x/N,2) for x in A_])
print('---- rule at hit tick (#>=2 in chain>=2) by air/ground')
def rt(D):
    c=collections.Counter()
    for ep in D.values():
        tk=[t for t in range(ep.n) if ep.taken[t]]
        prev=None
        for t in tk:
            if prev is not None and t-prev<=15 and ep.n_dealt(prev+1,t-1)==0:
                # t is hit>=2
                rl=ep.raw_rows[t][ri]
                tag='r' if 'r' in rl else ('b' if 'b' in rl else 'none')
                c[(('air' if ep.meAir[t] else 'gnd'),tag)]+=1
            prev=t
    return c
for nm,D in (('C',C),('R',R)): print(nm,sorted(rt(D).items()))
print('---- next-hit-within-15 by rule at ground hit#>=2')
def rt2(D):
    c=collections.defaultdict(lambda:[0,0,0.0])
    for ep in D.values():
        tk=[t for t in range(ep.n) if ep.taken[t]]
        for i,t in enumerate(tk):
            if i==0 or tk[i-1]<t-15 or ep.n_dealt(tk[i-1]+1,t-1)>0: continue
            if t+16>=ep.n or ep.meAir[t]: continue
            rl=ep.raw_rows[t][ri]; tag='r' if 'r' in rl else ('b' if 'b' in rl else 'none')
            nxt=any(ep.taken[u] for u in range(t+1,t+16))
            c[tag][0]+=1;c[tag][1]+=nxt
            c[tag][2]+=sum('S' in ep.keys[u] for u in range(t+1,t+9))/8.0
    return c
for nm,D in (('C',C),('R',R)):
    print(nm,{k:(v[0],round(v[1]/v[0],2),round(v[2]/v[0],2)) for k,v in rt2(D).items()})
print('---- falsifier: net HP per fight excluding the combo hits beyond #5 (hits 6+ of any taken chain removed)')
def netx(D,cut):
    tot=0
    for ep in D.values():
        net=ep.lost(ep.oppHp,1,ep.n)-ep.lost(ep.myHp,1,ep.n)
        if cut:
            tk=[t for t in range(ep.n) if ep.taken[t]];run=[]
            allr=[]
            for t in tk:
                if run and (t-run[-1]>15 or ep.n_dealt(run[-1]+1,t-1)>0): allr.append(run);run=[]
                run.append(t)
            if run: allr.append(run)
            for r in allr:
                for t in r[5:]:
                    net+=ep.myHp[t-1]-ep.myHp[t] if ep.myHp[t-1]>ep.myHp[t] else 0
        tot+=net
    return tot/len(D)
print('all   C %+.2f R %+.2f gap %+.2f'%(netx(C,0),netx(R,0),netx(C,0)-netx(R,0)))
print('cut   C %+.2f R %+.2f gap %+.2f'%(netx(C,1),netx(R,1),netx(C,1)-netx(R,1)))
print('---- outside combos (no hit taken in prior 15 ticks), per fight')
def outside(D):
    c=collections.Counter()
    for ep in D.values():
        last=-99
        for t in range(ep.n):
            if ep.taken[t]: last=t
            if t-last<=15: continue
            c['t']+=1
            c['air']+=ep.meAir[t]
            near=ep.dist[t]<3.3
            ready=ep.mySw[t]>=0.9
            c['near']+=near
            c['near_gnd_ready']+= near and ready and not ep.meAir[t]
            c['near_air_ready']+= near and ready and ep.meAir[t]
            c['x_near_gnd_ready']+= near and ready and not ep.meAir[t] and 'x' in ep.keys[t]
            c['x_near_air_ready']+= near and ready and ep.meAir[t] and 'x' in ep.keys[t]
            c['S']+='S' in ep.keys[t]
    return c
for nm,D in (('C',C),('R',R)):
    c=outside(D);print(nm,{k:(round(v/60,1) if k in('t','near','near_gnd_ready','near_air_ready','x_near_gnd_ready','x_near_air_ready') else round(v/c['t'],3)) for k,v in c.items()})
