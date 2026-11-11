"""Its own take-offs hit while rising: at which air tick, by context; and the teacher's situation at that hit; per-check stability of 'their next first' after a rising own-jump hit vs ground."""
import sys, collections, re
from load import segments, fights, HITS, JUMPS, CHECKLOG
H, checks = segments(HITS)
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check', line)
    if m: steps.append(m.group(1))
C = {s: collections.Counter() for s in 'AB'}
for ci in range(1, K + 1):
    F = fights(H, checks[-ci])
    for r in jchecks[-ci]:
        p, s = int(r[0]), r[1]; t0 = int(r[HJ['tick']]); hr = int(r[HJ['hitRisingAt']])
        rows = F.get((p, s), [])
        lastown = max([i_(x, 'tick') for x in rows if f(x, 'dealt') > 0 and i_(x, 'tick') <= t0] or [-999])
        lasttheir = max([i_(x, 'tick') for x in rows if f(x, 'taken') > 0 and i_(x, 'tick') <= t0] or [-999])
        ctx = 'its combo' if t0 - lastown <= 20 and lastown > lasttheir else 'their combo' if t0 - lasttheir <= 20 and lasttheir > lastown else 'neutral'
        if hr:
            b = '1-2' if hr <= 2 else '3-4' if hr <= 4 else '5+'
            C[s][(ctx, b)] += 1; C[s][ctx] += 1
            hit = [x for x in rows if i_(x, 'tick') == t0 + hr and f(x, 'taken') > 0]
            if hit:
                C[s][(ctx, 'sit', hit[0][H['situation']])] += 1
                C[s][(ctx, 'crit')] += i_(hit[0], 'critsTaken') > 0
                C[s][(ctx, 'sprint')] += i_(hit[0], 'sprintsTaken') > 0
for s in 'AB':
    for ctx in ['neutral', 'its combo', 'their combo']:
        n = C[s][ctx]
        if not n: continue
        sits = sorted(((v, k[2]) for k, v in C[s].items() if isinstance(k, tuple) and len(k) == 3 and k[0] == ctx and k[1] == 'sit'), reverse=True)[:5]
        print('%s %-11s hit rising %4d: air tick 1-2 %2.0f%%, 3-4 %2.0f%%, 5+ %2.0f%%; their hit a crit %2.0f%%, sprint %2.0f%%; teacher said %s' % (s, ctx, n,
              *[100 * C[s][(ctx, b)] / n for b in ('1-2', '3-4', '5+')], 100 * C[s][(ctx, 'crit')] / n, 100 * C[s][(ctx, 'sprint')] / n, ', '.join('%s %d' % (k, v) for v, k in sits)))
