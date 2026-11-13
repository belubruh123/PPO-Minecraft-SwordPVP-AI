import gzip,glob,sys,os,collections,statistics as st
D="C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
def load(f):
    rows=[];hdr=None
    for l in gzip.open(f,'rt',encoding='utf8',errors='replace'):
        if l.startswith('tick,dist'): hdr=l.strip().split(','); continue
        if hdr:
            p=l.rstrip('\n').split(',',len(hdr)-1)
            if len(p)==len(hdr) and p[0].isdigit(): rows.append(dict(zip(hdr,p)))
    return rows
def an(files):
    R=collections.defaultdict(list); C=collections.Counter()
    for f in files:
        rows=load(f)
        for r in rows:
            for k in('dist','mySword','oppSword','oppToward','myToward'):r[k]=float(r[k])
            r['tick']=int(r['tick'])
        for i,r in enumerate(rows):
            d=r['dist'];ev=r['event']
            if d<8:
                C['t8']+=1
                b='<2.5' if d<2.5 else '2.5-3.2' if d<=3.2 else '3.2-3.5' if d<=3.5 else '>3.5'
                C['bin'+b]+=1
                if d<3.0:
                    C['in3']+=1
                    if r['mySword']<0.9: C['in3_empty']+=1
                    if r['oppSword']>=0.9: C['in3_oppready']+=1
                    if r['mySword']<0.9 and r['oppSword']>=0.9: C['in3_empty_oppready']+=1
                    if r['mySword']<0.9 and r['oppSword']<0.9: C['in3_both_empty']+=1
                    if r['mySword']>=0.9 and r['oppSword']>=0.9: C['in3_both_ready']+=1
                    if r['mySword']>=0.9 and r['oppSword']<0.9: C['in3_myready_oppempty']+=1
            if i and rows[i-1]['dist']>=3.0>d:
                C['entries']+=1
                if r['oppSword']>=0.9: C['entry_oppready']+=1
                if r['mySword']<0.9: C['entry_myempty']+=1
                C['entry_myW']+= 'W' in r['keys']; C['entry_oppToward>0.1']+= r['oppToward']>0.1
            if 'DEALT' in ev: R['dealt_d'].append(d); R['dealt_mysw'].append(r['mySword'])
            if 'TAKEN' in ev:
                R['taken_d'].append(d); R['taken_oppswing_air']+= [r['opp']]
                C['taken']+=1
                if 'my-sword-empty' in ev: C['taken_empty']+=1
                if 'jumped-in' in ev: C['taken_jumpin']+=1
                w=rows[max(0,i-10):i]
                for k in 'WSrjAD': C['pre_'+k]+=sum(k in x['keys'] for x in w)
                C['pre_n']+=len(w)
                C['taken_oppToward_sum']+=r['oppToward']
                # distance 6 ticks before
                if i>=6: R['d_before6'].append(rows[i-6]['dist'])
                if r['me']=='g': C['taken_meG']+=1
                if r['opp'].startswith('a'): C['taken_oppAir']+=1
                if r['opp'].endswith('s'): C['taken_oppSpr']+=1
            if 'DEALT' in ev:
                C['dealt']+=1
                if r['me']=='g':C['dealt_meG']+=1
                if 'j' in r['keys']:C['dealt_j']+=1
            if 'first+' in ev: R['first+'].append(d)
            if 'first-' in ev: R['first-'].append(d)
            C['ticks']+=1
            if 'j' in r['keys'] and d<8: C['jticks8']+=1
            if 'W' in r['keys'] and d<8: C['W8']+=1
            if 'S' in r['keys'] and d<8: C['S8']+=1
    return R,C
def rep(name,files):
    R,C=an(files); n=len(files)
    m=lambda x:round(st.mean(x),2) if x else None
    md=lambda x:round(st.median(x),2) if x else None
    print(f"== {name}: {n} rounds, ticks {C['ticks']}, ticks<8 {C['t8']}")
    t=C['t8'] or 1
    print(" dist profile (<8 blocks): "+", ".join(f"{b} {100*C['bin'+b]/t:.0f}%" for b in['<2.5','2.5-3.2','3.2-3.5','>3.5']))
    print(f" its hits {C['dealt']}: dist mean {m(R['dealt_d'])} med {md(R['dealt_d'])}; on ground {C['dealt_meG']}, with j key {C['dealt_j']}")
    print(f" taken {C['taken']} ({C['taken']/n:.1f}/round): dist mean {m(R['taken_d'])} med {md(R['taken_d'])}; empty-sword {C['taken_empty']} ({100*C['taken_empty']/max(1,C['taken']):.0f}%), jumped-in {C['taken_jumpin']}, opp in air {C['taken_oppAir']}, opp sprint {C['taken_oppSpr']}")
    print(f" taken: dist 6 ticks earlier mean {m(R['d_before6'])}; keys in prior 10 ticks (share): "+", ".join(f"{k} {100*C['pre_'+k]/max(1,C['pre_n']):.0f}%" for k in 'WSrjAD'))
    print(f" first+ (it first) {len(R['first+'])} dist mean {m(R['first+'])}; first- {len(R['first-'])} dist mean {m(R['first-'])}")
    print(f" ticks<3.0: {C['in3']} ({100*C['in3']/t:.0f}% of <8); of them my sword empty {100*C['in3_empty']/max(1,C['in3']):.0f}%, opp ready {100*C['in3_oppready']/max(1,C['in3']):.0f}%, me empty & opp ready {100*C['in3_empty_oppready']/max(1,C['in3']):.0f}%, both ready {100*C['in3_both_ready']/max(1,C['in3']):.0f}%, me ready & opp empty {100*C['in3_myready_oppempty']/max(1,C['in3']):.0f}%")
    print(f" entries into <3.0: {C['entries']} ({C['entries']/n:.1f}/round); opp ready at entry {100*C['entry_oppready']/max(1,C['entries']):.0f}%, my empty {100*C['entry_myempty']/max(1,C['entries']):.0f}%, W pressed {100*C['entry_myW']/max(1,C['entries']):.0f}%, opp closing>0.1 {100*C['entry_oppToward>0.1']/max(1,C['entries']):.0f}%")
    print(f" keys within <8: W {100*C['W8']/t:.0f}% S {100*C['S8']/t:.0f}% j {100*C['jticks8']/t:.1f}%")
fs=sorted(glob.glob(D+"20261113-2242*")+glob.glob(D+"20261113-2243*")+glob.glob(D+"20261113-224*"))
fs=sorted(set(f for f in fs if '-open' not in f))
rep("TONIGHT all",fs)
rep("TONIGHT won",[f for f in fs if 'won' in f])
rep("TONIGHT lost",[f for f in fs if 'lost' in f])
old=sorted(f for f in glob.glob(D+"2026110*")+glob.glob(D+"2026111[0-2]*") if '-won' in f)
rep("OLD won (9-12 Nov)",old)
