"""oppdemo/lib.py: STAGE 1 (offline only) of "learn from the opponents' winning moves". Python 3.8, standard library only.

Reads the user's real duels (read only; the names in file names and summary lines are never printed, stored or written:
duels are referred to by index) and builds, per round, the opponent's inferred actions and the situation features as
the opponent saw them, with the same features for our AI so that the later training stage can compute them for it too.

Facts about the recording (src/client/java/dev/swordai/client/RoundRecorder.java), which the inference rests on:
  - oppToward = -(opp velocity . unit vector from us to it): POSITIVE = the opponent moves TOWARD our AI (W), negative =
    away (S). One value is the client's position delta of that tick (blocks/tick; walking 0.216, sprinting 0.28).
  - oppSide = Math.abs(opp velocity x unit vector): UNSIGNED. A or D cannot be told apart in the recording. This stage
    therefore does NOT output A or D at all, only "moving sideways" (a flag), never a guess of the side.
  - opp column = 'g'/'a' (ground or air) + 's' if the client saw it sprinting; oppUp = its y delta of the tick.
  - oppSword = the sword charge as read off its arm; a fall of more than 0.05 in one tick is its swing (BodyState).
    98% of the hits taken come on the very tick of such a fall, so its attack = that fall, its hit = TAKEN.
  - "swing:" events are OUR AI's swings (not the opponent's); DEALT = our hit; TAKEN = its hit on us (our HP drops one
    tick later, damage about 1 or 2 HP a hit).
  - On servers since 1 Nov the opponent's HP reads a constant: only our HP is known in most duels (bench.Episode.opp_visible).

PvP facts only from docs/PVP-TECHNIQUES.md (Part 8: the sword is back after 12.5 ticks, a jump is about 12 ticks, a hit
lifts a target only on the ground, knockback arrives a few ticks late on a server). The user's critical rule (memory
"jump-in ticks", 5 Nov): never hit a jump-in after its ground tick 1, rising or falling; K2: a knockback hit on someone
falling is not a move for our AI.
"""
import collections
import glob
import gzip
import os
import random
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import bench  # noqa: E402  (tools/bench.py: Episode parser of the recording, reused as is)

FIGHTS = bench.FIGHTS

# ---- constants (each with its source) ------------------------------------------------------------------------------
WINDOW = 40          # outcome window, ticks (docs/BENCHMARK.md, bench.WINDOW)
NO_HIT_BACK = 10     # a stun: its hit and no hit back for 10 ticks (docs/BENCHMARK.md)
TRADE = 4            # both hit within 4 ticks = a trade (the user's brief)
COMBO_GAP = 13       # a second hit within 13 ticks of the first (sword back after 12.5, Part 8) = the combo went on
HIT_AHEAD = 12       # a move (not a swing) is credited with the first hit of the actor in the next 12 ticks
START_SKIP = 20      # first ticks of a round, after a teleport or a reset: no examples (spawn, teleport)
TELEPORT = 6.0       # a one-tick change of the distance above this = a teleport
READY = bench.READY  # 0.9: a sword is ready
MIN_NET = 2.0        # "clearly in its favour": net HP over 40 ticks at least this (about two hits)
KB_BEFORE, KB_AFTER = 1, 8   # opponent ticks around our DEALT where its velocity is our knockback, not its keys
FEATURE_LAG = 2     # the state the opponent decided on = the row this many ticks before its action shows
V_MOVE = 0.10        # |toward speed| for W / S (2-tick mean); walking is 0.216, sprinting 0.28, strafing alone ~0.15 on the diagonal
V_STILL = 0.03       # below this (and not sideways) = standing still
V_SIDE = 0.08        # moving sideways (unsigned)
MAX_DIST = 8.0       # decision points only within this eye-to-hitbox distance (the fight, not the approach)
AIR_GROUND_TICK = 2  # the user's rule: a target in the air for 2+ ticks (after its ground tick 1) must not be hit

ACTIONS = ('ATK', 'JUMP', 'FWD', 'BACK', 'STILL')


# ---- rounds ----------------------------------------------------------------------------------------------------------
class Round(object):
    """A stretch a..b-1 (rows of one Episode) that is one round: starts at the file start or a health reset, ends at our
    death (first tick with our HP 0), the opponent's death, a health reset, or the file end."""
    __slots__ = ('ep', 'duel', 'a', 'b', 'outcome', 'why', 'idx')


