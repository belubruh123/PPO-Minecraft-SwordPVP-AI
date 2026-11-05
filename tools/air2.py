import csv, sys, collections
rows = list(csv.DictReader(open(sys.argv[1], encoding='utf8')))
tot = {s: collections.Counter() for s in 'AB'}
for r in rows:
    s = r['side']
    t = float(r['taken'])
    if t <= 0:
        continue
    tot[s]['all taken'] += t
    if r['onGround'] == '1':
        continue
    why = 'knocked up' if r['knockedUp'] == '1' else 'own jump'
    tot[s]['air, ' + why] += t
    tot[s]['air, %s, situation %s' % (why, r['situation'])] += t
    tot[s]['air, %s, reach %s' % (why, '<2.5' if float(r['reach']) < 2.5 else '2.5-3' if float(r['reach']) < 3 else '3+')] += t
    tot[s]['air, %s, W %s sprintKey %s' % (why, r['forward'], r['sprintKey'])] += t
for s in 'AB':
    a = tot[s]['all taken']
    print('== %s  (share of all damage taken)' % s)
    for k, v in sorted(tot[s].items(), key=lambda kv: -kv[1])[:16]:
        print('  %-48s %5.1f%%' % (k, 100 * v / a))
