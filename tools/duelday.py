"""duelday.py [FROM yyyymmdd[-hhmm]] [TO ...]: the user's real duels split by A/B arm, in one table, with the decision of
docs/DUEL-DAY.md at the bottom. Read only; opponents' names are never printed.

Reads the round files the mod writes (profiles/DRL AI/swordai/fights/*.txt.gz). Since 13 Nov 02:50 the first line names the
A/B arm ("SwordAI round, <time>, A/B arm <tag>"); older files show as "no tag". A round is won or lost; "open" (the duel was
stopped or the opponent changed mid-round) is counted apart and left out of the rounds won.

Per arm, per round (a round = full health to a death):
  rounds won %        won / (won + lost), with its standard error
  hits dealt / taken hits a round (the client cannot see the opponent's health)
  3+ combos taken     the opponent's combos of 3 or more hits in a row (the recorder's count)
  their combo 2->3    after their 2nd hit in a row: the share where THEIR hit came next (lower is better), and the share
                      where it swung within 12 ticks (swing-back, higher is better)
  punish crit         its crit within 18 ticks of being thrown up by a hit from the ground (B1), over those chances; 50-65%
  crit-back           after its punish crit, hit within 30 ticks (and likely critted) by the opponent (lower is better)
  late jump-in hits   its hits on an opponent's jump-in (take-off toward it within 8 blocks, not lifted by its hit, not in its
                      own combo) at air tick 2 or later, counted from the ground flag (the user's rule: never; 0 is the bar)
"""
import glob
import gzip
import math
import os
import sys

BASE = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
lo = sys.argv[1].replace('-', '') if len(sys.argv) > 1 else '0'
hi = sys.argv[2].replace('-', '') if len(sys.argv) > 2 else '9'
PUNISH_WINDOW = 18
CRITBACK_WINDOW = 30
SWINGBACK_WINDOW = 12


def read(path):
    """The arm tag, the result, the summary numbers and the timeline rows of one round file."""
    arm = 'no tag'
    combos3 = 0
    dmg = (0.0, 0.0)
    rows = []
    ix = None
    with gzip.open(path, 'rt', encoding='utf-8', errors='replace') as f:
        for n, line in enumerate(f):
            line = line.rstrip('\n')
            if n == 0:
                if ', A/B arm ' in line:
                    arm = line.split(', A/B arm ', 1)[1].strip()
                continue
            if n == 1 and ', hits ' in line:
                # Hits, not damage: the client cannot see the opponent's health (damage dealt is always 0).
                try:
                    d = line.split(', hits ', 1)[1].split(' dealt / ')
                    dmg = (float(d[0]), float(d[1].split()[0]))
                except (ValueError, IndexError):
                    pass
                continue
            if 'combos of 3+ taken ' in line:
                try:
                    combos3 = int(line.split('combos of 3+ taken ', 1)[1].split(',')[0])
                except ValueError:
                    pass
                continue
            if line.startswith('tick,dist,'):
                ix = {k: i for i, k in enumerate(line.split(','))}
                continue
            if ix is None or not line[:1].isdigit():
                continue
            p = line.split(',', len(ix) - 1)
            if len(p) < len(ix):
                continue
            try:
                rows.append(dict(dist=float(p[ix['dist']]), me=p[ix['me']], opp=p[ix['opp']], oppUp=float(p[ix['oppUp']]),
                                 oppToward=float(p[ix['oppToward']]), ev=p[ix['event']]))
            except (ValueError, KeyError):
                continue
    result = os.path.basename(path)[:-len('.txt.gz')].rsplit('-', 1)[-1]
    return arm, result, combos3, dmg, rows


