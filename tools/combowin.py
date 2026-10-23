"""combowin.py LABEL: the combos it took in the runs of combopair.sh LABEL (fp2-LABEL-windows.txt: "file S E" per run),
from the fight traces (run/swordai/traces, named MMDD-HHMMSS-kind-...), rule on (cand) against rule off (inst).
A combo: hits taken each within GAP ticks of the one before, no hit of its own in between. "Went on": after a hit that
was already the 2nd+ of a combo, another hit taken within GAP ticks before any hit of its own - what the user wants
lower (23 Oct: "make its combo hard to continue"). Also damage dealt/taken, and the ticks the rule held A/D ('aside')."""
import collections
import datetime
import glob
import gzip
import os
import re
import sys

GAP = 20
label = sys.argv[1]
TR = r'D:/swordai/run/swordai/traces'
DMG = re.compile(r'(TAKEN|HIT)( CRIT| sprint)? ([\d.]+)')

windows = []
for line in open('fp2-%s-windows.txt' % label):
    f, s, e = line.split()
    who = 'rule on ' if '-cand-' in f else 'rule off'
    part = f.rsplit('-', 1)[1].split('.')[0]
    windows.append((who, part, s, e))

day = datetime.date.today()
traces = []
for p in glob.glob(TR + '/*.txt.gz'):
    b = os.path.basename(p)
    try:
        t = datetime.datetime.strptime('%d%s' % (day.year, b[:11]), '%Y%m%d-%H%M%S')
    except ValueError:
        continue
    traces.append((t, b.split('-')[2], p))


def at(hms):
    return datetime.datetime.combine(day, datetime.time(*map(int, hms.split(':'))))


def fight(p):
    hits = []  # (tick, 'T' or 'D', damage)
    aside = 0
    for line in gzip.open(p, 'rt', encoding='utf-8', errors='replace'):
        q = line.split('|')
        if len(q) < 10 or not q[0].strip().isdigit():
            continue
        tick = int(q[0])
        if 'aside' in q[6]:
            aside += 1
        for m in DMG.finditer(q[8]):
            hits.append((tick, 'T' if m.group(1) == 'TAKEN' else 'D', float(m.group(3))))
    return hits, aside


agg = collections.defaultdict(collections.Counter)
for who, part, s, e in windows:
    a, b = at(s), at(e) + datetime.timedelta(seconds=5)
    for t, kind, p in traces:
        if not (a <= t <= b):
            continue
        hits, aside = fight(p)
        for key in ((who, part), (who, 'all')):
            c = agg[key]
            c['fights'] += 1
            c['aside'] += aside
            c['dealt'] += sum(h[2] for h in hits if h[1] == 'D')
            c['taken'] += sum(h[2] for h in hits if h[1] == 'T')
            row = []
            for i, (tick, side, dmg) in enumerate(hits):
                if side == 'D':
                    row = []
                    continue
                if row and tick - row[-1] > GAP:
                    row = []
                row.append(tick)
                if len(row) >= 2:
                    c['combo hits'] += 1
                    c['combo dmg'] += dmg
                    nxt = next((h for h in hits[i + 1:] if h[0] > tick), None)
                    c['went on'] += nxt is not None and nxt[1] == 'T' and nxt[0] - tick <= GAP
                if len(row) == 3:
                    c['3+ combos'] += 1

print('combos taken, %s (rule on = comboStrafeRule, same network, same fight sets):' % label)
for part in ('sp', 'mix', 'old', 'all'):
    for who in ('rule on ', 'rule off'):
        c = agg.get((who, part))
        if not c:
            continue
        n = max(1, c['fights'])
        print('  %-3s %s: %3d fights | dealt %5.1f taken %5.1f a fight | combo hits (2nd+) %.2f a fight, %.1f dmg | '
              'combo went on after one %.0f%% | 3+ hit combos %.2f a fight | A/D held by the rule %.1f ticks a fight' % (
                  part, who, c['fights'], c['dealt'] / n, c['taken'] / n, c['combo hits'] / n, c['combo dmg'] / n,
                  100.0 * c['went on'] / max(1, c['combo hits']), c['3+ combos'] / n, c['aside'] / n))
