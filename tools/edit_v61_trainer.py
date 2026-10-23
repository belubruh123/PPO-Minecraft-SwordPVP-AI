ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''	private static final int PUNISH_TICKS = 16;
''', '''	private static final int PUNISH_TICKS = 34;
	/** Not thrown up by then (and that long again for a knockback that is late), it never will be. */
	private static final int PUNISH_LIFT_TICKS = 4;
''', 1),
    ('''	private int punishTicks;
	private boolean punishAir;
''', '''	private int punishTicks;
	private boolean punishAir;
	private int lagTicks;
''', 1),
    ('''	void setLag(int ticks) {
		seenEye.setTicks(ticks);''', '''	void setLag(int ticks) {
		lagTicks = Math.max(0, ticks);
		seenEye.setTicks(ticks);''', 1),
    ('''			punishAir |= !bot.onGround();
			if (--punishTicks == 0 || punishAir && bot.onGround()) {''', '''			punishAir |= !bot.onGround();
			punishTicks--;
			boolean never = !punishAir && punishTicks < PUNISH_TICKS - (2 * lagTicks + PUNISH_LIFT_TICKS);
			if (punishTicks <= 0 || never || punishAir && bot.onGround()) {''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	static final int KIND_ODD = 6;
''', '''	static final int KIND_ODD = 6;
	/** The one that comes after it with a running jump and a critical hit; see {@code BotProfile.jumpIn}. */
	static final int KIND_JUMPER = 7;
	/** The one that takes part of the knockback and answers its hits with a critical hit on the way down; see {@code BotProfile.punish}. */
	static final int KIND_PUNISHER = 8;
''', 1),
    ('''	final Tally oddTally = new Tally();
''', '''	final Tally oddTally = new Tally();
	final Tally jumperTally = new Tally();
	final Tally punisherTally = new Tally();
	/** The jump made for it on a hit of a combo it is taking; see {@link JumpReset}. */
	private JumpReset jumpReset;
	/** The step back after a hit of its own that has not sent the opponent away; see {@link StepBack}. */
	private final StepBack stepBack = new StepBack();
	private boolean stepBackAtAction;
''', 1),
    ('''			facts.ruleJump = facts.tookOff ? ruleJumpAtAction : 0;
''', '''			facts.ruleJump = facts.tookOff ? ruleJumpAtAction : 0;
			facts.stepBack = stepBackAtAction;
''', 1),
    ('''		// In the ticks after a hit of a combo it is taking, S is held for it; see Config.backRule.
		boolean backOut = backRuleNow && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		watchFall(keys);''', '''		// A hit of a combo it is taking: it jumps with it, W and the sprint key down; see JumpReset.
		int reset = 0;
		if (!episodeLockedDrill && backRuleNow && cfg.jumpResetChance > 0f && stage.unlocked(ActionSpace.JUMP)) {
			if (jumpReset == null) {
				jumpReset = new JumpReset(cfg.jumpResetChance, rng.nextLong());
			}
			reset = jumpReset.plan(obs.vec, keys);
		}
		// Where it does not, S is held for it in the ticks after that hit; see Config.backRule.
		boolean backOut = backRuleNow && reset == 0 && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		keys = JumpIn.keys(keys, reset);
		// After a hit of its own that has not sent the opponent away it steps back; see StepBack.
		int step = cfg.stepBackRule && !episodeLockedDrill && stage.unlocked(ActionSpace.JUMP) ? stepBack.plan(obs.vec) : 0;
		watchFall(keys);''', 1),
    ('''			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut, dealtRow);
			keys = JumpIn.keys(keys, jump);
			ruleJumpAtAction = jumpIn.tookOff();
		}
''', '''			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0, dealtRow);
			keys = JumpIn.keys(keys, jump);
			ruleJumpAtAction = jumpIn.tookOff();
		}
		if (jumpReset != null && jumpReset.tookOff()) {
			ruleJumpAtAction = JumpReset.KIND;
		}
		if (owes || backOut || reset != 0 || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE
				|| !ActionSpace.unlocked(keys, ActionSpace.FORWARD) || !ActionSpace.unlocked(keys, ActionSpace.SPRINT)) {
			step = 0;
		}
		if (step != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		stepBackAtAction = step != 0;
''', 1),
    ('''		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerAtAction = true;
			forcedAnswers++;
		}''', '''		JumpIn.apply(action, reset);
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerAtAction = true;
			forcedAnswers++;
		}''', 1),
    ('''			float[] shares = {stage.traderShare, stage.runnerShare, stage.circlerShare, stage.cornerShare, stage.noKnockShare, stage.oddShare};''',
     '''			float[] shares = {stage.traderShare, stage.runnerShare, stage.circlerShare, stage.cornerShare, stage.noKnockShare, stage.oddShare,
					stage.jumperShare, stage.punisherShare};''', 1),
    ('''		if (scripted && episodeKind != KIND_CIRCLER && episodeKind != KIND_CORNER && episodeKind != KIND_NO_KNOCK
				&& rng.nextFloat() < stage.randomShare) {''', '''		if (scripted && episodeKind != KIND_CIRCLER && episodeKind != KIND_CORNER && episodeKind != KIND_NO_KNOCK
				&& episodeKind != KIND_JUMPER && episodeKind != KIND_PUNISHER && rng.nextFloat() < stage.randomShare) {''', 1),
    ('''		} else if (episodeKind == KIND_ODD) {
			plan.bot.antic = BotProfile.ANTICS[rng.nextInt(BotProfile.ANTICS.length)];
			plan.bot.fleeChance = 0f;
			if (BotProfile.ANTIC_SPAM.equals(plan.bot.antic)) {
				plan.bot.rushChance = 1f;
				plan.bot.critChance = 0f;
			}
		}
''', '''		} else if (episodeKind == KIND_ODD) {
			plan.bot.antic = BotProfile.ANTICS[rng.nextInt(BotProfile.ANTICS.length)];
			plan.bot.fleeChance = 0f;
			if (BotProfile.ANTIC_SPAM.equals(plan.bot.antic)) {
				plan.bot.rushChance = 1f;
				plan.bot.critChance = 0f;
			}
		} else if (episodeKind == KIND_JUMPER) {
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
		} else if (episodeKind == KIND_PUNISHER) {
			// It fights as the stage's opponent does, and every hit it takes it answers on its way down.
			plan.bot.punish = true;
			plan.bot.fleeChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 2 + rng.nextInt(4));
		}
