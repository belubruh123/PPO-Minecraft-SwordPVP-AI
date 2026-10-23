ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''	private static final float SPIN_DEG = 36f;
''', '''	private static final float SPIN_DEG = 36f;
	/**
	 * A running jump for a critical hit ({@link BotProfile#jumpIn}): by the tick it is well on its way
	 * down, the ninth, it has covered this much ground, and it jumps from where that and what the
	 * agent will have moved by then leave the agent this near.
	 */
	private static final int RUN_JUMP_TICKS = 9;
	private static final double RUN_JUMP_COVERS = 2.8;
	private static final double RUN_JUMP_ENDS_WITHIN = 2.6;
	private static final double RUN_JUMP_FARTHEST = 5.6;
	/** It is not jumped at an agent that is itself coming in faster than this: that one is in reach on the ground. */
	private static final double RUN_JUMP_NOT_AT = -0.1;
	/** Hit, it means its next swing for its way down for this long; see {@link BotProfile#punish}. */
	private static final int PUNISH_TICKS = 16;
''', 1),
    ('''	private int anticTicks;
	private boolean anticOn;
	private int anticDir;
''', '''	private int anticTicks;
	private boolean anticOn;
	private int anticDir;
	/** Where it saw the agent a tick ago, and how fast the agent is going away from it by that; see {@link BotProfile#jumpIn}. */
	private double seenX = Double.NaN;
	private double seenZ;
	private double agentAway;
	/** Ticks left of meaning its swing for its own way down from the agent's hit; see {@link BotProfile#punish}. */
	private int punishTicks;
	private boolean punishAir;
