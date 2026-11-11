"""Per check: own ground non-crit hits; trade rate; no-trade take-off share; no-trade staying down their%/own%; A vs B; with a pooled z for staying down their%."""
import collections, sys, math
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 12
tot = {s: collections.Counter() for s in 'AB'}
for i in range(K, 0, -1):
    t, stp = steps[-i]; F = fights(H, checks[-i]); L = pj[stp]
    J = {}
    for r in jchecks[-i]:
        J.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    C = {s: collections.Counter() for s in 'AB'}
    for (p, s), rows in F.items():
        ev = events(rows, L['%d%s' % (p, s)]); js = J.get((p, s), [])
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            to = any(e['t'] < j <= e['t'] + 10 for j in js)
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = (nxt['t'] - e['t']) if nxt else 99
            res = 'none' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else 'their')
            c = C[s]; c['n'] += 1
            if res == 'their' and dt <= 5: c['trade'] += 1; continue
            g = 'jump' if to else 'down'
            c[g] += 1; c[g + res] += 1
    line = '%s' % t
    for s in 'AB':
        c = C[s]
        for k2, v in c.items(): tot[s][k2] += v
        line += ' | %s hits %3d trade %2.0f%% takeoff %2.0f%% down: own %2.0f%% their %2.0f%%' % (s, c['n'], 100 * c['trade'] / c['n'], 100 * c['jump'] / max(1, c['jump'] + c['down']),
                                                                                     100 * c['downown'] / max(1, c['down']), 100 * c['downtheir'] / max(1, c['down']))
    print(line)
for s in 'AB':
    c = tot[s]
    print('ALL %s hits %d trade %.1f%% takeoff %.1f%% | down n %d own %.1f%% their %.1f%% | jump n %d own %.1f%% their %.1f%%' % (s, c['n'], 100 * c['trade'] / c['n'], 100 * c['jump'] / (c['jump'] + c['down']),
          c['down'], 100 * c['downown'] / c['down'], 100 * c['downtheir'] / c['down'], c['jump'], 100 * c['jumpown'] / max(1, c['jump']), 100 * c['jumptheir'] / max(1, c['jump'])))
a, b = tot['A'], tot['B']
pa, pb = a['downtheir'] / a['down'], b['downtheir'] / b['down']
p = (a['downtheir'] + b['downtheir']) / (a['down'] + b['down'])
print('staying down their%% A-B %+.1f points, z %.2f' % (100 * (pa - pb), (pa - pb) / math.sqrt(p * (1 - p) * (1 / a['down'] + 1 / b['down']))))
pa, pb = a['trade'] / a['n'], b['trade'] / b['n']; p = (a['trade'] + b['trade']) / (a['n'] + b['n'])
print('trade A-B %+.1f points, z %.2f' % (100 * (pa - pb), (pa - pb) / math.sqrt(p * (1 - p) * (1 / a['n'] + 1 / b['n']))))
