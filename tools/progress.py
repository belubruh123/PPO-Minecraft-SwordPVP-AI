"""progress.py: 21 Oct evening progress table. Damage a minute dealt/taken per opponent in the frozen mix runs (same fights,
evalSeed 20261020): T (tactics model) 2016, GC 1851, G 1741, and the installed network averaged over all its paired mix runs.
Then the tactics model's choices per kind of fight from the training log, early vs late, and its per-tactic advantages."""
import glob
import re
import sys

ROW = re.compile(r'^(\S.{20})\s+(\d+)\s+(\d+)\s+\d+%\s+(\d+)-(\d+)')


def table(path):
    out = {}
    for line in open(path, encoding='utf-8', errors='replace'):
        m = ROW.match(line)
        if m and not line.startswith('opponent'):
            out[m.group(1).strip()] = (int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)))
    return out


cols = [('T 2016', ['hc-2016-mix.txt']), ('GC 1851', ['hc-1851-mix.txt']), ('G 1741', ['hc-1741-mix.txt']),
        ('installed', sorted(glob.glob('hc-*-inst-mix.txt')))]
data = []
for name, files in cols:
    agg = {}
    for f in files:
        for k, (d, t, w, l) in table(f).items():
            a = agg.setdefault(k, [0, 0, 0, 0, 0])
            a[0] += d
            a[1] += t
            a[2] += w
            a[3] += l
            a[4] += 1
    data.append((name, len(files), agg))
kinds = ['spacer (crit, S)', 'jump-crit chaser', 'punisher', 'no-knockback', 'timer (friend)', 'whiffer', 'circler',
         'the 0.14.0 model', 'own copy', 'stander (3 blocks)', 'corner', 'backer', 'odd', 'easy earlier bots']
print('mix runs, damage a minute dealt/taken (net), rounds won')
print('%-18s' % 'opponent' + ''.join('%26s' % ('%s (%d run%s)' % (n, c, '' if c == 1 else 's')) for n, c, _ in data))
for k in kinds:
    row = '%-18s' % k[:18]
    for _, _, agg in data:
        if k in agg:
            d, t, w, l, n = agg[k]
            row += '%26s' % ('%d/%d (%+d) %d%%' % (d / n, t / n, (d - t) / n, round(100 * w / max(1, w + l))))
        else:
            row += '%26s' % '-'
    print(row)

log = sys.argv[1] if len(sys.argv) > 1 else 'client.log'
since = sys.argv[2] if len(sys.argv) > 2 else '19:35:00'
CH = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*tactics chosen: .*?\(per kind, in that order\)(.*)$')
TM = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*tactics model: \d+ choices: press \d+ \(([-+.\d]+)\) crit back \d+ \(([-+.\d]+)\) space \d+ \(([-+.\d]+)\) back off \d+ \(([-+.\d]+)\), entropy ([.\d]+)')
picks = {}
adv = {}
ents = {}
times = []
for line in open(log, encoding='utf-8', errors='replace'):
    m = CH.match(line)
    if m and m.group(1) >= since:
        times.append(m.group(1))
    m2 = TM.match(line)
    if m2 and m2.group(1) >= since:
        half = 'early' if m2.group(1) < '20:16:00' else 'late'
        for i, name in enumerate(['press', 'crit back', 'space', 'back off']):
            adv.setdefault(half, [[] for _ in range(4)])[i].append(float(m2.group(2 + i)))
        ents.setdefault(half, []).append(float(m2.group(6)))
    if m and m.group(1) >= since:
        half = 'early' if m.group(1) < '20:16:00' else 'late'
        for part in m.group(2).split(' | ')[1:]:
            mm = re.match(r'(.+?) (\d+): (\d+)% (\d+)% (\d+)% (\d+)%', part.strip())
            if not mm:
                continue
            k = mm.group(1)
            n = int(mm.group(2))
            p = picks.setdefault(k, {}).setdefault(half, [0.0, 0.0, 0.0, 0.0, 0])
            for i in range(4):
                p[i] += n * int(mm.group(3 + i)) / 100.0
            p[4] += n
print()
print('tactics chosen in training (press / crit back / space / back off), before 20:16 vs after:')
for k in sorted(picks, key=lambda x: -sum(v[4] for v in picks[x].values())):
    s = '%-14s' % k
    for half in ('early', 'late'):
        p = picks[k].get(half)
        s += '   %s: ' % half + ('-' if not p else '%3.0f%% %3.0f%% %3.0f%% %3.0f%% (%d)' % tuple([100 * p[i] / p[4] for i in range(4)] + [p[4]]))
    print(s)
print()
for half in ('early', 'late'):
    if half in adv:
        a = adv[half]
        print('%s: mean advantage per choice press %+.3f, crit back %+.3f, space %+.3f, back off %+.3f over %d updates; entropy %.3f'
              % (half, *[sum(x) / len(x) for x in a], len(a[0]), sum(ents[half]) / len(ents[half])))
