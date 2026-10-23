"""hourlysum.py LABEL: one line for hourly-check.log from hc-LABEL-{sp,mix,lost}.txt (report.py output), set against the
installed network's numbers (final-X-INST-sp.txt and final-X-X-INST-sp-mix.txt, the 20 Oct 18:22 final eval).
Keeps a count of hours in a row worse than installed in hourly-state.txt and warns at two."""
import io
import os
import re
import sys

ROW = re.compile(r'^(.{20}) +(\d+) +(\d+) +(\d+)% +(\d+)-(\d+) +(\d+)%')
HARD = ['timer (friend)', 'spacer (crit, S)', 'stander (3 blocks)', 'whiffer', 'jump-crit chaser', 'punisher', 'circler', 'no-knockback']


def rows(path):
    out = {}
    if not os.path.exists(path):
        return out
    for line in io.open(path, encoding='utf-8', errors='replace'):
        m = ROW.match(line.rstrip('\n'))
        if m:
            d, t, won, lost = int(m.group(2)), int(m.group(3)), int(m.group(5)), int(m.group(6))
            out[m.group(1).strip()] = (d, t, won, lost)
    return out


def lost_line(path):
    r = w = b = 0
    if os.path.exists(path):
        for line in io.open(path, encoding='utf-8', errors='replace'):
            m = re.search(r'(\d+) replayed of which (\d+) won, (\d+) beaten for good', line)
            if m:
                r += int(m.group(1))
                w += int(m.group(2))
                b += int(m.group(3))
    return r, w, b


def summary(sp, mix):
    s = sp.get('spacer (crit, S)')
    hard = [mix[k][0] - mix[k][1] for k in HARD if k in mix]
    old = mix.get('the 0.14.0 model')
    return {
        'spacer': None if not s else (s[0], s[1], s[0] - s[1], 100.0 * s[2] / max(1, s[2] + s[3])),
        'hard': None if not hard else sum(hard) / len(hard),
        'old': None if not old else (old[0], old[1], old[0] - old[1], 100.0 * old[2] / max(1, old[2] + old[3])),
    }


label = sys.argv[1]
now = summary(rows('hc-%s-sp.txt' % label), rows('hc-%s-mix.txt' % label))
base = summary(rows('final-X-INST-sp.txt'), rows('final-X-X-INST-sp-mix.txt'))
replayed, won, beaten = lost_line('hc-%s-lost.txt' % label)


def fmt(x, b):
    if x is None:
        return 'n/a'
    return '%d/%d net %+d (%.0f%% rounds) [installed %+d, %.0f%%]' % (x[0], x[1], x[2], x[3], b[2], b[3])


score = (now['spacer'][2] if now['spacer'] else 0) + (now['hard'] or 0) + 0.5 * (now['old'][2] if now['old'] else 0)
base_score = base['spacer'][2] + base['hard'] + 0.5 * base['old'][2]
delta = score - base_score
verdict = 'BETTER than installed' if delta > 20 else 'WORSE than installed' if delta < -20 else 'about EQUAL to installed'
state = 'hourly-state.txt'
worse = int(open(state).read().strip() or 0) if os.path.exists(state) else 0
worse = worse + 1 if delta < -20 else 0
open(state, 'w').write(str(worse))
line = ('%s | spacer-only %s | mix hard-kind avg net %+.0f [installed %+.0f] | 0.14.0 %s | kept lost fights replayed %d, won %d (%.0f%%) '
        '| score %+.0f vs installed: %s%s') % (
    label, fmt(now['spacer'], base['spacer']), now['hard'] or 0, base['hard'], fmt(now['old'], base['old']),
    replayed, won, 100.0 * won / max(1, replayed), delta, verdict,
    ' | !!! WORSE %d HOURS RUNNING: go back to the best snapshot and change one thing' % worse if worse >= 2 else '')
print(line)
with io.open('hourly-check.log', 'a', encoding='utf-8') as f:
    f.write(line + '\n')
with io.open('hourly-scores.txt', 'a', encoding='utf-8') as f:
    f.write('%s %.1f\n' % (label, delta))
