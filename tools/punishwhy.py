"""Punish episodes in traces SINCE..UNTIL: what hit started each (crit or plain), and whether a holdcrit label came in the 10 ticks before."""
import glob, gzip, os, sys
from collections import Counter
D = 'D:/swordai/run/swordai/traces'
since, until = sys.argv[1], sys.argv[2]
kinds = sys.argv[3].split(',') if len(sys.argv) > 3 else None
c = Counter()
for path in sorted(glob.glob(os.path.join(D, '*.txt.gz'))):
    b = os.path.basename(path).split('-')
    if not (since <= b[1] <= until):
        continue
    if kinds and b[2] not in kinds:
        continue
    rows = []
    for line in gzip.open(path, 'rt', encoding='utf-8', errors='replace'):
        p = [x.strip() for x in line.split('|')]
        if len(p) >= 10 and p[0].isdigit():
            rows.append(p)
    inp = False
    for i, r in enumerate(rows):
        on = 'p-' in r[6]
        if on and not inp:
            taken = next((rows[j][8] for j in range(i, max(-1, i - 25), -1) if 'TAKEN' in rows[j][8]), '')
            held = any('holdcrit' in rows[j][6] for j in range(max(0, i - 11), i + 1))
            crit = 'CRIT' in taken.split('TAKEN', 1)[1] if taken else False
            c[(b[2], 'crit' if crit else ('plain' if taken else 'no hit'), 'held' if held else 'not held')] += 1
        inp = on
for k, v in sorted(c.items()):
    print(k, v)