def split_rounds(ep, duel, file_won):
    """Rounds of one episode. A reset = our HP jumps up by 5+ (regeneration is +1), or the opponent's HP jumps up by 5+ where
    it is visible. Outcome 'lost' = we died (HP 0) or, with no death, finished behind in the health race; 'won' = the
    opponent died, or we finished ahead; 'undecided' = no basis (equal). Judgement of rounds with no death:
      - the opponent's HP visible: our HP vs its HP at the end of the round;
      - not visible: our HP vs an estimate of its HP, 20 - DMG * hits dealt (DMG = mean damage of a hit dealt, bench data);
      - a round ending on the file end takes the file's own result (-won) as 'won' only when we did not die."""
    n = ep.n
    cuts = [0]
    for k in range(1, n):
        if ep.myHp[k] - ep.myHp[k - 1] >= 5 or (ep.opp_visible and ep.oppHp[k] - ep.oppHp[k - 1] >= 5):
            cuts.append(k)
    cuts.append(n)
    out = []
    for i in range(len(cuts) - 1):
        a, b = cuts[i], cuts[i + 1]
        # our death: the first tick of the stretch with HP 0 ends the round there (what follows is the respawn)
        death = next((k for k in range(a, b) if ep.myHp[k] <= 0.5), None)
        opp_death = next((k for k in range(a, b) if ep.opp_visible and ep.oppHp[k] <= 0.5), None)
        r = Round()
        r.ep, r.duel, r.a = ep, duel, a
        if death is not None and (opp_death is None or death <= opp_death):
            r.b, r.outcome, r.why = death + 1, 'lost', 'death'
        elif opp_death is not None:
            r.b, r.outcome, r.why = opp_death + 1, 'won', 'opp death'
        else:
            r.b = b
            r.outcome = 'undecided'
            r.why = 'race'
        if b - a < 30:
            continue          # a scrap (a respawn, a reset): not a round
        out.append(r)
    return out


def judge_race(rounds, dmg):
    """Undecided rounds (no death seen): judge the health race at the round's end. Needs dmg (HP per hit dealt, estimated
    where the opponent's HP is not visible)."""
    for r in rounds:
        if r.outcome != 'undecided':
            continue
        ep = r.ep
        end = r.b - 1
        mine = ep.myHp[end]
        if ep.opp_visible:
            theirs = ep.oppHp[end]
        else:
            theirs = 20.0 - dmg * sum(1 for k in range(r.a, r.b) if ep.dealt[k]) + 0.0
        if mine + 1.0 < theirs:
            r.outcome, r.why = 'lost', 'race behind'
        elif mine > theirs + 1.0:
            r.outcome, r.why = 'won', 'race ahead'
        else:
            r.why = 'race level'


def estimate_dmg(eps):
    """HP per hit we deal, measured where the opponent's HP is visible (about 1.1)."""
    tot = hits = 0
    for e in eps:
        if not e.opp_visible:
            continue
        for k in range(1, e.n):
            d = e.oppHp[k - 1] - e.oppHp[k]
            if 0 < d <= 16:
                tot += d
        hits += sum(e.dealt)
    return (tot / hits) if hits else 1.1


# ---- per-tick inference, from one side's point of view -----------------------------------------------------------------
class Side(object):
    """Everything about one round from one actor's point of view: actor = 'opp' (inferred from what we saw of it) or 'me'
    (our AI: its own keys are known exactly). All lists are indexed by the episode row.
      act[t]   one of ACTIONS or None (unknown: never guessed)
      conf[t]  'hi' / 'med' / None
      det[t]   details of the action (sprint, crit swing, strafing)
      feat[t]  situation (the state before the action, FEATURE_LAG ticks earlier for the opponent) or None
      hits_own / hits_other: tick lists of hits the actor dealt / took (opp: TAKEN rows / DEALT rows)."""


def _air_runs(air):
    """Consecutive ticks in the air up to and including each row (0 on the ground)."""
    out, c = [], 0
    for a in air:
        c = c + 1 if a else 0
        out.append(c)
    return out


