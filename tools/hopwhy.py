import re, sys
# hopwhy.py LOG FROM: sums the 'five blocks off or more on the ground' part of the update lines
log, start = sys.argv[1], sys.argv[2]
names = ['ticks', 'jumped', 'not going at it', 'hit lately', 'to the side', 'sword not back', 'left alone', 'coming in']
tot = [0] * len(names)
pat = re.compile(r'five blocks off or more on the ground on (\d+) ticks: jumped (\d+), not going at it (\d+), hit lately (\d+), to the side (\d+), sword not back (\d+), left alone (\d+), coming in (\d+)')
for line in open(log, encoding='utf-8', errors='replace'):
    m = re.match(r'\[(\d\d:\d\d:\d\d)\]', line)
    if not m or m.group(1) < start:
        continue
    p = pat.search(line)
    if p:
        for i in range(len(names)):
            tot[i] += int(p.group(1 + i))
print(', '.join(f'{n} {v}' + (f' ({100.0 * v / tot[0]:.0f}%)' if i and tot[0] else '') for i, (n, v) in enumerate(zip(names, tot))))
