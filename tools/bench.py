"""bench.py: the scorer of the SwordAI benchmark (docs/BENCHMARK.md). Python 3.8, standard library only.

  C:/Python38/python.exe -I tools/bench.py weights [--fights DIR] [--from YYYYMMDD] [--to YYYYMMDD]
  C:/Python38/python.exe -I tools/bench.py score TAG [--all-bots]
  C:/Python38/python.exe -I tools/bench.py compare TAG_C TAG_R [--all-bots] [--out FILE]
  C:/Python38/python.exe -I tools/bench.py selftest [--fights DIR]
  (--bench DIR reads and writes another bench folder instead of run/swordai/bench)

Input: the round timelines written by src/client/java/dev/swordai/client/RoundRecorder.java (gzipped text: a summary on
top, then the header `tick,dist,mySword,oppSword,myHp,oppHp,me,opp,oppUp,oppToward,oppSide,myToward,keys,rules,event` and
one row a tick, 20 a second). "me"/"my" is the AI being measured; "opp"/"they" is the other.
  - Benchmark episodes: run/swordai/bench/<tag>/<iiii>-<bot>.txt.gz (iiii = episode index of tools/bench/suite-v1.txt,
    the same seed for every tag, so tags are paired by it; bot = the suite line's bot, or h2h), a DONE file at the end.
  - The user's real duels: profiles/DRL AI/swordai/fights/*.txt.gz (read only). Their file names and summary lines hold
    the opponents' names: this script never prints or writes them, and refers to real duels by index only.

What it does:
  weights  runs the situation detectors on the real duels and writes run/swordai/bench/weights.json: per test, the
           situations per real fight and per minute (the weight of the test), plus the part split from
           run/swordai/bench/weights.txt (made with the default 0.5 / 0.25 / 0.25 if missing).
  score    per test of one tag: situations, rate, mean HP per situation; head-to-head and bot-fight HP per fight.
  compare  C against R, paired by episode index: per test the rates, the HP difference per situation with its standard
           error (per episode the mean HP of its situations, then the paired difference over episodes), times the
           weight = HP per real fight; the skills part = the sum of the tests divided by how deep their HP windows
           overlap in real fights (weights.json window_overlap), so that it is HP per real fight; the head-to-head part
           (C's h2h net minus R's); the bot-fight part (every non-h2h episode's net, C minus R); the TOTAL with its
           propagated standard error; the VERDICT. Writes run/swordai/bench/latest.json for the panel.
           A tag folder holds <iiii>-<bot>.txt.gz episodes (anything else there, hits.csv, jumps.csv, suite.txt,
           network.txt, is ignored) and a DONE of "key value" lines at the end (SchoolSession.finishBench).
  selftest runs the detectors on the real duels (counts, rates, a spot-check of one situation per test, rows only, no
           names) and runs compare end to end on two halves of the real duels (even and odd files as fake tags,
           written to selftest.json, never latest.json).

HP: a situation's HP is counted over the 40 ticks from its start (from the tick before it to 39 after): the opponent's
HP lost minus its own HP lost. Where the opponent's HP is not visible (the user's live duels on servers since 1 Nov show it
as a constant), only its own HP lost counts (net = minus own HP lost); every output says how many situations had both.

Every detector below says which technique of docs/PVP-TECHNIQUES.md it is and quotes the document; the numbers it uses
are the document's (the sword back after 12.5 ticks, a jump of about 12 ticks: Part 8), the benchmark design's
(docs/BENCHMARK.md: the stun = its hit and no hit back for 10 ticks; 40-tick HP window), the recorder's (an exchange ends
after 20 ticks with no hit) or the code's existing ones (a sword is ready at 0.9 charge, Body.CHARGED; the bots' T4
moment is "their swing began, the sword is back and they are near", reach < 4.2, PvpPlayer.edges; the jump-in of
tools/jumpins.py and tools/knockaway.py). A situation must clearly be that situation: when unsure, it is not counted.
"""
import glob
import gzip
import json
import math
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BENCH = os.path.join(REPO, 'run', 'swordai', 'bench')
FIGHTS = 'C:/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI/swordai/fights'
SUITE = os.path.join(HERE, 'bench', 'suite-v1.txt')

WINDOW = 40         # HP window of a situation, ticks (docs/BENCHMARK.md)
GAP = 20            # no hit either way for this long: a new exchange (RoundRecorder.EXCHANGE_GAP); also "in a row"
READY = 0.9         # a sword is ready at this charge (Body.CHARGED, StepFacts.OPP_READY)
ATTACK_CHARGE = 0.8  # their seen swing counts as an attack only when their sword was this far back (not a click on an
                    # empty sword; the arm is read a tick late, so a swing made at 0.9 can read 0.8)
NEAR = 4.2          # "they are near" (PvpPlayer.edges, the bots' own T4 moment): eye-to-hitbox distance
SWORD_BACK = 12     # the sword is back after 12.5 ticks (Part 8): "right after their attack" = before theirs is back
STUN_TICKS = 10     # a stun: its sprint hit and no hit back for 10 ticks (docs/BENCHMARK.md)
MATCH = 8           # a DEALT is matched to its own swing at most this many ticks before it (seen: 1-6)
JUMP_SPAN = 16      # a jump-in's air time as tools/jumpins.py looks at it (a jump is about 12 ticks, Part 8, plus lag)
LIFT_SPAN = 30      # after a lift, the landing is looked for this long
MAX_DROP = 16       # a one-tick HP drop above this is not a sword hit (a respawn, a reset): not counted
H2H = 'h2h'         # the bot-fight part is every other episode (all the suite's bots), paired by episode index


# ---------------------------------------------------------------------------------------------------------------------
# One recorded round or episode.

class Hit(object):
    """One of its own hits: d = the tick of DEALT, u = the tick of the swing it came from, kind = what the swing was
    (crit, sprint, plain, sweep, early; as the game made it out, FightSession.endTick), None when no swing was found."""
    __slots__ = ('d', 'u', 'kind')

    def __init__(self, d, u, kind):
        self.d, self.u, self.kind = d, u, kind


