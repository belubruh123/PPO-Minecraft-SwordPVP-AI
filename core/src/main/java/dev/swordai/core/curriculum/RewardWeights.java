package dev.swordai.core.curriculum;

/**
 * Weights of every reward term. Each stage turns on the terms for the skill it teaches. All of
 * these are computed from ground truth that the acting network never sees.
 */
public final class RewardWeights {
	// ---- aim (per tick) -------------------------------------------------------------------------
	// "On the opponent" means anywhere on the hitbox. Nothing here prefers its centre, so once the
	// crosshair is on, there is no reason left to move it.
	/** Per degree the crosshair came closer to the opponent's hitbox since the previous tick. */
	public float aimProgress = 0f;
	/** While the crosshair is on the opponent. */
	public float onTarget = 0f;
	/** Extra while it is on the head in particular. Off by default: any spot on the opponent is fine. */
	public float onHead = 0f;
	/** While off the opponent, scaled by how far off (capped at 45 degrees), so drifting off is never free. */
	public float aimError = 0f;
	/**
	 * While on the opponent and not sliding across it: full with the crosshair at rest on a standing
	 * opponent (or moving exactly with a walking one), mostly gone at the slightest wobble.
	 */
	public float still = 0f;
	/** Charged for every change of camera speed, so speeding up and slowing down happen once, not in a flutter. */
	public float smooth = 0f;
	/** While any of the opponent is on screen: looking away into the sky or floor is never free. */
	public float visible = 0f;
	/**
	 * Extra for staying on the opponent, growing over the first second of an unbroken hold. Touching
	 * it and drifting off again earns far less than keeping the crosshair there.
	 */
	public float hold = 0f;
	/**
	 * While the opponent is off screen: paid per 40 degrees turned, as long as the turn keeps going
	 * the same way (towards the side the opponent was last seen on, if it was seen). This is what
	 * teaches a full, steady look-around instead of twitching left and right.
	 */
	public float search = 0f;
	/** While the opponent is off screen: charged for looking up or down instead of level. */
	public float blindPitch = 0f;
	/**
	 * Above zero: the aim terms above (progress, on target, hold, still, aim error) only count while the
	 * weapon is at least this charged. Below it the weapon cannot land a proper hit anyway, so the
	 * crosshair is free to wander off the opponent, the way a player lets it between the hits of a combo.
	 */
	public float aimFromCharge = 0f;
	/**
	 * While the weapon is below {@link #aimFromCharge} and the opponent is on screen: paid for a camera
	 * that stays where it is, so the time between swings is spent moving the feet, not the view.
	 */
	public float calm = 0f;

	// ---- spacing (per tick) ---------------------------------------------------------------------
	/** While the eye-to-hitbox range is inside [rangeMin, rangeMax]; fades out over rangeFalloff. */
	public float range = 0f;
	public float rangeMin = 2.2f;
	public float rangeMax = 3.0f;
	public float rangeFalloff = 2.0f;

