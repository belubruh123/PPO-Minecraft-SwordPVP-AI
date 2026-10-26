"""scene-hits-report.py [kind] [opponents] [scene-hits.csv]: why scenes are lost. Reads the hit log of the training scene
fights (school/scene-hits.csv, SchoolSession#flushSceneHits) and sets the scenes LOST against the scenes WON, per scene:
damage dealt by kind of hit, taken by kind and by what it was doing when hit. The biggest gaps come first: what it does in
the scenes it loses that it does not in those it wins.

kind: crit (default), jump-in, combo. opponents: bots (default: every bot but the installed model), installed, all, or
a bot's name (fall-spacer, punish-critter, ...). Then the mastered scenes tested again: those lost against the same
scenes' wins before (forgetting), and the opponents they are lost against."""
import csv
import sys
from collections import defaultdict

kind = sys.argv[1] if len(sys.argv) > 1 else 'crit'
who = sys.argv[2] if len(sys.argv) > 2 else 'bots'
path = sys.argv[3] if len(sys.argv) > 3 else 'D:/swordai/run/swordai/school/scene-hits.csv'


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
        return 'sprint hit on them falling (K2)' if i(r, 'themFalling') else 'sprint knockback hit'
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
    """What it was doing when it got hit (first that applies)."""
    if i(r, 'theirRow') >= 2:
        return 'in their combo (2nd+ hit in a row)'
    if i(r, 'walkedInOnCooldown'):
        return 'walked in with its sword not back'
    if not i(r, 'onGround'):
        return 'in the air, falling' if i(r, 'falling') else 'in the air, going up'
    fwd = 'walking at them' if i(r, 'forward') > 0 else 'backing off' if i(r, 'forward') < 0 else 'not walking'
    spr = ', sprinting' if i(r, 'sprinting') else ''
    if f(r, 'charge') < 0.9:
        if i(r, 'sinceSwing') <= 12:
            return f'sword coming back after its own swing, {fwd}{spr}'
        return f'sword not back (no swing lately), {fwd}{spr}'
    if i(r, 'sinceSwing') > 20:
        return f'sword ready but did not swing, {fwd}{spr}'
    return f'sword ready, swung lately (traded), {fwd}{spr}'


def wanted(r):
    if r['kind'] != kind:
        return False
    if who == 'all':
        return True
    if who == 'bots':
        return r['bot'] != 'installed-model'
    if who == 'installed':
        return r['bot'] == 'installed-model'
    return r['bot'] == who


# One fight = a run of rows with the same serial and scene (the serial starts again with each session).
fights = []
last = None
for r in csv.DictReader(open(path, encoding='utf8')):
    key = (r['pair'], r['scene'])
    if key != last:
        fights.append([])
        last = key
    fights[-1].append(r)

learning = [fi for fi in fights if fi and wanted(fi[0]) and fi[0]['review'] == '0']
groups = {'lost': [fi for fi in learning if fi[0]['won'] == '0'], 'won': [fi for fi in learning if fi[0]['won'] == '1']}
if not learning:
    sys.exit(f'no {kind} scenes against {who} in {path} yet')
stats = {g: {'dealt': defaultdict(float), 'taken': defaultdict(float), 'ctx': defaultdict(float), 'sit': defaultdict(float),
             'bot': defaultdict(int)} for g in groups}
for g, fs in groups.items():
    for fi in fs:
        stats[g]['bot'][fi[0]['bot']] += 1
        for r in fi:
            d, t = f(r, 'dealt'), f(r, 'taken')
            if d > 0:
                stats[g]['dealt'][dealt_kind(r)] += d
            if t > 0:
                stats[g]['taken'][taken_kind(r)] += t
                stats[g]['ctx'][taken_context(r)] += t
                stats[g]['sit'][r['situation']] += t
nl, nw = max(1, len(groups['lost'])), max(1, len(groups['won']))


def table(title, lost, won, sign):
    """sign +1: more is better (dealt); -1: less is better (taken). Biggest cost in the lost scenes first."""
    keys = sorted(set(lost) | set(won), key=lambda k: sign * (lost.get(k, 0) / nl - won.get(k, 0) / nw))
    print(f'\n{title} (a scene; gap = what the lost scenes cost against the won)')
    print(f'  {"":52s} {"lost":>7s} {"won":>7s} {"gap":>7s}')
    for k in keys:
        lv, wv = lost.get(k, 0) / nl, won.get(k, 0) / nw
        print(f'  {k:52s} {lv:7.2f} {wv:7.2f} {sign * (lv - wv):+7.2f}')


decided = sum(1 for fi in learning if fi[0]['decider'] == '1')
print(f'{kind} scenes against {who}: {len(learning)} with a hit, won {len(groups["won"])} ({100.0 * len(groups["won"]) / len(learning):.0f}%); '
      f'the decider chose in {decided}')
print('  lost by opponent: ' + ', '.join(f'{b} {c}' for b, c in sorted(stats['lost']['bot'].items(), key=lambda x: -x[1])))
print('  won by opponent:  ' + ', '.join(f'{b} {c}' for b, c in sorted(stats['won']['bot'].items(), key=lambda x: -x[1])))
table('Damage DEALT by kind of hit', stats['lost']['dealt'], stats['won']['dealt'], +1)
table('Damage TAKEN by kind of hit', stats['lost']['taken'], stats['won']['taken'], -1)
table('Damage TAKEN by what it was doing', stats['lost']['ctx'], stats['won']['ctx'], -1)
table('Damage TAKEN by situation (the teacher\'s label)', stats['lost']['sit'], stats['won']['sit'], -1)

# Forgetting: mastered scenes tested again.
reviews = [fi for fi in fights if fi and wanted(fi[0]) and fi[0]['review'] == '1']
if reviews:
    forgot = [fi for fi in reviews if fi[0]['won'] == '0']
    by = defaultdict(lambda: [0, 0])
    for fi in reviews:
        by[fi[0]['bot']][0] += 1
        by[fi[0]['bot']][1] += fi[0]['won'] == '0'
    print(f'\nMastered {kind} scenes tested again: {len(reviews)}, lost {len(forgot)} ({100.0 * len(forgot) / len(reviews):.0f}%)')
    print('  by opponent (tested, lost): ' + ', '.join(f'{b} {t}/{l}' for b, (t, l) in sorted(by.items(), key=lambda x: -x[1][1])))
    before = defaultdict(list)
    for fi in fights:
        if fi and fi[0]['review'] == '0' and fi[0]['won'] == '1':
            before[fi[0]['scene']].append(fi)
    taken_now, taken_then = defaultdict(float), defaultdict(float)
    m = 0
    for fi in forgot:
        wins = before.get(fi[0]['scene'])
        if not wins:
            continue
        m += 1
        for r in fi:
            if f(r, 'taken') > 0:
                taken_now[taken_context(r)] += f(r, 'taken')
        for r in wins[-1]:
            if f(r, 'taken') > 0:
                taken_then[taken_context(r)] += f(r, 'taken') / 1.0
    if m:
        print(f'  {m} of the lost ones were won in this log before; damage taken by what it was doing, now against its last win:')
        for k in sorted(set(taken_now) | set(taken_then), key=lambda k: -(taken_now.get(k, 0) - taken_then.get(k, 0))):
            print(f'    {k:52s} now {taken_now.get(k, 0) / m:6.2f}  then {taken_then.get(k, 0) / m:6.2f}')
