"""Own ground non-crit hits by the key on the hit tick: reach, row, themFalling/rising, sprint, label, trade rate. A vs B, last K checks."""
import collections, sys, statistics as st
from moments import events, steps, pj, H, checks
from load import fights
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
def g(r, k): return float(r[H[k]])
D = collections.defaultdict(list)
for i in range(1, K + 1):
    t, stp = steps[-i]; F = fights(H, checks[-i]); L = pj[stp]
    for (p, s), rows in F.items():
        ev = events(rows, L['%d%s' % (p, s)])
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            trade = nxt is not None and nxt['who'] == 'them' and nxt['t'] - e['t'] <= 5
            key = {1: 'W', -1: 'S', 0: 'none'}[int(e['row'][H['forward']])]
            D[(s, key)].append((e, trade))
for key in ('S', 'W', 'none'):
    for s in 'AB':
        L_ = D[(s, key)]; n = len(L_)
        if n < 10: continue
        r = [e['row'] for e, tr in L_]
        print('%s %-4s n %4d trade %2.0f%% | reach med %.2f | sprintKey %2.0f%% sprintHit %2.0f%% | row>=2 %2.0f%% | them falling %2.0f%% rising %2.0f%% | oppCharge>=0.9 %2.0f%% | strafe %2.0f%% | labels %s' % (
            s, key, n, 100 * sum(tr for e, tr in L_) / n, st.median([g(x, 'reach') for x in r]), 100 * sum(int(x[H['sprintKey']]) for x in r) / n,
            100 * sum(e['sprint'] for e, tr in L_) / n, 100 * sum(int(x[H['myRow']]) >= 2 for x in r) / n, 100 * sum(int(x[H['themFalling']]) for x in r) / n,
            100 * sum(int(x[H['themRising']]) for x in r) / n, 100 * sum(g(x, 'oppCharge') >= 0.9 for x in r) / n, 100 * sum(int(x[H['strafe']]) != 0 for x in r) / n,
            collections.Counter(x[H['situation']] for x in r).most_common(4)))
