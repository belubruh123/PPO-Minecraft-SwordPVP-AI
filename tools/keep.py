import glob, gzip, io, contextlib, os
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
from collections import defaultdict, Counter
G = defaultdict(Counter)
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*.txt.gz')):
    kind = os.path.basename(p).split('-')[2]
    with gzip.open(p, 'rt') as f: head = f.readline()
    if kind == 'spacer': kind = 'stander' if 'stands' in head else 'spacer'
    ts = parse(p)
    j = 1
    while j < len(ts):
        x = ts[j]
        if 'keep' in x['rules'] and 'keep' not in ts[j - 1]['rules'] and not x['meg']:
            out = 'no hit'
            for y in ts[j + 1:j + 14]:
                h = y['ev'].split('TAKEN')[0]
                if 'TAKEN' in y['ev'] and 'HIT' not in h: out = 'hit by it first'; break
                if 'HIT' in h: out = 'our crit' if 'CRIT' in h else 'our plain hit'; break
            dband = 'd<2.2' if x['d'] < 2.2 else 'd<2.6' if x['d'] < 2.6 else 'd>=2.6'
            mv = 'it away' if x['tw'] < -0.05 else 'it in' if x['tw'] > 0.05 else 'it still'
            for key in [kind, kind + ' ' + dband, 'ALL ' + dband + ' ' + mv]:
                G[key][out] += 1; G[key]['n'] += 1
            j += 10; continue
        j += 1
for k in sorted(G):
    g = G[k]; n = g['n']
    if n >= 8: print('%-28s %4d keeps: our crit %3.0f%%  our plain %3.0f%%  no hit %3.0f%%  hit by it first %3.0f%%' % (k, n, 100.0*g['our crit']/n, 100.0*g['our plain hit']/n, 100.0*g['no hit']/n, 100.0*g['hit by it first']/n))
