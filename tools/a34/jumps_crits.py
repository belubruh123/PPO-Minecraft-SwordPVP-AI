import sys; sys.path.insert(0,"D:/swordai/tools/a34")
from load import *
from episodes import rounds  # reuses feats
from collections import Counter, defaultdict
# jumps = j pressed from ground (first tick of press, me on ground)
jc=Counter(); ctx=defaultdict(Counter); nj=0
crit_c=Counter(); n_crit=0
for ts,res,rows in rounds:
    last_taken=-99; last_dealt=-99
    for i,r in enumerate(rows):
        if r["T"]: last_taken=i
        if r["D"]: last_dealt=i
        if r["j"] and r["me"][0]=="g" and not (i>0 and rows[i-1]["j"]):
            nj+=1
            ru=r["rules"]
            who = "J" if "J" in ru else "r" if "r" in ru else "net"
            jc[who]+=1
            since_t=i-last_taken; since_d=i-last_dealt
            c = "after_taken<=12" if since_t<=12 else "after_dealt<=12" if since_d<=12 else "free"
            ctx[who][c]+=1
            ctx[who]["dist<3.5"]+= r["dist"]<3.5
            ctx[who]["dist>=6"]+= r["dist"]>=6
        if r["crit"]:
            n_crit+=1
            win=rows[max(0,i-8):i+1]
            rl="".join(x["rules"] for x in win)
            t12=any(x["T"] for x in rows[max(0,i-14):i])
            tag=[]
            if "k" in rl: tag.append("k")
            if "m" in rl: tag.append("m")
            if "r" in rl: tag.append("r")
            if "J" in rl: tag.append("J")
            if "a" in rl: tag.append("a")
            crit_c["+".join(tag) or "none"]+=1
            crit_c["[punish: hit taken <=14 ticks before]"]+=t12
            crit_c["[after own take-off from ground within 10]"]+= any(x["j"] and x["me"][0]=="g" for x in rows[max(0,i-10):i+1])
print("ground jumps",nj,dict(jc), "per round %.1f"%(nj/len(rounds)))
for w in ctx: print(w,dict(ctx[w]))
print("crit swings",n_crit,dict(crit_c))
