"""hourlysum3.py LABEL: the hourly line of hourlycheck4.sh (23 Oct), from PAIRED runs (Config.pairedBase): in pair-LABEL-sp
and pair-LABEL-mix (windows in pair-windows.txt) every fight was fought by the checked network (A, its own settings) and by
the installed one (B, the installed settings) from the same seed in the same run. Score = spacer A-B net a minute + mean of
the hard kinds' A-B + half the 0.14.0 model's A-B, with its standard error; BETTER if over two standard errors above 0,
WORSE if two below, else about EQUAL. Appends the line to hourly-check.log, the score to hourly-scores.txt, and counts
WORSE hours in hourly-state.txt (reset by anything else). Damage a minute dealt/taken per opponent for both sides."""
import collections
import math
import re
import sys

label = sys.argv[1]
LINE = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*paired result (\d+) ([AB]): (.+?), dealt ([\d.]+), taken ([\d.]+), ticks (\d+)')
# The trainer's names for the kinds (Trainer.KIND_NAMES; the spacer that stands is 'stander'): the hard ones of the old checks.
HARD = ['timer', 'spacer', 'stander', 'whiffer', 'jumper', 'punisher', 'circler', 'no-knockback']
OLD = 'the 0.14.0 model'
MIN_TICKS = 20
dropped = []


def window(part):
    for line in open('pair-windows.txt'):
        f, s, e = line.split()
        if f == 'pair-%s-%s.txt' % (label, part):
            return s, e
    return None


def pairs(part):
    w = window(part)
    out = collections.defaultdict(list)
    if not w:
        return out
    fights = collections.defaultdict(dict)
    for line in open('client.log', encoding='utf-8', errors='replace'):
        m = LINE.match(line)
        if m and w[0] <= m.group(1) <= w[1]:
            fights[int(m.group(2))][m.group(3)] = (m.group(4), float(m.group(5)), float(m.group(6)), max(1, int(m.group(7))))
    for n, f in fights.items():
        if 'A' in f and 'B' in f and f['A'][0] == f['B'][0]:
            # A fight shorter than a second (23 Oct: knocked out in 5 ticks, damage carried over) is not a fight, and per
            # minute it weighs thousands: the pair is left out.
            if min(f['A'][3], f['B'][3]) < MIN_TICKS:
                dropped.append('%s %s' % (part, n))
                continue
            out[f['A'][0]].append((f['A'], f['B']))
    return out


def net(x):
    return 1200.0 * (x[1] - x[2]) / x[3]


def diff(rows):
    """Mean A-B net a minute over the pairs, and its standard error."""
    d = [net(a) - net(b) for a, b in rows]
    if len(d) < 2:
        return 0.0, float('inf') if not d else 999.0
    m = sum(d) / len(d)
    return m, math.sqrt(sum((x - m) ** 2 for x in d) / (len(d) - 1) / len(d))


sp = pairs('sp')
mix = pairs('mix')
sp_rows = [r for rows in sp.values() for r in rows]
s_m, s_se = diff(sp_rows)
hard = [diff(mix[k]) for k in HARD if len(mix.get(k, [])) >= 2]
h_m = sum(m for m, se in hard) / len(hard) if hard else 0.0
h_se = math.sqrt(sum(se ** 2 for m, se in hard)) / len(hard) if hard else 999.0
o_m, o_se = diff(mix.get(OLD, []))
score = s_m + h_m + 0.5 * o_m
se = math.sqrt(s_se ** 2 + h_se ** 2 + 0.25 * o_se ** 2)
verdict = 'BETTER than installed' if score > 2 * se else 'WORSE than installed' if score < -2 * se else 'about EQUAL to installed'


def side(rows, i):
    n = len(rows)
    d = sum(1200.0 * r[i][1] / r[i][3] for r in rows) / n
    t = sum(1200.0 * r[i][2] / r[i][3] for r in rows) / n
    won = 100.0 * sum(1 for r in rows if r[i][1] > r[i][2]) / n
    return '%d/%d (%d%% won)' % (d, t, won)


parts = ['%s PAIRED | spacer-only %d pairs: %s vs installed %s, A-B %+.0f (se %.0f)' % (
    label, len(sp_rows), side(sp_rows, 0) if sp_rows else '-', side(sp_rows, 1) if sp_rows else '-', s_m, s_se)]
parts.append('mix hard kinds A-B %+.0f (se %.0f, %d kinds)' % (h_m, h_se, len(hard)))
if mix.get(OLD):
    parts.append('0.14.0 %s vs %s, A-B %+.0f (se %.0f)' % (side(mix[OLD], 0), side(mix[OLD], 1), o_m, o_se))
if dropped:
    parts.append('%d pairs under a second left out (%s)' % (len(dropped), ', '.join(dropped)))
parts.append('score %+.0f (se %.0f): %s' % (score, se, verdict))
line = ' | '.join(parts)
print(line)
with open('hourly-check.log', 'a') as f:
    f.write(line + '\n')
    for k in sorted(set(sp) | set(mix)):
        rows = sp.get(k, []) + mix.get(k, [])
        if rows:
            f.write('      %-20s %3d pairs: dealt/taken a minute %s | installed %s\n' % (k, len(rows), side(rows, 0), side(rows, 1)))
with open('hourly-scores.txt', 'a') as f:
    f.write('%s %.1f\n' % (label, score))
state = 0
try:
    state = int(open('hourly-state.txt').read().strip() or 0)
except (OSError, ValueError):
    pass
state = state + 1 if verdict.startswith('WORSE') else 0
open('hourly-state.txt', 'w').write('%d\n' % state)
