"""No trade: take-off before the next event (any time up to it, within 20 ticks), by the take-off tick; outcome. A vs B."""
import collections, sys
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
C = {s: collections.Counter() for s in 'AB'}
TT = {s: collections.Counter() for s in 'AB'}
for i in range(1, K + 1):
    t, stp = steps[-i]; F = fights(H, checks[-i]); L = pj[stp]
    J = {}
    for r in jchecks[-i]:
        J.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    for (p, s), rows in F.items():
        ev = events(rows, L['%d%s' % (p, s)]); js = J.get((p, s), [])
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = (nxt['t'] - e['t']) if nxt else 99
            if nxt is not None and dt <= 5 and nxt['who'] == 'them': continue
            res = 'none' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else 'their')
            end = min(dt, 21)
            to = [j - e['t'] for j in js if e['t'] < j < e['t'] + end]
            g = 'stays down to the next event' if not to else ('took off at 1-5' if min(to) <= 5 else 'took off at 6-10' if min(to) <= 10 else 'took off at 11-20')
            C[s][g] += 1; C[s][(g, res)] += 1; C[s]['n'] += 1
            if to: TT[s][min(to)] += 1
for s in 'AB':
    c = C[s]
    print('%s: no-trade own ground hits %d' % (s, c['n']))
    for g in ('stays down to the next event', 'took off at 1-5', 'took off at 6-10', 'took off at 11-20'):
        n = c[g]
        if n: print('   %-30s %4d (%2.0f%%): own %2.0f%% their %2.0f%% none %2.0f%%' % (g, n, 100 * n / c['n'], 100 * c[(g, 'own')] / n, 100 * c[(g, 'their')] / n, 100 * c[(g, 'none')] / n))
    print('   take-off tick histogram:', dict(sorted(TT[s].items())))
