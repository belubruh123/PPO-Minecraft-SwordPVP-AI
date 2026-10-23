"""traces.py [DIR] [--since MMDD-HHMMSS] [--kind K] [--lost]: reads the fight traces (FightTrace, 22 Oct) and sorts every hit
taken by how it came, from the ticks before it, per kind of opponent:
  whiffed      - its own swing missed in the 14 ticks before (its sword down, the opponent's up)
  ran-in-down  - it was moving in (W) with its sword under 0.9 in the 6 ticks before, the opponent's sword ready
  ready-idle   - its sword was back and the opponent within 3.1 for 2+ ticks before, and it did not swing
  traded       - it landed a hit within 4 ticks either side
  hit-first-by - none of those: the opponent simply came in and hit first (its own sword was down: cooldown)
Also per fight: whiffs, swings, hits dealt/taken, crits each way, and the share of ticks the opponent was in the air."""
import glob
import gzip
import os
import re
import sys
from collections import Counter, defaultdict

args = sys.argv[1:]
d = 'D:/swordai/run/swordai/traces'
since = ''
kindf = None
lost_only = False
i = 0
while i < len(args):
    a = args[i]
    if a == '--since':
        since = args[i + 1]; i += 2; continue
    if a == '--kind':
        kindf = args[i + 1]; i += 2; continue
    if a == '--lost':
        lost_only = True; i += 1; continue
    d = a; i += 1

LINE = re.compile(r'^\s*(\d+) \| ([\d.]+|\s*-) \| ([\d.]+) ([\d.]+) \| ([Ga])([Ga]) \| ([-+][\d.]+) ([-+][\d.]+) \| (\S+) \| (.*?)\| (.*?) \| (.*?)\| (\d+)/(\d+)')


def parse(path):
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        lines = f.read().splitlines()
    ticks = []
    for l in lines[3:]:
        m = LINE.match(l)
        if not m:
            continue
        dist = m.group(2).strip()
        ticks.append(dict(t=int(m.group(1)), d=float(dist) if dist != '-' else 99.0, my=float(m.group(3)), op=float(m.group(4)),
                          meg=m.group(5) == 'G', opg=m.group(6) == 'G', up=float(m.group(7)), tw=float(m.group(8)), keys=m.group(9),
                          rules=m.group(10).strip(), tac=m.group(11).strip(), ev=m.group(12)))
    return lines[0], lines[1], ticks


cats = defaultdict(Counter)
dmg = defaultdict(Counter)
perkind = defaultdict(lambda: Counter())
files = sorted(glob.glob(os.path.join(d, '*.txt.gz')))
for p in files:
    name = os.path.basename(p)
    if since and name[:11] < since:
        continue
    parts = name.split('-')
    kind = parts[2]
    won = parts[4] == 'W'
    if kindf and kind != kindf:
        continue
    if lost_only and won:
        continue
    head, res, ts = parse(p)
    pk = perkind[kind]
    pk['fights'] += 1
    pk['lost'] += 0 if won else 1
    for j, x in enumerate(ts):
        ev = x['ev']
        if 'whiff' in ev:
            pk['whiffs'] += 1
        if 'X' in x['keys']:
            pk['swings'] += 1
        if not x['opg']:
            pk['opp air ticks'] += 1
        pk['ticks'] += 1
        if 'HIT' in ev:
            pk['hits dealt'] += 1
            if 'CRIT' in ev.split('TAKEN')[0]:
                pk['crits dealt'] += 1
        if 'TAKEN' in ev:
            crit = 'CRIT' in ev.split('TAKEN')[1]
            amount = float(re.search(r'TAKEN (?:CRIT )?([\d.]+)', ev).group(1))
            pk['hits taken'] += 1
            if crit:
                pk['crits taken'] += 1
            before = ts[max(0, j - 14):j]
            near = ts[max(0, j - 4):min(len(ts), j + 5)]
            if any('HIT' in y['ev'] for y in near):
                c = 'traded'
            elif any('whiff' in y['ev'] for y in before):
                c = 'whiffed'
            elif any(y['keys'][0] == 'W' and y['my'] < 0.9 and y['op'] >= 0.9 for y in ts[max(0, j - 6):j]):
                c = 'ran-in-down'
            elif sum(1 for y in ts[max(0, j - 4):j] if y['my'] >= 1.0 and y['d'] <= 3.1 and 'X' not in y['keys']) >= 2:
                c = 'ready-idle'
            else:
                c = 'hit-first-by'
            key = c + (' (crit)' if crit else '')
            cats[kind][key] += 1
            dmg[kind][key] += amount

for kind in sorted(perkind, key=lambda k: -perkind[k]['fights']):
    pk = perkind[kind]
    print('== %s: %d fights (%d lost), swings %d, whiffs %d (%.0f%%), hits dealt %d (crits %d), taken %d (crits %d), opponent in the air %.0f%% of ticks'
          % (kind, pk['fights'], pk['lost'], pk['swings'], pk['whiffs'], 100.0 * pk['whiffs'] / max(1, pk['swings']), pk['hits dealt'],
             pk['crits dealt'], pk['hits taken'], pk['crits taken'], 100.0 * pk['opp air ticks'] / max(1, pk['ticks'])))
    tot = sum(dmg[kind].values())
    for c, n in cats[kind].most_common():
        print('   %-22s %4d hits  %6.0f damage (%3.0f%% of taken)' % (c, n, dmg[kind][c], 100.0 * dmg[kind][c] / max(1, tot)))
