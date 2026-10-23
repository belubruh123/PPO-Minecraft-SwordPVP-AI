ROOT = 'D:/swordai/'


def edit(path, pairs):
    full = ROOT + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
('''	/**
	 * Crit, S, crit, S: it keeps a step outside the agent's reach while its sword comes back, walks
	 * in and jumps from the edge of that reach once it is back, makes its critical hit on the way
	 * down, and backs straight out with S. Never to the side. The user, 18 October: "crit and then
	 * PRESS S like go out of distance and Crit and go out of distance", at a "PERFECT 3 block".
	 */''', '''	/**
	 * The player at the edge of the reach, in two ways, as {@link #critChance} has it swing by swing.
	 * For a critical hit: it keeps a step outside the agent's reach while its sword comes back, walks
	 * in and jumps from the edge of that reach once it is back, makes its critical hit on the way
	 * down, and backs straight out with S. Otherwise it stands where it is and hits the agent the
	 * moment it comes into reach. Never to the side, nothing late, nothing off. The user, 18 October:
	 * "crit and then PRESS S like go out of distance and Crit and go out of distance", and "PERFECT
	 * 3 block not moving", "just pure perfect combo"; the player is "mix of both".
	 */''', 1)])

edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
('''	/**
	 * And against one that comes in with a jump for a critical hit and backs out with S straight
	 * after, out of the fighter's reach until its sword is back ({@code BotProfile.spacer}). What
	 * beats it: hitting it out of the air as it comes, and going after it while it walks backwards
	 * with its sword on the way back.
	 */''', '''	/**
	 * And against the player at the edge of the reach ({@code BotProfile.spacer}): one that comes in
	 * with a jump for a critical hit and backs out with S straight after, one that stands three blocks
	 * off and hits whatever comes into its reach, perfectly, and one that does both. A third of the
	 * fights each.
	 */''', 1)])

B = 'src/main/java/dev/swordai/server/BotBrain.java'
edit(B, [
('''				} else if (profile.spacer && punishTicks <= 0) {
					// Crit, S, crit, S. After its hit, straight back out of the agent's reach; while its sword
					// comes back, a step outside that reach; with it about to be back, in (the jump from the
					// edge of the reach is below). Never to the side, never at a sprint near the agent.
					backoffTicks = 0;
					boolean ready = bot.getAttackStrengthScale(0.5f + SPACER_LEAD_TICKS) >= profile.attackChargeMin;
					if (spacerOut > 0 && reachDist < SPACER_RANGE + 0.2) {''', '''				} else if (profile.spacer && punishTicks <= 0 && !critPlan && spacerOut <= 0) {
					// Standing at the edge of the reach: it does not move, and hits whatever comes into its
					// reach (in tryAttack). Only an agent far off is walked up to.
					backoffTicks = 0;
					if (reachDist > SPACER_RANGE + 2.0) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && reachDist > SPACER_RANGE + 3.0;
					}
				} else if (profile.spacer && punishTicks <= 0) {
					// Crit, S, crit, S. After its hit, straight back out of the agent's reach; while its sword
					// comes back, a step outside that reach; with it about to be back, in (the jump from the
					// edge of the reach is below). Never to the side, never at a sprint near the agent.
					backoffTicks = 0;
					boolean ready = bot.getAttackStrengthScale(0.5f + SPACER_LEAD_TICKS) >= profile.attackChargeMin;
					if (spacerOut > 0 && reachDist < SPACER_RANGE + 0.2) {''', 1),
('''		if (profile.spacer) {
			spacerOut = SPACER_OUT_TICKS;
		} else if''', '''		if (profile.spacer) {
			// Out again after a critical hit; one that stands stays standing.
			spacerOut = crit ? SPACER_OUT_TICKS : 0;
		} else if''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	final Tally spacerTally = new Tally();
''', '''	final Tally spacerTally = new Tally();
	/** The fights against the one at the edge of the reach that only stands and hits; see {@link #KIND_SPACER}. */
	final Tally standerTally = new Tally();
	/** The running fight is against that one. */
	private boolean episodeStander;
''', 1),
('''		episodeTrader = episodeKind == KIND_TRADER;
''', '''		episodeTrader = episodeKind == KIND_TRADER;
		episodeStander = false;
''', 1),
('''			plan.bot.spacer = true;
			plan.bot.sprint = true;
			plan.bot.critChance = 1f;''', '''			plan.bot.spacer = true;
			plan.bot.sprint = true;
			// A third of them stand and hit, a third come in for a critical hit and back out every time,
			// and a third do either, swing by swing.
			int style = rng.nextInt(3);
			episodeStander = style == 0;
			plan.bot.critChance = style == 0 ? 0f : style == 1 ? 1f : 0.3f + 0.4f * rng.nextFloat();''', 1),
('''		if (episodeKind == KIND_SPACER) {
			return spacerTally;
		}''', '''		if (episodeKind == KIND_SPACER) {
			return episodeStander ? standerTally : spacerTally;
		}''', 1),
('''			pendingTally.add(spacerTally);
''', '''			pendingTally.add(spacerTally);
			pendingTally.add(standerTally);
''', 1),
('''		spacerTally.clear();
''', '''		spacerTally.clear();
		standerTally.clear();
''', 1),
('''| spacers {} | swings made''', '''| spacers {} | standers {} | swings made''', 1),
('''brief(spacerTally), forcedAnswers''', '''brief(spacerTally), brief(standerTally), forcedAnswers''', 1),
])
print('spacer3 ok')
