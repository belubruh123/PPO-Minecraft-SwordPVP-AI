ROOT = 'D:/swordai/'


def edit(path, pairs):
    full = ROOT + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


C = 'core/src/main/java/dev/swordai/core/curriculum/'
edit(C + 'BotProfile.java', [
('''	/**
	 * With {@link #holdGround}: it does not walk at all, not even back to where a hit took it from.
	 * It stands, turns to face the agent and swings the moment the agent is in its reach with its
	 * sword back, with nothing late and nothing off. The user, 18 October: a "PERFECT 3 block not
	 * moving" player, "just pure perfect combo".
	 */
	public boolean spacer = false;''', '''	/**
	 * Crit, S, crit, S: it keeps a step outside the agent's reach while its sword comes back, walks
	 * in and jumps from the edge of that reach once it is back, makes its critical hit on the way
	 * down, and backs straight out with S. Never to the side. The user, 18 October: "crit and then
	 * PRESS S like go out of distance and Crit and go out of distance", at a "PERFECT 3 block".
	 */
	public boolean spacer = false;''', 1)])

edit(C + 'StageSpec.java', [
('''	/**
	 * And against one that stands still and swings, perfectly, the moment the fighter is in its
	 * reach with its sword back ({@code BotProfile.spacer}). What beats it is coming straight back
	 * after its hit, while its sword is on the way back: it deals only the knockback of a standing
	 * swing and does not follow.
	 */''', '''	/**
	 * And against one that comes in with a jump for a critical hit and backs out with S straight
	 * after, out of the fighter's reach until its sword is back ({@code BotProfile.spacer}). What
	 * beats it: hitting it out of the air as it comes, and going after it while it walks backwards
	 * with its sword on the way back.
	 */''', 1)])

