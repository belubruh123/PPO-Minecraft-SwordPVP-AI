package dev.swordai.core.curriculum;

import dev.swordai.core.env.StepFacts;
import dev.swordai.core.env.TakenStreak;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.JumpIn;
import dev.swordai.core.rl.JumpReset;
import dev.swordai.core.rl.JumpNudge;
import dev.swordai.core.vision.ViewGeometry;

/**
 * Turns ground truth about a step into a reward and updates the metrics. One instance follows one
 * episode at a time; call {@link #endEpisode} between episodes.
 */
public final class RewardModel {
	/** A combo is over once this many ticks pass without another landed hit. */
	private static final int COMBO_TIMEOUT_TICKS = 40;
	/** The opponent's combo is over once this many ticks pass without it landing another hit. */
	private static final int COMBOED_TIMEOUT_TICKS = TakenStreak.COMBO_TICKS;
	/**
	 * A combo that ends counts as one the fighter backed out of only if it spent at least this many of
	 * its ticks out of the opponent's reach. One that ended with it standing where it was, because
	 * the opponent missed or waited, was not its doing.
	 */
	private static final int ESCAPE_MIN_TICKS = 5;
	private static final int NEVER = 100_000;
	private static final float FULL_CHARGE = 0.9f;
	/** The same on its own screen, where the game has not yet added its half tick: eleven ticks after the last swing. */
	private static final float FULL_CHARGE_AT_CLICK = dev.swordai.core.rl.SwingGate.EARLY_CHARGE - 0.005f;
	private static final float SPAM_CHARGE = 0.6f;
	private static final float REENGAGE_CHARGE = 0.75f;
	/** A sword's reach, eye to hitbox, in blocks: beyond it the opponent cannot be hit from where the fighter stands. */
	private static final float REACH = 3f;
	/**
	 * Eye to hitbox, the opponent this near is in the fighter's face: too close to be out of reach
	 * again by the time a jump made now has come down, unless it turns and runs. The same line the
	 * first crit drill draws (see {@code Trainer.DRILL_CRIT_ZONE}).
	 */
	public static final float IN_FACE = 1.8f;
	/** A jump is over once the fighter stands on the ground again, or after this many ticks whatever has become of it. */
	private static final int JUMP_OVER_TICKS = 16;
	/**
	 * For this many ticks after the opponent has been put somewhere else, a jump is not judged: with
	 * lag the fighter still sees it where it was, and jumps at what is no longer there.
	 */
	private static final int HOP_SETTLE_TICKS = 8;
	/**
	 * Until a critical hit of its own has been measured, one is taken to do this many times the damage
	 * of a full hit that is not critical: the game's own factor, before armour.
	 */
	private static final float CRIT_DAMAGE_FACTOR = 1.5f;
	/** Ticks of being ready to sprint-hit that are paid for per landed hit (and before the first). */
	private static final int PRIMED_TICKS_PER_HIT = 6;
	/** Ticks of correct footwork inside the window for a hit to count as W-tapped. */
	private static final int TAP_TICKS_NEEDED = 2;
	/** The hold bonus is at full strength after this many consecutive ticks on the opponent. */
	private static final int HOLD_FULL_TICKS = 20;
	/** Sliding this many degrees a tick across the opponent, or more, earns none of the still bonus. */
	private static final float SLIP_FULL = 2f;
	/** Below this the crosshair counts as resting on the opponent (for the "steady" metric). */
	public static final float STILL_SLIP = 0.15f;
	/** A change of camera speed of this many degrees per tick, or more, costs the whole smooth weight. */
	private static final float SMOOTH_FULL = 16f;
	/**
	 * The search reward is paid per degree swept (this many degrees earn one unit), not per tick, so a
	 * fast look-around earns exactly as much as a slow one and finishing sooner is never a loss.
	 */
	private static final float SEARCH_FULL_TURN = 40f;
	/** Smaller turns than this do not count as choosing a direction. */
	private static final float SEARCH_MIN_TURN = 0.5f;
	/** Turning the camera this many degrees a tick, or more, earns none of the calm bonus. */
	private static final float CALM_FULL = 3f;
	/**
	 * Below this charge the weapon counts as recharging in the metrics. Stages that only score the aim
	 * once the weapon is nearly back use their own value for the reward ({@link RewardWeights#aimFromCharge}).
	 */
	public static final float RECHARGED = 0.75f;

