"""ALL own ground non-crit hits (both stay and take-off): outcome by dt bins; trade (their hit dt<=5) rate by reach at the hit tick, key, row."""
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
            to = min([j - e['t'] for j in js if e['t'] < j <= e['t'] + 10] or [99])
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = (nxt['t'] - e['t']) if nxt else 99
            res = 'none' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else 'their')
            recs.append(dict(s=s, e=e, n=nxt, res=res, dt=dt, to=to, bot=e['row'][2]))
def pct(a, b): return '%3.0f%%' % (100.0 * a / b) if b else '  - '
for s in 'AB':
    R = [r for r in recs if r['s'] == s]; n = len(R)
    tr = [r for r in R if r['res'] == 'their' and r['dt'] <= 5]
    nt = [r for r in R if not (r['res'] == 'their' and r['dt'] <= 5)]
    print('== %s: %d own ground non-crit hits; trade (their hit dt 1-5) %s' % (s, n, pct(len(tr), n)))
    c = collections.Counter(r['res'] for r in R)
    print('   overall own %s their %s none %s' % (pct(c['own'], n), pct(c['their'], n), pct(c['none'], n)))
    m = len(nt)
    for lab, sub in (('no trade, all', nt), ('no trade, takes off <=10', [r for r in nt if r['to'] <= 10]), ('no trade, stays down', [r for r in nt if r['to'] > 10])):
        cc = collections.Counter(r['res'] for r in sub); mm = len(sub)
        print('   %-26s n %4d (%s of no-trade): own %s their %s none %s' % (lab, mm, pct(mm, m), pct(cc['own'], mm), pct(cc['their'], mm), pct(cc['none'], mm)))
    # trade rate by reach at the hit tick
    bins = [(0, 2.0), (2.0, 2.3), (2.3, 2.6), (2.6, 2.85), (2.85, 9)]
    print('   trade rate by reach at hit:', '  '.join('%.2f-%.2f: n %d %s' % (a, b, len([r for r in R if a <= g(r['e']['row'], 'reach') < b]),
          pct(len([r for r in tr if a <= g(r['e']['row'], 'reach') < b]), len([r for r in R if a <= g(r['e']['row'], 'reach') < b]))) for a, b in bins))
    kk = lambda r: {1: 'W', -1: 'S', 0: 'none'}[int(r['e']['row'][H['forward']])]
    print('   trade rate by key on hit tick:', '  '.join('%s: n %d %s' % (k, len([r for r in R if kk(r) == k]), pct(len([r for r in tr if kk(r) == k]), len([r for r in R if kk(r) == k]))) for k in ('S', 'W', 'none')))
    row = lambda r: min(int(r['e']['row'][H['myRow']]), 3)
    print('   trade rate by its row:', '  '.join('%d: n %d %s' % (k, len([r for r in R if row(r) == k]), pct(len([r for r in tr if row(r) == k]), len([r for r in R if row(r) == k]))) for k in (1, 2, 3)))
    sp = lambda r: r['e']['sprint']
    print('   trade rate sprint hit / plain:', '  '.join('%s: n %d %s' % (k, len([r for r in R if sp(r) == v]), pct(len([r for r in tr if sp(r) == v]), len([r for r in R if sp(r) == v]))) for k, v in (('sprint', 1), ('plain', 0))))
    print('   reach at hit: median %.2f, mean %.2f, <2.3 %s, >=2.6 %s' % (st.median([g(r['e']['row'], 'reach') for r in R]), st.mean([g(r['e']['row'], 'reach') for r in R]),
          pct(len([r for r in R if g(r['e']['row'], 'reach') < 2.3]), n), pct(len([r for r in R if g(r['e']['row'], 'reach') >= 2.6]), n)))
    tro = [r for r in tr if r['n']['row'] is not None]
    if tro:
        x = [r['n']['row'] for r in tro]; mm = len(x)
        print('   AT THE TRADE HIT (their): reach med %.2f, their crit %s sprint %s, my ground %s, W %s S %s, sprintKey %s, jump %s, dmg mean %.2f vs my hit %.2f' % (
            st.median([g(y, 'reach') for y in x]), pct(sum(int(y[H['critsTaken']]) > 0 for y in x), mm), pct(sum(int(y[H['sprintsTaken']]) > 0 for y in x), mm),
            pct(sum(int(y[H['onGround']]) for y in x), mm), pct(sum(int(y[H['forward']]) == 1 for y in x), mm), pct(sum(int(y[H['forward']]) == -1 for y in x), mm),
            pct(sum(int(y[H['sprintKey']]) for y in x), mm), pct(sum(int(y[H['jumpKey']]) for y in x), mm), st.mean([g(y, 'taken') for y in x]), st.mean([r['e']['dmg'] for r in tro])))
        d = collections.Counter(r['dt'] for r in tro)
        print('   trade dt:', dict(sorted(d.items())))