B = 'src/main/java/dev/swordai/server/BotBrain.java'
edit(B, [
# Undo the standing version's change to the corner bot.
('''					// It stands where it was put, and walks back there if a hit has moved it. One that holds
					// whatever spot it is on (BotProfile.spacer) does not walk at all.
					backoffTicks = 0;
					double px = postX - bot.getX();
					double pz = postZ - bot.getZ();
					if (!profile.spacer && px * px + pz * pz > POST_SLACK * POST_SLACK && critJump < 0) {''', '''					// It stands where it was put, and walks back there if a hit has moved it.
					backoffTicks = 0;
					double px = postX - bot.getX();
					double pz = postZ - bot.getZ();
					if (px * px + pz * pz > POST_SLACK * POST_SLACK && critJump < 0) {''', 1),
('''	/** An agent coming in at this much a tick or more, its sword back, is met with a jump. */
	private static final double TIMER_ARRIVING = 0.1;
''', '''	/** An agent coming in at this much a tick or more, its sword back, is met with a jump. */
	private static final double TIMER_ARRIVING = 0.1;
	/**
	 * Crit, S, crit, S ({@link BotProfile#spacer}): while its sword comes back it holds this far off,
	 * eye to hitbox, a step outside the agent's reach; the jump in is taken from {@link #HOLD_JUMP_REACH}.
	 */
	private static final double SPACER_RANGE = 3.3;
	/** It goes in once its sword will be back this many ticks on, when its jump comes down. */
	private static final int SPACER_LEAD_TICKS = 6;
	/** After its hit it walks backwards for this long at most, or until it is out of the agent's reach. */
	private static final int SPACER_OUT_TICKS = 12;
''', 1),
('''	private int pressTicks;
''', '''	private int pressTicks;
	/** Ticks left of backing straight out after its hit; see {@link BotProfile#spacer}. */
	private int spacerOut;
''', 1),
('''		backoffTicks = 0;
		pressTicks = 0;
		critJump = -1;''', '''		backoffTicks = 0;
		pressTicks = 0;
		spacerOut = 0;
		critJump = -1;''', 1),
('''		wayTicks = 0;
		backoffTicks = 0;
		pressTicks = 0;
		followUpTicks = 0;''', '''		wayTicks = 0;
		backoffTicks = 0;
		pressTicks = 0;
		spacerOut = 0;
		followUpTicks = 0;''', 1),
('''				} else if (pressTicks > 0) {
					// It has just landed a critical hit and stays on top of the agent''', '''				} else if (profile.spacer && punishTicks <= 0) {
					// Crit, S, crit, S. After its hit, straight back out of the agent's reach; while its sword
					// comes back, a step outside that reach; with it about to be back, in (the jump from the
					// edge of the reach is below). Never to the side, never at a sprint near the agent.
					backoffTicks = 0;
					boolean ready = bot.getAttackStrengthScale(0.5f + SPACER_LEAD_TICKS) >= profile.attackChargeMin;
					if (spacerOut > 0 && reachDist < SPACER_RANGE + 0.2) {
						spacerOut--;
						mx = -ux;
						mz = -uz;
					} else {
						spacerOut = 0;
						if (ready || reachDist > SPACER_RANGE + 0.15) {
							mx = ux;
							mz = uz;
							sprint = profile.sprint && reachDist > SPACER_RANGE + 1.5;
						} else if (reachDist < SPACER_RANGE - 0.1) {
							mx = -ux;
							mz = -uz;
						}
					}
				} else if (pressTicks > 0) {
					// It has just landed a critical hit and stays on top of the agent''', 1),
('''				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0 && !fleeing && !profile.orbit && !profile.holdGround
''', '''				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0 && !fleeing && !profile.orbit && !profile.holdGround && !profile.spacer
''', 1),
('''		if (!profile.timer && critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& (runJump || reachDist <= (profile.holdGround ? HOLD_JUMP_REACH : CRIT_JUMP_REACH))
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE) {''', '''		if (!profile.timer && critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& (runJump || reachDist <= (profile.holdGround || profile.spacer ? HOLD_JUMP_REACH : CRIT_JUMP_REACH))
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE
				&& (!profile.spacer || spacerOut <= 0 && bot.getAttackStrengthScale(0.5f + SPACER_LEAD_TICKS) >= profile.attackChargeMin)) {''', 1),
('''		if (crit && !profile.orbit && profile.critPress > 0f && rng.nextFloat() < profile.critPress) {''', '''		if (profile.spacer) {
			spacerOut = SPACER_OUT_TICKS;
		} else if (crit && !profile.orbit && profile.critPress > 0f && rng.nextFloat() < profile.critPress) {''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	/** The one that stands still and hits perfectly at the edge of its reach; see {@code BotProfile.spacer}. */''',
 '''	/** Crit, S, crit, S, at the edge of its reach; see {@code BotProfile.spacer}. */''', 1),
('''			// It stands where it is and never walks, not even back to where a hit took it from; it turns to
			// face the fighter and swings the moment the fighter is in its reach with its sword back, with
			// nothing late and nothing off, and never jumps. The user, 18 October: "PERFECT 3 block not
			// moving", "just pure perfect combo".
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.holdGround = true;
			plan.bot.spacer = true;
			plan.bot.sprint = false;
			plan.bot.critChance = 0f;''', '''			// Crit, S, crit, S: a step outside its reach while its sword comes back, in with a jump once it
			// is back, the critical hit on the way down, and straight back out with S. Nothing late,
			// nothing off, never to the side. The user, 18 October: "crit and then PRESS S like go out of
			// distance and Crit and go out of distance", at a "PERFECT 3 block".
			plan.bot.movement = BotProfile.FIGHT;
			plan.bot.spacer = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;''', 1),
('''			plan.bot.followUpTicks = dev.swordai.core.env.TakenStreak.COMBO_TICKS;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.jumpResetChance = 0.5f * rng.nextFloat();''', '''			plan.bot.followUpTicks = 0;
			plan.bot.followUpReach = 0f;
			plan.bot.followUpWhiff = 0f;
			plan.bot.swingSlip = 0f;
			plan.bot.jumpResetChance = 0.5f * rng.nextFloat();''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
('''		// And the one that stands still and hits perfectly at the edge of its reach; see StageSpec.spacerShare.''',
 '''		// And the one that comes in for a critical hit and backs straight out with S; see StageSpec.spacerShare.''', 1)])
print('spacer2 ok')
