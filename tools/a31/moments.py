"""Q1: split every check fight's damage into moment types (chains of hits), A vs B, with exact outcomes.

A chain = consecutive hits by one dealer, each within G ticks of the previous hit (any dealer).
Its start: an OPENER (no hit by anyone in the G ticks before) or a REPLY (the other side hit within G ticks).
Start kinds:
  it:   B1 (punishCrit flag), crit, T8 (them rising), fall (them falling, no crit: K2 broken), sprint, plain
  them: crit, sprint, plain
Chain damage = all its hits. "its combo" / "their combo" = the hits after the first in a chain.
"""
import sys, json, re, collections, math
from load import *

G = 20
H, checks = segments(HITS)
pj = json.load(open("pairs.json"))
steps = []
for line in open(CHECKLOG):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check at (T-\w) \((\d+) steps', line)
    if m:
        steps.append((m.group(1), m.group(3)))
steps = [x for x in steps if pj.get(x[1])]


def f(r, k):
    return float(r[H[k]])


def events(rows, logged):
    ev = []
    for r in rows:
        t = int(r[H['tick']])
        d, tk = f(r, 'dealt'), f(r, 'taken')
        ruled = int(r[H['ruled']])
        if d > 0:
            ev.append(dict(t=t, who='it', dmg=d, crit=int(r[H['crit']]), pc=int(r[H['punishCrit']]), sprint=int(r[H['sprintHit']]),
                           rising=int(r[H['themRising']]), falling=int(r[H['themFalling']]), ruled=ruled, ground=int(r[H['onGround']]),
                           sit=r[H['situation']], since=int(r[H['sinceSwing']]), row=r))
        if tk > 0:
            ev.append(dict(t=t, who='them', dmg=tk, crit=1 if int(r[H['critsTaken']]) > 0 else 0, pc=0,
                           sprint=1 if int(r[H['sprintsTaken']]) > 0 else 0, rising=0, falling=0, ruled=ruled, ground=int(r[H['onGround']]),
                           myfalling=int(r[H['falling']]), sit=r[H['situation']], since=int(r[H['sinceSwing']]), row=r))
    res, ld, lt, ticks, bot = logged
    d = sum(e['dmg'] for e in ev if e['who'] == 'it')
    tk = sum(e['dmg'] for e in ev if e['who'] == 'them')
    last = ev[-1]['t'] if ev else 0
    kt = max(last + 1, ticks - 12)
    if ld - d > 0.5:
        ev.append(dict(t=kt, who='it', dmg=ld - d, crit=1 if ld - d > 8 else 0, pc=0, sprint=0, rising=0, falling=0, ruled=-1, ground=-1, sit='KO', since=-1, row=None, ko=1))
    if lt - tk > 0.5:
        ev.append(dict(t=kt, who='them', dmg=lt - tk, crit=1 if lt - tk > 8 else 0, pc=0, sprint=0, rising=0, falling=0, ruled=-1, ground=-1, sit='KO', since=-1, row=None, ko=1))
    return ev


def chains(ev):
    out = []
    prev = None
    for e in ev:
        if prev is None or e['t'] - prev['t'] > G:
            out.append(dict(who=e['who'], start='open', first=e, hits=[e], before=None))
        elif prev['who'] == e['who']:
            out[-1]['hits'].append(e)
        else:
            out.append(dict(who=e['who'], start='reply', first=e, hits=[e], before=out[-1]))
        prev = e
    return out


def kind(c):
    e = c['first']
    if c['who'] == 'it':
        if e['pc']:
            k = 'B1'
        elif e['crit']:
            k = 'crit'
        elif e['rising']:
            k = 'T8'
        elif e['falling']:
            k = 'fall'
        elif e['sprint']:
            k = 'sprint'
        else:
            k = 'plain'
    else:
        k = 'crit' if e['crit'] else 'sprint' if e['sprint'] else 'plain'
    if e.get('ko'):
        k += '?'
    return '%s %s %s' % (c['who'], c['start'], k)


def analyse(idx_list, label):
    # per category: [side] -> list over pairs of (count, dmg first, dmg combo)
    per_pair = {}  # (check, pair) -> {side: {cat: net}} and outcome
    tot = collections.defaultdict(lambda: {'A': [0, 0.0, 0.0, 0], 'B': [0, 0.0, 0.0, 0]})
    for i in idx_list:
        t, st = steps[-i]
        F = fights(H, checks[-i])
        L = pj[st]
        for (p, s), rows in F.items():
            k = '%d%s' % (p, s)
            ev = events(rows, L[k])
            cs = chains(ev)
            cats = collections.Counter()
            for c in cs:
                kk = kind(c)
                sign = 1 if c['who'] == 'it' else -1
                first = c['hits'][0]['dmg']
                rest = sum(h['dmg'] for h in c['hits'][1:])
                T = tot[kk][s]
                T[0] += 1
                T[1] += first
                T[2] += rest
                T[3] += len(c['hits']) >= 3
                cats[kk] += sign * (first + rest)
            per_pair.setdefault((i, p), {})[s] = (L[k][0], cats)
    pairs = {k: v for k, v in per_pair.items() if 'A' in v and 'B' in v}
    n = len(pairs)
    print('==== %s: %d paired fights' % (label, n))
    wa = sum(1 for v in pairs.values() if v['A'][0] == 1)
    wb = sum(1 for v in pairs.values() if v['B'][0] == 1)
    print('rounds won A %d B %d' % (wa, wb))
    allcats = sorted(tot, key=lambda c: -abs(tot[c]['A'][1] + tot[c]['A'][2] - tot[c]['B'][1] - tot[c]['B'][2]))
    print('%-22s %6s %6s | %7s %7s | %6s %6s | %8s %5s | %5s %5s' % ('chain start', 'nA', 'nB', 'dmgA', 'dmgB', 'combA', 'combB', 'netA-B/f', 'se', 'flipA', 'flipB'))
    rows_out = []
    for c in allcats:
        A, B = tot[c]['A'], tot[c]['B']
        sign = 1 if c.startswith('it') else -1
        diffs = []
        flipA = flipB = 0
        for v in pairs.values():
            a = v['A'][1].get(c, 0.0)
            b = v['B'][1].get(c, 0.0)
            diffs.append(a - b)
            # counterfactual: give A's fight B's amount in this category (and vice versa) - does the round flip?
            ma = sum(v['A'][1].values())
            mb = sum(v['B'][1].values())
            oa, ob = v['A'][0], v['B'][0]
            if oa == 1 and ob != 1 and ma - a + b < 0:
                flipA += 1  # A won only thanks to this category's difference
            if ob == 1 and oa != 1 and mb - b + a < 0:
                flipB += 1  # B won only thanks to this category's difference
        mean = sum(diffs) / n
        var = sum((d - mean) ** 2 for d in diffs) / (n - 1)
        se = math.sqrt(var / n)
        print('%-22s %6d %6d | %7.0f %7.0f | %6.0f %6.0f | %+8.2f %5.2f | %5d %5d' % (c, A[0], B[0], A[1] + A[2], B[1] + B[2], A[2], B[2], mean, se, flipA, flipB))
    return pairs, tot


if __name__ == '__main__':
    import sys
    K=int(sys.argv[1]); analyse(list(range(1,K+1)),'last %d'%K); sys.exit()
    which = sys.argv[1] if len(sys.argv) > 1 else 'on'
    if which == 'on':
        analyse(list(range(1, 11)), 'rules ON, last 10 checks 09:36-18:15')
    elif which == 'off':
        analyse([12], 'RULES-OFF check 07:03')
    elif which == 'each':
        for i in range(1, 11):
            analyse([i], steps[-i][0])
