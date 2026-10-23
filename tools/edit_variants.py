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


# ---- the opponent's profile -------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	public float critPress = 0f;
''',
     '''	public float critPress = 0f;
	/**
	 * The chance, each time it lands a hit or takes one, that it runs: for a second or two it makes
	 * for somewhere away from the agent. What a player does who keeps running out of a fight. Most of
	 * those runs are made with its back turned and at a sprint ({@link #fleeTurned}): it cannot swing
	 * then, and a fighter that only sprints after it never gets any nearer, since both run as fast. A
	 * running jump is faster than a run, and the hit made coming down from it, with the sprint let
	 * go, is a critical hit that does not send the runner on its way as a sprint hit would. The rest
	 * it backs off with its eyes on the agent and its sword ready: that one hits a fighter that
	 * jumps in on it carelessly.
	 */
	public float fleeChance = 0f;
	public float fleeTurned = 0.6f;
	/**
	 * It fights by going round the agent: it keeps to about two blocks, walks sideways round it,
	 * changing direction now and then, and makes its hits as jumped critical hits from there. A
	 * critical hit throws the agent up and hardly back, so it comes down with this opponent still
	 * in reach, but no longer where its crosshair was.
	 */
	public boolean orbit = false;
''', 1),
    ('''		b.critPress = critPress;
''',
     '''		b.critPress = critPress;
		b.fleeChance = fleeChance;
		b.fleeTurned = fleeTurned;
		b.orbit = orbit;
