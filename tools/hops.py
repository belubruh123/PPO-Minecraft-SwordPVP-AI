import re, sys
# hops.py LOG FROM: sums 'rule jumps: chase N (crit M)' and 'hops N' per fight kind from FROM on
log, start = sys.argv[1], sys.argv[2]
tot = {}
for line in open(log, encoding='utf-8', errors='replace'):
    m = re.match(r'\[(\d\d:\d\d:\d\d)\]', line)
    if not m or m.group(1) < start or 'fights since the last update' not in line:
        continue
    for seg in line.split(' | '):
        part = re.search(r'([a-z][a-z0-9 ]*?) (\d+) steps: .*?rule jumps: chase (\d+) \(crit (\d+)\).*?hit in the air (\d+).*?, hops (\d+)', seg)
        if not part:
            continue
        k = part.group(1).strip()
        t = tot.setdefault(k, [0, 0, 0, 0, 0])
        for i in range(5):
            t[i] += int(part.group(2 + i))
for k, (steps, chase, crit, air, hops) in sorted(tot.items()):
    mins = steps / 1200.0
    if mins > 0:
        print(f'{k:10s} {mins:5.1f} min  chase {chase/mins:4.1f}/min (crit {crit}), hops {hops/mins:4.1f}/min ({hops}), rule jumps hit in the air {air}')
