"""demowins.py [--apply]: the user's recorded fights (profile swordai/demos) matched with what the game said of each
("Fight N vs KIND recorded: you dealt A, took B") in the profile's logs, by the time the file was written. Won = dealt more
than taken. With --apply, the dev run's run/swordai/demos keeps only the won fights against the spacer that comes in; the
lost ones go to demos-lost, unmatched ones (cut short when recording stopped) to demos-aside. The user, 23 Oct 09:20:
"filter out the fight I lost and let it learn the fight I won"."""
import datetime
import glob
import gzip
import os
import re
import shutil
import sys

PROF = r'C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI'
DEV = r'D:/swordai/run/swordai'
LINE = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\].*\[SwordAI\] Fight \d+ vs (.+?) recorded: you dealt (\d+), took (\d+)')

said = []
for path in sorted(glob.glob(PROF + '/logs/*.log.gz')) + [PROF + '/logs/latest.log']:
    name = os.path.basename(path)
    if name == 'latest.log':
        day = datetime.date.fromtimestamp(os.path.getmtime(path))
        text = open(path, encoding='utf-8', errors='replace').read()
    else:
        day = datetime.date.fromisoformat(name[:10])
        text = gzip.open(path, 'rt', encoding='utf-8', errors='replace').read()
    for line in text.splitlines():
        m = LINE.search(line)
        if m:
            t = datetime.datetime.combine(day, datetime.time(int(m[1]), int(m[2]), int(m[3])))
            said.append((t, m[4], int(m[5]), int(m[6])))

rows = []
for f in sorted(glob.glob(PROF + '/swordai/demos/*.bin.gz')):
    name = os.path.basename(f)
    t = datetime.datetime.strptime(name[:15], '%Y%m%d-%H%M%S')
    match = [s for s in said if abs((s[0] - t).total_seconds()) <= 2]
    if match:
        _, kind, dealt, taken = match[0]
        rows.append((name, kind, dealt, taken, 'won' if dealt > taken else 'lost'))
    else:
        rows.append((name, '?', None, None, 'unmatched'))

spacer = [r for r in rows if r[1] == 'spacer']
won = [r for r in spacer if r[4] == 'won']
for r in rows:
    print('%-45s %-18s %5s %5s %s' % (r[0], r[1], r[2], r[3], r[4]))
print('spacer fights: %d, won %d, lost %d; other kinds %d; unmatched %d' % (
    len(spacer), len(won), len(spacer) - len(won), sum(1 for r in rows if r[1] not in ('spacer', '?')), sum(1 for r in rows if r[1] == '?')))

if '--apply' in sys.argv:
    for sub in ('demos', 'demos-lost', 'demos-aside'):
        os.makedirs(os.path.join(DEV, sub), exist_ok=True)
    keep = {r[0] for r in won}
    lost = {r[0] for r in spacer if r[4] == 'lost'}
    for r in rows:
        src = os.path.join(PROF, 'swordai', 'demos', r[0])
        dest = 'demos' if r[0] in keep else 'demos-lost' if r[0] in lost else 'demos-aside'
        for sub in ('demos', 'demos-lost', 'demos-aside'):
            if sub != dest and os.path.exists(os.path.join(DEV, sub, r[0])):
                os.remove(os.path.join(DEV, sub, r[0]))
        shutil.copy2(src, os.path.join(DEV, dest, r[0]))
    print('dev run: demos %d, demos-lost %d, demos-aside %d' % tuple(len(os.listdir(os.path.join(DEV, s))) for s in ('demos', 'demos-lost', 'demos-aside')))
