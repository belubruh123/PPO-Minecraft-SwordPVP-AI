"""lastchecks.py IN OUT K: the last K checks of hits.csv (a check starts where the pair number falls back) into OUT."""
import sys
src, dst, k = sys.argv[1], sys.argv[2], int(sys.argv[3])
lines = open(src, encoding='utf8').read().splitlines()
head, rows = lines[0], lines[1:]
starts, last = [], None
for i, r in enumerate(rows):
    p = int(r.split(',', 1)[0])
    if last is None or p < last - 5:
        starts.append(i)
    last = p
cut = starts[-k] if len(starts) >= k else 0
open(dst, 'w', encoding='utf8').write('\n'.join([head] + rows[cut:]) + '\n')
print('checks found', len(starts), 'kept rows', len(rows) - cut)
