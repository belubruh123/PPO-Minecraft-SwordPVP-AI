"""Rounds flow of the swing-back bundle: per paired fight, the damage of (a) its first reply after their hit when a crit, (b) when a
plain/sprint hit, (c) their combo-continuation hits (2nd+ in a row) taken; and 3+ combos taken. Counterfactual: give A's fight B's
amount of the bundle (same seed) - does the round flip (net damage sign, as moments.py)?"""
import sys, json, re, collections, math
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
P = {}
for ci in range(1, K + 1):
    t, st = steps[-ci]; F = fights(H, checks[-ci]); L = pj[st]
    for (p, s), rows in F.items():
        ev = []
        for r in rows:
            tk = i_(r, 'tick')
            if f(r, 'dealt') > 0: ev.append(('it', tk, f(r, 'dealt'), r))
            if f(r, 'taken') > 0: ev.append(('them', tk, f(r, 'taken'), r))
        ev.sort(key=lambda e: (e[1], 0 if e[0] == 'them' else 1))
        m = collections.Counter()
        for k, e in enumerate(ev):
            if e[0] == 'them':
                row = i_(e[3], 'theirRow')
                if row >= 2: m['cont'] += e[2]
                if row == 3: m['n3'] += 1
                if row >= 2: m['n2'] += 1
                continue
            prev = ev[k - 1] if k else None
            if prev and prev[0] == 'them' and e[1] - prev[1] <= G:
                if i_(e[3], 'crit'): m['critrep'] += e[2]; m['ncrit'] += 1
                else: m['plainrep'] += e[2]; m['nplain'] += 1
        res, ld, lt, ticks, bot = L['%d%s' % (p, s)]
        m['net'] = ld - lt; m['won'] = 1 if res == 1 else 0; m['lost'] = 1 if res == -1 else 0
        P.setdefault((ci, p), {})[s] = m
pairs = [v for v in P.values() if 'A' in v and 'B' in v]
n = len(pairs)
print('paired fights', n, 'A won', sum(v['A']['won'] for v in pairs), 'B won', sum(v['B']['won'] for v in pairs))
for key in ['ncrit', 'critrep', 'nplain', 'plainrep', 'n2', 'n3', 'cont']:
    a = sum(v['A'][key] for v in pairs); b = sum(v['B'][key] for v in pairs)
    print('%-9s A %7.0f  B %7.0f  per fight A-B %+.2f' % (key, a, b, (a - b) / n))
def flips(name, fn):
    fa = fb = 0; diffs = []
    for v in pairs:
        a, b = fn(v['A']), fn(v['B']); diffs.append(a - b)
        if v['A']['won'] and not v['B']['won'] and v['A']['net'] - a + b < 0: fa += 1
        if v['B']['won'] and not v['A']['won'] and v['B']['net'] - b + a < 0: fb += 1
    mu = sum(diffs) / n; se = math.sqrt(sum((d - mu) ** 2 for d in diffs) / (n - 1) / n)
    print('%-38s net A-B per fight %+.2f (se %.2f); rounds A won only thanks to it %d, B won only thanks to it %d' % (name, mu, se, fa, fb))
flips('crit replies (B1 trade gain)', lambda m: m['critrep'])
flips('plain/sprint replies', lambda m: m['plainrep'])
flips('-their continuation hits', lambda m: -m['cont'])
flips('replies - continuation (bundle)', lambda m: m['critrep'] + m['plainrep'] - m['cont'])
# rounds by 3+ combos taken
for s in 'AB':
    c = collections.Counter()
    for v in pairs:
        m = v[s]; g = 'took 3+' if m['n3'] > 0 else 'no 3+'
        c[g] += 1; c[(g, 'won')] += m['won']
    print(s, ' '.join('%s: %d fights, won %.0f%%' % (g, c[g], 100 * c[(g, 'won')] / max(1, c[g])) for g in ('took 3+', 'no 3+')))
