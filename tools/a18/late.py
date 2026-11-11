"""No trade, staying down: state at their late hit (dt 6-20) and at its own next hit, A vs B; plus the hit tick of those cases."""
import collections, sys, statistics as st
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
def g(r, k): return float(r[H[k]])
R = collections.defaultdict(list)
for i in range(1, K + 1):
    t, stp = steps[-i]; F = fights(H, checks[-i]); L = pj[stp]
    J = {}
    for r in jchecks[-i]:
        J.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    for (p, s), rows in F.items():
        ev = events(rows, L['%d%s' % (p, s)]); js = J.get((p, s), [])
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            if any(e['t'] < j <= e['t'] + 10 for j in js): continue
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = (nxt['t'] - e['t']) if nxt else 99
            if nxt is not None and dt <= 5 and nxt['who'] == 'them': continue
            res = 'none' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else 'their')
            R[(s, res)].append((e, nxt, dt))
def pct(a, b): return '%3.0f%%' % (100.0 * a / b) if b else '  - '
for res in ('their', 'own', 'none'):
    for s in 'AB':
        L_ = R[(s, res)]; n = len(L_)
        hit = [e['row'] for e, x, d in L_]
        print('%s %-5s n %4d | AT OWN HIT: reach med %.2f, W %s S %s none %s, sprintKey %s, myRow>=2 %s, themFalling %s, label %s' % (
            s, res, n, st.median([g(r, 'reach') for r in hit]), pct(sum(int(r[H['forward']]) == 1 for r in hit), n), pct(sum(int(r[H['forward']]) == -1 for r in hit), n),
            pct(sum(int(r[H['forward']]) == 0 for r in hit), n), pct(sum(int(r[H['sprintKey']]) for r in hit), n), pct(sum(int(r[H['myRow']]) >= 2 for r in hit), n),
            pct(sum(int(r[H['themFalling']]) for r in hit), n), collections.Counter(r[H['situation']] for r in hit).most_common(4)))
        if res != 'none':
            nx = [x['row'] for e, x, d in L_ if x['row'] is not None]; m = len(nx)
            print('      AT THE NEXT HIT: dt med %d, reach med %.2f (<=2.5 %s), my charge>=0.9 %s, W %s S %s, sprintKey %s, jump %s, ground %s, their sprint %s crit %s, ruled FWD %s, label %s' % (
                st.median([d for e, x, d in L_]), st.median([g(r, 'reach') for r in nx]), pct(sum(g(r, 'reach') <= 2.5 for r in nx), m), pct(sum(g(r, 'charge') >= 0.9 for r in nx), m),
                pct(sum(int(r[H['forward']]) == 1 for r in nx), m), pct(sum(int(r[H['forward']]) == -1 for r in nx), m), pct(sum(int(r[H['sprintKey']]) for r in nx), m),
                pct(sum(int(r[H['jumpKey']]) for r in nx), m), pct(sum(int(r[H['onGround']]) for r in nx), m), pct(sum(int(r[H['sprintsTaken']]) > 0 for r in nx), m),
                pct(sum(int(r[H['critsTaken']]) > 0 for r in nx), m), pct(sum(int(r[H['ruled']]) & 4 > 0 for r in nx), m), collections.Counter(r[H['situation']] for r in nx).most_common(4)))
