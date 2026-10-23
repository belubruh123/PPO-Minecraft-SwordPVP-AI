"""fightfeat.py KIND: per fight, features of how it went, and their average in won vs lost fights (and the fight's setup)."""
import glob, gzip, os, re, sys, io, contextlib
from collections import defaultdict
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
kind = sys.argv[1]
rows = []
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*-%s-*.txt.gz' % kind)):
    with gzip.open(p, 'rt') as f: head = f.readline()
    m = re.search(r'knockback (\d+)%, its connection (\d+) ticks \(seen (\d+), it sees (\d+)\), crit chance ([\d.]+)', head)
    ts = parse(p)
    F = defaultdict(float)
    F['kb%'] = float(m.group(1)); F['ping'] = float(m.group(2)); F['seen'] = float(m.group(3)); F['critch'] = float(m.group(5))
    F['stander'] = 1.0 if 'stands' in head else 0.0
    mins = len(ts) / 1200.0
    for j in range(1, len(ts)):
        x, prev = ts[j], ts[j - 1]
        hev = x['ev'].split('TAKEN')[0]
        if 'HIT' in hev:
            F['hits'] += 1
            if 'CRIT' in hev: F['our crits'] += 1
            if prev['op'] < 0.3: F['hits on spent sword'] += 1
            elif prev['op'] >= 0.9 and not prev['opg']: F['hits on ready+air'] += 1
            elif prev['op'] >= 0.9: F['hits on ready+ground'] += 1
        if 'TAKEN' in x['ev']:
            F['taken'] += 1
            if 'TAKEN CRIT' in x['ev']: F['its crits'] += 1
        if 'whiff' in x['ev']: F['whiffs'] += 1
        if x['d'] < 2.5: F['ticks under 2.5'] += 1.0 / max(1, len(ts)) * 100
        if x['d'] > 3.6 and x['d'] < 90: F['ticks over 3.6'] += 1.0 / max(1, len(ts)) * 100
    name = os.path.basename(p)
    dealt, taken = float(name.split('-')[-2]), float(name.split('-')[-1].split('.')[0])
    F['net'] = dealt - taken
    for k in list(F):
        if k in ('hits', 'our crits', 'hits on spent sword', 'hits on ready+air', 'hits on ready+ground', 'taken', 'its crits', 'whiffs'):
            F[k] /= mins
    rows.append((name.split('-')[4] == 'W', F))
keys = sorted({k for _, F in rows for k in F})
W = [F for w, F in rows if w]; L = [F for w, F in rows if not w]
print('%d won, %d lost  (per-minute counts; ticks shares in %%)' % (len(W), len(L)))
for k in keys:
    aw = sum(F[k] for F in W) / max(1, len(W)); al = sum(F[k] for F in L) / max(1, len(L))
    print('  %-24s won %7.2f   lost %7.2f' % (k, aw, al))