	private float prevMiss = Float.NaN;
	private float prevYawErr;
	private float prevPitchErr;
	private float prevYawTurn;
	private float prevPitchTurn;
	private int combo;
	private int ticksSinceHit = Integer.MAX_VALUE / 2;
	/** A hit taken this long after a hit of its own found it with its sword on the way back; see {@code Tally.counterHits}. */
	private static final int COUNTER_FROM_TICKS = 4;
	private static final int COUNTER_TO_TICKS = 13;
	/** Hits taken in a row without landing one in between: the opponent's combo. */
	private int takenStreak;
	/** The run of hits it is taking is one it was handed three or more hits into: not counted as a combo it could have got out of at two. */
	private boolean handedLong;
	/** The most hits in a row the run it is taking has come to, and whether it has swung since the run began. */
	private int runHits;
	private boolean runSwung;
	private int ticksSinceTaken;
	// Getting away from a combo must not become a way to collect: run, be hit again, run again.
	/** Getting away is paid for once; after that it has to land a proper hit before it is paid for again. */
	private boolean escapeArmed = true;
	/** The bonus for having got away, held back until it returns and lands a proper hit. */
	private float escapeHeld;
	/** Ticks of the opponent's running combo spent out of its reach. */
	private int clearTicks;
	/** Ticks left, after the last hit of the opponent's combo, in which holding S is paid for. */
	private int backLeft;
	/** Ticks since it was last hit, whatever has happened since. */
	private int sinceHurt = NEVER;
	/** It has been hit and has not swung since. */
	private boolean answerOwed;
	/** It has swung and has not been paid for a jump since. */
	private boolean jumpOpen;
	/** Ticks since it took off in a jump of its own that has not ended yet; negative when there is none. */
	private int jumpAge = -1;
	/** That jump has left the ground. */
	private boolean jumpAirborne;
	/** That jump was taken off with the opponent farther away than in its face (or, in a crit drill it is held for, than that drill wants a jump made). */
	private boolean jumpOutOfZone;
	/** The jump it is in was made at an opponent it was running after and not catching; see {@code RewardWeights.chaseCrit}. */
	private boolean jumpChase;
	/** The jump it is in was made by a rule: which one (see {@code JumpIn}), or 0. */
	private int jumpRule;
	/** The way the camera was last turning while the opponent was far away: -1, +1, or 0. */
	private int farDir;
	/** From this far off the opponent is far away: what the camera does there is counted (see {@code Tally.farTicks}). */
	static final float FAR_AIM_FROM = 11f;
	/** And only with the crosshair no farther from it than this: holding the aim, not turning to find it. */
	static final float FAR_AIM_WITHIN_DEG = 4f;
	/** For how many ticks in a row it has been on the ground at a sprint towards the opponent. */
	private int runTicks;
	/** How far off the opponent was on each of the last ticks, newest last. */
	private final float[] reachTrail = new float[CHASE_TICKS + 1];
	private int reachTrailLen;
	/** Ticks since the opponent was last put somewhere else. */
	private int sinceHop = NEVER;
	/** A quarter of a second at a sprint closes a block and a half on an opponent that stands; see {@code RewardWeights.chaseCrit}. */
	static final int CHASE_TICKS = 5;
	/** Closing less than this in that time, the opponent is going away about as fast as a walk or faster. */
	static final float CHASE_CLOSED_MOST = 0.7f;
	/** An opponent it hit this recently is flying off from that hit, not running. */
	static final int CHASE_AFTER_HIT_TICKS = 15;
	/** It has been hit, and has had no tick since in which a punish crit was there to be made. */
	private boolean punishWatch;
	/**
	 * Damage done by the hits of the running combo that were not critical, and how many critical hits'
	 * worth of it has been paid for; see {@link RewardWeights#comboAsCrit}.
	 */
	private float comboPlainDamage;
	private int comboWorthPaid;
	/** What the last hit of the running combo was; see {@link RewardWeights#critFollow}. */
	private static final int KIND_NONE = 0;
	private static final int KIND_PLAIN = 1;
	private static final int KIND_SPRINT = 2;
	private static final int KIND_CRIT = 3;
	private int lastHitKind = KIND_NONE;
	/**
	 * What its last critical hit did, and its last full hit that was not critical. Kept from one fight
	 * to the next: the swords and the armour stay what they are.
	 */
	private float critDamage;
	private float plainDamage;
	private int primedLeft = PRIMED_TICKS_PER_HIT;
	/** Ticks since either side landed a hit. */
	private int ticksSinceContact;
	private boolean tapWindowOpen;
	private int tapWindowAge;
	private int tapGoodTicks;
	private int blindTicks;
	private int holdTicks;
	/** -1 left, +1 right: the side the opponent was on when last seen. 0 until it has been seen. */
	private int lastSeenSide;
	/** The direction a look-around has committed to; 0 while the opponent is in view. */
	private int sweepDir;

	public int combo() {
		return combo;
	}

	/** Hits taken in a row without landing one: the opponent's combo as it stands. */
	public int takenStreak() {
		return takenStreak;
	}

	/** True once the opponent has been off screen long enough that the stage wants a restart. */
	public boolean lostTooLong(StageSpec stage) {
		return stage.lostResetTicks > 0 && blindTicks >= stage.lostResetTicks;
	}

	/**
	 * The episode begins in the middle of the opponent's combo (a combo drill): it has just landed
	 * this many hits in a row, before the first step. Those hits are not charged, since nothing the
	 * agent did brought them on, but they count towards the combo exactly as if they had been.
	 */
	public void startComboed(StageSpec stage, int hits, Tally tally) {
		if (hits <= 0) {
			return;
		}
		RewardWeights w = stage.reward;
		takenStreak = hits;
		// Two hits into it, the combo is at two as it gets its keys: whether a third follows is its doing.
		handedLong = hits >= 3;
		runHits = hits;
		runSwung = false;
		if (hits == 2) {
			tally.twoHitCombos++;
		}
		ticksSinceTaken = 0;
		ticksSinceContact = 0;
		sinceHurt = 0;
		answerOwed = true;
		tally.hitsToAnswer++;
		if (w.comboBreakAfter > 0 && hits >= w.comboBreakAfter) {
			tally.combosTaken++;
		}
		if (w.comboEscapeAfter > 0 && hits >= w.comboEscapeAfter) {
			tally.longCombosTaken++;
			tally.comboedHits++;
			backLeft = w.comboBackTicks;
		}
	}

