import sys, agg2
log = sys.argv[1] if len(sys.argv) > 1 else agg2.os.path.join(agg2.HERE, 'client.log')
sums = {}
for line in open(log, encoding='utf-8', errors='replace'):
    if 'fights since the last update' in line:
        p = agg2.parse_line(line.rstrip('\n'))
        if p:
            for k, c in p['cells'].items():
                agg2.add(sums.setdefault(k, {}), c)
for k, a in sums.items():
    ft = a.get('ft', 0)
    if ft > 0:
        print('%-8s far ticks %5d still %3.0f%% flips/100t %5.1f deg/tick %5.2f' % (k, ft, 100 * a['fs'] / ft, 100 * a['ff'] / ft, a['fd'] / ft))
