import re, sys
# timerstats.py LOG FROM: sums the 'timer:' part of the update lines
log, start = sys.argv[1], sys.argv[2]
pat = re.compile(r'timer: jumps for the sword (\d+) \(crits (\d+)\), jumps at it arriving (\d+) \(crits (\d+)\), hit out of either (\d+), punish crits (\d+)')
tot = [0] * 6
for line in open(log, encoding='utf-8', errors='replace'):
    m = re.match(r'\[(\d\d:\d\d:\d\d)\]', line)
    if not m or m.group(1) < start:
        continue
    p = pat.search(line)
    if p:
        for i in range(6):
            tot[i] += int(p.group(1 + i))
print('jumps for the sword %d (crits %d), jumps at it arriving %d (crits %d), hit out of either %d, punish crits %d' % tuple(tot))
