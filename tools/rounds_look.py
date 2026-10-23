"""Reads recorded /swordai fight rounds: python rounds_look.py DIR [FILE...]
Per round: its swings by kind and charge, swings in the air, what came right after them, and what the
hits it took came after."""
import collections
import gzip
import os
import sys

d = sys.argv[1]
names = sys.argv[2:] or sorted(n for n in os.listdir(d) if n.endswith('.txt.gz'))
tot = collections.Counter()
for name in names:
    lines = gzip.open(os.path.join(d, name), 'rt', encoding='utf-8').read().splitlines()
    i = next(k for k, l in enumerate(lines) if l.startswith('tick,dist'))
    rows = [l.split(',') for l in lines[i + 1:] if l and l[0].isdigit()]
    c = collections.Counter()
    events = []
    for k, r in enumerate(rows):
        tick, dist, my, opp, myhp, opphp, me, oppst, oppup, toward, side, mytoward, keys, rules, ev = (r + [''] * 15)[:15]
        my = float(my)
        if 'x' in keys:
            c['swings'] += 1
            kind = ''
            for e in ev.split(' '):
                if e.startswith('swing:'):
                    kind = e[6:]
            c['kind ' + (kind or '?')] += 1
            if my < 0.9:
                c['swing below 0.9 charge'] += 1
            elif my < 0.96:
                c['swing 0.9-0.96 charge'] += 1
            if me.startswith('a'):
                c['swings in the air'] += 1
                c['air swing ' + (kind or '?')] += 1
                # Hit within the 12 ticks after a swing in the air?
                for j in range(k + 1, min(len(rows), k + 13)):
                    if 'TAKEN' in rows[j][14] if len(rows[j]) > 14 else False:
                        c['air swing, then hit within 12 ticks'] += 1
                        break
            if float(dist) > 3.0:
                c['swings from beyond 3 blocks'] += 1
            if 'a' in rules or 'k' in rules or 'm' in rules:
                c['swings by a rule (a/k/m)'] += 1
        if len(r) > 14 and 'TAKEN' in r[14]:
            c['taken'] += 1
            # Its own last swing: how long before, and was it in the air?
            for j in range(k, max(-1, k - 25), -1):
                if 'x' in rows[j][12]:
                    c['taken %s after its own swing' % ('<=12 ticks' if k - j <= 12 else '13-25 ticks')] += 1
                    if rows[j][6].startswith('a'):
                        c['taken after its own AIR swing'] += 1
                    break
            else:
                c['taken with no swing of its own in 25 ticks'] += 1
            if me.startswith('a'):
                c['taken while in the air'] += 1
        if 'jump' in keys or 'j' in keys[3:4]:
            pass
        if me.startswith('a') and k > 0 and rows[k - 1][6].startswith('g'):
            c['times off the ground'] += 1
            if 'j' in rows[k - 1][12]:
                c['jumps of its own'] += 1
                rr = rows[k - 1][13]
                c['own jumps by a rule (J/r)' if ('J' in rr or 'r' in rr) else 'own jumps by the network'] += 1
    print('==', name, len(rows), 'ticks')
    for key in sorted(c):
        print('   %-45s %d' % (key, c[key]))
    tot.update(c)
print('== all')
for key in sorted(tot):
    print('   %-45s %d' % (key, tot[key]))