''', 1),
    ('''		anticTicks = 0;
		anticOn = false;
		if (profile.fleeUntilHit''', '''		anticTicks = 0;
		anticOn = false;
		seenX = Double.NaN;
		agentAway = 0;
		punishTicks = 0;
		punishAir = false;
		if (profile.fleeUntilHit''', 1),
    ('''		critPlan = (followUpTicks <= 0 || profile.orbit) && profile.critChance > 0f && rng.nextFloat() < profile.critChance;''',
     '''		critPlan = (followUpTicks <= 0 || profile.orbit || profile.jumpIn) && profile.critChance > 0f && rng.nextFloat() < profile.critChance;''', 1),
    ('''		double flat = Math.sqrt(dx * dx + dz * dz);

		// Running away: towards somewhere in the arena that is away from the agent.''', '''		double flat = Math.sqrt(dx * dx + dz * dz);
		// How fast the agent is going away from it, as it sees it.
		agentAway = Double.isNaN(seenX) || flat < 1e-6 ? 0 : ((agentEye.x - seenX) * dx + (agentEye.z - seenZ) * dz) / flat;
		seenX = agentEye.x;
		seenZ = agentEye.z;

		// Running away: towards somewhere in the arena that is away from the agent.''', 1),
    ('''					backoffTicks = 0;
					if (reachDist > CRIT_CLOSE) {
						mx = ux;
						mz = uz;
					}
				} else if (pressTicks > 0) {''', '''					backoffTicks = 0;
					if (reachDist > CRIT_CLOSE) {
						mx = ux;
						mz = uz;
						// A running jump is run up to its top; the sprint is off for the way down.
						sprint = profile.jumpIn && punishTicks <= 0 && profile.sprint && bot.getDeltaMovement().y > 0;
					}
				} else if (pressTicks > 0) {''', 1),
    ('''					mx = ux;
					mz = uz;
					sprint = profile.sprint && !critPlan;
				} else if (backoffTicks > 0) {''', '''					mx = ux;
					mz = uz;
					sprint = profile.sprint && (!critPlan || profile.jumpIn);
				} else if (backoffTicks > 0) {''', 1),
    ('''				} else if (reachDist > range + 0.15) {
					mx = ux;
					mz = uz;
					sprint = profile.sprint && !critPlan;
				} else if (reachDist < range - 0.6) {''', '''				} else if (reachDist > range + 0.15) {
					mx = ux;
					mz = uz;
					sprint = profile.sprint && (!critPlan || profile.jumpIn);
				} else if (reachDist < range - 0.6) {''', 1),
    ('''		if (critJump >= 0 && ++critJump > 2 && bot.onGround()) {
			// Down again. If the swing was not made, the plan stands and it jumps again.
			critJump = -1;
		}
		if (critPlan && ++critPlanTicks > CRIT_PATIENCE_TICKS) {
			critPlan = false;
		}
		if (critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& reachDist <= (profile.holdGround ? HOLD_JUMP_REACH : CRIT_JUMP_REACH)
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE) {
			// From a walk, never from a run: a running jump would carry it past the agent, and the sprint
			// has to be off by the time it swings.
			pack.setSprinting(false);
			bot.setSprinting(false);
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
			critJump = 0;
		}
''', '''		if (punishTicks > 0) {
			// Thrown up by the agent's hit: the way down is where its swing is meant for. Once it is
			// down again, or was never thrown up at all, that is over.
			punishAir |= !bot.onGround();
			if (--punishTicks == 0 || punishAir && bot.onGround()) {
				punishTicks = 0;
				critJump = -1;
				planNextSwing();
			}
		} else if (critJump >= 0 && ++critJump > 2 && bot.onGround()) {
			// Down again. If the swing was not made, the plan stands and it jumps again.
			critJump = -1;
		}
		if (critPlan && punishTicks <= 0 && ++critPlanTicks > CRIT_PATIENCE_TICKS) {
			critPlan = false;
		}
		if (profile.jumpIn && critPlan && critJump < 0 && agentAway < RUN_JUMP_NOT_AT && reachDist <= bot.entityInteractionRange() + 0.3) {
			// The agent is coming in itself and is all but in reach: this swing is an ordinary one.
			critPlan = false;
		}
		// A running jump is taken from where its way down will bring the agent into reach.
		boolean runJump = profile.jumpIn && agentAway >= RUN_JUMP_NOT_AT
				&& reachDist <= Math.min(RUN_JUMP_FARTHEST, RUN_JUMP_ENDS_WITHIN + RUN_JUMP_COVERS - RUN_JUMP_TICKS * Math.max(0, agentAway));
		if (critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& (runJump || reachDist <= (profile.holdGround ? HOLD_JUMP_REACH : CRIT_JUMP_REACH))
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE) {
			if (!runJump) {
				// From a walk, never from a run: a running jump would carry it past the agent, and the sprint
				// has to be off by the time it swings.
				pack.setSprinting(false);
				bot.setSprinting(false);
			}
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
			critJump = 0;
		}
''', 1),
    ('''		followUpTicks = profile.followUpTicks;
		if (followUpTicks > 0 && !profile.orbit) {
			critPlan = false;
		}''', '''		followUpTicks = profile.followUpTicks;
		if (followUpTicks > 0 && !profile.orbit && !profile.jumpIn) {
			critPlan = false;
		}''', 1),
    ('''	void tookHit() {
		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = BACKOFF_TICKS;
		}''', '''	void tookHit() {
		if (profile.punish && profile.attacks && fleeTicks <= 0) {
			// Its next swing is for its own way down from this hit, and a critical one. As with a jump
			// of its own: it walks in on the agent meanwhile, and the swing waits for nothing.
			punishTicks = PUNISH_TICKS;
			punishAir = false;
			critPlan = true;
			critPlanTicks = 0;
			critJump = 0;
			backoffTicks = 0;
			return;
		}
		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = BACKOFF_TICKS;
		}''', 1),
    ('''		boolean crit = critPlan;
		bot.attack(agent);
		// Swinging is what resets the cooldown on the server, exactly as for a real player's packet.
		bot.swing(InteractionHand.MAIN_HAND);
		critJump = -1;
''', '''		boolean crit = critPlan;
		bot.attack(agent);
		// Swinging is what resets the cooldown on the server, exactly as for a real player's packet.
		bot.swing(InteractionHand.MAIN_HAND);
		critJump = -1;
		punishTicks = 0;
''', 1),
])
print('bot ok')
