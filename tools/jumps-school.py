"""jumps-school.py [LAST_N_CHECKS]: the take-offs of the school's checks (run/swordai/school/jumps.csv, written by SchoolSession
since 2 Nov 19:00), side A (the school network) against side B (the installed model) on the same pairs, by the distance at the
take-off: how many; hit while still rising (the knock-away); hit while falling; its own hit first, and how many of those were
crits; and the median distance at the landing. The same table as tools/jumpins.py gives for the user's real duels.
Only take-offs moving toward the bot (closing by more than 0.05 a tick) count as jump-ins. Default: every check in the file."""
import csv
import sys

PATH = 'D:/swordai/run/swordai/school/jumps.csv'
BINS = [(0, 3), (3, 4), (4, 5), (5, 6), (6, 8)]
rows = list(csv.DictReader(open(PATH)))
# A check is a run of rows whose pair numbers do not go down by more than a few; split where they restart.
checks, cur, last = [], [], -1
for r in rows:
    p = int(r['pair'])
    if cur and p < last - 5:
        checks.append(cur)
        cur = []
    cur.append(r)
    last = p
if cur:
    checks.append(cur)
n = int(sys.argv[1]) if len(sys.argv) > 1 else len(checks)
use = [r for c in checks[-n:] for r in c]
print('%d checks, %d take-offs; a jump-in = toward the bot by more than 0.05 a tick' % (min(n, len(checks)), len(use)))
for side in ('A', 'B'):
    print('side %s  take-off at   n   hit rising   hit falling   own hit first (crits)   landed at (median)' % side)
    for a, b in BINS:
        s = [r for r in use if r['side'] == side and float(r['toward']) > 0.05 and a <= float(r['takeOff']) < b]
        if not s:
            continue
        k = len(s)
        rise = sum(1 for r in s if int(r['hitRisingAt']) > 0)
        fall = sum(1 for r in s if int(r['hitFallingAt']) > 0)
        own = sum(1 for r in s if int(r['ownHitAt']) > 0)
        crit = sum(1 for r in s if int(r['ownHitAt']) > 0 and r['ownCrit'] == '1')
        land = sorted(float(r['landed']) for r in s if float(r['landed']) >= 0)
        med = land[len(land) // 2] if land else float('nan')
        print('        %3.0f-%-3.0f %6d  %5d %4.0f%%  %5d %4.0f%%   %5d %4.0f%% (%d)        %5.2f' % (
            a, b, k, rise, 100 * rise / k, fall, 100 * fall / k, own, 100 * own / k, crit, med))
