"""Side by side, the end-of-day runs: python compare.py F1 F2 ...  (reads final-F*.txt written by evalfinal.sh)
Per opponent: damage dealt/taken a minute, damage share and rounds won; then the total over the hard kinds."""
import re
import sys

labels = sys.argv[1:]
ROW = re.compile(r'^(.{20}) +(\d+) +(\d+) +(\d+)% +(\d+)-(\d+) +(\d+)% +(\d+)% +([\d.]+)/m +(\d+)%')
KEEP = ['the 0.14.0 model', 'own copy', 'timer (friend)', 'spacer (crit, S)', 'stander (3 blocks)', 'whiffer',
        'jump-crit chaser', 'punisher', 'circler', 'no-knockback']
runs = {}
for lb in labels:
    rows = {}
    for line in open('final-%s.txt' % lb, encoding='utf-8'):
        m = ROW.match(line.rstrip('\n'))
        if m:
            rows[m.group(1).strip()] = [int(m.group(i)) for i in (2, 3, 4, 5, 6)] + [int(m.group(8)), float(m.group(9))]
    runs[lb] = rows
print('%-18s' % 'opponent' + ''.join('%22s' % lb for lb in labels))
for k in KEEP:
    cells = []
    for lb in labels:
        r = runs[lb].get(k)
        cells.append('%22s' % ('-' if not r else '%d/%d net %+d %d%%' % (r[0], r[1], r[0] - r[1], r[2])))
    print('%-18s' % k + ''.join(cells))
print('cells: damage dealt/taken a minute, net (dealt minus taken), damage share')
for lb in labels:
    hard = [runs[lb][k] for k in KEEP[2:] if k in runs[lb]]
    print('%s: net damage a minute, scripted hard kinds averaged %+.0f, 0.14.0 model %s' % (
        lb, sum(r[0] - r[1] for r in hard) / max(1, len(hard)), '-' if 'the 0.14.0 model' not in runs[lb] else '%+d' % (runs[lb]['the 0.14.0 model'][0] - runs[lb]['the 0.14.0 model'][1])))
for lb in labels:
    rw = sum(runs[lb][k][3] for k in KEEP[2:] if k in runs[lb])
    rl = sum(runs[lb][k][4] for k in KEEP[2:] if k in runs[lb])
    o = runs[lb].get('the 0.14.0 model')
    print('%s: scripted kinds rounds %d-%d (%.0f%%); 0.14.0 model %s' % (
        lb, rw, rl, 100.0 * rw / (rw + rl) if rw + rl else 0, '-' if not o else '%d-%d (%.0f%%), damage share %d%%' % (o[3], o[4], 100.0 * o[3] / max(1, o[3] + o[4]), o[2])))
