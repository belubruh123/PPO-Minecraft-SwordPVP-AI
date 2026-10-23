import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


# ---- Config -------------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean punishRule = true;
''',
     '''	public boolean punishRule = true;
	/**
	 * A running jump is made for it at an opponent it is running after and not catching, a few steps
	 * beyond its reach, and the hit on the way down is made a critical hit; see {@code JumpIn}.
	 */
	public boolean chaseRule = true;
	/**
	 * A jump is made for it when the opponent is still in its face a few ticks after its hit, and the
	 * hit on the way down is a critical hit: against one that a hit does not send away; see {@code JumpIn}.
	 */
	public boolean faceRule = true;
	/**
	 * The share of the hits of a combo of its own, from the third on, that an uppercut follows: a
	 * running jump and the sprint hit made on the way up; see {@code JumpIn}. 0: never.
	 */
	public float uppercutChance = 0.25f;
	/**
	 * The camera is left alone while the opponent is far away and the crosshair is on it or all but
	 * on it; see {@code FarRest}.
	 */
	public boolean farRestRule = true;
''', 1),
])
p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 59', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

# ---- StepFacts ----------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/env/StepFacts.java', [
    ('''	public boolean uppercut;
	/**
	 * The landed hit was a sweep''',
     '''	public boolean uppercut;
	/** The jump this step took off on was made by a rule: which one ({@code JumpIn.CHASE} and so on), or 0. */
	public int ruleJump;
	/**
	 * The landed hit was a sweep''', 1),
    ('''		uppercut = false;
''',
     '''		uppercut = false;
		ruleJump = 0;
''', 1),
])

# ---- Tally --------------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/Tally.java', [
    ('''	public long chaseJumps;
	public long chaseCrits;
	/** The ticks in which backing out is asked of it''',
     '''	public long chaseJumps;
	public long chaseCrits;
	/**
	 * Jumps a rule made for it (see {@code JumpIn}), by kind, and what came of them: the chase and the
	 * crit in its face by how many ended in a critical hit, the uppercut by how many hit on the way up;
	 * and in how many of all of them it was hit before it was down again.
	 */
	public long chaseRuleJumps;
	public long chaseRuleCrits;
	public long faceRuleJumps;
	public long faceRuleCrits;
	public long upperRuleJumps;
	public long upperRuleHits;
	public long ruleJumpsHit;
	/**
	 * Ticks with the opponent in view and far away (see {@code RewardModel.FAR_AIM_FROM}); on how many
	 * of them the camera did not move; how often it changed the direction it was turning in; and how
	 * far it turned in all, in hundredths of a degree.
	 */
	public long farTicks;
	public long farStillTicks;
	public long farFlips;
	public long farTurnCentiDeg;
	/** The ticks in which backing out is asked of it''', 1),
    ('''		chaseJumps += t.chaseJumps;
		chaseCrits += t.chaseCrits;
''',
     '''		chaseJumps += t.chaseJumps;
		chaseCrits += t.chaseCrits;
		chaseRuleJumps += t.chaseRuleJumps;
		chaseRuleCrits += t.chaseRuleCrits;
		faceRuleJumps += t.faceRuleJumps;
		faceRuleCrits += t.faceRuleCrits;
		upperRuleJumps += t.upperRuleJumps;
		upperRuleHits += t.upperRuleHits;
		ruleJumpsHit += t.ruleJumpsHit;
		farTicks += t.farTicks;
		farStillTicks += t.farStillTicks;
		farFlips += t.farFlips;
		farTurnCentiDeg += t.farTurnCentiDeg;
''', 2),
    ('''		chaseJumps = 0;
		chaseCrits = 0;
''',
     '''		chaseJumps = 0;
		chaseCrits = 0;
		chaseRuleJumps = 0;
		chaseRuleCrits = 0;
		faceRuleJumps = 0;
		faceRuleCrits = 0;
		upperRuleJumps = 0;
		upperRuleHits = 0;
		ruleJumpsHit = 0;
		farTicks = 0;
		farStillTicks = 0;
		farFlips = 0;
		farTurnCentiDeg = 0;
''', 1),
])

# ---- RewardModel --------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''	private boolean jumpChase;
''',
     '''	private boolean jumpChase;
	/** The jump it is in was made by a rule: which one (see {@code JumpIn}), or 0. */
	private int jumpRule;
	/** The way the camera was last turning while the opponent was far away: -1, +1, or 0. */
	private int farDir;
	/** From this far off the opponent is far away: what the camera does there is counted (see {@code Tally.farTicks}). */
	static final float FAR_AIM_FROM = 9f;
''', 1),
    ('''		prevMiss = miss;
		prevYawErr = f.yawErrDeg;
