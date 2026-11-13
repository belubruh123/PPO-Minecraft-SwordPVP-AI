import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds
from collections import Counter
C=Counter(); H=Counter()
for ts,res,rows in rounds:
    for i,r in enumerate(rows):
        if not r["crit"]: continue
        pun=any(x["T"] for x in rows[max(0,i-14):i])
        w=rows[max(0,i-12):i+1]
        rl="".join(x["rules"] for x in w)
        jump_src=None
        for x in reversed(w):
            if x["j"] and x["me"][0]=="g": jump_src="J" if "J" in x["rules"] else "r" if "r" in x["rules"] else "net"; break
        k=("punish" if pun else "voluntary")
        C[k]+=1
        if not pun: C["vol_jump_"+str(jump_src)]+=1; C["vol_with_k_or_m"]+=("k" in rl or "m" in rl)
        else: C["pun_jump_"+str(jump_src)]+=1
print(dict(C))
