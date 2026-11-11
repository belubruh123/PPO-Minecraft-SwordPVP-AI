import sys, json, re, collections
from load import segments, fights, HITS, JUMPS, CHECKLOG
H, checks = segments(HITS); HJ, jchecks = segments(JUMPS)
pj = json.load(open('pairs.json'))
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps', line)
    if m: steps.append((m.group(1), m.group(3)))
def f(r, k): return float(r[H[k]])
def i_(r, k): return int(r[H[k]])
K = int(sys.argv[1])
print('check        rounds A-B | A take-offs: all/its combo/their/neutral, hit rising | net/f all  its combo | B take-offs, hit rising, net/f')
for ci in range(K, 0, -1):
    t, st = steps[-ci]; F = fights(H, checks[-ci]); L = pj[st]; JT = {}
    for r in jchecks[-ci]: JT.setdefault((int(r[0]), r[1]), []).append(r)
    c = {s: collections.Counter() for s in 'AB'}; nf = {'A': 0, 'B': 0}
    for (p, s), rows in F.items():
        nf[s] += 1
        ev = sorted([(i_(r, 'tick'), f(r, 'dealt'), f(r, 'taken')) for r in rows])
        js = sorted(JT.get((p, s), []), key=lambda r: int(r[HJ['tick']]))
        for k, jr in enumerate(js):
            t0 = int(jr[HJ['tick']]); t1 = int(js[k + 1][HJ['tick']]) if k + 1 < len(js) else 10 ** 6
            lo = max([e[0] for e in ev if e[1] > 0 and e[0] <= t0] or [-999]); lt = max([e[0] for e in ev if e[2] > 0 and e[0] <= t0] or [-999])
            ctx = 'its' if t0 - lo <= 20 and lo > lt else 'their' if t0 - lt <= 20 and lt > lo else 'neu'
            first = next((e for e in ev if t0 < e[0] <= t0 + 20 and (e[1] > 0 or e[2] > 0)), None)
            d = sum(e[1] - e[2] for e in ev if first and t0 < e[0] <= min(first[0] + 20, t1))
            c[s]['n'] += 1; c[s][ctx] += 1; c[s]['rise'] += int(jr[HJ['hitRisingAt']]) > 0; c[s]['net'] += d; c[s]['net' + ctx] += d
    wa = sum(1 for k, v in L.items() if k.endswith('A') and v[0] == 1); wb = sum(1 for k, v in L.items() if k.endswith('B') and v[0] == 1)
    a, b = c['A'], c['B']
    print('%-12s %2d-%2d     | %4d %4d %3d %3d  %3d | %+5.1f %+5.1f | %3d %3d %+5.1f' % (t, wa, wb, a['n'], a['its'], a['their'], a['neu'], a['rise'], a['net'] / nf['A'], a['netits'] / nf['A'], b['n'], b['rise'], b['net'] / nf['B']))
