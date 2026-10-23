import glob, gzip, io, contextlib, os
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
from collections import defaultdict
G = defaultdict(lambda: [0, 0, 0, 0.0])
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*.txt.gz')):
    kind = os.path.basename(p).split('-')[2]
    with gzip.open(p, 'rt') as f: head = f.readline()
    if kind == 'spacer': kind = 'stander' if 'stands' in head else 'spacer'
    ts = parse(p)
    for j in range(1, len(ts) - 16):
        x = ts[j]
        if not (x['meg'] and x['keys'][3] == 'J' and 'jumpin:' in x['rules'] and j + 1 < len(ts) and not ts[j + 1]['meg']): continue
        k = x['rules'].split('jumpin:')[1].split()[0]
        k = {'1': 'chase', '5': 'hop', '6': 'counter', '3': 'upper', '2': 'face'}.get(k, k)
        sw = 'its sword ready' if x['op'] >= 0.9 else 'its sword spent'
        first = None; crit = False
        for y in ts[j + 1:j + 16]:
            h = y['ev'].split('TAKEN')[0]
            if 'TAKEN' in y['ev'] and 'HIT' not in h: first = 'it'; break
            if 'HIT' in h: first = 'we'; crit = 'CRIT' in h; break
        net = sum(amt(y['ev'], 'HIT') - amt(y['ev'], 'TAKEN') for y in ts[j:j + 15])
        for key in [(k, sw, 'ALL'), (k, sw, kind)]:
            g = G[key]; g[0] += 1; g[1] += first == 'we' and crit; g[2] += first == 'it'; g[3] += net
for k in sorted(G):
    n, c, it, net = G[k]
    if n >= 5: print('%-40s %4d jumps  our crit first %3.0f%%  it hit first %3.0f%%  net %+5.1f' % (' | '.join(k), n, 100.0 * c / n, 100.0 * it / n, net / n))