def timeline_counts(r):
    c = dict(two=0, three=0, own_next=0, swung=0, chances=0, pcrit=0, back=0, back_crit=0, jumpins=0, late=0)
    row = 0  # their hits in a row, no hit of its own between
    last_dealt = -1000
    for i, t in enumerate(r):
        ev = t['ev']
        dealt = 'DEALT' in ev
        taken = 'TAKEN' in ev
        if dealt:
            row = 0
            last_dealt = i
        if taken:
            row += 1
            if row == 2:
                c['two'] += 1
                nxt = None
                for j in range(i + 1, min(len(r), i + 41)):
                    e = r[j]['ev']
                    if 'DEALT' in e:
                        nxt = 'own'
                        break
                    if 'TAKEN' in e:
                        nxt = 'their'
                        break
                if nxt == 'their':
                    c['three'] += 1
                elif nxt == 'own':
                    c['own_next'] += 1
                if any('swing:' in r[j]['ev'] for j in range(i + 1, min(len(r), i + 1 + SWINGBACK_WINDOW))):
                    c['swung'] += 1
            # A punish-crit chance (as Habits and the training counter count it): thrown up by their hit from the ground.
            if i > 0 and r[i - 1]['me'].startswith('g') and any(x['me'].startswith('a') for x in r[i:i + 4]):
                c['chances'] += 1
                for j in range(i + 1, min(len(r), i + 1 + PUNISH_WINDOW)):
                    e = r[j]['ev']
                    if 'swing:crit' in e:
                        c['pcrit'] += 1
                        for k in range(j + 1, min(len(r), j + 1 + CRITBACK_WINDOW)):
                            if 'TAKEN' in r[k]['ev']:
                                c['back'] += 1
                                if 'crit' in r[k]['ev'].split('TAKEN', 1)[1]:
                                    c['back_crit'] += 1
                                break
                        break
                    if 'TAKEN' in e:
                        break
        # The opponent's jump (any direction, memory swordai-feedback-jump-in-ticks): their ground flag goes g -> a within 8
        # blocks, not lifted by its hit, not in its own combo. Late = its swing that connected (not a miss) at ground tick 2 or
        # later, the jumper still in the air, before their hit lands. The swing tick counts, not the hit's echo (DEALT lags).
        if i > 0 and r[i - 1]['opp'].startswith('g') and t['opp'].startswith('a'):
            ahead = r[i:i + 4]
            if t['dist'] < 8 and max(x['oppUp'] for x in ahead) > 0.1 and i - last_dealt > 20:
                c['jumpins'] += 1
                for j in range(i, min(len(r), i + 30)):
                    e = r[j]['ev']
                    if j > i and (r[j]['opp'].startswith('g') or 'TAKEN' in e):
                        break
                    if 'swing:' in e and 'swing:miss' not in e:
                        if j - i >= 2:
                            c['late'] += 1
                        break
    return c


def se_p(k, n):
    return math.sqrt(max(k * (n - k), 1) / n ** 3) if n else float('nan')


arms = {}
for path in sorted(glob.glob(BASE + '*.txt.gz')):
    stamp = os.path.basename(path)[:13].replace('-', '')
    if not (lo <= stamp[:len(lo)] and stamp[:len(hi)] <= hi):
        continue
    arm, result, combos3, dmg, rows = read(path)
    a = arms.setdefault(arm, dict(won=0, lost=0, open=0, dealt=0.0, taken=0.0, c3=[], tl=None))
    if result not in ('won', 'lost'):
        a['open'] += 1
        continue
    a[result] += 1
    a['dealt'] += dmg[0]
    a['taken'] += dmg[1]
    a['c3'].append(combos3)
    c = timeline_counts(rows)
    if a['tl'] is None:
        a['tl'] = c
    else:
        for k in c:
            a['tl'][k] += c[k]

if not arms:
    print('no round files between %s and %s' % (lo, hi))
    sys.exit(0)

print('Real rounds by A/B arm (%s to %s); a round = full health to a death' % (lo, hi))
print('%-12s %6s %14s %13s %15s %15s %13s %13s %10s %10s' % ('arm', 'rounds', 'won %', 'hits dlt/tkn', '3+ combos/rnd',
                                                              'their 2->3 %', 'swing-back %', 'punish crit', 'crit-back', 'late JI'))
