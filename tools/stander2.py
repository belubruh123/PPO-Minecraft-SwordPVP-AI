"""stander2.py: the stander (spacer style 0) fights of the RULES check, counters on (cand) vs off (inst): how each of its
hits came about, from the ticks before it."""
import glob, gzip, io, contextlib, os
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
from collections import Counter, defaultdict
W = [l.split() for l in open('fp2-RULES-windows.txt')]
def who(name):
    t = name[5:7] + ':' + name[7:9] + ':' + name[9:11]
    for f, s, e in W:
        if s <= t <= e: return 'cand' if '-cand-' in f else 'inst'
    return None
G = defaultdict(Counter)
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/1022-09*.txt.gz') + glob.glob('D:/swordai/run/swordai/traces/1022-10*.txt.gz')):
    n = os.path.basename(p); w = who(n)
    if not w or '-spacer-' not in n: continue
    with gzip.open(p, 'rt') as f: head = f.readline()
    if 'stands' not in head: continue
    g = G[w]; g['fights'] += 1; g['won'] += '-W-' in n
    ts = parse(p)
    for j, x in enumerate(ts):
        h = x['ev'].split('TAKEN')[0]
        if 'HIT' in h:
            g['hits'] += 1; g['dealt'] += amt(x['ev'], 'HIT')
            if 'CRIT' in h: g['our crits'] += 1
        if 'TAKEN' in x['ev']:
            g['taken'] += amt(x['ev'], 'TAKEN'); g['its hits'] += 1
            pre = ts[max(0, j - 6):j]
            if 'HIT' in h or any('HIT' in y['ev'] for y in ts[max(0, j - 2):j]): c = 'trade (we hit within 2 ticks before/same)'
            elif any('HIT' in y['ev'] for y in ts[j + 1:j + 3]): c = 'it first, we answered within 2 ticks'
            elif pre and pre[-1]['my'] < 0.9: c = 'our sword not back'
            elif pre and not pre[-1]['meg']: c = 'we were in the air, sword back'
            elif any('X' in y['keys'] for y in pre[-2:]): c = 'we swung (missed/blocked) just before'
            else: c = 'we walked in, sword back, no swing yet'
            g['its hit: ' + c] += 1
for w in ('cand', 'inst'):
    g = G[w]; f = max(1, g['fights'])
    print('== %s: %d stander fights, %d won' % (w, g['fights'], g['won']))
    for k, v in sorted(g.items()):
        if k not in ('fights', 'won'): print('   %-55s %6.1f a fight' % (k, v / f))
