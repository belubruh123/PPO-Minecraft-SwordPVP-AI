import re, sys
log, a, b = sys.argv[1:4]
M = re.compile(r"ran in at an opponent on its way up on (\d+) ticks, swings (\d+) \(ticks with one on its way up within range (\d+): its own feet off the ground (\d+), sword not back (\d+), not closing (\d+)\)")
F = re.compile(r"falls after a hit: crit (\d+), a plain hit first (\d+), opponent never under the crosshair (\d+), sword never back (\d+), other (\d+), opponent out of reach (\d+)")
P = re.compile(r"opponent under the crosshair with the sword back on (\d+) ticks, no swing on (\d+) of them, (\d+) of those kept")
W = re.compile(r"walked into reach on a ready sword (\d+), on one coming back (\d+)")
tm, tf, tp, tw = [0]*6, [0]*6, [0]*3, [0]*2
for line in open(log, encoding='utf-8', errors='replace'):
    t = line[1:9]
    if not (a <= t <= b) or 'fights since the last update' not in line: continue
    for rx, acc in ((M, tm), (F, tf), (P, tp), (W, tw)):
        m = rx.search(line)
        if m:
            for i, v in enumerate(m.groups()): acc[i] += int(v)
print('meet: ran in on %d ticks, swings %d | opponent rising in range %d ticks: own feet off %d, sword not back %d, not closing %d' % tuple(tm))
print('falls after its hit: crit %d, plain first %d, never under crosshair %d, sword never back %d, other %d, out of reach %d' % tuple(tf))
print('opponent under crosshair, sword back: %d ticks, no swing %d, kept for way down %d' % tuple(tp))
print('walked into reach: on a ready sword %d, on one coming back %d' % tuple(tw))
