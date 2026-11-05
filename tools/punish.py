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
            c['ALL its next hits'] += 1
            if q['crit'] == '1':
                c['crit'] += 1
                continue
            why = []
            if q['onGround'] == '1':
                why.append('on the ground already')
            elif q['falling'] != '1':
                why.append('in the air, not falling')
            if q['sprinting'] == '1':
                why.append('sprinting')
            if float(q['hitCharge']) < 0.9:
                why.append('sword not charged')
            reason = ' + '.join(why) or 'other'
            c['not a crit: ' + reason] += 1
            c['ticks after: %s' % ('<=8' if int(q['tick']) - int(r['tick']) <= 8 else '9-14' if int(q['tick']) - int(r['tick']) <= 14 else '15+')] += 1
    a = c['ALL its next hits']
    print('== %s: its next hit after a crit taken (no hit of theirs between): %d' % (s, a))
    for k, v in c.most_common():
        if k != 'ALL its next hits':
            print('   %-55s %4d %3.0f%%' % (k, v, 100.0 * v / a))
