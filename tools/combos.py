"""combos.py [N]: the combos it takes, from the newest N fight traces (run/swordai/traces): a run of hits taken, each
within GAP ticks of the one before, with no hit of its own in between. For combos of 2+ hits: how long they ran by
opponent kind, and what keys it held from the 2nd hit on (W/S, strafe A/D, jump), split by how many more hits came:
does strafing out of the line go with combos that end sooner? (The user, 23 Oct: it "don't know how to run and run to
left or right to make its combo hard to continue and go back in".)"""
import collections
import glob
import gzip
import os
import sys

GAP = 20
N = int(sys.argv[1]) if len(sys.argv) > 1 else 600
files = sorted(glob.glob(r'D:/swordai/run/swordai/traces/*.txt.gz'), key=os.path.getmtime)[-N:]


def parse(f):
    ticks = []
    for line in gzip.open(f, 'rt', encoding='utf-8', errors='replace'):
        p = line.split('|')
        if len(p) < 10 or not p[0].strip().isdigit():
            continue
        keys = p[5].strip()
        ev = p[8]
        ticks.append((int(p[0]), keys, 'TAKEN' in ev, 'HIT' in ev and 'TAKEN' not in ev.split('HIT')[0] if 'HIT' in ev else False, p[1].strip()))
    return ticks


lengths = collections.defaultdict(list)
after = {'ended': collections.Counter(), 'went on': collections.Counter()}
kinds_total = collections.Counter()
for f in files:
    kind = os.path.basename(f).split('-')[2]
    t = parse(f)
    i = 0
    combo = []
    for k, (tick, keys, taken, hit, dist) in enumerate(t):
        if hit:
            if len(combo) >= 2:
                lengths[kind].append(len(combo))
            combo = []
        if taken:
            if combo and tick - combo[-1] > GAP:
                if len(combo) >= 2:
                    lengths[kind].append(len(combo))
                combo = []
            combo.append(tick)
            if len(combo) >= 2:
                # Keys in the 12 ticks after this hit of the combo, and whether another hit of it followed.
                window = [x for x in t[k + 1:k + 13]]
                more = any(x[2] for x in t[k + 1:k + 1 + GAP]) and not any(x[3] for x in t[k + 1:k + 1 + GAP])
                c = after['went on' if more else 'ended']
                for x in window:
                    ks = x[1]
                    c['ticks'] += 1
                    c['W'] += ks[:1] == 'W'
                    c['S'] += ks[:1] == 'S'
                    c['strafe'] += len(ks) > 1 and ks[1] in 'AD'
                    c['jump'] += 'j' in ks or 'J' in ks
    if len(combo) >= 2:
        lengths[kind].append(len(combo))
    kinds_total[kind] += 1

print('%d traces; combos taken (2+ hits in a row, no hit back between):' % len(files))
for kind, ls in sorted(lengths.items(), key=lambda kv: -sum(kv[1])):
    n = kinds_total[kind]
    print('  %-12s %3d fights: %4d combos (%.1f a fight), avg length %.1f, 3+ hits %d, 5+ hits %d' % (
        kind, n, len(ls), len(ls) / n, sum(ls) / len(ls), sum(1 for x in ls if x >= 3), sum(1 for x in ls if x >= 5)))
for what, c in after.items():
    tk = max(1, c['ticks'])
    print('after a combo hit, when the combo %-8s: W %.0f%%  S %.0f%%  strafe A/D %.0f%%  jump %.0f%%  (%d ticks)' % (
        what, 100 * c['W'] / tk, 100 * c['S'] / tk, 100 * c['strafe'] / tk, 100 * c['jump'] / tk, c['ticks']))
