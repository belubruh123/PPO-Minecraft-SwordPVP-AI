import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter, defaultdict
# for each hit taken: how was it answered. group by whether keep 'k' fired within [T-1,T+2]
G=defaultdict(Counter)
def hit_w(x): return 1.5 if x["crit"] else 1.0
for ts,res,rows in rounds:
    n=len(rows)
    for i,r in enumerate(rows):
        if not r["T"]: continue
        ctx="air" if r["me"][0]=="a" else "ground"
        kk=any("k" in x["rules"] for x in rows[i:i+3])
        g=("k" if kk else "no-k")
        fut=rows[i+1:i+26]
        # first swing after T
        sw=next((t for t,x in enumerate(fut) if x["x"]),None)
        dd=next((t for t,x in enumerate(fut) if x["D"]),None)
        tt=next((t for t,x in enumerate(fut) if x["T"]),None)
        c=G[g]; c["n"]+=1
        c["swing_made"]+= sw is not None
        c["delay_sum"]+= sw if sw is not None else 0
        c["dealt25"]+= dd is not None
        c["we_hit_first"]+= dd is not None and (tt is None or dd<tt)
        c["they_hit_again_first"]+= tt is not None and (dd is None or tt<dd)
        c["neither"]+= dd is None and tt is None
        # damage units in next 25 both ways
        c["dealt_units"]+=sum(hit_w(x) for x in fut if x["D"] and (x["crit"] or True))
        # type of our first swing
        if sw is not None:
            x=fut[sw]; c["first_"+("crit" if x["crit"] else "spr" if x["spr"] else "plain" if x["pl"] else "?")]+=1
        c["taken25"]+=sum(1 for x in fut if x["T"])
for g,c in G.items():
    n=c["n"]
    print(g,"n",n,{k:(round(v/n,2) if k not in("n",) else v) for k,v in c.items()})
