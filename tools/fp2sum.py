"""fp2sum.py LABEL BLOCKS: summary of finalpair2.sh. Per block (its own fight set, both networks on it): spacer-only net,
the mix's hard-kind average net and the 0.14.0 model's net, score = spacer + hard + 0.5 * 0.14.0 (as finalpair.sh), and the
candidate's score minus the installed one's. Verdict: BETTER only if the mean difference is over 50 AND over two standard
errors across blocks; WORSE if under -50; else about EQUAL. Then damage dealt/taken a minute per opponent, averaged over
the blocks, both networks (the user's main measure)."""
import math
import re
import sys

label, blocks = sys.argv[1], int(sys.argv[2])
ROW = re.compile(r'^(.{20}) +(\d+) +(\d+) +(\d+)% +(\d+)-(\d+) +(\d+)%')
HARD = ['timer (friend)', 'spacer (crit, S)', 'stander (3 blocks)', 'whiffer', 'jump-crit chaser', 'punisher', 'circler', 'no-knockback']


def rows(p):
    out = {}
    try:
        for line in open(p, encoding='utf-8', errors='replace'):
            m = ROW.match(line.rstrip('\n'))
            if m:
                out[m.group(1).strip()] = (int(m.group(2)), int(m.group(3)), int(m.group(5)), int(m.group(6)))
    except FileNotFoundError:
        pass
    return out


def parts(who, k):
    base = 'fp2-%s-b%d-%s-' % (label, k, who)
    sp, mix, old = rows(base + 'sp.txt'), rows(base + 'mix.txt'), rows(base + 'old.txt')
    s = sp.get('spacer (crit, S)')
    hard = [mix[h][0] - mix[h][1] for h in HARD if h in mix]
    o = old.get('the 0.14.0 model')
    return (s[0] - s[1] if s else 0, sum(hard) / max(1, len(hard)), o[0] - o[1] if o else 0,
            100.0 * s[2] / max(1, s[2] + s[3]) if s else 0, 100.0 * o[2] / max(1, o[2] + o[3]) if o else 0), (sp, mix, old)


lines = []
diffs = []
tables = {'cand': {}, 'inst': {}}
for k in range(1, blocks + 1):
    pc, rc = parts('cand', k)
    pi, ri = parts('inst', k)
    sc = pc[0] + pc[1] + 0.5 * pc[2]
    si = pi[0] + pi[1] + 0.5 * pi[2]
    diffs.append(sc - si)
    lines.append('  block %d: spacer %+d (%.0f%%) vs %+d (%.0f%%) | hard %+.0f vs %+.0f | 0.14.0 %+d (%.0f%%) vs %+d (%.0f%%) | score %+.0f'
                 % (k, pc[0], pc[3], pi[0], pi[3], pc[1], pi[1], pc[2], pc[4], pi[2], pi[4], sc - si))
    for who, r in (('cand', rc), ('inst', ri)):
        for table in r:
            for name, (d, t, w, l) in table.items():
                acc = tables[who].setdefault(name, [0, 0, 0, 0, 0])
                acc[0] += d
                acc[1] += t
                acc[2] += w
                acc[3] += l
                acc[4] += 1
n = len(diffs)
mean = sum(diffs) / n
se = math.sqrt(sum((d - mean) ** 2 for d in diffs) / (n - 1) / n) if n > 1 else float('inf')
verdict = 'BETTER' if mean > 50 and mean > 2 * se else 'WORSE' if mean < -50 else 'about EQUAL'
print('%s FINAL PAIRED x%d blocks | score difference %+.0f (standard error %.0f, blocks %s): %s'
      % (label, n, mean, se, ' '.join('%+.0f' % d for d in diffs), verdict))
for line in lines:
    print(line)
print('  damage a minute dealt/taken, averaged over blocks (cand | installed):')
for name in sorted(set(tables['cand']) | set(tables['inst'])):
    def f(who):
        a = tables[who].get(name)
        if not a:
            return 'n/a'
        return '%d/%d (%d%% rounds)' % (a[0] / a[4], a[1] / a[4], 100.0 * a[2] / max(1, a[2] + a[3]))
    print('    %-22s %s | %s' % (name, f('cand'), f('inst')))
