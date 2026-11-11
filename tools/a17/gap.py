"""After each hit it TAKES with no own hit since (their combo), what comes next, A vs B, on the paired checks.
Read-only: hits.csv + pairs.json (outcomes)."""
import sys, json, re, collections
from load import segments, fights, HITS, CHECKLOG
H, checks = segments(HITS)
pj = json.load(open('pairs.json'))
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps', line)
    if m: steps.append((m.group(1), m.group(3)))
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
G = 20
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
R = {s: collections.Counter() for s in 'AB'}
D = {s: collections.defaultdict(float) for s in 'AB'}
for ci in range(1, K + 1):
    t, st = steps[-ci]; F = fights(H, checks[-ci])
    for (p, s), rows in F.items():
        ev = []
        for r in rows:
            tk = i_(r, 'tick')
            if f(r, 'dealt') > 0: ev.append(('it', tk, f(r, 'dealt'), r))
            if f(r, 'taken') > 0: ev.append(('them', tk, f(r, 'taken'), r))
        ev.sort(key=lambda e: (e[1], 0 if e[0] == 'them' else 1))
        for k, e in enumerate(ev):
            if e[0] != 'them': continue
            r = e[3]; row = i_(r, 'theirRow')  # hits taken in a row incl. this one
            g = 'row1' if row == 1 else 'row2' if row == 2 else 'row3+'
            c = R[s]; c[g] += 1
            nxt = next((x for x in ev[k + 1:] if x[1] > e[1]), None)
            if nxt is None or nxt[1] - e[1] > G:
                res = 'nothing'
            elif nxt[0] == 'it':
                rr = nxt[3]
                res = 'its B1' if i_(rr, 'punishCrit') else 'its crit' if i_(rr, 'crit') else 'its hit'
                D[s][(g, 'reply dmg')] += nxt[2]
            else:
                res = 'their next'
                gap = nxt[1] - e[1]
                ss = i_(nxt[3], 'sinceSwing')
                c[(g, 'their next', 'swung in gap' if ss < gap else 'no swing in gap')] += 1
                c[(g, 'their next', 'gap<=12' if gap <= 12 else 'gap13+')] += 1
                D[s][(g, 'their next dmg')] += nxt[2]
            c[(g, res)] += 1
            c[(g, 'ground')] += i_(r, 'onGround')
for g in ['row1', 'row2', 'row3+']:
    print('== their hit', g)
    for s in 'AB':
        c = R[s]; n = c[g]
        line = '%s n %4d: ' % (s, n)
        for res in ['its B1', 'its crit', 'its hit', 'their next', 'nothing']:
            line += '%s %4.1f%%  ' % (res, 100 * c[(g, res)] / max(1, n))
        tn = c[(g, 'their next')]
        line += '| their next: no swing in gap %d/%d, gap<=12 %d' % (c[(g, 'their next', 'no swing in gap')], tn, c[(g, 'their next', 'gap<=12')])
        line += ' | reply dmg %.0f, their next dmg %.0f, net/hit %+.2f' % (D[s][(g, 'reply dmg')], D[s][(g, 'their next dmg')], (D[s][(g, 'reply dmg')] - D[s][(g, 'their next dmg')]) / max(1, n))
        print(line)
