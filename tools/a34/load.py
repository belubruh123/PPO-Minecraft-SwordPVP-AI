import gzip, glob, os, re, sys
D = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
def files():
    fs = sorted(f for f in glob.glob(D+"20261113-224*.txt.gz") if "open" not in os.path.basename(f))
    return fs
def load(f):
    lines = gzip.open(f, "rt", encoding="utf-8", errors="replace").read().split("\n")
    head = lines[1]
    res = "WON" if head.startswith("WON") else "LOST" if head.startswith("LOST") else "?"
    ts = os.path.basename(f)[9:15]
    i = next(k for k,l in enumerate(lines) if l.startswith("tick,dist"))
    cols = lines[i].split(",")
    rows = []
    for l in lines[i+1:]:
        if not l or not l[0].isdigit(): continue
        p = l.split(",", len(cols)-1)
        if len(p) < len(cols): continue
        d = dict(zip(cols, p))
        d["tick"] = int(d["tick"]); d["dist"]=float(d["dist"]); d["mySword"]=float(d["mySword"]); d["oppSword"]=float(d["oppSword"])
        d["myHp"]=int(d["myHp"]); d["oppHp"]=int(d["oppHp"])
        rows.append(d)
    return ts, res, head, rows
