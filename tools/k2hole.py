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
            if not (r['hitsDealt'] != '0' and r['sprintHit'] == '1' and r['themFalling'] == '1'):
                continue
            t = int(r['tick'])
            prev = [q for q in fr[max(0, i - 6):i] if q['hitsDealt'] != '0' and t - int(q['tick']) <= 15]
            took = [q for q in fr[max(0, i - 6):i] if q['hitsTaken'] != '0' and t - int(q['tick']) <= 15]
            kind = 'its own hit in the 15 ticks before (combo-exempt)' if prev else 'no hit of its own before'
            c[kind] += 1
            c['their hit in the 15 ticks before'] += 1 if took else 0
            nxt = [q for q in fr[i + 1:i + 6] if int(q['tick']) - t <= 12]
            back = any(q['hitsTaken'] != '0' for q in nxt)
            crit = any(q['critsTaken'] != '0' for q in nxt)
            c[kind + ': hit back within 12'] += back
            c[kind + ': crit back within 12'] += crit
            c['ALL'] += 1
    print('== %s: sprint hits landed on them falling: %d' % (s, c['ALL']))
    for k, v in sorted(c.items()):
        if k != 'ALL':
            print('   %-62s %4d' % (k, v))
