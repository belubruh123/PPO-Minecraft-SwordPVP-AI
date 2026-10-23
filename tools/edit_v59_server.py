ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


# ---- Stages -------------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''	private static final int PATH_LAG = 2;
''',
     '''	private static final int PATH_LAG = 3;
''', 1),
    ('''		st.lagStutter = 0.12f;
''',
     '''		st.lagStutter = 0.18f;
		st.slowKnockbackMaxTicks = 12;
''', 2),
    ('''		st.traderShare = 0.25f;
		// And a quarter each against one that keeps running out of the fight and one that goes round
		// the fighter making critical hits; see StageSpec.runnerShare. Those are the fights a jump is
		// for. The rest, a quarter, are against the opponent a sprint hit knocks away, which still
		// swings at a fighter that jumps into its reach.
		st.runnerShare = 0.25f;
		st.circlerShare = 0.25f;
''',
     '''		st.traderShare = 0.12f;
		// And against one that keeps running out of the fight and one that goes round the fighter
		// making critical hits; see StageSpec.runnerShare. Those are the fights a jump is for.
		st.runnerShare = 0.2f;
		st.circlerShare = 0.15f;
		// One that stands in a corner and makes critical hits from there, one that takes next to no
		// knockback and makes critical hits from on top of the fighter, and one that does something
		// odd all fight long; see StageSpec.cornerShare. What is left, not quite a fifth, are against
		// the opponent a sprint hit knocks away, which still swings at a fighter that jumps into its reach.
		st.cornerShare = 0.1f;
		st.noKnockShare = 0.12f;
		st.oddShare = 0.13f;
		// Half the fights against the one that goes round it, and against the one that takes no
		// knockback, begin with it beside the fighter or behind it.
		st.besideShare = 0.5f;
		// And every other scripted opponent is one drawn afresh; see StageSpec.randomShare.
		st.randomShare = 0.5f;
''', 1),
])

# ---- StageSpec ----------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public static final int LAG_STALL_MAX_TICKS = 5;
''',
     '''	public static final int LAG_STALL_MAX_TICKS = 6;
''', 1),
])

# ---- EpisodePlan --------------------------------------------------------------------------------
edit('src/main/java/dev/swordai/server/EpisodePlan.java', [
    ('''	public boolean mixedTurns;
''',
     '''	public boolean mixedTurns;
	/**
	 * The fight begins at a corner of the arena: the opponent stands in it with the walls behind it,
	 * and the agent a few steps out from it, looking more or less its way.
	 */
	public boolean cornerStart;
''', 1),
])