''', 1),
    ('''		} else if (episodeKind == KIND_NO_KNOCK) {
			plan.botKnockback = 0.15f * rng.nextFloat();
		} else if (episodeCritDrill) {''', '''		} else if (episodeKind == KIND_NO_KNOCK) {
			plan.botKnockback = 0.15f * rng.nextFloat();
		} else if (episodeKind == KIND_PUNISHER) {
			// A server that hands out a part of the knockback: a third to four fifths of it.
			plan.botKnockback = 0.3f + 0.5f * rng.nextFloat();
		} else if (episodeCritDrill) {''', 1),
    ('''		punishMemory.reset();
		drillJumpOutOfZone = false;''', '''		punishMemory.reset();
		stepBack.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		drillJumpOutOfZone = false;''', 1),
    ('''		punishMemory.reset();
		episodeSlow = false;
		critDrillKind = -1;''', '''		punishMemory.reset();
		stepBack.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		episodeSlow = false;
		critDrillKind = -1;''', 1),
    ('''		if (episodeKind == KIND_ODD) {
			return oddTally;
		}
''', '''		if (episodeKind == KIND_ODD) {
			return oddTally;
		}
		if (episodeKind == KIND_JUMPER) {
			return jumperTally;
		}
		if (episodeKind == KIND_PUNISHER) {
			return punisherTally;
		}
''', 1),
    ('''			pendingTally.add(oddTally);
		}''', '''			pendingTally.add(oddTally);
			pendingTally.add(jumperTally);
			pendingTally.add(punisherTally);
		}''', 1),
    ('''		oddTally.clear();
		if (slowCount.steps > 0) {''', '''		oddTally.clear();
		jumperTally.clear();
		punisherTally.clear();
		if (slowCount.steps > 0) {''', 1),
    ('''| corner {} | no knockback {} | odd {} | swings made by the rules {}''', '''| corner {} | no knockback {} | odd {} | jump critters {} | punishers {} | swings made by the rules {}''', 1),
    ('''				brief(cornerTally), brief(noKnockTally), brief(oddTally), forcedAnswers, keptAnswers,''',
     '''				brief(cornerTally), brief(noKnockTally), brief(oddTally), brief(jumperTally), brief(punisherTally), forcedAnswers, keptAnswers,''', 1),
    ('''						+ cornerTally.punishCrits + noKnockTally.punishCrits + oddTally.punishCrits,''',
     '''						+ cornerTally.punishCrits + noKnockTally.punishCrits + oddTally.punishCrits + jumperTally.punishCrits + punisherTally.punishCrits,''', 1),
])
print('trainer ok')