''', 1),
])

# ---- the scripted mind ------------------------------------------------------------------------
edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''	private static final int PRESS_TICKS = 16;
''',
     '''	private static final int PRESS_TICKS = 16;
	/** Going round the agent it keeps this far from it, eye to hitbox: inside the reach it jumps for a crit from. */
	private static final double ORBIT_RANGE = 1.8;
	/** From farther off than this it comes in at a run before it starts going round. */
	private static final double ORBIT_RUN_FROM = 4.0;
	/** A run away from the agent lasts this long: a second and a quarter to three seconds. */
	private static final int FLEE_MIN_TICKS = 25;
	private static final int FLEE_MORE_TICKS = 36;
''', 1),
    ('''	/** This fight it keeps pushing into the agent instead of holding its range. */
	private boolean rushing;
''',
     '''	/** This fight it keeps pushing into the agent instead of holding its range. */
	private boolean rushing;
	/** Ticks left of running away from the agent; see {@link BotProfile#fleeChance}. */
	private int fleeTicks;
	/** In this run its back is turned: it looks where it is going, sprints, and cannot swing. */
	private boolean fleeBackTurned;
''', 1),
    ('''		rushing = profile.rushChance > 0f && rng.nextFloat() < profile.rushChance;
		planNextSwing();
	}

	private void planNextSwing() {
		// A swing that follows up a hit comes on rhythm; a jump for a crit is how a new attack begins.
		critPlan = followUpTicks <= 0 && profile.critChance > 0f && rng.nextFloat() < profile.critChance;
''',
     '''		rushing = profile.rushChance > 0f && rng.nextFloat() < profile.rushChance;
		fleeTicks = 0;
		if (profile.fleeChance > 0f && rng.nextFloat() < 0.5f * profile.fleeChance) {
			// Some fights it runs from the start.
			startFlee();
		}
		planNextSwing();
	}

	private void startFlee() {
		fleeTicks = FLEE_MIN_TICKS + rng.nextInt(FLEE_MORE_TICKS);
		fleeBackTurned = rng.nextFloat() < profile.fleeTurned;
		wayTicks = 0;
		backoffTicks = 0;
		pressTicks = 0;
		followUpTicks = 0;
	}

	private void planNextSwing() {
		// A swing that follows up a hit comes on rhythm; a jump for a crit is how a new attack begins.
		// The one that goes round the agent makes every hit it can a jumped one.
		critPlan = (followUpTicks <= 0 || profile.orbit) && profile.critChance > 0f && rng.nextFloat() < profile.critChance;
''', 1),
    ('''		aim(bot, pack, dx, agentEye.y - 0.25 - botEye.y, dz);

		if (idle) {
''',
     '''		// Running away: towards somewhere in the arena that is away from the agent.
		boolean fleeing = !idle && !anchored && fleeTicks > 0 && BotProfile.FIGHT.equals(profile.movement);
		double fleeX = 0;
		double fleeZ = 0;
		if (fleeing) {
			double wdx = wayX - bot.getX();
			double wdz = wayZ - bot.getZ();
			if (--wayTicks <= 0 || wdx * wdx + wdz * wdz < 2.25) {
				pickRefuge(bot, agent, arena);
				wdx = wayX - bot.getX();
				wdz = wayZ - bot.getZ();
			}
			double far = Math.sqrt(wdx * wdx + wdz * wdz);
			fleeX = far > 1e-6 ? wdx / far : 0;
			fleeZ = far > 1e-6 ? wdz / far : 0;
			if (--fleeTicks == 0) {
				// It turns and fights again.
				planNextSwing();
			}
		}
		if (fleeing && fleeBackTurned) {
			// Its eyes are on where it is going.
			aim(bot, pack, fleeX, 0, fleeZ);
		} else {
			aim(bot, pack, dx, agentEye.y - 0.25 - botEye.y, dz);
		}

		if (idle) {
''', 1),
    ('''		if (--strafeTicks <= 0) {
			strafeDir = rng.nextBoolean() ? 1 : -1;
			strafeTicks = 10 + rng.nextInt(30);
		}
''',
     '''		if (--strafeTicks <= 0) {
			strafeDir = rng.nextBoolean() ? 1 : -1;
			// Going round the agent it keeps a direction for longer: a second and a half to four and a half.
			strafeTicks = profile.orbit ? 30 + rng.nextInt(60) : 10 + rng.nextInt(30);
		}
''', 1),
    ('''				double range = critPlan ? Math.min(profile.preferredRange, CRIT_RANGE) : profile.preferredRange;
				if (critJump >= 0) {
''',
     '''				double range = critPlan ? Math.min(profile.preferredRange, CRIT_RANGE) : profile.preferredRange;
				if (fleeing) {
					backoffTicks = 0;
					if (fleeBackTurned) {
						mx = fleeX;
						mz = fleeZ;
						sprint = profile.sprint;
					} else {
						// Backing off with its eyes on the agent: at a walk, which is all backwards is.
						mx = -ux;
						mz = -uz;
					}
				} else if (profile.orbit) {
					// Round the agent, at a walk: sideways, and in or out to keep its distance. In its jump
					// for a critical hit it goes on round, so it comes down beside where it took off.
					backoffTicks = 0;
					if (reachDist > ORBIT_RUN_FROM) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && critJump < 0;
					} else {
						double inward = Math.max(-1.0, Math.min(1.0, (reachDist - ORBIT_RANGE) * 1.5));
						mx = lx * strafeDir + ux * inward;
						mz = lz * strafeDir + uz * inward;
					}
				} else if (critJump >= 0) {
''', 1),
    ('''				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0) {
''',
     '''				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0 && !fleeing && !profile.orbit) {
''', 1),
    ('''		if (critPlan && critJump < 0 && profile.attacks && bot.onGround() && reachDist <= CRIT_JUMP_REACH
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE) {
''',
     '''		if (critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround() && reachDist <= CRIT_JUMP_REACH
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE) {
''', 1),
    ('''		if (profile.attacks) {
			tryAttack(bot, agent, botEye, agentBox);
		}
	}

	/** One of its swings has hurt the agent: for a while the next one follows on rhythm, not on sight. */
	void landedHit() {
		followUpTicks = profile.followUpTicks;
		if (followUpTicks > 0) {
			critPlan = false;
		}
	}

	/** The agent has hit it: now and then it backs off instead of walking straight back in. */
	void tookHit() {
		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = BACKOFF_TICKS;
		}
	}
''',
     '''		// With its back turned it has nothing under its crosshair and does not swing.
		if (profile.attacks && !(fleeing && fleeBackTurned)) {
			tryAttack(bot, agent, botEye, agentBox);
		}
	}

	/** One of its swings has hurt the agent: for a while the next one follows on rhythm, not on sight. */
	void landedHit() {
		followUpTicks = profile.followUpTicks;
		if (followUpTicks > 0 && !profile.orbit) {
			critPlan = false;
		}
		if (profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			// Hit and run.
			startFlee();
		}
	}

	/** The agent has hit it: now and then it backs off instead of walking straight back in. */
	void tookHit() {
		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = BACKOFF_TICKS;
		}
		if (profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			startFlee();
		}
	}
''', 1),
    ('''		if (crit && profile.critPress > 0f && rng.nextFloat() < profile.critPress) {
''',
     '''		if (crit && !profile.orbit && profile.critPress > 0f && rng.nextFloat() < profile.critPress) {
''', 1),
    ('''	private static float clamp(float v, float lo, float hi) {
''',
     '''	/**
	 * Where it runs to: of a dozen spots in the arena, the one farthest from the agent that does not
	 * take it past the agent to get there.
	 */
	private void pickRefuge(ServerPlayer bot, ServerPlayer agent, Arena arena) {
		double span = arena.radius - 2.5;
		double awayX = bot.getX() - agent.getX();
		double awayZ = bot.getZ() - agent.getZ();
		double best = -1;
		for (int attempt = 0; attempt < 12; attempt++) {
			double x = arena.centerX + (rng.nextDouble() * 2 - 1) * span;
			double z = arena.centerZ + (rng.nextDouble() * 2 - 1) * span;
			double toX = x - bot.getX();
			double toZ = z - bot.getZ();
			// Not through the agent: the way there has to lead away from it, or at least not towards it.
			boolean leadsAway = toX * awayX + toZ * awayZ >= 0;
			double score = Math.hypot(x - agent.getX(), z - agent.getZ()) + (leadsAway ? 100 : 0);
			if (score > best) {
				best = score;
				wayX = x;
				wayZ = z;
			}
		}
		wayTicks = 30 + rng.nextInt(30);
	}

	private static float clamp(float v, float lo, float hi) {
''', 1),
])

