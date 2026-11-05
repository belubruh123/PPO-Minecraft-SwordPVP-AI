"""kbtick.py: like knockaway.py, but the AI's hit on a jumper bucketed by the tick after their take-off (as the AI saw it),
with the HP the AI lost in the 30 ticks after the take-off. Names never printed."""
import collections, glob, gzip, sys
base = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
WINDOW = 30
def rows(path):
    out = []
    with gzip.open(path, 'rt', errors='replace') as f:
        for line in f:
            p = line.rstrip('\n').split(',')
            if len(p) < 15 or not p[0].isdigit():
                continue
            try:
                out.append(dict(t=int(p[0]), dist=float(p[1]), hp=float(p[4]), opp=p[7], oppUp=float(p[8]), oppToward=float(p[9]), ev=p[14]))
            except ValueError:
                continue
    return out
st = collections.defaultdict(collections.Counter)
for path in sorted(glob.glob(base + '*.txt.gz')):
    r = rows(path)
    if len(r) < 20:
        continue
    for i in range(1, len(r) - 1):
        a, b = r[i - 1], r[i]
        if not (a['opp'].startswith('g') and b['opp'].startswith('a')):
            continue
        if max(x['oppUp'] for x in r[i:i + 4]) <= 0.1 or any('DEALT' in x['ev'] for x in r[max(0, i - 3):i + 1]) or b['oppToward'] < 0.1:
            continue
        if not 2.0 <= b['dist'] < 6.0:
            continue
        key = 'no hit in the air'
        for j in range(i, min(len(r), i + 16)):
            if r[j]['opp'].startswith('g') and j > i + 1:
                break
            if 'TAKEN' in r[j]['ev']:
                key = 'they hit first'
                break
            if 'DEALT' in r[j]['ev']:
                sw = max((k for k in range(max(i - 4, j - 6), j + 1) if 'swing' in r[k]['ev']), default=None)
                if sw is None:
                    key = 'hit, swing not found'
                    break
                h = sw - i
                key = 'swing at tick %s' % (str(h) if -2 <= h <= 6 else ('<-2' if h < -2 else '7-15'))
                break
        end = min(len(r), i + WINDOW)
        lost = sum(max(0.0, r[k - 1]['hp'] - r[k]['hp']) for k in range(i + 1, end))
        s = st[key]
        s['n'] += 1
        s['dealt'] += sum(1 for x in r[i:end] if 'DEALT' in x['ev'])
        s['taken'] += sum(1 for x in r[i:end] if 'TAKEN' in x['ev'])
        s['crits'] += sum(1 for x in r[i:end] if 'TAKEN:crit' in x['ev'])
        s['lost'] += lost
print('%-18s %5s %9s %9s %9s %10s' % ('what the AI did', 'n', 'its hits', 'theirs', 'crits', 'HP lost'))
for k in ['swing at tick <-2'] + ['swing at tick %d' % t for t in range(-2, 7)] + ['swing at tick 7-15', 'hit, swing not found', 'no hit in the air', 'they hit first']:
    s = st[k]
    if s['n']:
        n = s['n']
        print('%-18s %5d %9.2f %9.2f %9.2f %10.2f' % (k, n, s['dealt'] / n, s['taken'] / n, s['crits'] / n, s['lost'] / n))
