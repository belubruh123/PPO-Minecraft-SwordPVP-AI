ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/client/java/dev/swordai/client/Trainer.java', [
    # ---- fields ----
    ('''	final Tally runnerTally = new Tally();
	final Tally circlerTally = new Tally();
''',
     '''	final Tally runnerTally = new Tally();
	final Tally circlerTally = new Tally();
	/** And against the one in the corner, the one that takes no knockback and the odd ones; see {@code StageSpec.cornerShare}. */
	final Tally cornerTally = new Tally();
	final Tally noKnockTally = new Tally();
	final Tally oddTally = new Tally();
	static final int KIND_PLAIN = 0;
	static final int KIND_TRADER = 1;
	static final int KIND_RUNNER = 2;
	static final int KIND_CIRCLER = 3;
	static final int KIND_CORNER = 4;
	static final int KIND_NO_KNOCK = 5;
	static final int KIND_ODD = 6;
	/** Which kind of scripted opponent the running fight is against. */
	int episodeKind;
	/** The jumps that are made for it; see {@code JumpIn}. */
	private JumpIn jumpIn;
	private int dealtRow;
	private int ruleJumpAtAction;
	/**
	 * What became of each fall it was knocked into by a hit, since the last update: a critical hit
	 * on the way down; a hit that was not one, made first; the opponent never under its crosshair and
	 * in reach on the way down; that, but never with the sword back; anything else.
	 */
	long fallCrits;
	long fallPlain;
	long fallNoAim;
	long fallNoSword;
	long fallOther;
	private boolean fallWatch;
	private boolean fallPick;
	private boolean fallSword;
	private boolean fallHit;
	private boolean fallCrit;
	private int fallTicks;
''', 1),
    # ---- kinds ----
    ('''		float kind = rng.nextFloat();
		episodeTrader = scripted && kind < stage.traderShare;
		episodeRunner = scripted && !episodeTrader && kind < stage.traderShare + stage.runnerShare;
		episodeCircler = scripted && !episodeTrader && !episodeRunner
				&& kind < stage.traderShare + stage.runnerShare + stage.circlerShare;
		if (episodeTrader) {
			plan.bot.rushChance = 1f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, stage.traderReactionTicks);
			plan.bot.dodgeChance = 0f;
		} else if (episodeRunner) {
			plan.bot.fleeChance = stage.runnerFlee;
			plan.bot.rushChance = 0f;
			plan.bot.critChance = 0f;
		} else if (episodeCircler) {
			plan.bot.orbit = true;
			plan.bot.critChance = stage.circlerCrit;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
		}
''',
     '''		float kind = rng.nextFloat();
		episodeKind = KIND_PLAIN;
		if (scripted) {
			float[] shares = {stage.traderShare, stage.runnerShare, stage.circlerShare, stage.cornerShare, stage.noKnockShare, stage.oddShare};
			float upTo = 0f;
			for (int i = 0; i < shares.length; i++) {
				upTo += shares[i];
				if (kind < upTo) {
					episodeKind = i + 1;
					break;
				}
			}
		}
		episodeTrader = episodeKind == KIND_TRADER;
		episodeRunner = episodeKind == KIND_RUNNER;
		episodeCircler = episodeKind == KIND_CIRCLER;
		// Every other scripted opponent is one drawn afresh; see StageSpec.randomShare. Not the ones
		// whose whole point is how they are set up.
		if (scripted && episodeKind != KIND_CIRCLER && episodeKind != KIND_CORNER && episodeKind != KIND_NO_KNOCK
				&& rng.nextFloat() < stage.randomShare) {
			randomise(plan.bot, episodeKind == KIND_PLAIN);
		}
		boolean beside = false;
		if (episodeTrader) {
			plan.bot.rushChance = 1f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, stage.traderReactionTicks);
			plan.bot.dodgeChance = 0f;
		} else if (episodeRunner) {
			plan.bot.fleeChance = stage.runnerFlee;
			plan.bot.rushChance = 0f;
			plan.bot.critChance = 0f;
			// Half of them keep away for good: only a fighter that jumps after them gets to them.
			plan.bot.fleeUntilHit = rng.nextFloat() < stage.runnerKeepsAway;
			plan.bot.sprint = true;
		} else if (episodeCircler) {
			plan.bot.orbit = true;
			plan.bot.critChance = stage.circlerCrit;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			beside = rng.nextFloat() < stage.besideShare;
		} else if (episodeKind == KIND_CORNER) {
			plan.cornerStart = true;
			plan.bot.holdGround = true;
			plan.bot.critChance = 0.9f;
			plan.bot.critPress = 0f;
			plan.bot.rushChance = 0f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 3);
			plan.bot.farChance = 0f;
		} else if (episodeKind == KIND_NO_KNOCK) {
			// On top of the fighter, a critical hit every time its sword is back, and hardly moved by a hit.
			plan.bot.rushChance = 1f;
			plan.bot.critChance = 0.9f;
			plan.bot.critPress = 1f;
			plan.bot.dodgeChance = 0f;
			plan.bot.wtap = false;
			plan.bot.fleeChance = 0f;
			plan.bot.reactionTicks = Math.min(plan.bot.reactionTicks, 2);
			beside = rng.nextFloat() < 0.5f * stage.besideShare;
		} else if (episodeKind == KIND_ODD) {
			plan.bot.antic = BotProfile.ANTICS[rng.nextInt(BotProfile.ANTICS.length)];
			plan.bot.fleeChance = 0f;
			if (BotProfile.ANTIC_SPAM.equals(plan.bot.antic)) {
				plan.bot.rushChance = 1f;
				plan.bot.critChance = 0f;
			}
		}
		if (beside) {
			// Close by and out of the picture: off to a side, or behind.
			plan.bot.minBearingDeg = 70f;
			plan.bot.maxBearingDeg = 180f;
			plan.bot.minDistance = 1.8f;
			plan.bot.maxDistance = 3.0f;
			plan.bot.farChance = 0f;
		}
''', 1),
    ('''		if (episodeTrader || face || episodeCircler) {
			// It takes a part of the hit, drawn per fight, and is back before the sword is.
			plan.botKnockback = stage.traderKnockbackMin + (stage.traderKnockbackMax - stage.traderKnockbackMin) * rng.nextFloat();
		} else if (episodeCritDrill) {
''',
     '''		if (episodeTrader || face || episodeCircler) {
			// It takes a part of the hit, drawn per fight, and is back before the sword is.
			plan.botKnockback = stage.traderKnockbackMin + (stage.traderKnockbackMax - stage.traderKnockbackMin) * rng.nextFloat();
		} else if (episodeKind == KIND_CORNER) {
			// With the walls behind it a hit moves it along them at the most.
			plan.botKnockback = 0.3f + 0.4f * rng.nextFloat();
		} else if (episodeKind == KIND_NO_KNOCK) {
			plan.botKnockback = 0.15f * rng.nextFloat();
		} else if (episodeCritDrill) {
''', 1),
    ('''		hasLastAction = false;
		clicked = false;
		drillJumpOutOfZone = false;
''',
     '''		hasLastAction = false;
		clicked = false;
		dealtRow = 0;
		fallWatch = false;
		if (jumpIn != null) {
			jumpIn.reset();
		}
		drillJumpOutOfZone = false;
''', 1),
    # ---- facts ----
    ('''			facts.tookOff = facts.jumpKey && groundAtAction;
''',
     '''			facts.tookOff = facts.jumpKey && groundAtAction;
			facts.ruleJump = facts.tookOff ? ruleJumpAtAction : 0;
''', 1),
    # ---- decision ----
    ('''		boolean backOut = backRuleNow && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		if (pendingUpdate != null) {
''',
     '''		boolean backOut = backRuleNow && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		watchFall(keys);
		// The jumps that are made for it, and its keys in the air of them; see JumpIn. And the camera
		// is left alone on an opponent that is far away; see FarRest.
		if (facts.hitsTaken > 0) {
			dealtRow = 0;
		}
		dealtRow += facts.hitsDealt;
		int jump = 0;
		ruleJumpAtAction = 0;
		if (!episodeLockedDrill) {
			if (jumpIn == null) {
				jumpIn = new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, rng.nextLong());
			}
			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut, dealtRow);
			keys = JumpIn.keys(keys, jump);
			ruleJumpAtAction = jumpIn.tookOff();
			if (cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec)) {
				keys = FarRest.keys(keys);
			}
		}
		if (pendingUpdate != null) {
''', 1),
    ('''		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		if (groundAtAction) {
''',
     '''		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerAtAction = true;
			forcedAnswers++;
		}
		if (groundAtAction) {
''', 1),
    # ---- tallies ----
    ('''		if (episodeCircler) {
			return circlerTally;
		}
''',
     '''		if (episodeCircler) {
			return circlerTally;
		}
		if (episodeKind == KIND_CORNER) {
			return cornerTally;
		}
		if (episodeKind == KIND_NO_KNOCK) {
			return noKnockTally;
		}
		if (episodeKind == KIND_ODD) {
			return oddTally;
		}
''', 1),
    ('''			pendingTally.add(runnerTally);
			pendingTally.add(circlerTally);
		}
''',
     '''			pendingTally.add(runnerTally);
			pendingTally.add(circlerTally);
			pendingTally.add(cornerTally);
			pendingTally.add(noKnockTally);
			pendingTally.add(oddTally);
		}
''', 1),
    ('''		keepLast(circlerTally, lastCircler);
		circlerTally.clear();
''',
     '''		keepLast(circlerTally, lastCircler);
		circlerTally.clear();
		cornerTally.clear();
		noKnockTally.clear();
		oddTally.clear();
''', 1),
    ('''| runners {} | circlers {} | swings made by the answer rule {} | hits after which the swing was kept for the way down {}, punish crits made {}",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally),
				slowCount.brief(), quickCount.brief(), brief(drillTally), brief(rehearsalTally), brief(runnerTally), brief(circlerTally), forcedAnswers, keptAnswers,
				tally.punishCrits + traderTally.punishCrits + runnerTally.punishCrits + circlerTally.punishCrits + rehearsalTally.punishCrits
						+ punishDrillTally.punishCrits + freeDrillTally.punishCrits + drillTally.punishCrits);
		forcedAnswers = 0;
		keptAnswers = 0;
''',
     '''| runners {} | circlers {} | corner {} | no knockback {} | odd {} | swings made by the rules {} | hits after which the swing was kept for the way down {}, punish crits made {} | falls after a hit: crit {}, a plain hit first {}, opponent never under the crosshair {}, sword never back {}, other {}",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally),
				slowCount.brief(), quickCount.brief(), brief(drillTally), brief(rehearsalTally), brief(runnerTally), brief(circlerTally),
				brief(cornerTally), brief(noKnockTally), brief(oddTally), forcedAnswers, keptAnswers,
				tally.punishCrits + traderTally.punishCrits + runnerTally.punishCrits + circlerTally.punishCrits + rehearsalTally.punishCrits
						+ punishDrillTally.punishCrits + freeDrillTally.punishCrits + drillTally.punishCrits
						+ cornerTally.punishCrits + noKnockTally.punishCrits + oddTally.punishCrits,
				fallCrits, fallPlain, fallNoAim, fallNoSword, fallOther);
		forcedAnswers = 0;
		keptAnswers = 0;
		fallCrits = 0;
		fallPlain = 0;
		fallNoAim = 0;
		fallNoSword = 0;
		fallOther = 0;
''', 1),
    ('''S held %d/%d, chase jumps %d of which a crit %d",
''',
     '''S held %d/%d, chase jumps %d of which a crit %d, "
						+ "rule jumps: chase %d (crit %d) face %d (crit %d) upper %d (hit %d) hit in the air %d, uppercuts %d, far: ticks %d still %d flips %d turned %d",
''', 1),
    ('''				t.combosOver, t.combosUnanswered, t.combosSatThrough, t.pushHeld, t.pushTicks, t.chaseJumps, t.chaseCrits);
''',
     '''				t.combosOver, t.combosUnanswered, t.combosSatThrough, t.pushHeld, t.pushTicks, t.chaseJumps, t.chaseCrits,
				t.chaseRuleJumps, t.chaseRuleCrits, t.faceRuleJumps, t.faceRuleCrits, t.upperRuleJumps, t.upperRuleHits, t.ruleJumpsHit,
				t.uppercuts, t.farTicks, t.farStillTicks, t.farFlips, t.farTurnCentiDeg / 100);
''', 1),
    # ---- helpers ----
    ('''	/** Whether the body's own game has the opponent under the crosshair and within reach right now; see {@code AnswerRule}. */
	boolean crosshairOnOpponent() {
		return false;
	}
''',
     '''	/** Whether the body's own game has the opponent under the crosshair and within reach right now; see {@code AnswerRule}. */
	boolean crosshairOnOpponent() {
		return false;
	}

	/**
	 * Follows each fall it is knocked into by a hit, from the hit to the landing or to its own next
	 * hit, and counts what became of it; see {@link #fallCrits}. That is what says why a punish crit
	 * was not made when one was not.
	 *
	 * @param keys the keys that are the network's on this tick
	 */
	private void watchFall(int keys) {
		if (fallWatch) {
			if (facts.hitsDealt > 0) {
				fallHit = true;
				fallCrit = facts.critDealt;
			}
			if (!groundAtAction && facts.falling) {
				fallTicks++;
				fallPick |= crosshairOnOpponent();
				fallSword |= crosshairOnOpponent() && bodyCharge() >= swingMinCharge();
			}
			if (fallHit || facts.hitsTaken > 0 || groundAtAction && fallTicks > 0) {
				if (fallCrit) {
					fallCrits++;
				} else if (fallHit) {
					fallPlain++;
				} else if (fallTicks > 0) {
					if (!fallPick) {
						fallNoAim++;
					} else if (!fallSword) {
						fallNoSword++;
					} else {
						fallOther++;
					}
				}
				fallWatch = false;
			}
		}
		if (facts.hitsTaken > 0 && !episodeLockedDrill) {
			fallWatch = true;
			fallPick = false;
			fallSword = false;
			fallHit = false;
			fallCrit = false;
			fallTicks = 0;
		}
	}

	/**
	 * A scripted opponent drawn afresh: how quick it is, how well it aims, how near it likes to be,
	 * how often it jumps, backs off, is late with a swing. See {@code StageSpec.randomShare}.
	 *
	 * @param whole also how it attacks: whether it comes in for critical hits, stays on top of them, or keeps pushing in
	 */
	private void randomise(BotProfile b, boolean whole) {
		b.reactionTicks = 2 + rng.nextInt(8);
		b.aimNoiseDeg = 1.5f + 6f * rng.nextFloat();
		b.turnRateDeg = 20f + 25f * rng.nextFloat();
		b.preferredRange = 2.2f + 0.8f * rng.nextFloat();
		b.jumpChance = rng.nextFloat() < 0.3f ? 0.04f * rng.nextFloat() : 0f;
		b.wtap = rng.nextBoolean();
		b.dodgeChance = 0.4f * rng.nextFloat();
		b.swingSlip = 0.5f * rng.nextFloat();
		b.attackChargeMin = 0.85f + 0.15f * rng.nextFloat();
		if (whole) {
			b.critChance = rng.nextBoolean() ? 0.8f * rng.nextFloat() : 0f;
			b.critPress = rng.nextFloat();
			b.rushChance = rng.nextFloat() < 0.3f ? 1f : 0f;
		}
	}
''', 1),
])
s = open(ROOT + 'src/client/java/dev/swordai/client/Trainer.java', encoding='utf-8').read()
for imp in ('import dev.swordai.core.rl.JumpIn;', 'import dev.swordai.core.rl.FarRest;'):
    if imp not in s:
        s = s.replace('import dev.swordai.core.rl.AnswerRule;', 'import dev.swordai.core.rl.AnswerRule;\n' + imp, 1)
open(ROOT + 'src/client/java/dev/swordai/client/Trainer.java', 'w', encoding='utf-8', newline='').write(s)
print('trainer ok')