# ---- the stage ----------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float traderShare = 0f;
''',
     '''	public float traderShare = 0f;
	/**
	 * The share of the stage's own fights that are against a runner: the stage's opponent, but one
	 * that keeps running out of the fight ({@code BotProfile.fleeChance}). And the share against a
	 * circler: one that goes round the fighter and makes its hits as jumped critical hits
	 * ({@code BotProfile.orbit}). What they are for: against every opponent up to 0.14.0 a jump was
	 * the wrong call, since each of them either swung at whatever came into its reach or could not be
	 * kept off at all, and so the fighter stopped jumping. A player who runs is caught by a running
	 * jump and not by a run, and the hit to make on one is a critical hit; one who circles and crits
	 * leaves a punish crit to make after every hit, to a fighter that can keep its crosshair on it.
	 */
	public float runnerShare = 0f;
	public float circlerShare = 0f;
	/** How often a runner runs, each time it lands a hit or takes one; see {@code BotProfile.fleeChance}. */
	public float runnerFlee = 0.8f;
	/** The share of a circler's swings that are jumped critical hits. */
	public float circlerCrit = 0.85f;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.traderShare = 0.4f;
''',
     '''		st.traderShare = 0.25f;
		// And a quarter each against one that keeps running out of the fight and one that goes round
		// the fighter making critical hits; see StageSpec.runnerShare. Those are the fights a jump is
		// for. The rest, a quarter, are against the opponent a sprint hit knocks away, which still
		// swings at a fighter that jumps into its reach.
		st.runnerShare = 0.25f;
		st.circlerShare = 0.25f;