''',
     '''		if (f.enemyVisible && f.reachDist > FAR_AIM_FROM && !f.discontinuity) {
			tally.farTicks++;
			tally.farTurnCentiDeg += Math.round(turned * 100f);
			if (turned < STILL_SLIP) {
				tally.farStillTicks++;
			}
			int dir = f.yawTurnDeg > 0.05f ? 1 : f.yawTurnDeg < -0.05f ? -1 : 0;
			if (dir != 0) {
				if (farDir != 0 && dir != farDir) {
					tally.farFlips++;
				}
				farDir = dir;
			}
		} else if (!f.enemyVisible || f.discontinuity) {
			farDir = 0;
		}
		prevMiss = miss;
		prevYawErr = f.yawErrDeg;
''', 1),
    ('''			if (f.uppercut) {
				tally.uppercuts++;
''',
     '''			if (f.uppercut) {
				tally.uppercuts++;
				if (jumpAge >= 0 && jumpRule == JumpIn.UPPER) {
					tally.upperRuleHits++;
					jumpRule = 0;
				}
''', 1),
    ('''					if (jumpChase) {
						// And it was made at an opponent that a run was not catching.
						r += w.chaseCrit;
						tally.chaseCrits++;
					}
''',
     '''					if (jumpChase) {
						// And it was made at an opponent that a run was not catching.
						r += w.chaseCrit;
						tally.chaseCrits++;
					}
					if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.UPPER) {
						tally.chaseRuleCrits++;
					} else if (jumpRule == JumpIn.FACE) {
						tally.faceRuleCrits++;
					}
''', 1),
    ('''			jumpChase = jumpAge >= 0 && chasing && f.reachAtAction > w.chaseFrom && f.reachAtAction <= w.chaseTo;
			if (jumpChase) {
				tally.chaseJumps++;
			}
''',
     '''			jumpChase = jumpAge >= 0 && chasing && f.reachAtAction > w.chaseFrom && f.reachAtAction <= w.chaseTo;
			if (jumpChase) {
				tally.chaseJumps++;
			}
			jumpRule = jumpAge >= 0 ? f.ruleJump : 0;
			if (jumpRule == JumpIn.CHASE) {
				tally.chaseRuleJumps++;
			} else if (jumpRule == JumpIn.FACE) {
				tally.faceRuleJumps++;
			} else if (jumpRule == JumpIn.UPPER) {
				tally.upperRuleJumps++;
			}
''', 1),
    ('''						if (jumpOutOfZone && !jumpChase) {
''',
     '''						// Nor one a rule made for it: that was not its to leave out.
						if (jumpOutOfZone && !jumpChase && jumpRule == 0) {
''', 1),
    ('''			tally.hitsTaken += f.hitsTaken;
			tally.critsTaken += f.critsTaken;
			punishWatch = true;
''',
     '''			tally.hitsTaken += f.hitsTaken;
			tally.critsTaken += f.critsTaken;
			if (jumpAge >= 0 && jumpRule != 0) {
				// Hit in the air of a jump a rule made for it.
				tally.ruleJumpsHit++;
			}
			punishWatch = true;
''', 1),
    ('''		jumpChase = false;
		runTicks = 0;
''',
     '''		jumpChase = false;
		jumpRule = 0;
		farDir = 0;
		runTicks = 0;
''', 1),
])
s = open(ROOT + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', encoding='utf-8').read()
if 'import dev.swordai.core.rl.JumpIn;' not in s:
    s = s.replace('import dev.swordai.core.rl.JumpNudge;', 'import dev.swordai.core.rl.JumpIn;\nimport dev.swordai.core.rl.JumpNudge;', 1)
    assert 'import dev.swordai.core.rl.JumpIn;' in s
    open(ROOT + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', 'w', encoding='utf-8', newline='').write(s)

# ---- Fighter ------------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final boolean punishRule;
''',
     '''	private final boolean punishRule;
	/** The jumps that are made for it, if its training has given it the jump key; see {@link JumpIn}. */
	private final JumpIn jumpIn;
	private final boolean farRest;
	/** Hits it has landed in a row without taking one. */
	private int dealtRow;
''', 1),
    ('''		this.punishRule = cfg.punishRule;
''',
     '''		this.punishRule = cfg.punishRule;
		boolean jumps = ActionSpace.unlocked(headMask, ActionSpace.JUMP) && ActionSpace.unlocked(headMask, ActionSpace.SPRINT);
		this.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f)
				? new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, seed ^ 0x5eedL) : null;
		this.farRest = cfg.farRestRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD);
''', 1),
    ('''		ticksBack = 0;
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''',
     '''		ticksBack = 0;
		dealtRow = 0;
		if (jumpIn != null) {
			jumpIn.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', 1),
    ('''		sinceHitDealt = 0;
		dealtSince++;
''',
     '''		sinceHitDealt = 0;
		dealtSince++;
		dealtRow++;
''', 1),
    ('''		sinceHitTaken = 0;
		takenSince++;
''',
     '''		sinceHitTaken = 0;
		takenSince++;
		dealtRow = 0;
''', 1),
    ('''		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		learner.act(obs, keys, false, false, temperature, action);
''',
     '''		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		// The jumps that are made for it, and its keys in the air of them; see JumpIn.
		int jump = jumpIn == null ? 0 : jumpIn.plan(obs.vec, keys, crosshairOnOpponent, owes || backOut, dealtRow);
		keys = JumpIn.keys(keys, jump);
		// And the camera is left alone on an opponent that is far away; see FarRest.
		if (farRest && FarRest.applies(obs.vec)) {
			keys = FarRest.keys(keys);
		}
		learner.act(obs, keys, false, false, temperature, action);
''', 1),
    ('''		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		if (action[ActionSpace.ATTACK] == 1) {
			idea.swung(rng);
		}
''',
     '''		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerForced = true;
		}
		if (action[ActionSpace.ATTACK] == 1) {
			idea.swung(rng);
		}
''', 1),
])

# ---- BotProfile ---------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	/** Largest bearing from the agent's current view direction, in degrees either side. */
	public float maxBearingDeg = 50f;
''',
     '''	/** Largest bearing from the agent's current view direction, in degrees either side. */
	public float maxBearingDeg = 50f;
	/** And the smallest: above zero the bot is never put straight ahead, but off to a side or behind. */
	public float minBearingDeg = 0f;
''', 1),
    ('''	public boolean orbit = false;
''',
     '''	public boolean orbit = false;
	/**
	 * A run does not end by itself: it keeps away from the agent until the agent has hit it, turning
	 * its back whenever the agent comes near and looking back at it from a distance. What a player
	 * does who will not be caught. Only a fighter that covers ground faster than a run gets to it.
	 */
	public boolean fleeUntilHit = false;
	/**
	 * It does not walk: it stands where it was put (in a corner, with the wall behind it), turns to
	 * face the agent and hits it whenever it comes into reach, jumping for its critical hits on the
	 * spot. A hit does not send it anywhere, so what decides the fight is what each hit does.
	 */
	public boolean holdGround = false;
	/** Something odd it does all fight long; one of the {@code ANTIC_} names, or empty. */
	public String antic = "";
	/** It jumps all the time. */
	public static final String ANTIC_HOP = "hop";
	/** It keeps turning on the spot as it walks in, and swings whenever the agent passes under its crosshair. */
	public static final String ANTIC_SPIN = "spin";
	/** It stands still for seconds on end, then runs at the agent, then stands again. */
	public static final String ANTIC_STATUE = "statue";
	/** It changes the way it is going every few ticks. */
	public static final String ANTIC_ZIGZAG = "zigzag";
	/** It clicks as fast as it can, long before its sword is back. */
	public static final String ANTIC_SPAM = "spam";
	/** It keeps ducking and standing up. */
	public static final String ANTIC_DUCK = "duck";
	public static final String[] ANTICS = {ANTIC_HOP, ANTIC_SPIN, ANTIC_STATUE, ANTIC_ZIGZAG, ANTIC_SPAM, ANTIC_DUCK};
''', 1),
    ('''		b.maxBearingDeg = maxBearingDeg;
''',
     '''		b.maxBearingDeg = maxBearingDeg;
		b.minBearingDeg = minBearingDeg;
''', 1),
    ('''		b.orbit = orbit;
''',
     '''		b.orbit = orbit;
		b.fleeUntilHit = fleeUntilHit;
		b.holdGround = holdGround;
		b.antic = antic;
''', 1),
    ('''		b.maxBearingDeg = DRILL_BEARING_DEG;
''',
     '''		b.maxBearingDeg = DRILL_BEARING_DEG;
		b.minBearingDeg = 0f;
''', 1),
])

