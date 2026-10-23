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
    ('''	/**
	 * The one that plays on the agent's rhythm ({@link BotProfile#timer}) holds this far off, eye to
	 * hitbox: a step outside the agent's reach, so that the agent has to come to it. Stepping in to
	 * bait a swing it comes this near, for a few ticks.
	 */
	private static final double TIMER_RANGE = 3.4;
	private static final double TIMER_DIP_RANGE = 2.75;
	private static final int TIMER_DIP_TICKS = 3;
	private static final int TIMER_DIP_EVERY_MIN = 10;
	private static final int TIMER_DIP_EVERY_MORE = 16;''', '''	/**
	 * The one that plays on the agent's rhythm ({@link BotProfile#timer}) holds this far off, eye to
	 * hitbox, while the agent's sword is on its way back: a step outside the agent's reach. With the
	 * agent's sword back it keeps farther off, so that the agent has to run in to it.
	 */
	private static final double TIMER_RANGE = 3.4;
	private static final double TIMER_FAR = 5.4;
	/** The agent's reach, eye to hitbox. */
	private static final double TIMER_AGENT_REACH = 3.0;
	/**
	 * An agent running in at it with its sword back it meets with a jump taken this many ticks before
	 * the agent will be in reach: it is on its way down as the agent arrives, and the agent's sprint
	 * hit and its critical hit come at once.
	 */
	private static final int TIMER_ARRIVE_MIN = 6;
	private static final int TIMER_ARRIVE_MAX = 8;''', 1),
    ('''	/** An agent coming in at this much a tick or more, its sword back, is met with a jump. */
	private static final double TIMER_ARRIVING = 0.08;''', '''	/** An agent coming in at this much a tick or more, its sword back, is met with a jump. */
	private static final double TIMER_ARRIVING = 0.1;''', 1),
    ('''	/** Positive: ticks left of a step into the agent's reach; negative, counting up: till the next. */
	private int dipTicks = -TIMER_DIP_EVERY_MIN;
''', '', 1),
    ('''		timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
		dipTicks = -(TIMER_DIP_EVERY_MIN + rng.nextInt(TIMER_DIP_EVERY_MORE));
		if (profile.fleeUntilHit''', '''		timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
		if (profile.fleeUntilHit''', 1),
    ('''				timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
			}
			if (dipTicks > 0) {
				if (--dipTicks == 0) {
					dipTicks = -(TIMER_DIP_EVERY_MIN + rng.nextInt(TIMER_DIP_EVERY_MORE));
				}
			} else if (++dipTicks >= 0) {
				dipTicks = TIMER_DIP_TICKS;
			}
		}''', '''				timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
			}
		}''', 1),
    ('''					// A step outside the agent's reach, and now and then a step into it.
					backoffTicks = 0;
					double want = dipTicks > 0 ? TIMER_DIP_RANGE : TIMER_RANGE;''', '''					// A step outside the agent's reach while its sword is on the way back; farther with it back.
					backoffTicks = 0;
					double want = agentTicks < TIMER_AGENT_READY ? TIMER_RANGE : TIMER_FAR;''', 1),
    ('''			// Down a tick or two before the agent's sword is back; or, with it back, as the agent arrives.
			boolean onTime = agentTicks < TIMER_AGENT_READY ? agentTicks == timerLand - TIMER_LEAD_TICKS : agentAway <= -TIMER_ARRIVING;
			if (lands && onTime) {''', '''			// With its sword back, the agent running in is met so that the jump is on its way down as the
			// agent comes into reach.
			double ownIn = bot.getDeltaMovement().x * ux + bot.getDeltaMovement().z * uz;
			double closing = -agentAway + Math.max(0.1, ownIn);
			double toReach = (reachDist - TIMER_AGENT_REACH) / Math.max(0.05, closing);
			boolean arrives = agentAway <= -TIMER_ARRIVING && toReach >= TIMER_ARRIVE_MIN && toReach <= TIMER_ARRIVE_MAX;
			// Down a tick or two before the agent's sword is back; or, with it back, as the agent arrives.
			boolean onTime = agentTicks < TIMER_AGENT_READY ? lands && agentTicks == timerLand - TIMER_LEAD_TICKS : arrives;
			if (onTime) {''', 1),
])
print('timer3 ok')