def side_data(r, who):
    ep = r.ep
    n = ep.n
    S = Side()
    S.who = who
    S.round = r
    opp = (who == 'opp')
    own_hit = ep.taken if opp else ep.dealt       # the actor's hits (opponent's hit = our TAKEN)
    oth_hit = ep.dealt if opp else ep.taken
    own_air = ep.oppAir if opp else ep.meAir
    oth_air = ep.meAir if opp else ep.oppAir
    own_sw = ep.oppSw if opp else ep.mySw
    oth_sw = ep.mySw if opp else ep.oppSw
    own_tow = ep.oppToward if opp else ep.myToward
    oth_tow = ep.myToward if opp else ep.oppToward
    own_air_run = _air_runs(own_air)
    oth_air_run = _air_runs(oth_air)
    S.own_hit, S.oth_hit = own_hit, oth_hit
    S.own_air_run, S.oth_air_run = own_air_run, oth_air_run
    # the first rows after a teleport: a one-tick distance jump
    tele = [False] * n
    for k in range(r.a + 1, r.b):
        if abs(ep.dist[k] - ep.dist[k - 1]) > TELEPORT:
            for j in range(k, min(n, k + START_SKIP)):
                tele[j] = True
    S.tele = tele
    # last hit dealt / taken, and the streak, per tick (hits in a row either way within COMBO_GAP... recorder gap = 20)
    since_own = [999] * n
    since_oth = [999] * n
    streak = [0] * n
    last_own = last_oth = -999
    last_ev = None            # 'own' or 'oth'
    run = 0
    for k in range(r.a, r.b):
        if own_hit[k]:
            if last_ev == 'own' and k - max(last_own, last_oth) <= bench.GAP:
                run += 1
            else:
                run = 1
            last_ev, last_own = 'own', k
        if oth_hit[k]:
            if last_ev == 'oth' and k - max(last_own, last_oth) <= bench.GAP:
                run += 1
            else:
                run = 1
            last_ev, last_oth = 'oth', k
        since_own[k] = min(999, k - last_own)
        since_oth[k] = min(999, k - last_oth)
        if last_ev is None or k - max(last_own, last_oth) > bench.GAP:
            streak[k] = 0
        else:
            streak[k] = run if last_ev == 'own' else -run
    S.streak = streak
    # actions
    act = [None] * n
    conf = [None] * n
    det = [None] * n
    kb = [False] * n          # the opponent's velocity is our knockback here
    if opp:
        for k in range(r.a, r.b):
            if ep.dealt[k]:
                for j in range(max(r.a, k - KB_BEFORE), min(r.b, k + KB_AFTER + 1)):
                    kb[j] = True
    S.kb = kb
    opp_side = [0.0] * n      # UNSIGNED sideways speed (the recorder writes abs()): only a "strafing" flag, never A or D
    if opp and 'oppSide' in ep.header:
        ci = ep.header.index('oppSide')
        for k in range(n):
            try:
                opp_side[k] = float(ep.raw_rows[k][ci])
            except (ValueError, IndexError):
                pass
    for k in range(r.a + 1, r.b):
        if tele[k]:
            continue
        if opp:
            sw = ep.theirSwing[k]
            # the air flag turns on a tick before oppUp shows the rise (2-tick position cadence): rising within k..k+2
            jump = (not ep.oppAir[k - 1]) and ep.oppAir[k] and max(ep.oppUp[k:k + 3]) > 0.1 and not kb[k]
            sprint = ep.oppSprint[k]
            s = 0.5 * (ep.oppToward[k] + ep.oppToward[k - 1])
            if sw:
                falling = ep.oppAir[k] and ep.oppUp[k] < 0 and not sprint
                act[k], conf[k] = 'ATK', 'hi'
                det[k] = {'sprint': sprint, 'crit': falling, 'ready': own_sw[k - 1] >= READY}
            elif jump:
                act[k], conf[k] = 'JUMP', 'hi' if max(ep.oppUp[k:k + 3]) > 0.2 else 'med'
                det[k] = {'sprint': sprint}
            elif ep.oppAir[k] or ep.oppAir[k - 1] or kb[k]:
                pass              # in the air the horizontal speed is momentum, and after our hit it is knockback: unknown
            else:
                if s >= V_MOVE:
                    act[k], conf[k] = 'FWD', 'hi' if s >= 0.18 else 'med'
                elif s <= -V_MOVE:
                    act[k], conf[k] = 'BACK', 'hi' if s <= -0.16 else 'med'
                # 'standing still' is NOT inferred: run.py check_inference shows its precision against our own keys is ~15%
                # (a player who let go of W still slides; one pushing a wall does not move), so it stays unknown
                det[k] = {'sprint': sprint, 'strafe': opp_side[k] >= V_SIDE}
        else:
            kk = (ep.keys[k] + '-----')[:5]
            sw = ep.swing[k] is not None
            if sw:
                act[k], conf[k] = 'ATK', 'hi'
                det[k] = {'sprint': kk[2] == 'r', 'crit': ep.swing[k] == 'crit', 'ready': ep.mySw[k - 1] >= READY}
            elif kk[3] == 'j' and not ep.meAir[k - 1]:
                act[k], conf[k] = 'JUMP', 'hi'
                det[k] = {'sprint': kk[2] == 'r'}
            elif kk[0] == 'W':
                act[k], conf[k] = 'FWD', 'hi'
                det[k] = {'sprint': kk[2] == 'r'}
            elif kk[0] == 'S':
                act[k], conf[k] = 'BACK', 'hi'
                det[k] = {'sprint': False}
            else:
                act[k], conf[k] = 'STILL', 'hi'
                det[k] = {'sprint': False}
    S.act, S.conf, S.det = act, conf, det
    # features
    feat = [None] * n
    lag = FEATURE_LAG if opp else 1   # our own swing resets mySword on the row it is made: the state before is the row before
    for k in range(r.a + START_SKIP, r.b):
        j = k - lag
        if j < r.a or tele[k] or tele[j]:
            continue
        d = ep.dist[j]
        if d >= MAX_DIST:
            continue
        feat[k] = dict(dist=d, own_sw=own_sw[j], oth_sw=oth_sw[j], own_air=own_air[j], oth_air=oth_air[j],
                       own_air_run=own_air_run[j], oth_air_run=oth_air_run[j],
                       own_tow=own_tow[j], oth_tow=oth_tow[j], closing=own_tow[j] + oth_tow[j],
                       since_own=since_own[j], since_oth=since_oth[j], streak=streak[j])
    S.feat = feat
    return S


