"""Real fights from the profile's game log: python fights.py [log]"""
import re
import sys

log = sys.argv[1] if len(sys.argv) > 1 else 'C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/logs/latest.log'
P = re.compile(r'\[(\d\d:\d\d:\d\d)\].*Stopped \(([^)]*)\)\. vs (\S+), (\d+)s in control of (\d+)s: hits (\d+) dealt / (\d+) taken, kills (\d+), deaths (\d+), swings (\d+) \((\d+)% .*?on target (\d+)%.*?(?:ping: you (\S+) ms, opponent (\S+?)(?: ms)?; )?the swings that reached.*?: (\d+) before the sword was back, (\d+) critical, (\d+) with the sprint behind them.*?, (\d+) sweeps, (\d+) plain')
seen = set()
rows = []
for line in open(log, encoding='utf-8', errors='replace'):
    if '[CHAT]' not in line:
        continue
    m = P.search(line)
    if not m:
        continue
    key = m.group(1) + m.group(3)
    if key in seen:
        continue
    seen.add(key)
    rows.append(m.groups())
tot = [0] * 8
by = {}
for r in rows:
    t, why, name, ctl, dur, dealt, taken, kills, deaths, swings, onx, ontarget, pyou, popp, early, crit, sprint, sweep, plain = r
    print('%s %-14s %3ss  %2s dealt / %2s taken  k%s d%s  swings %2s (%3s%% on)  crit %2s sprint %2s sweep %s plain %s early %s  ping %s/%s' % (
        t, name, ctl, dealt, taken, kills, deaths, swings, onx, crit, sprint, sweep, plain, early, pyou, popp))
    b = by.setdefault(name, [0, 0, 0, 0, 0, 0])
    for i, v in enumerate((ctl, dealt, taken, kills, deaths)):
        b[i] += int(v)
    b[5] += 1
print()
for name, b in sorted(by.items(), key=lambda kv: kv[1][1] / max(1, kv[1][2])):
    print('%-14s %2d fights %4ds  %3d dealt / %3d taken (%.2f)  kills %d deaths %d' % (name, b[5], b[0], b[1], b[2], b[1] / max(1, b[2]), b[3], b[4]))
s = [sum(b[i] for b in by.values()) for i in range(6)]
print('ALL            %2d fights %4ds  %3d dealt / %3d taken (%.2f)  kills %d deaths %d' % (s[5], s[0], s[1], s[2], s[1] / max(1, s[2]), s[3], s[4]))
