package dev.swordai.core.curriculum;

/** Counters over a stretch of play; ratios of these are the metrics that drive promotion and reports. */
public final class Tally {
	public long steps;
	public long visibleTicks;
	public long onHeadTicks;
	public long onTargetTicks;
	/** Ticks on the opponent during which the crosshair did not slide across it. */
	public long stillTicks;
	public double aimErrorSum;
	public long inRangeTicks;
	/** Unbroken stretches on the opponent that have ended. */
	public long holds;
	/** Times the opponent was found again after being off screen, and the ticks that took in total. */
	public long reacquires;
	public long reacquireTicks;
	/** Times the opponent stayed off screen until the episode was restarted. */
	public long lostEpisodes;

	public long clicks;
	public long clicksOnTarget;
	/** Swings made before the weapon was back to 60% charge. */
	public long spamClicks;
	public long hitsDealt;
	public long fullChargeHits;
	public long sprintHits;
	public long headHits;
	/** Landed hits that were critical hits, and how many of those were punish crits. */
	public long critHits;
	public long punishCrits;
	/**
	 * Hits taken that left a punish crit there to be made: knocked up by the hit, it came down with
	 * the weapon back and the opponent under its crosshair and in reach.
	 */
	public long punishChances;
	/** Jumps of its own that have ended, and how many of them ended in a critical hit. */
	public long jumpsEnded;
	public long jumpCrits;
	/** Ticks it stood on the ground with the opponent out of a sword's reach, and how many of them it took off on. */
	public long farGroundTicks;
	public long farJumps;
	/** Swings made in the air, and how many of them reached nothing. */
	public long airSwings;
	public long airWhiffs;
	/** Times the hits of a combo that were not critical added up to another critical hit's worth of damage. */
	public long comboCritWorths;
	/** Critical hits that followed a sprint hit in the same combo, and sprint hits that followed a critical hit: the two taken in turn. */
	public long critFollows;
	public long sprintFollows;
	/** Landed hits that were uppercuts. */
	public long uppercuts;
	/** Landed hits that were sweeps (slow, no sprint knockback). */
	public long sweepHits;
	public long hitsTaken;
	/** Hits taken that were critical hits. */
	public long critsTaken;
	/** Hits landed with the opponent very close, and how many of those had a sprint behind them. */
	public long closeHits;
	public long closeSprintHits;
	/** Times the opponent got a combo going on the agent, and how many of those a hit back ended. */
	public long combosTaken;
	public long comboBreaks;
	/** Times such a combo grew long enough that getting away is the thing to do, and how often it got away and then came back with a hit. */
	public long longCombosTaken;
	public long comboEscapes;
	/**
	 * Hits that left the agent in the opponent's combo (the second in a row, and every one after), and
	 * how many of those were followed by another: after the rest it got out.
	 */
	public long comboedHits;
	public long comboedHitsFollowed;
	/**
	 * The opponent's combos that got to two hits in a row with the agent free to do something about
	 * the next, and how many of those got a third. A combo it is handed three hits into (a drill)
	 * is in neither.
	 */
	public long twoHitCombos;
	public long threeHitCombos;
	/**
	 * The opponent's combos on the agent (two hits in a row or more) that are over, and how many of
	 * them it sat through without one swing of its own from the first hit to the end. That number is
	 * meant to be nought.
	 */
	public long combosOver;
	public long combosUnanswered;
	/**
	 * Of those, the ones that went to a third hit or beyond. Two hits and then out of it without a
	 * swing is the combo got out of; three and never a swing is the one sat through.
	 */
	public long combosSatThrough;
	/** Jumps made at an opponent it was running after and not catching ({@code RewardWeights.chaseCrit}), and how many ended in a critical hit. */
	public long chaseJumps;
	public long chaseCrits;
	/**
	 * Jumps a rule made for it (see {@code JumpIn}), by kind, and what came of them: the chase and the
	 * crit in its face by how many ended in a critical hit, the uppercut by how many hit on the way up;
	 * and in how many of all of them it was hit before it was down again.
	 */
	public long chaseRuleJumps;
	public long chaseRuleCrits;
	/** Crit-back jumps ({@code JumpIn.COUNTER}) and the critical hits they ended in. */
	public long counterRuleJumps;
	public long counterRuleCrits;
	public long faceRuleJumps;
	public long faceRuleCrits;
	public long upperRuleJumps;
	public long upperRuleHits;
	public long ruleJumpsHit;
	/** Of the chase jumps a rule made, how many were running jumps from five blocks off or more ({@code JumpIn.HOP}). */
	public long hopRuleJumps;
	/** Jumps made with a hit of a combo it was taking; see {@code JumpReset}. */
	public long resetJumps;
	/** Ticks on which it was stepping back after a hit of its own; see {@code StepBack}. */
	public long stepBackTicks;
	/** Hits taken four to thirteen ticks after a hit of its own: its sword on the way back and nothing to answer with. */
	public long counterHits;
	/**
	 * Ticks with the opponent in view and far away (see {@code RewardModel.FAR_AIM_FROM}); on how many
	 * of them the camera did not move; how often it changed the direction it was turning in; and how
	 * far it turned in all, in hundredths of a degree.
	 */
	public long farTicks;
	public long farStillTicks;
	public long farFlips;
	public long farTurnCentiDeg;
	/**
	 * What became of each fall it was knocked into by a hit: a critical hit on the way down; a hit
	 * that was not one, made first; the opponent in reach on the way down but never under its
	 * crosshair; that, but never with the sword back; anything else; and the opponent never in reach.
	 */
	public long fallCrits;
	public long fallPlain;
	public long fallNoAim;
	public long fallNoSword;
	public long fallOther;
	public long fallFar;
	/** The ticks in which backing out is asked of it (see {@code BackNudge}), and how many of them it held S. */
	public long pushTicks;
	public long pushHeld;
	/** Ticks just after such a hit, with the opponent still near, and how many of them it held S. */
	public long backTicks;
	public long backHeld;
	/** Times it left the ground by its own jump. */
	public long jumps;
	/** Ticks right after a swing, on the ground with the opponent on screen, and how many of them it pressed jump. */
	public long jumpWindowTicks;
	public long jumpWindowJumps;
	/** Hits taken that could be answered with a swing, and how many were, in time. */
	public long hitsToAnswer;
	public long answers;
	public double damageDealt;
	public double damageTaken;
	/**
	 * Rounds of a life each (20 health): every time 20 has been dealt or taken since the last round
	 * ended, the round goes to whoever dealt more in it. The opponents in training cannot die, so this
	 * is what says how often it would have won; see {@code RewardModel.ROUND_HEALTH}.
	 */
	public long roundsWon;
	public long roundsLost;

