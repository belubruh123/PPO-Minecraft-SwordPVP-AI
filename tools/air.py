"""air.py HITS: damage taken in the air and the follow-up to their crits, side A vs B, from a checks' hits.csv."""
import csv, sys, collections
rows = list(csv.DictReader(open(sys.argv[1], encoding='utf8')))
fights = collections.defaultdict(list)
for r in rows:
    fights[(r['pair'], r['side'], r['bot'])].append(r)
n = {s: sum(1 for k in fights if k[1] == s) for s in 'AB'}
air = {s: collections.Counter() for s in 'AB'}
for r in rows:
    s = r['side']
    if float(r['taken']) > 0 and r['onGround'] == '0':
        kind = 'crit' if r['critsTaken'] != '0' else ('sprint' if r['sprintsTaken'] != '0' else 'plain')
        phase = 'falling' if r['falling'] == '1' else 'rising/top'
        air[s]['%s, it %s, jump key %s, its row %s' % (kind, phase, r['jumpKey'], 'combo' if int(r['myRow']) >= 1 else '-')] += float(r['taken'])
        air[s]['ALL in the air'] += float(r['taken'])
        air[s]['by situation ' + r['situation']] += float(r['taken'])
for s in 'AB':
    print('== %s: damage taken in the air a fight (%d fights)' % (s, n[s]))
    for k, v in air[s].most_common(14):
        print('  %-55s %6.1f' % (k, v / n[s] * 9))
# After their crit: its next hit of either side within 25 ticks.
print('== after a crit taken: what came next within 25 ticks (first hit of either side)')
for s in 'AB':
    c = collections.Counter()
    for k, fr in fights.items():
        if k[1] != s:
            continue
        for i, r in enumerate(fr):
            if r['critsTaken'] == '0':
                continue
            t0 = int(r['tick'])
            nxt = None
            for q in fr[i + 1:]:
                if int(q['tick']) - t0 > 25:
                    break
                nxt = q
                break
            if nxt is None:
                c['nothing within 25 ticks'] += 1
            elif nxt['hitsDealt'] != '0' and nxt['hitsTaken'] == '0':
                c['its hit: ' + ('CRIT' if nxt['crit'] == '1' else ('sprint hit' if nxt['sprintHit'] == '1' else 'plain hit')) + (', them falling' if nxt['themFalling'] == '1' else '')] += 1
            elif nxt['hitsTaken'] != '0' and nxt['hitsDealt'] == '0':
                c['their next hit first (' + ('crit' if nxt['critsTaken'] != '0' else 'sprint' if nxt['sprintsTaken'] != '0' else 'plain') + ')'] += 1
            else:
                c['traded'] += 1
            c['ALL'] += 1
    tot = c['ALL']
    print('  %s: %d crits taken' % (s, tot))
    for k, v in c.most_common():
        if k != 'ALL':
            print('     %-45s %4d  %3.0f%%' % (k, v, 100.0 * v / tot))
