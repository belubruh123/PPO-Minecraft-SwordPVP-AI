"""pairsum.py LOG [START END]: a paired evaluation in one run (Config.pairedBase, 23 Oct): every "paired result N A|B: kind,
dealt D, taken T, ticks K" line of the client log (between START and END, HH:MM:SS), A and B matched by N (the same seed).
Per opponent kind and in all: damage a minute dealt/taken by each side, and the paired difference of net damage a minute
(A minus B) with its standard error over the pairs; a difference over two standard errors is called. Incomplete pairs
(the run stopped between the two fights) are left out."""
import collections
import math
import re
import sys

LINE = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*paired result (\d+) ([AB]): (.+?), dealt ([\d.]+), taken ([\d.]+), ticks (\d+)')
log = sys.argv[1]
start = sys.argv[2] if len(sys.argv) > 2 else '00:00:00'
end = sys.argv[3] if len(sys.argv) > 3 else '99:99:99'

fights = collections.defaultdict(dict)
for line in open(log, encoding='utf-8', errors='replace'):
    m = LINE.match(line)
    if not m or not (start <= m.group(1) <= end):
        continue
    n, side, kind = int(m.group(2)), m.group(3), m.group(4)
    dealt, taken, ticks = float(m.group(5)), float(m.group(6)), int(m.group(7))
    fights[n][side] = (kind, dealt, taken, max(1, ticks))

pairs = collections.defaultdict(list)
mismatched = 0
for n, f in sorted(fights.items()):
    if 'A' in f and 'B' in f:
        if f['A'][0] != f['B'][0]:
            # The same seed must give the same opponent: anything else is a fault in the pairing.
            mismatched += 1
            continue
        pairs[f['A'][0]].append((f['A'], f['B']))
        pairs['ALL'].append((f['A'], f['B']))


def per_min(x, ticks):
    return 1200.0 * x / ticks


def summary(rows):
    n = len(rows)
    a_d = sum(per_min(a[1], a[3]) for a, b in rows) / n
    a_t = sum(per_min(a[2], a[3]) for a, b in rows) / n
    b_d = sum(per_min(b[1], b[3]) for a, b in rows) / n
    b_t = sum(per_min(b[2], b[3]) for a, b in rows) / n
    diffs = [per_min(a[1] - a[2], a[3]) - per_min(b[1] - b[2], b[3]) for a, b in rows]
    mean = sum(diffs) / n
    se = math.sqrt(sum((d - mean) ** 2 for d in diffs) / (n - 1) / n) if n > 1 else float('inf')
    won_a = sum(1 for a, b in rows if a[1] > a[2])
    won_b = sum(1 for a, b in rows if b[1] > b[2])
    return n, a_d, a_t, b_d, b_t, mean, se, won_a, won_b


print('paired evaluation %s %s-%s: A = this run\'s network, B = the one compared with; damage a minute dealt/taken' % (log, start, end))
for kind in sorted(pairs, key=lambda k: (k == 'ALL', -len(pairs[k]))):
    rows = pairs[kind]
    if len(rows) < 2:
        continue
    n, a_d, a_t, b_d, b_t, mean, se, won_a, won_b = summary(rows)
    call = 'A better' if mean > 2 * se else 'B better' if mean < -2 * se else 'level'
    print('  %-20s %3d pairs | A %4.0f/%4.0f (won %2d) | B %4.0f/%4.0f (won %2d) | A-B net %+5.0f a minute (se %3.0f): %s' % (
        kind, n, a_d, a_t, won_a, b_d, b_t, won_b, mean, se, call))
if mismatched:
    print('  WARNING: %d pairs met different opponents from the same seed (left out)' % mismatched)
