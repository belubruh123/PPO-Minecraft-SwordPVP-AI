"""Sums the per-update "fights since the last update" lines of the dev client's log over blocks of updates.

  python agg2.py [--block N] [--last N] [--log PATH] [--view kinds|rules|far|falls|all]
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CELL = re.compile(
    r'(?P<st>\d+) steps: (?P<h>\d+) hits \((?P<c>\d+)% crit, (?P<s>\d+)% sprint\), (?P<t>\d+) taken, '
    r'(?P<j>\d+) jumps/min of which (?P<jc>\d+)% crit, punish (?P<pm>\d+)/(?P<pc>\d+), rew (?P<r>-?[\d.]+), '
    r'sweeps (?P<sw>\d+), early (?P<early>\d+), swings (?P<clicks>\d+), air swings (?P<air>\d+) \((?P<airmiss>\d+) at nothing\), '
    r'hits answered (?P<ans>\d+)/(?P<toans>\d+), combos on it (?P<two>\d+) of which a third hit (?P<three>\d+), '
    r'combos over (?P<over>\d+) of which without a swing back (?P<nosw>\d+) \((?P<sat>\d+) of them three hits or more\), '
    r'S held (?P<sheld>\d+)/(?P<sticks>\d+), chase jumps (?P<cj>\d+) of which a crit (?P<cc>\d+)'
    r'(?:, rule jumps: chase (?P<rcj>\d+) \(crit (?P<rcc>\d+)\) face (?P<rfj>\d+) \(crit (?P<rfc>\d+)\) upper (?P<ruj>\d+) \(hit (?P<ruh>\d+)\) '
    r'hit in the air (?P<rhit>\d+), uppercuts (?P<upc>\d+), far: ticks (?P<ft>\d+) still (?P<fs>\d+) flips (?P<ff>\d+) turned (?P<fd>\d+)(?:, found again (?P<ra>\d+) times in (?P<rat>\d+) ticks, in view (?P<vis>\d+)(?:, falls: crit (?P<fc>\d+) plain (?P<fp>\d+) noaim (?P<fna>\d+) nosword (?P<fns>\d+) other (?P<fo>\d+) far (?P<ffar>\d+)(?:, resets (?P<rs>\d+), step back ticks (?P<sbt>\d+), hit after its own hit (?P<ch>\d+), crits taken (?P<ct>\d+)(?:, own combos (?P<oc>\d+) of (?P<och>\d+) hits(?:, hops (?P<hops>\d+))?(?:, damage (?P<dd>\d+) dealt / (?P<dt>\d+) taken(?:, rounds (?P<rw>\d+) won (?P<rl>\d+) lost)?)?)?)?)?)?)?')
KINDS = [('own', 'own'), ('traders', 'trader'), ('crit drill 1', 'face'), ('punish held', 'punH'), ('punish free', 'punF'),
         ('combo drills', 'combo'), ('scripted opponents of earlier stages', 'older'), ('runners', 'RUNNER'),
         ('circlers', 'CIRCLER'), ('corner', 'CORNER'), ('no knockback', 'NOKB'), ('odd', 'ODD'),
         ('jump critters', 'JUMPER'), ('punishers', 'PUNISHER'), ('backers', 'BACKER'), ('timers', 'TIMER'), ('spacers', 'SPACER'), ('standers', 'STANDER'), ('whiffers', 'WHIFFER'), ('0.14.0 model', 'OLD014'), ('outside bot', 'OUTSIDE'), ('replays of lost fights', 'REPLAY')]
FALLS = re.compile(r'falls after a hit: crit (\d+), a plain hit first (\d+), opponent never under the crosshair (\d+), sword never back (\d+), other (\d+)(?:, opponent out of reach (\d+))?')
CHASE = re.compile(r'chase jumps ended: crit (\d+), another hit (\d+), under the crosshair but no swing (\d+), in reach but not under the crosshair (\d+), never in reach (\d+)')
RULES = re.compile(r'swings made by the (?:answer rule|rules) (\d+) \| hits after which the swing was kept for the way down (\d+), punish crits made (\d+)')


def parse_cell(text):
    m = CELL.search(text)
    if not m:
        return None
    d = {k: float(v) if v is not None else 0.0 for k, v in m.groupdict().items()}
    st = d['st']
    d['c'] = d['h'] * d['c'] / 100
    d['s'] = d['h'] * d['s'] / 100
    jumps = d['j'] * st / 1200.0
    d['jc'] = jumps * d['jc'] / 100
    d['j'] = jumps
    d['r'] = d['r'] * st
    return d


def parse_line(line):
    m = re.match(r'^\[(\d\d:\d\d:\d\d)\].*?\((\d+) steps in total\): (.*)$', line)
    if not m:
        return None
    out = {'time': m.group(1), 'steps': int(m.group(2)), 'cells': {}}
    body = ' | ' + m.group(3)
    for label, short in KINDS:
        k = re.search(r' \| ' + re.escape(label) + r' (.*?)(?= \| |$)', body)
        if k:
            cell = parse_cell(k.group(1))
            if cell:
                out['cells'][short] = cell
    f = FALLS.search(body)
    out['falls'] = [int(x or 0) for x in f.groups()] if f else [0] * 6
    c = CHASE.search(body)
    out['chase'] = [int(x) for x in c.groups()] if c else [0] * 5
    r = RULES.search(body)
    out['rules'] = [int(x) for x in r.groups()] if r else [0] * 3
    return out


def add(a, b):
    for k, v in b.items():
        a[k] = a.get(k, 0.0) + v


def pct(a, b):
    return 100.0 * a / b if b > 0 else 0.0


def show_kind(a, total):
    if not a or a.get('st', 0) <= 0:
        return '-'
    m = a['st'] / 1200.0
    return '%3.0f/%-3.0f c%3.0f%% pun %3.0f/%-3.0f j%3.0f/m find %4.1ft see %2.0f%% [%2.0f%%]' % (
        a['h'] / m, a['t'] / m, pct(a['c'], a['h']), a['pm'], a['pc'], a['j'] / m,
        a.get('rat', 0) / a['ra'] if a.get('ra', 0) > 0 else 0, pct(a.get('vis', 0), a['st']), pct(a['st'], total))


def show_rules(a):
    if not a or a.get('st', 0) <= 0:
        return '-'
    m = a['st'] / 1200.0
    near = a.get('fc', 0) + a.get('fp', 0) + a.get('fna', 0) + a.get('fns', 0) + a.get('fo', 0)
    return 'chase %2.0f(crit %2.0f) face %2.0f(crit %2.0f) upper %2.0f(hit %2.0f) hitInAir %2.0f upc %2.0f | sw%3.0f%% early%1.0f sat%2.0f | falls in reach %3.0f: crit %2.0f%% plain %2.0f%% noaim %2.0f%% nosword %2.0f%% other %2.0f%% (far %3.0f)' % (
        a['rcj'], a['rcc'], a['rfj'], a['rfc'], a['ruj'], a['ruh'], a['rhit'], a['upc'], pct(a['sw'], a['h']), a['early'], a['sat'],
        near, pct(a.get('fc', 0), near), pct(a.get('fp', 0), near), pct(a.get('fna', 0), near), pct(a.get('fns', 0), near),
        pct(a.get('fo', 0), near), a.get('ffar', 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--block', type=int, default=10)
    ap.add_argument('--last', type=int, default=0)
    ap.add_argument('--log', default=os.path.join(HERE, 'client.log'))
    ap.add_argument('--view', default='kinds')
    args = ap.parse_args()
    parsed = []
    with open(args.log, encoding='utf-8', errors='replace') as f:
        for line in f:
            if 'fights since the last update' in line:
                p = parse_line(line.rstrip('\n'))
                if p:
                    parsed.append(p)
    print('updates: %d; blocks of %d' % (len(parsed), args.block))
    rows = []
    for i in range(0, len(parsed), args.block):
        chunk = parsed[i:i + args.block]
        sums = {}
        falls = [0] * 6
        rules = [0] * 3
        chase = [0] * 5
        for p in chunk:
            for short, cell in p['cells'].items():
                add(sums.setdefault(short, {}), cell)
            falls = [x + y for x, y in zip(falls, p['falls'])]
            rules = [x + y for x, y in zip(rules, p['rules'])]
            chase = [x + y for x, y in zip(chase, p['chase'])]
        fight = [s for s in sums if s not in ('combo', 'older')]
        total = sum(sums[s]['st'] for s in fight)
        if total <= 0:
            continue
        head = '%s %6.0fk' % (chunk[-1]['time'], chunk[-1]['steps'] / 1000.0)
        lines = []
        if args.view in ('kinds', 'all'):
            lines.append(head + ' | ' + ' | '.join('%s %s' % (s, show_kind(sums.get(s), total))
                                                    for s in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'JUMPER', 'PUNISHER', 'BACKER', 'TIMER', 'SPACER', 'STANDER', 'WHIFFER', 'OLD014', 'face', 'older')))
        if args.view in ('rules', 'all'):
            for s in ('own', 'trader', 'RUNNER', 'CIRCLER', 'CORNER', 'NOKB', 'ODD', 'JUMPER', 'PUNISHER', 'BACKER', 'TIMER', 'SPACER', 'STANDER', 'WHIFFER', 'OLD014', 'older'):
                if sums.get(s):
                    lines.append('%s   %-7s %s' % (head, s, show_rules(sums[s])))
        if args.view in ('far', 'all'):
            allk = {}
            for s in sums:
                add(allk, sums[s])
            ft = allk.get('ft', 0)
            lines.append('%s   FAR ticks %5.0f still %3.0f%% flips/100t %4.1f deg/tick %5.2f' % (
                head, ft, pct(allk.get('fs', 0), ft), pct(allk.get('ff', 0), ft), allk.get('fd', 0) / ft if ft else 0))
        if args.view in ('falls', 'all'):
            n = sum(falls)
            near = n - falls[5]
            lines.append('%s   FALLS after a hit %4d, out of reach %3.0f%%; of the %d in reach: crit %3.0f%% plain-first %3.0f%% no-aim %3.0f%% no-sword %3.0f%% other %3.0f%% | rule swings %d kept %d punish crits %d' % (
                head, n, pct(falls[5], n), near, pct(falls[0], near), pct(falls[1], near), pct(falls[2], near), pct(falls[3], near), pct(falls[4], near), rules[0], rules[1], rules[2]))
        if args.view in ('rules', 'falls', 'all'):
            lines.append('%s   CHASE jumps ended %d: crit %d, other hit %d, pick but no swing %d, in reach no aim %d, never in reach %d' % (
                head, sum(chase), chase[0], chase[1], chase[2], chase[3], chase[4]))
        rows.append('\n'.join(lines))
    if args.last > 0:
        rows = rows[-args.last:]
    print('\n'.join(rows))


if __name__ == '__main__':
    sys.exit(main())