class Episode(object):
    NEEDED = ('tick', 'dist', 'mySword', 'oppSword', 'myHp', 'oppHp', 'me', 'opp', 'oppUp', 'oppToward', 'myToward',
              'keys', 'event')

    def __init__(self, path, idx=None, bot=None):
        self.path = path
        self.idx = idx
        self.bot = bot
        names, raw = None, []
        with gzip.open(path, 'rt', encoding='utf-8', errors='replace') as fh:
            for line in fh:
                line = line.rstrip('\r\n')
                if names is None:
                    if line.startswith('tick,'):
                        names = line.split(',')
                    continue
                if not line or not line[0].isdigit():
                    continue
                p = line.split(',', len(names) - 1)
                if len(p) == len(names):
                    raw.append(p)
        if names is None:
            raise ValueError('no timeline header')
        missing = [c for c in self.NEEDED if c not in names]
        if missing:
            raise ValueError('timeline without ' + ', '.join(missing))
        col = {c: i for i, c in enumerate(names)}
        rows = []
        for p in raw:
            try:
                rows.append((float(p[col['dist']]), float(p[col['mySword']]), float(p[col['oppSword']]),
                             float(p[col['myHp']]), float(p[col['oppHp']]), p[col['me']], p[col['opp']],
                             float(p[col['oppUp']]), float(p[col['oppToward']]), float(p[col['myToward']]),
                             p[col['keys']], p[col['rules']] if 'rules' in col else '', p[col['event']], p))
            except ValueError:
                continue
        self.raw_rows = [r[13] for r in rows]
        self.header = names
        self.n = n = len(rows)
        self.dist = [r[0] for r in rows]
        self.mySw = [r[1] for r in rows]
        self.oppSw = [r[2] for r in rows]
        self.myHp = [r[3] for r in rows]
        self.oppHp = [r[4] for r in rows]
        self.meAir = [r[5].startswith('a') for r in rows]
        self.oppAir = [r[6].startswith('a') for r in rows]
        self.oppSprint = [r[6].endswith('s') for r in rows]
        self.oppUp = [r[7] for r in rows]
        self.oppToward = [r[8] for r in rows]
        self.myToward = [r[9] for r in rows]
        self.keys = [r[10] for r in rows]
        self.ev = [r[12] for r in rows]
        self.dealt = [False] * n
        self.taken = [False] * n
        self.takenCrit = [False] * n
        self.swing = [None] * n
        for t, e in enumerate(self.ev):
            for tok in e.split():
                if tok == 'DEALT':
                    self.dealt[t] = True
                elif tok.startswith('TAKEN'):
                    self.taken[t] = True
                    if 'crit?' in tok.split(':')[1:]:
                        self.takenCrit[t] = True
                elif tok.startswith('swing:'):
                    self.swing[t] = tok[6:]
        # Their swing as this client saw it: the arm started a swing, so their sword charge read off it fell back
        # (BodyState.noteOpponentSwing). In the real duels 98% of the hits taken come on the very tick of such a fall.
        self.theirSwing = [False] + [self.oppSw[t] < self.oppSw[t - 1] - 0.05 for t in range(1, n)]
        self.has_swings = any(k is not None for k in self.swing)
        # Its own hits, each matched to the newest swing that reached someone (kind other than miss) in the MATCH ticks
        # before the DEALT (the hit shows a round trip after the swing).
        self.hits = []
        used = set()
        for d in range(n):
            if not self.dealt[d]:
                continue
            u = None
            for k in range(d - 1, max(-1, d - MATCH - 1), -1):
                if self.swing[k] is not None and self.swing[k] != 'miss' and k not in used:
                    u = k
                    break
            if u is not None:
                used.add(u)
                self.hits.append(Hit(d, u, self.swing[u]))
            else:
                self.hits.append(Hit(d, None, None))
        self.hitsBySwing = sorted((h for h in self.hits if h.u is not None), key=lambda h: h.u)
        self.hitAt = {h.d: h for h in self.hits}
        # The opponent's HP is visible when it ever falls; or when it never got hit and reads above 1. The live duels on
        # servers since 1 Nov read a constant (1 or 20) with hits dealt: not visible.
        drops = any(self.oppHp[k] < self.oppHp[k - 1] for k in range(1, n))
        self.opp_visible = drops or (not any(self.dealt) and n > 0 and max(self.oppHp) > 1.5)
        self._pt = self._prefix(self.taken)
        self._pd = self._prefix(self.dealt)
        self._ps = self._prefix([k is not None for k in self.swing])

    @staticmethod
    def _prefix(xs):
        out = [0]
        for x in xs:
            out.append(out[-1] + (1 if x else 0))
        return out

    def _count(self, pre, a, b):
        a = max(a, 0)
        b = min(b, self.n - 1)
        return 0 if b < a else pre[b + 1] - pre[a]

    def n_taken(self, a, b):
        """Hits taken in ticks a..b (both included)."""
        return self._count(self._pt, a, b)

    def n_dealt(self, a, b):
        return self._count(self._pd, a, b)

    def n_swings(self, a, b):
        return self._count(self._ps, a, b)

    def first_hit_swung(self, lo, hi):
        """Its first hit whose swing is in ticks lo..hi."""
        for h in self.hitsBySwing:
            if h.u > hi:
                return None
            if h.u >= lo:
                return h
        return None

    def stun(self, h):
        """T3 as docs/BENCHMARK.md measures it: its sprint hit, and no hit back for 10 ticks (a hit of theirs on the very
        tick its hit shows is a trade, not a stun: counted as a hit back)."""
        return h is not None and h.kind == 'sprint' and self.n_taken(h.d, h.d + STUN_TICKS) == 0

    def reply_stun(self, s, since):
        """Its answer stuns: its first hit swung after tick s and before their sword is back (s+1..s+12) is a stun, and
        no hit of theirs came after tick `since` and before it (else it was hit through, T4)."""
        h = self.first_hit_swung(s + 1, s + SWORD_BACK)
        return bool(h is not None and self.stun(h) and self.n_taken(since + 1, h.d) == 0)

    def lost(self, hp, a, b):
        """HP lost from tick a-1 to tick b-1 (drops between consecutive ticks k-1 -> k for a <= k < b)."""
        s = 0.0
        for k in range(max(1, a), min(self.n, b)):
            d = hp[k - 1] - hp[k]
            if 0 < d <= MAX_DROP:
                s += d
        return s

    def hp(self, s, length=WINDOW):
        """(net, own HP lost, their HP lost or None) over the window from tick s (from the tick before it)."""
        mine = self.lost(self.myHp, s, s + length)
        theirs = self.lost(self.oppHp, s, s + length) if self.opp_visible else None
        return (theirs or 0.0) - mine, mine, theirs

    def whole(self):
        return self.hp(1, self.n)

    def knockouts(self):
        """(it knocked them out, it was knocked out): HP at 0 at the end."""
        if not self.n:
            return False, False
        return (self.opp_visible and self.oppHp[-1] <= 0.0), self.myHp[-1] <= 0.0


# ---------------------------------------------------------------------------------------------------------------------
# The situation detectors, one per test of docs/BENCHMARK.md. Each returns a list of (start tick, outcome dict).

def det_punish(ep):
    """Punish crit (B1, T1c). B1: "the crit you get from falling after someone attacks you. Their hit knocks you up; you
    crit them on the way down." Part 8: "A hit lifts the target only if it is on the ground".
    Start: their hit (TAKEN) while it was on the ground the tick before, and it is in the air within 3 ticks (lifted).
    Outcome 'landed': its crit (a swing the game made a crit, with its DEALT) swung after the hit and before it lands
    again. 'double' (shown only): T1c, "after you punish-crit them, immediately jump and crit again": another crit of
    its own swung within 25 ticks (a jump and a sword recharge) of that one."""
    out = []
    for s in range(1, ep.n):
        if not ep.taken[s] or ep.meAir[s - 1]:
            continue
        a = next((k for k in range(s, min(ep.n, s + 4)) if ep.meAir[k]), None)
        if a is None:
            continue
        land = next((k for k in range(a + 1, min(ep.n, s + LIFT_SPAN)) if not ep.meAir[k]), min(ep.n, s + LIFT_SPAN))
        crit = None
        for h in ep.hitsBySwing:
            if h.u > land:
                break
            if h.u >= s and h.kind == 'crit':
                crit = h
                break
        double = False
        if crit is not None:
            double = any(h.kind == 'crit' and crit.u < h.u <= crit.u + 25 for h in ep.hitsBySwing)
        out.append((s, {'landed': crit is not None, 'double': double}))
    return out


