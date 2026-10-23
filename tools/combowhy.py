"""combowhy.py LABEL WHO: for the combopair runs of one side (cand/inst), each 2nd+ hit of a combo: opponent kind, whether
another hit followed within 20 ticks (went on), and the fighter's keys and distance in the 12 ticks after."""
import collections, datetime, glob, gzip, os, re, sys
label, who = sys.argv[1], sys.argv[2]
DMG = re.compile(r'(TAKEN|HIT)( CRIT| sprint)? ([\d.]+)')
day = datetime.date.today()
def at(h): return datetime.datetime.combine(day, datetime.time(*map(int, h.split(':'))))
wins = [(at(s), at(e)) for f, s, e in (l.split() for l in open('fp2-%s-windows.txt' % label)) if '-%s-' % who in f]
by = collections.defaultdict(collections.Counter)
ex = []
for p in glob.glob(r'D:/swordai/run/swordai/traces/*.txt.gz'):
    b = os.path.basename(p)
    t = datetime.datetime.strptime('%d%s' % (day.year, b[:11]), '%Y%m%d-%H%M%S')
    if not any(a <= t <= e + datetime.timedelta(seconds=5) for a, e in wins):
        continue
    kind = b.split('-')[2]
    rows = []
    for line in gzip.open(p, 'rt', errors='replace'):
        q = line.split('|')
        if len(q) >= 10 and q[0].strip().isdigit():
            rows.append(q)
    row = []
    for i, q in enumerate(rows):
        ev = q[8]
        if 'HIT' in ev:
            # Its own hit, a trade on the same tick too: no combo of the opponent's goes on through it.
            row = []
        if 'TAKEN' in ev:
            tick = int(q[0])
            if row and tick - row[-1] > 20:
                row = []
            row.append(tick)
            if len(row) >= 2:
                nxt = next((r for r in rows[i + 1:] if 'TAKEN' in r[8] or 'HIT' in r[8]), None)
                on = nxt is not None and 'TAKEN' in nxt[8] and 'HIT' not in nxt[8] and int(nxt[0]) - tick <= 20
                c = by[kind]
                c['n'] += 1; c['on'] += on
                after = rows[i:i + 12]
                c['aside'] += sum('aside' in r[6] for r in after)
                c['W'] += sum(r[5].strip()[:1] == 'W' for r in after)
                c['S'] += sum(r[5].strip()[:1] == 'S' for r in after)
                c['ticks'] += len(after)
                if on and len(ex) < 6 and 'aside' in q[6]:
                    ex.append('%s %s\n' % (b, ''.join('    %s|%s|%s|%s|%s\n' % (r[0], r[1], r[5], r[6].strip(), r[8].strip()) for r in rows[i:i + 1 + int(nxt[0]) - int(q[0])])))
for k, c in sorted(by.items(), key=lambda kv: -kv[1]['n']):
    t = max(1, c['ticks'])
    print('%-10s 2nd+ hits %3d, went on %3.0f%% | next 12 ticks: rule A/D %3.0f%%, W %3.0f%%, S %3.0f%%' % (k, c['n'], 100.0 * c['on'] / c['n'], 100.0 * c['aside'] / t, 100.0 * c['W'] / t, 100.0 * c['S'] / t))
if '-v' in sys.argv:
    print(''.join(ex))
