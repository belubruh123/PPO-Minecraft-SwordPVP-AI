"""spmode.py LOG FROM TO [LOG FROM TO ...]: sums the spacer section of the per-update lines in each window, to compare the
'good mode' and 'normal mode' spacer-only runs (21 Oct)."""
import re
import sys

NUM = r'(-?\d+(?:\.\d+)?)'
FIELDS = [
    ('hits', r'spacers \d+ steps: (\d+) hits'), ('crit%', r'spacers \d+ steps: \d+ hits \((\d+)% crit'),
    ('sprint%', r'spacers \d+ steps: \d+ hits \(\d+% crit, (\d+)% sprint'), ('taken', r'sprint\), (\d+) taken'),
    ('jumps/min', r'taken, (\d+) jumps/min'), ('jumpcrit%', r'jumps/min of which (\d+)% crit'),
    ('sweeps', r'sweeps (\d+)'), ('swings', r'swings (\d+), air'), ('air swings', r'air swings (\d+)'),
    ('answered', r'hits answered (\d+)/'), ('crits taken', r'crits taken (\d+)'), ('own combos', r'own combos (\d+) of'),
    ('dealt', r'damage (\d+) dealt'), ('dtaken', r'dealt / (\d+) taken'), ('won', r'rounds \d+ won (\d+)'),
    ('roundsW', r'rounds (\d+) won'), ('lost', r'won \d+ lost (\d+)|won (\d+) lost'),
    ('rule swings', r'swings made by the rules (\d+)'), ('kept for way down', r'swing was kept for the way down (\d+)'),
    ('falls crit', r'falls after a hit: crit (\d+)'), ('walked into ready', r'walked into reach on a ready sword (\d+)'),
    ('hit after own hit', r'hit after its own hit (\d+)'), ('rule jumps chase', r'rule jumps: chase (\d+)'),
    ('ran in on way up', r'ran in at an opponent on its way up on (\d+) ticks'), ('steps', r'spacers (\d+) steps'),
]


def window(log, a, b):
    tot = {}
    n = 0
    for line in open(log, encoding='utf-8', errors='replace'):
        if 'fights since the last update' not in line:
            continue
        t = line[1:9]
        if not (a <= t <= b):
            continue
        sec = line.split('| spacers ', 1)
        if len(sec) < 2:
            continue
        sp = 'spacers ' + sec[1].split(' | standers', 1)[0]
        rest = line
        n += 1
        for name, pat in FIELDS:
            src = sp if name not in ('rule swings', 'kept for way down', 'walked into ready', 'ran in on way up') else rest
            m = re.search(pat, src)
            if m:
                v = float(next(g for g in m.groups() if g is not None))
                tot.setdefault(name, []).append(v)
    return n, tot


args = sys.argv[1:]
cols = []
for i in range(0, len(args), 3):
    n, tot = window(args[i], args[i + 1], args[i + 2])
    cols.append((args[i + 1], n, tot))
names = [f[0] for f in FIELDS]
print('%-20s' % 'field' + ''.join('%14s' % c[0] for c in cols))
print('%-20s' % 'updates' + ''.join('%14d' % c[1] for c in cols))
for name in names:
    row = []
    for _, n, tot in cols:
        xs = tot.get(name, [])
        if not xs:
            row.append('%14s' % '-')
        elif name.endswith('%') or name == 'jumps/min':
            row.append('%14.1f' % (sum(xs) / len(xs)))
        else:
            row.append('%14.0f' % sum(xs))
    print('%-20s' % name + ''.join(row))
