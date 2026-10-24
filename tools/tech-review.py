"""tech-review.py [N] [--run D:/swordai/run]: every technique over the last N school updates (default 8, about ten minutes),
with what its moments missed and why, to tell a rule in the way from a technique not learned from one not learned yet.

Per technique: done/moments now and in the N updates before (the trend), the keys it was off on (summed ticks shares,
weighted by moments), swings its teacher asked for that a rule kept back (sword gate / whiff guard), and a verdict:
  RULE      a rule kept back more than a tenth of its moments' swings: the rule, not the network
  FEW       under 10 moments in the window: too few chances to learn (its bots, or the moment's start)
  RISING    up 5 points or more on the window before: learning, normal
  STUCK     enough moments, not rising: look at the keys it is off on and fix the teaching
Below 60% first, then below 80%.
"""
import collections
import glob
import os
import re
import sys

KEYS = ('W/S', 'A/D', 'sprint', 'jump', 'swing')


def updates(run):
    logs = sorted(glob.glob(os.path.join(run, 'school-client-*.log')), key=os.path.getmtime)[-4:]
    logs.append(os.path.join(run, 'school-client.log'))
    out = []
    for f in logs:
        if os.path.exists(f):
            out += [l for l in open(f, encoding='utf8', errors='replace') if 'school update' in l]
    return out


def tally(lines):
    done = collections.defaultdict(lambda: [0, 0])
    off = collections.defaultdict(lambda: collections.defaultdict(float))
    blocked = collections.defaultdict(lambda: [0, 0])
    for l in lines:
        m = re.search(r'done since the last update:([^|]*)', l)
        moments = {}
        if m:
            for t, x, n in re.findall(r' (\w+) (\d+)/(\d+) done', m.group(1)):
                done[t][0] += int(x)
                done[t][1] += int(n)
                moments[t] = int(n)
        m = re.search(r'why not done:([^|]*)', l)
        if m:
            for part in m.group(1).split(';'):
                p = part.strip().split(',')[0]
                if not p:
                    continue
                t = p.split()[0]
                for k in KEYS:
                    km = re.search(re.escape(k) + r' (\d+)%', p)
                    if km:
                        off[t][k] += int(km.group(1)) * moments.get(t, 0)
        m = re.search(r'kept back by a rule \(sword gate/whiff guard\):([^|]*)', l)
        if m:
            for t, a, b in re.findall(r' (\w+) (\d+)/(\d+);', m.group(1)):
                blocked[t][0] += int(a)
                blocked[t][1] += int(b)
    return done, off, blocked


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    n = int(args[0]) if args else 8
    run = sys.argv[sys.argv.index('--run') + 1] if '--run' in sys.argv else 'D:/swordai/run'
    ups = updates(run)
    now, before = ups[-n:], ups[-2 * n:-n]
    d, off, blocked = tally(now)
    d0, _, _ = tally(before)
    rows = []
    for t, (x, k) in d.items():
        if t == '-':
            continue
        r = 100.0 * x / k if k else 0.0
        x0, k0 = d0.get(t, (0, 0))
        r0 = 100.0 * x0 / k0 if k0 else None
        keys = {kk: v / k for kk, v in off[t].items()} if k else {}
        worst = ', '.join('%s %.0f%%' % (kk, v) for kk, v in sorted(keys.items(), key=lambda kv: -kv[1])[:3] if v >= 3)
        bg, bw = blocked.get(t, (0, 0))
        if (bg + bw) > 0.1 * max(1, k) and (bg + bw) >= 3:
            verdict = 'RULE (gate %d, guard %d)' % (bg, bw)
        elif k < 10:
            verdict = 'FEW'
        elif r0 is not None and r - r0 >= 5:
            verdict = 'RISING'
        else:
            verdict = 'STUCK'
        rows.append((r, t, x, k, r0, worst, verdict))
    rows.sort()
    print('%d updates (%s to %s); before: %d' % (len(now), now[0][1:9] if now else '-', now[-1][1:9] if now else '-', len(before)))
    for bar in (60, 80):
        sel = [row for row in rows if row[0] < bar and (bar == 60 or row[0] >= 60)]
        print('--- under %d%% ---' % bar if bar == 60 else '--- 60-80% ---')
        for r, t, x, k, r0, worst, verdict in sel:
            print('%-5s %3.0f%% (%d/%d)  before %s  off: %-32s %s' % (t, r, x, k, '-' if r0 is None else '%3.0f%%' % r0, worst or '-', verdict))
    print('80%%+: %s' % ', '.join('%s %.0f%%' % (t, r) for r, t, *_ in rows if r >= 80))


if __name__ == '__main__':
    main()
