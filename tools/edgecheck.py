import glob, gzip, io, contextlib, os, sys
with contextlib.redirect_stdout(io.StringIO()):
    from exch import parse, amt
from collections import Counter
since = sys.argv[1]; until = sys.argv[2] if len(sys.argv) > 2 else '9999'
c = Counter()
for p in sorted(glob.glob('D:/swordai/run/swordai/traces/*.txt.gz')):
    n = os.path.basename(p)[:11]
    if n < since or n >= until: continue
    kind = os.path.basename(p).split('-')[2]
    with gzip.open(p, 'rt') as f: head = f.readline()
    if kind == 'spacer': kind = 'stander' if 'stands' in head else 'spacer'
    c[kind + ' fights'] += 1; c[kind + ' won'] += '-W-' in p
    ts = parse(p)
    for j, x in enumerate(ts):
        if 'jumpin:0' in x['rules']: c[kind + ' approach ticks'] += 1
        if x['keys'][3] == 'J' and x['meg'] and 'jumpin:7' in x['rules']:
            v = ' stand' if x['keys'][0] != 'W' else ' run'
            c[kind + v + ' edge jumps'] += 1
            c[kind + v + ' edge net over 15 ticks'] += sum(amt(y['ev'], 'HIT') - amt(y['ev'], 'TAKEN') for y in ts[j:j + 15])
            c[kind + v + ' edge crits within 15'] += sum(1 for y in ts[j:j + 15] if 'CRIT' in y['ev'].split('TAKEN')[0])
            for y in ts[j + 1:j + 16]:
                h = y['ev'].split('TAKEN')[0]
                if 'HIT' in h:
                    c[kind + ' edge: our hit first' + (' (CRIT)' if 'CRIT' in h else '') + (' + its hit same tick' if 'TAKEN' in y['ev'] else '')] += 1; break
                if 'TAKEN' in y['ev']: c[kind + ' edge: its hit first'] += 1; break
            else: c[kind + ' edge: nothing'] += 1
        h = x['ev'].split('TAKEN')[0]
        if 'HIT' in h:
            c[kind + ' dealt'] += amt(x['ev'], 'HIT')
            if 'CRIT' in h: c[kind + ' our crits'] += 1
        if 'TAKEN' in x['ev']: c[kind + ' taken'] += amt(x['ev'], 'TAKEN')
for k, v in sorted(c.items()): print('%-50s %g' % (k, v))
