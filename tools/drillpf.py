"""Per-fight damage of the last paired drill window in a log (pair-windows.txt's last line): python drillpf.py client.log."""
import re
import statistics as st
import sys
from collections import Counter, defaultdict

win = open('pair-windows.txt', encoding='utf-8').read().split('\n')
win = [w for w in win if w.strip()][-1].split()
label, s0, e0 = win[0], win[1], win[2]
pairs = defaultdict(dict)
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    m = re.search(r'^\[(\d\d:\d\d:\d\d)\].*paired result (\d+) ([AB]): jump-in, dealt ([\d.]+), taken ([\d.]+), ticks (\d+)', line)
    if m and s0 <= m.group(1) <= e0:
        pairs[int(m.group(2))][m.group(3)] = (float(m.group(4)), float(m.group(5)), int(m.group(6)))
full = [p for p in pairs.values() if 'A' in p and 'B' in p]
n = len(full)
print('%s: %d pairs' % (label, n))
if n < 2:
    sys.exit()
for s in 'AB':
    print('  %s dealt %.2f taken %.2f net %+.2f a fight, %.1f ticks; most common (dealt, taken): %s' % (
        s, st.mean(p[s][0] for p in full), st.mean(p[s][1] for p in full), st.mean(p[s][0] - p[s][1] for p in full),
        st.mean(p[s][2] for p in full), Counter((p[s][0], p[s][1]) for p in full).most_common(5)))
diff = [(p['A'][0] - p['A'][1]) - (p['B'][0] - p['B'][1]) for p in full]
print('  A-B net %+.2f a fight (se %.2f)' % (st.mean(diff), st.stdev(diff) / n ** 0.5))
