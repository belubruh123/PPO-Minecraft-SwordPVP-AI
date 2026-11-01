"""duels.py [logs folder] [--by-session]: the installed model's live duels against real players, from the user's game logs.

The user's Minecraft (Modrinth profile "DRL AI") writes one SwordAI chat line per duel ("Stopped (by command). vs <name>, 23s in
control of 24s: hits 22 dealt / 15 taken, kills 1, deaths 0, swings 23 (100% ...), on target 87%, ...") and, before it, the line that says
which network plays ("Playing with the technique school's network (11225259 steps of training) ..."). This reads latest.log and the
archived *.log.gz, and prints per log file and per network: duels, kills-deaths, hits dealt and taken a minute, the ratio of hits with
its standard error over duels, and the swings on target. Opponents' names are never printed.

It is the best measure of the installed model there is: 40 duels in 25 minutes, real opponents, the user's own ping. Compare two networks
on the ratio of hits (dealt / taken) and the win rate, with the standard errors printed here; never on a screen of 45 pairs.
Live A/B (AbPool, 1 Nov): when the school folder of the profile has ab/pool.txt, each duel draws one arm at random and the start line says
"[A/B arm tag]"; the arms are then compared inside the same hours against the same opponents, at the end ("arms"). That comparison, not
a screen against scripted bots and not a comparison of two evenings (the ratio moves +-0.15 from one evening to the next), is what to
decide on.
Read only. Never copy a name out of these logs into a note."""
import glob
import gzip
import math
import os
import re
import sys

base = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('--') else "C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/logs/"
if not base.endswith('/'):
    base += '/'

stopped = re.compile(r"\[(\d\d:\d\d:\d\d)\].*\[CHAT\] \[SwordAI\] Stopped \(by command\)\. vs \S+, (\d+)s in control of (\d+)s: "
                     r"hits (\d+) dealt / (\d+) taken, kills (\d+), deaths (\d+), swings (\d+) \((\d+)% with the opponent under the crosshair\)"
                     r"(?:, on target (\d+)%)?")
playing = re.compile(r"\[CHAT\] \[SwordAI\] The AI now plays your character.*?Playing with (.*?)(?:, the installed|; no rule|\.\s*$|\. )")
steps = re.compile(r"\((\d+) steps of training\)")
arm_tag = re.compile(r"\[A/B arm ([^\]]+)\]")


class Tally:
    def __init__(self):
        self.duels = 0
        self.secs = 0
        self.dealt = 0
        self.taken = 0
        self.kills = 0
        self.deaths = 0
        self.swings = 0
        self.on_target = []
        self.ratios = []

    def add(self, secs, dealt, taken, kills, deaths, swings, on_target):
        self.duels += 1
        self.secs += secs
        self.dealt += dealt
        self.taken += taken
        self.kills += kills
        self.deaths += deaths
        self.swings += swings
        if on_target is not None:
            self.on_target.append(on_target)
        if dealt + taken > 0:
            self.ratios.append(dealt / (dealt + taken))

    def line(self):
        if self.duels == 0:
            return 'no duels'
        mins = max(self.secs, 1) / 60.0
        share = [r for r in self.ratios]
        mean = sum(share) / len(share) if share else float('nan')
        sd = math.sqrt(sum((x - mean) ** 2 for x in share) / max(1, len(share) - 1)) if len(share) > 1 else float('nan')
        se = sd / math.sqrt(len(share)) if len(share) > 1 else float('nan')
        ot = sum(self.on_target) / len(self.on_target) if self.on_target else float('nan')
        return ('%3d duels, %3d-%-3d kills-deaths, hits %4d dealt %4d taken (%.2f; %.0f / %.0f a minute), share of hits dealt %.3f (se %.3f), '
                'on target %.0f%%' % (self.duels, self.kills, self.deaths, self.dealt, self.taken, self.dealt / max(1, self.taken),
                                       self.dealt / mins, self.taken / mins, mean, se, ot))


files = sorted(glob.glob(base + '*.log.gz')) + [base + 'latest.log']
total = {}
for f in files:
    opener = gzip.open if f.endswith('.gz') else open
    per_file = {}
    net = 'unknown network'
    try:
        with opener(f, 'rt', encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if '[CHAT] [SwordAI]' not in line:
                    continue
                m = playing.search(line)
                if m:
                    text = m.group(1)
                    s = steps.search(text)
                    a = arm_tag.search(line)
                    net = ('arm ' + a.group(1)) if a else (('school network %s steps' % s.group(1)) if s else text[:60])
                    continue
                m = stopped.search(line)
                if not m:
                    continue
                secs = int(m.group(2))
                if secs < 5:
                    continue
                on_target = int(m.group(10)) if m.group(10) else None
                for tally in (per_file.setdefault(net, Tally()), total.setdefault(net, Tally())):
                    tally.add(secs, int(m.group(4)), int(m.group(5)), int(m.group(6)), int(m.group(7)), int(m.group(8)), on_target)
    except Exception as e:  # a truncated archive
        print(os.path.basename(f), 'unreadable:', e)
        continue
    if per_file:
        print(os.path.basename(f))
        for net, t in per_file.items():
            print('   %-34s %s' % (net, t.line()))
print()
print('all logs, by network')
for net, t in total.items():
    print('   %-34s %s' % (net, t.line()))

arms = {k: v for k, v in total.items() if k.startswith('arm ')}
if len(arms) >= 2:
    print()
    print('arms of the live A/B pool (same hours, same kind of opponents); difference = share of hits dealt, arm minus the baseline')
    base_key = 'arm installed' if 'arm installed' in arms else sorted(arms)[0]
    b = arms[base_key]

    def stat(t):
        share = t.ratios
        mean = sum(share) / len(share)
        sd = math.sqrt(sum((x - mean) ** 2 for x in share) / max(1, len(share) - 1)) if len(share) > 1 else float('nan')
        return mean, sd / math.sqrt(len(share)) if len(share) > 1 else float('nan')

    bm, bse = stat(b)
    for k in sorted(arms):
        t = arms[k]
        m, se = stat(t)
        if k == base_key:
            print('   %-28s baseline: share %.3f (se %.3f) over %d duels' % (k, m, se, t.duels))
        else:
            d = m - bm
            dse = math.sqrt(se * se + bse * bse)
            print('   %-28s share %.3f (se %.3f) over %d duels; vs %s %+.3f (se %.3f, %+.1f se)' % (k, m, se, t.duels, base_key, d, dse, d / dse if dse > 0 else float('nan')))
