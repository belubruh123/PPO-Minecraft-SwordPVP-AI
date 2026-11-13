import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter, defaultdict
G=defaultdict(Counter)
for ts,res,rows in rounds:
    for i,r in enumerate(rows):
        if "m" in r["rules"] and not (i>0 and "m" in rows[i-1]["rules"]):
            fut=rows[i:i+16]
            d=next((t for t,x in enumerate(fut) if x["D"]),None); t_=next((t for t,x in enumerate(fut) if x["T"]),None)
            rd="oppReady" if r["oppSword"]>=0.9 else "oppSpent"
            db="d<=3.5" if r["dist"]<=3.5 else "d3.5-5" if r["dist"]<=5 else "d>5"
            for g in (rd, db, rd+" "+db, "ALL"):
                c=G[g]; c["n"]+=1
                c["we_first"]+= d is not None and (t_ is None or d<t_)
                c["they_first"]+= t_ is not None and (d is None or t_<d)
                c["D"]+=sum(x["D"] for x in fut); c["T"]+=sum(x["T"] for x in fut)
                c["sprhit_first"]+= d is not None and (t_ is None or d<t_) and any(x["spr"] for x in fut[:d+1])
for g in sorted(G):
    c=G[g]; n=c["n"]; print("%-20s n %3d we_first %.2f (sprint %.2f) they_first %.2f D/ep %.2f T/ep %.2f"%(g,n,c["we_first"]/n,c["sprhit_first"]/n,c["they_first"]/n,c["D"]/n,c["T"]/n))
