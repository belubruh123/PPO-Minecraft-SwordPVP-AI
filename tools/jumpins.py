"""jumpins.py [FROM_DATE] [TO_DATE]: jump-ins in the user's live duels, from the per-duel timelines the mod keeps
(profiles/DRL AI/swordai/fights/*.txt.gz, one line a tick: tick,dist,mySword,oppSword,myHp,oppHp,me,opp,oppUp,oppToward,oppSide,
myToward,keys,rules,event). Opponents' names are never printed.

A jump-in is a take-off (ground to air, not lifted by a hit) toward the other while within 8 blocks. For the AI's own and for the
opponents', by the distance at the take-off: how many; how many were hit while still rising (the first 5 ticks of the jump: the
knock-away, T8 in docs/PVP-TECHNIQUES.md); how many were hit later in the air; how many dealt their hit first; and where they
landed. For the AI's own, also the share pressed by the installed model's jump-in rule (rules column J) and the swing kind."""
import collections
import glob
import gzip
import sys

base = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
lo = sys.argv[1] if len(sys.argv) > 1 else '00000000'
hi = sys.argv[2] if len(sys.argv) > 2 else '99999999'
RISING = 5
BINS = [(0, 3), (3, 4), (4, 5), (5, 6), (6, 7), (7, 8)]


def rows(path):
    out = []
    with gzip.open(path, 'rt', errors='replace') as f:
        for line in f:
            p = line.rstrip('\n').split(',')
            if len(p) < 15 or not p[0].isdigit():
                continue
            try:
                out.append(dict(t=int(p[0]), dist=float(p[1]), me=p[6], opp=p[7], oppUp=float(p[8]),
                                oppToward=float(p[9]), myToward=float(p[11]), keys=p[12], rules=p[13], ev=p[14]))
            except ValueError:
                continue
    return out


