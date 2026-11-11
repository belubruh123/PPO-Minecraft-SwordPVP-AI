"""groundkey.py's classes, with trades (their hit at 1-5) shown apart."""
import collections, sys
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 3
R = {s: collections.Counter() for s in 'AB'}
for i in range(1, K + 1):
    t, st = steps[-i]; F = fights(H, checks[-i]); L = pj[st]
    J = {}
    for r in jchecks[-i]:
        J.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    for (p, s), rows in F.items():
        ev = events(rows, L['%d%s' % (p, s)]); js = J.get((p, s), [])
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            jumped = any(e['t'] < j <= e['t'] + 10 for j in js)
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = nxt['t'] - e['t'] if nxt else 99
            res = 'nothing' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else ('trade' if dt <= 5 else 'their late'))
            kk = {1: "W", -1: "S"}.get(int(e["row"][H["forward"]]), "none"); g = ("jump" if jumped else "ground") + "+" + kk
            R[s][g] += 1; R[s][(g, res)] += 1
for s in 'AB':
    c = R[s]
    for g in [a + '+' + b for a in ['ground', 'jump'] for b in ['S', 'W', 'none']]:
        n = c[g]
        if n >= 10:
            nt = n - c[(g, 'trade')]
            print('%s %-11s %4d: trade %2.0f%% | their late %2.0f%% own %2.0f%% | WITHOUT trades (n %d): own %2.0f%% their %2.0f%%' % (s, g, n, 100 * c[(g, 'trade')] / n, 100 * c[(g, 'their late')] / n, 100 * c[(g, 'own')] / n,
                  nt, 100 * c[(g, 'own')] / max(1, nt), 100 * c[(g, 'their late')] / max(1, nt)))
