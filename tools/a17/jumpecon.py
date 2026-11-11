"""Take-off economy: per take-off, net damage from the take-off to 20 ticks after its first hit event (cut at the next take-off), by context
and by how the jump ended. Per fight sums, A vs B, and the flip counterfactual (A's fight with B's take-off net)."""
import sys, json, re, collections, math
from load import segments, fights, HITS, JUMPS, CHECKLOG
H, checks = segments(HITS)
HJ, jchecks = segments(JUMPS)
pj = json.load(open('pairs.json'))
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps', line)
    if m: steps.append((m.group(1), m.group(3)))
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
P = {}; S = {s: collections.Counter() for s in 'AB'}; N = {s: collections.Counter() for s in 'AB'}
for ci in range(1, K + 1):
    t, st = steps[-ci]; F = fights(H, checks[-ci]); L = pj[st]; JT = {}
    for r in jchecks[-ci]:
        JT.setdefault((int(r[0]), r[1]), []).append(r)
    for (p, s), rows in F.items():
        ev = sorted([(i_(r, 'tick'), f(r, 'dealt'), f(r, 'taken')) for r in rows])
        js = sorted(JT.get((p, s), []), key=lambda r: int(r[HJ['tick']]))
        net = collections.Counter()
        for k, jr in enumerate(js):
            t0 = int(jr[HJ['tick']]); t1 = int(js[k + 1][HJ['tick']]) if k + 1 < len(js) else 10 ** 6
            lastown = max([e[0] for e in ev if e[1] > 0 and e[0] <= t0] or [-999]); lasttheir = max([e[0] for e in ev if e[2] > 0 and e[0] <= t0] or [-999])
            ctx = 'its combo' if t0 - lastown <= 20 and lastown > lasttheir else 'their combo' if t0 - lasttheir <= 20 and lasttheir > lastown else 'neutral'
            hr, hf, oh = int(jr[HJ['hitRisingAt']]), int(jr[HJ['hitFallingAt']]), int(jr[HJ['ownHitAt']])
            how = 'hit rising' if hr else 'hit falling' if hf else 'own hit' if oh else 'nothing'
            first = next((e for e in ev if t0 < e[0] <= t0 + 20 and (e[1] > 0 or e[2] > 0)), None)
            d = 0.0
            if first:
                end = min(first[0] + 20, t1)
                d = sum(e[1] - e[2] for e in ev if t0 < e[0] <= end)
            net[ctx] += d; net['all'] += d
            S[s][(ctx, how)] += d; N[s][(ctx, how)] += 1
        res, ld, lt, ticks, bot = L['%d%s' % (p, s)]
        net['won'] = res == 1; net['fnet'] = ld - lt
        P.setdefault((ci, p), {})[s] = net
pairs = [v for v in P.values() if 'A' in v and 'B' in v]; n = len(pairs)
print('paired fights', n)
for s in 'AB':
    print(s)
    for ctx in ['neutral', 'its combo', 'their combo']:
        print('   %-11s' % ctx + ''.join('  %s %4d net %+5.1f/jump' % (how, N[s][(ctx, how)], S[s][(ctx, how)] / max(1, N[s][(ctx, how)])) for how in ('hit rising', 'hit falling', 'own hit', 'nothing')))
for key in ['all', 'neutral', 'its combo', 'their combo']:
    diffs = [v['A'][key] - v['B'][key] for v in pairs]; mu = sum(diffs) / n; se = math.sqrt(sum((x - mu) ** 2 for x in diffs) / (n - 1) / n)
    fa = sum(1 for v in pairs if v['A']['won'] and not v['B']['won'] and v['A']['fnet'] - v['A'][key] + v['B'][key] < 0)
    fb = sum(1 for v in pairs if v['B']['won'] and not v['A']['won'] and v['B']['fnet'] - v['B'][key] + v['A'][key] < 0)
    print('take-off windows %-11s A %+.2f/f, B %+.2f/f, A-B %+.2f (se %.2f); A won only thanks to it %d, B %d' % (key, sum(v['A'][key] for v in pairs) / n, sum(v['B'][key] for v in pairs) / n, mu, se, fa, fb))
