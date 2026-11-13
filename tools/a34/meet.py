import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter
S=Counter(); B=Counter()
def first(fut):
    d=next((t for t,x in enumerate(fut) if x["D"]),None); t_=next((t for t,x in enumerate(fut) if x["T"]),None)
    return d,t_
def tally(c,fut,rows,i):
    d,t_=first(fut)
    c["n"]+=1
    if d is not None and (t_ is None or d<t_):
        c["we_first"]+=1
        x=next(x for x in fut if x["D"])  # event tick is hit; swing flags are on swing tick (earlier)
        sw=[y for y in fut[:d+1] if y["crit"] or y["spr"] or y["pl"]]
        c["our_first_type_"+(("crit" if sw[-1]["crit"] else "spr" if sw[-1]["spr"] else "plain") if sw else "?")]+=1
    elif t_ is not None:
        c["they_first"]+=1
        sw=[y for y in fut[:t_+1] if y["T"]]
        ev=fut[t_]["event"]
        c["they_first_"+("airborne-jumpin" if "jumped-in" in ev else "other")]+=1
    else: c["none"]+=1
    c["D15"]+=sum(x["D"] for x in fut); c["T15"]+=sum(x["T"] for x in fut)
for ts,res,rows in rounds:
    for i,r in enumerate(rows):
        m="m" in r["rules"] and not (i>0 and "m" in rows[i-1]["rules"])
        if m: tally(S,rows[i:i+16],rows,i)
        # baseline: opp rising & approaching within 6, sword back, no m on this tick or +-3
        up=float(r["oppUp"]); tw=float(r["oppToward"])
        cond = r["opp"][0]=="a" and up>=0.05 and tw>=0.08 and r["dist"]<=6 and r["mySword"]>=0.7
        near_m=any("m" in x["rules"] for x in rows[max(0,i-3):i+4])
        if cond and not near_m and not (i>0 and (float(rows[i-1]["oppUp"])>=0.05 and rows[i-1]["opp"][0]=="a")):
            tally(B,rows[i:i+16],rows,i)
print("m episodes",dict(S)); print("jumper-in no m",dict(B))
