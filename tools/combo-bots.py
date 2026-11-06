"""combo-bots.py DIR_OR_GLOB [...]: its own combos per opponent from tick timelines (RoundRecorder format: the user's real duels in
the profile's swordai/fights/, or a benchmark folder run/swordai/bench/TAG/).

The user (7 Nov 07:35): combos must reach 3+ and start nearly any time, and "adjust your bot that fight against it cause it might
be too perfect". This compares how far its combos get against each training bot with how far they get against real players:
a combo is its hits in a row, each within GAP ticks of the last, no hit taken between. Per opponent kind: combos started
(2+ hits) a fight, 3+ a fight, the share of 2-hit combos that went on to 3, and how soon the opponent hit back after its
first hit (ticks; a bot that answers faster than any player teaches it that a second hit is never there).
"""
import collections
import glob
import gzip
import os
import sys

GAP = 20


def files(args):
    for a in args:
        if os.path.isdir(a):
            yield from sorted(glob.glob(os.path.join(a, '*.txt.gz')))
        else:
            yield from sorted(glob.glob(a))


def read(p):
    head, rows, seen = [], [], False
    with gzip.open(p, 'rt', encoding='utf8', errors='replace') as f:
        for line in f:
            if line.startswith('tick,dist,'):
                seen = True
                continue
            if seen and line[:1].isdigit():
                rows.append(line.rstrip('\n').split(','))
            elif not seen:
                head.append(line)
    return head, rows


def kind_of(p, head):
    base = os.path.basename(p)
    if base[:4].isdigit() and base[4] == '-':
        return base[5:].split('.')[0]          # benchmark: 0000-bot.txt.gz
    return 'real players'                        # the user's real duels


def main():
    stats = collections.defaultdict(lambda: collections.Counter())
    theirs = collections.defaultdict(lambda: collections.Counter())
    answer = collections.defaultdict(list)
    for p in files(sys.argv[1:]):
        try:
            head, rows = read(p)
        except (OSError, EOFError):
            continue
        if len(rows) < 20:
            continue
        k = kind_of(p, head)
        s = stats[k]
        s['fights'] += 1
        run, last, first_at = 0, -1000, None
        for r in rows:
            if len(r) < 15:
                continue
            t, ev = int(r[0]), r[14]
            dealt, taken = 'DEALT' in ev, 'TAKEN' in ev
            if taken:
                if first_at is not None and run == 1:
                    answer[k].append(t - first_at)
                if run >= 2:
                    s['combos 2+'] += 1
                if run >= 3:
                    s['combos 3+'] += 1
                run, first_at = 0, None
                if dealt:
                    continue
            if dealt:
                if t - last <= GAP and run >= 1:
                    run += 1
                    if run == 2:
                        s['reached 2'] += 1
                    if run == 3:
                        s['reached 3'] += 1
                else:
                    if run >= 2:
                        s['combos 2+'] += 1
                    if run >= 3:
                        s['combos 3+'] += 1
                    run, first_at = 1, t
                    s['first hits'] += 1
                last = t
        if run >= 2:
            s['combos 2+'] += 1
        if run >= 3:
            s['combos 3+'] += 1
        # Their combos on it, the same way (which bot's way of holding a combo works in this game).
        o = theirs[k]
        o['fights'] += 1
        orun, olast = 0, -1000
        for r in rows:
            if len(r) < 15:
                continue
            t, ev = int(r[0]), r[14]
            if 'DEALT' in ev:
                orun = 0
                continue
            if 'TAKEN' in ev:
                if t - olast <= GAP and orun >= 1:
                    orun += 1
                    o['reached 2'] += orun == 2
                    o['reached 3'] += orun == 3
                    o['reached 4'] += orun == 4
                else:
                    orun = 1
                    o['first hits'] += 1
                olast = t
    print('%-20s %6s %10s %10s %10s %12s %16s' % ('opponent', 'fights', 'starts/f', '2+ a fight', '3+ a fight', '2 -> 3', 'hit back after'))
    order = sorted(stats, key=lambda k: (k != 'real players', k))
    for k in order:
        s = stats[k]
        n = max(1, s['fights'])
        a = sorted(answer[k])
        med = a[len(a) // 2] if a else None
        quick = sum(1 for x in a if x <= 10) / max(1, len(a))
        print('%-20s %6d %10.2f %10.2f %10.2f %11.0f%% %8s ticks, <=10: %3.0f%%' % (
            k, s['fights'], s['first hits'] / n, s['reached 2'] / n, s['reached 3'] / n,
            100 * s['reached 3'] / max(1, s['reached 2']), med if med is not None else '-', 100 * quick))
    print()
    print('their combos on it: %-12s %6s %10s %10s %10s %8s %8s' % ('opponent', 'fights', 'starts/f', '2+ a fight', '3+ a fight', '2 -> 3', '3 -> 4'))
    for k in order:
        o = theirs[k]
        n = max(1, o['fights'])
        print('%-32s %6d %10.2f %10.2f %10.2f %7.0f%% %7.0f%%' % (k, o['fights'], o['first hits'] / n, o['reached 2'] / n, o['reached 3'] / n,
              100 * o['reached 3'] / max(1, o['reached 2']), 100 * o['reached 4'] / max(1, o['reached 3'])))


if __name__ == '__main__':
    main()
