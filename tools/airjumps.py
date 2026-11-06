"""airjumps.py TAG [TAG ...]: hits taken in the air after its own jump, in benchmark episodes (run/swordai/bench/TAG/*.txt.gz),
and who pressed that jump: a rule (the take-off tick's rules column has J = "jump made for it" or r = jump reset) or the network.

Block 4 (PLAN.md): its own jumps inside their reach. In the hourly checks' hit log (tools/air2.py) 31% of the damage the training
network took (run 42's three checks) came in the air from its own jumps, the 23 Oct model's 6%. The hit log has no take-off
tick; these timelines have one line a tick with the keys and the rules that set them. The take-off is the last tick before the
hit that it pressed jump (j) on the ground (me = g). A hit taken while it is in the air with no take-off since it last stood is
"knocked up" (their hit lifted it), not its own jump.
"""
import collections
import glob
import gzip
import os
import sys

BENCH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'run', 'swordai', 'bench')


def episodes(tag):
    for p in sorted(glob.glob(os.path.join(BENCH, tag, '[0-9][0-9][0-9][0-9]-*.txt.gz'))):
        rows = []
        with gzip.open(p, 'rt', encoding='utf8', errors='replace') as f:
            seen = False
            for line in f:
                if line.startswith('tick,dist,'):
                    seen = True
                    continue
                if seen and line[:1].isdigit():
                    rows.append(line.rstrip('\n').split(','))
        yield os.path.basename(p), rows


def hp(x):
    try:
        return float(x)
    except ValueError:
        return None


def main():
    tags = sys.argv[1:]
    for tag in tags:
        taken = collections.Counter()
        count = collections.Counter()
        reach = collections.Counter()
        since = collections.Counter()
        bots = collections.Counter()
        total = 0.0
        jumps = collections.Counter()
        hits = collections.Counter()
        for name, rows in episodes(tag):
            bot = name[5:].split('.')[0]
            takeoff = None
            for i, r in enumerate(rows):
                if len(r) < 15:
                    continue
                me, keys, rules, ev = r[6], r[12], r[13], r[14]
                if me == 'g':
                    if 'j' in keys:
                        takeoff = i
                        jumps['rule' if ('J' in rules or 'r' in rules) else 'network'] += 1
                    else:
                        takeoff = None
                if 'TAKEN' in ev and i > 0:
                    # The HP column moves a few ticks after the TAKEN event (as DEALT comes ~2 ticks after the swing).
                    a = hp(rows[i - 1][4])
                    later = [hp(x[4]) for x in rows[i:i + 5] if len(x) > 4 and hp(x[4]) is not None]
                    d = max(0.0, a - min(later)) if a is not None and later else 0.0
                    hits['all'] += 1
                    total += d
                    if me != 'a':
                        continue
                    if takeoff is None:
                        key = 'knocked up (no own take-off)'
                    else:
                        t = rows[takeoff]
                        key = 'own jump, rule ' + ''.join(c for c in t[13] if c in 'Jr') if ('J' in t[13] or 'r' in t[13]) else 'own jump, network'
                        dist = hp(t[1])
                        reach[key + (', take-off dist <3' if dist is not None and dist < 3 else ', take-off dist 3-4.5' if dist is not None and dist < 4.5 else ', take-off dist 4.5+')] += d
                        since[key + ', hit %d+ ticks after take-off' % (5 * ((i - takeoff) // 5))] += d
                        bots[key + ', ' + bot] += d
                    taken[key] += d
                    count[key] += 1
        print('== %s: damage taken %.0f HP in %d hits; jumps pressed on the ground: %s' % (tag, total, hits['all'], dict(jumps)))
        for k, v in taken.most_common():
            print('  %-40s %6.1f HP  %5.1f%% of all taken  (%d hits, %.1f%% of hits)' % (k, v, 100 * v / max(total, 1), count[k],
                                                                                  100 * count[k] / max(hits['all'], 1)))
        for title, c in (('by take-off distance', reach), ('by ticks after take-off', since), ('by bot', bots)):
            print('  -- %s' % title)
            for k, v in c.most_common(12):
                print('     %-62s %5.1f%%' % (k, 100 * v / max(total, 1)))


if __name__ == '__main__':
    main()