	public float step(StageSpec stage, StepFacts f, Tally tally) {
		RewardWeights w = stage.reward;
		boolean pitchUnlocked = stage.unlocked(ActionSpace.PITCH);
		// With pitch locked the agent cannot fix vertical error, so it is neither scored nor counted.
		float miss = pitchUnlocked ? f.missDeg : f.yawMissDeg;
		boolean onTarget = pitchUnlocked ? f.onBody : f.yawMissDeg <= 0f;
		boolean onHead = onTarget && (pitchUnlocked ? f.onHead : f.yawOnHead);
		float r = 0f;
		// A weapon that is recharging cannot hit, so a stage may leave the crosshair alone until it
		// is nearly back: where a fighter looks between swings is then its own business.
		boolean recharging = f.charge < (w.aimFromCharge > 0f ? w.aimFromCharge : RECHARGED);
		// Just hit: for a second the fight is about getting a swing back at the opponent, and nothing
		// about swinging is charged. (As things stood when this step was chosen.)
		boolean underAttack = w.answer > 0f && sinceHurt <= w.answerWindowTicks;
		// While that lasts the aim counts whatever the weapon is doing: the opponent has to be found again.
		boolean aimScored = w.aimFromCharge <= 0f || !recharging || underAttack;
		float turned = Math.abs(f.yawTurnDeg) + Math.abs(f.pitchTurnDeg);
		// The combo as it stood when this step was chosen: what a jump on this step was for.
		int comboBefore = combo;
		// Being comboed: the opponent has landed several hits in a row and may land the next.
		boolean comboed = w.comboBreakAfter > 0 && takenStreak >= w.comboBreakAfter;
		// Enough hits in a row that stepping out of the combo is worth as much as swinging back.
		boolean fleeing = w.comboEscapeAfter > 0 && takenStreak >= w.comboEscapeAfter;
		// Not once it has already been paid for getting away without having fought back since.
		boolean running = fleeing && escapeArmed;

		// Everything that compares with the previous tick is skipped across a restart or a hop.
		boolean continuous = !f.discontinuity && !Float.isNaN(prevMiss);
		float slip = 0f;
		if (continuous) {
			if (aimScored) {
				r += w.aimProgress * (prevMiss - miss);
			}
			// How far the crosshair moved relative to the opponent, whoever of the two did the moving.
			slip = Math.abs(ViewGeometry.wrapDegrees(f.yawErrDeg - prevYawErr))
					+ (pitchUnlocked ? Math.abs(f.pitchErrDeg - prevPitchErr) : 0f);
			float speedChange = Math.abs(f.yawTurnDeg - prevYawTurn) + Math.abs(f.pitchTurnDeg - prevPitchTurn);
			r -= w.smooth * Math.min(speedChange, SMOOTH_FULL) / SMOOTH_FULL;
		}
		if (f.enemyVisible && f.reachDist > FAR_AIM_FROM && !f.discontinuity && f.missDeg <= FAR_AIM_WITHIN_DEG) {
			tally.farTicks++;
			tally.farTurnCentiDeg += Math.round(turned * 100f);
			if (turned < STILL_SLIP) {
				tally.farStillTicks++;
			}
			int dir = f.yawTurnDeg > 0.05f ? 1 : f.yawTurnDeg < -0.05f ? -1 : 0;
			if (dir != 0) {
				if (farDir != 0 && dir != farDir) {
					tally.farFlips++;
				}
				farDir = dir;
			}
		} else if (!f.enemyVisible || f.discontinuity) {
			farDir = 0;
		}
		prevMiss = miss;
		prevYawErr = f.yawErrDeg;
		prevPitchErr = f.pitchErrDeg;
		prevYawTurn = f.yawTurnDeg;
		prevPitchTurn = f.pitchTurnDeg;

		if (recharging) {
			tally.rechargeTicks++;
			if (turned < STILL_SLIP) {
				tally.rechargeCalmTicks++;
			}
		} else {
			tally.readyTicks++;
			if (onTarget) {
				tally.readyOnTargetTicks++;
			}
		}

		if (!aimScored) {
			// Paid for leaving the camera where it is, as long as the opponent stays on screen. Not while
			// the plan is to go round the opponent: that cannot be done without turning.
			if (f.enemyVisible && !f.ideaCircle) {
				r += w.calm * (1f - Math.min(turned, CALM_FULL) / CALM_FULL);
			}
		} else if (onTarget) {
			r += w.onTarget;
			if (onHead) {
				r += w.onHead;
			}
			holdTicks++;
			r += w.hold * Math.min(holdTicks, HOLD_FULL_TICKS) / HOLD_FULL_TICKS;
			if (continuous) {
				// The square root makes the first tenth of a degree the expensive one: a rest is a rest.
				r += w.still * (1f - (float) Math.sqrt(Math.min(slip, SLIP_FULL) / SLIP_FULL));
				if (slip < STILL_SLIP) {
					tally.stillTicks++;
				}
			}
		} else {
			r -= w.aimError * Math.min(miss, 45f) / 45f;
			if (holdTicks > 0) {
				tally.holds++;
			}
			holdTicks = 0;
		}

		if (f.enemyVisible) {
			r += w.visible;
			if (blindTicks > 0) {
				tally.reacquires++;
				tally.reacquireTicks += blindTicks;
			}
			blindTicks = 0;
			sweepDir = 0;
			if (Math.abs(f.yawErrDeg) > 1f) {
				lastSeenSide = f.yawErrDeg > 0f ? 1 : -1;
			}
		} else {
			blindTicks++;
			float turn = f.yawTurnDeg;
			if (Math.abs(turn) >= SEARCH_MIN_TURN) {
				int dir = turn > 0f ? 1 : -1;
				if (sweepDir == 0) {
					// Start towards where it was last seen; with no memory, whichever way was picked.
					sweepDir = lastSeenSide != 0 ? lastSeenSide : dir;
				}
				if (dir == sweepDir) {
					r += w.search * Math.min(Math.abs(turn), SEARCH_FULL_TURN) / SEARCH_FULL_TURN;
				}
			}
			r -= w.blindPitch * Math.min(Math.abs(f.agentPitchDeg), 45f) / 45f;
		}

		float rangeScore = rangeScore(f.reachDist, w);
		// The first ticks after a hit of the opponent's combo: the time to be backing out, not coming back.
		boolean backing = running && backLeft > 0;
		if (running) {
			// Fighting range is the opponent's range too. A fighter that deep into a combo is better off
			// out of it, until it has a hit of its own to bring back.
			if (f.reachDist > w.comboSpaceFrom) {
				r += w.comboSpace;
				clearTicks++;
			}
			if (backing) {
				backLeft--;
				tally.pushTicks++;
				if (f.forwardAxis < 0) {
					tally.pushHeld++;
				}
			}
			// S is paid until it is out of the opponent's reach, however long that takes; from there
			// on S is not paid but charged for.
			if (f.reachDist <= w.comboBackWithin) {
				tally.backTicks++;
				if (f.forwardAxis < 0) {
					r += w.comboBack;
					tally.backHeld++;
				}
			} else if (f.forwardAxis < 0) {
				r -= w.comboBackFar;
			}
		} else {
			r += w.range * rangeScore;
		}

		if (primedLeft > 0 && f.charge >= REENGAGE_CHARGE) {
			if (w.critReady > 0f && f.falling && !f.serverSprinting && f.reachDist <= w.critReadyWithin) {
				// Coming down with the sprint off as the weapon comes back: the swing that follows is a critical hit.
				r += w.critReady;
				primedLeft--;
			} else if (f.serverSprinting && f.reachDist <= w.primedWithin) {
				r += w.primed;
				primedLeft--;
			}
		}

		if (f.clicked) {
			if (takenStreak > 0) {
				runSwung = true;
			}
			tally.clicks++;
			if (f.clickOnTarget) {
				tally.clicksOnTarget++;
			} else {
				r -= w.whiff;
			}
			if (f.inAir) {
				// The swing a critical hit is made of. At nothing, it is the weapon thrown away in mid-air.
				tally.airSwings++;
				if (!f.clickOnTarget) {
					// Not an attempt at an opponent at the edge of its reach: that costs the charge and no more.
					if (w.airWhiffBeyond <= 0f || f.reachAtAction > w.airWhiffBeyond) {
						r -= w.airWhiff;
					}
					tally.airWhiffs++;
				}
			}
			// A swing with the weapon barely recharged is a tap, and costs what a tap costs whether or
			// not the fighter has just been hit. Up to 0.11.1 it was free for a second after every hit
			// taken, and the first swing in that second was paid whatever it was: against an opponent
			// that keeps hitting, that is every swing, and what it paid for was clicking in a panic.
			if (f.chargeAtClick < SPAM_CHARGE) {
				r -= w.spam;
				tally.spamClicks++;
			}
			if (underAttack && answerOwed && f.reachAtAction <= w.answerWithin && f.chargeAtClick >= FULL_CHARGE_AT_CLICK) {
				// The hit has been answered: with a swing that had the weapon charged, whatever it came to.
				r += w.answer;
				answerOwed = false;
				tally.answers++;
			}
		}

		if (f.ideaOn) {
			// Not while it is getting out of a combo: then the only thing asked of its feet is to get out.
			if (f.ideaStrafe != 0 && f.enemyVisible && f.reachDist <= w.mixWithin && !running) {
				tally.mixTicks++;
				if (f.strafeAxis == f.ideaStrafe) {
					r += w.mixStrafe;
					tally.mixFollowed++;
				} else if (f.strafeAxis != 0) {
					r -= w.mixStrafe;
				}
			} else if (f.enemyVisible && f.reachDist <= w.mixWithin && !running) {
				// A straight run, the W-tap combo (see MixIdea): no strafe. Charged as going the wrong way
				// round is; not strafing is not paid, since it is what a fighter does by itself.
				tally.mixTicks++;
				if (f.strafeAxis == 0) {
					tally.mixFollowed++;
				} else {
					r -= w.mixStrafe;
				}
			}
			if (f.clicked) {
				tally.plannedClicks++;
				// Holding a swing back is for its own combos; while it is the one being comboed, every
				// swing is on time.
				if (f.ideaHold > 0 && !comboed && !underAttack) {
					r -= w.mixTiming;
				} else {
					tally.timedClicks++;
				}
			}
		}

		if (punishWatch && f.knockedUp && f.critOpen) {
			// Knocked up by the opponent's hit, it came down with everything a punish crit takes.
			tally.punishChances++;
			punishWatch = false;
		}

		if (f.hitsDealt > 0) {
			float c = Math.max(0f, Math.min(1f, f.hitCharge));
			// A hit that neither knocks back nor is critical is worth what the stage says such a hit is
			// worth: everything while hitting at all is being learned, nothing once it can do better.
			float worth = f.sprintHit || f.critDealt ? 1f : w.plainHit;
			r += w.hit * c * c * f.hitsDealt * worth;
			if (f.sprintHit) {
				r += w.sprintHit;
				tally.sprintHits += f.hitsDealt;
			}
			if (f.reachAtAction > 0f && f.reachAtAction < w.closeWithin) {
				tally.closeHits += f.hitsDealt;
				if (f.sprintHit) {
					tally.closeSprintHits += f.hitsDealt;
				}
			}
			primedLeft = PRIMED_TICKS_PER_HIT;
			if (onHead) {
				r += w.headHit;
				tally.headHits += f.hitsDealt;
			}
			if (running && clearTicks >= ESCAPE_MIN_TICKS && c >= FULL_CHARGE) {
				// It backed out of the combo and has come back with a proper hit, before the opponent
				// could land another: the whole of what getting out is worth.
				r += w.comboEscape;
				tally.comboEscapes++;
			} else if (comboed) {
				r += w.comboBreak;
				tally.comboBreaks++;
			}
			if (f.sweep) {
				// A slow hit with no sprint behind it leaves the opponent where it is. Only from inside a
				// combo is it let off: there any charged hit that lands is the way out.
				if (!comboed) {
					r -= w.sweep;
				}
				tally.sweepHits += f.hitsDealt;
			}
			endRun(tally);
			takenStreak = 0;
			handedLong = false;
			clearTicks = 0;
			backLeft = 0;
			if (c >= FULL_CHARGE) {
				// It came back from a run with a proper hit: now the run has paid off, and it may be
				// paid for getting away again.
				if (escapeHeld > 0f) {
					r += escapeHeld;
					escapeHeld = 0f;
					tally.comboEscapes++;
				}
				escapeArmed = true;
			}
			if (f.uppercut) {
				tally.uppercuts++;
				if (jumpAge >= 0 && jumpRule == JumpIn.UPPER) {
					// What that jump was made for.
					tally.upperRuleHits++;
					tally.jumpsEnded++;
					jumpAge = -1;
				}
				if (combo >= w.uppercutCombo) {
					r += w.uppercut;
					if (w.uppercut > 0f) {
						// That is what this jump was for.
						jumpAge = -1;
					}
				}
			}
			if (f.critDealt) {
				tally.critHits += f.hitsDealt;
				r += w.crit;
				if (f.punishCrit) {
					r += w.punishCrit;
					tally.punishCrits++;
					if (punishWatch) {
						tally.punishChances++;
						punishWatch = false;
					}
				}
				if (jumpAge >= 0) {
					// The jump it is in has come to what a jump is for.
					tally.jumpsEnded++;
					tally.jumpCrits++;
					if (jumpChase) {
						// And it was made at an opponent that a run was not catching.
						r += w.chaseCrit;
						tally.chaseCrits++;
					}
					if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.UPPER || jumpRule == JumpIn.HOP) {
						tally.chaseRuleCrits++;
					} else if (jumpRule == JumpIn.COUNTER) {
						tally.counterRuleCrits++;
					} else if (jumpRule == JumpIn.FACE) {
						tally.faceRuleCrits++;
					}
					jumpAge = -1;
				}
			}
			// A hit made before the weapon was back is a tap: it earns nothing for the combo and is not
			// counted as part of one. Up to 0.12.1 it earned the whole combo bonus, more than five times
			// what tapping cost, and so tapping away at an opponent from close up was the best-paid thing
			// a fighter with a combo going could do.
			// Nor does a hit that the stage counts for nothing: a combo is made of hits that knock back or
			// are critical.
			if (c >= SPAM_CHARGE && worth > 0f) {
				if (combo > 0) {
					// The two kinds of hit taken in turn: the opponent knocked away and then come down on,
					// or hit hard and then sent away.
					if (f.critDealt && lastHitKind == KIND_SPRINT) {
						r += w.critFollow;
						tally.critFollows++;
					} else if (f.sprintHit && !f.critDealt && lastHitKind == KIND_CRIT) {
						r += w.sprintFollow;
						tally.sprintFollows++;
					}
				}
				lastHitKind = f.critDealt ? KIND_CRIT : f.sprintHit ? KIND_SPRINT : KIND_PLAIN;
				r += w.combo * Math.min(combo, 4) * c * c;
				combo += f.hitsDealt;
				r += w.comboAsCrit * critWorths(f, c, tally);
			}
			ticksSinceHit = 0;
			tally.hitsDealt += f.hitsDealt;
			if (c >= FULL_CHARGE) {
				tally.fullChargeHits += f.hitsDealt;
			}
			closeTapWindow(tally);
			tapWindowOpen = true;
			tapWindowAge = 0;
			tapGoodTicks = 0;
		} else {
			ticksSinceHit++;
			if (tapWindowOpen) {
				tapWindowAge++;
				boolean wReleased = f.forwardAxis <= 0;
				boolean backOrStrafe = f.forwardAxis < 0 || f.strafeAxis != 0;
				if (wReleased && backOrStrafe) {
					r += w.tap;
					tapGoodTicks++;
				} else if (wReleased) {
					r += 0.5f * w.tap;
				}
				if (tapWindowAge >= w.tapWindowTicks) {
					closeTapWindow(tally);
				}
			} else if (!backing && f.charge >= REENGAGE_CHARGE && f.reachDist > w.rangeMax && f.forwardAxis > 0 && f.sprintKey
					&& (w.spaceIn <= 0f || f.oppCharge < StepFacts.OPP_READY)) {
				r += w.reengage;
			}
			if (combo > 0 && ticksSinceHit > COMBO_TIMEOUT_TICKS) {
				endCombo(tally);
			}
		}