	/** Combos that have ended, and the total hits in them. */
	public long combos;
	public long comboHits;
	/** Landed hits whose follow-up window has closed, and how many of those were W-tapped. */
	public long tapWindows;
	public long tapsDone;
	/** Ticks with the weapon recharging, and how many of them the camera did not move. */
	public long rechargeTicks;
	public long rechargeCalmTicks;
	/** Ticks with the weapon (nearly) ready, and how many of them the crosshair was on the opponent. */
	public long readyTicks;
	public long readyOnTargetTicks;
	/** Ticks with a strafe plan and the opponent in view, and how many of them followed it. */
	public long mixTicks;
	public long mixFollowed;
	/** Swings made under a plan, and how many of them waited as long as it said. */
	public long plannedClicks;
	public long timedClicks;

	public long episodes;
	public long wins;
	public long losses;
	/** Fights that ran out of time with nobody knocked out. */
	public long draws;
	/** Self-play: fights won and lost against the copies from before the current one. */
	public long oldWins;
	public long oldLosses;
	public double rewardSum;

	public void add(Tally t) {
		steps += t.steps;
		visibleTicks += t.visibleTicks;
		onHeadTicks += t.onHeadTicks;
		onTargetTicks += t.onTargetTicks;
		stillTicks += t.stillTicks;
		aimErrorSum += t.aimErrorSum;
		inRangeTicks += t.inRangeTicks;
		holds += t.holds;
		reacquires += t.reacquires;
		reacquireTicks += t.reacquireTicks;
		lostEpisodes += t.lostEpisodes;
		clicks += t.clicks;
		clicksOnTarget += t.clicksOnTarget;
		spamClicks += t.spamClicks;
		hitsDealt += t.hitsDealt;
		fullChargeHits += t.fullChargeHits;
		sprintHits += t.sprintHits;
		headHits += t.headHits;
		critHits += t.critHits;
		punishCrits += t.punishCrits;
		punishChances += t.punishChances;
		jumpsEnded += t.jumpsEnded;
		jumpCrits += t.jumpCrits;
		farGroundTicks += t.farGroundTicks;
		farJumps += t.farJumps;
		airSwings += t.airSwings;
		airWhiffs += t.airWhiffs;
		comboCritWorths += t.comboCritWorths;
		critFollows += t.critFollows;
		sprintFollows += t.sprintFollows;
		uppercuts += t.uppercuts;
		sweepHits += t.sweepHits;
		critsTaken += t.critsTaken;
		closeHits += t.closeHits;
		closeSprintHits += t.closeSprintHits;
		combosTaken += t.combosTaken;
		comboBreaks += t.comboBreaks;
		comboEscapes += t.comboEscapes;
		longCombosTaken += t.longCombosTaken;
		comboedHits += t.comboedHits;
		comboedHitsFollowed += t.comboedHitsFollowed;
		twoHitCombos += t.twoHitCombos;
		threeHitCombos += t.threeHitCombos;
		combosOver += t.combosOver;
		combosUnanswered += t.combosUnanswered;
		combosSatThrough += t.combosSatThrough;
		chaseJumps += t.chaseJumps;
		chaseCrits += t.chaseCrits;
		chaseRuleJumps += t.chaseRuleJumps;
		chaseRuleCrits += t.chaseRuleCrits;
		counterRuleJumps += t.counterRuleJumps;
		counterRuleCrits += t.counterRuleCrits;
		faceRuleJumps += t.faceRuleJumps;
		faceRuleCrits += t.faceRuleCrits;
		upperRuleJumps += t.upperRuleJumps;
		upperRuleHits += t.upperRuleHits;
		hopRuleJumps += t.hopRuleJumps;
		ruleJumpsHit += t.ruleJumpsHit;
		resetJumps += t.resetJumps;
		stepBackTicks += t.stepBackTicks;
		counterHits += t.counterHits;
		farTicks += t.farTicks;
		farStillTicks += t.farStillTicks;
		farFlips += t.farFlips;
		farTurnCentiDeg += t.farTurnCentiDeg;
		fallCrits += t.fallCrits;
		fallPlain += t.fallPlain;
		fallNoAim += t.fallNoAim;
		fallNoSword += t.fallNoSword;
		fallOther += t.fallOther;
		fallFar += t.fallFar;
		pushTicks += t.pushTicks;
		pushHeld += t.pushHeld;
		backTicks += t.backTicks;
		backHeld += t.backHeld;
		hitsToAnswer += t.hitsToAnswer;
		answers += t.answers;
		jumps += t.jumps;
		jumpWindowTicks += t.jumpWindowTicks;
		jumpWindowJumps += t.jumpWindowJumps;
		hitsTaken += t.hitsTaken;
		damageDealt += t.damageDealt;
		damageTaken += t.damageTaken;
		roundsWon += t.roundsWon;
		roundsLost += t.roundsLost;
		combos += t.combos;
		comboHits += t.comboHits;
		tapWindows += t.tapWindows;
		tapsDone += t.tapsDone;
		rechargeTicks += t.rechargeTicks;
		rechargeCalmTicks += t.rechargeCalmTicks;
		readyTicks += t.readyTicks;
		readyOnTargetTicks += t.readyOnTargetTicks;
		mixTicks += t.mixTicks;
		mixFollowed += t.mixFollowed;
		plannedClicks += t.plannedClicks;
		timedClicks += t.timedClicks;
		episodes += t.episodes;
		wins += t.wins;
		draws += t.draws;
		oldWins += t.oldWins;
		oldLosses += t.oldLosses;
		losses += t.losses;
		rewardSum += t.rewardSum;
	}

