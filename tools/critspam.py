"""critspam.py [lost|won|all] [FROM_STAMP]: the crit spammer's pattern in the user's live rounds (run 100, agent 42), from the
per-round timelines the mod keeps (profiles/DRL AI/swordai/fights/*.txt.gz). FROM_STAMP is yyyymmdd-HHMMSS (default 20261114-224000,
the run98-2210 test). Opponents' names are never printed; rounds are counted, never named.

Measures, per group of rounds: their jump-ins that hit (take-off distance, take-off to hit, distance at the hit, its state and rule),
its punish crits (B1: a crit of its own that landed within 15 ticks after a hit it took) and what followed: their hit back within
12/30 ticks, whether they had jumped again before its crit landed (the document's T1), the opponent's state at the hit back, their
sword's ticks since their last swing; their next take-off after that hit back (the crit combo); their crit/jump-in hits in a row;
its sprint hits traded within 3 ticks."""
import collections
import glob
import gzip
import os
import statistics
import sys

BASE = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"


def load(path):
    rows, ix = [], None
    with gzip.open(path, 'rt', errors='replace') as g:
        for line in g:
            line = line.rstrip('\n')
            if line.startswith('tick,dist,'):
                ix = {n: i for i, n in enumerate(line.split(','))}
                continue
            if ix is None or not line[:1].isdigit():
                continue
            p = line.split(',', len(ix) - 1)
            if len(p) < len(ix):
                continue
            try:
                rows.append(dict(t=int(p[0]), d=float(p[1]), hp=p[4], me=p[6], opp=p[7], up=float(p[8]), tw=float(p[9]),
                                 mtw=float(p[11]), keys=p[ix['keys']], rules=p[ix['rules']],
                                 osw=p[ix['oppSwing']] if 'oppSwing' in ix else '', ev=p[ix['event']]))
            except ValueError:
                continue
    return rows


def q(xs):
    if not xs:
        return '-'
    xs = sorted(xs)
    n = len(xs)
    return 'n%d median %.2f (p25 %.2f, p75 %.2f)' % (n, xs[n // 2], xs[n // 4], xs[(3 * n) // 4])


def hpdrop(rows, k):
    try:
        return int(rows[max(0, k - 1)]['hp']) - int(rows[min(len(rows) - 1, k + 3)]['hp'])
    except ValueError:
        return 0


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'lost'
    since = sys.argv[2] if len(sys.argv) > 2 else '20261114-224000'
    files = sorted(f for f in glob.glob(BASE + '*.txt.gz') if os.path.basename(f)[:15] >= since
                   and (mode == 'all' or f.endswith('-' + mode + '.txt.gz')))
    C = collections.Counter()
    S = collections.defaultdict(list)
    for f in files:
        rows = load(f)
        if not rows:
            continue
        C['rounds'] += 1
        taken = [k for k, r in enumerate(rows) if 'TAKEN' in r['ev']]
        dealt = [k for k, r in enumerate(rows) if 'DEALT' in r['ev']]
        C['hits taken'] += len(taken)
        C['hits dealt'] += len(dealt)
        # Their take-offs: ground to air within 8 blocks, not within 4 ticks after its hit landed (a lift).
        tos = [k for k in range(1, len(rows)) if rows[k - 1]['opp'][0] == 'g' and rows[k]['opp'][0] == 'a' and rows[k]['d'] < 8
               and not any(0 <= k - j <= 4 for j in dealt)]
        jumpin = set()
        for k in taken:
            prior = [j for j in tos if 0 < k - j <= 14]
            if not prior:
                continue
            j, r = prior[-1], rows[k]
            jumpin.add(k)
            C['jump-in hits'] += 1
            S['jump-in: take-off distance'].append(rows[j - 1]['d'])
            S['jump-in: take-off to hit, ticks'].append(r['t'] - rows[j]['t'])
            S['jump-in: distance at the hit'].append(r['d'])
            S['jump-in: its own speed toward them (5 ticks after take-off)'].append(
                statistics.mean(rows[m]['mtw'] for m in range(j, min(len(rows), j + 5))))
            C['jump-in hit: it on the ground'] += r['me'] == 'g'
            C['jump-in hit: its sword ready'] += 'my-sword-ready' in r['ev']
            C['jump-in hit: rule k (swing kept for the way down)'] += 'k' in r['rules']
        for k, r in enumerate(rows):
            if 'swing:crit' not in r['ev']:
                continue
            d = [j for j in dealt if 0 <= j - k <= 3]
            tk = [j for j in taken if 0 < k - j <= 15]
            if not d or not tk:
                continue
            jd, h = d[0], tk[-1]
            C['punish crits'] += 1
            C['punish crits: rule a (answer swing) pressed it'] += 'a' in r['rules']
            S['punish crit: ticks from their hit to its swing'].append(r['t'] - rows[h]['t'])
            seg = rows[h:jd + 1]
            rejump = any(seg[m - 1]['opp'][0] == 'g' and seg[m]['opp'][0] == 'a' for m in range(1, len(seg)))
            C['punish crits: they had jumped again before it landed (T1)'] += rejump
            nxt = [j for j in taken if j > jd]
            if not nxt:
                continue
            dt = rows[nxt[0]]['t'] - rows[jd]['t']
            S['punish crit: ticks from it landing to their next hit'].append(dt)
            C['punish crits: hit back within 30'] += dt <= 30
            if dt > 12:
                continue
            n = rows[nxt[0]]
            C['punish crits: hit back within 12'] += 1
            C['  ... after their jump again (T1)'] += rejump
            C['  ... them in the air (seen)'] += n['opp'][0] == 'a'
            C['  ... its sword empty'] += 'my-sword-empty' in n['ev']
            S['hit back: their sword ticks since their last swing'].append(
                next((n['t'] - rows[m]['t'] for m in range(nxt[0] - 1, max(0, nxt[0] - 40), -1)
                      if rows[m]['osw'] == '1' and rows[m - 1]['osw'] == '0'), -1))
            S['hit back: distance'].append(n['d'])
            S['hit back: HP'].append(hpdrop(rows, nxt[0]))
            again = [j for j in tos if 0 < rows[j]['t'] - n['t'] <= 25]
            if again:
                C['hit back: they took off again within 25 (crit combo)'] += 1
                S['crit combo: ticks from the hit back to their take-off'].append(rows[again[0]]['t'] - n['t'])
                S['crit combo: take-off distance'].append(rows[again[0] - 1]['d'])
        run = best = 0
        for k in sorted(set(taken) | set(dealt)):
            ev = rows[k]['ev']
            if 'DEALT' in ev and 'TAKEN' not in ev:
                run = 0
            elif 'crit?' in ev or 'jumped-in' in ev or k in jumpin:
                run += 1
                best = max(best, run)
            else:
                run = 0
        S['their crit/jump-in hits in a row, longest a round'].append(best)
        for k, r in enumerate(rows):
            if 'swing:sprint' in r['ev']:
                d = [j for j in dealt if 0 <= j - k <= 3]
                if d:
                    C['its sprint hits landed'] += 1
                    C['  ... traded within 3 ticks'] += any(0 <= rows[j]['t'] - rows[d[0]]['t'] <= 3 for j in taken)
    print('%s rounds since %s: %d' % (mode, since, C['rounds']))
    for k in C:
        print('  %-62s %d' % (k, C[k]))
    for k in S:
        print('  %-62s %s' % (k, q(S[k])))


if __name__ == '__main__':
    main()