def det_jumpin(ep):
    """Jump-in defence (K2, T8). K2: "Don't knockback-hit someone who is falling ... Wait until they are on the ground
    or jumping up"; T8: "If the opponent jumps at you and you hit them as they are rising ... usually a free stun".
    Start: their take-off at it, as tools/jumpins.py and tools/knockaway.py count it: ground to air (seen rising: oppUp
    above 0.1 within 3 ticks), coming at it (oppToward at least 0.1), from 2 to 6 blocks, not lifted by its own hit (no
    DEALT in the 3 ticks before). Outcomes over the jump (16 ticks): 'crits' = their hits taken that the recorder marks
    crit? (lower is better); 'kbFalling' = its sprint hits (the knockback hit, Part 0) swung while they were in the air
    going down (K2 broken; lower is better); 'rising' (shown only) = its hits swung while they were still going up."""
    out = []
    for s in range(1, ep.n):
        if not (ep.oppAir[s] and not ep.oppAir[s - 1]):
            continue
        if max(ep.oppUp[s:s + 4]) <= 0.1 or ep.n_dealt(s - 3, s) or ep.oppToward[s] < 0.1:
            continue
        if not 2.0 <= ep.dist[s] < 6.0:
            continue
        end = min(ep.n - 1, s + JUMP_SPAN)
        crits = sum(1 for k in range(s, end + 1) if ep.takenCrit[k])
        falling = rising = 0
        for h in ep.hitsBySwing:
            if h.u > end:
                break
            if h.u >= s and ep.oppAir[h.u]:
                if ep.oppUp[h.u] < 0 and h.kind == 'sprint':
                    falling += 1
                if ep.oppUp[h.u] > 0:
                    rising += 1
        out.append((s, {'crits': crits, 'kbFalling': falling, 'rising': rising}))
    return out


def _my_swings_before(ep, s, span):
    return ep.n_swings(s - span, s - 1) > 0


def det_deflect(ep):
    """Deflection (T4). "Sprint-hit the opponent immediately after they attack you (the same thing as hit selecting)";
    Part 0: "Hit selecting: waiting for the opponent's hit, then hitting right after it. The deflection (T4) is the same
    idea." "A well-timed, well-spaced deflection stuns them."
    Start: their swing (seen on their arm, their sword at least 0.8 back: an attack) that hits it (TAKEN within -1..+3
    ticks), with them near (under 4.2) and its own sword ready (0.9): the bots' own T4 moment (PvpPlayer.edges). Not
    when it swung itself in the 12 ticks before (then their hit is a deflection of its attack: reverse deflection's).
    Outcome 'stun': its first hit swung after their swing and before their sword is back (12 ticks) is a sprint hit
    with no hit back for 10 ticks, and no other hit of theirs came before it ("they hit you through your deflection")."""
    out = []
    for s in range(1, ep.n):
        if not ep.theirSwing[s] or ep.oppSw[s - 1] < ATTACK_CHARGE or ep.dist[s] >= NEAR or ep.mySw[s] < READY:
            continue
        if _my_swings_before(ep, s, SWORD_BACK):
            continue
        ta = next((k for k in range(max(0, s - 1), min(ep.n, s + 4)) if ep.taken[k]), None)
        if ta is None:
            continue
        out.append((s, {'stun': ep.reply_stun(s, max(s, ta))}))
    return out


def det_reverse(ep):
    """Reverse deflection (T5). "Sprint-hit your opponent immediately after they use a deflection on you"; their
    deflection = T4 from their side: "sprint-hit the opponent immediately after they attack you", hit selecting =
    "waiting for the opponent's hit, then hitting right after it" (part 0).
    Start: its own swing (hit or miss: its attack) with them near (under 4.2) and their sword ready (0.9: waiting for
    it, as the bots' T4 moment has it); their next swing after it is a hit on it within 12 ticks (TAKEN, the swing seen
    up to 3 ticks before it), the first hit of theirs since its swing, with them seen sprinting on its tick or the one
    before (a sprint hit: in the real duels such hits knock it 0.54 blocks a tick, the others 0.32). Window from their
    hit. Outcome 'stun': its first hit swung after theirs and before their sword is
    back (12 ticks) is a sprint hit with no hit back for 10 ticks and none before it. ("Only works if their deflection
    did not stun you" is part of the outcome, not the start.)"""
    out = []
    for s in range(1, ep.n):
        if not ep.taken[s]:
            continue
        u = next((k for k in range(s - 1, max(-1, s - SWORD_BACK - 1), -1) if ep.swing[k] is not None), None)
        if u is None or ep.n_taken(u, s - 1) or ep.dist[u] >= NEAR or ep.oppSw[u] < READY:
            continue
        sw = next((k for k in range(u + 1, min(ep.n, s + 2)) if ep.theirSwing[k]), None)
        if sw is None or sw < s - 3:
            continue
        if not (ep.oppSprint[s] or ep.oppSprint[s - 1]):
            continue
        out.append((s, {'stun': ep.reply_stun(s, s)}))
    return out


def det_miss(ep):
    """Make them miss (T6, T6a). "When the opponent goes to attack you, stop moving or hold S, so their swing misses. As
    soon as you see them miss, your next sprint hit stuns them"; T6a: strafe so they miss.
    Start: their swing (seen on their arm, their sword at least 0.8 back: an attack) with them near (under 4.2) that
    does not hit it (no TAKEN up to 4 ticks after), not in the 10 ticks after a hit taken (then no swing of theirs can
    hit it: not a miss it made). Outcome 'stun': its first hit swung after their swing and before their sword is back
    (12 ticks) is a sprint hit with no hit back for 10 ticks and none before it."""
    out = []
    for s in range(1, ep.n):
        if not ep.theirSwing[s] or ep.oppSw[s - 1] < ATTACK_CHARGE or ep.dist[s] >= NEAR:
            continue
        if ep.n_taken(s - 10, s + 4):
            continue
        out.append((s, {'stun': ep.reply_stun(s, s)}))
    return out


def det_threeblock(ep):
    """Three-block hit (T7). "Sprint-hit the opponent before they are close enough to hit you back" at the start of a
    trade. Start: it comes within 4 blocks (from 4 or more the tick before) with no hit either way in the 20 ticks
    before (a new exchange); one start per 20 ticks (the distance jitters around 4). Outcome 'won': the first hit of the
    trade (within the 40 ticks) is its own, not on the same tick as theirs, a sprint hit, and no hit back for 10 ticks.
    'first' (shown only): the first hit is its own."""
    out = []
    last = -1000
    for s in range(1, ep.n):
        if not (ep.dist[s - 1] >= 4.0 and ep.dist[s] < 4.0):
            continue
        prev, last = last, s
        if s - prev <= GAP or ep.n_dealt(s - GAP, s) or ep.n_taken(s - GAP, s):
            continue
        t = next((k for k in range(s, min(ep.n, s + WINDOW)) if ep.dealt[k] or ep.taken[k]), None)
        first = t is not None and ep.dealt[t] and not ep.taken[t]
        out.append((s, {'won': bool(first and ep.stun(ep.hitAt.get(t))), 'first': bool(first)}))
    return out