# ---- bins ------------------------------------------------------------------------------------------------------------------
def dist_bin(d):
    return 'close' if d < 3.0 else ('mid' if d < 4.5 else 'far')


def bin_key(f, level=3):
    """Coarse situation bin, the same for the opponent and for our AI in the opponent's place. Three levels (the data is small, so
    a coarse level is the only one that can have support):
      level 1: dist | own | oth
      level 2: level 1 + air (own ground/air, other's ground/air)
      level 3: level 2 + streak
      dist   close (< 3.0 blocks eye-to-hitbox) / mid (3.0-4.5) / far (4.5-8)
      own    its own sword ready (>= 0.9) R or cooling c
      oth    the other's sword R / c
      air    own ground/air + other's ground/air: gg, ga, ag, aa
      streak hits in a row: own combo 2+ (C), own 1 (c), none (0), other's 1 (t), other's 2+ (T)"""
    k = '%s|own%s|oth%s' % (dist_bin(f['dist']), 'R' if f['own_sw'] >= READY else 'c', 'R' if f['oth_sw'] >= READY else 'c')
    if level >= 2:
        k += '|%s%s' % ('a' if f['own_air'] else 'g', 'a' if f['oth_air'] else 'g')
    if level >= 3:
        st = f['streak']
        k += '|' + ('C' if st >= 2 else ('c' if st == 1 else ('0' if st == 0 else ('t' if st == -1 else 'T'))))
    return k


BIN_DOC = {
    'key': 'level 1: dist|own<R/c>|oth<R/c>; level 2 adds |<own g/a><other g/a>; level 3 adds |streak',
    'dist': {'close': 'eye-to-hitbox distance < 3.0', 'mid': '3.0 <= d < 4.5', 'far': '4.5 <= d < 8 (>= 8: no decision point)'},
    'own/oth': 'sword charge >= 0.9 (R, ready) or below (c, cooling); own = the actor (the opponent, or our AI in its place)',
    'air': 'actor on the ground/air, then the other one (g/a): from the recording columns me / opp',
    'streak': 'hits in a row within 20 ticks, no hit the other way between: C = actor 2+, c = actor 1, 0 = none, t = other 1, T = other 2+',
    'state_row': 'opponent: the row FEATURE_LAG=2 ticks before its action shows (it decided on an older state, and its action shows late); our AI: the same row',
}


