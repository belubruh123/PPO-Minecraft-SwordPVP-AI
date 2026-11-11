"""Own ground non-crit hit, no take-off within 10 ticks: hit-tick state and the next event's state, A vs B."""
import collections, sys, statistics as st
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 3
def g(r, k): return float(r[H[k]])
recs = []
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
            res = 'none' if nxt is None or nxt['t'] - e['t'] > 20 else ('own' if nxt['who'] == 'it' else 'their')
            recs.append(dict(s=s, check=i, bot=e['row'][2], e=e, n=nxt, res=res, dt=(nxt['t'] - e['t']) if nxt else 99))
def pct(a, b): return '%3.0f%%' % (100.0 * a / b) if b else '  - '
for s in 'AB':
    R = [r for r in recs if r['s'] == s]
    n = len(R)
    c = collections.Counter(r['res'] for r in R)
    print('== %s: %d ground hits staying down; own %s their %s none %s' % (s, n, pct(c['own'], n), pct(c['their'], n), pct(c['none'], n)))
    # hit-tick state
    rch = [g(r['e']['row'], 'reach') for r in R]
    oc = [g(r['e']['row'], 'oppCharge') for r in R]
    print('  hit tick: reach median %.2f, oppCharge>=0.9 %s, sprintHit %s, ruled FWD %s SPR %s JMP %s, myRow>=2 %s' % (
        st.median(rch), pct(sum(x >= 0.9 for x in oc), n), pct(sum(r['e']['sprint'] for r in R), n),
        pct(sum(int(r['e']['row'][H['ruled']]) & 4 > 0 for r in R), n), pct(sum(int(r['e']['row'][H['ruled']]) & 16 > 0 for r in R), n),
        pct(sum(int(r['e']['row'][H['ruled']]) & 32 > 0 for r in R), n), pct(sum(int(r['e']['row'][H['myRow']]) >= 2 for r in R), n)))
    # timing of their hit
    th = [r for r in R if r['res'] == 'their' and r['n']['row'] is not None]
    dts = collections.Counter(min(r['dt'] // 3 * 3, 18) for r in th)
    print('  their hit at tick (bins of 3):', ' '.join('%d-%d:%d' % (b, b + 2, dts[b]) for b in range(0, 21, 3)))
    ow = [r for r in R if r['res'] == 'own' and r['n']['row'] is not None]
    dto = collections.Counter(min(r['dt'] // 3 * 3, 18) for r in ow)
    print('  own next at tick (bins of 3):  ', ' '.join('%d-%d:%d' % (b, b + 2, dto[b]) for b in range(0, 21, 3)))
    # state at their hit
    if th:
        rows = [r['n']['row'] for r in th if r['n']['row'] is not None]
        m = len(rows)
        print('  AT THEIR HIT (n %d): my charge>=0.9 %s, <0.5 %s; reach med %.2f; my ground %s; keys W %s S %s none %s; sprintKey %s; jump %s; their sprint %s crit %s; ruled FWD %s; sinceSwing<dt %s' % (
            m, pct(sum(g(x, 'charge') >= 0.9 for x in rows), m), pct(sum(g(x, 'charge') < 0.5 for x in rows), m), st.median([g(x, 'reach') for x in rows]),
            pct(sum(int(x[H['onGround']]) for x in rows), m), pct(sum(int(x[H['forward']]) == 1 for x in rows), m), pct(sum(int(x[H['forward']]) == -1 for x in rows), m),
            pct(sum(int(x[H['forward']]) == 0 for x in rows), m), pct(sum(int(x[H['sprintKey']]) for x in rows), m), pct(sum(int(x[H['jumpKey']]) for x in rows), m),
            pct(sum(int(x[H['sprintsTaken']]) > 0 for x in rows), m), pct(sum(int(x[H['critsTaken']]) > 0 for x in rows), m),
            pct(sum(int(x[H['ruled']]) & 4 > 0 for x in rows), m),
            pct(sum(int(x[H['sinceSwing']]) < r['dt'] for x, r in zip(rows, th)), m)))
        # my charge at their hit vs dt
        print('  their hit dt<=8: %d, my charge>=0.9 there %s' % (sum(r['dt'] <= 8 for r in th), pct(sum(r['dt'] <= 8 and g(r['n']['row'], 'charge') >= 0.9 for r in th), sum(r['dt'] <= 8 for r in th))))
        print('  their hit dt>=9: %d, my charge>=0.9 there %s, swung since own hit %s, sinceSwing med %s' % (
            sum(r['dt'] >= 9 for r in th), pct(sum(r['dt'] >= 9 and g(r['n']['row'], 'charge') >= 0.9 for r in th), sum(r['dt'] >= 9 for r in th)),
            pct(sum(r['dt'] >= 9 and int(r['n']['row'][H['sinceSwing']]) < r['dt'] for r in th), sum(r['dt'] >= 9 for r in th)),
            st.median([int(r['n']['row'][H['sinceSwing']]) for r in th if r['dt'] >= 9] or [0])))
    if ow:
        rows = [r['n']['row'] for r in ow if r['n']['row'] is not None]
        m = len(rows)
        print('  AT OWN NEXT (n %d): reach med %.2f; sprintHit %s; crit %s; ground %s; W %s S %s; their charge>=0.9 %s' % (
            m, st.median([g(x, 'reach') for x in rows]), pct(sum(int(x[H['sprintHit']]) for x in rows), m), pct(sum(int(x[H['crit']]) for x in rows), m),
            pct(sum(int(x[H['onGround']]) for x in rows), m), pct(sum(int(x[H['forward']]) == 1 for x in rows), m), pct(sum(int(x[H['forward']]) == -1 for x in rows), m),
            pct(sum(g(x, 'oppCharge') >= 0.9 for x in rows), m)))
    # by bot
    bc = collections.defaultdict(collections.Counter)
    for r in R: bc[r['bot']][r['res']] += 1
    print('  by bot (n own/their):', '; '.join('%s %d %s/%s' % (b, sum(v.values()), pct(v['own'], sum(v.values())), pct(v['their'], sum(v.values()))) for b, v in sorted(bc.items())))