		if (f.walkedInOnCooldown) {
			r += w.spaceIn;
		}
		if (f.walkedInOnReady) {
			r -= w.spaceOut;
		}
		if (f.oppJumpIn == 2) {
			r += w.jumpInReady;
		} else if (f.oppJumpIn == 1) {
			r -= w.jumpInReady;
		}
		if (f.hitIntoJumpIn) {
			r -= w.hitIntoJumpIn;
		}

		if (f.jumpKey && f.reachAtAction > w.jumpWithin && comboBefore < w.uppercutCombo) {
			r -= w.farJump;
		}
		if (f.discontinuity) {
			sinceHop = 0;
		} else if (sinceHop < NEVER) {
			sinceHop++;
		}
		// Standing with the opponent too far off for a jump: out of reach, or in the first crit drill,
		// farther than where it wants the jump made. Every such tick is one on which a jump is for nothing.
		// (Where a jump is only wanted from in the opponent's face, that is anything out of reach. Where
		// it is wanted from wherever a critical hit comes of it, it is farther than a jump carries.)
		float jumpWithin = f.critZone > 0f ? f.critZone : w.jumpFreeWithin > 0f ? REACH : w.jumpWithin;
		if (!f.inAir && f.reachAtAction > jumpWithin && sinceHop > HOP_SETTLE_TICKS) {
			tally.farGroundTicks++;
			if (f.tookOff) {
				tally.farJumps++;
			}
		}
		// Running after the opponent and not getting nearer: at a sprint towards it for a quarter of a
		// second, and what that should have closed has not been closed.
		// (As it stood before this step: the step it takes off on is no longer one on the ground.)
		int ranFor = runTicks;
		boolean atARun = f.onGround && f.forwardAxis > 0 && f.sprintKey && f.enemyVisible && !f.discontinuity;
		runTicks = atARun ? runTicks + 1 : 0;
		if (f.discontinuity) {
			reachTrailLen = 0;
		}
		boolean chasing = w.chaseCrit > 0f && ranFor >= CHASE_TICKS && reachTrailLen > CHASE_TICKS
				&& reachTrail[0] - f.reachAtAction < CHASE_CLOSED_MOST && ticksSinceHit > CHASE_AFTER_HIT_TICKS
				&& sinceHop > HOP_SETTLE_TICKS;
		if (reachTrailLen > CHASE_TICKS) {
			System.arraycopy(reachTrail, 1, reachTrail, 0, CHASE_TICKS);
			reachTrailLen = CHASE_TICKS;
		}
		reachTrail[reachTrailLen++] = f.reachAtAction;
		if (f.tookOff) {
			tally.jumps++;
			jumpAge = sinceHop > HOP_SETTLE_TICKS ? 0 : -1;
			jumpAirborne = false;
			jumpOutOfZone = f.reachAtAction > (f.critZone > 0f ? f.critZone : w.jumpFreeWithin);
			jumpChase = jumpAge >= 0 && chasing && f.reachAtAction > w.chaseFrom && f.reachAtAction <= w.chaseTo;
			if (jumpChase) {
				tally.chaseJumps++;
			}
			jumpRule = jumpAge >= 0 ? f.ruleJump : 0;
			if (jumpRule == JumpIn.CHASE || jumpRule == JumpIn.HOP) {
				tally.chaseRuleJumps++;
				if (jumpRule == JumpIn.HOP) {
					tally.hopRuleJumps++;
				}
			} else if (jumpRule == JumpIn.COUNTER) {
				tally.counterRuleJumps++;
			} else if (jumpRule == JumpIn.FACE) {
				tally.faceRuleJumps++;
			} else if (jumpRule == JumpIn.UPPER) {
				tally.upperRuleJumps++;
			} else if (jumpRule == JumpReset.KIND) {
				tally.resetJumps++;
			}
		} else if (jumpAge >= 0) {
			jumpAge++;
		}
		if (jumpAge >= 0) {
			if (f.discontinuity) {
				// The opponent was put somewhere else in the middle of it: nothing to be said of this jump.
				jumpAge = -1;
			} else {
				jumpAirborne |= !f.onGround;
				boolean landed = f.onGround && (jumpAirborne || jumpAge >= 2);
				if (landed || jumpAge >= JUMP_OVER_TICKS) {
					// Back on the ground, and no critical hit came of it (that ends a jump where it lands).
					if (jumpAirborne) {
						// Charged by where it took off, in a stage that draws such a line
						// (RewardWeights.jumpFreeWithin): from nearer than that it is free, whatever became
						// of it. Where there is no such line every jump that came to nothing costs the
						// same small amount, wherever it was made from.
						// Nor one a rule made for it: that was not its to leave out.
						if (jumpOutOfZone && !jumpChase && jumpRule == 0) {
							r -= w.wastedJump;
						}
						tally.jumpsEnded++;
					}
					jumpAge = -1;
				}
			}
		}
		if (f.stepBack) {
			tally.stepBackTicks++;
		}
		if (f.jumpWindow) {
			tally.jumpWindowTicks++;
			if (f.jumpKey) {
				tally.jumpWindowJumps++;
			}
		}
		if (f.clicked) {
			jumpOpen = true;
		}
		// A jump is the start of a critical hit if it is made three to five ticks after the swing; see JumpNudge.
		if (jumpOpen && f.tookOff && !f.clicked && f.chargeAtClick >= JumpNudge.MIN_CHARGE && f.chargeAtClick <= JumpNudge.MAX_CHARGE
				&& f.reachAtAction <= w.jumpWithin) {
			r += w.critJump;
			jumpOpen = false;
		}

