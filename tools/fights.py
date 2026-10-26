import re,glob,os,statistics as st
bots={'attack-then-s':'C3','stun-escaper':'C3','uppercutter':'T10','lunger':'T11a','fall-spacer':'K3'}
files=sorted(glob.glob(r'D:\swordai\run\school-client-1025-*.log'))+[r'D:\swordai\run\school-client.log']
pat=re.compile(r'school fight (random )?([a-z\-]+): dealt ([\d.]+), taken ([\d.]+), ticks (\d+)(.*?)teacher spoke on (\d+) ticks; (random )?[a-z\-]+ \[(.*?)\] react (\d+) timing ([\d.]+) lag (\d+) ping (\d+)')
rows=[]
for f in files:
    for l in open(f,encoding='utf-8',errors='replace'):
        if 'school fight' not in l: continue
        m=pat.search(l)
        if not m: continue
        name=m.group(2)
        if name not in bots: continue
        rnd=bool(m.group(8))
        tech=dict(kv.split('=') for kv in m.group(9).split() if '=' in kv)
        tech={k:float(v) for k,v in tech.items()}
        d=float(m.group(3)); t=float(m.group(4)); ticks=int(m.group(5))
        rows.append(dict(bot=name,rnd=rnd,d=d,t=t,ticks=ticks,tech=tech,react=int(m.group(10)),timing=float(m.group(11)),lag=int(m.group(12)),ping=int(m.group(13)),f=os.path.basename(f)))
print(len(rows))
for b,key in bots.items():
    for rnd in (False,True):
        r=[x for x in rows if x['bot']==b and x['rnd']==rnd]
        if not r: continue
        net=[(x['d']-x['t']) for x in r]
        dm=[x['d']*1200/x['ticks'] for x in r]; tm=[x['t']*1200/x['ticks'] for x in r]
        print(f"{b:14s} {'random' if rnd else 'pure  '} n={len(r):4d} net/fight {st.mean(net):6.1f} dealt/min {st.mean(dm):5.0f} taken/min {st.mean(tm):5.0f} ticks {st.mean([x['ticks'] for x in r]):5.0f}")
        if not rnd:
            # split by key technique p
            r2=sorted(r,key=lambda x:x['tech'].get(key,0))
            h=len(r2)//2
            for part,name in ((r2[:h],'low '+key),(r2[h:],'high '+key)):
                if part: print(f"     {name:10s} p {st.mean([x['tech'].get(key,0) for x in part]):.2f} net {st.mean([x['d']-x['t'] for x in part]):6.1f}")
            for attr in ('ping','lag','react'):
                r3=sorted(r,key=lambda x:x[attr]); h=len(r3)//2
                print(f"     {attr} low {st.mean([x[attr] for x in r3[:h]]):.1f}: net {st.mean([x['d']-x['t'] for x in r3[:h]]):6.1f} | high {st.mean([x[attr] for x in r3[h:]]):.1f}: net {st.mean([x['d']-x['t'] for x in r3[h:]]):6.1f}")