# ---- StageSpec ----------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float runnerFlee = 0.8f;
''',
     '''	public float runnerFlee = 0.8f;
	/** The share of the fights against a runner in which it keeps away until it is hit; see {@code BotProfile.fleeUntilHit}. */
	public float runnerKeepsAway = 0.5f;
	/**
	 * Shares of the stage's fights against more opponents of its own kind each:
	 * <ul>
	 * <li>one that stands in a corner and makes critical hits from there ({@code BotProfile.holdGround}),
	 * with the fight begun next to that corner;</li>
	 * <li>one that takes next to no knockback and makes critical hits, one after the other, from on top
	 * of the fighter: what a cheat looks like, and a player who has learned to hit without knocking back;</li>
	 * <li>one that does something odd all fight long ({@code BotProfile.ANTICS}).</li>
	 * </ul>
	 */
	public float cornerShare = 0f;
	public float noKnockShare = 0f;
	public float oddShare = 0f;
	/**
	 * The share of the fights against one that goes round the fighter, or takes no knockback, that
	 * begin with it close by and off to a side or behind: out of the picture, and making its first
	 * critical hit from there.
	 */
	public float besideShare = 0f;
	/**
	 * The share of the fights against a scripted opponent in which that opponent is not the stage's
	 * as written but one drawn afresh: how quick it is, how well it aims, how near it likes to be,
	 * how often it jumps, backs off or comes in for a critical hit.
	 */
	public float randomShare = 0f;
''', 1),
])
print('core ok')
