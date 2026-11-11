"""Their first hit (row 1) on it in the air vs on the ground; rising/falling; whether its own take-off (jumps.csv) preceded it; and what follows."""
import sys, json, re, collections
from load import segments, fights, HITS, JUMPS, CHECKLOG
H, checks = segments(HITS)
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
G = 20
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
C = {s: collections.Counter() for s in 'AB'}
J = {s: collections.Counter() for s in 'AB'}
for ci in range(1, K + 1):
    F = fights(H, checks[-ci])
    JT = {}
    for r in jchecks[-ci]:
        JT.setdefault((int(r[0]), r[1]), []).append(r)
        s = r[1]; c = J[s]; c['jumps'] += 1
        hr, hf, oh = int(r[HJ['hitRisingAt']]), int(r[HJ['hitFallingAt']]), int(r[HJ['ownHitAt']])
        c['hit rising'] += hr > 0; c['hit falling'] += hf > 0; c['own hit'] += oh > 0; c['own crit'] += int(r[HJ['ownCrit']])
        c['nothing'] += hr == 0 and hf == 0 and oh == 0
    for (p, s), rows in F.items():
        ev = []
        for r in rows:
            tk = i_(r, 'tick')
            if f(r, 'dealt') > 0: ev.append(('it', tk, f(r, 'dealt'), r))
            if f(r, 'taken') > 0: ev.append(('them', tk, f(r, 'taken'), r))
        ev.sort(key=lambda e: (e[1], 0 if e[0] == 'them' else 1))
        js = [int(r[HJ['tick']]) for r in JT.get((p, s), [])]
        for k, e in enumerate(ev):
            if e[0] != 'them' or i_(e[3], 'theirRow') != 1: continue
            r = e[3]
            if i_(r, 'onGround'): g = 'ground'
            else:
                own = [j for j in js if 0 < e[1] - j <= 14]
                g = ('air falling' if i_(r, 'falling') else 'air rising') + (' own jump' if own else ' no logged take-off')
            nxt = next((x for x in ev[k + 1:] if x[1] > e[1]), None)
            res = 'none' if nxt is None or nxt[1] - e[1] > G else ('them' if nxt[0] == 'them' else 'crit' if i_(nxt[3], 'crit') else 'hit')
            c = C[s]; c['n'] += 1; c[g] += 1; c[(g, res)] += 1; c[(g, 'crittaken')] += i_(r, 'critsTaken') > 0
for s in 'AB':
    c = C[s]; n = c['n']
    print('%s: their first hits %d' % (s, n))
    for g in ['ground', 'air rising own jump', 'air rising no logged take-off', 'air falling own jump', 'air falling no logged take-off']:
        m = c[g]
        if not m: continue
        print('   %-30s %4d (%2.0f%%): next them %2.0f%% crit %2.0f%% hit %2.0f%% none %2.0f%%; their hit a crit %2.0f%%' % (g, m, 100 * m / n,
              *[100 * c[(g, x)] / m for x in ('them', 'crit', 'hit', 'none')], 100 * c[(g, 'crittaken')] / m))
    j = J[s]
    print('   its jumps %d: hit rising %d, hit falling %d, its own hit %d (crit %d), nothing %d' % (j['jumps'], j['hit rising'], j['hit falling'], j['own hit'], j['own crit'], j['nothing']))