	// ---- hits (per event) -----------------------------------------------------------------------
	/** Per landed hit, multiplied by attack charge squared (vanilla's own damage curve). */
	public float hit = 0f;
	/**
	 * The share of {@link #hit} that a hit earns which is neither a sprint hit nor a critical hit: a
	 * hit that does its damage and nothing else. 1 while hitting at all is what is being learned. In
	 * the stages after the sprint hit is learned it is 0: a fighter that can knock back or crit and
	 * does neither has wasted its sword, and such a hit earns nothing for the combo and is not
	 * counted as part of one.
	 */
	public float plainHit = 1f;
	/** Extra when the crosshair was on the head as the hit landed. */
	public float headHit = 0f;
	/** Extra when the hit was a sprint-knockback hit. */
	public float sprintHit = 0f;
	/** Extra per hit already in the current combo (capped at four). */
	public float combo = 0f;
	/**
	 * Clicking with nothing under the crosshair. Off in every stage: a miss resets the weapon's charge,
	 * which is cost enough, and charging more for it teaches not swinging when a hit was there to be had.
	 */
	public float whiff = 0f;
	/** Clicking while the weapon is still mostly uncharged, whether or not it hits. */
	public float spam = 0f;
	/**
	 * Charged for a landed hit that was a sweep: a hit made slowly and without a sprint behind it. It
	 * hurts as much as any hit but leaves the opponent standing right there, free to hit back.
	 */
	public float sweep = 0f;
	/**
	 * Extra for a hit landed while being comboed: after taking {@link #comboBreakAfter} hits in a row
	 * without landing one. Any hit knocks the opponent back and ends its combo, so swinging back is
	 * worth it even when the hit itself is a poor one; such a hit is also never charged as a sweep.
	 */
	public float comboBreak = 0f;
	public int comboBreakAfter = 2;
	/**
	 * For answering a hit with a swing: paid once per hit taken, for the first swing made within
	 * {@link #answerWindowTicks} ticks of it with the opponent no farther off than {@link #answerWithin},
	 * whether that swing lands or cuts the air. A fighter that only swings when it is sure of the hit
	 * stops swinging exactly when it is being comboed, which is when a swing is worth trying.
	 *
	 * <p>That distance is a sword's reach and a little more: a swing at an opponent that is plainly
	 * out of reach answers nothing. What to do then is to get clear and come back, see {@link #comboEscape}.
	 *
	 * <p>It cannot be collected on: there is one per hit taken, and a hit taken costs several times as
	 * much. For the same stretch of ticks nothing else about swinging is charged either: not swinging
	 * before the weapon is back, not swinging before the plan said, not a slow hit. And the aim counts
	 * the whole time, also while the weapon recharges, since it has to find the opponent again.
	 */
	public float answer = 0f;
	public int answerWindowTicks = 20;
	public float answerWithin = 3.2f;
	/**
	 * The other way out of a combo: backing out of it and coming back with a hit. From the moment it
	 * has taken {@link #comboEscapeAfter} hits in a row, which is when the opponent has a combo going.
	 * Earned when that combo then ends because the opponent could not land another hit, which is what
	 * happens when the fighter lets itself be knocked clear (W let go, backing off or stepping aside)
	 * instead of running back into the next hit. Hitting back is paid as well ({@link #comboBreak}),
	 * so it is free to do whichever the moment allows: swing if the opponent is in reach, step out if
	 * it is not.
	 *
	 * <p>So that backing off cannot be collected on, it is hedged twice. The bonus is only paid once
	 * the fighter has come back and landed a fully charged hit; being hit first forfeits it. And after
	 * one such escape nothing about backing off is paid again until it has landed a fully charged hit.
	 *
	 * <p>It need not wait for the opponent's combo to run out. Having been clear of it for a few ticks
	 * and then landing a fully charged hit before the opponent lands another is the same thing done
	 * faster, and is paid the same (in place of {@link #comboBreak}, not on top of it).
	 */
	public float comboEscape = 0f;
	public int comboEscapeAfter = 2;
	/**
	 * Per tick of backing out with the opponent farther away than {@link #comboSpaceFrom}: just out of
	 * its reach. While backing out this replaces the pay for standing at fighting range, which in a
	 * combo is the range at which the next hit lands.
	 */
	public float comboSpace = 0f;
	public float comboSpaceFrom = 3.5f;
	/**
	 * How backing out is done: with S. Per tick of holding S while backing out, for the first
	 * {@link #comboBackTicks} ticks after each hit of the combo, with the opponent no farther off than
	 * {@link #comboBackWithin}. Those are the ticks that decide it: the hit has thrown the fighter back,
	 * the opponent is running after it, and whether its next swing finds the fighter a step out of
	 * reach or a step inside it depends on the key held on the way. W held there carries it straight
	 * back into the next hit. For the same ticks coming back in is not paid ({@link #reengage}).
	 *
	 * <p>What a hit's worth of this comes to stays below what the hit cost ({@link #hitTaken}), so
	 * standing with its back to the wall and being hit is never a way to collect.
	 */
	public float comboBack = 0f;
	public int comboBackTicks = 12;
	public float comboBackWithin = 3f;
	/**
	 * Per tick of holding S in the opponent's combo once it is farther off than
	 * {@link #comboBackWithin}: by then backing away has done what it is for, and going on with it is
	 * running from the fight.
	 */
	public float comboBackFar = 0f;
	/** Per hit received. */
	public float hitTaken = 0f;
	/** Per point of health: dealt minus taken. */
	public float damage = 0f;
	/** Knockout bonus / penalty. */
	public float ko = 0f;
	/**
	 * Against stalling. Charged per tick once {@link #stallAfterTicks} have passed with no hit landed
	 * by either side: circling out of reach, or standing off, must never be worth more than fighting.
	 */
	public float stall = 0f;
	public int stallAfterTicks = 100;
	/** Charged once when a fight to the knockout runs out of time: nobody won, so both lost. */
	public float draw = 0f;

