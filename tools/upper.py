"""Per kind of fight over a whole log: how often the uppercut is jumped for and lands. python upper.py [log]"""
import os
import sys

import agg2

log = sys.argv[1] if len(sys.argv) > 1 else os.path.join(agg2.HERE, 'client.log')
rows = [agg2.parse_line(l.rstrip('\n')) for l in open(log, encoding='utf-8', errors='replace') if 'fights since the last update' in l]
rows = [r for r in rows if r]
tot = {}
for p in rows:
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)
allk = {}
for k, a in tot.items():
    agg2.add(allk, a)
    m = a['st'] / 1200.0
    if m > 1.0:
        print('%-8s %5.1f min: upper jumps %.2f/min, uppercuts %.2f/min (%.0f%% of the jumps), rule jumps hit in the air %.2f/min, dealt/taken %.0f/%.0f' % (
            k, m, a['ruj'] / m, a['upc'] / m, agg2.pct(a['ruh'], a['ruj']), a['rhit'] / m, a['h'] / m, a['t'] / m))
m = allk['st'] / 1200.0
print('ALL      %5.1f min: upper jumps %.2f/min, uppercuts %.2f/min (%.0f%% of the jumps); %d uppercuts in all' % (
    m, allk['ruj'] / m, allk['upc'] / m, agg2.pct(allk['ruh'], allk['ruj']), allk['upc']))
