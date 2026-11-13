import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from collections import defaultdict
W=20
def feats(rows):
    n=len(rows)
    for i,r in enumerate(rows):
        e=r["event"]
        r["D"]="DEALT" in e; r["T"]="TAKEN" in e
        r["crit"]="swing:crit" in e; r["spr"]="swing:sprint" in e; r["pl"]="swing:plain" in e
        r["x"]=r["keys"][4]=="x"; r["j"]=r["keys"][3]=="j"
        r["air"]=r["me"].startswith("a")
    for i,r in enumerate(rows):
        r["takeoff"]= i>0 and r["me"][0]=="a" and rows[i-1]["me"][0]=="g"
rounds=[]
for f in files():
    ts,res,head,rows=load(f); feats(rows); rounds.append((ts,res,rows))
stat=defaultdict(lambda: defaultdict(float))
def add(name, rows, i):
    s=stat[name]; s["eps"]+=1
    seg=rows[i:i+W]
    s["D"]+=sum(r["D"] for r in seg); s["T"]+=sum(r["T"] for r in seg)
    s["crit"]+=sum(r["crit"] for r in seg); s["spr"]+=sum(r["spr"] for r in seg); s["pl"]+=sum(r["pl"] for r in seg)
    s["take"]+=sum(r["takeoff"] for r in seg)
    s["swing"]+=sum(r["x"] for r in seg)
for ts,res,rows in rounds:
    for L in "akmrbtJcls":
        on=False
        for i,r in enumerate(rows):
            h=L in r["rules"]
            if h:
                stat[L]["ticks"]+=1
                if not on: add(L,rows,i)
            on=h
    # baseline: starts of ticks within reach (dist<3.5), no rule among a,k,m,r,b,J, every 10th tick
    for i,r in enumerate(rows):
        if r["dist"]<3.5 and not any(c in r["rules"] for c in "akmrbJt") and i%5==0:
            add("base_inreach",rows,i)
        if r["dist"]>=3.5 and not any(c in r["rules"] for c in "akmrbJt") and i%5==0:
            add("base_far",rows,i)
for k in list(stat)+[]:
    s=stat[k]; e=s["eps"]
    print("%-12s eps %4d ticks %5d | per-episode next%d: D %.2f T %.2f crit %.2f spr %.2f pl %.2f takeoff %.2f swings %.2f"%(k,e,s["ticks"],W,s["D"]/e,s["T"]/e,s["crit"]/e,s["spr"]/e,s["pl"]/e,s["take"]/e,s["swing"]/e))
