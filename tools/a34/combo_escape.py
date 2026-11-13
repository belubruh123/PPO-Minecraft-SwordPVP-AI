import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter, defaultdict
G=defaultdict(Counter)
for ts,res,rows in rounds:
    streak=0; lastT=-99
    for i,r in enumerate(rows):
        if r["D"]: streak=0
        if r["T"]:
            streak = streak+1 if i-lastT<=25 else 1
            lastT=i
            if streak>=2:
                w=rows[i:i+4]
                tag="r" if any("r" in x["rules"] for x in w) else "b" if any("b" in x["rules"] for x in w) else "none"
                fut=rows[i+1:i+16]
                c=G[tag]; c["n"]+=1
                c["next_hit_taken_15"]+=any(x["T"] for x in fut)
                c["we_dealt_15"]+=any(x["D"] for x in fut)
                # net hits in 15
                c["T_in_15"]+=sum(x["T"] for x in fut); c["D_in_15"]+=sum(x["D"] for x in fut)
                c["streak_ge3_continues"]+= streak>=2 and any(x["T"] for x in fut[:12]) and not any(x["D"] for x in fut[:12])
for g,c in G.items():
    n=c["n"]; print(g,n,{k:round(v/n,2) for k,v in c.items() if k!="n"})
