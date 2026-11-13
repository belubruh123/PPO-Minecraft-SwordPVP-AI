import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter
S=Counter(); L=Counter(); delay=[]
for ts,res,rows in rounds:
    i=0
    while i<len(rows):
        if "k" in rows[i]["rules"] and not (i>0 and "k" in rows[i-1]["rules"]):
            j=i
            while j+1<len(rows) and "k" in rows[j+1]["rules"]: j+=1
            n=j-i+1; L[min(n,12)]+=1
            seg=rows[i:j+6]
            first_sw=next((t for t,x in enumerate(seg) if x["x"]),None)
            typ=None
            if first_sw is not None:
                x=seg[first_sw]; typ="crit" if x["crit"] else "spr" if x["spr"] else "plain" if x["pl"] else "?"
            tk=[t for t,x in enumerate(rows[i:j+1]) if x["T"]]
            S["eps"]+=1
            S["air_start" if rows[i]["me"][0]=="a" else "ground_start"]+=1
            S["swing_"+str(typ)]+=1
            S["taken_during_keep"]+=bool(tk)
            if first_sw is not None: delay.append(first_sw)
            # swing later but never? check next 20
            fut=rows[i:i+20]
            S["dealt_next20"]+=any(x["D"] for x in fut)
            S["taken_next20"]+=any(x["T"] for x in fut[1:])
            S["dealt_before_taken20"]+= (lambda a,b: a is not None and (b is None or a<b))(next((t for t,x in enumerate(fut) if x["D"]),None),next((t for t,x in enumerate(fut) if x["T"] and t>0),None))
            i=j+1
        else: i+=1
print(dict(S)); print("len hist",sorted(L.items()))
import statistics
print("ticks until first swing from k start: mean %.1f median %s"%(statistics.mean(delay),statistics.median(delay)), Counter(delay))
