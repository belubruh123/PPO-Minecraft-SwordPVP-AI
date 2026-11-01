"""duel-hits.py [FROM_DATE] [TO_DATE]: how the hits of the user's live duels came, from the per-duel timelines the mod keeps
(profiles/DRL AI/swordai/fights/*.txt.gz: one line a tick with distance, both swords' charge, air or ground, the keys pressed, the rules
that pressed them, and the events DEALT, TAKEN:... and swing:...). Dates are yyyymmdd (file names); default all. Opponents' names
are never printed.

For each day it prints, per duel: hits dealt and taken; of the hits taken, the share that were trades (it had dealt a hit in the
4 ticks before or on the same tick), jumped in, critical, with its sword empty or ready; the keys it pressed in the 3 ticks before a
hit taken against the keys it pressed at all ticks (what is over-represented before a hit is what lets it through); the distance at the
hit; and the same for the hits it dealt, so that what it gets right can be read against what it gets wrong."""
import collections
import glob
import gzip
import os
import sys

base = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
lo = sys.argv[1] if len(sys.argv) > 1 else '00000000'
hi = sys.argv[2] if len(sys.argv) > 2 else '99999999'
HEAD = 'tick,dist,mySword,oppSword,myHp,oppHp,me,opp,oppUp,oppToward,oppSide,myToward,keys,rules,event'


class Day:
    def __init__(self):
        self.duels = 0
        self.ticks = 0
        self.dealt = 0
        self.taken = 0
        self.taken_trade = 0
        self.taken_jump = 0
        self.taken_crit = 0
        self.taken_empty = 0
        self.taken_ready = 0
        self.taken_air_me = 0
        self.taken_dist = []
        self.dealt_dist = []
        self.before_taken = collections.Counter()
        self.before_taken_n = 0
        self.before_dealt = collections.Counter()
        self.before_dealt_n = 0
        self.all_keys = collections.Counter()
        self.all_n = 0
        self.rules_taken = collections.Counter()
        self.opp_air_taken = 0


def key_flags(k):
    k = (k + '-----')[:5]
    return {'W': k[0] == 'W', 'S': k[0] == 'S', 'strafe': k[1] in 'AD', 'sprint': k[2] == 'r', 'jump': k[3] == 'j', 'attack': k[4] == 'x'}


days = collections.OrderedDict()
for f in sorted(glob.glob(base + '*.txt.gz')):
    stamp = os.path.basename(f)[:8]
    if not (lo <= stamp <= hi):
        continue
    d = days.setdefault(stamp, Day())
    rows = []
    started = False
    with gzip.open(f, 'rt', encoding='utf-8', errors='replace') as fh:
        for line in fh:
            line = line.rstrip('\n')
            if line == HEAD:
                started = True
                continue
            if not started:
                continue
            p = line.split(',')
            if len(p) < 15:
                continue
            try:
                rows.append((int(p[0]), float(p[1]), float(p[2]), float(p[3]), p[6], p[7], p[12], p[13], p[14]))
            except ValueError:
                continue
    if not rows:
        continue
    d.duels += 1
    d.ticks += len(rows)
    dealt_ticks = []
    flags = [key_flags(r[6]) for r in rows]
    for i, (tick, dist, mysw, oppsw, me, opp, keys, rules, ev) in enumerate(rows):
        fl = flags[i]
        for name in fl:
            if any(flags[j][name] for j in range(max(0, i - 3), i + 1)):
                d.all_keys[name] += 1
        d.all_n += 1
        if 'DEALT' in ev:
            d.dealt += 1
            d.dealt_dist.append(dist)
            dealt_ticks.append(tick)
            d.before_dealt_n += 1
            for name in fl:
                if any(flags[j][name] for j in range(max(0, i - 3), i + 1)):
                    d.before_dealt[name] += 1
        if 'TAKEN' in ev:
            d.taken += 1
            d.taken_dist.append(dist)
            if any(t >= tick - 4 for t in dealt_ticks):
                d.taken_trade += 1
            if 'jumped-in' in ev:
                d.taken_jump += 1
            if 'crit?' in ev:
                d.taken_crit += 1
            if 'my-sword-empty' in ev:
                d.taken_empty += 1
            if 'my-sword-ready' in ev:
                d.taken_ready += 1
            if me.startswith('a'):
                d.taken_air_me += 1
            if opp.startswith('a'):
                d.opp_air_taken += 1
            d.before_taken_n += 1
            for name in fl:
                if any(flags[j][name] for j in range(max(0, i - 3), i + 1)):
                    d.before_taken[name] += 1
            for ch in (rules or ''):
                d.rules_taken[ch] += 1

print('day        duels  dealt taken  | of the hits taken: trade jumped-in crit? empty ready  me-in-air opp-in-air | dist at hit taken / dealt')
for day, d in days.items():
    if not d.duels:
        continue
    n = max(1, d.taken)
    md = lambda a: sum(a) / len(a) if a else float('nan')
    print('%s  %4d  %5.1f %5.1f  |                    %4.0f%%  %6.0f%% %4.0f%% %4.0f%% %4.0f%%  %6.0f%% %8.0f%% | %.2f / %.2f' % (
        day, d.duels, d.dealt / d.duels, d.taken / d.duels, 100.0 * d.taken_trade / n, 100.0 * d.taken_jump / n, 100.0 * d.taken_crit / n,
        100.0 * d.taken_empty / n, 100.0 * d.taken_ready / n, 100.0 * d.taken_air_me / n, 100.0 * d.opp_air_taken / n, md(d.taken_dist),
        md(d.dealt_dist)))
print()
print('a key down at any of the 4 ticks up to a hit TAKEN, against the same window at every tick (share of ticks / of hits)')
names = ['W', 'S', 'strafe', 'sprint', 'jump', 'attack']
print('day        ' + '   '.join('%-14s' % n for n in names))
for day, d in days.items():
    if not d.duels:
        continue
    cells = []
    for nme in names:
        base_rate = 100.0 * d.all_keys[nme] / max(1, d.all_n)
        pre = 100.0 * d.before_taken[nme] / max(1, d.before_taken_n)
        cells.append('%3.0f%% / %3.0f%%    ' % (base_rate, pre))
    print('%s  %s' % (day, ' '.join(cells)))
print()
print('keys down in the 4 ticks up to a hit DEALT')
for day, d in days.items():
    if not d.duels:
        continue
    cells = []
    for nme in names:
        base_rate = 100.0 * d.all_keys[nme] / max(1, d.all_n)
        pre = 100.0 * d.before_dealt[nme] / max(1, d.before_dealt_n)
        cells.append('%3.0f%% / %3.0f%%    ' % (base_rate, pre))
    print('%s  %s' % (day, ' '.join(cells)))
