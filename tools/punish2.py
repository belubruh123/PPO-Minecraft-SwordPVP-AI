import csv, sys, collections
rows = list(csv.DictReader(open(sys.argv[1], encoding='utf8')))
fights = collections.defaultdict(list)
for r in rows:
    fights[(r['pair'], r['side'], r['bot'])].append(r)
for s in 'AB':
    c = collections.Counter()
    for k, fr in fights.items():
        if k[1] != s:
            continue
        for i, r in enumerate(fr):
            if r['critsTaken'] == '0' or i + 1 >= len(fr):
                continue
            q = fr[i + 1]
            if int(q['tick']) - int(r['tick']) > 25 or q['hitsDealt'] == '0' or q['hitsTaken'] != '0':
                continue
            out = 'crit' if q['crit'] == '1' else 'landed first' if q['onGround'] == '1' else 'rising' if q['falling'] != '1' else 'other'
            lifted = 'hit on the ground (lifted)' if r['onGround'] == '1' else 'hit in the air'
            ready = int(r['sinceSwing']) >= 4
            c[(out, lifted)] += 1
            c[(out, 'sword back by the fall' if ready else 'swung under 4 ticks before their crit')] += 1
            c[(out, 'ALL')] += 1
    print('== ' + s)
    for out in ('crit', 'landed first', 'rising', 'other'):
        a = c[(out, 'ALL')]
        parts = ['%s %d%%' % (k[1], 100 * v / a) for k, v in c.items() if k[0] == out and k[1] != 'ALL']
        print('  %-13s %4d: %s' % (out, a, '; '.join(sorted(parts))))
