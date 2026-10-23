ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''	private static final double RUN_JUMP_ENDS_WITHIN = 2.6;
	private static final double RUN_JUMP_FARTHEST = 5.6;
	/** It is not jumped at an agent that is itself coming in faster than this: that one is in reach on the ground. */
	private static final double RUN_JUMP_NOT_AT = -0.1;
''', '''	private static final double RUN_JUMP_ENDS_WITHIN = 2.85;
	private static final double RUN_JUMP_FARTHEST = 5.6;
	/**
	 * The jump is for an agent that is going away from it at this much a tick or more (walking
	 * backwards is 0.2) and is out of its reach or all but. An agent that stands or comes in gets the
	 * sprint hit, on rhythm, from the edge of its reach.
	 */
	private static final double RUN_JUMP_AT = 0.08;
	private static final double RUN_JUMP_BEYOND = 2.7;
''', 1),
    ('''		AABB agentBox = seenBox.pass(agent.getBoundingBox());
		double reachDist = Math.sqrt(agentBox.distanceToSqr(botEye));
''', '''		AABB agentBox = seenBox.pass(agent.getBoundingBox());
		double reachDist = Math.sqrt(agentBox.distanceToSqr(botEye));
		if (profile.jumpIn && critJump < 0 && punishTicks <= 0 && !fleeing) {
			// Which hit its next one is, is decided by what the agent is doing right now.
			critPlan = agentAway >= RUN_JUMP_AT && reachDist > RUN_JUMP_BEYOND;
		}
''', 1),
    ('''		if (profile.jumpIn && critPlan && critJump < 0 && agentAway < RUN_JUMP_NOT_AT && reachDist <= bot.entityInteractionRange() + 0.3) {
			// The agent is coming in itself and is all but in reach: this swing is an ordinary one.
			critPlan = false;
		}
		// A running jump is taken from where its way down will bring the agent into reach.
		boolean runJump = profile.jumpIn && agentAway >= RUN_JUMP_NOT_AT
				&& reachDist <= Math.min(RUN_JUMP_FARTHEST, RUN_JUMP_ENDS_WITHIN + RUN_JUMP_COVERS - RUN_JUMP_TICKS * Math.max(0, agentAway));''',
     '''		// A running jump is taken from where its way down will bring the agent into reach.
		boolean runJump = profile.jumpIn
				&& reachDist <= Math.min(RUN_JUMP_FARTHEST, RUN_JUMP_ENDS_WITHIN + RUN_JUMP_COVERS - RUN_JUMP_TICKS * Math.max(0, agentAway));''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	/**
	 * Its jumps for a critical hit are running jumps, taken from as far off as the way down will
	 * bring the agent into its reach: up to five and a half blocks from an agent that stands, four
	 * from one that is backing off. It sprints up to the top of the jump, lets go of the sprint and
	 * swings on the way down, and is off the ground again as soon as its sword is far enough back.
	 * What a player does to one that backs out of combos with S: every hit after the first is a
	 * critical one, and backing off only makes room for the next jump.
	 */''', '''	/**
	 * It combos with sprint hits from the edge of its reach, and an agent that backs off out of that
	 * reach it comes after with a running jump, taken from as far off as the way down will bring the
	 * agent into reach (four blocks from one that is walking backwards): it sprints up to the top of
	 * the jump, lets go of the sprint and makes a critical hit on the way down. Which of the two its
	 * next hit is, is decided tick by tick by what the agent is doing. What a player does to one that
	 * backs out of combos with S: backing off only makes room for the next jump.
	 */''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''		} else if (episodeKind == KIND_JUMPER) {
			// Whenever the fighter is beyond its reach and not coming in, it comes after it with a running
			// jump and makes its hit on the way down; and from there again, for as long as that works.
			plan.bot.jumpIn = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 0.7f + 0.3f * rng.nextFloat();
			plan.bot.critPress = 1f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 2 + rng.nextInt(3));
			plan.bot.farChance = 0f;
		} else if''', '''		} else if (episodeKind == KIND_JUMPER) {
			// As good a player as a script can be. Its combo is sprint hits from the edge of its reach, on
			// rhythm, with nothing late and nothing off; and whenever the fighter backs off out of that
			// reach it comes after it with a running jump and makes its hit on the way down, a critical
			// one. The fight begins with its combo already on: one or two hits in (the head start).
			plan.bot.jumpIn = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;
			plan.bot.critPress = 1f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.reactionTicks = rng.nextInt(2);
			plan.bot.aimNoiseDeg = 0.5f + rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 1f;
			plan.bot.preferredRange = 2.9f;
			plan.bot.followUpTicks = dev.swordai.core.env.TakenStreak.COMBO_TICKS;
			plan.bot.followUpReach = 0.3f * rng.nextFloat();
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = BotProfile.DRILL_MIN_DISTANCE;
			plan.bot.maxDistance = BotProfile.DRILL_MAX_DISTANCE;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if''', 1),
    ('''		openerHits = episodeDrill ? fewest + rng.nextInt(Math.max(1, stage.drillHits - fewest + 1)) : 0;''',
     '''		openerHits = episodeDrill ? fewest + rng.nextInt(Math.max(1, stage.drillHits - fewest + 1))
				: episodeKind == KIND_JUMPER ? 1 + rng.nextInt(2) : 0;''', 1),
    ('''	/** Combo drill: follows the hits the copy lands on the held agent; true once it has landed them all. */
	boolean openerLanded() {''', '''	/** Whether the fight about to begin starts with the opponent's combo already on: the agent is held until its first hits have landed. */
	boolean headStart() {
		return openerHits > 0;
	}

	/** Combo drill: follows the hits the copy lands on the held agent; true once it has landed them all. */
	boolean openerLanded() {''', 1),
    ('''		if (episodeDrill && taken > 0) {
			// It starts that many hits into the copy's combo, and knows as much of it as it would in
			// a fight: how long ago the last hit was, and that it has not jumped since.
			rewards.startComboed(stage, taken, drillTally);''', '''		if (headStart() && taken > 0) {
			// It starts that many hits into the opponent's combo, and knows as much of it as it would in
			// a fight: how long ago the last hit was, and that it has not jumped since.
			rewards.startComboed(stage, taken, episodeTally());''', 1),
])
for path in ('src/client/java/dev/swordai/client/TrainingSession.java', 'src/client/java/dev/swordai/client/HeadlessSession.java'):
    edit(path, [
        ('boolean landed = episodeDrill && openerLanded();', 'boolean landed = headStart() && openerLanded();', 1),
        ('if (episodeDrill && !landed) {', 'if (headStart() && !landed) {', 1),
    ])
print('strong ok')
