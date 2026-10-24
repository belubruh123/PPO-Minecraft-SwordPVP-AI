"""compare-checks.py OLD NEW [--run D:/swordai/run]: the school side (A) of two paired checks, technique by technique.

OLD and NEW are the checks' times as their headers show them ("17:44"). A technique is judged where both checks had at
least MIN moments of it; a drop of more than DROP points is a lost skill. Also each check's A - B in all and against the
mixer (the real-player-like bot), the one bot every check has. Prints SWAP OK only when no skill is lost and the newer
check is no worse on damage against the mixer.
"""
import re
import sys

MIN = 8
DROP = 10


def block(text, hhmm):
    parts = re.split(r'\n(?====)', text)
    found = [p for p in parts if p.startswith('===') and (' ' + hhmm + ' ') in p.split('\n', 1)[0]]
    if not found:
        sys.exit('no check at ' + hhmm)
    return found[-1]


def techniques(b):
    m = re.search(r'A \(school\) techniques:(.*)', b)
    return {t: (int(x), int(n)) for t, x, n in re.findall(r' (\w+) (\d+)/(\d+) done', m.group(1))} if m else {}


def rows(b):
    out = {}
    for line in b.splitlines():
        m = re.match(r'(\S+)\s+(\d+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+([\d.]+)', line)
        if m:
            out[m.group(1)] = float(m.group(5))
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    run = 'D:/swordai/run'
    if '--run' in sys.argv:
        run = sys.argv[sys.argv.index('--run') + 1]
    text = open(run + '/school-checks.log', encoding='utf8', errors='replace').read()
    old, new = block(text, args[0]), block(text, args[1])
    to, tn = techniques(old), techniques(new)
    lost = []
    print('technique   %-12s %-12s change' % (args[0], args[1]))
    for t in sorted(set(to) | set(tn)):
        a = to.get(t, (0, 0))
        b = tn.get(t, (0, 0))
        ra = 100.0 * a[0] / a[1] if a[1] else None
        rb = 100.0 * b[0] / b[1] if b[1] else None
        judged = a[1] >= MIN and b[1] >= MIN
        d = rb - ra if ra is not None and rb is not None else None
        flag = ''
        if judged and d < -DROP:
            flag = '  LOST'
            lost.append(t)
        print('%-11s %-12s %-12s %s%s' % (t, '-' if ra is None else '%3.0f%% (%d)' % (ra, a[1]),
                                          '-' if rb is None else '%3.0f%% (%d)' % (rb, b[1]),
                                          '' if d is None else '%+.0f' % d, flag))
    ro, rn = rows(old), rows(new)
    mixer = lambda r: (r.get('mixer', 0) + r.get('mixer*', 0)) / 2
    print('A - B all: %s %+.1f -> %s %+.1f' % (args[0], ro.get('ALL', 0), args[1], rn.get('ALL', 0)))
    print('A - B vs mixer (mean of mixer, mixer*): %+.1f -> %+.1f' % (mixer(ro), mixer(rn)))
    ok = not lost and mixer(rn) >= mixer(ro)
    print(('SWAP OK' if ok else 'NO SWAP') + (': lost ' + ', '.join(lost) if lost else '') +
          ('' if mixer(rn) >= mixer(ro) else ': worse against the mixer'))


if __name__ == '__main__':
    main()