def det_jumpreset(ep):
    """Jump reset (T9, T9a, T9b, T9c). "A way to take reduced horizontal knockback: press jump at the same moment you
    take damage." Start: their hit (TAKEN) while it was on the ground the tick before (a jump is pressed from the
    ground). Outcome 'kb' (lower is better): the knockback taken, read as its fastest speed away from the opponent in
    the hit tick and the 3 after (blocks a tick; minus myToward). 'jumped' (shown only): jump pressed from the tick
    before to the tick after the hit."""
    out = []
    for s in range(1, ep.n):
        if not ep.taken[s] or ep.meAir[s - 1]:
            continue
        kb = max(0.0, -min(ep.myToward[s:s + 4]))
        jumped = any('j' in ep.keys[k] for k in range(s - 1, min(ep.n, s + 2)))
        out.append((s, {'kb': kb, 'jumped': jumped}))
    return out


def det_holdcombo(ep):
    """Holding a combo (T10, T11a, T11b, T11c, T2). T3: "Combos are a chain of stuns"; the hops "hold players in combos".
    Its hits in a row: each within 20 ticks of the one before and no hit taken in between. Start: the first stun of a
    row (its sprint hit with no hit back for 10 ticks), at the DEALT. Outcome 'more': its hits in that row after the
    first stun (higher is better)."""
    out = []
    row = []
    rows = []
    for h in ep.hits:
        if row and (h.d - row[-1].d > GAP or ep.n_taken(row[-1].d + 1, h.d)):
            rows.append(row)
            row = []
        row.append(h)
    if row:
        rows.append(row)
    for r in rows:
        for i, h in enumerate(r):
            if ep.stun(h):
                out.append((h.d, {'more': len(r) - i - 1}))
                break
    return out


def det_combosout(ep):
    """Getting out of combos (C2, C3, C4, C7). C3: "when the opponent stuns you, jump and hold S, so that you fly out of
    the trade"; "Common mistake: holding S only a hit or two after being stunned ... you take more damage".
    Their hits in a row: each within 20 ticks of the one before and no hit of its own in between. Start: their second
    hit of a row. Outcome 'third': they land a third (lower is better)."""
    out = []
    row = []
    for t in range(ep.n):
        if not ep.taken[t]:
            continue
        if row and (t - row[-1] > GAP or ep.n_dealt(row[-1] + 1, t)):
            row = []
        row.append(t)
        if len(row) == 2:
            out.append([t, {'third': False}])
        elif len(row) == 3:
            out[-1][1]['third'] = True
    return [tuple(x) for x in out]


def det_crits(ep):
    """Crits (T1, T1a, T1b, T2, C1, C6): the crit trades. Part 0: "Crit: a hit made while falling ... 1.5x damage"; T1:
    "you win the crit trade with an extra crit". Crits: its own (a swing the game made a crit, with its DEALT) and
    theirs (a hit taken that the recorder marks crit?). Start: a crit of either side with no crit of either side in
    the 20 ticks before (a crit trade begins). Outcomes over the 40 ticks: 'net' = its crits minus theirs (count);
    'dmg' = the damage of its crits minus the damage of theirs (HP; None when their HP is not visible)."""
    ev = []
    for h in ep.hitsBySwing:
        if h.kind == 'crit':
            ev.append((h.u, 1, h))
    for t in range(ep.n):
        if ep.takenCrit[t]:
            ev.append((t, -1, None))
    ev.sort(key=lambda e: e[0])
    out = []
    last = -1000
    for c, side, _ in ev:
        if c - last > GAP:
            mine = [e for e in ev if c <= e[0] < c + WINDOW and e[1] == 1]
            theirs = [e for e in ev if c <= e[0] < c + WINDOW and e[1] == -1]
            dmg = None
            if ep.opp_visible:
                dmg = sum(ep.lost(ep.oppHp, e[2].d, e[2].d + 2) for e in mine) - sum(ep.lost(ep.myHp, e[0], e[0] + 2) for e in theirs)
            out.append((c, {'net': len(mine) - len(theirs), 'dmg': dmg}))
        last = c
    return out


def det_basics(ep):
    """Basics (the old stages 1-3: aim, finding it again, hit timing). One per episode. Outcome 'landed' = its swings
    that reached the opponent / its swings. The recording has no crosshair or lost-sight column, so "on target" and
    "found again" cannot be read from it. Rate only: not in the total (its HP would be the whole fight's)."""
    sw = [k for k in ep.swing if k is not None]
    if not sw:
        return []
    return [(0, {'swings': len(sw), 'landed': sum(1 for k in sw if k != 'miss')})]


# Each test: key, name, technique IDs, the bots of docs/BENCHMARK.md that create it, the detector, its rate (outcome key,
# 'share' or 'mean', +1 = higher is better, -1 = lower is better, label), extra outcomes shown, in the total or not.
TESTS = [
    dict(key='punish', name='Punish crit', ids='B1 T1c', bots=('punish-critter', 'jump-in-critter'), det=det_punish,
         rate=('landed', 'share', 1, 'crit landed on the way down'), extra=[('double', 'share', 'then crit again (T1c)')]),
    dict(key='jumpin', name='Jump-in defence', ids='K2 T8', bots=('jump-in-critter',), det=det_jumpin,
         rate=('crits', 'mean', -1, 'crits taken per jump-in'),
         extra=[('kbFalling', 'mean', 'KB hits on falling jumpers per jump-in'), ('rising', 'mean', 'hits on rising jumpers')]),
    dict(key='deflect', name='Deflection', ids='T4', bots=('swinger', 'attack-then-s'), det=det_deflect,
         rate=('stun', 'share', 1, 'stun right after their hit'), extra=[]),
    dict(key='reverse', name='Reverse deflection', ids='T5', bots=('deflector',), det=det_reverse,
         rate=('stun', 'share', 1, 'stun right after their deflection'), extra=[]),
    dict(key='miss', name='Make them miss', ids='T6 T6a', bots=('swinger',), det=det_miss,
         rate=('stun', 'share', 1, 'their miss, then its stun'), extra=[]),
    dict(key='threeblock', name='Three-block hit', ids='T7', bots=('straight-sprinter', 'three-block'), det=det_threeblock,
         rate=('won', 'share', 1, 'first hit, sprint, no hit back'), extra=[('first', 'share', 'first hit its own')]),
    dict(key='jumpreset', name='Jump reset', ids='T9 T9a T9b T9c', bots=('rhythm-comboer', 'stunner', 'deflector'),
         det=det_jumpreset, rate=('kb', 'mean', -1, 'knockback taken (blocks/tick)'),
         extra=[('jumped', 'share', 'jump pressed on the hit')]),
    dict(key='holdcombo', name='Holding a combo', ids='T10 T11a T11b T11c T2', bots=('stunner', 'stun-escaper', 'mixer'),
         det=det_holdcombo, rate=('more', 'mean', 1, 'hits in a row after its first stun'), extra=[]),
    dict(key='combosout', name='Getting out of combos', ids='C2 C3 C4 C7',
         bots=('rhythm-comboer', 'uppercutter', 'lunger', 'hopper'), det=det_combosout,
         rate=('third', 'share', -1, 'their third hit in a row'), extra=[]),
    dict(key='crits', name='Crits', ids='T1 T1a T1b T2 C1 C6', bots=('standing-critter', 'double-critter', 'crit-breaker'),
         det=det_crits, rate=('dmg', 'mean', 1, 'crit damage dealt - taken'), extra=[('net', 'mean', 'crits dealt - taken')]),
    dict(key='basics', name='Basics (stages 1-3)', ids='stages 1-3', bots=None, det=det_basics,
         rate=('landed', 'ratio', 1, 'swings landing'), extra=[], total=False),
]
TEST_BY_KEY = {t['key']: t for t in TESTS}


