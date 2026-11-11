"""Its own take-offs (jumps.csv, not jump resets): context (ticks since its own last hit / their last hit), take-off distance, outcome."""
import sys, collections
from load import segments, fights, HITS, JUMPS
H, checks = segments(HITS)
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
C = {s: collections.Counter() for s in 'AB'}
for ci in range(1, K + 1):
    F = fights(H, checks[-ci])
    for r in jchecks[-ci]:
        p, s = int(r[0]), r[1]; t0 = int(r[HJ['tick']])
        rows = F.get((p, s), [])
        lastown = max([i_(x, 'tick') for x in rows if f(x, 'dealt') > 0 and i_(x, 'tick') <= t0] or [-999])
        lasttheir = max([i_(x, 'tick') for x in rows if f(x, 'taken') > 0 and i_(x, 'tick') <= t0] or [-999])
        if t0 - lastown <= 20 and lastown > lasttheir: ctx = 'after its own hit (its combo)'
        elif t0 - lasttheir <= 20 and lasttheir > lastown: ctx = 'in their combo'
        else: ctx = 'neutral'
        d = float(r[HJ['takeOff']]); dist = 'from >3.3' if d > 3.3 else 'in reach'
        hr, hf, oh, oc = int(r[HJ['hitRisingAt']]), int(r[HJ['hitFallingAt']]), int(r[HJ['ownHitAt']]), int(r[HJ['ownCrit']])
        res = 'hit rising' if hr else 'hit falling' if hf else ('own crit' if oc else 'own hit') if oh else 'nothing'
        c = C[s]; c['n'] += 1; c[ctx] += 1; c[(ctx, dist)] += 1; c[(ctx, dist, res)] += 1; c[(ctx, res)] += 1
for s in 'AB':
    c = C[s]; print('%s: %d take-offs (%.1f per check)' % (s, c['n'], c['n'] / K))
    for ctx in ['neutral', 'after its own hit (its combo)', 'in their combo']:
        for dist in ['from >3.3', 'in reach']:
            m = c[(ctx, dist)]
            if not m: continue
            print('   %-30s %-9s %4d: hit rising %2.0f%%, hit falling %2.0f%%, own crit %2.0f%%, own plain %2.0f%%, nothing %2.0f%%' % (ctx, dist, m,
                  *[100 * c[(ctx, dist, x)] / m for x in ('hit rising', 'hit falling', 'own crit', 'own hit', 'nothing')]))
