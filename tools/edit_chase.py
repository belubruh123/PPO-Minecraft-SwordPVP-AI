import re

ROOT = 'D:/swordai/'
SP = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/RewardWeights.java', [
    ('''	public float farJump = 0f;
	public float jumpWithin = 4.5f;
''',
     '''	public float farJump = 0f;
	public float jumpWithin = 4.5f;
	/**
	 * A critical hit that comes of a jump made at an opponent it is running after and not getting
	 * nearer to: on top of what a critical hit earns. The opponent is {@link #chaseFrom} to
	 * {@link #chaseTo} blocks off, the fighter has been at a sprint towards it for a quarter of a
	 * second and has closed next to nothing, and it has not just hit it (an opponent flying off from a
	 * hit is not one that is running). Such a jump is also never charged as wasted
	 * ({@link #wastedJump}), whatever comes of it.
	 *
	 * <p>Why: a run does not catch a player who is backing off or running, and a running jump does;
	 * the hit made coming down from it is a critical hit, which does not send that player on its way
	 * as a sprint hit would. But a jump that is tried and comes to nothing costs, everywhere, and so
	 * the first thing ever learned about jumping was not to (four runs of 0.14.0, each time within
	 * fifty updates). Here, and only here, trying is free. Being hit in the air is not: an opponent
	 * that backs off with its sword ready still makes a careless jump cost what a hit costs.
	 * Zero: no such thing.
	 */
	public float chaseCrit = 0f;
	public float chaseFrom = 3.0f;
	public float chaseTo = 6.0f;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/Tally.java', [
    ('''	public long combosSatThrough;
''',
     '''	public long combosSatThrough;
	/** Jumps made at an opponent it was running after and not catching ({@code RewardWeights.chaseCrit}), and how many ended in a critical hit. */
	public long chaseJumps;
	public long chaseCrits;
''', 1),
    ('''		combosSatThrough += t.combosSatThrough;
''',
     '''		combosSatThrough += t.combosSatThrough;
		chaseJumps += t.chaseJumps;
		chaseCrits += t.chaseCrits;
''', 2),
    ('''		combosSatThrough = 0;
''',
     '''		combosSatThrough = 0;
		chaseJumps = 0;
		chaseCrits = 0;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''	private boolean jumpOutOfZone;
''',
     '''	private boolean jumpOutOfZone;
	/** The jump it is in was made at an opponent it was running after and not catching; see {@code RewardWeights.chaseCrit}. */
	private boolean jumpChase;
	/** For how many ticks in a row it has been on the ground at a sprint towards the opponent. */
	private int runTicks;
	/** How far off the opponent was on each of the last ticks, newest last. */
	private final float[] reachTrail = new float[CHASE_TICKS + 1];
	private int reachTrailLen;
''', 1),
    ('''				if (jumpAge >= 0) {
					// The jump it is in has come to what a jump is for.
					tally.jumpsEnded++;
					tally.jumpCrits++;
					jumpAge = -1;
				}
''',
     '''				if (jumpAge >= 0) {
					// The jump it is in has come to what a jump is for.
					tally.jumpsEnded++;
					tally.jumpCrits++;
					if (jumpChase) {
						// And it was made at an opponent that a run was not catching.
						r += w.chaseCrit;
						tally.chaseCrits++;
					}
					jumpAge = -1;
				}
''', 1),
    ('''		if (f.tookOff) {
			tally.jumps++;
			jumpAge = sinceHop > HOP_SETTLE_TICKS ? 0 : -1;
			jumpAirborne = false;
			jumpOutOfZone = f.reachAtAction > (f.critZone > 0f ? f.critZone : w.jumpFreeWithin);
		} else if (jumpAge >= 0) {
''',
     '''		// Running after the opponent and not getting nearer: at a sprint towards it for a quarter of a
		// second, and what that should have closed has not been closed.
		boolean running = f.onGround && f.forwardAxis > 0 && f.sprintKey && f.enemyVisible && !f.discontinuity;
		runTicks = running ? runTicks + 1 : 0;
		if (f.discontinuity) {
			reachTrailLen = 0;
		}
		boolean chasing = w.chaseCrit > 0f && runTicks >= CHASE_TICKS && reachTrailLen > CHASE_TICKS
				&& reachTrail[0] - f.reachAtAction < CHASE_CLOSED_MOST && ticksSinceHit > CHASE_AFTER_HIT_TICKS
				&& sinceHop > HOP_SETTLE_TICKS;
		if (reachTrailLen > CHASE_TICKS) {
			System.arraycopy(reachTrail, 1, reachTrail, 0, CHASE_TICKS);
			reachTrailLen = CHASE_TICKS;
		}
		reachTrail[reachTrailLen++] = f.reachAtAction;
		if (f.tookOff) {
			tally.jumps++;
			jumpAge = sinceHop > HOP_SETTLE_TICKS ? 0 : -1;
			jumpAirborne = false;
			jumpOutOfZone = f.reachAtAction > (f.critZone > 0f ? f.critZone : w.jumpFreeWithin);
			jumpChase = jumpAge >= 0 && chasing && f.reachAtAction > w.chaseFrom && f.reachAtAction <= w.chaseTo;
			if (jumpChase) {
				tally.chaseJumps++;
			}
		} else if (jumpAge >= 0) {
''', 1),
    ('''						if (jumpOutOfZone) {
							r -= w.wastedJump;
						}
''',
     '''						if (jumpOutOfZone && !jumpChase) {
							r -= w.wastedJump;
						}
''', 1),
    ('''	private int sinceHop = NEVER;
''',
     '''	private int sinceHop = NEVER;
	/** A quarter of a second at a sprint closes a block and a half on an opponent that stands; see {@code RewardWeights.chaseCrit}. */
	static final int CHASE_TICKS = 5;
	/** Closing less than this in that time, the opponent is going away about as fast as a walk or faster. */
	static final float CHASE_CLOSED_MOST = 0.7f;
	/** An opponent it hit this recently is flying off from that hit, not running. */
	static final int CHASE_AFTER_HIT_TICKS = 15;
''', 1),
])

# Reset with the episode.
p = ROOT + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java'
s = open(p, encoding='utf-8').read()
i = s.rindex('		jumpAge = -1;\n')
s = s[:i] + '		jumpAge = -1;\n		jumpChase = false;\n		runTicks = 0;\n		reachTrailLen = 0;\n' + s[i + len('		jumpAge = -1;\n'):]
open(p, 'w', encoding='utf-8', newline='').write(s)

edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	public float fleeTurned = 0.6f;
''', '''	public float fleeTurned = 0.35f;
''', 1),
    ('''	 * for somewhere away from the agent. What a player does who keeps running out of a fight. Most of
	 * those runs are made with its back turned and at a sprint ({@link #fleeTurned}): it cannot swing
	 * then, and a fighter that only sprints after it never gets any nearer, since both run as fast. A
	 * running jump is faster than a run, and the hit made coming down from it, with the sprint let
	 * go, is a critical hit that does not send the runner on its way as a sprint hit would. The rest
	 * it backs off with its eyes on the agent and its sword ready: that one hits a fighter that
	 * jumps in on it carelessly.
''',
     '''	 * for somewhere away from the agent. What a player does who keeps running out of a fight. Most
	 * times it backs off with its eyes on the agent and its sword ready: a run closes on that only
	 * slowly, a running jump twice as fast, and it hits a fighter that comes in on it carelessly. The
	 * rest ({@link #fleeTurned}) it turns its back and sprints: it cannot swing then, and a fighter
	 * that only sprints after it never gets any nearer, since both run as fast. Either way the hit
	 * made coming down from a running jump, with the sprint let go, is a critical hit, which does not
	 * send the runner on its way as a sprint hit would.
''', 1),
])

edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
    ('''		assertTrue(runner.copy().fleeTurned > 0.5f, "most of its runs are made with its back turned");''',
     '''		assertTrue(runner.copy().fleeTurned > 0f && runner.copy().fleeTurned < 0.5f, "most times it backs off with its sword ready");''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.runnerShare = 0.25f;
		st.circlerShare = 0.25f;
''',
     '''		st.runnerShare = 0.25f;
		st.circlerShare = 0.25f;
		// A jump at an opponent that a run is not catching is free to try, and a critical hit that
		// comes of it earns as much again as half a critical hit; see RewardWeights.chaseCrit.
		st.reward.chaseCrit = 1.5f;
''', 1),
    ('''		st.reward.airWhiff = 0.3f;
		st.reward.comboAsCrit = 0f;
		st.reward.uppercut = 0.3f;
''',
     '''		st.reward.airWhiff = 0.3f;
		st.reward.chaseCrit = 0.4f;
		st.reward.comboAsCrit = 0f;
		st.reward.uppercut = 0.3f;
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		if (episodeTrader || face) {
''',
     '''		if (episodeTrader || face || episodeCircler) {
''', 1),
    ('''combos over %d of which without a swing back %d (%d of them three hits or more), S held %d/%d",''',
     '''combos over %d of which without a swing back %d (%d of them three hits or more), S held %d/%d, chase jumps %d of which a crit %d",''', 1),
    ('''				t.combosOver, t.combosUnanswered, t.combosSatThrough, t.pushHeld, t.pushTicks);
	}''',
     '''				t.combosOver, t.combosUnanswered, t.combosSatThrough, t.pushHeld, t.pushTicks, t.chaseJumps, t.chaseCrits);
	}''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 57', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

edit('agg.ps1', [
    ('''(?: \\((\\d+) of them three hits or more\\), S held (\\d+)/(\\d+))?)?)?)?') {''',
     '''(?: \\((\\d+) of them three hits or more\\), S held (\\d+)/(\\d+)(?:, chase jumps (\\d+) of which a crit (\\d+))?)?)?)?)?') {''', 1),
    ("""'over','noSwing','sat','sHeld','sTicks')""", """'over','noSwing','sat','sHeld','sTicks','cj','cc')""", 1),
    ("sat = 0.0; sHeld = 0.0; sTicks = 0.0 }", "sat = 0.0; sHeld = 0.0; sTicks = 0.0; cj = 0.0; cc = 0.0 }", 2),
    ('''        if ($Matches[22] -ne $null -and $Matches[22] -ne '') { $o.sat = [double]$Matches[22]; $o.sHeld = [double]$Matches[23]; $o.sTicks = [double]$Matches[24] }
''',
     '''        if ($Matches[22] -ne $null -and $Matches[22] -ne '') { $o.sat = [double]$Matches[22]; $o.sHeld = [double]$Matches[23]; $o.sTicks = [double]$Matches[24] }
        if ($Matches[25] -ne $null -and $Matches[25] -ne '') { $o.cj = [double]$Matches[25]; $o.cc = [double]$Matches[26] }
''', 1),
    ('''pun {5:N0}/{6:N0} air{7,3:N0}/m({8,2:N0}% miss) r{9,6:N3} [{10,2:N0}%]' -f ($a.h / $m), ($a.t / $m), $cs, ($a.j / $m), $js, $a.pt, $a.pc, ($a.air / $m), $am, ($a.r / $a.st), (100 * $a.st / $all))''',
     '''pun {5:N0}/{6:N0} chase {11:N0}/{12:N0} air{7,3:N0}/m({8,2:N0}% miss) r{9,6:N3} [{10,2:N0}%]' -f ($a.h / $m), ($a.t / $m), $cs, ($a.j / $m), $js, $a.pt, $a.pc, ($a.air / $m), $am, ($a.r / $a.st), (100 * $a.st / $all), $a.cc, $a.cj)''', 1),
], root=SP)
print('ok')
