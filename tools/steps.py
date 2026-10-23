import sys, agg2
log, start, end = sys.argv[1], sys.argv[2], sys.argv[3]
tot = {}
for line in open(log, encoding='utf-8', errors='replace'):
    t = line[1:9]
    if not (start <= t <= end) or 'fights since the last update' not in line:
        continue
    p = agg2.parse_line(line.rstrip('\n'))
    if not p:
        continue
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)
all_st = sum(a.get('st', 0) for a in tot.values())
for k, a in sorted(tot.items(), key=lambda kv: -kv[1].get('st', 0)):
    st = a.get('st', 0)
    if st <= 0: continue
    m = st / 1200.0
    print('%-10s %5.1f%% steps  dealt %4.0f/min taken %4.0f/min  rounds %d-%d' % (k, 100.0 * st / all_st, a.get('dd', 0) / m, a.get('dt', 0) / m, a.get('rw', 0), a.get('rl', 0)))