def in_total(test):
    return test.get('total', True)


# ---------------------------------------------------------------------------------------------------------------------
# Situations of an episode, with their HP.

class Situation(object):
    __slots__ = ('test', 'start', 'out', 'net', 'mine', 'theirs')

    def __init__(self, test, start, out, hp):
        self.test, self.start, self.out = test, start, out
        self.net, self.mine, self.theirs = hp


def situations(ep):
    """{test key: [Situation]} for one episode (cached on it)."""
    cached = getattr(ep, '_sits', None)
    if cached is not None:
        return cached
    res = {}
    for t in TESTS:
        res[t['key']] = [Situation(t['key'], s, o, ep.hp(s)) for s, o in t['det'](ep)]
    ep._sits = res
    return res


def uses(test, ep, all_bots):
    """Whether the episode's situations count for the test: its bots per docs/BENCHMARK.md (all skill episodes with
    --all-bots; never the head-to-head)."""
    if ep.bot == H2H:
        return False
    if all_bots or test['bots'] is None:
        return True
    return ep.bot in test['bots']


def rate_of(test, sits):
    """(value, label) of the test's rate over the situations."""
    key, kind, _, label = test['rate']
    if kind == 'ratio':
        a = sum(s.out['landed'] for s in sits)
        b = sum(s.out['swings'] for s in sits)
        return (a / b if b else None), label
    vals = [s.out[key] for s in sits if s.out.get(key) is not None]
    if test['key'] == 'crits' and not vals:
        vals = [s.out['net'] for s in sits]
        label = 'crits dealt - taken (count; their HP not visible)'
    if not vals:
        return None, label
    return sum(float(v) for v in vals) / len(vals), label


def extra_of(key, kind, sits):
    vals = [s.out[key] for s in sits if s.out.get(key) is not None]
    return sum(float(v) for v in vals) / len(vals) if vals else None


def fmt_rate(v, kind):
    if v is None:
        return '-'
    if kind in ('share', 'ratio'):
        return '%3.0f%%' % (100 * v)
    return '%.2f' % v


def mean_se(xs):
    xs = [x for x in xs if x is not None]
    n = len(xs)
    if not n:
        return None, None, 0
    m = sum(xs) / n
    if n < 2:
        return m, None, n
    sd = math.sqrt(sum((x - m) ** 2 for x in xs) / (n - 1))
    return m, sd / math.sqrt(n), n


# ---------------------------------------------------------------------------------------------------------------------
# Files.

def real_duels(folder, lo='00000000', hi='99999999'):
    """The user's real duels, oldest first, as Episodes with idx = their place in that order. Names never leave here."""
    out = []
    bad = 0
    for i, path in enumerate(sorted(glob.glob(os.path.join(folder, '*.txt.gz')))):
        stamp = os.path.basename(path)[:8]
        if not (lo <= stamp <= hi):
            continue
        try:
            ep = Episode(path, idx=i, bot='real')
        except (OSError, ValueError, EOFError):
            bad += 1
            continue
        if ep.n >= 20:
            ep.day = stamp
            out.append(ep)
    if bad:
        print('%d real duel files could not be read' % bad)
    return out


EPISODE_NAME = re.compile(r'^(\d{4})-(.+)\.txt\.gz$')


def read_done(folder):
    """The DONE file SchoolSession.finishBench writes at the end of a suite: "key value" lines (tag, suite-episodes,
    episodes, written-now, written-before, left-out-no-league, left-out-broken-off, network, network-sha256, league,
    started, finished; a value may hold spaces). None when there is no DONE (the run is not finished)."""
    path = os.path.join(folder, 'DONE')
    if not os.path.exists(path):
        return None
    info = {}
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            p = line.strip().split(None, 1)
            if p:
                v = p[1].strip() if len(p) > 1 else ''
                info[p[0]] = int(v) if re.match(r'^-?\d+$', v) else v
    return info


def load_tag(tag):
    """The episodes of a tag: only <iiii>-<bot>.txt.gz (hits.csv, jumps.csv, suite.txt, network.txt and the rest of the
    folder are not episodes), and its DONE as a dict (None if missing)."""
    folder = os.path.join(BENCH, tag)
    if not os.path.isdir(folder):
        sys.exit('no benchmark episodes for tag %s (%s)' % (tag, folder))
    eps = []
    for path in sorted(glob.glob(os.path.join(folder, '*.txt.gz'))):
        m = EPISODE_NAME.match(os.path.basename(path))
        if not m:
            continue
        try:
            eps.append(Episode(path, idx=int(m.group(1)), bot=m.group(2)))
        except (OSError, ValueError, EOFError) as e:
            print('episode %s unreadable: %s' % (os.path.basename(path), e))
    return eps, read_done(folder)


def done_notes(tag, info):
    """What a tag's DONE says is missing, as notes."""
    if info is None:
        return ['%s has no DONE file: its run is not finished' % tag]
    out = []
    if isinstance(info.get('episodes'), int) and isinstance(info.get('suite-episodes'), int) and info['episodes'] < info['suite-episodes']:
        out.append('%s has %d of the suite\'s %d episodes' % (tag, info['episodes'], info['suite-episodes']))
    for k in ('left-out-no-league', 'left-out-broken-off'):
        if isinstance(info.get(k), int) and info[k] > 0:
            out.append('%s: %s %d' % (tag, k, info[k]))
    return out


