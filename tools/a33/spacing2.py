import sys,glob,collections,statistics as st
sys.path.insert(0,'D:/swordai/tools/a33')
import importlib.util
src=open('D:/swordai/tools/a33/spacing.py').read().split("fs=sorted(glob")[0]
exec(src)
fs=sorted(set(f for f in glob.glob(D+"20261113-224*") if '-open' not in f))
old=sorted(f for f in glob.glob(D+"2026110*")+glob.glob(D+"2026111[0-2]*") if '-won' in f)
def run(name,files):
    H=collections.defaultdict(collections.Counter);C=collections.Counter();A=[]
    for f in files:
        rows=load(f)
        for r in rows:
            for k in('dist','mySword','oppSword'):r[k]=float(r[k])
        n=len(rows)
        for i,r in enumerate(rows):
            d=r['dist']
            if d<8:
                C['t8']+=1
                if r['me']=='a':C['air8']+=1
                if d<1.2: 
                    C['t<1.2']+=1
                    if 'W' in r['keys']:C['W<1.2']+=1
                    if 'S' in r['keys']:C['S<1.2']+=1
                if r['mySword']<0.9 and r['oppSword']>=0.9 and d<3:
                    C['emp_oppready']+=1
                    if 'S' in r['keys']:C['emp_oppready_S']+=1
                    if 'W' in r['keys']:C['emp_oppready_W']+=1
                if r['mySword']>=0.9 and 2.6<=d<=3.3 : 
                    C['edge_ready']+=1
            ev=r['event']
            if 'DEALT' in ev:
                b=int(min(d,3.5)*2)/2; H['dealt'][b]+=1
                # after-hit: dist +4, hit taken within 10
                if i+10<n:
                    C['d_n']+=1; A.append(rows[i+4]['dist'])
                    if any('TAKEN' in x['event'] for x in rows[i+1:i+11]): C['d_counter10']+=1
                    if rows[i+4]['dist']<1.5:C['d_stay<1.5']+=1
            if 'TAKEN' in ev:
                b=int(min(d,3.5)*2)/2; H['taken'][b]+=1
                if i>=12:
                    C['t_n']+=1
                    if any('DEALT' in x['event'] for x in rows[i-12:i]): C['t_afterMyHit12']+=1
    t=C['t8'] or 1
    print(f"== {name}")
    print(" air share <8:",round(100*C['air8']/t),"% ; W at dist<1.2:",round(100*C['W<1.2']/max(1,C['t<1.2'])),"% S:",round(100*C['S<1.2']/max(1,C['t<1.2'])),"% (of",C['t<1.2'],"ticks)")
    print(" own empty & opp ready & d<3: ticks",C['emp_oppready'],"W",round(100*C['emp_oppready_W']/max(1,C['emp_oppready'])),"% S",round(100*C['emp_oppready_S']/max(1,C['emp_oppready'])),"%")
    for k in('dealt','taken'): print(" hist",k,{b:H[k][b] for b in sorted(H[k])})
    print(" after its hit: dist at +4 mean",round(st.mean(A),2),"; still <1.5 at +4:",round(100*C['d_stay<1.5']/C['d_n']),"%; hit back within 10 ticks:",round(100*C['d_counter10']/C['d_n']),"%")
    print(" hits taken within 12 ticks after its own hit:",round(100*C['t_afterMyHit12']/C['t_n']),"%")
run("tonight",fs);run("old won",old)
