"""spacing.py LOG [MINUTES]: the spacing counters per block of MINUTES (default 60): walked into reach on a ready sword
vs on one coming back, and the distance kept in each case (the user's 'why it loses': it walks into a ready sword)."""
import re
import sys

log = sys.argv[1]
block = int(sys.argv[2]) if len(sys.argv) > 2 else 60
rows = {}
for line in open(log, encoding='utf-8', errors='replace'):
    if 'fights since the last update' not in line:
        continue
    t = line[1:9]
    h, m = int(t[0:2]), int(t[3:5])
    key = (h * 60 + m) // block
    r = rows.setdefault(key, [t, 0, 0, 0, 0, 0.0, 0, 0.0, 0, 0, 0])
    a = re.search(r'walked into reach on a ready sword (\d+), on one coming back (\d+) \(against the 0.14.0 model (\d+) / (\d+)\)', line)
    if a:
        r[1] += int(a.group(1)); r[2] += int(a.group(2)); r[3] += int(a.group(3)); r[4] += int(a.group(4))
    d = re.search(r"distance kept: opponent's sword back ([\d.]+) over (\d+) ticks, on its way back ([\d.]+) over (\d+) ticks", line)
    if d:
        r[5] += float(d.group(1)) * int(d.group(2)); r[6] += int(d.group(2))
        r[7] += float(d.group(3)) * int(d.group(4)); r[8] += int(d.group(4))
    r[9] += 1
print('from      updates  into ready  into cooldown  ratio   (vs 0.14.0)   dist ready  dist cooldown')
for k in sorted(rows):
    t, rd, cd, ro, co, ds, dn, cs, cn, n, _ = rows[k]
    print('%s  %5d  %9d  %12d  %5.1f   (%d / %d)    %8.2f  %10.2f' % (
        t, n, rd, cd, rd / max(1, cd), ro, co, ds / max(1, dn), cs / max(1, cn)))
