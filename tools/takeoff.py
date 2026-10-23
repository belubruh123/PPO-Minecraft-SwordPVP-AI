import glob, os, sys, io, contextlib
from collections import Counter
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse
kind = sys.argv[1]
ops = Counter(); mys = Counter(); air = Counter(); n = 0; dists = Counter(); ticks_to_crit = Counter()
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*-%s-*.txt.gz' % kind)):
    ts = parse(p)
    for j in range(1, len(ts)):
        a, b = ts[j - 1], ts[j]
        if a['opg'] and not b['opg'] and a['d'] >= 3.1 and a['d'] < 4.5 and a['tw'] > -0.05:
            n += 1
            ops[round(a['op'], 1)] += 1; mys[round(a['my'], 1)] += 1; dists[round(a['d'], 1)] += 1
            for k in range(j, min(len(ts), j + 16)):
                if 'TAKEN' in ts[k]['ev']:
                    ticks_to_crit[k - j] += 1; break
                if ts[k]['opg'] and k > j + 2:
                    ticks_to_crit['landed'] += 1; break
print(n, 'take-offs from 3.1-4.5')
print('its sword at take-off', sorted(ops.items()))
print('our sword at take-off', sorted(mys.items()))
print('distance', sorted(dists.items()))
print('ticks from take-off to its hit', sorted(ticks_to_crit.items(), key=lambda x: str(x[0])))
