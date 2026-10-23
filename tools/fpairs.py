"""fpairs.py LOG A_FROM A_TO B_FROM B_TO [LOG ...repeat 5]: paired fight-by-fight comparison of two frozen runs made with
the same evalSeed (21 Oct). Reads 'eval result N: KIND, dealt D, taken T, ticks K' lines; fight N of run A and fight N of
run B were set up identically. Per fight: net = dealt - taken (damage points). Prints, per kind and in all: fights
paired, mean net A and B, mean difference A-B with its standard error, how many fights A did better / worse / same
(sign test), and a normal-approximation p-value for the signed-rank test. Several groups of 5 arguments are pooled
(e.g. the blocks of finalpair2.sh)."""
import math
import re
import sys

RES = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*eval result (\d+): ([^,]+), dealt ([\d.]+), taken ([\d.]+), ticks (\d+)')


def fights(log, a, b):
    out = {}
    for line in open(log, encoding='utf-8', errors='replace'):
        m = RES.match(line)
        if m and a <= m.group(1) <= b:
            out[int(m.group(2))] = (m.group(3).strip(), float(m.group(4)), float(m.group(5)), int(m.group(6)))
    return out


def signed_rank_p(diffs):
    d = [x for x in diffs if x != 0]
    n = len(d)
    if n < 6:
        return float('nan')
    ranked = sorted(range(n), key=lambda i: abs(d[i]))
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and abs(d[ranked[j + 1]]) == abs(d[ranked[i]]):
            j += 1
        for k in range(i, j + 1):
            ranks[ranked[k]] = (i + j) / 2.0 + 1
        i = j + 1
    w = sum(r for r, x in zip(ranks, d) if x > 0)
    mu = n * (n + 1) / 4.0
    sd = math.sqrt(n * (n + 1) * (2 * n + 1) / 24.0)
    z = (w - mu) / sd
    return math.erfc(abs(z) / math.sqrt(2))


args = sys.argv[1:]
pairs = []
for i in range(0, len(args), 5):
    log, af, at, bf, bt = args[i:i + 5]
    A, B = fights(log, af, at), fights(log, bf, bt)
    for n in sorted(set(A) & set(B)):
        if A[n][0] != B[n][0]:
            print('fight %d is %s in A but %s in B: not paired' % (n, A[n][0], B[n][0]))
            continue
        pairs.append((A[n][0], A[n][1] - A[n][2], B[n][1] - B[n][2], A[n][3], B[n][3]))


def report(name, ps):
    if not ps:
        return
    a = [p[1] for p in ps]
    b = [p[2] for p in ps]
    d = [x - y for x, y in zip(a, b)]
    n = len(d)
    mean = sum(d) / n
    se = math.sqrt(sum((x - mean) ** 2 for x in d) / (n - 1) / n) if n > 1 else float('nan')
    better = sum(1 for x in d if x > 0)
    worse = sum(1 for x in d if x < 0)
    print('%-18s fights %3d | net per fight A %+6.1f B %+6.1f | A-B %+6.1f (SE %4.1f) | A better %3d worse %3d same %3d | signed-rank p %.3f'
          % (name, n, sum(a) / n, sum(b) / n, mean, se, better, worse, n - better - worse, signed_rank_p(d)))


kinds = sorted(set(p[0] for p in pairs))
for k in kinds:
    report(k, [p for p in pairs if p[0] == k])
report('ALL', pairs)
