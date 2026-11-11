"""Jump now vs not now, after its own ground non-crit hit, no trade: risk set at tick tau = no event and no take-off before tau.
Compare take-off at tau with no take-off at tau (outcome = next event within 20 ticks of its hit), weighted by the jumpers at each tau."""
import collections, sys
from load import segments, fights, JUMPS
from moments import events, steps, pj, H, checks
HJ, jchecks = segments(JUMPS)
K = int(sys.argv[1]) if len(sys.argv) > 1 else 9
cases = {s: [] for s in 'AB'}
for i in range(1, K + 1):
    t, stp = steps[-i]; F = fights(H, checks[-i]); L = pj[stp]
    J = {}
    for r in jchecks[-i]:
        J.setdefault((int(r[0]), r[1]), []).append(int(r[HJ['tick']]))
    for (p, s), rows in F.items():
        ev = events(rows, L['%d%s' % (p, s)]); js = J.get((p, s), [])
        for k, e in enumerate(ev):
            if e['who'] != 'it' or e['crit'] or e['ground'] != 1 or e.get('ko'): continue
            nxt = next((x for x in ev[k + 1:] if x['t'] > e['t']), None)
            dt = (nxt['t'] - e['t']) if nxt else 99
            if nxt is not None and dt <= 5 and nxt['who'] == 'them': continue
            res = 'none' if nxt is None or dt > 20 else ('own' if nxt['who'] == 'it' else 'their')
            dmg = nxt['dmg'] if res != 'none' else 0.0
            crit = res == 'own' and nxt['crit']
            to = min([j - e['t'] for j in js if e['t'] < j <= e['t'] + 20] or [99])
            cases[s].append((dt, res, to, dmg, crit))
def net(sub):
    n = len(sub)
    return (sum(c[3] for c in sub if c[1] == 'own') - sum(c[3] for c in sub if c[1] == 'their')) / n if n else 0
for s in 'AB':
    W = collections.Counter(); tot = collections.Counter()
    print('== %s' % s)
    for tau in range(1, 19):
        risk = [c for c in cases[s] if c[0] > tau - 0 and c[2] >= tau]  # no event up to tau (event at tau counts as before the jump), no take-off before tau
        risk = [c for c in risk if c[0] > tau]
        jj = [c for c in risk if c[2] == tau]; nn = [c for c in risk if c[2] > tau]
        if not risk: continue
        w = len(jj)
        for lab, sub in (('j', jj), ('n', nn)):
            for r in ('own', 'their', 'none'):
                tot[(lab, r)] += w * len([c for c in sub if c[1] == r]) / max(1, len(sub))
            tot[(lab, 'net')] += w * net(sub)
            tot[(lab, 'crit')] += w * len([c for c in sub if c[4]]) / max(1, len(sub))
        tot['w'] += w
        if w >= 4: print('  tau %2d: risk %4d, jump now %3d: own %2.0f%% their %2.0f%% net %+.2f | not now: own %2.0f%% their %2.0f%% net %+.2f' % (tau, len(risk), w,
              100 * len([c for c in jj if c[1] == 'own']) / w, 100 * len([c for c in jj if c[1] == 'their']) / w, net(jj),
              100 * len([c for c in nn if c[1] == 'own']) / max(1, len(nn)), 100 * len([c for c in nn if c[1] == 'their']) / max(1, len(nn)), net(nn)))
    w = tot['w']
    if w:
        print('  WEIGHTED (%d take-offs): jump now own %.0f%% their %.0f%% crit-next %.0f%% net %+.2f HP | not now own %.0f%% their %.0f%% crit-next %.0f%% net %+.2f HP' % (w,
              100 * tot[('j', 'own')] / w, 100 * tot[('j', 'their')] / w, 100 * tot[('j', 'crit')] / w, tot[('j', 'net')] / w,
              100 * tot[('n', 'own')] / w, 100 * tot[('n', 'their')] / w, 100 * tot[('n', 'crit')] / w, tot[('n', 'net')] / w))
