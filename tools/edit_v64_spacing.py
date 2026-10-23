ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	/** Ticks with the opponent five blocks off or more, by what kept it from a running jump; see {@link JumpIn#hopWhy}. */''', '''	/**
	 * Spacing: on its feet, the opponent in view and within six blocks, no hit either way for six
	 * ticks, the distance kept (as the critic measures it) with the opponent's sword back and with it
	 * on its way back; see {@code ObsSpec.SC_OPP_CHARGE}.
	 */
	private long spaceReadyTicks;
	private double spaceReadySum;
	private long spaceCoolTicks;
	private double spaceCoolSum;
	/** Ticks with the opponent five blocks off or more, by what kept it from a running jump; see {@link JumpIn#hopWhy}. */''', 1),
    ('''		if (stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill) {
			meetHow[MeetJump.how(obs.vec)]++;
		}''', '''		if (stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill) {
			meetHow[MeetJump.how(obs.vec)]++;
		}
		if (groundAtAction && facts.enemyVisible && facts.reachDist < 6f && clock.ticksSinceHitTaken >= 6 && clock.ticksSinceHitDealt >= 6) {
			if (obs.vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_OPP_CHARGE] >= 0.9f) {
				spaceReadyTicks++;
				spaceReadySum += facts.reachDist;
			} else {
				spaceCoolTicks++;
				spaceCoolSum += facts.reachDist;
			}
		}''', 1),
    ('''| timer: jumps for the sword {} (crits {}), jumps at it arriving {} (crits {}), hit out of either {}, punish crits {}",''',
     '''| timer: jumps for the sword {} (crits {}), jumps at it arriving {} (crits {}), hit out of either {}, punish crits {} | distance kept: opponent's sword back {} over {} ticks, on its way back {} over {} ticks",''', 1),
    ('''				timerCount(dev.swordai.server.BotBrain.TIMER_HIT_IN_JUMP), timerCount(dev.swordai.server.BotBrain.TIMER_PUNISH_CRITS));''',
     '''				timerCount(dev.swordai.server.BotBrain.TIMER_HIT_IN_JUMP), timerCount(dev.swordai.server.BotBrain.TIMER_PUNISH_CRITS),
				String.format(Locale.ROOT, "%.2f", spaceReadyTicks == 0 ? 0.0 : spaceReadySum / spaceReadyTicks), spaceReadyTicks,
				String.format(Locale.ROOT, "%.2f", spaceCoolTicks == 0 ? 0.0 : spaceCoolSum / spaceCoolTicks), spaceCoolTicks);
		spaceReadyTicks = 0;
		spaceReadySum = 0;
		spaceCoolTicks = 0;
		spaceCoolSum = 0;''', 1),
])
print('spacing ok')