		if (f.hitsTaken > 0) {
			// Held in place for a drill, there was nothing it could have done about it.
			if (!f.cannotAvoid) {
				r -= w.hitTaken * f.hitsTaken;
				r -= w.critTaken * f.critsTaken;
			}
			tally.hitsTaken += f.hitsTaken;
			tally.critsTaken += f.critsTaken;
			if (jumpAge >= 0 && jumpRule != 0 && jumpRule != JumpReset.KIND) {
				// Hit in the air of a jump a rule made for it.
				tally.ruleJumpsHit++;
			}
			if (ticksSinceHit >= COUNTER_FROM_TICKS && ticksSinceHit <= COUNTER_TO_TICKS) {
				tally.counterHits++;
			}
			punishWatch = true;
			endCombo(tally);
			int before = takenStreak;
			takenStreak += f.hitsTaken;
			if (before == 0) {
				// A new run of hits: what it swung before does not answer it. (A swing on the tick of the hit does not either.)
				runSwung = false;
			}
			runHits = takenStreak;
			if (!handedLong && !f.cannotAvoid) {
				if (before < 2 && takenStreak >= 2) {
					tally.twoHitCombos++;
				}
				if (before < 3 && takenStreak >= 3) {
					tally.threeHitCombos++;
					r -= w.comboThird;
				}
			}
			if (takenStreak >= 2 && !f.cannotAvoid) {
				r -= w.comboedHit * f.hitsTaken;
			}
			ticksSinceTaken = 0;
			sinceHurt = 0;
			answerOwed = true;
			tally.hitsToAnswer++;
			// Hit again after getting away, before landing anything: the run was worth nothing.
			escapeHeld = 0f;
			clearTicks = 0;
			if (w.comboBreakAfter > 0 && before < w.comboBreakAfter && takenStreak >= w.comboBreakAfter) {
				tally.combosTaken++;
			}
			if (w.comboEscapeAfter > 0 && before < w.comboEscapeAfter && takenStreak >= w.comboEscapeAfter) {
				tally.longCombosTaken++;
			}
			if (w.comboEscapeAfter > 0 && takenStreak >= w.comboEscapeAfter) {
				// Another hit of a combo: it is in one now, and if it was in one before, it did not get out.
				tally.comboedHits++;
				if (before >= w.comboEscapeAfter) {
					tally.comboedHitsFollowed++;
				}
				backLeft = w.comboBackTicks;
			}
		} else if (takenStreak > 0 && ++ticksSinceTaken > COMBOED_TIMEOUT_TICKS) {
			if (running && clearTicks >= ESCAPE_MIN_TICKS) {
				// The opponent's combo ran out without another hit, with the fighter out of its reach:
				// it got away. What that was worth is held back until it has returned and landed a hit
				// of its own.
				escapeHeld = w.comboEscape;
				escapeArmed = false;
			}
			endRun(tally);
			takenStreak = 0;
			handedLong = false;
			clearTicks = 0;
			backLeft = 0;
		}
		if (f.hitsTaken == 0 && sinceHurt < NEVER) {
			sinceHurt++;
		}
		if (f.hitsDealt > 0 || f.hitsTaken > 0) {
			ticksSinceContact = 0;
		} else if (++ticksSinceContact > w.stallAfterTicks) {
			r -= w.stall;
		}
		if (f.timedOut) {
			r -= w.draw;
			tally.draws++;
		}
		r += w.damage * (f.damageDealt - f.damageTaken);
		if (f.koDealt) {
			r += w.ko;
		}
		if (f.koTaken) {
			r -= w.ko;
		}

