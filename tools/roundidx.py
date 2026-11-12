# Round 1 vs later rounds against the same opponent (memory carried in real duels, reset in training). Names never printed.
import glob, gzip, os, collections
B = "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights/"
files = sorted(glob.glob(B + '*.txt.gz'))
prev = None; idx = 0; prevt = None
st = collections.defaultdict(lambda: [0, 0, 0, 0, 0])  # rounds, first exchange won, first exchange lost, hits dealt, hits taken
import datetime
for f in files:
    b = os.path.basename(f)[:-7]
    stamp, rest = b[:15], b[16:]
    opp = rest.rsplit('-', 1)[0]
    t = datetime.datetime.strptime(stamp, '%Y%m%d-%H%M%S')
    if opp == prev and prevt and (t - prevt).total_seconds() < 120:
        idx += 1
    else:
        idx = 1
    prev, prevt = opp, t
    k = '1' if idx == 1 else '2' if idx == 2 else '3+'
    fw = fl = 0; hd = ht = 0
    with gzip.open(f, 'rt', errors='replace') as g:
        for n, line in enumerate(g):
            if n == 1 and ', hits ' in line:
                d = line.split(', hits ', 1)[1].split(' dealt / ')
                hd, ht = int(d[0]), int(d[1].split()[0])
            if line[:1].isdigit() and ('first+' in line or 'first-' in line) and not (fw or fl):
                fw, fl = ('first+' in line), ('first-' in line)
    s = st[k]; s[0] += 1; s[1] += fw; s[2] += fl; s[3] += hd; s[4] += ht
for k in ('1', '2', '3+'):
    s = st[k]
    if s[0]:
        print('round %-3s vs the same opponent: %3d rounds, first exchange won %3.0f%% of %d, hits %.1f dealt / %.1f taken a round, ratio %.2f' % (
            k, s[0], 100 * s[1] / max(1, s[1] + s[2]), s[1] + s[2], s[3] / s[0], s[4] / s[0], s[3] / max(1, s[4])))