summ = {}
for arm in sorted(arms):
    a = arms[arm]
    n = a['won'] + a['lost']
    if not n:
        print('%-12s %6d  (only %d open rounds)' % (arm, 0, a['open']))
        continue
    t = a['tl']
    c3 = a['c3']
    m3 = sum(c3) / n
    s3 = math.sqrt(sum((x - m3) ** 2 for x in c3) / max(n - 1, 1) / n)
    s = dict(n=n, win=a['won'] / n, win_se=se_p(a['won'], n), c3=m3, c3_se=s3,
             three=t['three'] / t['two'] if t['two'] else float('nan'), three_se=se_p(t['three'], t['two']),
             swung=t['swung'] / t['two'] if t['two'] else float('nan'), swung_se=se_p(t['swung'], t['two']),
             pcrit=t['pcrit'] / t['chances'] if t['chances'] else float('nan'),
             back=t['back'] / t['pcrit'] if t['pcrit'] else float('nan'), back_se=se_p(t['back'], t['pcrit']),
             late=t['late'], jumpins=t['jumpins'])
    summ[arm] = s
    print('%-12s %6d %7.0f%% (%2.0f) %6.1f / %4.1f %8.2f (%.2f) %8.0f%% (%2.0f) %8.0f%% (%2.0f) %5.0f%% of %3d %5.0f%% (%d%% crit) %4d/%-4d%s' % (
        arm, n, 100 * s['win'], 100 * s['win_se'], a['dealt'] / n, a['taken'] / n, m3, s3, 100 * s['three'], 100 * s['three_se'],
        100 * s['swung'], 100 * s['swung_se'], 100 * s['pcrit'], t['chances'], 100 * s['back'],
        round(100 * t['back_crit'] / t['pcrit']) if t['pcrit'] else 0, t['late'], t['jumpins'],
        '' if not a['open'] else '   (+%d open)' % a['open']))

# The decision (docs/DUEL-DAY.md): the candidate against the installed arm.
ref = summ.get('installed')
cands = [k for k in summ if k not in ('installed', 'no tag')]
if ref is None or not cands:
    print('\nDecision: needs rounds tagged "installed" and a candidate arm (files before 13 Nov 02:50 carry no tag).')
    sys.exit(0)
for arm in cands:
    s = summ[arm]
    d = s['win'] - ref['win']
    dse = math.hypot(s['win_se'], ref['win_se'])
    worse = []

    def check(name, cand, refv, se, higher_bad, hard):
        if any(map(math.isnan, (cand, refv))):
            return
        gap = (cand - refv) if higher_bad else (refv - cand)
        if gap > (2 if hard else 1) * se:
            worse.append(name + (' (HARD)' if hard else ''))

    check('3+ combos taken', s['c3'], ref['c3'], math.hypot(s['c3_se'], ref['c3_se']), True, True)
    check('their 3rd hit after their 2nd', s['three'], ref['three'], math.hypot(s['three_se'], ref['three_se']), True, True)
    check('swing-back in their combo', s['swung'], ref['swung'], math.hypot(s['swung_se'], ref['swung_se']), False, False)
    check('crit-back after its punish crit', s['back'], ref['back'], math.hypot(s['back_se'], ref['back_se']), True, False)
    if s['late'] > ref['late'] * s['n'] / ref['n'] + 1:
        worse.append('late jump-in hits (HARD)')
    pc = s['pcrit']
    pc_note = '' if math.isnan(pc) or 0.45 <= pc <= 0.70 else '; punish crit %.0f%% is outside 50-65%%' % (100 * pc)
    print('\n%s vs installed: rounds won %+.0f points (se %.0f) over %d / %d rounds%s' % (arm, 100 * d, 100 * dse, s['n'], ref['n'], pc_note))
    if worse:
        print('  worse: ' + ', '.join(worse))
    short = 60 - min(s['n'], ref['n'])
    if any('HARD' in w for w in worse) or d <= -0.05 and s['n'] >= 60 and ref['n'] >= 60:
        verdict = 'NO: keep the installed model'
    elif short > 0:
        verdict = 'NOT ENOUGH ROUNDS: play about %d more rounds per arm' % short
    elif d >= 0.05 and not worse:
        verdict = 'YES: %s is better (install only with the user\'s word)' % arm
    else:
        verdict = 'TIE: keep the installed model'
    print('  -> ' + verdict)
