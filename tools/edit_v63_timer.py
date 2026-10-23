ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	public boolean punish = false;
''', '''	public boolean punish = false;
	/**
	 * It plays on the agent's rhythm, as a player does who has found out that the agent swings the
	 * moment its sword is all the way back and never before. It holds its distance a step outside
	 * the agent's reach and now and then steps into it and out again. Once the agent has swung, it
	 * takes a jump timed so that its critical hit comes down a tick or two before the agent's sword
	 * is back, when the agent has nothing to answer it with; an agent that comes in at it with its
	 * sword back it meets with a jump that has it on its way down as the agent arrives. Hit, it
	 * answers on its way down ({@link #punish}). The friend of the user's who still beat it on
	 * 18 October did this.
	 */
	public boolean timer = false;
''', 1),
    ('''		b.punish = punish;
''', '''		b.punish = punish;
		b.timer = timer;
''', 1),
])

B = 'src/main/java/dev/swordai/server/BotBrain.java'
edit(B, [
    ('''	/** The one that clicks as fast as it can swings with its sword this far back: every three or four ticks. */
	private static final float SPAM_CHARGE = 0.25f;
''', '''	/** The one that clicks as fast as it can swings with its sword this far back: every three or four ticks. */
	private static final float SPAM_CHARGE = 0.25f;
	/**
	 * The one that plays on the agent's rhythm ({@link BotProfile#timer}) holds this far off, eye to
	 * hitbox: a step outside the agent's reach, so that the agent has to come to it. Stepping in to
	 * bait a swing it comes this near, for a few ticks.
	 */
	private static final double TIMER_RANGE = 3.4;
	private static final double TIMER_DIP_RANGE = 2.75;
	private static final int TIMER_DIP_TICKS = 3;
	private static final int TIMER_DIP_EVERY_MIN = 10;
	private static final int TIMER_DIP_EVERY_MORE = 16;
	/** The tick after its last swing on which the agent swings again: with its sword all the way back, as it always has. */
	private static final int TIMER_AGENT_READY = 12;
	/** From its take-off to its swing on the way down, coming into the agent's reach. */
	private static final int TIMER_LEAD_TICKS = 8;
	/** What a jump from a walk carries it in by then; one from a run carries it {@link #RUN_JUMP_COVERS}. */
	private static final double TIMER_WALK_COVERS = 0.8;
	/** It jumps where that leaves the agent this near, eye to hitbox, and not so near that it would come down past it. */
	private static final double TIMER_ENDS_WITHIN = 2.7;
	private static final double TIMER_ENDS_FROM = 0.4;
	/** An agent coming in at this much a tick or more, its sword back, is met with a jump. */
	private static final double TIMER_ARRIVING = 0.08;
''', 1),
    ('''	/** It has just been hit and means to jump with it on its next tick; see {@link BotProfile#jumpResetChance}. */
	private boolean pendingReset;
''', '''	/** It has just been hit and means to jump with it on its next tick; see {@link BotProfile#jumpResetChance}. */
	private boolean pendingReset;
	/** The tick after the agent's last swing on which its next critical hit is to come down; see {@link BotProfile#timer}. */
	private int timerLand = TIMER_AGENT_READY - 1;
	/** How long its sword had been on the way back as it saw it a tick ago. */
	private int agentTicksSeen;
	/** Positive: ticks left of a step into the agent's reach; negative, counting up: till the next. */
	private int dipTicks = -TIMER_DIP_EVERY_MIN;
