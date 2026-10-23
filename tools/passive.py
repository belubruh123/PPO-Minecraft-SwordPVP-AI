"""Passivity check: of the ticks with the opponent under the crosshair and the sword back, the share with no swing, per log
(training updates only): python passive.py LOG [LOG...]."""
import re
import sys

for path in sys.argv[1:]:
    t = n = k = 0
    rows = 0
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.search(r'opponent under the crosshair with the sword back on (\d+) ticks, no swing on (\d+) of them, (\d+) of those kept', line)
        if m:
            t += int(m.group(1)); n += int(m.group(2)); k += int(m.group(3)); rows += 1
    if t:
        print('%s: %d updates, %d ticks in reach with the sword back, no swing %.1f%% (kept for a crit %.1f%%)' % (path, rows, t, 100.0 * n / t, 100.0 * k / t))
