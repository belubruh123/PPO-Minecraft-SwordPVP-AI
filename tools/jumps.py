"""jumps.py [--kind K]: every jump the opponent makes at us with its sword (nearly) back, from within 4.2, and what we did
during its air time: hit it while it rose / while it fell (and whether that hit was a crit), backed out with S and did not
swing, or neither. Outcome: crit taken during the jump (to 2 ticks after it lands) and net damage to 6 ticks after."""
import glob, gzip, os, re, sys
from collections import defaultdict
from exch import parse, amt
kindf = sys.argv[sys.argv.index('--kind') + 1] if '--kind' in sys.argv else None
G = defaultdict(lambda: [0, 0, 0.0])
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*.txt.gz')):
    kind = os.path.basename(p).split('-')[2]
    if kindf and kind != kindf: continue
    ts = parse(p)
    j = 1
    while j < len(ts):
        a, b = ts[j - 1], ts[j]
        if a['opg'] and not b['opg'] and a['op'] >= 0.8 and a['d'] <= 4.2:
            k = j
            while k < len(ts) and not ts[k]['opg']: k += 1
            air = ts[j:k]
            end = min(len(ts), k + 3)
            resp = None
            for y in ts[j:end]:
                if 'HIT' in y['ev'].split('TAKEN')[0] and resp is None:
                    prev = ts[ts.index(y) - 1]
                    resp = ('hit it rising' if prev['up'] > 0.02 else 'hit it falling') + (' (our crit)' if 'CRIT' in y['ev'].split('TAKEN')[0] else '')
            if resp is None:
                s = sum(1 for y in air if y['keys'][0] == 'S')
                w = sum(1 for y in air if y['keys'][0] == 'W')
                resp = 'backed out (S %d+), no hit' % 3 if s >= 3 else 'stood/walked in, no hit'
            dt = 'from %.1f' % (min(4.2, round(a['d'] * 2) / 2))
            took = any('TAKEN CRIT' in y['ev'] for y in ts[j:end])
            net = sum(amt(y['ev'], 'HIT') - amt(y['ev'], 'TAKEN') for y in ts[j:min(len(ts), k + 7)])
            for key in [(kind, resp), (kind, dt, resp)]:
                g = G[key]; g[0] += 1; g[1] += took; g[2] += net
            j = k
        j += 1
for key in sorted(G):
    n, t, net = G[key]
    if n >= 5: print('%-62s %4d jumps  its crit lands %3.0f%%  net/jump %+5.1f' % (' | '.join(key), n, 100.0 * t / n, net / n))
