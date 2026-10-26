"""hits-report.py [hits.csv] [first_pair]: where the damage of a check goes, side A (the school network) against side B
(the installed model) on the same seeds. Reads the hit log SchoolSession writes in checks (school/hits.csv).

Every row is one tick with a hit dealt or taken. The report sorts each side's damage dealt and taken into the kinds of
hit and what the side was doing when it happened, per fight, and lists the biggest gaps first: the actions where A loses
damage that B does not."""
import csv
import sys
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else 'D:/swordai/run/swordai/school/hits.csv'
first = int(sys.argv[2]) if len(sys.argv) > 2 else 0

rows = [r for r in csv.DictReader(open(path, encoding='utf8')) if int(r['pair']) >= first]
fights = {'A': set(), 'B': set()}
for r in rows:
    fights[r['side']].add(r['pair'])
pairs = sorted(set.intersection(fights['A'], fights['B']), key=int)
keep = set(pairs)
rows = [r for r in rows if r['pair'] in keep]
n = len(pairs)
if n == 0:
    sys.exit('no pairs with both sides in ' + path)


def f(r, k):
    return float(r[k])


def i(r, k):
    return int(float(r[k]))


def dealt_kind(r):
    if i(r, 'punishCrit'):
        return 'punish crit (after being lifted)'
    if i(r, 'crit'):
        return 'crit'
    if i(r, 'sprintHit'):
        return 'sprint knockback hit'
    if f(r, 'hitCharge') < 0.9:
        return 'weak hit (sword not charged)'
    return 'plain charged hit'


def taken_kind(r):
    if i(r, 'critsTaken'):
        return 'their crit'
    if i(r, 'sprintsTaken'):
        return 'their sprint hit'
    return 'their plain hit'


def taken_context(r):
    """What the side was doing when it got hit (first that applies)."""
    if i(r, 'theirRow') >= 3:
        return 'in their combo (3rd+ hit in a row)'
    if i(r, 'theirRow') == 2:
        return 'in their combo (2nd hit in a row)'
    if i(r, 'walkedInOnCooldown'):
        return 'walked in with its sword not back'
    if not i(r, 'onGround'):
        return 'in the air'
    fwd = 'walking at them' if i(r, 'forward') > 0 else 'backing off' if i(r, 'forward') < 0 else 'not walking'
    if f(r, 'charge') < 0.9:
        if i(r, 'sinceSwing') <= 12:
            return f'sword coming back after its own swing, {fwd}'
        return f'sword not back (no swing lately), {fwd}'
    if i(r, 'sinceSwing') > 20:
        return f'sword ready but did not swing, {fwd}'
    return f'sword ready, swung lately (traded), {fwd}'


dealt = {s: defaultdict(float) for s in 'AB'}
taken = {s: defaultdict(float) for s in 'AB'}
taken_ctx = {s: defaultdict(float) for s in 'AB'}
dealt_sit = {s: defaultdict(float) for s in 'AB'}
taken_sit = {s: defaultdict(float) for s in 'AB'}
by_bot = {s: defaultdict(lambda: [0.0, 0.0]) for s in 'AB'}
bot_pairs = defaultdict(set)
for r in rows:
    s = r['side']
    d, t = f(r, 'dealt'), f(r, 'taken')
    bot_pairs[r['bot']].add(r['pair'])
    by_bot[s][r['bot']][0] += d
    by_bot[s][r['bot']][1] += t
    if d > 0:
        dealt[s][dealt_kind(r)] += d
        dealt_sit[s][r['situation']] += d
    if t > 0:
        taken[s][taken_kind(r)] += t
        taken_ctx[s][taken_context(r)] += t
        taken_sit[s][r['situation']] += t


def table(title, a, b, sign):
    """sign +1: more is better for A (dealt); -1: less is better (taken)."""
    keys = sorted(set(a) | set(b), key=lambda k: sign * (a.get(k, 0) - b.get(k, 0)))
    print(f'\n{title} (per fight; gap = what it costs A against B)')
    print(f'  {"":44s} {"A":>7s} {"B":>7s} {"gap":>7s}')
    for k in keys:
        av, bv = a.get(k, 0) / n, b.get(k, 0) / n
        print(f'  {k:44s} {av:7.2f} {bv:7.2f} {sign * (av - bv):+7.2f}')


tot = {s: (sum(dealt[s].values()) / n, sum(taken[s].values()) / n) for s in 'AB'}
print(f'{n} pairs from pair {pairs[0]}: A dealt {tot["A"][0]:.1f} taken {tot["A"][1]:.1f} a fight (net {tot["A"][0] - tot["A"][1]:+.1f}); '
      f'B dealt {tot["B"][0]:.1f} taken {tot["B"][1]:.1f} (net {tot["B"][0] - tot["B"][1]:+.1f}); A-B {tot["A"][0] - tot["A"][1] - tot["B"][0] + tot["B"][1]:+.1f}')
table('Damage DEALT by kind of hit', dealt['A'], dealt['B'], +1)
table('Damage TAKEN by kind of hit', taken['A'], taken['B'], -1)
table('Damage TAKEN by what the side was doing', taken_ctx['A'], taken_ctx['B'], -1)
table('Damage DEALT by situation (the teacher\'s label)', dealt_sit['A'], dealt_sit['B'], +1)
table('Damage TAKEN by situation (the teacher\'s label)', taken_sit['A'], taken_sit['B'], -1)
print('\nNet a fight by opponent (A, B, A-B, fights)')
for bot in sorted(bot_pairs, key=lambda b: (by_bot['A'][b][0] - by_bot['A'][b][1]) - (by_bot['B'][b][0] - by_bot['B'][b][1])):
    m = len(bot_pairs[bot])
    a = (by_bot['A'][bot][0] - by_bot['A'][bot][1]) / m
    b = (by_bot['B'][bot][0] - by_bot['B'][bot][1]) / m
    print(f'  {bot:24s} {a:+7.1f} {b:+7.1f} {a - b:+7.1f} {m:4d}')
