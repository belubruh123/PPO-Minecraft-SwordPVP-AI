"""Per check: after their hit (no own hit since), P(their next hit first within 20 ticks), P(its crit reply), P(its plain/sprint reply), by row; plus state at their hit."""
import sys, json, re, collections
from load import segments, fights, HITS, CHECKLOG
H, checks = segments(HITS)
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps', line)
    if m: steps.append((m.group(1), m.group(3)))
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
G = 20
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
tot = {s: collections.Counter() for s in 'AB'}
print('%-12s | %-38s | %-38s' % ('check', 'A: row1 next=them/crit/hit  row2+ them', 'B: same'))
for ci in range(K, 0, -1):
    t, st = steps[-ci]; F = fights(H, checks[-ci])
    C = {s: collections.Counter() for s in 'AB'}
    for (p, s), rows in F.items():
        ev = []
        for r in rows:
            tk = i_(r, 'tick')
            if f(r, 'dealt') > 0: ev.append(('it', tk, f(r, 'dealt'), r))
            if f(r, 'taken') > 0: ev.append(('them', tk, f(r, 'taken'), r))
        ev.sort(key=lambda e: (e[1], 0 if e[0] == 'them' else 1))
        for k, e in enumerate(ev):
            if e[0] != 'them': continue
            r = e[3]; g = 'r1' if i_(r, 'theirRow') == 1 else 'r2'
            nxt = next((x for x in ev[k + 1:] if x[1] > e[1]), None)
            res = 'none' if nxt is None or nxt[1] - e[1] > G else ('them' if nxt[0] == 'them' else 'crit' if i_(nxt[3], 'crit') else 'hit')
            for c in (C[s], tot[s]):
                c[g] += 1; c[(g, res)] += 1
                ready = f(r, 'charge') >= 0.95; near = f(r, 'reach') <= 3.0
                c[(g, 'ready')] += ready; c[(g, 'ready&near')] += ready and near
                c[(g, 'air')] += 1 - i_(r, 'onGround'); c[(g, 'sprinting')] += i_(r, 'sprinting')
    def fmt(c):
        return 'n%3d them %2.0f crit %2.0f hit %2.0f | n%3d them %2.0f' % (c['r1'], 100*c[('r1','them')]/c['r1'], 100*c[('r1','crit')]/c['r1'], 100*c[('r1','hit')]/c['r1'], c['r2'], 100*c[('r2','them')]/max(1,c['r2']))
    print('%-12s | %s | %s' % (t, fmt(C['A']), fmt(C['B'])))
for s in 'AB':
    c = tot[s]
    for g in ('r1', 'r2'):
        n = c[g]
        print(s, g, 'n', n, 'them next %.1f%% crit %.1f%% hit %.1f%% none %.1f%%' % tuple(100*c[(g,x)]/n for x in ('them','crit','hit','none')),
              '| at their hit: sword ready %.0f%%, ready&in reach %.0f%%, in air %.0f%%, sprinting %.0f%%' % tuple(100*c[(g,x)]/n for x in ('ready','ready&near','air','sprinting')))
