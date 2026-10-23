"""ourjumps.py [stander|spacer-jumping|KIND]: our own jumps (J on the ground) with our sword back at an opponent on the ground:
by take-off distance and whether we were sprinting, whether our hit came as a crit, whether it hit us first, net over 15 ticks."""
import glob, gzip, sys, io, contextlib
from collections import defaultdict
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
sel = sys.argv[1]
G = defaultdict(lambda: [0, 0, 0, 0.0])
pat = '*-spacer-*' if sel in ('stander', 'spacer-jumping') else '*-%s-*' % sel
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/' + pat + '.txt.gz')):
    with gzip.open(p, 'rt') as f: head = f.readline()
    if sel == 'stander' and 'stands' not in head: continue
    if sel == 'spacer-jumping' and 'stands' in head: continue
    ts = parse(p)
    for j in range(1, len(ts) - 16):
        x = ts[j]
        if not (x['meg'] and x['keys'][3] == 'J' and x['my'] >= 0.9 and x['opg'] and 2.5 <= x['d'] <= 5.5): continue
        if not (j + 1 < len(ts) and not ts[j + 1]['meg']): continue
        run = 'sprint' if x['keys'][2] == 'r' else 'walk'
        rule = 'rule' if 'jumpin' in x['rules'] or 'jumpreset' in x['rules'] else 'net'
        first = None; crit = False
        for y in ts[j + 1:j + 16]:
            h = y['ev'].split('TAKEN')[0]
            if 'TAKEN' in y['ev'] and 'HIT' not in h: first = 'it'; break
            if 'HIT' in h: first = 'we'; crit = 'CRIT' in h; break
        net = sum(amt(y['ev'], 'HIT') - amt(y['ev'], 'TAKEN') for y in ts[j:j + 15])
        key = ('%.1f' % (int(x['d'] * 4) / 4.0), run)
        g = G[key]; g[0] += 1; g[1] += first == 'we' and crit; g[2] += first == 'it'; g[3] += net
        g = G[('all', run, rule)]; g[0] += 1; g[1] += first == 'we' and crit; g[2] += first == 'it'; g[3] += net
for k in sorted(G):
    n, c, it, net = G[k]
    if n >= 4: print('%-26s %4d jumps  our crit first %3.0f%%  it hit first %3.0f%%  net %+5.1f' % (' '.join(k), n, 100.0 * c / n, 100.0 * it / n, net / n))