class Tally:
    def __init__(self):
        self.n = collections.Counter()
        self.rise = collections.Counter()
        self.air = collections.Counter()
        self.first = collections.Counter()
        self.land = collections.defaultdict(list)
        self.rule = collections.Counter()
        self.crit = collections.Counter()

    def line(self, name):
        print(name)
        print('  take-off at   n   hit rising   hit later   own hit first   landed at (median)   by the rule J   swung a crit')
        tot = [0, 0, 0, 0, 0, 0]
        for a, b in BINS:
            k = (a, b)
            n = self.n[k]
            if not n:
                continue
            ls = sorted(self.land[k])
            med = ls[len(ls) // 2] if ls else float('nan')
            print('  %3.0f-%-3.0f %6d  %5d %4.0f%%  %5d %4.0f%%  %6d %4.0f%%      %5.2f            %4.0f%%          %4.0f%%' % (
                a, b, n, self.rise[k], 100 * self.rise[k] / n, self.air[k], 100 * self.air[k] / n,
                self.first[k], 100 * self.first[k] / n, med, 100 * self.rule[k] / n, 100 * self.crit[k] / n))
            for i, v in enumerate((n, self.rise[k], self.air[k], self.first[k], self.rule[k], self.crit[k])):
                tot[i] += v
        if tot[0]:
            print('  all     %6d  %5d %4.0f%%  %5d %4.0f%%  %6d %4.0f%%                       %4.0f%%          %4.0f%%' % (
                tot[0], tot[1], 100 * tot[1] / tot[0], tot[2], 100 * tot[2] / tot[0], tot[3], 100 * tot[3] / tot[0],
                100 * tot[4] / tot[0], 100 * tot[5] / tot[0]))


def binof(d):
    for a, b in BINS:
        if a <= d < b:
            return (a, b)
    return None


mine, theirs = Tally(), Tally()
duels = 0
files = sorted(f for f in glob.glob(base + '*.txt.gz') if lo <= (''.join(c for c in f[-30:] if c.isdigit())[:8] or '0') <= hi)
for path in files:
    r = rows(path)
    if len(r) < 20:
        continue
    duels += 1
    for i in range(1, len(r) - 1):
        a, b = r[i - 1], r[i]
        recent = r[max(0, i - 3):i + 1]
        for who in ('me', 'opp'):
            was, now = a[who], b[who]
            if not (was.startswith('g') and now.startswith('a')):
                continue
            if who == 'me':
                if 'j' not in a['keys'] and 'j' not in b['keys']:
                    continue
                if any('TAKEN' in x['ev'] for x in recent) or b['myToward'] < 0.1:
                    continue
                tally, hitme, hitthem = mine, 'TAKEN', 'DEALT'
            else:
                # Seen from a client their rise shows a tick or two after the ground flag changes (oppUp is 0 or less on it).
                ahead = r[i:i + 4]
                if max(x['oppUp'] for x in ahead) <= 0.1 or any('DEALT' in x['ev'] for x in recent) or b['oppToward'] < 0.1:
                    continue
                tally, hitme, hitthem = theirs, 'DEALT', 'TAKEN'
            k = binof(b['dist'])
            if k is None:
                continue
            tally.n[k] += 1
            if who == 'me' and 'J' in b['rules']:
                tally.rule[k] += 1
            landed = None
            swing = None
            for j in range(i, min(len(r), i + 16)):
                c = r[j]
                since = j - i
                if who == 'me' and swing is None and 'swing:' in c['ev']:
                    swing = c['ev'].split('swing:')[1].split()[0]
                    if swing == 'crit':
                        tally.crit[k] += 1
                # hitme: the jumper is hit; hitthem: the jumper hits first.
                if hitme in c['ev'] and c[who].startswith('a'):
                    if since < RISING:
                        tally.rise[k] += 1
                    else:
                        tally.air[k] += 1
                    break
                if hitthem in c['ev']:
                    tally.first[k] += 1
                    break
                if since > 0 and c[who].startswith('g'):
                    landed = c['dist']
                    break
            if landed is not None:
                tally.land[k].append(landed)

print('%d duels (%s to %s); a jump-in = take-off toward the other within 8 blocks, not lifted by a hit' % (duels, lo, hi))
mine.line("THE AI'S OWN jump-ins (hit rising = knocked away while going up; own hit first = it hit before being hit)")
theirs.line("THE OPPONENTS' jump-ins at the AI (hit rising = the AI knocked them away while going up)")

# The opening: each round's first approach, up to its first hit. Who jumped in, from how far, and who hit first.
openings = collections.Counter()
for path in files:
    r = rows(path)
    if len(r) < 20:
        continue
    my_off = their_off = None
    first = None
    for i in range(1, len(r)):
        a, b = r[i - 1], r[i]
        if 'DEALT' in b['ev'] or 'TAKEN' in b['ev']:
            first = 'AI' if 'DEALT' in b['ev'] and 'TAKEN' not in b['ev'] else 'them' if 'TAKEN' in b['ev'] and 'DEALT' not in b['ev'] else 'both'
            break
        if my_off is None and a['me'].startswith('g') and b['me'].startswith('a') and b['dist'] < 9:
            my_off = b['dist']
        if their_off is None and a['opp'].startswith('g') and b['opp'].startswith('a') and b['dist'] < 9:
            their_off = b['dist']
    if first is None:
        continue
    mine_k = 'AI no jump' if my_off is None else 'AI jumped from <4' if my_off < 4 else 'AI jumped from 4-6' if my_off < 6 else 'AI jumped from 6+'
    theirs_k = 'they jumped' if their_off is not None else 'they did not'
    openings[(mine_k, theirs_k, first)] += 1
print("THE OPENING of each round (first approach up to the first hit): who hit first")
for mk in ('AI no jump', 'AI jumped from <4', 'AI jumped from 4-6', 'AI jumped from 6+'):
    for tk in ('they jumped', 'they did not'):
        c = {f: openings[(mk, tk, f)] for f in ('AI', 'them', 'both')}
        n = sum(c.values())
        if n:
            print('  %-20s %-13s %4d rounds: AI first %3d (%3.0f%%), them first %3d (%3.0f%%), same tick %d' % (
                mk, tk, n, c['AI'], 100 * c['AI'] / n, c['them'], 100 * c['them'] / n, c['both']))