	/**
	 * Adds only how {@code t} went as far as the opponent's combos are concerned. The combo drills are
	 * kept apart from the stage's own fights, which they would make look worse than they are, but they
	 * are where most of the combos on the agent happen.
	 */
	public void addCombosTaken(Tally t) {
		combosTaken += t.combosTaken;
		comboBreaks += t.comboBreaks;
		longCombosTaken += t.longCombosTaken;
		comboEscapes += t.comboEscapes;
		comboedHits += t.comboedHits;
		comboedHitsFollowed += t.comboedHitsFollowed;
		twoHitCombos += t.twoHitCombos;
		threeHitCombos += t.threeHitCombos;
		combosOver += t.combosOver;
		combosUnanswered += t.combosUnanswered;
		combosSatThrough += t.combosSatThrough;
		chaseJumps += t.chaseJumps;
		chaseCrits += t.chaseCrits;
		chaseRuleJumps += t.chaseRuleJumps;
		chaseRuleCrits += t.chaseRuleCrits;
		counterRuleJumps += t.counterRuleJumps;
		counterRuleCrits += t.counterRuleCrits;
		faceRuleJumps += t.faceRuleJumps;
		faceRuleCrits += t.faceRuleCrits;
		upperRuleJumps += t.upperRuleJumps;
		upperRuleHits += t.upperRuleHits;
		hopRuleJumps += t.hopRuleJumps;
		ruleJumpsHit += t.ruleJumpsHit;
		resetJumps += t.resetJumps;
		stepBackTicks += t.stepBackTicks;
		counterHits += t.counterHits;
		farTicks += t.farTicks;
		farStillTicks += t.farStillTicks;
		farFlips += t.farFlips;
		farTurnCentiDeg += t.farTurnCentiDeg;
		fallCrits += t.fallCrits;
		fallPlain += t.fallPlain;
		fallNoAim += t.fallNoAim;
		fallNoSword += t.fallNoSword;
		fallOther += t.fallOther;
		fallFar += t.fallFar;
		pushTicks += t.pushTicks;
		pushHeld += t.pushHeld;
		backTicks += t.backTicks;
		backHeld += t.backHeld;
		hitsToAnswer += t.hitsToAnswer;
		answers += t.answers;
	}

