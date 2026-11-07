"""reward-trend.py [LOG ...] [--bucket MIN]: each reward term's counter per training update, averaged in time buckets, so
"it should have learned it by now" can be checked (the user, 7 Nov 08:40: give an expectation of when a reward is learned, and
investigate if it falls short: reward hacking, an impossible bot, or a bot that teaches the technique wrongly).

Reads the school update lines (default run/school-client.log) and the counters the reward terms print there:
  punish crits paid, other crits, combo follow-up hits (3rd+), chase crits, made to miss, deflection starts,
  hits taken in the air from its own jump, own-combo scenes won (of fought), punish crit chances critted (whatever chosen).
Counts are per update (about 70 s of play); a term that is learned rises (or, for a cost, falls) bucket after bucket.
"""
import re
import sys

PAT = {
    'punish crits': r'real punish crits rewarded (\d+)',
    'other crits': r'other crits (\d+)',
    'follow-ups': r'combo follow-up hits rewarded (\d+)',
    '3rd+': r'combo follow-up hits rewarded \d+ \(3rd\+ (\d+)\)',
    'chase crits': r'chase crits (\d+)',
    'made miss': r'made to miss (\d+)',
    'deflect starts': r'deflection starts (\d+)',
    'air hits taken': r'hits taken in the air from its own jump (\d+)',
    'K2 broken': r'K2 broken (\d+)',
}
SCENE = {
    'own scenes won%': r'; own combo scenes kept \d+, won \d+ of \d+ \(last (\d+)% of (\d+)\)',
    'own2 scenes won%': r'; own combo 2 scenes kept \d+, won \d+ of \d+ \(last (\d+)% of (\d+)\)',
    'punish scenes won%': r'; punish scenes kept \d+, won \d+ of \d+ \(last (\d+)% of (\d+)\)',
    'crit scenes won%': r'; crit scenes kept \d+, won \d+ of \d+ \(last (\d+)% of (\d+)\)',
}


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    bucket = 30
    if '--bucket' in sys.argv:
        bucket = int(sys.argv[sys.argv.index('--bucket') + 1])
        args = [a for a in args if a != str(bucket)]
    logs = args or ['D:/swordai/run/school-client.log']
    rows = []
    for p in logs:
        with open(p, encoding='utf8', errors='replace') as f:
            for line in f:
                if '(swordai) school update' not in line or 'done since the last update' not in line:
                    continue
                m = re.match(r'\[(\d\d):(\d\d):(\d\d)\]', line)
                if not m:
                    continue
                t = int(m.group(1)) * 60 + int(m.group(2))
                r = {'t': t}
                for k, pat in PAT.items():
                    mm = re.search(pat, line)
                    r[k] = int(mm.group(1)) if mm else None
                for k, pat in SCENE.items():
                    mm = re.search(pat, line)
                    r[k] = (int(mm.group(1)), int(mm.group(2))) if mm else None
                rows.append(r)
    if not rows:
        print('no update lines')
        return
    keys = [k for k in PAT if any(r[k] is not None for r in rows)]
    skeys = [k for k in SCENE if any(r[k] is not None for r in rows)]
    print('per update (about 70 s), mean in %d-minute buckets; scenes: the rolling share won (of the last N) at the end of the bucket' % bucket)
    print('%-11s %4s ' % ('from', 'upd') + ' '.join('%10s' % k[:10] for k in keys) + ' ' + ' '.join('%14s' % k[:14] for k in skeys))
    start = rows[0]['t']
    groups = {}
    for r in rows:
        groups.setdefault((r['t'] - start) // bucket, []).append(r)
    prev_scene = {k: None for k in skeys}
    for g in sorted(groups):
        rs = groups[g]
        t = start + g * bucket
        cells = []
        for k in keys:
            v = [r[k] for r in rs if r[k] is not None]
            cells.append('%10.1f' % (sum(v) / len(v)) if v else '%10s' % '-')
        scells = []
        for k in skeys:
            last = next((r[k] for r in reversed(rs) if r[k] is not None), None)
            scells.append('%9d%% (%d)' % last if last else '%14s' % '-')
        print('%02d:%02d %10d ' % ((t // 60) % 24, t % 60, len(rs)) + ' '.join(cells) + ' ' + ' '.join(scells))


if __name__ == '__main__':
    main()
