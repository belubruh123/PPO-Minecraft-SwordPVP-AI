"""Per kind of opponent over a stretch of the log: python ab.py [--log PATH] [--from HH:MM:SS] [--to HH:MM:SS] [--kinds a,b]"""
import argparse
import os

import agg2

ap = argparse.ArgumentParser()
ap.add_argument('--log', default=os.path.join(agg2.HERE, 'client.log'))
ap.add_argument('--from', dest='start', default='00:00:00')
ap.add_argument('--to', dest='end', default='99:99:99')
ap.add_argument('--kinds', default='own,combo,trader,RUNNER,CIRCLER,CORNER,NOKB,ODD,JUMPER,PUNISHER,BACKER,older')
args = ap.parse_args()
tot = {}
n = 0
first = last = None
for line in open(args.log, encoding='utf-8', errors='replace'):
    if 'fights since the last update' not in line:
        continue
    p = agg2.parse_line(line.rstrip('\n'))
    if not p or p['time'] < args.start or p['time'] > args.end:
        continue
    n += 1
    first = first or p['time']
    last = p['time']
    for k, c in p['cells'].items():
        agg2.add(tot.setdefault(k, {}), c)
print('%d updates, %s to %s' % (n, first, last))
print('%-9s %5s  %-11s %5s %6s  %-22s %-16s %6s %6s %7s %6s' % (
    'kind', 'min', 'land/taken', 'crit%', 'ctkn/m', 'combos on it: 3rd hit', 'over: sat 3+', 'reset', 'stepbk', 'ctr/min', 'air/m') + '  S-held  chase(crit)/m  sweeps  own combos: /min, hits each   damage/min dealt/taken   rounds won')
for k in args.kinds.split(','):
    a = tot.get(k)
    if not a or a['st'] <= 0:
        continue
    m = a['st'] / 1200.0
    print('%-9s %5.1f  %4.0f / %-4.0f %4.0f%% %6.1f  %4.0f: %3.0f = %3.0f%%       %4.0f: %2.0f = %4.1f%% %6.1f %6.0f %7.1f %6.1f' % (
        k, m, a['h'] / m, a['t'] / m, agg2.pct(a['c'], a['h']), a.get('ct', 0) / m,
        a['two'], a['three'], agg2.pct(a['three'], a['two']), a['over'], a['sat'], agg2.pct(a['sat'], a['over']),
        a.get('rs', 0) / m, a.get('sbt', 0) / m, a.get('ch', 0) / m, a.get('rhit', 0) / m)
          + '  %3.0f%%    %4.1f (%4.1f)   %4.1f%% e%d' % (agg2.pct(a['sheld'], a['sticks']), a.get('rcj', 0) / m, a.get('rcc', 0) / m, agg2.pct(a['sw'], a['h']), a['early'])
          + '   %4.1f  %4.2f' % (a.get('oc', 0) / m, a.get('och', 0) / a['oc'] if a.get('oc', 0) > 0 else 0)
          + '   %5.1f / %-5.1f' % (a.get('dd', 0) / m, a.get('dt', 0) / m)
          + '   %d/%d = %.0f%%' % (a.get('rw', 0), a.get('rw', 0) + a.get('rl', 0), agg2.pct(a.get('rw', 0), a.get('rw', 0) + a.get('rl', 0))))
