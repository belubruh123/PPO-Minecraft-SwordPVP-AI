package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.TakenStreak;
import dev.swordai.core.vision.MaskFrame;

import java.util.Arrays;
import java.util.Random;

/**
 * The trained policy as a player, with no learning attached: everything between what it senses on
 * one tick and what it presses. Whoever owns the body supplies the picture and the body's state and
 * carries out the returned action; that is all that differs between playing the user's own
 * character and playing a fake player.
 *
 * <p>Not thread-safe: one thread drives one fighter.
 */
public final class Fighter {
	private final Learner learner;
	private final int headMask;
	private final boolean ideasLearned;
	private final boolean assist;
	private float swingMinCharge;
	/** The least charge for a swing a rule makes; see {@code Config.ruleSwingCharge}. */
	private float ruleMinCharge;
	private int swingExtraTicks;
	private final boolean answerRule;
	/** See {@code Config.answerSpareBacking}. */
	private final boolean answerSpareBacking;
	/** See {@code Config.answerNudge}. */
	private final float answerNudge;
	private final boolean punishRule;
	/** The jumps that are made for it, if its training has given it the jump key; see {@link JumpIn}. */
	private final JumpIn jumpIn;
	private final boolean farRest;
	private final boolean straightIn;
	private final boolean whiffGuard;
	/** The camera brought onto the opponent after a hit; see {@link AimRule}. Null where that is not a rule. */
	private final AimRule aimRule;
	/** See {@link LostRule}; null where the camera is not the rules' to turn. */
	private final LostRule lostRule;
	private final boolean afterHit;
	private final FallWatch fallWatch = new FallWatch();
	/** The jump made for it on a hit of a combo it is taking; see {@link JumpReset}. Null where that is not a rule. */
	private final JumpReset jumpReset;
	/** Out of the line of a combo it is taking, to the side; see {@link ComboStrafe}. Null where that is not a rule. */
	private final ComboStrafe comboStrafe;
	/** The step back after a hit of its own that has not sent the opponent away; see {@link StepBack}. */
	private final StepBack stepBack;
	private final boolean meetRule;
	private final boolean punishWait;
	private final float keepWithin;
	private final boolean punishInCombo;
	private final boolean meetWhenComing;
	private final boolean dodgeJump;
	private final EdgeHold edgeHold;
	/** How it fights, chosen by the tactics model every half second; see {@link Tactic}. Null without one. */
	private final Tactic.Clock tacticClock;
	private final boolean counterCritRule;
	private final boolean edgeHoldRule;
	private final boolean stepBackRule;
	private final boolean tacticOnEvents;
	/** The window of the fight the opponent reader reads; null without one. See {@link OpponentReader}. */
	private final OpponentReader.Window oppWindow;
	/** Hits it has landed in a row without taking one. */
	private int dealtRow;
	/** S is held for it in the ticks after a hit of a combo it is taking; see {@code Config.backRule}. */
	private boolean backOutRule;
	/** The game's pick as of this decision: the opponent under the crosshair and within reach; see {@link AnswerRule}. */
	private boolean crosshairOnOpponent;
	/** The swing of the last decision was the rule's, not the network's. */
	private boolean answerForced;
	/** Which rules had keys on the last decision; see {@link #rules()}. */
	private int rules;
	/** For how many ticks in a row the weapon has been all the way back. */
	private int ticksBack;
	private final float temperature;
	private final Random rng;

	private final Obs obs = new Obs();
	private final SightTracker sight = new SightTracker();
	private final AimMotor motor;
	private final MixIdea idea = new MixIdea();
	private final int[] action = new int[ActionSpace.HEADS];
	private boolean hasAction;
	private int sinceHitDealt = 1000;
	private int sinceHitTaken = 1000;
	private final TakenStreak takenStreak = new TakenStreak();
	/** Hits landed and taken since the last decision. */
	private int dealtSince;
	private int takenSince;

	/**
	 * @param headMask     the controls the policy has learned to use
	 * @param ideasLearned whether its training hands it a {@link MixIdea} (in the stage it is in now);
	 *                     if not, the plan is applied to its actions from outside (when the config
	 *                     allows it)
	 */
	public Fighter(Learner learner, Config cfg, int headMask, boolean ideasLearned, long seed) {
		this(learner, cfg, headMask, ideasLearned, cfg.playTemperature, seed);
	}

	/**
	 * To be told before every decision: whether the game has the opponent under the crosshair and
	 * within reach right now. What the rule about answering a hit goes by; see {@link AnswerRule}.
	 */
	public void crosshairOnOpponent(boolean on) {
		crosshairOnOpponent = on;
	}

