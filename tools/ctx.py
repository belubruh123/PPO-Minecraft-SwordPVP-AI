"""ctx.py KIND N: for the first N crits taken against KIND (lost fights), the 14 ticks before and 3 after, compact."""
import glob, gzip, os, sys
kind = sys.argv[1]; n = int(sys.argv[2]); skip = int(sys.argv[3]) if len(sys.argv) > 3 else 0
c = 0
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*-%s-*-L-*.txt.gz' % kind)):
    with gzip.open(p, 'rt') as f: L = f.read().splitlines()
    print('##', os.path.basename(p), L[0][:160])
    body = L[3:]
    for j, l in enumerate(body):
        if 'TAKEN CRIT' in l and j > 40:
            c += 1
            if c <= skip: continue
            print('\n'.join(body[max(0, j - 14):j + 3])); print('--')
            if c >= n + skip: sys.exit()
