"""bench-rounds.py TAG_C TAG_R: rounds won on the benchmark, C against R paired by episode (the same seed).
Every bench episode starts at full health and ends when one side dies (the user, 9 Nov: "full heart and fight until one
is dead when testing and benchmarking"); bots = the 300 bot rounds, h2h = the 60 rounds against the league network.
z = (only C won - only R won) / sqrt(both counts): about 2 or more is beyond chance."""
import gzip, os, sys, math
B = r"D:\swordai\run\swordai\bench"
def res(tag):
    out = {}
    for f in os.listdir(os.path.join(B, tag)):
        if not f.endswith('.txt.gz'): continue
        with gzip.open(os.path.join(B, tag, f), 'rt', encoding='utf8', errors='replace') as g:
            g.readline(); w = g.readline().split(' ')[0]
        out[f[:4]] = (f[5:-7], w == 'WON', w)
    return out
c, r = sys.argv[1], sys.argv[2]
C, R = res(c), res(r)
for part in ('bots', 'h2h'):
    keys = [k for k in C if k in R and ((C[k][0] == 'h2h') == (part == 'h2h'))]
    cw = sum(C[k][1] for k in keys); rw = sum(R[k][1] for k in keys)
    b = sum(1 for k in keys if C[k][1] and not R[k][1]); d = sum(1 for k in keys if R[k][1] and not C[k][1])
    z = (b - d) / math.sqrt(b + d) if b + d else 0
    other = sum(1 for k in keys if C[k][2] not in ('WON', 'LOST'))
    print(f"{part}: {len(keys)} rounds, {c} won {cw} ({100*cw/len(keys):.0f}%), {r} won {rw} ({100*rw/len(keys):.0f}%); paired: only {c} won {b}, only {r} won {d}, z {z:+.1f}; not ended by a death {other}")
