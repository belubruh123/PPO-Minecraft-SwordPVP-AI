"""The older requirements, from a whole log: python reqs.py [log]
weak hits (early swings), sweeps, hits answered, out of a combo by its second hit, combos sat through, slow against ordinary connection."""
import os
import re
import sys

import agg2

log = sys.argv[1] if len(sys.argv) > 1 else os.path.join(agg2.HERE, 'client.log')
PING = re.compile(r'(\d+) steps: (\d+) dealt \((\d+)% crit, (\d+)% sprint\), (\d+) taken')
tot = {}
slow = [0, 0, 0]
quick = [0, 0, 0]
n = 0
for line in open(log, encoding='utf-8', errors='replace'):
    if 'fights since the last update' not in line:
        continue
    p = agg2.parse_line(line.rstrip('\n'))
    if not p:
        continue
    n += 1
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)
    for label, into in (('slow connection', slow), ('ordinary connection', quick)):
        m = re.search(r' \| ' + label + r' (.*?)(?= \| |$)', line)
        if m:
            g = PING.search(m.group(1))
            if g:
                into[0] += int(g.group(1))
                into[1] += int(g.group(2))
                into[2] += int(g.group(5))
print('%d updates' % n)
allk = {}
for k in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'JUMPER', 'PUNISHER', 'BACKER', 'older', 'combo'):
    a = tot.get(k)
    if not a or a['st'] < 1200:
        continue
    agg2.add(allk, a)
    print('%-8s hits %5.0f sweeps %4.1f%% early %d | answered %3.0f%% of %4.0f hits taken | combos on it %4.0f, out by the second hit %3.0f%% | combos over %4.0f, without any swing back %3.0f (three hits or more: %2.0f)' % (
        k, a['h'], agg2.pct(a['sw'], a['h']), a['early'], agg2.pct(a['ans'], a['toans']), a['toans'], a['two'],
        100 - agg2.pct(a['three'], a['two']), a['over'], a['nosw'], a['sat']))
a = allk
print('ALL      hits %5.0f sweeps %4.1f%% early %d | answered %3.0f%% | out by the second hit %3.0f%% | combos over %4.0f, sat through (3+ hits, no swing) %2.0f = %.1f%%' % (
    a['h'], agg2.pct(a['sw'], a['h']), a['early'], agg2.pct(a['ans'], a['toans']), 100 - agg2.pct(a['three'], a['two']), a['over'], a['sat'], agg2.pct(a['sat'], a['over'])))
for name, v in (('slow connection', slow), ('ordinary connection', quick)):
    if v[0] > 0:
        m = v[0] / 1200.0
        print('%-20s %5.1f min: %.0f landed / %.0f taken a minute (%.2f)' % (name, m, v[1] / m, v[2] / m, v[1] / max(1, v[2])))
