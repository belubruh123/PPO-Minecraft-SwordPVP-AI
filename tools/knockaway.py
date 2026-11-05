"""knockaway.py [FROM_DATE]: the opponents' jump-ins at the AI in the user's live duels, sorted by what the AI did while
they were in the air (hit them rising, hit them falling, or no hit), and what followed in the next WINDOW ticks after the
take-off: the AI's hits and theirs, their crits, and whether the distance grew (a knock-away). Reads the per-duel timelines
(profiles/DRL AI/swordai/fights/*.txt.gz). Names are never printed."""
import collections
import glob
import gzip
import sys

base = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
lo = sys.argv[1] if len(sys.argv) > 1 else '00000000'
RISING = 5
WINDOW = 30


def rows(path):
    out = []
    with gzip.open(path, 'rt', errors='replace') as f:
        for line in f:
            p = line.rstrip('\n').split(',')
            if len(p) < 15 or not p[0].isdigit():
                continue
            try:
                out.append(dict(t=int(p[0]), dist=float(p[1]), opp=p[7], oppUp=float(p[8]), oppToward=float(p[9]), ev=p[14]))
            except ValueError:
                continue
    return out


stats = collections.defaultdict(lambda: collections.Counter())
files = sorted(f for f in glob.glob(base + '*.txt.gz') if (''.join(c for c in f[-30:] if c.isdigit())[:8] or '0') >= lo)
duels = 0
for path in files:
    r = rows(path)
    if len(r) < 20:
        continue
    duels += 1
    for i in range(1, len(r) - 1):
        a, b = r[i - 1], r[i]
        if not (a['opp'].startswith('g') and b['opp'].startswith('a')):
            continue
        ahead = r[i:i + 4]
        recent = r[max(0, i - 3):i + 1]
        if max(x['oppUp'] for x in ahead) <= 0.1 or any('DEALT' in x['ev'] for x in recent) or b['oppToward'] < 0.1:
            continue
        if not 2.0 <= b['dist'] < 6.0:
            continue
        # While they are in the air: the AI's first hit and when (ticks since the take-off), before any hit of theirs.
        kind, hit_at, theirs_first = 'no hit in the air', None, False
        for j in range(i, min(len(r), i + 16)):
            if r[j]['opp'].startswith('g') and j > i + 1:
                break
            if 'TAKEN' in r[j]['ev'] and hit_at is None:
                theirs_first = True
                break
            if 'DEALT' in r[j]['ev']:
                hit_at = j - i
                kind = 'hit rising (tick %d or less)' % RISING if hit_at <= RISING else 'hit falling'
                break
        if theirs_first:
            kind = 'they hit first'
        s = stats[kind]
        s['n'] += 1
        end = min(len(r), i + WINDOW)
        dealt = sum(1 for x in r[i:end] if 'DEALT' in x['ev'])
        taken = sum(1 for x in r[i:end] if 'TAKEN' in x['ev'])
        crits = sum(1 for x in r[i:end] if 'TAKEN:crit' in x['ev'])
        s['dealt'] += dealt
        s['taken'] += taken
        s['crits taken'] += crits
        if hit_at is not None:
            # After its hit: did they hit back within 12 ticks (the knock-away failed), and did the distance grow by 1.5+?
            k = i + hit_at
            back = any('TAKEN' in x['ev'] for x in r[k + 1:k + 13])
            s['hit back within 12'] += back
            far = max((x['dist'] for x in r[k:k + 10]), default=0) - r[k]['dist']
            s['pushed 1.5+ and no hit back'] += (far >= 1.5 and not back)

print('%d duels from %s; their jump-ins from 2-6 blocks; the %d ticks after the take-off' % (duels, lo, WINDOW))
print('  %-26s %5s  %13s %13s %11s  %18s  %15s' % ('what the AI did', 'n', 'its hits', 'their hits', 'their crits',
                                                   'they hit back <12t', 'real knock-away'))
for kind in ('hit rising (tick %d or less)' % RISING, 'hit falling', 'no hit in the air', 'they hit first'):
    s = stats[kind]
    n = s['n']
    if not n:
        continue
    hb = '%d (%.0f%%)' % (s['hit back within 12'], 100 * s['hit back within 12'] / n) if 'hit' in kind and 'first' not in kind else '-'
    ka = '%d (%.0f%%)' % (s['pushed 1.5+ and no hit back'], 100 * s['pushed 1.5+ and no hit back'] / n) if 'hit' in kind and 'first' not in kind else '-'
    print('  %-26s %5d  %6.2f a jump %6.2f a jump %6.2f a jump  %18s  %15s' % (kind, n, s['dealt'] / n, s['taken'] / n,
                                                                           s['crits taken'] / n, hb, ka))
