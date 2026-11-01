"""takeover.py [client log] [first HH:MM]: run 30's numbers from the client log (since the last redeploy).

1. The rules' keys: the smoothed cross-entropy to the rules' keys, the agreement, and the share of full fights played without
   the rules, update by update (every n-th), from the 'take-over:' segment of the update line.
2. The fights: damage a minute, net, knockouts of the full fights against the scripted bots played with the rules over the
   network (the overlay, as in the checks) and without them, and the same for the sparring fights against the installed model.
   Scenes, reviews and drills are not in either.
The question it answers: is the network without the rules as good as with them, and is the gap closing?"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else 'D:/swordai/run/school-client.log'
first = sys.argv[2] if len(sys.argv) > 2 else '00:00'

take = re.compile(r"\[(\d\d:\d\d):\d\d\].*school update (\d+), .*take-over: rules' keys ce ([\d.]+) \(smoothed\), agree (\d+)%; "
                  r"share of full fights without the rules (\d+)%")
fight = re.compile(r"\[(\d\d:\d\d):\d\d\].*school fight (.+?)(?: \(without the rules\))?: dealt ([\d.]+), taken ([\d.]+), ticks (\d+)(, knockout|, knocked out)?")
off_mark = ' (without the rules): dealt'

rows = []
groups = {}
for line in open(path, encoding='utf-8', errors='replace'):
    m = take.search(line)
    if m and m.group(1) >= first:
        rows.append(m.groups())
        continue
    if 'school fight ' not in line:
        continue
    m = fight.search(line)
    if not m or m.group(1) < first:
        continue
    name = m.group(2)
    spar = name.startswith('installed-model')
    off = off_mark in line
    key = ('sparring' if spar else 'bots', 'without the rules' if off else 'with the rules')
    g = groups.setdefault(key, [0, 0.0, 0.0, 0, 0, 0])
    g[0] += 1
    g[1] += float(m.group(3))
    g[2] += float(m.group(4))
    g[3] += int(m.group(5))
    g[4] += 1 if m.group(6) == ', knockout' else 0
    g[5] += 1 if m.group(6) == ', knocked out' else 0

print('updates with a take-over segment:', len(rows))
step = max(1, len(rows) // 12)
print('time   update  ce(smoothed)  agree  share-without-rules')
for r in rows[::step] + ([rows[-1]] if rows and (len(rows) - 1) % step else []):
    print('%s  %6s  %.3f         %3s%%   %3s%%' % (r[0], r[1], float(r[2]), r[3], r[4]))
print()
print('fights since %s                      n   dealt/min  taken/min   net/min   ko won-lost' % first)
for key in sorted(groups):
    n, dealt, taken, ticks, won, lost = groups[key]
    minutes = ticks / 1200.0
    print('%-9s %-18s %5d   %8.0f   %8.0f   %+7.0f   %d-%d' % (key[0], key[1], n, dealt / minutes, taken / minutes, (dealt - taken) / minutes, won, lost))