	// ---- critical hits --------------------------------------------------------------------------
	// A critical hit is made while falling and with the server not having the fighter sprinting: half
	// as much damage again, which through armour comes to nearly twice, but none of a sprint hit's
	// knockback.
	/**
	 * Extra cost of a hit taken that is the second in a row or a later one, on top of what any hit
	 * taken costs: being comboed is worse than being hit, and is charged as such.
	 */
	public float comboedHit = 0f;
	/**
	 * Extra cost, once, of the third hit of a combo it is taking, when it was free to do something
	 * about it (see {@code Tally.threeHitCombos}). Two hits in a row is being caught; the third is not
	 * having got out. That is the mark it is held to: out of a combo by its second hit, by a hit of
	 * its own or by getting clear.
	 */
	public float comboThird = 0f;
	/** Extra for every critical hit landed. */
	public float crit = 0f;
	/**
	 * Extra for a critical hit that follows a sprint hit in the same combo: the opponent was knocked
	 * away, and the fighter went after it with a jump and came down on it. That is the place for a
	 * critical hit: the time until the sword is back is spent in the air anyway, the opponent is in no
	 * position to hit first, and half as much damage again is had for nothing.
	 */
	public float critFollow = 0f;
	/**
	 * Extra for a sprint hit that follows a critical hit in the same combo. A critical hit leaves the
	 * opponent close; the hit after it is the one to send it away with, and that sets up the next jump.
	 * With {@link #critFollow} this pays the two kinds of hit taken in turn better than either alone.
	 */
	public float sprintFollow = 0f;
	/**
	 * Extra cost of a hit taken that was a critical hit, on top of what any hit taken costs. For the
	 * stages that do not count damage: there a critical hit would otherwise cost no more than a tap,
	 * and an opponent jumping in for one would be nothing to do anything about.
	 */
	public float critTaken = 0f;
	/**
	 * Extra on top of that for a punish crit: a critical hit made on the way down from being knocked
	 * into the air by the opponent's own hit, without jumping. It turns the opponent's hit into an opening.
	 */
	public float punishCrit = 0f;
	/**
	 * Charged once for a jump that was not a sure thing and came to nothing: it took off with the
	 * opponent farther away than in its face ({@code RewardModel.IN_FACE}), and was back on the
	 * ground without a critical hit. From there a sprint hit is on, and a jump gives that up along
	 * with the footwork for half a second: so it has to come off, and with a critical hit paying
	 * {@link #crit} it is worth making where it comes off better than about one time in three. That is
	 * the line between the two ways of hitting, and where it runs is found in the fights.
	 *
	 * <p>A jump that took off with the opponent in its face is not charged, whatever came of it:
	 * mistimed, or the opponent got away while it was in the air. That costs the hit it could have
	 * been and nothing more, so that a fighter is not taught out of jumping by opponents that move.
	 * (0.13.1 and 0.13.2 charged by whether the opponent was in reach on the way down, wherever the
	 * jump was made from. In fights that was nearly every jump, and the jump was gone within fifteen
	 * updates of leaving the drills.)
	 *
	 * <p>Only a cost, so there is nothing in it to collect on. What keeps the jump alive is what a
	 * critical hit pays ({@link #crit}).
	 *
	 * <p>It has to be of the same order as that. In 0.13.0 it was a seventh of what a critical hit
	 * earns, and two things followed. A jump was worth making wherever a critical hit came of it one
	 * time in eight, which is a jump at an opponent four blocks off. And what is learned from a batch
	 * of fights goes by how far each outcome is from the average: next to the critical hits, a cost
	 * that small all but vanished, while every critical hit landed from close up made jumping likelier
	 * everywhere. So it kept jumping at an opponent out of reach long after it had the timing.
	 */
	public float wastedJump = 0f;
	/**
	 * A jump taken off with the opponent no farther away than this is never charged as wasted,
	 * whatever came of it ({@code RewardModel.IN_FACE} up to 0.13.3, when a jump was only wanted from
	 * right in the opponent's face). Zero: every jump that ends without a critical hit is charged
	 * {@link #wastedJump}, which is then set low; where a jump is worth making is left to what
	 * critical hits pay.
	 */
	public float jumpFreeWithin = 1.8f;
	/**
	 * Charged for a swing made in the air that reaches nothing: the opponent was not under the
	 * crosshair, or too far off. Only in the air. A swing on the ground that misses still costs
	 * nothing but the weapon's charge, as before.
	 *
	 * <p>In the air it is different in two ways. The swing there is the one a critical hit is made of,
	 * and it comes at the end of a jump or a knock-up that has taken half a second; and the weapon it
	 * uses up is the one the fighter needs the moment it lands, when an opponent that was out of reach
	 * arrives. Without a cost nothing ever told the crit model that such a swing is a bad one: held in
	 * place for a drill it loses nothing by it, and after being knocked up by a hit there is not even
	 * a jump to be charged for.
	 */
	public float airWhiff = 0f;
	/**
	 * Above zero: a swing in the air that reaches nothing is only charged {@link #airWhiff} if the
	 * opponent was farther away than this when it was made. Nearer than that it was an attempt: the
	 * opponent was at the edge of the sword's reach or a step beyond it, which is where an opponent
	 * that is comboing the fighter stands, and where a person is a tick late or half a step too close
	 * every few swings. Such a swing costs the sword's charge and nothing else, so that whether it is
	 * worth making is learned from how often it lands. Zero: every swing in the air at nothing is charged.
	 *
	 * <p>Up to the first runs of 0.14.0 every one was charged, at 1.0. Comboed by a person, who keeps
	 * at three blocks, it was in the air from one hit to the next and did not swing once.
	 */
	public float airWhiffBeyond = 0f;
	/**
	 * The other way to the same damage. Paid each time the hits of one combo that were not critical
	 * add up to another critical hit's worth of damage, for as long as the combo lasts. So a sprint
	 * hit that is followed up earns what a critical hit earns, hit for hit and damage for damage, and
	 * a sprint hit that stays alone earns less: it did less than a critical hit would have.
	 *
	 * <p>This is what makes not jumping a choice with something behind it. Where a critical hit is
	 * not a sure thing the jump is left alone, the sprint-hit fighter plays on, and if its combo comes
	 * to as much as the critical hit would have, that was the right call and is paid as one.
	 *
	 * <p>It is counted in damage done, and paid again for every further critical hit's worth, so a
	 * long combo is worth more than several short ones and there is nothing to gain by breaking one
	 * off to start the count again. A hit made before the weapon was back counts for nothing here,
	 * as it does for the combo.
	 */
	public float comboAsCrit = 0f;
	/**
	 * Per tick of being set up for a critical hit as the weapon comes back: falling, not sprinting as
	 * far as the server knows, the weapon nearly recharged and the opponent within
	 * {@link #critReadyWithin}. It is to a critical hit what {@link #primed} is to a sprint hit, and is
	 * paid out of the same few ticks per landed hit, so a fighter is paid for the one or the other and
	 * hopping about without ever hitting earns nothing.
	 */
	public float critReady = 0f;
	public float critReadyWithin = 3.5f;
	/**
	 * For the jump a critical hit starts with: taking off as it swings or within a few ticks of it,
	 * with the opponent within {@link #jumpWithin}. A jump lasts about as long as the weapon takes to
	 * come back, so one made then has the fighter coming down just as the next swing is ready. Paid
	 * once per swing.
	 */
	public float critJump = 0f;
	/**
	 * Per tick the jump key is held with the opponent farther away than {@link #jumpWithin}, unless a
	 * combo is long enough for an uppercut: a jump is for a hit, and there is none to be had from there.
	 */
	public float farJump = 0f;
	public float jumpWithin = 4.5f;
	/**
	 * A critical hit that comes of a jump made at an opponent it is running after and not getting
	 * nearer to: on top of what a critical hit earns. The opponent is {@link #chaseFrom} to
	 * {@link #chaseTo} blocks off, the fighter has been at a sprint towards it for a quarter of a
	 * second and has closed next to nothing, and it has not just hit it (an opponent flying off from a
	 * hit is not one that is running). Such a jump is also never charged as wasted
	 * ({@link #wastedJump}), whatever comes of it.
	 *
	 * <p>Why: a run does not catch a player who is backing off or running, and a running jump does;
	 * the hit made coming down from it is a critical hit, which does not send that player on its way
	 * as a sprint hit would. But a jump that is tried and comes to nothing costs, everywhere, and so
	 * the first thing ever learned about jumping was not to (four runs of 0.14.0, each time within
	 * fifty updates). Here, and only here, trying is free. Being hit in the air is not: an opponent
	 * that backs off with its sword ready still makes a careless jump cost what a hit costs.
	 * Zero: no such thing.
	 */
	public float chaseCrit = 0f;
	public float chaseFrom = 3.0f;
	public float chaseTo = 6.0f;
	/**
	 * Extra for an uppercut that continues a combo of at least {@link #uppercutCombo} hits: a
	 * sprint-knockback hit made while still rising from a sprint-jump at the opponent. The jump carries
	 * the fighter in faster than a run, so it catches an opponent that is backing out of the combo, and
	 * it lands off the usual rhythm.
	 */
	public float uppercut = 0f;
	public int uppercutCombo = 3;

