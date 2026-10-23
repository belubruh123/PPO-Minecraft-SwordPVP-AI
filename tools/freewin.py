"""freewin.py: windows where its sword is spent (<0.35, server) and ours is back (>=0.9), it within 4.5: did we land a hit before
its sword was back (>=0.9)? By kind; and when we did not, how far it was and whether we were moving in."""
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
    j = 0
    while j < len(ts):
        x = ts[j]
        if x['op'] < 0.35 and x['my'] >= 0.9 and x['d'] <= 4.5:
            k = j
            hit = False
            while k < len(ts) and ts[k]['op'] < 0.9:
                if 'HIT' in ts[k]['ev'].split('TAKEN')[0]: hit = True; break
                k += 1
            g = G[kind]; g['windows'] += 1
            if hit: g['used'] += 1
            else:
                g['missed'] += 1
                ds = [y['d'] for y in ts[j:k]]
                g['missed, it got within 3.0'] += min(ds) <= 3.0 if ds else 0
                g['missed, we pressed W most'] += sum(y['keys'][0] == 'W' for y in ts[j:k]) * 2 > len(ts[j:k])
                g['missed, start d<3.3'] += x['d'] < 3.3
                g['missed, we were in the air at start'] += not x['meg']
            j = max(k, j + 1)
            continue
        j += 1
for kind, g in sorted(G.items()):
    print('%-12s windows %4d  used %3.0f%%  | missed %d: %s' % (kind, g['windows'], 100.0 * g['used'] / g['windows'], g['missed'],
          ', '.join('%s %d' % (k.replace('missed, ', ''), v) for k, v in g.items() if k.startswith('missed,'))))
