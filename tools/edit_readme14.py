p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()

ITEM = '''14. *What it would not learn is done for it, and it meets the players it lost to.* From real fights
    on a server after stage 10: it is good, but (a) when the opponent keeps running out it only
    sprints after them; (b) against a player who goes round it making critical hits it hardly ever
    makes a punish crit, and when it does, not reliably; (c) from a little beyond its reach it never
    jumps in for a critical hit; (d) its aim still shakes at an opponent twenty blocks away; and
    it has no answer to a player who stands in a corner and makes critical hits, or to one who
    takes next to no knockback.

    **Why the jump never came.** Five runs taught it the jump in a drill and lost it again in the
    fights, each within fifty updates. A jump that comes to nothing costs, a jump at random makes a
    fighter worse, and against every scripted opponent the sprint-hit combo it already had was
    simply the better fight (against the one that runs: 36 hits a minute landed and 11 taken
    without a jump, 24 and 10 while it was still jumping). So the first thing it learns about
    jumping in a fight is not to.

    **Why the punish crit was not reliable.** Counted per fall: of the falls after a hit in which
    the opponent stayed within a sword's reach, a critical hit was made in 19%. In 49% the
    crosshair was never on the opponent on the way down (the hit moves the fighter, the opponent
    has moved on or was never in the picture), in 21% the sword had already been spent on a plain
    hit, in 10% it was not back yet.

    So these are now **rules**, like the ones about the sword's charge (5.), backing out (11.) and
    answering a hit (12.): done for it on the ticks they apply to, in training and in every fight,
    with the keys they take taken from the network for the tick so that what it learns it learns
    around them. Each has a switch in `config.json`, and `/swordai status` and the report list
    the ones that are on. All of them go by what the network itself is shown, the fighter's own
    body and how large and where the opponent is on screen; how far away the opponent is, is read
    off its height on screen.

    - **The camera after a hit** (`aimRule`). For sixteen ticks after it is hit, until it has hit
      back, the camera is brought onto the opponent: to the middle of it as fast as the hand gets
      there without going past, and with nothing on screen round to the side it was last seen on
      or, never having seen it, away from the way the hit threw it, which is where the hit came
      from.
    - **The swing kept for the way down** (`punishRule`, from 13.'s rules), and now also on the
      tick of the hit itself, while its feet are still on the ground: against an opponent that was
      in reach the last time it came down from a hit, the swing waits for the fall; against any
      other it is made at once, as a trade.
    - **The chase** (`chaseRule`). At a run after an opponent a step or two beyond its reach that
      the run is hardly catching, it jumps from where the way down will bring that opponent into
      reach, keeps W and the sprint down on the way up, and on the way down lets go of both for a
      tick and swings on the next: a critical hit. After an opponent that runs with its back
      turned, as fast as the fighter itself, it makes one running jump after the other from any
      distance up to fourteen blocks, which is the only way it gets nearer. (The first version
      took a running jump to gain a block on a run. It gains a quarter of one: one critical hit
      from 29 jumps.) Hit in the air of such a jump, it does not make another for fifteen seconds:
      that opponent is one that hits a fighter out of the air.
    - **The crit in its face** (`faceRule`). If its last two hits did not send the opponent away
      (still on top of it as the sword came back), and its next hit lands and the opponent is still
      in its face three to five ticks later, it jumps with W and the sprint up and swings on the
      way down, and goes on like that: critical hits without knockback, one after the other, on an
      opponent that takes none anyway. Two hits, not one: a knockback that comes half a second late
      has the opponent still standing there as the sword comes back, once. Hit in the air of such a
      jump, it leaves it alone for thirty seconds and goes back to the punish crit.
    - **The uppercut** (`uppercutChance`, a quarter). After that share of the hits of a combo of
      its own, from the third on: a running jump at the opponent as the sword comes back, and the
      sprint hit made while it is still going up. See the PvP tutorial of the Minecraft Wiki for
      what it is for.
    - **A steady hand far away** (`farRestRule`). With the opponent on screen and more than about
      ten blocks off, the camera is steered for it: at rest until the crosshair is two and a half
      degrees off the opponent, then onto it in one move and at rest again. The hand model starts a
      turn at a degree a tick and no less, and at twenty blocks the whole opponent is a degree and
      a half wide; the network went on correcting there, each correction as large as the target.

    **And stage 8 has the opponents to use them on**, each a share of its fights (and so of the
    scripted fights that the duel and self-play keep going back to):

    - the **runner**, of which half now keep away for good: back turned and at a sprint whenever
      the fighter is within seven blocks, until it is hit, then two to five seconds of fighting and
      off again;
    - the one that **goes round it** making critical hits, in half of those fights starting close
      by and out of the picture, off to a side or behind;
    - the one **in the corner**: it stands where the walls meet, turns to face the fighter and
      jumps on the spot for a critical hit whenever the fighter comes into reach;
    - the one that takes **next to no knockback** (none to 15%), stays on top of the fighter and
      makes a critical hit every time its sword is back;
    - the **odd ones**: one that jumps all the time, one that spins on the spot as it walks in,
      one that stands like a statue and then runs at it, one that changes direction every few
      ticks, one that clicks as fast as it can, one that keeps ducking;
    - and every other scripted opponent is **drawn afresh**: how quick it is, how well it aims,
      how near it likes to be, how often it jumps, backs off, is late with a swing or comes in
      for a critical hit.

    The slow connection is slower as well: knockback up to 0.6 seconds late (0.5), what it sees of
    the opponent up to two ticks older, and the stutter half as heavy again.

    NUMBERS_14

'''
anchor = '''The run these numbers are from is the one in *Running it with nobody at the keyboard*: each update writes
one line to the log with what the fights of each kind came to.
'''
assert s.count(anchor) == 1
if '14. *What it would not learn is done for it' not in s:
    s = s.replace(anchor, ITEM + anchor)

old8 = "Then real fights against two kinds of opponent: one that a sprint hit knocks away, and one that cannot be kept off (four fights in ten)."
assert s.count(old8) == 1
s = s.replace(old8, "Then real fights against the opponent a sprint hit knocks away and, in four fights of five, against the ones it does not: one that cannot be kept off, one that keeps running out, one that goes round it making critical hits, one that stands in a corner, one that takes no knockback, and the odd ones (see 14. under *What 0.14.0 found*).")
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
