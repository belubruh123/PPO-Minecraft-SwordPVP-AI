import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from collections import Counter
tot=Counter(); n=0
for f in files():
    ts,res,head,rows=load(f)
    n+=len(rows)
    ev=Counter()
    for r in rows:
        e=r["event"]
        if "DEALT" in e: ev["D"]+=1
        if "TAKEN" in e: ev["T"]+=1
        for s in ("crit","sprint","plain"):
            if "swing:"+s in e: ev[s]+=1
        for c in r["rules"]: tot[c]+=1
        if "j" in r["keys"][3:4]: ev["jkeys"]+=1
    print(ts,res,len(rows),dict(ev), head[:90])
print(n,dict(tot))
