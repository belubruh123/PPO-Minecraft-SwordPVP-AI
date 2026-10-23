import re, sys
log, start = sys.argv[1], sys.argv[2]
P = re.compile(r'opponent under the crosshair with the sword back on (\d+) ticks, no swing on (\d+) of them, (\d+) of those kept')
T = re.compile(r'^\[(\d\d:\d\d:\d\d)\]')
a = b = c = 0
for line in open(log, encoding='utf-8', errors='replace'):
    if 'fights since the last update' not in line:
        continue
    t = T.match(line)
    if not t or t.group(1) < start:
        continue
    m = P.search(line)
    if m:
        a += int(m.group(1)); b += int(m.group(2)); c += int(m.group(3))
print('ticks with the opponent under the crosshair and the sword back: %d, no swing on %d (%.0f%%), of those kept by the punish rule %d' % (a, b, 100.0 * b / max(1, a), c))