	// ---- W-tap shaping (per tick) ---------------------------------------------------------------
	/**
	 * In the few ticks after a landed hit: W released and either S or a strafe key held, which keeps
	 * the agent at the edge of its reach instead of running into the opponent's swing.
	 */
	public float tap = 0f;
	public int tapWindowTicks = 6;
	/** Once the weapon is nearly recharged and the opponent is out of reach: W + sprint to close in. */
	public float reengage = 0f;
	/**
	 * Spacing: paid once per swing of the opponent's, on the step it walks into reach while that sword
	 * is on its way back ({@code StepFacts.walkedInOnCooldown}): out of reach while the other sword is
	 * ready, in while it comes back. While above zero, {@link #reengage} is paid only into such a sword;
	 * coming back in on a ready one is left to what comes of it. The user, 18 October: "Let it learn to
	 * space other". Zero (off) unless set.
	 */
	public float spaceIn = 0f;
	/**
	 * Spacing, the other half: taken on each step it walks into reach while the opponent's sword is
	 * ready ({@code StepFacts.walkedInOnReady}). The user, 19 October: opponents space it, crit it and
	 * sprint-hit it, because it walks into a ready sword; a day of {@link #spaceIn} alone left it doing
	 * that twice as often as walking in on one coming back. Zero (off) unless set.
	 */
	public float spaceOut = 0f;
	/**
	 * Paid when the opponent jumps in at it while its sword is ready to meet the jump (a hit then knocks
	 * the opponent out of the air and its critical hit never lands), taken when the sword is down. The
	 * spacer comes in on its own clock whatever the fighter's sword is doing; trading at its landing
	 * leaves the sword down for its next jump in (20 October). Zero (off) unless set.
	 */
	public float jumpInReady = 0f;
	/**
	 * Taken for a plain hit (not a critical one) made first on an opponent jumping in at it with its sword ready
	 * ({@code StepFacts.hitIntoJumpIn}): the hit does not stop its crit, which comes back at once. The user, 23 October:
	 * "TEACH IT ... hit first is BAD in cases when enemy is in air / jump in"; the answer is to let it land and punish crit.
	 * Zero (off) unless set.
	 */
	public float hitIntoJumpIn = 0f;
	/**
	 * Per tick with the weapon nearly recharged, the opponent within {@link #primedWithin} and the
	 * sprint in place as the server knows it, so that the hit about to be made will knock back. This is
	 * what makes it press W and sprint again before a hit even when the opponent is already in its
	 * face and no step forward seems needed. Paid for a few ticks per landed hit only, so standing
	 * ready without ever hitting earns nothing.
	 */
	public float primed = 0f;
	public float primedWithin = 4f;
	/** Hits made from nearer than this count as close ones in the metrics. */
	public float closeWithin = 2.4f;

	// ---- mix-ups: following a random plan (see MixIdea) -----------------------------------------
	/**
	 * Per tick with the opponent in view and no farther off than {@link #mixWithin}: paid for strafing
	 * the way the plan says, charged for the other way.
	 */
	public float mixStrafe = 0f;
	/**
	 * Strafing is footwork for a fight; beyond this distance the plan pays nothing and costs nothing.
	 * Out of reach it runs straight in (see {@code StraightIn}), so that is where the plan ends. Was 5
	 * up to 18 October.
	 */
	public float mixWithin = dev.swordai.core.rl.StraightIn.OUT;
	/** Charged for every swing made while the plan still says to hold it back. */
	public float mixTiming = 0f;
}
