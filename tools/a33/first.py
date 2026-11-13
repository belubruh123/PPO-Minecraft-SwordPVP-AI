import glob,collections,statistics as st
exec(open('D:/swordai/tools/a33/spacing.py').read().split("fs=sorted(glob")[0])
fs=sorted(set(f for f in glob.glob(D+"20261113-224*") if '-open' not in f))
old=sorted(f for f in glob.glob(D+"2026110*")+glob.glob(D+"2026111[0-2]*") if '-won' in f)
def go(name,files):
    C=collections.Counter();dd=[];dp=[];W=0;S=0;n=0
    for f in files:
        rows=load(f)
        for i,r in enumerate(rows):
            ev=r['event']
            if 'first-' in ev and i>=8:
                n+=1; d=float(r['dist'])
                C['ready' if 'my-sword-ready' in ev else 'empty' if 'empty' in ev else 'other']+=1
                C['opp_air' if r['opp'][0]=='a' else 'opp_gnd']+=1
                C['me_air' if r['me']=='a' else 'me_gnd']+=1
                C['jumpedin' if 'jumped-in' in ev else 'notjumpin']+=1
                C['crit' if 'crit?' in ev else 'nocrit']+=1
                w=rows[i-8:i]; W+=sum('W' in x['keys'] for x in w); S+=sum('S' in x['keys'] for x in w)
                dp.append(float(rows[i-8]['dist']))
                C['own_closing']+= float(r['myToward'])>0.1
                C['opp_closing']+= float(r['oppToward'])>0.1
    print(name,n,dict(C),"W%",round(100*W/(8*n)),"S%",round(100*S/(8*n)),"dist 8 ticks before",round(st.mean(dp),2))
go("tonight",fs);go("old",old)