	public void clear() {
		steps = 0;
		visibleTicks = 0;
		onHeadTicks = 0;
		onTargetTicks = 0;
		stillTicks = 0;
		aimErrorSum = 0;
		inRangeTicks = 0;
		holds = 0;
		reacquires = 0;
		reacquireTicks = 0;
		lostEpisodes = 0;
		clicks = 0;
		clicksOnTarget = 0;
		spamClicks = 0;
		hitsDealt = 0;
		fullChargeHits = 0;
		sprintHits = 0;
		headHits = 0;
		critHits = 0;
		punishCrits = 0;
		punishChances = 0;
		jumpsEnded = 0;
		jumpCrits = 0;
		farGroundTicks = 0;
		farJumps = 0;
		airSwings = 0;
		airWhiffs = 0;
		comboCritWorths = 0;
		critFollows = 0;
		sprintFollows = 0;
		uppercuts = 0;
		sweepHits = 0;
		critsTaken = 0;
		closeHits = 0;
		closeSprintHits = 0;
		combosTaken = 0;
		comboBreaks = 0;
		comboEscapes = 0;
		longCombosTaken = 0;
		comboedHits = 0;
		comboedHitsFollowed = 0;
		twoHitCombos = 0;
		threeHitCombos = 0;
		combosOver = 0;
		combosUnanswered = 0;
		combosSatThrough = 0;
		chaseJumps = 0;
		chaseCrits = 0;
		chaseRuleJumps = 0;
		chaseRuleCrits = 0;
		counterRuleJumps = 0;
		counterRuleCrits = 0;
		faceRuleJumps = 0;
		faceRuleCrits = 0;
		upperRuleJumps = 0;
		upperRuleHits = 0;
		hopRuleJumps = 0;
		ruleJumpsHit = 0;
		resetJumps = 0;
		stepBackTicks = 0;
		counterHits = 0;
		farTicks = 0;
		farStillTicks = 0;
		farFlips = 0;
		farTurnCentiDeg = 0;
		fallCrits = 0;
		fallPlain = 0;
		fallNoAim = 0;
		fallNoSword = 0;
		fallOther = 0;
		fallFar = 0;
		pushTicks = 0;
		pushHeld = 0;
		backTicks = 0;
		backHeld = 0;
		jumps = 0;
		jumpWindowTicks = 0;
		jumpWindowJumps = 0;
		hitsToAnswer = 0;
		answers = 0;
		hitsTaken = 0;
		damageDealt = 0;
		damageTaken = 0;
		roundsWon = 0;
		roundsLost = 0;
		combos = 0;
		comboHits = 0;
		tapWindows = 0;
		tapsDone = 0;
		rechargeTicks = 0;
		rechargeCalmTicks = 0;
		readyTicks = 0;
		readyOnTargetTicks = 0;
		mixTicks = 0;
		mixFollowed = 0;
		plannedClicks = 0;
		timedClicks = 0;
		episodes = 0;
		wins = 0;
		draws = 0;
		oldWins = 0;
		oldLosses = 0;
		losses = 0;
		rewardSum = 0;
	}