		tally.steps++;
		if (f.enemyVisible) {
			tally.visibleTicks++;
		}
		if (onHead) {
			tally.onHeadTicks++;
		}
		if (onTarget) {
			tally.onTargetTicks++;
		}
		if (rangeScore >= 1f) {
			tally.inRangeTicks++;
		}
		tally.aimErrorSum += miss;
		tally.damageDealt += f.damageDealt;
		tally.damageTaken += f.damageTaken;
		roundDealt += f.damageDealt;
		roundTaken += f.damageTaken;
		if (roundDealt >= ROUND_HEALTH || roundTaken >= ROUND_HEALTH) {
			if (roundDealt >= roundTaken) {
				tally.roundsWon++;
			} else {
				tally.roundsLost++;
			}
			roundDealt = 0f;
			roundTaken = 0f;
		}
		tally.rewardSum += r;
		return r;
	}

	/** A run of hits taken is over: counted, if it was a combo, and whether it was sat through without a swing. */
	private void endRun(Tally tally) {
		if (runHits >= 2) {
			tally.combosOver++;
			if (!runSwung) {
				tally.combosUnanswered++;
				if (runHits >= 3) {
					tally.combosSatThrough++;
				}
			}
		}
		runHits = 0;
		runSwung = false;
	}

	/** Closes anything still open and resets for the next episode. */
	/** A life: a round is over once this much has been dealt or taken in it; see {@link Tally#roundsWon}. */
	public static final float ROUND_HEALTH = 20f;
	private float roundDealt;
	private float roundTaken;

	public void endEpisode(Tally tally, boolean won, boolean lost) {
		closeTapWindow(tally);
		endCombo(tally);
		endRun(tally);
		if (holdTicks > 0) {
			tally.holds++;
		}
		if (blindTicks > 0) {
			tally.lostEpisodes++;
		}
		tally.episodes++;
		if (won) {
			tally.wins++;
		}
		if (lost) {
			tally.losses++;
		}
		reset();
	}

	/** Forgets the episode without counting anything: one that was set up and never played. */
	public void reset() {
		combo = 0;
		roundDealt = 0f;
		roundTaken = 0f;
		tapWindowOpen = false;
		prevMiss = Float.NaN;
		prevYawTurn = 0f;
		prevPitchTurn = 0f;
		ticksSinceHit = Integer.MAX_VALUE / 2;
		takenStreak = 0;
		handedLong = false;
		runHits = 0;
		runSwung = false;
		ticksSinceTaken = 0;
		escapeArmed = true;
		escapeHeld = 0f;
		clearTicks = 0;
		backLeft = 0;
		primedLeft = PRIMED_TICKS_PER_HIT;
		ticksSinceContact = 0;
		sinceHurt = NEVER;
		answerOwed = false;
		jumpOpen = false;
		jumpAge = -1;
		jumpChase = false;
		jumpRule = 0;
		farDir = 0;
		runTicks = 0;
		reachTrailLen = 0;
		jumpAirborne = false;
		jumpOutOfZone = false;
		sinceHop = NEVER;
		punishWatch = false;
		comboPlainDamage = 0f;
		comboWorthPaid = 0;
		lastHitKind = KIND_NONE;
		blindTicks = 0;
		holdTicks = 0;
		lastSeenSide = 0;
		sweepDir = 0;
	}

	private void closeTapWindow(Tally tally) {
		if (tapWindowOpen) {
			tapWindowOpen = false;
			tally.tapWindows++;
			if (tapGoodTicks >= TAP_TICKS_NEEDED) {
				tally.tapsDone++;
			}
		}
	}

	private void endCombo(Tally tally) {
		if (combo > 0) {
			tally.combos++;
			tally.comboHits += combo;
			combo = 0;
		}
		comboPlainDamage = 0f;
		comboWorthPaid = 0;
		lastHitKind = KIND_NONE;
	}

	/**
	 * A hit of the running combo has landed with the weapon back. Returns how many further critical
	 * hits' worth of damage the combo's hits that were not critical have now come to; see
	 * {@link RewardWeights#comboAsCrit}.
	 */
	private int critWorths(StepFacts f, float charge, Tally tally) {
		if (f.damageDealt <= 0f) {
			// Where the damage of a hit is not known, it is not counted.
			return 0;
		}
		boolean measured = charge >= FULL_CHARGE && f.hitsDealt == 1;
		if (f.critDealt) {
			if (measured) {
				critDamage = f.damageDealt;
			}
			return 0;
		}
		if (measured) {
			plainDamage = f.damageDealt;
		}
		comboPlainDamage += f.damageDealt;
		float worth = critDamage > 0f ? critDamage : CRIT_DAMAGE_FACTOR * plainDamage;
		if (worth <= 0f) {
			return 0;
		}
		int reached = (int) (comboPlainDamage / worth + 1e-4f);
		int more = Math.max(0, reached - comboWorthPaid);
		comboWorthPaid = Math.max(comboWorthPaid, reached);
		tally.comboCritWorths += more;
		return more;
	}

	/** 1 inside the band, fading linearly to 0 over {@code rangeFalloff} blocks on either side. */
	static float rangeScore(float dist, RewardWeights w) {
		if (dist >= w.rangeMin && dist <= w.rangeMax) {
			return 1f;
		}
		float outside = dist < w.rangeMin ? w.rangeMin - dist : dist - w.rangeMax;
		if (w.rangeFalloff <= 0f) {
			return 0f;
		}
		return Math.max(0f, 1f - outside / w.rangeFalloff);
	}
}