# ---- ArenaServer --------------------------------------------------------------------------------
edit('src/main/java/dev/swordai/server/ArenaServer.java', [
    ('''		float yaw = ViewGeometry.wrapDegrees(agent.getYRot());
		float pitch = (s.rng.nextFloat() * 2f - 1f) * plan.startPitchRangeDeg;
''',
     '''		float yaw = ViewGeometry.wrapDegrees(agent.getYRot());
		double cornerX = 0;
		double cornerZ = 0;
		if (plan.cornerStart) {
			// The opponent in a corner, the agent four to eight blocks out from it and looking its way,
			// give or take forty degrees.
			double inset = s.arena.radius - CORNER_INSET;
			double sx = s.rng.nextBoolean() ? 1 : -1;
			double sz = s.rng.nextBoolean() ? 1 : -1;
			cornerX = s.arena.centerX + sx * inset;
			cornerZ = s.arena.centerZ + sz * inset;
			double out = Math.atan2(-sz, -sx) + Math.toRadians((s.rng.nextDouble() * 2 - 1) * 30.0);
			double far = 4.0 + s.rng.nextDouble() * 4.0;
			ax = cornerX + Math.cos(out) * far;
			az = cornerZ + Math.sin(out) * far;
			ay = s.arena.floorY;
			s.arena.clearPillar(s.agentPillar);
			yaw = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(cornerX - ax, cornerZ - az) + (s.rng.nextFloat() * 2f - 1f) * 40f);
		}
		float pitch = (s.rng.nextFloat() * 2f - 1f) * plan.startPitchRangeDeg;
''', 1),
    ('''		s.placeBotRandomly(ax, az, yaw);
		CombatLog.clear();
''',
     '''		if (plan.cornerStart) {
			s.moveBot(cornerX, cornerZ, 0, ax, az);
		} else {
			s.placeBotRandomly(ax, az, yaw);
		}
		CombatLog.clear();
''', 1),
    ('''			double bearing = (rng.nextDouble() * 2 - 1) * profile.maxBearingDeg;
''',
     '''			// To either side, no nearer straight ahead than the profile says.
			double least0 = Math.max(0f, Math.min(profile.minBearingDeg, profile.maxBearingDeg));
			double bearing = (least0 + rng.nextDouble() * (profile.maxBearingDeg - least0)) * (rng.nextBoolean() ? 1 : -1);
''', 1),
    ('''		brain.setAnchored(raised);
''',
     '''		brain.setAnchored(raised);
		brain.setPost(x, z);
''', 1),
    ('''	private void placeBotRandomly(double ax, double az, float agentYaw) {
''',
     '''	/** How far in from the walls the opponent of a fight that begins at a corner stands. */
	private static final double CORNER_INSET = 1.4;

	private void placeBotRandomly(double ax, double az, float agentYaw) {
''', 1),
])

# ---- BotBrain -----------------------------------------------------------------------------------
edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''	private static final int FLEE_MORE_TICKS = 36;
''',
     '''	private static final int FLEE_MORE_TICKS = 36;
	/** Keeping away for good, it turns and runs once the agent is nearer than this, and looks back at it from farther off than a little more. */
	private static final double KEEP_AWAY_RUN = 7.0;
	private static final double KEEP_AWAY_LOOK = 9.0;
	/** Caught by a hit, it fights for this long before it is off again: two to five seconds. */
	private static final int CAUGHT_MIN_TICKS = 40;
	private static final int CAUGHT_MORE_TICKS = 60;
	/** Holding its ground it jumps for its critical hits from this far off, so that it is coming down as the agent arrives. */
	private static final double HOLD_JUMP_REACH = 3.4;
	/** And walks back to where it was put once it is farther from there than this. */
	private static final double POST_SLACK = 0.7;
	private static final float SPIN_DEG = 36f;
''', 1),
    ('''	private boolean fleeBackTurned;
''',
     '''	private boolean fleeBackTurned;
	/** Caught by a hit while keeping away: ticks left of fighting before it runs again; see {@link BotProfile#fleeUntilHit}. */
	private int caughtTicks;
	/** Where it was last put: the post it holds; see {@link BotProfile#holdGround}. */
	private double postX;
	private double postZ;
	/** Ticks left of what its antic is doing now, and which of its two things that is; see {@link BotProfile#antic}. */
	private int anticTicks;
	private boolean anticOn;
	private int anticDir;
