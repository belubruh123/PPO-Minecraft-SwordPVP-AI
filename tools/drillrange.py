"""Jump-in drill traces by the bot's take-off distance (trace header ", jumps in from X"): python drillrange.py SINCE_HHMMSS.
Per band: fights, net per fight, let land + punished (crit), hit first, taken twice."""
import glob
import gzip
import os
import re
import sys
from collections import defaultdict

D = 'D:/swordai/run/swordai/traces'
since = sys.argv[1].replace(':', '') if len(sys.argv) > 1 else '000000'
bands = [(1.8, 2.6), (2.6, 3.5), (3.5, 4.5), (4.5, 5.7)]
st = defaultdict(lambda: defaultdict(float))
for path in glob.glob(os.path.join(D, '*-jump-in-*.txt.gz')):
    if os.path.basename(path).split('-')[1] < since:
        continue
    lines = gzip.open(path, 'rt', encoding='utf-8', errors='replace').read().split('\n')
    m = re.search(r'jumps in from ([\d.]+)', lines[0])
    r = re.search(r'dealt ([\d.]+), taken ([\d.]+)', lines[1])
    if not m or not r:
        continue
    f = float(m.group(1))
    band = next((b for b in bands if b[0] <= f < b[1]), None)
    if band is None:
        continue
    dealt, taken = float(r.group(1)), float(r.group(2))
    rows = [[x.strip() for x in l.split('|')] for l in lines[3:] if l.strip()]
    rows = [x for x in rows if len(x) >= 10 and x[0].isdigit()]
    first = next((x[8] for x in rows if x[8]), '')
    s = st[band]
    s['n'] += 1
    s['net'] += dealt - taken
    s['crit dealt'] += dealt >= 10
    s['taken first'] += 'TAKEN' in first or (not first and taken > 0)
    s['hit first'] += 'HIT' in first
    s['taken twice'] += taken >= 17 and dealt == 0
for band in bands:
    s = st[band]
    n = s['n']
    if not n:
        continue
    print('take-off %.1f-%.1f: %4d fights, net %+.2f a fight, crit dealt %.0f%%, crit/hit taken first %.0f%%, hit first %.0f%%, taken twice for nothing %.0f%%' % (
        band[0], band[1], n, s['net'] / n, 100 * s['crit dealt'] / n, 100 * s['taken first'] / n, 100 * s['hit first'] / n, 100 * s['taken twice'] / n))
