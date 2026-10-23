"""Jump-in drill traces (23 Oct): python drilltr.py SINCE_HHMMSS [UNTIL_HHMMSS]. Per side (rules ON = clash/holdcrit labels
seen or not; a fight is A if any of jumpin:9 / holdcrit / p- appears, which only the rules make), how the exchange went:
first event (dealt/taken/same), crits each way, damage; and for clash jumps: distance at take-off, ticks from take-off to
the crit taken, to its own hit, own state when hit (rising/falling)."""
import glob
import gzip
import os
import sys
from collections import Counter, defaultdict

D = 'D:/swordai/run/swordai/traces'
since = sys.argv[1].replace(':', '') if len(sys.argv) > 1 else '000000'
until = sys.argv[2].replace(':', '') if len(sys.argv) > 2 else '999999'


def rows(path):
    out = []
    with gzip.open(path, 'rt', encoding='utf-8', errors='replace') as f:
        for line in f:
            p = [x.strip() for x in line.split('|')]
            if len(p) < 10 or not p[0].isdigit():
                continue
            out.append(p)
    return out


stats = defaultdict(Counter)
dmg = defaultdict(lambda: [0.0, 0.0, 0])
clash_d = []
clash_hit_tick = Counter()
clash_own = Counter()
for path in sorted(glob.glob(os.path.join(D, '*-jump-in-*.txt.gz'))):
    stamp = os.path.basename(path).split('-')[1]
    if not (since <= stamp <= until):
        continue
    r = rows(path)
    if not r:
        continue
    side = 'ON' if any(('jumpin:9' in x[6]) or ('holdcrit' in x[6]) or ('p-' in x[6]) for x in r) else 'OFF'
    if side == 'OFF' and any('meet' in x[6] for x in r):
        pass
    first_dealt = next((int(x[0]) for x in r if 'HIT' in x[8]), None)
    first_taken = next((int(x[0]) for x in r if 'TAKEN' in x[8]), None)
    crit_dealt = sum(1 for x in r if 'HIT CRIT' in x[8])
    crit_taken = sum(1 for x in r if 'TAKEN CRIT' in x[8])
    s = stats[side]
    s['fights'] += 1
    if first_dealt is None and first_taken is None:
        s['nothing'] += 1
    elif first_taken is None:
        s['hit only'] += 1
    elif first_dealt is None:
        s['taken only'] += 1
    elif first_dealt < first_taken:
        s['hit first'] += 1
    elif first_dealt > first_taken:
        s['taken first, hit back'] += 1
    else:
        s['same tick'] += 1
    s['crits dealt'] += crit_dealt
    s['crits taken'] += crit_taken
    s['meet ticks'] += sum(1 for x in r if 'meet' in x[6])
    dd, tt = r[-1][9].split('/')
    dmg[side][0] += float(dd)
    dmg[side][1] += float(tt)
    dmg[side][2] += 1
    # Clash jumps.
    for i, x in enumerate(r):
        if x[5].endswith('J.') or 'J' in x[5][3:4]:
            pass
        if 'jumpin:9' in x[6] and (i == 0 or 'jumpin:9' not in r[i - 1][6]):
            t0 = int(x[0])
            try:
                clash_d.append(float(x[1]))
            except ValueError:
                pass
            tk = next((int(y[0]) for y in r[i:] if 'TAKEN' in y[8]), None)
            hk = next((int(y[0]) for y in r[i:] if 'HIT' in y[8]), None)
            clash_hit_tick['taken +%s' % (tk - t0 if tk is not None else '-')] += 1
            clash_own['own hit +%s' % (hk - t0 if hk is not None else '-')] += 1

for side in ('ON', 'OFF'):
    s = stats[side]
    n = max(1, s['fights'])
    d = dmg[side]
    print('%s: %d fights, dealt %.1f taken %.1f a fight (net %+.1f)' % (side, s['fights'], d[0] / n, d[1] / n, (d[0] - d[1]) / n))
    print('   ' + ', '.join('%s %d' % (k, v) for k, v in s.items() if k != 'fights'))
if clash_d:
    clash_d.sort()
    print('clash take-off distance: n %d, 10%% %.2f, median %.2f, 90%% %.2f' % (len(clash_d), clash_d[len(clash_d) // 10], clash_d[len(clash_d) // 2], clash_d[9 * len(clash_d) // 10]))
    print('   ' + ', '.join('%s: %d' % kv for kv in sorted(clash_hit_tick.items(), key=lambda kv: -kv[1])[:12]))
    print('   ' + ', '.join('%s: %d' % kv for kv in sorted(clash_own.items(), key=lambda kv: -kv[1])[:12]))
