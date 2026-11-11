"""Rounds flow of the window after its own ground non-crit hit. Per A fight, the expected net change if
(1) its take-offs (no trade) had the staying-down rates (A's own), (2) its staying-down exchanges had B's rates, (3) its trades had B's rate.
Flip proxy (as moments.py/flow.py): A fight not won where the net damage + delta >= 0 while it was < 0 and B won the same seed."""
import collections, sys, math, statistics as st
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
P = {}
cases = []
for i in range(1, K + 1):
    t, stp = steps[-i]; F = fights(H, checks[-i]); L = pj[stp]
    J = {}
    for r in jchecks[-i]:
        J.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    for (p, s), rows in F.items():
        lg = L['%d%s' % (p, s)]
        ev = events(rows, lg); js = J.get((p, s), [])
        P.setdefault((i, p), {})[s] = dict(res=lg[0], net=lg[1] - lg[2], cls=collections.Counter())
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            to = any(e['t'] < j <= e['t'] + 10 for j in js)
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = (nxt['t'] - e['t']) if nxt else 99
            res = 'none' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else 'their')
            cls = 'trade' if res == 'their' and dt <= 5 else ('jump' if to else 'down')
            cases.append((s, cls, res, nxt['dmg'] if res != 'none' else 0.0))
            P[(i, p)][s]['cls'][cls] += 1
rate = {}
for s in 'AB':
    for cls in ('trade', 'jump', 'down'):
        sub = [c for c in cases if c[0] == s and c[1] == cls]
        rate[(s, cls)] = {r: len([c for c in sub if c[2] == r]) / max(1, len(sub)) for r in ('own', 'their', 'none')}
    allh = [c for c in cases if c[0] == s]
    rate[(s, 'trade_rate')] = len([c for c in allh if c[1] == 'trade']) / len(allh)
own_d = st.mean([c[3] for c in cases if c[2] == 'own' and c[1] != 'trade'])
their_d = st.mean([c[3] for c in cases if c[2] == 'their' and c[1] != 'trade'])
trade_d = st.mean([c[3] for c in cases if c[1] == 'trade'])
print('mean dmg: own next %.2f, their late hit %.2f, their trade hit %.2f' % (own_d, their_d, trade_d))
for k, v in rate.items(): print(k, v if isinstance(v, float) else {a: round(b, 3) for a, b in v.items()})
def ev_net(rt): return rt['own'] * own_d - rt['their'] * their_d
d_jump = ev_net(rate[('A', 'down')]) - ev_net(rate[('A', 'jump')])
d_down = ev_net(rate[('B', 'down')]) - ev_net(rate[('A', 'down')])
d_trade = (rate[('A', 'trade_rate')] - rate[('B', 'trade_rate')]) * trade_d  # per hit, fewer trades
print('per case: take-off -> stay down %+.2f HP; A down -> B down %+.2f HP; trade rate A->B per hit %+.2f HP' % (d_jump, d_down, d_trade))
pairs = [v for v in P.values() if 'A' in v and 'B' in v]
n = len(pairs)
for name, fn in (('take-offs stay down', lambda a: a['cls']['jump'] * d_jump),
                 ('down at B rates', lambda a: a['cls']['down'] * d_down),
                 ('trades at B rate', lambda a: sum(a['cls'].values()) * d_trade),
                 ('all three', lambda a: a['cls']['jump'] * d_jump + a['cls']['down'] * d_down + sum(a['cls'].values()) * d_trade)):
    deltas = [fn(v['A']) for v in pairs]
    flips = sum(1 for v, d in zip(pairs, deltas) if v['A']['res'] != 1 and v['A']['net'] < 0 and v['A']['net'] + d >= 0)
    flipsB = sum(1 for v, d in zip(pairs, deltas) if v['A']['res'] != 1 and v['B']['res'] == 1 and v['A']['net'] < 0 and v['A']['net'] + d >= 0)
    print('%-22s mean %+.2f HP a fight; A rounds that would flip (net<0 -> >=0): %d of %d fights (%d where only B won); per 44 pairs %.2f' % (name, sum(deltas) / n, flips, n, flipsB, 44 * flips / n))
print('hits per fight: A %.2f B %.2f' % (sum(sum(v['A']['cls'].values()) for v in pairs) / n, sum(sum(v['B']['cls'].values()) for v in pairs) / n))
