"""As groundnext_n.py, split by its W/S key on the hit tick (S, W, none) as well as take-off within 10 ticks."""
import collections
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
R = {s: collections.Counter() for s in 'AB'}
import sys
K = int(sys.argv[1]) if len(sys.argv) > 1 else 10
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
            res = 'nothing' if nxt is None or nxt['t'] - e['t'] > 20 else ('its next hit' if nxt['who'] == 'it' else 'their hit')
            kk = {1: "W", -1: "S"}.get(int(e["row"][H["forward"]]), "none"); g = ("jump" if jumped else "ground") + "+" + kk
            c = R[s]; c[g] += 1; c[(g, res)] += 1
            r = e['row']; c['n'] += 1
            c['S'] += int(r[H['forward']]) == -1; c['W'] += int(r[H['forward']]) == 1; c['sprintKey'] += int(r[H['sprintKey']]); c['sprintHit'] += e['sprint']
for s in 'AB':
    c = R[s]
    print('%s: its ground sprint/plain hits %d (sprint hits %.0f%%); keys on the hit tick: S %.0f%%, W %.0f%%, sprint key %.0f%%' % (s, c['n'], 100 * c['sprintHit'] / c['n'], 100 * c['S'] / c['n'], 100 * c['W'] / c['n'], 100 * c['sprintKey'] / c['n']))
    for g in [a + '+' + b for a in ['ground', 'jump'] for b in ['S', 'W', 'none']]:
        n = c[g]
        if n: print('   %-11s %4d (%2.0f%%): its next hit %2.0f%%, their hit %2.0f%%, nothing %2.0f%%' % (g, n, 100 * n / c['n'], 100 * c[(g, 'its next hit')] / n, 100 * c[(g, 'their hit')] / n, 100 * c[(g, 'nothing')] / n))
