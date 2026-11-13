import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter, defaultdict
G=defaultdict(Counter)
def tally(c,fut):
    d=next((t for t,x in enumerate(fut) if x["D"]),None); t_=next((t for t,x in enumerate(fut) if x["T"]),None)
    c["n"]+=1
    if d is not None and (t_ is None or d<t_): c["we_first"]+=1
    elif t_ is not None: c["they_first"]+=1
    else: c["none"]+=1
    c["D"]+=sum(x["D"] for x in fut); c["T"]+=sum(x["T"] for x in fut)
    c["crit_made"]+=sum(x["crit"] for x in fut)
    c["spr_made"]+=sum(x["spr"] for x in fut)
for ts,res,rows in rounds:
    prev=False
    for i,r in enumerate(rows):
        up=float(r["oppUp"]); tw=float(r["oppToward"])
        cond = r["opp"][0]=="a" and up>=0.05 and tw>=0.05 and r["dist"]<=6 and r["mySword"]>=0.5 and r["me"][0]=="g"
        if cond and not prev:
            mf="m" in r["rules"]
            sprint_fwd = r["keys"][0]=="W" and r["keys"][2]=="r"
            db="d<=3.5" if r["dist"]<=3.5 else "d3.5-5" if r["dist"]<=5 else "d>5"
            g=("m" if mf else "no-m")+" "+("W+spr" if sprint_fwd else "not-W+spr")+" "+db
            tally(G[g],rows[i:i+16]); tally(G["ALL "+("m" if mf else "no-m")],rows[i:i+16])
        prev=cond
for g in sorted(G):
    c=G[g]; n=c["n"]; print("%-26s n %3d we_first %.2f they_first %.2f | D/ep %.2f T/ep %.2f crit/ep %.2f spr/ep %.2f"%(g,n,c["we_first"]/n,c["they_first"]/n,c["D"]/n,c["T"]/n,c["crit_made"]/n,c["spr_made"]/n))
