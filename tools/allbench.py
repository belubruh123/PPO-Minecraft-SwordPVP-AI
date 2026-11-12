# Every benchmarked network against the installed-model reference measured nearest in time on the same suite.
import os, subprocess, hashlib, datetime, re
B = 'D:/swordai/run/swordai/bench/'
tags = [t for t in os.listdir(B) if os.path.isdir(B + t)]
info = {}
for t in tags:
    try:
        st = [l for l in open(B + t + '/DONE') if l.startswith('started')][0].split()[1:3]
        when = datetime.datetime.strptime(' '.join(st), '%Y-%m-%d %H:%M:%S')
        suite = hashlib.md5(open(B + t + '/suite.txt', 'rb').read()).hexdigest()[:8]
        net = open(B + t + '/network.txt').read().split()[1]
    except Exception:
        continue
    info[t] = (when, suite, os.path.basename(net))
refs = [t for t in info if info[t][2] == 'run35-0657.bin']
for t in sorted(info, key=lambda x: info[x][0]):
    if t in refs:
        continue
    when, suite, net = info[t]
    same = [r for r in refs if info[r][1] == suite]
    if not same:
        print('%-12s %s %-16s no reference on its suite' % (t, when.strftime('%m-%d %H:%M'), net)); continue
    r = min(same, key=lambda r: abs((info[r][0] - when).total_seconds()))
    out = subprocess.run(['C:/Python38/python.exe', 'D:/swordai/tools/bench.py', 'compare', t, r], capture_output=True, text=True).stdout
    m = re.search(r'TOTAL = .*?= ([+-][\d.]+) \(([\d.]+)\)\s+->\s+(\w+)', out)
    bots = re.search(r'bot fights.*C-R ([+-][\d.]+) \(([\d.]+)\)', out)
    gap = abs((info[r][0] - when).total_seconds()) / 3600
    print('%-12s %s %-16s vs %-10s (%4.1f h apart): total %s (%s) %s; bots %s' % (t, when.strftime('%m-%d %H:%M'), net, r, gap,
          m.group(1) if m else '?', m.group(2) if m else '?', m.group(3) if m else '', bots.group(1) if bots else '?'))