''', 1),
    ('''		fleeTicks = 0;
		if (profile.fleeChance > 0f && rng.nextFloat() < 0.5f * profile.fleeChance) {
			// Some fights it runs from the start.
			startFlee();
		}
		planNextSwing();
	}

	private void startFlee() {
		fleeTicks = FLEE_MIN_TICKS + rng.nextInt(FLEE_MORE_TICKS);
		fleeBackTurned = rng.nextFloat() < profile.fleeTurned;
''',
     '''		fleeTicks = 0;
		caughtTicks = 0;
		anticTicks = 0;
		anticOn = false;
		if (profile.fleeUntilHit || profile.fleeChance > 0f && rng.nextFloat() < 0.5f * profile.fleeChance) {
			// Some fights it runs from the start; the one that keeps away always does.
			startFlee();
		}
		planNextSwing();
	}

	/** Where it has been put: the spot it goes back to if it is one that holds its ground. */
	void setPost(double x, double z) {
		postX = x;
		postZ = z;
	}

	private boolean antic(String name) {
		return name.equals(profile.antic);
	}

	private void startFlee() {
		fleeTicks = profile.fleeUntilHit ? Integer.MAX_VALUE / 2 : FLEE_MIN_TICKS + rng.nextInt(FLEE_MORE_TICKS);
		fleeBackTurned = profile.fleeUntilHit || rng.nextFloat() < profile.fleeTurned;
''', 1),
    ('''		boolean fleeing = !idle && !anchored && fleeTicks > 0 && BotProfile.FIGHT.equals(profile.movement);
''',
     '''		if (profile.fleeUntilHit && fleeTicks <= 0 && !idle && --caughtTicks <= 0) {
			// It has fought for long enough: off again.
			startFlee();
		}
		boolean fleeing = !idle && !anchored && fleeTicks > 0 && BotProfile.FIGHT.equals(profile.movement);
		if (fleeing && profile.fleeUntilHit) {
			// With the agent near it has its back to it and runs; from far off it turns round to look.
			if (flat < KEEP_AWAY_RUN) {
				fleeBackTurned = true;
			} else if (flat > KEEP_AWAY_LOOK) {
				fleeBackTurned = false;
			}
		}
''', 1),
    ('''		if (fleeing && fleeBackTurned) {
			// Its eyes are on where it is going.
			aim(bot, pack, fleeX, 0, fleeZ);
		} else {
''',
     '''		if (fleeing && fleeBackTurned) {
			// Its eyes are on where it is going.
			aim(bot, pack, fleeX, 0, fleeZ);
		} else if (!idle && antic(BotProfile.ANTIC_SPIN)) {
			// Round and round, whatever is in front of it.
			pack.look(bot.getYRot() + SPIN_DEG, 0f);
			bot.setYHeadRot(bot.getYRot());
		} else {
''', 1),
    ('''				double range = critPlan ? Math.min(profile.preferredRange, CRIT_RANGE) : profile.preferredRange;
				if (fleeing) {
					backoffTicks = 0;
					if (fleeBackTurned) {
						mx = fleeX;
						mz = fleeZ;
						sprint = profile.sprint;
					} else {
''',
     '''				double range = critPlan ? Math.min(profile.preferredRange, CRIT_RANGE) : profile.preferredRange;
				if (profile.holdGround) {
					// It stands where it was put, and walks back there if a hit has moved it.
					backoffTicks = 0;
					double px = postX - bot.getX();
					double pz = postZ - bot.getZ();
					if (px * px + pz * pz > POST_SLACK * POST_SLACK && critJump < 0) {
						mx = px;
						mz = pz;
					}
				} else if (antic(BotProfile.ANTIC_STATUE) || antic(BotProfile.ANTIC_ZIGZAG)) {
					backoffTicks = 0;
					if (--anticTicks <= 0) {
						anticOn = !anticOn;
						boolean statue = antic(BotProfile.ANTIC_STATUE);
						// A statue stands for two to five seconds and runs for one and a half to three and a half;
						// the other one goes a new way every few ticks.
						anticTicks = statue ? (anticOn ? 30 + rng.nextInt(40) : 40 + rng.nextInt(60)) : 3 + rng.nextInt(6);
						anticDir = rng.nextInt(5);
					}
					if (antic(BotProfile.ANTIC_STATUE)) {
						if (anticOn) {
							mx = ux;
							mz = uz;
							sprint = profile.sprint;
						}
					} else {
						// Towards the agent, to either side of that, or straight sideways.
						double fwd = anticDir <= 2 ? 1.0 : 0.0;
						double sideways = anticDir == 0 ? 0.0 : anticDir % 2 == 1 ? 1.0 : -1.0;
						if (reachDist < range - 0.6) {
							fwd = -1.0;
						}
						mx = ux * fwd + lx * sideways;
						mz = uz * fwd + lz * sideways;
						sprint = profile.sprint && fwd > 0;
					}
				} else if (fleeing) {
					backoffTicks = 0;
					if (fleeBackTurned) {
						mx = fleeX;
						mz = fleeZ;
						sprint = profile.sprint;
					} else if (profile.fleeUntilHit) {
						// Far enough off: it stands and watches the agent come.
					} else {
''', 1),
    ('''				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0 && !fleeing && !profile.orbit) {
''',
     '''				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0 && !fleeing && !profile.orbit && !profile.holdGround
						&& !antic(BotProfile.ANTIC_STATUE) && !antic(BotProfile.ANTIC_ZIGZAG)) {
''', 1),
    ('''		if (profile.jumpChance > 0f && bot.onGround() && rng.nextFloat() < profile.jumpChance) {
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
		}
''',
     '''		float jumpChance = antic(BotProfile.ANTIC_HOP) ? Math.max(0.5f, profile.jumpChance) : profile.jumpChance;
		if (jumpChance > 0f && bot.onGround() && critJump < 0 && rng.nextFloat() < jumpChance) {
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
		}
		if (antic(BotProfile.ANTIC_DUCK)) {
			// Down for a quarter of a second, up for a quarter of a second.
			pack.setSneaking((bot.tickCount / 5) % 2 == 0);
		} else if (bot.isShiftKeyDown()) {
			pack.setSneaking(false);
		}
''', 1),
    ('''		if (critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround() && reachDist <= CRIT_JUMP_REACH
''',
     '''		if (critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& reachDist <= (profile.holdGround ? HOLD_JUMP_REACH : CRIT_JUMP_REACH)
''', 1),
    ('''					} else if (critJump >= 0) {
''',
     '''					} else if (critJump >= 0 && !profile.holdGround) {
''', 0),
    ('''		if (profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			startFlee();
		}
	}
''',
     '''		if (profile.fleeUntilHit) {
			if (fleeTicks > 0) {
				// Caught: it turns and fights, for a while.
				fleeTicks = 0;
				caughtTicks = CAUGHT_MIN_TICKS + rng.nextInt(CAUGHT_MORE_TICKS);
				planNextSwing();
			}
		} else if (profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			startFlee();
		}
	}
''', 1),
    ('''		if (profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			// Hit and run.
			startFlee();
		}
''',
     '''		if (!profile.fleeUntilHit && profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			// Hit and run.
			startFlee();
		}
''', 1),
    ('''		int needed = (planned ? 0 : profile.reactionTicks) + (jumped ? 0 : slipTicks);
''',
     '''		// One that turns on the spot swings the moment the agent passes under its crosshair, and one
		// that clicks as fast as it can does not wait for anything.
		boolean atOnce = antic(BotProfile.ANTIC_SPIN) || antic(BotProfile.ANTIC_SPAM);
		int needed = atOnce ? 0 : (planned ? 0 : profile.reactionTicks) + (jumped ? 0 : slipTicks);
''', 1),
    ('''		if (bot.getAttackStrengthScale(0.5f) < profile.attackChargeMin) {
			return;
		}
		if (critPlan) {
''',
     '''		if (bot.getAttackStrengthScale(0.5f) < (antic(BotProfile.ANTIC_SPAM) ? SPAM_CHARGE : profile.attackChargeMin)) {
			return;
		}
		if (antic(BotProfile.ANTIC_STATUE) && !anticOn) {
			// Standing like a statue it does not swing either.
			return;
		}
		if (critPlan) {
''', 1),
    ('''	private static final float SPIN_DEG = 36f;
''',
     '''	private static final float SPIN_DEG = 36f;
	/** The one that clicks as fast as it can swings with its sword this far back: every three or four ticks. */
	private static final float SPAM_CHARGE = 0.25f;
''', 1),
])
print('server ok')