''', 1),
    ('''		punishTicks = 0;
		punishAir = false;
		if (profile.fleeUntilHit''', '''		punishTicks = 0;
		punishAir = false;
		agentTicksSeen = 0;
		timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
		dipTicks = -(TIMER_DIP_EVERY_MIN + rng.nextInt(TIMER_DIP_EVERY_MORE));
		if (profile.fleeUntilHit''', 1),
    # The agent's sword, as the server has it.
    ('''		AABB agentBox = seenBox.pass(agent.getBoundingBox());
		double reachDist = Math.sqrt(agentBox.distanceToSqr(botEye));
''', '''		AABB agentBox = seenBox.pass(agent.getBoundingBox());
		double reachDist = Math.sqrt(agentBox.distanceToSqr(botEye));
		// How long the agent's sword has been on its way back since its last swing.
		float agentScale = agent.getAttackStrengthScale(0f);
		int agentTicks = agentScale >= 1f ? Integer.MAX_VALUE / 2 : Math.round(agentScale * agent.getCurrentItemAttackStrengthDelay());
		if (profile.timer) {
			if (agentTicks < agentTicksSeen) {
				// The agent has swung: where its next critical hit comes down is drawn afresh, a tick or two
				// before the agent's sword is back.
				timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
			}
			if (dipTicks > 0) {
				if (--dipTicks == 0) {
					dipTicks = -(TIMER_DIP_EVERY_MIN + rng.nextInt(TIMER_DIP_EVERY_MORE));
				}
			} else if (++dipTicks >= 0) {
				dipTicks = TIMER_DIP_TICKS;
			}
		}
		agentTicksSeen = agentTicks;
''', 1),
    # Movement: spacing.
    ('''				} else if (rushing && BotProfile.FIGHT.equals(profile.movement)) {''', '''				} else if (profile.timer && punishTicks <= 0) {
					// A step outside the agent's reach, and now and then a step into it.
					backoffTicks = 0;
					double want = dipTicks > 0 ? TIMER_DIP_RANGE : TIMER_RANGE;
					if (reachDist > want + 0.15) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && reachDist > want + 1.5;
					} else if (reachDist < want - 0.15) {
						mx = -ux;
						mz = -uz;
					}
				} else if (rushing && BotProfile.FIGHT.equals(profile.movement)) {''', 1),
    ('''						sprint = profile.jumpIn && punishTicks <= 0 && profile.sprint && bot.getDeltaMovement().y > 0;''',
     '''						sprint = (profile.jumpIn || profile.timer) && punishTicks <= 0 && profile.sprint && bot.getDeltaMovement().y > 0;''', 1),
    # Jumps.
    ('''		if (critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& (runJump || reachDist <= (profile.holdGround ? HOLD_JUMP_REACH : CRIT_JUMP_REACH))''', '''		if (profile.timer && critPlan && critJump < 0 && punishTicks <= 0 && !fleeing && profile.attacks && bot.onGround()
				&& bot.getAttackStrengthScale(0.5f + TIMER_LEAD_TICKS) >= profile.attackChargeMin) {
			// Where the jump would leave the agent by the time it is coming down into its reach.
			double ends = reachDist - (bot.isSprinting() ? RUN_JUMP_COVERS : TIMER_WALK_COVERS) + TIMER_LEAD_TICKS * agentAway;
			boolean lands = ends <= TIMER_ENDS_WITHIN && ends >= TIMER_ENDS_FROM;
			// Down a tick or two before the agent's sword is back; or, with it back, as the agent arrives.
			boolean onTime = agentTicks < TIMER_AGENT_READY ? agentTicks == timerLand - TIMER_LEAD_TICKS : agentAway <= -TIMER_ARRIVING;
			if (lands && onTime) {
				pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
				critJump = 0;
			}
		}
		if (!profile.timer && critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& (runJump || reachDist <= (profile.holdGround ? HOLD_JUMP_REACH : CRIT_JUMP_REACH))''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float backerShare = 0f;
''', '''	public float backerShare = 0f;
	/**
	 * And against one that plays on its rhythm: it holds a step outside its reach and times its
	 * critical hits to come down just before the fighter's sword is back ({@code BotProfile.timer}).
	 * What the user's friend still beat it with on 18 October.
	 */
	public float timerShare = 0f;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.traderShare = 0.08f;
		// And against one that keeps running out of the fight and one that goes round the fighter
		// making critical hits; see StageSpec.runnerShare. Those are the fights a jump is for.
		st.runnerShare = 0.12f;
		st.circlerShare = 0.1f;''', '''		st.traderShare = 0.07f;
		// And against one that keeps running out of the fight and one that goes round the fighter
		// making critical hits; see StageSpec.runnerShare. Those are the fights a jump is for.
		st.runnerShare = 0.1f;
		st.circlerShare = 0.09f;''', 1),
    ('''		st.cornerShare = 0.07f;
		st.noKnockShare = 0.08f;
		st.oddShare = 0.08f;
		// And the two it lost to on the first evening against people; see StageSpec.jumperShare.
		st.jumperShare = 0.2f;
		st.punisherShare = 0.15f;
		st.backerShare = 0.08f;''', '''		st.cornerShare = 0.06f;
		st.noKnockShare = 0.07f;
		st.oddShare = 0.07f;
		// And the two it lost to on the first evening against people; see StageSpec.jumperShare.
		st.jumperShare = 0.16f;
		st.punisherShare = 0.1f;
		st.backerShare = 0.06f;
		// And the one that plays on its rhythm; see StageSpec.timerShare.
		st.timerShare = 0.14f;''', 1),
])
edit('core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java', [
    ('''		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f && crit.backerShare >= 0.05f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare
				+ crit.jumperShare + crit.punisherShare + crit.backerShare <= 0.97f, "a share is left for the opponent a sprint hit knocks away");''',
     '''		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f && crit.backerShare >= 0.05f && crit.timerShare >= 0.1f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare
				+ crit.jumperShare + crit.punisherShare + crit.backerShare + crit.timerShare <= 0.97f, "a share is left for the opponent a sprint hit knocks away");''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	final Tally backerTally = new Tally();
''', '''	final Tally backerTally = new Tally();
	final Tally timerTally = new Tally();
''', 1),
    ('''	static final int KIND_BACKER = 9;
''', '''	static final int KIND_BACKER = 9;
	/** The one that plays on its rhythm; see {@code BotProfile.timer}. */
	static final int KIND_TIMER = 10;
''', 1),
    ('''					stage.jumperShare, stage.punisherShare, stage.backerShare};''', '''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare};''', 1),
    ('''				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && rng.nextFloat() < stage.randomShare) {''',
     '''				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && episodeKind != KIND_TIMER && rng.nextFloat() < stage.randomShare) {''', 1),
    ('''		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', '''		} else if (episodeKind == KIND_TIMER) {
			// As sharp as the one that comes after it with a running jump, and it knows the fighter's rhythm.
			plan.bot.timer = true;
			plan.bot.punish = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;
			plan.bot.critPress = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = true;
			plan.bot.fleeChance = 0f;
			plan.bot.jumpChance = 0f;
			plan.bot.reactionTicks = rng.nextInt(2);
			plan.bot.aimNoiseDeg = 0.5f + rng.nextFloat();
			plan.bot.turnRateDeg = 60f;
			plan.bot.attackChargeMin = 0.92f;
			plan.bot.preferredRange = 3.4f;
			plan.bot.followUpTicks = 0;
			plan.bot.swingSlip = 0f;
			plan.bot.farChance = 0f;
			plan.bot.minDistance = BotProfile.DRILL_MIN_DISTANCE;
			plan.bot.maxDistance = BotProfile.DRILL_MAX_DISTANCE;
			plan.bot.maxBearingDeg = BotProfile.DRILL_BEARING_DEG;
			plan.bot.minBearingDeg = 0f;
			plan.bot.maxPillar = 0;
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.''', 1),
    ('''		} else if (episodeKind == KIND_PUNISHER) {
			// A server that hands out a part of the knockback: a third to four fifths of it.
			plan.botKnockback = 0.3f + 0.5f * rng.nextFloat();''', '''		} else if (episodeKind == KIND_PUNISHER) {
			// A server that hands out a part of the knockback: a third to four fifths of it.
			plan.botKnockback = 0.3f + 0.5f * rng.nextFloat();
		} else if (episodeKind == KIND_TIMER) {
			// On a server that hands out somewhat less.
			plan.botKnockback = 0.4f + 0.5f * rng.nextFloat();''', 1),
    ('''		if (episodeKind == KIND_BACKER) {
			return backerTally;
		}''', '''		if (episodeKind == KIND_BACKER) {
			return backerTally;
		}
		if (episodeKind == KIND_TIMER) {
			return timerTally;
		}''', 1),
    ('''			pendingTally.add(backerTally);
''', '''			pendingTally.add(backerTally);
			pendingTally.add(timerTally);
''', 1),
    ('''		backerTally.clear();
''', '''		backerTally.clear();
		timerTally.clear();
''', 1),
    ('''| punishers {} | backers {} | swings made by the rules {}''', '''| punishers {} | backers {} | timers {} | swings made by the rules {}''', 1),
    ('''brief(punisherTally), brief(backerTally), forcedAnswers, keptAnswers,''', '''brief(punisherTally), brief(backerTally), brief(timerTally), forcedAnswers, keptAnswers,''', 1),
    ('''jumperTally.punishCrits + punisherTally.punishCrits + backerTally.punishCrits,''', '''jumperTally.punishCrits + punisherTally.punishCrits + backerTally.punishCrits + timerTally.punishCrits,''', 1),
])
print('timer ok')
