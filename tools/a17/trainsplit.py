import re, glob, collections
logs = sorted(glob.glob(r'D:/swordai/run/school-client-1111-2*.log')) + sorted(glob.glob(r'D:/swordai/run/school-client-1112-*.log')) + [r'D:/swordai/run/school-client-live.log']
pat = re.compile(r'in their combo with the sword ready and them in reach: (\d+) ticks, swung (\d+), held by the jump-in guard (\d+), swing not open (\d+) \(crosshair on its body (\d+)\), in the air itself (\d+) \(lifted by their hit (\d+), falling (\d+)\), other (\d+) \(them falling (\d+)\)')
cam = re.compile(r"in their combo, the aim rule's: (\d+) ticks, on body no pick (\d+), off body (\d+); the network's: (\d+) ticks, on body no pick (\d+), off body (\d+)")
ky = re.compile(r'its own jump in their combo, the keep given back: training (\d+) \(swung (\d+)\)')
tk = re.compile(r'their combo: no-jump taught (\d+), its own take-offs (\d+)')
T = collections.Counter(); n = 0; CAM = [0]*6; TK = [0, 0]
for fn in logs:
    for line in open(fn, errors='replace'):
        if 'school update' not in line: continue
        m = pat.search(line)
        if not m: continue
        v = list(map(int, m.groups())); n += 1
        for k, x in zip(['ticks', 'swung', 'guard', 'notopen', 'onbody', 'air', 'lifted', 'falling', 'other', 'otherfalling'], v): T[k] += x
        m2 = cam.search(line)
        if m2:
            for i, x in enumerate(map(int, m2.groups())): CAM[i] += x
        m3 = tk.search(line)
        if m3: TK[0] += int(m3.group(1)); TK[1] += int(m3.group(2))
t = T['ticks']
print('updates', n, 'ticks', t)
for k in ['swung', 'guard', 'notopen', 'onbody', 'air', 'lifted', 'falling', 'other', 'otherfalling']:
    print('  %-12s %6d %5.1f%%' % (k, T[k], 100 * T[k] / t))
offpick = CAM[1] + CAM[2] + CAM[4] + CAM[5]
print('camera split: aim rule ticks %d (no pick on body %d, off body %d); network ticks %d (no pick on body %d, off body %d); no pick total %d (%.1f%%)' % (*CAM, offpick, 100 * offpick / max(1, CAM[0] + CAM[3])))
print('not open but pick ON (rule keep / charge lock) ~ %d (%.1f%%)' % (T['notopen'] - offpick, 100 * (T['notopen'] - offpick) / t))
print('no-jump taught', TK[0], 'own take-offs in their combo', TK[1])