# ---- outcomes --------------------------------------------------------------------------------------------------------------
def net_hp(r, t, dmg, who, length=WINDOW):
    """Net HP over the window from tick t+1 to t+length (cut at the round's end) from the actor's point of view:
    HP the other lost minus HP the actor lost. The opponent's HP where visible; else dmg x our hits dealt. Returns
    (net, the window's full length reached)."""
    ep = r.ep
    a, b = t + 1, min(r.b, t + 1 + length)
    mine = ep.lost(ep.myHp, a, b + 1)    # a drop shows one tick after the hit
    if ep.opp_visible:
        theirs = ep.lost(ep.oppHp, a, b + 1)
    else:
        theirs = dmg * sum(1 for k in range(a, b) if ep.dealt[k])
    net = theirs - mine           # from our AI's point of view
    return (-net if who == 'opp' else net), (t + 1 + length <= r.b)


def is_good_opp(S, t, dmg):
    """GOOD move (opponent's point of view) for the opponent's action at tick t. Returns (good, reason or drop reason).
    A move is kept only when, over what followed:
      - it landed a hit (ATK: its swing at t is the hit, TAKEN on that tick or the next; other moves: its first hit in the
        next HIT_AHEAD ticks) with no hit back from us within 10 ticks of that hit, OR its combo went on (a further hit
        of its within COMBO_GAP ticks, no hit of ours between); AND
      - the net HP over the next 40 ticks is at least MIN_NET in its favour (our HP loss minus the damage we did);
    dropped: trades (we hit within 4 ticks before or after its hit), and the rule breakers (a hit on a jumper after its
    ground tick 1, rising or falling, which also covers K2 the falling target; the jumper's air time counted on the recording
    row of the hit and the 3 rows before, because the server applies the hit earlier than the client shows it)."""
    r = S.round
    ep = r.ep
    act = S.act[t]
    if act == 'ATK':
        cand = [k for k in (t, t + 1) if k < r.b and ep.taken[k]]
        h = cand[0] if cand else None
    else:
        h = next((k for k in range(t + 1, min(r.b, t + 1 + HIT_AHEAD)) if ep.taken[k]), None)
    if h is None:
        return False, 'no hit'
    # trade
    if any(ep.dealt[k] for k in range(max(r.a, h - TRADE), min(r.b, h + TRADE + 1))):
        return False, 'trade'
    # rule breakers: the target (our AI) in the air 2+ ticks near the hit
    for k in range(max(r.a, h - 3), h + 1):
        if S.oth_air_run[k] >= AIR_GROUND_TICK:
            return False, 'rule: hit on a jumper after its ground tick 1 (K2 included)'
    # no hit back for 10 ticks, or the combo went on
    back = any(ep.dealt[k] for k in range(h + 1, min(r.b, h + 1 + NO_HIT_BACK)))
    combo = False
    for k in range(h + 1, min(r.b, h + 1 + COMBO_GAP)):
        if ep.dealt[k]:
            break
        if ep.taken[k]:
            combo = True
            break
    if back and not combo:
        return False, 'hit back'
    net, full = net_hp(r, t, dmg, 'opp')
    if net < MIN_NET:
        return False, 'net below %.1f' % MIN_NET
    return True, 'combo' if combo else 'no hit back'


# ---- helpers shared by the runner ----------------------------------------------------------------------------------------------
def load_all(folder=None):
    """All real duels in file order: (duel index, Episode, file result 'won' or 'open'). Only indexes leave this function."""
    folder = folder or FIGHTS
    eps = bench.real_duels(folder)
    res = []
    for e in eps:
        res.append((e.idx, e, 'won' if e.path.endswith('-won.txt.gz') else 'open'))
    return res


def ping_ms(path):
    """The ping the summary line states, '(101 ms)'. Only the number is taken; the line (it holds the name) is never kept."""
    try:
        with gzip.open(path, 'rt', encoding='utf-8', errors='replace') as fh:
            for _ in range(4):
                line = fh.readline()
                m = re.search(r'\((\d+) ms\)', line)
                if m:
                    return int(m.group(1))
    except (OSError, EOFError):
        pass
    return None


def mean(xs):
    return sum(xs) / len(xs) if xs else 0.0


def sd(xs):
    if len(xs) < 2:
        return 0.0
    m = mean(xs)
    return (sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) ** 0.5


def split_duels(duel_ids, seed):
    """Deterministic half split by duel: shuffle with the seed, first half = train."""
    ids = sorted(duel_ids)
    rnd = random.Random(seed)
    rnd.shuffle(ids)
    h = (len(ids) + 1) // 2
    return set(ids[:h]), set(ids[h:])