def read_parts():
    """The part split of run/swordai/bench/weights.txt; made with the default when missing."""
    path = os.path.join(BENCH, 'weights.txt')
    parts = {'skills': 0.5, 'h2h': 0.25, 'bots': 0.25}
    if not os.path.exists(path):
        os.makedirs(BENCH, exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            f.write('# The benchmark total = these parts (docs/BENCHMARK.md), each in HP per fight. Change the numbers to\n'
                    '# change the split; they are scaled to sum to 1. skills = the skill tests weighted by how often their\n'
                    '# situation comes in a real fight; h2h = head-to-head against the reference; bots = the mixed bot fights.\n'
                    'skills 0.5\nh2h 0.25\nbots 0.25\n')
        return parts
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            p = line.split('#')[0].split()
            if len(p) >= 2 and p[0] in parts:
                try:
                    parts[p[0]] = float(p[1])
                except ValueError:
                    pass
    s = sum(parts.values())
    if s > 0 and abs(s - 1) > 1e-9:
        parts = {k: v / s for k, v in parts.items()}
    return parts


def clean(o):
    """For JSON a browser can read: NaN and infinities become null."""
    if isinstance(o, float):
        return o if math.isfinite(o) else None
    if isinstance(o, dict):
        return {k: clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [clean(v) for v in o]
    return o


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(clean(obj), f, indent=1, allow_nan=False)
    os.replace(tmp, path)


# ---------------------------------------------------------------------------------------------------------------------
# weights

def weights_from(eps):
    """Per test: situations per real fight and per minute, rate and HP, over the given real duels."""
    fights = len(eps)
    minutes = sum(ep.n for ep in eps) / 1200.0
    tests = {}
    for t in TESTS:
        sits = [s for ep in eps for s in situations(ep)[t['key']]]
        vis = [s for s in sits if s.theirs is not None]
        rate, label = rate_of(t, sits)
        tests[t['key']] = {
            'name': t['name'], 'ids': t['ids'], 'occurrences': len(sits),
            'per_fight': len(sits) / fights if fights else None,
            'per_minute': len(sits) / minutes if minutes else None,
            'rate': rate, 'rate_label': label, 'rate_kind': t['rate'][1], 'better': t['rate'][2],
            'hp_net_visible': (sum(s.net for s in vis) / len(vis)) if vis else None, 'visible_situations': len(vis),
            'own_hp_lost': (sum(s.mine for s in sits) / len(sits)) if sits else None,
            'in_total': in_total(t)}
    return fights, minutes, tests


def window_overlap(eps):
    """How many in-total tests' HP windows cover a fight tick that any covers (the skills part counts that tick's HP
    that many times), and the share of fight ticks covered."""
    covered, layers, ticks = 0, 0, 0
    for ep in eps:
        cover = [0] * ep.n
        for t in TESTS:
            if not in_total(t):
                continue
            for s in situations(ep)[t['key']]:
                for k in range(s.start, min(ep.n, s.start + WINDOW)):
                    cover[k] += 1
        covered += sum(1 for c in cover if c)
        layers += sum(cover)
        ticks += ep.n
    return (layers / covered if covered else None), (covered / ticks if ticks else None)


def cmd_weights(folder, lo, hi, quiet=False):
    eps = real_duels(folder, lo, hi)
    if not eps:
        sys.exit('no real duels in %s' % folder)
    parts = read_parts()
    fights, minutes, tests = weights_from(eps)
    vis = sum(1 for ep in eps if ep.opp_visible)
    overlap, share = window_overlap(eps)
    obj = {'made': time.strftime('%Y-%m-%d %H:%M:%S'), 'source': 'the user\'s real duels (fights/*.txt.gz)',
           'days': [eps[0].day, eps[-1].day], 'fights': fights, 'minutes': minutes, 'opp_hp_visible_fights': vis,
           'window_ticks': WINDOW, 'window_overlap': overlap, 'window_share_of_fight': share,
           'note': 'per_fight = situations per real round; HP: opponent HP lost minus own HP lost where the opponent\'s HP '
                   'is visible (hp_net_visible), own HP lost only elsewhere (own_hp_lost); window_overlap = how many '
                   'in-total test windows cover a covered fight tick on average',
           'parts': parts, 'tests': tests}
    write_json(os.path.join(BENCH, 'weights.json'), obj)
    if not quiet:
        print('Real duels %s to %s: %d fights, %.1f minutes (their HP visible in %d fights; elsewhere own HP only)' % (
            eps[0].day, eps[-1].day, fights, minutes, vis))
        print('%-22s %-20s %6s %8s %8s  %-38s %9s %9s' % ('test', 'IDs', 'n', '/fight', '/minute', 'rate', 'HP net*', 'own lost'))
        for t in TESTS:
            w = tests[t['key']]
            print('%-22s %-20s %6d %8.2f %8.2f  %-38s %9s %9s%s' % (
                t['name'], t['ids'], w['occurrences'], w['per_fight'], w['per_minute'],
                (w['rate_label'] + ' ' + fmt_rate(w['rate'], t['rate'][1]))[:38],
                '%+.2f' % w['hp_net_visible'] if w['hp_net_visible'] is not None else '-',
                '%.2f' % w['own_hp_lost'] if w['own_hp_lost'] is not None else '-', '' if in_total(t) else '  (rate only)'))
        print('* HP net per situation over the %d-tick window, only in fights where their HP is visible' % WINDOW)
        print('The in-total tests\' windows cover %.0f%% of the fight ticks, %.2f deep on average there (the skills part '
              'counts that HP so many times)' % (100 * share, overlap))
        print('parts: skills %.2f, head-to-head %.2f, bot fights %.2f  ->  %s' % (
            parts['skills'], parts['h2h'], parts['bots'], os.path.join(BENCH, 'weights.json')))
    return obj


def load_weights():
    path = os.path.join(BENCH, 'weights.json')
    if not os.path.exists(path):
        if os.path.isdir(FIGHTS):
            print('no weights.json yet: made from the real duels now')
            return cmd_weights(FIGHTS, '00000000', '99999999', quiet=True)
        sys.exit('no %s: run "bench.py weights" first' % path)
    with open(path, encoding='utf-8') as f:
        return json.load(f)


# ---------------------------------------------------------------------------------------------------------------------
# score

def fight_net(ep):
    net, mine, theirs = ep.whole()
    return net


def cmd_score(tag, all_bots):
    eps, done = load_tag(tag)
    if not eps:
        sys.exit('tag %s has no episodes' % tag)
    bots = {}
    for ep in eps:
        bots[ep.bot] = bots.get(ep.bot, 0) + 1
    print('Benchmark tag %s: %d episodes (%s)' % (tag, len(eps), ', '.join('%s %d' % kv for kv in sorted(bots.items()))))
    if done is not None:
        print('  DONE: network %s (sha256 %s), league %s, %s to %s' % (
            done.get('network', '?'), str(done.get('network-sha256', '?'))[:12], done.get('league', '?'),
            done.get('started', '?'), done.get('finished', '?')))
    for n in done_notes(tag, done):
        print('  warning: ' + n)
    noswing = sum(1 for ep in eps if not ep.has_swings and any(ep.dealt))
    hidden = sum(1 for ep in eps if not ep.opp_visible)
    if noswing:
        print('  warning: %d episodes have hits but no swing events: hit kinds unknown there' % noswing)
    if hidden:
        print('  warning: %d episodes do not show the opponent\'s HP: own HP only there' % hidden)
    print('%-22s %6s %7s  %-44s %16s' % ('test', 'n', '/ep', 'rate', 'HP/situation (se)'))
    for t in TESTS:
        use = [ep for ep in eps if uses(t, ep, all_bots)]
        sits = [s for ep in use for s in situations(ep)[t['key']]]
        rate, label = rate_of(t, sits)
        m, se, n = mean_se([s.net for s in sits])
        extra = '; '.join('%s %s' % (lab, fmt_rate(extra_of(k, kd, sits), kd)) for k, kd, lab in t['extra'])
        print('%-22s %6d %7.2f  %-44s %16s%s' % (
            t['name'], len(sits), len(sits) / max(1, len(use)), (label + ' ' + fmt_rate(rate, t['rate'][1]))[:44],
            ('%+.2f (%.2f)' % (m, se)) if se is not None else ('%+.2f' % m if m is not None else '-'),
            ('' if in_total(t) else '  (rate only)') + ('\n%32s%s' % ('', extra) if extra else '')))
    for name, sel in (('Head-to-head', lambda ep: ep.bot == H2H),
                      ('Bot fights (all bot episodes)', lambda ep: ep.bot != H2H),
                      ('  of which mixer (shown only)', lambda ep: ep.bot == 'mixer')):
        part = [ep for ep in eps if sel(ep)]
        if not part:
            print('%s: no episodes' % name)
            continue
        m, se, n = mean_se([fight_net(ep) for ep in part])
        ko = [ep.knockouts() for ep in part]
        print('%s: %d fights, HP net per fight %+.2f%s, knockouts %d-%d' % (
            name, n, m, (' (se %.2f)' % se) if se is not None else '', sum(1 for a, b in ko if a), sum(1 for a, b in ko if b)))


# ---------------------------------------------------------------------------------------------------------------------
# compare

def episode_means(eps, test, all_bots):
    """{(idx, bot): (mean HP of its situations, number)} for the episodes with at least one situation of the test."""
    out = {}
    for ep in eps:
        if not uses(test, ep, all_bots):
            continue
        sits = situations(ep)[test['key']]
        if sits:
            out[(ep.idx, ep.bot)] = (sum(s.net for s in sits) / len(sits), len(sits))
    return out


def paired(c, r):
    keys = sorted(set(c) & set(r))
    return [c[k] - r[k] for k in keys], keys


def compare(eps_c, eps_r, tag_c, tag_r, weights, parts, all_bots, out_path, done=(True, True)):
    notes = []
    wtests = weights.get('tests', {})
    rows = []
    skills, skills_var = 0.0, 0.0
    worse_tests = []
    for t in TESTS:
        use_c = [ep for ep in eps_c if uses(t, ep, all_bots)]
        use_r = [ep for ep in eps_r if uses(t, ep, all_bots)]
        sits_c = [s for ep in use_c for s in situations(ep)[t['key']]]
        sits_r = [s for ep in use_r for s in situations(ep)[t['key']]]
        rc, label = rate_of(t, sits_c)
        rr, label_r = rate_of(t, sits_r)
        if label != label_r:
            # One side fell back to a count (crits with the opponent's HP hidden): the same unit for both.
            rc, rr = extra_of('net', 'mean', sits_c), extra_of('net', 'mean', sits_r)
            label = 'crits dealt - taken (count)'
        mc = episode_means(eps_c, t, all_bots)
        mr = episode_means(eps_r, t, all_bots)
        diffs, keys = paired({k: v[0] for k, v in mc.items()}, {k: v[0] for k, v in mr.items()})
        d, se, n = mean_se(diffs)
        w = (wtests.get(t['key']) or {}).get('per_fight')
        row = {'key': t['key'], 'name': t['name'], 'ids': t['ids'], 'rate_label': label, 'rate_kind': t['rate'][1],
               'better': t['rate'][2], 'c_rate': rc, 'r_rate': rr, 'c_situations': len(sits_c), 'r_situations': len(sits_r),
               'c_hp': mean_se([s.net for s in sits_c])[0], 'r_hp': mean_se([s.net for s in sits_r])[0],
               'pairs': n, 'unpaired': len(set(mc) ^ set(mr)), 'diff': d, 'se': se, 'weight': w,
               'contrib': None, 'contrib_se': None, 'in_total': False, 'worse': False,
               'extra': [{'label': lab, 'kind': kd, 'c': extra_of(k, kd, sits_c), 'r': extra_of(k, kd, sits_r)}
                         for k, kd, lab in t['extra']]}
        if in_total(t) and se is not None and w is not None:
            row['in_total'] = True
            row['contrib'] = d * w
            row['contrib_se'] = se * w
            skills += d * w
            skills_var += (se * w) ** 2
            if d < -2 * se and d < 0:
                row['worse'] = True
                worse_tests.append(t['name'])
        elif in_total(t):
            notes.append('%s: %s, left out of the total' % (
                t['name'], 'no weight (run bench.py weights)' if w is None else 'fewer than 2 paired episodes'))
        rows.append(row)

    def part(sel):
        c = {(ep.idx, ep.bot): fight_net(ep) for ep in eps_c if sel(ep)}
        r = {(ep.idx, ep.bot): fight_net(ep) for ep in eps_r if sel(ep)}
        diffs, keys = paired(c, r)
        d, se, n = mean_se(diffs)
        kc = [ep.knockouts() for ep in eps_c if sel(ep)]
        return {'pairs': n, 'value': d, 'se': se, 'c_net': mean_se(list(c.values()))[0], 'r_net': mean_se(list(r.values()))[0],
                'c_fights': len(c), 'r_fights': len(r),
                'c_knockouts': [sum(1 for a, b in kc if a), sum(1 for a, b in kc if b)]}

    h2h = part(lambda ep: ep.bot == H2H)
    bots = part(lambda ep: ep.bot != H2H)
    mixer = part(lambda ep: ep.bot == 'mixer')
    # The tests' HP windows overlap in a real fight (punish crit and jump reset start on the same hits, ...): the sum of
    # the tests counts a fight's HP about `overlap` times. Divided by the depth measured on the real duels
    # (weights.json window_overlap), the skills part estimates HP per real fight, like the other two parts.
    overlap = weights.get('window_overlap') or 1.0
    if not weights.get('window_overlap'):
        notes.append('weights.json has no window_overlap (made by an older bench.py): skills not divided; run bench.py weights')
    raw_se = math.sqrt(skills_var) if any(r['in_total'] for r in rows) else None
    raw = skills if raw_se is not None else None
    skills_v = raw / overlap if raw is not None else None
    skills_se = raw_se / overlap if raw_se is not None else None
    comp = {'skills': (skills_v, skills_se), 'h2h': (h2h['value'], h2h['se']), 'bots': (bots['value'], bots['se'])}
    present = {k: v for k, v in comp.items() if v[0] is not None and v[1] is not None}
    used = {k: parts[k] for k in present}
    wsum = sum(used.values())
    if set(present) != set(comp):
        notes.append('left out of the total (no paired data): %s; the other parts scaled to sum to 1' % ', '.join(
            k for k in comp if k not in present))
    if wsum > 0:
        used = {k: v / wsum for k, v in used.items()}
        total = sum(used[k] * present[k][0] for k in present)
        total_se = math.sqrt(sum((used[k] * present[k][1]) ** 2 for k in present))
    else:
        total, total_se = None, None
    if total is None:
        verdict = 'NO DATA'
    elif total > 2 * total_se and not worse_tests and not (h2h['se'] is not None and h2h['value'] < -2 * h2h['se']):
        verdict = 'BETTER'
    elif total < -2 * total_se:
        verdict = 'WORSE'
    else:
        verdict = 'TIE'
    if verdict == 'TIE' and total is not None and total > 2 * total_se:
        why = []
        if worse_tests:
            why.append('worse by more than 2 se in: ' + ', '.join(worse_tests))
        if h2h['se'] is not None and h2h['value'] < -2 * h2h['se']:
            why.append('head-to-head worse by more than 2 se')
        notes.append('total above 2 se, but ' + '; '.join(why))
    for tag, info in ((tag_c, done[0]), (tag_r, done[1])):
        if info is not True:
            notes.extend(done_notes(tag, info))
    hidden = sum(1 for ep in list(eps_c) + list(eps_r) if not ep.opp_visible)
    if hidden:
        notes.append('%d episodes do not show the opponent\'s HP: own HP only there' % hidden)
    result = {'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'tag_c': tag_c, 'tag_r': tag_r, 'suite': 'v1',
              'all_bots': all_bots, 'episodes': {'c': len(eps_c), 'r': len(eps_r)}, 'parts': parts, 'parts_used': used,
              'done': {'c': done[0] if isinstance(done[0], dict) else None, 'r': done[1] if isinstance(done[1], dict) else None},
              'weights_made': weights.get('made'), 'weights_fights': weights.get('fights'), 'window_ticks': WINDOW,
              'tests': rows,
              'skills': {'value': skills_v, 'se': skills_se, 'sum_of_tests': raw, 'sum_of_tests_se': raw_se,
                         'overlap': overlap},
              'h2h': h2h, 'bots': bots, 'bots_from': 'all bot episodes (every suite bot, not h2h)', 'mixer': mixer,
              'total': {'value': total, 'se': total_se}, 'verdict': verdict, 'notes': notes}
    print_compare(result)
    if out_path:
        write_json(out_path, result)
        print('written: %s' % out_path)
    return result


def _pm(v, se=None, f='%+.2f'):
    if v is None:
        return '-'
    return (f % v) + ((' (%.2f)' % se) if se is not None else '')


def print_compare(r):
    print('Benchmark: C = %s against R = %s, paired by episode (seed)%s' % (r['tag_c'], r['tag_r'],
                                                                         ', all bots' if r['all_bots'] else ''))
    print('%-22s %-40s %6s %15s %7s %15s' % ('test', 'rate: C / R', 'pairs', 'HP/sit C-R (se)', 'weight', 'HP/fight (se)'))
    for row in r['tests']:
        kd = row['rate_kind']
        rate = '%s %s / %s' % (row['rate_label'][:24], fmt_rate(row['c_rate'], kd), fmt_rate(row['r_rate'], kd))
        print('%-22s %-40s %6d %15s %7s %15s%s' % (
            row['name'], rate, row['pairs'], _pm(row['diff'], row['se']),
            '%.2f' % row['weight'] if row['weight'] is not None else '-',
            _pm(row['contrib'], row['contrib_se']) if row['in_total'] else ('rate only' if row['key'] == 'basics' else 'left out'),
            '  WORSE' if row['worse'] else ''))
    s = r['skills']
    print('skills (HP per real fight) = sum of the tests %s / window overlap %.2f = %s' % (
        _pm(s['sum_of_tests'], s['sum_of_tests_se']), s['overlap'], _pm(s['value'], s['se'])))
    for name, p in (('head-to-head', r['h2h']), ('bot fights (all bot episodes)', r['bots']),
                    ('  of which mixer (shown only)', r['mixer'])):
        print('%s: C %s, R %s HP per fight; C-R %s over %d pairs' % (
            name, _pm(p['c_net']), _pm(p['r_net']), _pm(p['value'], p['se']), p['pairs']))
    pu = r['parts_used']
    print('TOTAL = %s = %s  ->  %s' % (' + '.join('%.2f %s' % (pu[k], k) for k in ('skills', 'h2h', 'bots') if k in pu),
                                       _pm(r['total']['value'], r['total']['se']), r['verdict']))
    for n in r['notes']:
        print('  note: ' + n)


def cmd_compare(tag_c, tag_r, all_bots, out_path):
    eps_c, done_c = load_tag(tag_c)
    eps_r, done_r = load_tag(tag_r)
    weights = load_weights()
    parts = read_parts()
    bad = [(a.idx, a.bot, b.bot) for a in eps_c for b in eps_r if a.idx == b.idx and a.bot != b.bot]
    if bad:
        print('warning: %d episode indexes have different bots in the two tags (different suites?); those are not paired' % len(bad))
    return compare(eps_c, eps_r, tag_c, tag_r, weights, parts, all_bots, out_path, (done_c, done_r))


# ---------------------------------------------------------------------------------------------------------------------
# selftest

def show_rows(ep, s, before=3, after=14):
    print('    ' + ','.join(ep.header))
    for k in range(max(0, s - before), min(ep.n, s + after)):
        print('    %s%s' % (','.join(ep.raw_rows[k]), '   <- start' if k == s else ''))


def cmd_selftest(folder):
    eps = real_duels(folder)
    if not eps:
        sys.exit('no real duels in %s' % folder)
    print('== 1. The detectors on the real duels ==')
    obj = cmd_weights(folder, '00000000', '99999999')
    print()
    print('Per day (situations per fight):')
    days = sorted(set(ep.day for ep in eps))
    print('%-9s %5s ' % ('day', 'fights') + ' '.join('%10s' % t['key'] for t in TESTS))
    for day in days:
        de = [ep for ep in eps if ep.day == day]
        print('%-9s %5d ' % (day, len(de)) + ' '.join('%10.2f' % (sum(len(situations(ep)[t['key']]) for ep in de) / len(de))
                                                      for t in TESTS))
    print()
    print('== 2. Spot checks: one situation per test (real duel by index; rows only) ==')
    for t in TESTS:
        found = [(ep, s) for ep in eps for s in situations(ep)[t['key']]]
        if not found:
            print('%s: none' % t['name'])
            continue
        # A situation with the rate's outcome, from the middle of the list, so both kinds get looked at across runs.
        good = [x for x in found if x[1].out.get(t['rate'][0])]
        ep, s = (good or found)[len(good or found) // 2]
        print('%s (%s): real duel #%d, tick %d, outcome %s, HP net %+.0f (own lost %.0f, theirs %s)' % (
            t['name'], t['ids'], ep.idx, s.start, s.out, s.net, s.mine, '-' if s.theirs is None else '%.0f' % s.theirs))
        show_rows(ep, s.start)
    print()
    print('== 3. compare end to end: even-numbered real duels as "C", odd as "R", paired by order ==')
    print('   (episode i of each half; i %% 5 == 0 labelled h2h, i %% 5 == 1 mixer; skill tests over all episodes)')
    ce, re_ = [], []
    for k, ep in enumerate(eps):
        i = k // 2
        ep.idx = i
        ep.bot = H2H if i % 5 == 0 else 'mixer' if i % 5 == 1 else 'real'
        (ce if k % 2 == 0 else re_).append(ep)
    weights = obj
    res = compare(ce, re_, 'real-even', 'real-odd', weights, read_parts(), True, os.path.join(BENCH, 'selftest.json'))
    print()
    print('== 4. A/A: the even half against itself (must be exactly 0 and TIE) ==')
    res2 = compare(ce, ce, 'real-even', 'real-even', weights, read_parts(), True, None)
    ok = res2['total']['value'] == 0 and res2['verdict'] == 'TIE'
    print('A/A check: %s' % ('OK' if ok else 'FAILED'))
    return res


# ---------------------------------------------------------------------------------------------------------------------

def main(argv):
    global BENCH
    args, flags = [], {}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ('--fights', '--from', '--to', '--out', '--bench'):
            if i + 1 >= len(argv):
                print(__doc__)
                return 2
            flags[a] = argv[i + 1]
            i += 2
            continue
        if a.startswith('--'):
            flags[a] = True
        else:
            args.append(a)
        i += 1
    if not args:
        print(__doc__)
        return 2
    cmd = args[0]
    folder = flags.get('--fights', FIGHTS)
    BENCH = flags.get('--bench', BENCH)
    if cmd == 'weights':
        cmd_weights(folder, flags.get('--from', '00000000'), flags.get('--to', '99999999'))
    elif cmd == 'score' and len(args) >= 2:
        cmd_score(args[1], bool(flags.get('--all-bots')))
    elif cmd == 'compare' and len(args) >= 3:
        cmd_compare(args[1], args[2], bool(flags.get('--all-bots')), flags.get('--out', os.path.join(BENCH, 'latest.json')))
    elif cmd == 'selftest':
        cmd_selftest(folder)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
