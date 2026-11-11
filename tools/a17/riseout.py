"""Every hit it takes, by its state (ground / rising on own take-off / falling on own take-off / air other) and context (neutral, its combo, their combo):
what comes next within 20 ticks. Plus counts per fight."""
import sys, collections
from load import segments, fights, HITS, JUMPS
H, checks = segments(HITS)
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
G = 20
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
C = {s: collections.Counter() for s in 'AB'}; NF = {'A': 0, 'B': 0}
for ci in range(1, K + 1):
    F = fights(H, checks[-ci]); JT = {}
    for r in jchecks[-ci]:
        JT.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    for (p, s), rows in F.items():
        NF[s] += 1
        ev = []
        for r in rows:
            tk = i_(r, 'tick')
            if f(r, 'dealt') > 0: ev.append(('it', tk, f(r, 'dealt'), r))
            if f(r, 'taken') > 0: ev.append(('them', tk, f(r, 'taken'), r))
        ev.sort(key=lambda e: (e[1], 0 if e[0] == 'them' else 1))
        js = JT.get((p, s), [])
        lastown = lasttheir = -999
        for k, e in enumerate(ev):
            if e[0] == 'it': lastown = e[1]; continue
            r = e[3]; t = e[1]
            ctx = 'its combo' if t - lastown <= 20 and lastown > lasttheir else 'their combo' if t - lasttheir <= 20 and lasttheir > lastown else 'neutral'
            lasttheir = t
            if i_(r, 'onGround'): st = 'ground'
            else:
                own = any(0 < t - j <= 14 for j in js)
                st = ('falling' if i_(r, 'falling') else 'rising') + (' own jump' if own else ' other air')
            nxt = next((x for x in ev[k + 1:] if x[1] > t), None)
            res = 'none' if nxt is None or nxt[1] - t > G else ('them' if nxt[0] == 'them' else 'crit' if i_(nxt[3], 'crit') else 'hit')
            c = C[s]; c[(ctx, st)] += 1; c[(ctx, st, res)] += 1; c[ctx] += 1
for ctx in ['neutral', 'its combo', 'their combo']:
    print('== hits taken,', ctx)
    for st in ['ground', 'rising own jump', 'falling own jump', 'rising other air', 'falling other air']:
        line = '  %-18s' % st
        for s in 'AB':
            n = C[s][(ctx, st)]
            if n < 15: line += ' | %s n %4d %38s' % (s, n, ''); continue
            line += ' | %s n %4d (%.2f/f) them %2.0f%% crit %2.0f%% hit %2.0f%% none %2.0f%%' % (s, n, n / NF[s], *[100 * C[s][(ctx, st, x)] / n for x in ('them', 'crit', 'hit', 'none')])
        print(line)
