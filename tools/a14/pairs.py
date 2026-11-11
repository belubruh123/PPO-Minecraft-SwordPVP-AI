"""Exact per-pair outcomes from the training logs' 'school pair N A bot: ... knockout' lines, keyed by the check's step count."""
import re, glob, json
logs = sorted(glob.glob(r'D:/swordai/run/school-client-111[01]-*.log')) + [r'D:/swordai/run/school-client-live.log']
out = {}
cur = None
for fn in logs:
    with open(fn, errors='replace') as f:
        for line in f:
            if 'login' in line.lower() or 'token' in line.lower():
                continue
            m = re.search(r'Checking the technique school at (T-\w) \((\d+) steps', line)
            if m and '(swordai)' not in line:
                cur = m.group(2); out.setdefault(cur, {})
                continue
            m = re.search(r'school pair (\d+) ([AB]) (.+?): dealt ([\d.]+), taken ([\d.]+), ticks (\d+), (knockout|knocked out|[a-z ]+)', line)
            if m and cur:
                p, s = int(m.group(1)), m.group(2)
                res = 1 if m.group(7) == 'knockout' else -1 if m.group(7) == 'knocked out' else 0
                out[cur]['%d%s' % (p, s)] = (res, float(m.group(4)), float(m.group(5)), int(m.group(6)), m.group(3))
json.dump(out, open('pairs.json', 'w'))
for k, v in out.items():
    a = sum(1 for kk, vv in v.items() if kk.endswith('A') and vv[0] == 1)
    b = sum(1 for kk, vv in v.items() if kk.endswith('B') and vv[0] == 1)
    print(k, len(v), 'A', a, 'B', b)
