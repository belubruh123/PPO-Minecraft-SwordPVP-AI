import re, sys
P = re.compile(r'\[(\d\d:\d\d:\d\d)\].*Stopped \(([^)]*)\)\. vs (\S+), (\d+)s in control of (\d+)s: hits (\d+) dealt / (\d+) taken, kills (\d+), deaths (\d+), swings (\d+) \((\d+)% ')
for log in sys.argv[1:]:
    seen = set(); secs = dealt = taken = swings = 0
    for line in open(log, encoding='utf-8', errors='replace'):
        if '[CHAT]' not in line:
            continue
        m = P.search(line)
        if not m or (m.group(1) + m.group(3)) in seen:
            continue
        seen.add(m.group(1) + m.group(3))
        secs += int(m.group(4)); dealt += int(m.group(6)); taken += int(m.group(7)); swings += int(m.group(10))
    mins = secs / 60.0
    print('%-40s %4.1f min: swings %.1f/min, landed %.1f/min, taken %.1f/min, landed per swing %.2f' % (log[-40:], mins, swings / mins, dealt / mins, taken / mins, dealt / max(1, swings)))