	private static float ratio(double a, double b) {
		return b <= 0 ? 0f : (float) (a / b);
	}

	public float onHead() {
		return ratio(onHeadTicks, steps);
	}

	public float onTarget() {
		return ratio(onTargetTicks, steps);
	}

	public float visible() {
		return ratio(visibleTicks, steps);
	}

	/** Mean distance of the crosshair from the opponent's hitbox, degrees; zero while on it. */
	public float aimError() {
		return ratio(aimErrorSum, steps);
	}

	/** Mean length, in ticks, of an unbroken stretch on the opponent. Twenty ticks is one second. */
	public float meanHold() {
		return ratio(onTargetTicks, Math.max(1, holds));
	}

	/** Share of the time on the opponent that the crosshair rested there instead of sliding about. */
	public float still() {
		return ratio(stillTicks, onTargetTicks);
	}

	/** Mean ticks from losing sight of the opponent to seeing it again. */
	public float meanReacquire() {
		return ratio(reacquireTicks, reacquires);
	}

	/** Share of look-arounds that failed: the opponent was never found before the restart. */
	public float lostRate() {
		return ratio(lostEpisodes, lostEpisodes + reacquires);
	}

	public float inRange() {
		return ratio(inRangeTicks, steps);
	}

	public float clickAccuracy() {
		return ratio(clicksOnTarget, clicks);
	}

	/** Share of swings made before the weapon had recovered. */
	public float spamRate() {
		return ratio(spamClicks, clicks);
	}

	public float fullCharge() {
		return ratio(fullChargeHits, hitsDealt);
	}

	/** Twenty decisions a second. */
	public float hitsPerMinute() {
		return ratio(hitsDealt * 1200.0, steps);
	}

	public float sprintHitFraction() {
		return ratio(sprintHits, hitsDealt);
	}

	/** Share of landed hits that were sweeps. */
	public float sweepShare() {
		return ratio(sweepHits, hitsDealt);
	}

	/** Share of landed hits that were uppercuts. */
	public float uppercutShare() {
		return ratio(uppercuts, hitsDealt);
	}

	/** Share of landed hits that were critical hits. */
	public float critShare() {
		return ratio(critHits, hitsDealt);
	}

	/**
	 * Punish crits landed per chance it had of one (capped at one): of the hits that knocked it up and
	 * left the opponent in its reach as it came down, how many it answered with a critical hit. (Up to
	 * 0.12.3 this was counted per critical hit taken, whether or not anything could be done about it.)
	 */
	public float punishRate() {
		return Math.min(1f, ratio(punishCrits, punishChances));
	}