	/**
	 * Whether backing out of a combo is a rule for it, as it is in training from the stage on that
	 * keeps the push for S; see {@code Config.backRule}.
	 */
	public void backOutRule(boolean on) {
		backOutRule = on;
	}

	/** Whether the swing of the last decision was made by that rule. Such a swing is only to be carried out if the opponent is still under the crosshair. */
	public boolean answerForced() {
		return answerForced;
	}

	/** The rule's swing was not carried out: by the time of the click the opponent was no longer under the crosshair. */
	public void answerCancelled() {
		answerForced = false;
		action[ActionSpace.ATTACK] = 0;
	}

	/**
	 * The fight is on someone's server: the weapon has to be at least this far back before it swings,
	 * if that is more than the rule already asks; see {@code Config.swingMinChargeOnline}.
	 */
	public void swingNoEarlierThan(float charge) {
		swingNoEarlierThan(charge, 0);
	}

	/**
	 * @param extraTicks and only once the weapon has been all the way back for this many ticks more: for
	 *                   a server that still counts its swings as early; see {@code Config.swingExtraTicksOnline}
	 */
	public void swingNoEarlierThan(float charge, int extraTicks) {
		swingMinCharge = Math.max(swingMinCharge, charge);
		ruleMinCharge = Math.max(ruleMinCharge, swingMinCharge);
		swingExtraTicks = Math.max(0, extraTicks);
	}

	/** The fight is on someone's server: a swing a rule makes waits for at least this; see {@code Config.ruleSwingChargeOnline}. */
	public void rulesNoEarlierThan(float charge) {
		ruleMinCharge = Math.max(ruleMinCharge, charge);
	}

