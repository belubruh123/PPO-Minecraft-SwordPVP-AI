"""combos.py HITS_CSV [K]: follow-up hits of its own combos a fight, per hourly check, side A (training) and B (the 23 Oct model).

Block 3 (PLAN.md, run 42's combo reward): a follow-up hit is a hit dealt with myRow >= 2 (its second or later hit since the
last hit taken; a trade tick shows myRow 0). "within 20" also needs its previous hit in the same fight at most 20 ticks before
(run 42's COMBO_GAP), the term the reward pays. The checks are told apart as tools/lastchecks.py does (pair numbers start
again). 3+ = a combo that reached a third hit (block 5, run 44). Baseline, the last three checks before run 42 (7 Nov 01:54-03:33): any gap A 2.14 / B 2.31, within 20 A 1.74 / B 1.75.
"""
import csv
import sys


def checks_of(rows):
    out, last, cur = [], None, []
    for r in rows:
        p = int(r['pair'])
        if last is not None and p < last - 5:
            out.append(cur)
            cur = []
        cur.append(r)
        last = p
    if cur:
        out.append(cur)
    return out


def side_stats(rows, side):
    s = [r for r in rows if r['side'] == side]
    fights = {(r['pair'], r['bot']) for r in s}
    anygap = within = three = reached2 = 0
    prev, chain = {}, {}
    for r in s:
        key = (r['pair'], r['bot'])
        tick = int(r['tick'])
        if int(r['hitsTaken']) > 0:
            prev.pop(key, None)
            chain[key] = 0
            continue
        if int(r['hitsDealt']) > 0:
            if int(r['myRow']) >= 2:
                anygap += 1
            if key in prev and tick - prev[key] <= 20:
                within += 1
                chain[key] = chain.get(key, 1) + 1
                reached2 += chain[key] == 2
                three += chain[key] == 3
            else:
                chain[key] = 1
            prev[key] = tick
    n = max(1, len(fights))
    return len(fights), anygap / n, within / n, three / n, three / max(1, reached2)


def main():
    rows = list(csv.DictReader(open(sys.argv[1], encoding='utf8')))
    k = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    print('per fight: follow-up hits (any gap, within 20 ticks), 3+ combos (within 20), share of 2-hit combos that reached 3')
    print('check      A fights  any gap  within 20   3+  2->3 |  B fights  any gap  within 20   3+  2->3')
    tot = {'A': [0, 0.0, 0.0, 0.0], 'B': [0, 0.0, 0.0, 0.0]}
    for c in checks_of(rows)[-k:]:
        line = []
        for side in 'AB':
            n, a, w, t3, share = side_stats(c, side)
            line.append('%9d %8.2f %10.2f %4.2f %4.0f%%' % (n, a, w, t3, 100 * share))
            for i, v in enumerate((n, a * n, w * n, t3 * n)):
                tot[side][i] += v
        print('check   %s | %s' % (line[0], line[1]))
    print('all %d   %s | %s' % (k, *('%9d %8.2f %10.2f %4.2f' % (t[0], t[1] / max(1, t[0]), t[2] / max(1, t[0]), t[3] / max(1, t[0])) for t in (tot['A'], tot['B']))))


if __name__ == '__main__':
    main()