''', 1),
])

# ---- the trainer ----------------------------------------------------------------------------------
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		episodeTrader = !episodeDrill && !episodeCritDrill && !againstItself && stage.traderShare > 0f
				&& rng.nextFloat() < stage.traderShare;
		if (episodeTrader) {
			plan.bot.rushChance = 1f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, stage.traderReactionTicks);
			plan.bot.dodgeChance = 0f;
		}
''',
     '''		// And shares against one that keeps running out of the fight, and one that goes round the
		// fighter making critical hits; see StageSpec.runnerShare.
		boolean scripted = !episodeDrill && !episodeCritDrill && !againstItself;
		float kind = rng.nextFloat();
		episodeTrader = scripted && kind < stage.traderShare;
		episodeRunner = scripted && !episodeTrader && kind < stage.traderShare + stage.runnerShare;
		episodeCircler = scripted && !episodeTrader && !episodeRunner
				&& kind < stage.traderShare + stage.runnerShare + stage.circlerShare;
		if (episodeTrader) {
			plan.bot.rushChance = 1f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, stage.traderReactionTicks);
			plan.bot.dodgeChance = 0f;
		} else if (episodeRunner) {
			plan.bot.fleeChance = stage.runnerFlee;
			plan.bot.rushChance = 0f;
			plan.bot.critChance = 0f;
		} else if (episodeCircler) {
			plan.bot.orbit = true;
			plan.bot.critChance = stage.circlerCrit;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
		}
''', 1),
    ('''		return episodeStageIndex != curriculum.stageIndex() ? rehearsalTally : episodeTrader ? traderTally : tally;
''',
     '''		// The fights against a runner and against a circler are kept apart wherever they are fought,
		// in their own stage or replayed from a later one: they are what says whether it jumps for a
		// critical hit where one is to be had.
		if (episodeRunner) {
			return runnerTally;
		}
		if (episodeCircler) {
			return circlerTally;
		}
		return episodeStageIndex != curriculum.stageIndex() ? rehearsalTally : episodeTrader ? traderTally : tally;
''', 1),
    ('''	final Tally rehearsalTally = new Tally();
''',
     '''	final Tally rehearsalTally = new Tally();
	/** The fights against a runner and against a circler since the last update; see {@code StageSpec.runnerShare}. */
	final Tally runnerTally = new Tally();
	final Tally circlerTally = new Tally();
	final Tally lastRunner = new Tally();
	final Tally lastCircler = new Tally();
	boolean episodeRunner;
	boolean episodeCircler;
''', 1),
    ('''		pendingTally.add(tally);
		pendingTally.add(traderTally);
''',
     '''		pendingTally.add(tally);
		pendingTally.add(traderTally);
		if (curriculum.current().runnerShare > 0f || curriculum.current().circlerShare > 0f) {
			// Where they are the stage's own fights they count towards it like the others.
			pendingTally.add(runnerTally);
			pendingTally.add(circlerTally);
		}
''', 1),
    ('''		keepLast(traderTally, lastTrader);
		traderTally.clear();
''',
     '''		keepLast(traderTally, lastTrader);
		traderTally.clear();
		keepLast(runnerTally, lastRunner);
		runnerTally.clear();
		keepLast(circlerTally, lastCircler);
		circlerTally.clear();
''', 1),
    ('''slow connection {} | ordinary connection {} | combo drills {} | scripted opponents of earlier stages {} | swings made by the answer rule {}",''',
     '''slow connection {} | ordinary connection {} | combo drills {} | scripted opponents of earlier stages {} | runners {} | circlers {} | swings made by the answer rule {}",''', 1),
    ('''				slowCount.brief(), quickCount.brief(), brief(drillTally), brief(rehearsalTally), forcedAnswers);''',
     '''				slowCount.brief(), quickCount.brief(), brief(drillTally), brief(rehearsalTally), brief(runnerTally), brief(circlerTally), forcedAnswers);''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 55', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

# ---- the log reader -------------------------------------------------------------------------------
edit('agg.ps1', [
    ('''(?: \\| scripted opponents of earlier stages (.*?))?(?: \\| swings made by the answer rule (\\d+))?$') {''',
     '''(?: \\| scripted opponents of earlier stages (.*?))?(?: \\| runners (.*?) \\| circlers (.*?))?(?: \\| swings made by the answer rule (\\d+))?$') {''', 1),
    ('''reh = (Parse ([string]$Matches[11])) })''',
     '''reh = (Parse ([string]$Matches[11])); run = (Parse ([string]$Matches[12])); circ = (Parse ([string]$Matches[13])) })''', 1),
    ('''$slow = Zero; $ord = Zero; $cd = Zero; $reh = Zero
''', '''$slow = Zero; $ord = Zero; $cd = Zero; $reh = Zero; $run = Zero; $circ = Zero
''', 1),
    ('''Add $cd $parsed[$k].cd; Add $reh $parsed[$k].reh }''',
     '''Add $cd $parsed[$k].cd; Add $reh $parsed[$k].reh; Add $run $parsed[$k].run; Add $circ $parsed[$k].circ }''', 1),
    ('''    $all = $own.st + $tr.st + $d1.st + $pu.st
    if ($all -le 0) { continue }
''',
     '''    $all = $own.st + $tr.st + $d1.st + $pu.st + $run.st + $circ.st
    if ($all -le 0) { continue }
    if ($Crit) {
        $out.Add(('{0} {1,5:N0}k | own {2} | trader {3} | RUNNER {4} | CIRCLER {5} | face {6}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (Show $own $all), (Show $tr $all), (ShowCrit $run $all), (ShowCrit $circ $all), (Show $d1 $all)))
        continue
    }
''', 1),
    ('''param([int]$Block = 20, [int]$Last = 0, [string]$Log = "", [switch]$Swings)''',
     '''param([int]$Block = 20, [int]$Last = 0, [string]$Log = "", [switch]$Swings, [switch]$Crit)''', 1),
    ('''function ShowSwings($a) {''',
     '''function ShowCrit($a, [double]$all) {
    # dealt/taken a minute, crit share, jumps a minute (share ending in a crit), punish crits made/chances, air swings a minute (share at nothing), reward
    if ($a.st -le 0) { return '-' }
    $m = $a.st / 1200.0
    $cs = 0; if ($a.h -gt 0) { $cs = 100 * $a.c / $a.h }
    $js = 0; if ($a.j -gt 0) { $js = 100 * $a.jc / $a.j }
    $am = 0; if ($a.air -gt 0) { $am = 100 * $a.airMiss / $a.air }
    return ('{0,3:N0}/{1,-3:N0} c{2,3:N0}% j{3,3:N0}/m({4,3:N0}%) pun {5:N0}/{6:N0} air{7,3:N0}/m({8,2:N0}% miss) r{9,6:N3} [{10,2:N0}%]' -f ($a.h / $m), ($a.t / $m), $cs, ($a.j / $m), $js, $a.pt, $a.pc, ($a.air / $m), $am, ($a.r / $a.st), (100 * $a.st / $all))
}
function ShowSwings($a) {''', 1),
], root=SP)
print('ok')
