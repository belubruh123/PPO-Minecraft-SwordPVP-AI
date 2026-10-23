"""The older requirements over a stretch of a log: python reqs2.py LOG FROM TO"""
import re
import sys

import agg2

log, start, end = sys.argv[1], sys.argv[2], sys.argv[3]
tot = {}
n = 0
for line in open(log, encoding='utf-8', errors='replace'):
    if 'fights since the last update' not in line:
        continue
    p = agg2.parse_line(line.rstrip('\n'))
    if not p or p['time'] < start or p['time'] > end:
        continue
    n += 1
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)
a = {}
for k, c in tot.items():
    agg2.add(a, c)
m = a['st'] / 1200.0
print('%d updates, %.0f fight-minutes: hits %d, sweeps %.1f%%, early %d, answered %.0f%%, out by the second hit %.0f%%, combos over %d of which sat through (3+ hits, no swing) %d = %.1f%%, resets %.1f a minute for %.1f hits taken' % (
    n, m, a['h'], agg2.pct(a['sw'], a['h']), a['early'], agg2.pct(a['ans'], a['toans']), 100 - agg2.pct(a['three'], a['two']),
    a['over'], a['sat'], agg2.pct(a['sat'], a['over']), a.get('rs', 0) / m, a['t'] / m))
