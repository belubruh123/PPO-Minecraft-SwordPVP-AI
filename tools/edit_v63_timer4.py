ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


B = 'src/main/java/dev/swordai/server/BotBrain.java'
edit(B, [
    ('''final class BotBrain {
''', '''public final class BotBrain {
	/**
	 * For the training log, from the one that plays on the agent's rhythm ({@link BotProfile#timer}):
	 * jumps timed for the agent's sword, jumps that met the agent arriving, critical hits that came
	 * of each, and how many of either jump the agent hit it out of. Read and cleared by the trainer,
	 * which in a world of one's own runs in the same game.
	 */
	public static final java.util.concurrent.atomic.AtomicIntegerArray TIMER_COUNTS = new java.util.concurrent.atomic.AtomicIntegerArray(6);
	public static final int TIMER_GAP_JUMPS = 0;
	public static final int TIMER_ARRIVE_JUMPS = 1;
	public static final int TIMER_GAP_CRITS = 2;
	public static final int TIMER_ARRIVE_CRITS = 3;
	public static final int TIMER_HIT_IN_JUMP = 4;
	public static final int TIMER_PUNISH_CRITS = 5;
''', 1),
    ('''	/** How long its sword had been on the way back as it saw it a tick ago. */
	private int agentTicksSeen;
''', '''	/** How long its sword had been on the way back as it saw it a tick ago. */
	private int agentTicksSeen;
	/** Which of its timed jumps it is in, as a {@code TIMER_} count: the gap, the arrival; or -1. */
	private int timerJump = -1;
''', 1),
    ('''			if (onTime) {
				pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
				critJump = 0;
			}''', '''			if (onTime) {
				pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
				critJump = 0;
				timerJump = agentTicks < TIMER_AGENT_READY ? TIMER_GAP_JUMPS : TIMER_ARRIVE_JUMPS;
				TIMER_COUNTS.incrementAndGet(timerJump);
			}''', 1),
    ('''		} else if (critJump >= 0 && ++critJump > 2 && bot.onGround()) {
			// Down again. If the swing was not made, the plan stands and it jumps again.
			critJump = -1;
		}''', '''		} else if (critJump >= 0 && ++critJump > 2 && bot.onGround()) {
			// Down again. If the swing was not made, the plan stands and it jumps again.
			critJump = -1;
			timerJump = -1;
		}''', 1),
    ('''		boolean crit = critSwing;
		bot.attack(agent);''', '''		boolean crit = critSwing;
		if (profile.timer && crit) {
			TIMER_COUNTS.incrementAndGet(punishTicks > 0 ? TIMER_PUNISH_CRITS : timerJump == TIMER_ARRIVE_JUMPS ? TIMER_ARRIVE_CRITS : TIMER_GAP_CRITS);
		}
		timerJump = -1;
		bot.attack(agent);''', 1),
    ('''	void tookHit() {
		pendingReset = profile.jumpResetChance > 0f && rng.nextFloat() < profile.jumpResetChance;''', '''	void tookHit() {
		pendingReset = profile.jumpResetChance > 0f && rng.nextFloat() < profile.jumpResetChance;
		if (timerJump >= 0) {
			TIMER_COUNTS.incrementAndGet(TIMER_HIT_IN_JUMP);
			timerJump = -1;
		}''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''		} else {
			plan.agentLag = stage.seenLagTicks > 0 ? rng.nextInt(stage.seenLagTicks + 1) : 0;
			plan.botLag = stage.lagTicks > 0 ? rng.nextInt(stage.lagTicks + 1) : 0;
			plan.botPing = stage.hitLagTicks > 0 ? rng.nextInt(stage.hitLagTicks + 1) : 0;
		}''', '''		} else {
			plan.agentLag = stage.seenLagTicks > 0 ? rng.nextInt(stage.seenLagTicks + 1) : 0;
			plan.botLag = stage.lagTicks > 0 ? rng.nextInt(stage.lagTicks + 1) : 0;
			plan.botPing = stage.hitLagTicks > 0 ? rng.nextInt(stage.hitLagTicks + 1) : 0;
		}
		if (episodeKind == KIND_TIMER) {
			// The user's connection and his friend's: 50 to 100 ms each way. What it sees of the opponent
			// is two or three ticks old, and its hit moves the opponent three to five ticks after it lands.
			episodeSlow = false;
			plan.botPing = 3 + rng.nextInt(3);
			plan.agentLag = 2 + rng.nextInt(2);
			plan.botLag = plan.agentLag;
		}''', 1),
    ('''| five blocks off or more on the ground on {} ticks: jumped {}, not going at it {}, hit lately {}, to the side {}, sword not back {}, left alone {}, coming in {}",''',
     '''| five blocks off or more on the ground on {} ticks: jumped {}, not going at it {}, hit lately {}, to the side {}, sword not back {}, left alone {}, coming in {} | timer: jumps for the sword {} (crits {}), jumps at it arriving {} (crits {}), hit out of either {}, punish crits {}",''', 1),
    ('''				java.util.Arrays.stream(hopWhy).sum(), hopWhy[0], hopWhy[1], hopWhy[2], hopWhy[3], hopWhy[4], hopWhy[5], hopWhy[6]);''',
     '''				java.util.Arrays.stream(hopWhy).sum(), hopWhy[0], hopWhy[1], hopWhy[2], hopWhy[3], hopWhy[4], hopWhy[5], hopWhy[6],
				timerCount(dev.swordai.server.BotBrain.TIMER_GAP_JUMPS), timerCount(dev.swordai.server.BotBrain.TIMER_GAP_CRITS),
				timerCount(dev.swordai.server.BotBrain.TIMER_ARRIVE_JUMPS), timerCount(dev.swordai.server.BotBrain.TIMER_ARRIVE_CRITS),
				timerCount(dev.swordai.server.BotBrain.TIMER_HIT_IN_JUMP), timerCount(dev.swordai.server.BotBrain.TIMER_PUNISH_CRITS));''', 1),
    ('''	float swingMinCharge() {
		return cfg.swingMinCharge;
	}
''', '''	float swingMinCharge() {
		return cfg.swingMinCharge;
	}

	/** One of the counts of the scripted opponent that plays on its rhythm, cleared as it is read. */
	private static int timerCount(int which) {
		return dev.swordai.server.BotBrain.TIMER_COUNTS.getAndSet(which, 0);
	}
''', 1),
])
print('timer4 ok')
