"""exch.py [--kind K] [--since S]: every hit we land, and whether the opponent answered it (a hit taken within 9 ticks), sorted
by the state at our hit: the opponent's sword, the distance, whether it was in the air, whether our hit was a crit, and what we
pressed in the 4 ticks after (S = backed out, W = stayed in). For each group: hits, answered share, damage dealt vs taken in the
10 ticks from our hit. Shows which hits pay and which are traded away, per kind of opponent (22 Oct, the coach analysis)."""
import glob, gzip, os, re, sys
from collections import defaultdict
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

args = sys.argv[1:]
kindf = None; since = ''
i = 0
while i < len(args):
    if args[i] == '--kind': kindf = args[i + 1]; i += 2; continue
    if args[i] == '--since': since = args[i + 1]; i += 2; continue
    i += 1
LINE = re.compile(r'^\s*(\d+) \| ([\d.]+|\s*-) \| ([\d.]+) ([\d.]+) \| ([Ga])([Ga]) \| ([-+][\d.]+) ([-+][\d.]+) \| (\S+) \| (.*?)\| (.*?) \| (.*?)\| (\d+)/(\d+)')


def parse(path):
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        lines = f.read().splitlines()
    ts = []
    for l in lines[3:]:
        m = LINE.match(l)
        if not m: continue
        dd = m.group(2).strip()
        ts.append(dict(t=int(m.group(1)), d=float(dd) if dd != '-' else 99.0, my=float(m.group(3)), op=float(m.group(4)),
                       meg=m.group(5) == 'G', opg=m.group(6) == 'G', up=float(m.group(7)), tw=float(m.group(8)), keys=m.group(9),
                       rules=m.group(10).strip(), tac=m.group(11).strip(), ev=m.group(12)))
    return ts


def amt(ev, what):
    m = re.search(what + r' (?:CRIT |sprint )?([\d.]+)', ev)
    return float(m.group(1)) if m else 0.0


groups = defaultdict(lambda: [0, 0, 0.0, 0.0])
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*.txt.gz')):
    name = os.path.basename(p)
    if since and name[:11] < since: continue
    kind = name.split('-')[2]
    if kindf and kind != kindf: continue
    ts = parse(p)
    for j, x in enumerate(ts):
        if 'HIT' not in x['ev'] or j == 0: continue
        prev = ts[j - 1]  # the state the swing was made in (the event shows on the next line)
        hitev = x['ev'].split('TAKEN')[0]
        crit = 'CRIT' in hitev
        sprint = 'sprint' in hitev
        op = prev['op']
        opb = '<.3' if op < 0.3 else '<.6' if op < 0.6 else '<.9' if op < 0.9 else 'rdy'
        db = '<2.4' if prev['d'] < 2.4 else '<2.8' if prev['d'] < 2.8 else '3+'
        after = ts[j:j + 4]
        s = sum(1 for y in after if y['keys'][0] == 'S')
        mv = 'S' if s >= 2 else 'W'
        win = ts[j:j + 10]
        answered = any('TAKEN' in y['ev'] for y in ts[j:j + 9])
        dealt = sum(amt(y['ev'], 'HIT') for y in win)
        taken = sum(amt(y['ev'], 'TAKEN') for y in win)
        key = (kind, 'its sword ' + opb, 'it ' + ('ground' if prev['opg'] else 'air'), 'crit' if crit else 'sprint' if sprint else 'plain', 'after ' + mv)
        g = groups[key]
        g[0] += 1; g[1] += answered; g[2] += dealt; g[3] += taken
        for k2 in [(kind, 'its sword ' + opb), (kind, 'ALL')]:
            g = groups[k2]
            g[0] += 1; g[1] += answered; g[2] += dealt; g[3] += taken

for key in sorted(groups):
    n, a, d, t = groups[key]
    if n < 6: continue
    print('%-60s %4d hits  answered %3.0f%%  net/hit %+5.1f' % (' | '.join(key), n, 100.0 * a / n, (d - t) / n))
