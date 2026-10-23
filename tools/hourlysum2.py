"""hourlysum2.py LABEL: one line for hourly-check.log from hc-LABEL-{sp,mix,lost}.txt (report.py output), set against
the INSTALLED network measured the same way. Unlike hourlysum.py (one baseline, measured once at 18:06), the baseline
is the mean of every measurement of the installed network so far: final-X-INST-sp.txt, inst-*-sp.txt and the hourly
controls hc-*-inst-sp.txt for the spacer-only run; final-X-X-INST-sp-mix.txt and hc-*-inst-mix.txt for the mix.
Reason (20 Oct 21:58): the same installed network measured +88 net spacer-only at 18:06 and -9 at 21:51, so one
7-minute run against one baseline cannot tell a worse network from noise. Verdicts need +-50 on the score."""
import glob
import io
import os
import re
import sys

ROW = re.compile(r'^(.{20}) +(\d+) +(\d+) +(\d+)% +(\d+)-(\d+) +(\d+)%')
HARD = ['timer (friend)', 'spacer (crit, S)', 'stander (3 blocks)', 'whiffer', 'jump-crit chaser', 'punisher', 'circler', 'no-knockback']
MARGIN = 50.0


def rows(path):
    out = {}
    if not os.path.exists(path):
        return out
    for line in io.open(path, encoding='utf-8', errors='replace'):
        m = ROW.match(line.rstrip('\n'))
        if m:
            out[m.group(1).strip()] = (int(m.group(2)), int(m.group(3)), int(m.group(5)), int(m.group(6)))
    return out


def lost_line(path):
    r = w = 0
    if os.path.exists(path):
        for line in io.open(path, encoding='utf-8', errors='replace'):
            m = re.search(r'(\d+) replayed of which (\d+) won', line)
            if m:
                r += int(m.group(1))
                w += int(m.group(2))
    return r, w


def spacer(sp):
    s = sp.get('spacer (crit, S)')
    return None if not s else (s[0], s[1], s[0] - s[1], 100.0 * s[2] / max(1, s[2] + s[3]))


def mixed(mix):
    hard = [mix[k][0] - mix[k][1] for k in HARD if k in mix]
    old = mix.get('the 0.14.0 model')
    return (None if not hard else sum(hard) / len(hard),
            None if not old else (old[0], old[1], old[0] - old[1], 100.0 * old[2] / max(1, old[2] + old[3])))


def mean(xs):
    xs = [x for x in xs if x is not None]
    return None if not xs else tuple(sum(v) / len(xs) for v in zip(*xs)) if isinstance(xs[0], tuple) else sum(xs) / len(xs)


def paired(paths):
    """From the time paired evaluation began (paired-since.txt, Config.evalSeed), only the measurements made with it."""
    if not os.path.exists('paired-since.txt'):
        return paths
    since = float(open('paired-since.txt').read().strip())
    return [p for p in paths if os.path.exists(p) and os.path.getmtime(p) >= since]


def baseline():
    sps = [spacer(rows(p)) for p in paired(['final-X-INST-sp.txt'] + sorted(glob.glob('inst-*-sp.txt')) + sorted(glob.glob('pinst-*-sp.txt')) + sorted(glob.glob('hc-*-inst-sp.txt')) + sorted(glob.glob('fp-*-inst-sp.txt')))]
    mixes = [mixed(rows(p)) for p in paired(['final-X-X-INST-sp-mix.txt'] + sorted(glob.glob('pinst-*-mix.txt')) + sorted(glob.glob('hc-*-inst-mix.txt')) + sorted(glob.glob('fp-*-inst-mix.txt')))]
    sps = [x for x in sps if x]
    return mean(sps), len(sps), mean([m[0] for m in mixes]), mean([m[1] for m in mixes]), len(mixes)


def score(sp, hard, old):
    return (sp[2] if sp else 0) + (hard or 0) + 0.5 * (old[2] if old else 0)


def verdict(label, write=True):
    sp = spacer(rows('hc-%s-sp.txt' % label))
    hard, old = mixed(rows('hc-%s-mix.txt' % label))
    bsp, nsp, bhard, bold, nmix = baseline()
    delta = score(sp, hard, old) - score(bsp, bhard, bold)
    replayed, won = lost_line('hc-%s-lost.txt' % label)
    v = 'BETTER than installed' if delta > MARGIN else 'WORSE than installed' if delta < -MARGIN else 'about EQUAL to installed'
    ctl = spacer(rows('hc-%s-inst-sp.txt' % label))
    ctlmix = mixed(rows('hc-%s-inst-mix.txt' % label))

    def fmt(x, b):
        return 'n/a' if x is None else '%d/%d net %+d (%.0f%% rounds) [installed avg %+.0f, %.0f%%]' % (x[0], x[1], x[2], x[3], b[2], b[3])

    line = ('%s | spacer-only %s | mix hard-kind avg net %+.0f [installed avg %+.0f] | 0.14.0 %s | kept lost fights replayed %d, won %d (%.0f%%) '
            '| score %+.0f vs installed (avg of %d spacer-only, %d mix runs): %s') % (
        label, fmt(sp, bsp), hard or 0, bhard, fmt(old, bold), replayed, won, 100.0 * won / max(1, replayed), delta, nsp, nmix, v)
    if ctl:
        line += ' | installed this hour spacer-only %d/%d net %+d (%.0f%%)' % ctl
    if ctlmix[0] is not None:
        line += ' | installed this hour mix hard avg %+.0f, 0.14.0 net %+d' % (ctlmix[0], ctlmix[1][2] if ctlmix[1] else 0)
    if not write:
        return line, delta
    state = 'hourly-state.txt'
    worse = int(open(state).read().strip() or 0) if os.path.exists(state) else 0
    worse = worse + 1 if delta < -MARGIN else 0
    open(state, 'w').write(str(worse))
    if worse >= 2:
        line += ' | !!! WORSE %d HOURS RUNNING: rolled back' % worse
    with io.open('hourly-check.log', 'a', encoding='utf-8') as f:
        f.write(line + '\n')
    with io.open('hourly-scores.txt', 'a', encoding='utf-8') as f:
        f.write('%s %.1f\n' % (label, delta))
    return line, delta


if __name__ == '__main__':
    print(verdict(sys.argv[1], write=len(sys.argv) < 3)[0])