	/** Share of the hits it took that left it a punish crit to make. */
	public float punishChanceRate() {
		return Math.min(1f, ratio(punishChances, hitsTaken));
	}

	/** Share of its jumps that ended in a critical hit; the rest came to nothing. */
	public float jumpCritRate() {
		return ratio(jumpCrits, jumpsEnded);
	}

	/** Share of the ticks it stood with the opponent out of reach on which it jumped all the same. */
	public float farJumpRate() {
		return ratio(farJumps, farGroundTicks);
	}

	/** Share of its swings in the air that reached nothing. */
	public float airWhiffRate() {
		return ratio(airWhiffs, airSwings);
	}

	/** Share of the hits landed from very close that were sprint-knockback hits. */
	public float closeSprint() {
		return ratio(closeSprintHits, closeHits);
	}

	/** Share of the opponent's combos that the agent ended by hitting back. */
	public float breakRate() {
		return Math.min(1f, ratio(comboBreaks, combosTaken));
	}

	/** Share of the hits taken that it answered with a swing in time, hit or miss. */
	public float answerRate() {
		return Math.min(1f, ratio(answers, hitsToAnswer));
	}

	/** Share of the opponent's combos that the agent backed out of and then answered with a charged hit of its own. */
	public float escapeRate() {
		return Math.min(1f, ratio(comboEscapes, longCombosTaken));
	}

	/**
	 * Share of the hits that left it in the opponent's combo after which it got out: no further hit
	 * followed, whether because it backed away or because it hit back first.
	 */
	public float outRate() {
		return comboedHits <= 0 ? 0f : 1f - Math.min(1f, ratio(comboedHitsFollowed, comboedHits));
	}

	/**
	 * Share of the opponent's combos that stopped at two hits: it was out, by a hit of its own or by
	 * getting clear, before a third landed.
	 */
	public float outByTwoRate() {
		return twoHitCombos <= 0 ? 0f : 1f - Math.min(1f, ratio(threeHitCombos, twoHitCombos));
	}

	/** Share of the ticks just after a hit of the opponent's combo, with the opponent still near, that it held S. */
	public float backRate() {
		return ratio(backHeld, backTicks);
	}

	/** The share of the ticks in which backing out is asked of it that it held S. */
	public float pushRate() {
		return ratio(pushHeld, pushTicks);
	}

	/** Twenty decisions a second. */
	public float jumpsPerMinute() {
		return ratio(jumps * 1200.0, steps);
	}

	/** Share of the ticks right after a swing, on the ground, that it pressed jump. */
	public float jumpWindowRate() {
		return ratio(jumpWindowJumps, jumpWindowTicks);
	}

	public float headHitFraction() {
		return ratio(headHits, hitsDealt);
	}

	public float meanCombo() {
		return ratio(comboHits, combos);
	}

	public float tapRate() {
		return ratio(tapsDone, tapWindows);
	}

	/** Share of the time the weapon was recharging that the camera stayed where it was. */
	public float rechargeCalm() {
		return ratio(rechargeCalmTicks, rechargeTicks);
	}

	/** Share of the time the weapon was ready that the crosshair was on the opponent. */
	public float readyOnTarget() {
		return ratio(readyOnTargetTicks, readyTicks);
	}

	/** Share of the time with a strafe plan that the agent strafed the way it said. */
	public float mixStrafe() {
		return ratio(mixFollowed, mixTicks);
	}

	/** Share of swings that waited as long as the plan said. */
	public float mixTiming() {
		return ratio(timedClicks, plannedClicks);
	}

	/** Hits dealt per hit taken; a clean sheet counts as the number of hits dealt. */
	public float hitRatio() {
		return hitsTaken == 0 ? hitsDealt : ratio(hitsDealt, hitsTaken);
	}

	/** Share of the fights that ended that ran out of time instead of in a knockout. */
	public float drawRate() {
		return ratio(draws, wins + losses + draws);
	}

	public float winRate() {
		return ratio(wins, wins + losses);
	}

	public float rewardPerStep() {
		return ratio(rewardSum, steps);
	}
}
