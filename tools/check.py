"""A short health and progress line for the running dev client: python check.py [updates] [log]"""
import os
import re
import sys
import time

import agg2

n = int(sys.argv[1]) if len(sys.argv) > 1 else 40
log = sys.argv[2] if len(sys.argv) > 2 else os.path.join(agg2.HERE, 'client.log')
parsed = []
bad = 0
last = ''
with open(log, encoding='utf-8', errors='replace') as f:
    for line in f:
        if 'fights since the last update' in line:
            p = agg2.parse_line(line.rstrip('\n'))
            if p:
                parsed.append(p)
        if re.search(r'ERROR\]|Exception in|Stopped because|Stopped \(', line):
            bad += 1
            last = line[:140].rstrip()
age = time.time() - os.path.getmtime(log)
chunk = parsed[-n:]
sums = {}
falls = [0] * 6
chase = [0] * 5
for p in chunk:
    for k, c in p['cells'].items():
        agg2.add(sums.setdefault(k, {}), c)
    falls = [x + y for x, y in zip(falls, p['falls'])]
    chase = [x + y for x, y in zip(chase, p['chase'])]
print('%s log age %ds, updates %d (last %s, %dk steps), stops/errors %d %s' % (
    time.strftime('%H:%M:%S'), age, len(parsed), chunk[-1]['time'] if chunk else '-', chunk[-1]['steps'] / 1000 if chunk else 0, bad, last))
near = sum(falls[:5])
print('last %d updates: falls in reach %d crit %.0f%% plain %.0f%% noaim %.0f%% nosword %.0f%% | chase %d crit %d hit %d noaim %d far %d' % (
    len(chunk), near, agg2.pct(falls[0], near), agg2.pct(falls[1], near), agg2.pct(falls[2], near), agg2.pct(falls[3], near),
    sum(chase), chase[0], chase[1], chase[3], chase[4]))
out = []
far = {}
early = 0
for k in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'older', 'combo'):
    a = sums.get(k)
    if not a or a['st'] <= 0:
        continue
    m = a['st'] / 1200.0
    early += a['early']
    out.append('%s %.0f/%.0f c%.0f%% sw%.0f%% up%.0f' % (k, a['h'] / m, a['t'] / m, agg2.pct(a['c'], a['h']), agg2.pct(a['sw'], a['h']), a.get('upc', 0)))
    if k not in ('older', 'combo'):
        agg2.add(far, {x: a.get(x, 0) for x in ('ft', 'fs', 'ff', 'fd')})
print(' | '.join(out))
ft = far.get('ft', 0)
print('far (rule kinds): ticks %d still %.0f%% flips/100t %.1f deg/tick %.2f | early swings %d' % (
    ft, agg2.pct(far.get('fs', 0), ft), agg2.pct(far.get('ff', 0), ft), far.get('fd', 0) / ft if ft else 0, early))
