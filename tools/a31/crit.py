"""a31: their crit chain-starts split by context, A vs B, per-pair diff with se. usage: crit.py K (last K checks)"""
import sys, json, re, collections, math
from load import *
G = 20
H, checks = segments(HITS)
pj = json.load(open('pairs.json'))
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps', line)
    if m: steps.append((m.group(1), m.group(3)))
steps = [s for s in steps if s[1] in pj and pj[s[1]]]
def f(r,k): return float(r[H[k]])
def i_(r,k): return int(r[H[k]])
def events(rows, logged):
    ev=[]
    for r in rows:
        t=i_(r,'tick'); d,tk=f(r,'dealt'),f(r,'taken')
        if d>0: ev.append(dict(t=t,who='it',dmg=d,crit=i_(r,'crit'),pc=i_(r,'punishCrit'),row=r))
        if tk>0: ev.append(dict(t=t,who='them',dmg=tk,crit=1 if i_(r,'critsTaken')>0 or tk>=10 else 0,row=r))
    return ev
def classify(ev,k):
    e=ev[k]
    prev=ev[k-1] if k>0 else None
    r=e['row']
    sw=i_(r,'sinceSwing')
    if prev is None or e['t']-prev['t']>G:
        base='open'
    elif prev['who']=='them': return None  # continuation of their chain
    else:
        base='reply to its B1' if prev['pc'] else 'reply to its crit' if prev['crit'] else 'reply to its plain hit'
        return base
    # opener: whiff proxy = it swung within 12 ticks (no hit logged in the window)
    if sw<=12: return 'open after its own swing(whiff)'
    return 'open other (jump-in, mostly rule-bound)'
K=int(sys.argv[1]) if len(sys.argv)>1 else 3
tot=collections.defaultdict(lambda:{'A':[0,0.0],'B':[0,0.0]})
per={}
for i in range(1,K+1):
    t,st=steps[-i]; F=fights(H,checks[-i]); L=pj[st]
    print('check',t,st)
    for (p,s),rows in F.items():
        ev=events(rows,L['%d%s'%(p,s)])
        for k,e in enumerate(ev):
            if e['who']!='them' or not e['crit']: continue
            c=classify(ev,k)
            if c is None: continue
            tot[c][s][0]+=1; tot[c][s][1]+=e['dmg']
            per.setdefault((i,p),{}).setdefault(s,collections.Counter())[c]+=e['dmg']
            per[(i,p)][s]['ALL']+=e['dmg']
pairs=[v for v in per.values()]
nfights=len({(i,p) for (i,p) in per})
print('crit chain-starts, K=%d checks (damage = first hit only)'%K)
cats=sorted(tot)+['ALL']
npairs=K*44
for c in cats:
    if c=='ALL':
        nA=sum(tot[x]['A'][0] for x in tot); nB=sum(tot[x]['B'][0] for x in tot)
        dA=sum(tot[x]['A'][1] for x in tot); dB=sum(tot[x]['B'][1] for x in tot)
    else:
        nA,dA=tot[c]['A']; nB,dB=tot[c]['B']
    diffs=[(v.get('A',{}).get(c,0) if 'A' in v else 0)-(v.get('B',{}).get(c,0) if 'B' in v else 0) for v in per.values()]
    diffs+= [0]*(npairs-len(diffs))
    m=sum(diffs)/npairs; sd=math.sqrt(sum((d-m)**2 for d in diffs)/(npairs-1)); se=sd/math.sqrt(npairs)
    print('%-42s nA %4d nB %4d | dmgA %6.0f dmgB %6.0f | A-B per fight %+5.2f se %.2f | per check A %.0f B %.0f'%(c,nA,nB,dA,dB,m,se,dA/K,dB/K))
