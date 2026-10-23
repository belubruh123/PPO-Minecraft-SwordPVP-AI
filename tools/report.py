"""What the user judges it by, per opponent: python report.py LOG FROM TO
Damage dealt and taken a minute, share of the damage that was its own, rounds won, crits dealt and taken a minute,
combo hits, and the crit-back jumps."""
import re
import sys

import agg2

log, start, end = sys.argv[1], sys.argv[2], sys.argv[3]
NAMES = [('OLD014', 'the 0.14.0 model'), ('own', 'own copy'), ('TIMER', 'timer (friend)'), ('SPACER', 'spacer (crit, S)'),
         ('STANDER', 'stander (3 blocks)'), ('WHIFFER', 'whiffer'), ('JUMPER', 'jump-crit chaser'), ('PUNISHER', 'punisher'),
         ('CIRCLER', 'circler'), ('NOKB', 'no-knockback'), ('trader', 'trader'), ('BACKER', 'backer'), ('RUNNER', 'runner'),
         ('ODD', 'odd'), ('CORNER', 'corner'), ('older', 'easy earlier bots'), ('combo', 'combo drills'), ('REPLAY', 'replays of lost fights')]
BACK = re.compile(r'crit-back jumps (\d+) \(crit (\d+)\)')
tot, back, n = {}, [0, 0], 0
for line in open(log, encoding='utf-8', errors='replace'):
    t = line[1:9]
    if not (start <= t <= end) or 'fights since the last update' not in line:
        continue
    p = agg2.parse_line(line.rstrip('\n'))
    if not p:
        continue
    n += 1
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)
    body = ' | ' + line.split('steps in total): ', 1)[-1]
    for label, short in agg2.KINDS:
        k = re.search(r' \| ' + re.escape(label) + r' (.*?)(?= \| |$)', body)
        m = BACK.search(k.group(1)) if k else None
        if m:
            back[0] += int(m.group(1))
            back[1] += int(m.group(2))
all_st = sum(a.get('st', 0) for a in tot.values())
print('%s-%s, %d updates' % (start, end, n))
print('%-20s %6s %6s %6s %9s %6s %7s %7s %6s' % ('opponent', 'dealt', 'taken', 'dmg%', 'rounds', 'won%', 'crit%', 'critsIn', 'combo'))
for k, name in NAMES:
    a = tot.get(k)
    if not a or a.get('st', 0) < 600:
        continue
    m = a['st'] / 1200.0
    dd, dt = a.get('dd', 0) / m, a.get('dt', 0) / m
    rw, rl = a.get('rw', 0), a.get('rl', 0)
    print('%-20s %6.0f %6.0f %5.0f%% %4d-%-4d %5.0f%% %6.0f%% %6.1f/m %5.0f%%' % (
        name, dd, dt, 100 * dd / (dd + dt) if dd + dt else 0, rw, rl, 100.0 * rw / (rw + rl) if rw + rl else 0,
        100.0 * a['c'] / a['h'] if a.get('h') else 0, a.get('ct', 0) / m, 100.0 * (a.get('och', 0) - a.get('oc', 0)) / a['h'] if a.get('h') else 0))
print('dealt/taken: damage a minute; dmg%: its share of the damage; crit%: its hits that were crits; critsIn: crits it took a minute;'
      ' combo: its hits that were the 2nd or later of a combo of its own (no hit taken between)')
print('crit-back jumps %d, crits from them %d' % tuple(back))
LOST = re.compile(r'lost fights kept (\d+) \((.*)\), new (\d+), replayed (\d+) of which won (\d+), beaten for good (\d+) \((\d+) in all\)')
lt = [0, 0, 0, 0]
kept = None
for line in open(log, encoding='utf-8', errors='replace'):
    if start <= line[1:9] <= end and 'lost fights: ' in line:
        m = LOST.search(line)
        if m:
            kept = (m.group(1), m.group(2), m.group(7))
            for i, g in enumerate((3, 4, 5, 6)):
                lt[i] += int(m.group(g))
if kept:
    print('lost fights: %s kept now (%s); in this stretch %d new, %d replayed of which %d won, %d beaten for good (%s beaten in all)' % (
        kept[0], kept[1], lt[0], lt[1], lt[2], lt[3], kept[2]))
