"""edge.py KIND: both swords back, both on the ground, the opponent 3.2-4.2 off (the moment before a spacer's jump). What we did
in the next 3 ticks (dash: W+sprint all 3; hold: no W; back: S 2+; jump: J) and what came of it in the next 15 ticks."""
import glob, os, sys, io, contextlib
from collections import defaultdict
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
kind = sys.argv[1]
G = defaultdict(lambda: [0, 0, 0.0, 0])
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*-%s-*.txt.gz' % kind)):
    ts = parse(p)
    j = 0
    while j < len(ts) - 16:
        x = ts[j]
        if x['my'] >= 0.95 and x['op'] >= 0.9 and x['meg'] and x['opg'] and 3.2 <= x['d'] <= 4.2:
            nxt = ts[j:j + 3]
            ks = [y['keys'] for y in nxt]
            if any(k[3] == 'J' for k in ks): r = 'jump'
            elif all(k[0] == 'W' and k[2] == 'r' for k in ks): r = 'dash (W+sprint)'
            elif sum(k[0] == 'S' for k in ks) >= 2: r = 'back (S)'
            elif all(k[0] != 'W' for k in ks): r = 'hold (no W)'
            else: r = 'walk/mixed'
            win = ts[j:j + 15]
            net = sum(amt(y['ev'], 'HIT') - amt(y['ev'], 'TAKEN') for y in win)
            crit = any('TAKEN CRIT' in y['ev'] for y in ts[j:j + 12])
            first = None
            for y in win:
                if 'HIT' in y['ev'] and 'TAKEN' not in y['ev']: first = 'we'; break
                if 'TAKEN' in y['ev'] and 'HIT' not in y['ev']: first = 'it'; break
                if 'HIT' in y['ev'] and 'TAKEN' in y['ev']: first = 'both'; break
            g = G[r]; g[0] += 1; g[1] += crit; g[2] += net; g[3] += first == 'we'
            j += 15
            continue
        j += 1
for r, (n, c, net, we) in sorted(G.items()):
    print('%-18s %4d  its crit within 12 ticks %3.0f%%  we hit first %3.0f%%  net over 15 ticks %+5.1f' % (r, n, 100.0 * c / n, 100.0 * we / n, net / n))
