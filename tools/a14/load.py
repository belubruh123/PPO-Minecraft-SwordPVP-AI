"""Shared loader for hits.csv / jumps.csv checks (read-only)."""
import csv, re

HITS = r'D:/swordai/run/swordai/school/hits.csv'
JUMPS = r'D:/swordai/run/swordai/school/jumps.csv'
CHECKLOG = r'D:/swordai/run/school-checks.log'


def segments(path):
    rows = []
    with open(path) as f:
        r = csv.reader(f)
        h = next(r)
        for row in r:
            if row[0] == 'pair':
                continue
            rows.append(row)
    H = {k: i for i, k in enumerate(h)}
    checks, cur, prev = [], [], -1
    for row in rows:
        p = int(row[0])
        if p < prev and prev - p > 5:
            checks.append(cur)
            cur = []
        cur.append(row)
        prev = p
    checks.append(cur)
    return H, checks


def check_times():
    out = []
    with open(CHECKLOG) as f:
        for line in f:
            m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w)', line)
            if m:
                out.append((m.group(1), m.group(2)))
            m = re.match(r'ROUNDS WON: A (\d+)/(\d+).*B (\d+)/', line)
            if m and out and len(out[-1]) == 2:
                out[-1] = out[-1] + (int(m.group(1)), int(m.group(3)), int(m.group(2)))
    return out


def fights(H, check):
    """{(pair, side): [rows]} in tick order."""
    d = {}
    for row in check:
        d.setdefault((int(row[0]), row[1]), []).append(row)
    for k in d:
        d[k].sort(key=lambda r: int(r[H['tick']]))
    return d


def outcome(H, rows, hp=70.0):
    """+1 it knocked them out, -1 knocked out, 0 neither (time). Uses cumulative damage."""
    dealt = taken = 0.0
    for r in rows:
        dealt += float(r[H['dealt']])
        taken += float(r[H['taken']])
        if dealt >= hp - 0.01:
            return 1, dealt, taken
        if taken >= hp - 0.01:
            return -1, dealt, taken
    return 0, dealt, taken


def outcome2(H, rows, hp=70.0):
    """Like outcome, but the killing hit is not logged: the side nearer the 70 is taken to have landed it."""
    o, d, t = outcome(H, rows, hp)
    if o != 0:
        return o, d, t
    gd, gt = hp - d, hp - t
    if gd <= 10.6 and gt > 10.6:
        return 1, d, t
    if gt <= 10.6 and gd > 10.6:
        return -1, d, t
    if gd <= 10.6 and gt <= 10.6:
        return (1 if gd < gt else -1 if gt < gd else 0), d, t
    return 0, d, t