	/**
	 * @param temperature how it picks from the network's choices: 1 is exactly as in training
	 */
	public Fighter(Learner learner, Config cfg, int headMask, boolean ideasLearned, float temperature, long seed) {
		this.learner = learner;
		this.headMask = headMask;
		this.ideasLearned = ideasLearned;
		this.assist = !ideasLearned && cfg.fightMixAssist;
		this.swingMinCharge = cfg.swingMinCharge;
		this.answerSpareBacking = cfg.answerSpareBacking;
		this.ruleMinCharge = Math.max(cfg.swingMinCharge, cfg.ruleSwingCharge);
		this.answerRule = cfg.answerRule;
		this.answerNudge = cfg.answerNudge;
		this.punishRule = cfg.punishRule;
		this.tacticClock = learner.playsTactics() ? new Tactic.Clock() : null;
		boolean tactics = tacticClock != null;
		this.counterCritRule = cfg.counterCritRule;
		this.edgeHoldRule = cfg.edgeHoldRule;
		this.stepBackRule = cfg.stepBackRule;
		this.tacticOnEvents = cfg.tacticOnEvents;
		this.oppWindow = learner.playsReader() ? new OpponentReader.Window() : null;
		boolean jumps = ActionSpace.unlocked(headMask, ActionSpace.JUMP) && ActionSpace.unlocked(headMask, ActionSpace.SPRINT);
		this.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f || cfg.counterCritRule || cfg.edgeCritRule || tactics)
				? new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, cfg.chaseClosingMost, seed ^ 0x5eedL) : null;
		if (jumpIn != null) {
			jumpIn.chaseInCombo(cfg.chaseInCombo);
			jumpIn.approachHops(cfg.hopRule);
			jumpIn.counterCrit(cfg.counterCritRule);
			jumpIn.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
			jumpIn.edgeCrit(cfg.edgeCritRule);
		}
		this.jumpReset = jumps && cfg.jumpResetChance > 0f && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				? new JumpReset(cfg.jumpResetChance, seed ^ 0x7e5e7L) : null;
		if (jumpReset != null) {
			jumpReset.chasers(cfg.jumpResetChasers);
			jumpReset.counters(cfg.jumpResetCounterChance);
		}
		this.punishWait = cfg.punishWaitOnGround;
		this.keepWithin = cfg.keepWithin;
		this.punishInCombo = cfg.punishInCombo;
		this.meetWhenComing = cfg.meetWhenComing;
		this.edgeHold = (cfg.edgeHoldRule || tactics) && ActionSpace.unlocked(headMask, ActionSpace.FORWARD) && ActionSpace.unlocked(headMask, ActionSpace.SPRINT)
				? new EdgeHold() : null;
		this.dodgeJump = cfg.dodgeJumpRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD);
		this.meetRule = cfg.meetJumpRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				&& ActionSpace.unlocked(headMask, ActionSpace.SPRINT) && ActionSpace.unlocked(headMask, ActionSpace.ATTACK);
		this.stepBack = (cfg.stepBackRule || tactics) && ActionSpace.unlocked(headMask, ActionSpace.FORWARD) && ActionSpace.unlocked(headMask, ActionSpace.SPRINT)
				? new StepBack() : null;
		this.farRest = cfg.farRestRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD);
		this.straightIn = cfg.straightInRule && ActionSpace.unlocked(headMask, ActionSpace.STRAFE);
		this.comboStrafe = cfg.comboStrafeRule && ActionSpace.unlocked(headMask, ActionSpace.STRAFE) ? new ComboStrafe() : null;
		this.whiffGuard = cfg.whiffGuard;
		this.afterHit = cfg.aimRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.ATTACK);
		boolean camera = ActionSpace.unlocked(headMask, ActionSpace.YAW) && ActionSpace.unlocked(headMask, ActionSpace.PITCH);
		this.aimRule = (afterHit || farRest || cfg.lostRule) && camera ? new AimRule(cfg.aimResponse) : null;
		if (aimRule != null) {
			aimRule.level(cfg.aimLevel);
		}
		this.lostRule = cfg.lostRule && camera ? new LostRule() : null;
		this.temperature = temperature;
		this.motor = new AimMotor(cfg.aimResponse);
		this.rng = new Random(seed);
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** A new round, or the opponent was gone for a while: no memory, hand at rest, a fresh plan. */
	public void reset() {
		sight.reset();
		motor.reset();
		idea.clear();
		hasAction = false;
		sinceHitDealt = 1000;
		sinceHitTaken = 1000;
		takenStreak.reset();
		dealtSince = 0;
		takenSince = 0;
		ticksBack = 0;
		dealtRow = 0;
		if (jumpIn != null) {
			// The opponent of the next round is the one of the last.
			jumpIn.newRound();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		if (lostRule != null) {
			lostRule.reset();
		}
		fallWatch.reset();
		if (jumpReset != null) {
			jumpReset.newRound();
		}
		if (comboStrafe != null) {
			comboStrafe.reset();
		}
		if (stepBack != null) {
			// The opponent of the next round is the one of the last.
			stepBack.newRound();
		}
		if (edgeHold != null) {
			edgeHold.reset();
		}
		if (tacticClock != null) {
			tacticClock.reset();
		}
		if (oppWindow != null) {
			oppWindow.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back, as its stage's training does or does not; see {@link MixIdea#allowHolds}. */
	public void planHolds(boolean value) {
		idea.allowHolds(value);
	}

	public void hitDealt() {
		sinceHitDealt = 0;
		dealtSince++;
		dealtRow++;
	}

	public void hitTaken() {
		sinceHitTaken = 0;
		takenSince++;
		dealtRow = 0;
	}

	/**
	 * One decision.
	 *
	 * @param frame the newest picture, with the camera angles it was taken at
	 * @param body  the body's state now; the hit clocks are filled in here
	 * @return the action, valid until the next call; turn the camera by {@link #yawRate()} and
	 *         {@link #pitchRate()} rather than by the yaw and pitch heads
	 */
	public int[] decide(MaskFrame frame, BodyState body) {
		Arrays.fill(obs.vec, 0f);
		System.arraycopy(frame.peri, 0, obs.peri, 0, ObsSpec.PERI_SIZE);
		System.arraycopy(frame.fovea, 0, obs.fovea, 0, ObsSpec.FOVEA_SIZE);
		sight.update(frame.stats, frame.viewYaw, frame.viewPitch, body.yaw, body.pitch);
		sight.write(obs.vec);

		body.ticksSinceHitDealt = sinceHitDealt;
		body.ticksSinceHitTaken = sinceHitTaken;
		takenStreak.tick(dealtSince, takenSince);
		dealtSince = 0;
		takenSince = 0;
		body.hitsTakenInRow = takenStreak.shown();
		if (sinceHitDealt < 1000) {
			sinceHitDealt++;
		}
		if (sinceHitTaken < 1000) {
			sinceHitTaken++;
		}
		boolean visible = frame.enemyVisible();
		body.write(obs.vec, visible);
		motor.write(obs.vec);
		idea.tick(rng, body.charge);
		if (ideasLearned) {
			idea.write(obs.vec);
		}
		ObsBuilder.writeLastAction(obs.vec, hasAction ? action : null);
		ObsBuilder.writeHeadMask(obs.vec, headMask);
		// What kind of opponent this is, read off the last seconds of the fight; see OpponentReader.
		if (oppWindow != null) {
			oppWindow.push(obs.vec);
			oppWindow.write(obs.oppWindow);
			learner.readOpponent(obs, false, false);
		}
		// How it fights for the next half second, chosen by the tactics model; see Tactic.
		int tactic = -1;
		if (tacticClock != null) {
			if (tacticClock.due(obs.vec, tacticOnEvents)) {
				tacticClock.set(learner.chooseTactic(obs, false));
			}
			tactic = tacticClock.current();
		}
		obs.tactic = tactic;
		if (jumpIn != null) {
			jumpIn.counterCrit(Tactic.counters(tactic, counterCritRule));
		}

		// The attack key is not the network's while the weapon is not back; see SwingGate. On a server
		// that needs it, not for some ticks after that either.
		ticksBack = body.charge >= 1f ? ticksBack + 1 : 0;
		boolean waiting = swingExtraTicks > 0 && ticksBack <= swingExtraTicks;
		int keys = SwingGate.keys(headMask, waiting ? 0f : body.charge, swingMinCharge);
		// Nor at an opponent plainly out of reach: a swing at nothing; see SwingGate.outOfReach.
		if (whiffGuard && SwingGate.outOfReach(obs.vec)) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		// A swing a rule makes waits for the sword to be all the way back; see Config.ruleSwingCharge.
		boolean ruleReady = !waiting && body.charge >= ruleMinCharge;
		// And it is pressed for it where it owes the opponent an answer and one is there to be made; see AnswerRule.
		boolean owes = answerRule && swingMinCharge > 0f && ruleReady && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent, answerSpareBacking);
		// In the air it is kept for the way down and made as a critical hit; see AnswerRule.inTheAir.
		fallWatch.tick(obs.vec);
		// An opponent coming at it through the air, still on its way up, is run at and hit at once; see MeetJump.
		boolean meet = meetRule && swingMinCharge > 0f && MeetJump.applies(obs.vec, meetWhenComing) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE);
		// On the ground the swing is made at once; in the air it stays the swing it would have been.
		boolean meetSwings = meet && body.onGround;
		int when = owes && punishRule && !meetSwings ? AnswerRule.when(obs.vec, fallWatch.punishable(), punishInCombo && fallWatch.proven(), punishWait, keepWithin) : AnswerRule.NOW;
		boolean answer = owes && when == AnswerRule.NOW;
		boolean keep = owes && when != AnswerRule.NOW;
		boolean letGo = owes && when == AnswerRule.LET_GO;
		answerForced = answer;
		// With Config.answerNudge the rule pushes the network's swing instead of pressing it.
		boolean nudged = owes && answerNudge > 0f;
		if (owes && !nudged) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		// A hit of a combo it is taking: it jumps with it, W and the sprint key down; see JumpReset.
		int reset = jumpReset == null ? 0 : jumpReset.plan(obs.vec, keys, JumpReset.hitOnThisTick(obs.vec));
		if (reset != 0 || letGo) {
			// The jump made with a hit, and the critical hit it is about to make, come first.
			meet = false;
		}
		// Where it does not, S is held in the ticks after that hit; see Config.backRule.
		boolean backOut = backOutRule && reset == 0 && !meet && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		keys = JumpIn.keys(keys, reset);
		// After a hit of its own that has not sent the opponent away it steps back; see StepBack.
		if (stepBack != null && jumpReset != null) {
			stepBack.chasedBy(jumpReset.chased());
		}
		int step = stepBack == null ? 0 : stepBack.plan(obs.vec, tactic == Tactic.BACK);
		if (!Tactic.stepsBack(tactic, stepBackRule)) {
			step = 0;
		}
		// An opponent jumping in at it with its own sword not back: out of the way of that jump; see MeetJump.dodges.
		boolean dodge = dodgeJump && !meet && reset == 0 && !letGo && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.FORWARD)
				&& (jumpIn == null || jumpIn.kind() == JumpIn.NONE) && MeetJump.dodges(obs.vec, meetWhenComing);
		if (dodge) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		// Just out of reach of an opponent whose sword is ready, it does not walk in; see EdgeHold.
		boolean held = edgeHold != null && Tactic.holdsEdge(tactic, edgeHoldRule) && edgeHold.plan(obs.vec);
		boolean hold = held && !owes && !backOut && reset == 0 && !dodge && !meet && !letGo
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)
				&& (jumpIn == null || jumpIn.kind() == JumpIn.NONE);
		if (hold) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		boolean busy = owes || backOut || reset != 0 || dodge || hold;
		// The jumps that are made for it, and its keys in the air of them; see JumpIn.
		int jump = jumpIn == null ? 0 : jumpIn.plan(obs.vec, keys, crosshairOnOpponent, busy || meet, dealtRow);
		keys = JumpIn.keys(keys, jump);
		boolean meetSwing = meet && meetSwings && !owes && ruleReady && crosshairOnOpponent && ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		if (meet) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		if (meetSwing) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (busy || meet || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE
				|| !ActionSpace.unlocked(keys, ActionSpace.FORWARD) || !ActionSpace.unlocked(keys, ActionSpace.SPRINT)) {
			step = 0;
		}
		if (step != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		// And it is steered for it on an opponent that is far away; see FarRest.
		// And in the air of a jump that is made for it: the hit at the end of it needs the crosshair on the opponent.
		boolean hitAim = aimRule != null && afterHit && (AimRule.applies(obs.vec) || jumpIn != null && jumpIn.kind() != JumpIn.NONE);
		// And with the opponent off the screen it is turned to find it; see LostRule.
		boolean lost = lostRule != null && lostRule.applies(obs.vec);
		boolean aim = hitAim || lost || aimRule != null && farRest && FarRest.applies(obs.vec);
		if (aim) {
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim && !lost);
			keys &= ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		}
		// Out of reach it runs straight at the opponent, not round it; see StraightIn.
		boolean straight = straightIn && StraightIn.applies(obs.vec);
		if (straight) {
			keys &= ~ActionSpace.mask(ActionSpace.STRAFE);
		}
		// From the second hit of a combo it is taking, out of its line to the side; see ComboStrafe.
		int aside = comboStrafe == null ? 0 : comboStrafe.plan(obs.vec);
		if (aside != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.STRAFE);
		}
		learner.setSwingBias(nudged ? (answer ? answerNudge : keep ? -answerNudge : 0f) : 0f);
		learner.act(obs, keys, false, false, temperature, action);
		if (nudged) {
			answerForced = answer && action[ActionSpace.ATTACK] == 1;
		}
		if (aim) {
			action[ActionSpace.YAW] = aimRule.yawBin();
			action[ActionSpace.PITCH] = aimRule.pitchBin();
		}
		if (assist) {
			idea.assist(action, headMask, visible);
		}
		if (straight) {
			action[ActionSpace.STRAFE] = 0;
		}
		if (aside != 0) {
			action[ActionSpace.STRAFE] = aside;
		}
		if (answer && !nudged) {
			action[ActionSpace.ATTACK] = 1;
		}
		if (keep && !nudged) {
			action[ActionSpace.ATTACK] = 0;
		}
		JumpIn.apply(action, reset);
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (dodge) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
			action[ActionSpace.SPRINT] = 0;
		}
		if (hold) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (meet) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 1;
		}
		if (meetSwing) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			action[ActionSpace.ATTACK] = 1;
			answerForced = true;
		}
		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerForced = true;
		}
		if (action[ActionSpace.ATTACK] == 1) {
			idea.swung(rng);
		}
		rules = (answer ? 1 : 0) | (keep ? 1 << 1 : 0) | (meet ? 1 << 2 : 0) | (reset != 0 ? 1 << 3 : 0) | (backOut ? 1 << 4 : 0)
				| (step != 0 ? 1 << 5 : 0) | (jump != 0 ? 1 << 6 : 0) | (hitAim ? 1 << 7 : 0) | (lost ? 1 << 8 : 0) | (straight ? 1 << 9 : 0) | (dodge ? 1 << 10 : 0) | (hold ? 1 << 11 : 0);
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		hasAction = true;
		return action;
	}

	/**
	 * Which rules had keys on the last decision, a bit each: answer swing, swing kept for the way
	 * down, run at a jumper, jump reset, backing out, step back, a jump made for it, camera after a
	 * hit, looking for a lost opponent, straight in. For the record of a fight.
	 */
	public int rules() {
		return rules;
	}

	/** The action of the last decision. */
	public int[] action() {
		return action;
	}

	/** Degrees to turn the camera this tick; positive turns right. */
	public float yawRate() {
		return motor.yawRate();
	}

	/** Positive looks down. */
	public float pitchRate() {
		return motor.pitchRate();
	}

	/** Lets go of the camera without forgetting anything else (a pause). */
	public void restHand() {
		motor.reset();
	}

	public int headMask() {
		return headMask;
	}

	public boolean ideasLearned() {
		return ideasLearned;
	}

	public boolean assists() {
		return assist;
	}
}
