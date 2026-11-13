import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter
rowsout=[]
S=Counter()
for ts,res,rows in rounds:
    ld=-99; lt=-99
    for i,r in enumerate(rows):
        if r["D"]: ld=i
        if r["T"]: lt=i
        if r["j"] and r["me"][0]=="g" and "J" in r["rules"] and not (i>0 and rows[i-1]["j"]):
            fut=rows[i:i+20]
            d=next((t for t,x in enumerate(fut) if x["D"]),None); t_=next((t for t,x in enumerate(fut) if x["T"]),None)
            kind="uppercut(own combo)" if i-ld<=10 else "approach/chase d>=4" if r["dist"]>=4 else "near(d<4) other"
            S[kind]+=1
            S[kind+"|we_first"]+= d is not None and (t_ is None or d<t_)
            S[kind+"|they_first"]+= t_ is not None and (d is None or t_<d)
            S[kind+"|crit_made"]+=sum(x["crit"] for x in fut); S[kind+"|spr_made"]+=sum(x["spr"] for x in fut)
            rowsout.append((ts,i,round(r["dist"],1),r["oppToward"],i-ld,i-lt,d,t_))
for k in sorted(S): print(k,S[k])
for x in rowsout[:30]: print(x)
