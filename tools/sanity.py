"""Is the training run healthy? python sanity.py LOG FROM TO
Flags: no updates, the self copy not fighting back, the 0.14.0 model not dealing damage, errors in the log."""
import re
import sys

import agg2

log, start, end = sys.argv[1], sys.argv[2], sys.argv[3]
tot = {}
n = 0
errors = 0
for line in open(log, encoding='utf-8', errors='replace'):
    t = line[1:9]
    if not (start <= t <= end):
        continue
    if ('Exception' in line or '/ERROR]' in line) and 'Realms' not in line:
        errors += 1
    if 'fights since the last update' not in line:
        continue
    p = agg2.parse_line(line.rstrip('\n'))
    if not p:
        continue
    n += 1
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)

problems = []
if n < 10:
    problems.append('only %d updates' % n)


def per_min(k, field):
    a = tot.get(k)
    if not a or a.get('st', 0) < 2400:
        return None
    return a.get(field, 0) / (a['st'] / 1200.0)


def rounds(k):
    a = tot.get(k) or {}
    return a.get('rw', 0), a.get('rl', 0)


w, l = rounds('own')
if w + l >= 12 and not 0.2 <= w / (w + l) <= 0.8:
    problems.append('own copy rounds %d-%d (should be near even)' % (w, l))
old_dt = per_min('OLD014', 'dt')
if old_dt is not None and old_dt < 200:
    problems.append('0.14.0 model deals only %.0f damage a minute' % old_dt)
own_dt = per_min('own', 'dt')
if own_dt is not None and own_dt < 200:
    problems.append('own copy deals only %.0f damage a minute' % own_dt)
if errors:
    problems.append('%d error lines' % errors)
print('SANITY %s-%s: %d updates; own %d-%d; 0.14.0 deals %s/min; %s' % (
    start, end, n, w, l, 'n/a' if old_dt is None else '%.0f' % old_dt,
    'OK' if not problems else 'PROBLEM: ' + '; '.join(problems)))
