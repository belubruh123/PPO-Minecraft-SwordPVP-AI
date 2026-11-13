"""a31: A/B state at the moment of their crit chain-start, by context. usage state.py K"""
import sys, json, re, collections, math
from load import *
exec(open('crit.py').read().split("K=int(sys.argv[1])")[0].split("G = 20")[0])  # imports only
G=20
H, checks = segments(HITS)
pj = json.load(open('pairs.json'))
steps=[]
for line in open(CHECKLOG):
    m=re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps',line)
    if m: steps.append((m.group(1),m.group(3)))
steps=[s for s in steps if pj.get(s[1])]
def f(r,k): return float(r[H[k]])
def i_(r,k): return int(r[H[k]])
K=int(sys.argv[1]) if len(sys.argv)>1 else 3
rows_out=collections.defaultdict(list)  # (ctx,side)->list of dicts
for i in range(1,K+1):
    t,st=steps[-i]; F=fights(H,checks[-i]); L=pj[st]
    for (p,s),rows in F.items():
        ev=[]
        for r in rows:
            tk=i_(r,'tick')
            if f(r,'dealt')>0: ev.append(dict(t=tk,who='it',crit=i_(r,'crit'),b1=r[H['situation']]=='B1',row=r))
            if f(r,'taken')>0: ev.append(dict(t=tk,who='them',crit=1 if i_(r,'critsTaken')>0 or f(r,'taken')>=10 else 0,dmg=f(r,'taken'),row=r))
        for k,e in enumerate(ev):
            if e['who']!='them' or not e['crit']: continue
            prev=ev[k-1] if k else None
            if prev and e['t']-prev['t']<=G:
                if prev['who']=='them': continue
                ctx='reply to its B1' if prev['b1'] else 'reply to its crit' if prev['crit'] else 'reply to its plain/sprint hit'
                gap=e['t']-prev['t']
            else:
                ctx='opener'; gap=99
            r=e['row']
            ctx2=ctx
            if ctx=='opener':
                ctx2='opener after its swing<=12t' if i_(r,'sinceSwing')<=12 else 'opener, no own swing'
            d=dict(dmg=e['dmg'],ground=i_(r,'onGround'),sprint=i_(r,'sprinting'),sk=i_(r,'sprintKey'),w=i_(r,'forward'),
                   strafe=1 if i_(r,'strafe')!=0 else 0,back=1 if i_(r,'forward')<0 else 0,jump=i_(r,'jumpKey'),reach=f(r,'reach'),
                   charge=f(r,'charge'),swing=i_(r,'sinceSwing'),gap=gap,fall=i_(r,'falling'),sit=r[H['situation']])
            rows_out[(ctx2,s)].append(d); rows_out[('ALL crit starts',s)].append(d)
def mean(l): return sum(l)/len(l) if l else float('nan')
print('K=%d'%K)
for ctx in sorted({k[0] for k in rows_out}):
    for s in 'AB':
        l=rows_out[(ctx,s)]
        if not l: continue
        print('%-30s %s n %3d dmg %5.0f | ground %2.0f%% falling %2.0f%% jumpkey %2.0f%% W %2.0f%% S %2.0f%% strafe %2.0f%% sprint %2.0f%% | reach %.2f charge %.2f | swing<=12 %2.0f%% | gap %.1f'%(ctx,s,len(l),sum(x['dmg'] for x in l),
          100*mean([x['ground'] for x in l]),100*mean([x['fall'] for x in l]),100*mean([x['jump'] for x in l]),100*mean([x['w']>0 for x in l]),100*mean([x['back'] for x in l]),100*mean([x['strafe'] for x in l]),100*mean([x['sprint'] for x in l]),
          mean([x['reach'] for x in l]),mean([x['charge'] for x in l]),100*mean([x['swing']<=12 for x in l]),mean([x['gap'] for x in l if x['gap']<99])))
    # situations
import collections as C
for ctx in ['opener after its swing<=12t','opener, no own swing']:
    for s in 'AB':
        print(ctx,s,C.Counter(x['sit'] for x in rows_out[(ctx,s)]).most_common(6))
