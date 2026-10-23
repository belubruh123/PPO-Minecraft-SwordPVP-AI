"""report22.py LABEL...: the user's measure for 22 Oct evening. Damage a minute dealt/taken (net) and rounds won per opponent,
T4's frozen hourly runs (hc-LABEL-mix.txt and hc-LABEL-sp.txt) against the installed network's frozen runs (all
hc-*-inst-mix.txt / hc-*-inst-sp.txt, same fight sets). Spacer-only runs give the spacer and the one that stands."""
import glob
import re
import sys

ROW = re.compile(r'^(\S.{20})\s+(\d+)\s+(\d+)\s+\d+%\s+(\d+)-(\d+)')


def table(path):
    out = {}
    try:
        for line in open(path, encoding='utf-8', errors='replace'):
            m = ROW.match(line)
            if m and not line.startswith('opponent'):
                out[m.group(1).strip()] = (int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)))
    except FileNotFoundError:
        pass
    return out


def agg(files, only=None):
    a = {}
    for f in files:
        for k, (d, t, w, l) in table(f).items():
            if only and k not in only:
                continue
            x = a.setdefault(k, [0, 0, 0, 0, 0])
            x[0] += d; x[1] += t; x[2] += w; x[3] += l; x[4] += 1
    return a


labels = sys.argv[1:]
SP = ('spacer (crit, S)', 'stander (3 blocks)')
cand_mix = agg(['hc-%s-mix.txt' % l for l in labels])
cand_sp = agg(['hc-%s-sp.txt' % l for l in labels], SP)
inst_mix = agg(sorted(glob.glob('hc-*-inst-mix.txt')))
inst_sp = agg(sorted(glob.glob('hc-*-inst-sp.txt')), SP)
kinds = ['spacer (crit, S)', 'stander (3 blocks)', 'timer (friend)', 'jump-crit chaser', 'punisher', 'no-knockback', 'whiffer',
         'circler', 'the 0.14.0 model', 'own copy', 'corner', 'backer', 'odd', 'easy earlier bots']


def cell(a, k):
    if k not in a:
        return '-'
    d, t, w, l, n = a[k]
    return '%d/%d (%+d) %d%%' % (d / n, t / n, (d - t) / n, round(100 * w / max(1, w + l)))


print('damage a minute dealt/taken (net), rounds won; T4 hours %s vs the installed network (all its frozen runs)' % ', '.join(labels))
print('%-20s %-28s %-28s' % ('opponent', 'T4', 'installed'))
for k in kinds:
    if k in SP:
        print('%-20s %-28s %-28s  (spacer-only runs)' % (k[:20], cell(cand_sp, k), cell(inst_sp, k)))
    if k in cand_mix or k in inst_mix:
        print('%-20s %-28s %-28s%s' % (k[:20], cell(cand_mix, k), cell(inst_mix, k), '  (mix runs)' if k in SP else ''))
