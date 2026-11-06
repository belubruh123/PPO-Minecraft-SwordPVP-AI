"""combo-ends.py DIR_OR_GLOB [...]: why its combos stop at two hits (tick timelines: real duels or benchmark folders).

For every combo of its own that reached two hits (each within GAP ticks), the GAP ticks after the second hit: did a third hit
come, did they hit back first, or neither (the combo ran out)? For the ones that ran out or were hit back: the distance on the
way (closest, at the end), its sword's charge when they were in reach (<= 3.0), whether it swung (a swing event), held W, held
sprint, jumped, and the opponent's movement (toward/away). Side by side with the 3rd hits that came, so the difference shows.
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


def rows_of(p):
    rows, seen = [], False
    with gzip.open(p, 'rt', encoding='utf8', errors='replace') as f:
        for line in f:
            if line.startswith('tick,dist,'):
                seen = True
                continue
            if seen and line[:1].isdigit():
                r = line.rstrip('\n').split(',')
                if len(r) >= 15:
                    rows.append(r)
    return rows


def f(x, d=0.0):
    try:
        return float(x)
    except ValueError:
        return d


def main():
    out = collections.defaultdict(collections.Counter)
    sums = collections.defaultdict(lambda: collections.defaultdict(float))
    for p in files(sys.argv[1:]):
        try:
            rows = rows_of(p)
        except (OSError, EOFError):
            continue
        run, last = 0, -1000
        for i, r in enumerate(rows):
            t, ev = int(r[0]), r[14]
            if 'TAKEN' in ev:
                run, last = 0, -1000
                continue
            if 'DEALT' not in ev:
                continue
            run = run + 1 if t - last <= GAP and run >= 1 else 1
            last = t
            if run != 2:
                continue
            # The window after its second hit.
            end, how = None, 'ran out'
            for j in range(i + 1, len(rows)):
                tj, evj = int(rows[j][0]), rows[j][14]
                if tj - t > GAP:
                    end = j
                    break
                if 'TAKEN' in evj:
                    end, how = j, 'hit back first'
                    break
                if 'DEALT' in evj:
                    end, how = j, 'third hit'
                    break
            if end is None:
                end = len(rows) - 1
            w = rows[i + 1:end + 1]
            if not w:
                continue
            c = out[how]
            s = sums[how]
            c['n'] += 1
            dists = [f(x[1], 99) for x in w]
            s['closest'] += min(dists)
            s['dist at 10 ticks'] += dists[min(9, len(dists) - 1)]
            inreach = [x for x in w if f(x[1], 99) <= 3.0]
            c['was in reach'] += 1 if inreach else 0
            if inreach:
                s['charge in reach'] += max(f(x[2]) for x in inreach)
                c['charged in reach (>=0.9)'] += 1 if any(f(x[2]) >= 0.9 for x in inreach) else 0
            c['swung'] += 1 if any('swing' in x[14] for x in w) else 0
            c['swing missed'] += 1 if any('swing:miss' in x[14] for x in w) else 0
            n = len(w)
            s['W held share'] += sum(1 for x in w if x[12][:1] == 'W') / n
            s['S held share'] += sum(1 for x in w if x[12][:1] == 'S') / n
            s['sprint held share'] += sum(1 for x in w if 'r' in x[12]) / n
            s['jumped'] += 1 if any('j' in x[12] for x in w) else 0
            s['they went away share'] += sum(1 for x in w if f(x[9]) < -0.05) / n
            s['they came at it share'] += sum(1 for x in w if f(x[9]) > 0.05) / n
            s['ticks'] += n
            s['rules on'] += sum(1 for x in w if x[13]) / n
    tot = sum(c['n'] for c in out.values())
    print('two-hit combos: %d' % tot)
    for how in ('third hit', 'hit back first', 'ran out'):
        c, s = out[how], sums[how]
        n = max(1, c['n'])
        print('== %-15s %5d (%3.0f%%)' % (how, c['n'], 100 * c['n'] / max(1, tot)))
        print('   closest %.2f, at 10 ticks %.2f, ticks %.1f | in reach %3.0f%%, charge there %.2f, charged %3.0f%% | swung %3.0f%%, missed %3.0f%%'
              % (s['closest'] / n, s['dist at 10 ticks'] / n, s['ticks'] / n, 100 * c['was in reach'] / n,
                 s['charge in reach'] / max(1, c['was in reach']), 100 * c['charged in reach (>=0.9)'] / max(1, c['was in reach']),
                 100 * c['swung'] / n, 100 * c['swing missed'] / n))
        print('   W %3.0f%%  S %3.0f%%  sprint %3.0f%%  jumped %3.0f%% | they away %3.0f%%  toward %3.0f%% | a rule had keys %3.0f%%'
              % (100 * s['W held share'] / n, 100 * s['S held share'] / n, 100 * s['sprint held share'] / n, 100 * s['jumped'] / n,
                 100 * s['they went away share'] / n, 100 * s['they came at it share'] / n, 100 * s['rules on'] / n))


if __name__ == '__main__':
    main()
