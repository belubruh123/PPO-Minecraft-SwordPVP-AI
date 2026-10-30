"""schoolpairs.py LOG [--since HH:MM:SS]: the paired check of the technique school (A = school network, B = the installed
model as it plays real fights), from the "school pair N A|B bot: dealt x, taken y, ticks z[, knockout|, knocked out]" lines.

Per bot and in all: fights, net damage a fight (dealt - taken) for A and B, the paired difference A - B with its standard
error, knockouts won for each side, and dealt/taken a minute."""
import re
import sys
import math
from collections import defaultdict

PAT = re.compile(r"\[(\d\d:\d\d:\d\d)\].*school pair (\d+) ([AB]) ([a-z\-]+)( \(held out\))?: dealt ([\d.]+), taken ([\d.]+), ticks (\d+)(, knockout|, knocked out)?")


def main():
    log = sys.argv[1]
    since = None
    if '--since' in sys.argv:
        since = sys.argv[sys.argv.index('--since') + 1]
    pairs = defaultdict(dict)
    for line in open(log, encoding='utf8', errors='replace'):
        m = PAT.search(line)
        if not m:
            continue
        if since and m.group(1) < since:
            continue
        n = int(m.group(2))
        bot = m.group(4) + ('*' if m.group(5) else '')
        pairs[n][m.group(3)] = (bot, float(m.group(6)), float(m.group(7)), int(m.group(8)), m.group(9) or '')
    rows = defaultdict(list)
    for n, p in sorted(pairs.items()):
        if 'A' in p and 'B' in p:
            rows[p['A'][0]].append(p)
            rows['ALL'].append(p)
    print(f"{'bot':20s} {'pairs':>5s} {'A net/fight':>11s} {'B net/fight':>11s} {'A-B':>7s} {'se':>6s} {'A ko w-l':>9s} {'B ko w-l':>9s} {'A d/t a min':>13s} {'B d/t a min':>13s}")
    for bot in sorted(rows, key=lambda b: (b == 'ALL', b)):
        ps = rows[bot]
        diffs = [(p['A'][1] - p['A'][2]) - (p['B'][1] - p['B'][2]) for p in ps]
        n = len(diffs)
        mean = sum(diffs) / n
        se = math.sqrt(sum((d - mean) ** 2 for d in diffs) / (n - 1) / n) if n > 1 else float('nan')

        def side(s):
            net = sum(p[s][1] - p[s][2] for p in ps) / n
            w = sum(1 for p in ps if p[s][4] == ', knockout')
            l = sum(1 for p in ps if p[s][4] == ', knocked out')
            mins = sum(p[s][3] for p in ps) / 1200.0
            return net, f"{w}-{l}", f"{sum(p[s][1] for p in ps) / mins:5.0f}/{sum(p[s][2] for p in ps) / mins:<5.0f}"
        a, b = side('A'), side('B')
        print(f"{bot:20s} {n:5d} {a[0]:11.1f} {b[0]:11.1f} {mean:7.1f} {se:6.1f} {a[1]:>9s} {b[1]:>9s} {a[2]:>13s} {b[2]:>13s}")


main()
