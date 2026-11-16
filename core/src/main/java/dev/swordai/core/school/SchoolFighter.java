package dev.swordai.core.school;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.TakenStreak;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.Fighter;
import dev.swordai.core.rl.MixIdea;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.SightTracker;
import dev.swordai.core.rl.SwingGate;
import dev.swordai.core.vision.MaskFrame;

import java.util.Arrays;

/**
 * The network of the technique school playing a fight that is not training: the user's fights on a
 * server, and its side of a paired evaluation. It sees exactly what it sees in training (the picture,
 * its own body, the memory of the fight that the caller keeps, see {@link #decide}). Limits kept: the
 * game's own, no swing before the sword is back ({@link SwingGate}), no swing without the opponent
 * under the crosshair, the golden rule (K2, {@link #holdSwing}) and, where given, the installed
 * model's fight-time rules ({@link #rulesFrom}), as in the school's training and checks since 25 Oct.
 */
public final class SchoolFighter {
	private final SchoolLearner learner;
	private final float swingMinCharge;
	private final float temperature;
	private final SchoolRollout.Step step = new SchoolRollout.Step();
	private final SightTracker sight = new SightTracker();
	private final AimMotor motor;
	private final MixIdea idea = new MixIdea();
	private final int[] action = new int[ActionSpace.HEADS];
	private boolean hasAction;
	private int sinceHitDealt = 1000;
	private int sinceHitTaken = 1000;
	/** Their hits in a row since its last (run 88). */
	private int theirRow;
	/** Run 88 in the user's game: an A/B arm with the option "airyield" (see SchoolSession.AIR_KEEP_YIELD). */
	private boolean airKeepYield;
	private final TakenStreak takenStreak = new TakenStreak();
	/** Its combo jump resets mistimed now and then, as a person's are. */
	private final FairJump fairJump = new FairJump(System.nanoTime());
	private int dealtSince;
	private int takenSince;

	public SchoolFighter(SchoolLearner learner, float aimResponse, float swingMinCharge, float temperature) {
		this.learner = learner;
		this.motor = new AimMotor(aimResponse);
		this.swingMinCharge = swingMinCharge;
		this.temperature = temperature;
		reset();
	}

	public void reset() {
		learner.resetIntent();
		fairJump.reset();
		sight.reset();
		motor.reset();
		idea.clear();
		hasAction = false;
		sinceHitDealt = 1000;
		sinceHitTaken = 1000;
		theirRow = 0;
		takenStreak.reset();
		dealtSince = 0;
		takenSince = 0;
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	private boolean whiffGuard = true;
	private boolean crosshairOn;
	private Fighter rules;
	private boolean swingHeld;
	private boolean jumpInHeld;
	/**
	 * Within this many ticks of its own hit, an opponent in the air is its combo's (lifted by that hit), not one jumping in:
	 * the golden rule's guard stays off there. The user, 25 Oct 09:25: the guard made it forget to combo (its hits stunned 16%
	 * to the installed model's 24%, sprint hits 48% to 83% in the 09:11 check).
	 */
	public static final int K2_COMBO_TICKS = 15;
	/** Run 62: its own combo, as the school counts it (SchoolSession.COMBO_GAP). */
	static final int OWN_COMBO_TICKS = 20;

	/**
	 * The installed model's fight-time rules over this network: on a tick one of its rules sets keys, those keys are the
	 * rule's. The caller has {@code rules} decide each tick before {@link #decide}; this keeps it on the keys really pressed.
	 */
	public void rulesFrom(Fighter rules) {
		this.rules = rules;
	}

	private boolean rulesLetGo;

	/**
	 * This tick the installed model's rules leave W and the sprint to the network: lifted by their hit and coming down near them
	 * with the sword coming back, as in the school's training since run 12 (SchoolSession#RULES_LET_GO_IN_LIFT).
	 */
	public void rulesLetGo(boolean on) {
		rulesLetGo = on;
	}

	private boolean inLift;

	/** Thrown up by their hit and not down yet: told the punish crit there, the rules leave it its keys (SchoolStages#PUNISH_TOLD_YIELD). */
	public void inLift(boolean on) {
		inLift = on;
	}

	/** No swing this tick: a sprint hit would land on one falling by the time it gets there (the golden rule, K2). */
	public void holdSwing(boolean hold) {
		swingHeld = hold;
	}

	/**
	 * No swing this tick, even within K2_COMBO_TICKS of its own hit: a jump-in's swing held until they land (run 40). Its hit in
	 * that jump was on them rising, which is no combo of its own: the rest of the jump they come down at it.
	 */
	public void holdJumpInSwing(boolean hold) {
		jumpInHeld = hold;
	}

	/** Whether the game has the opponent under the crosshair and in reach now. */
	public void crosshairOnOpponent(boolean on) {
		crosshairOn = on;
	}

	/** Whether a swing at an opponent plainly out of reach is kept back (Config.whiffGuard). */
	public void whiffGuard(boolean on) {
		whiffGuard = on;
	}

	public void hitDealt() {
		sinceHitDealt = 0;
		dealtSince++;
		theirRow = 0;
	}

	public void hitTaken() {
		sinceHitTaken = 0;
		takenSince++;
		theirRow++;
	}

	/** Run 88: from their 2nd hit in a row, in the air, the rules' keep of the swing for the way down is the network's. */
	public void airKeepYield(boolean on) {
		airKeepYield = on;
	}

	/**
	 * Run 108 (the user, 15 Nov ~12:00: "dont jump reset unless you are certain and have a rate above of 95%"; 21:37: "install the current
	 * best and let me test it"). Since run 104c the network learns its own jump resets T9a/T9b/T9c in training (83%, 63%, 68% done at
	 * 21:19), under the user's 95%. In the user's duels, while the decider has chosen one of them: the rules do not give way (their
	 * reactive jump reset, right in 95% of real rounds, and the late-jump guard stay), and a jump of the network's own is let go of.
	 * FightSession only (the user's duels); off for the training bots.
	 */
	private boolean ownJumpResetGate;
	public long ownJumpResetsStripped;

	public void ownJumpResetGate(boolean on) {
		ownJumpResetGate = on;
	}

	/**
	 * Run 122 (agent 56; the user, 16 Nov 22:05, after a real test against human players: "The bot always get a sprint hit stun and then jump
	 * for no reason ... every time it is close to opponent it finally get a stun and then jump and make other combo them"; 22:25: "punish
	 * like 100000 if it jump while doing stun that makes absolute zero sense at all"). OWN_HIT_JUMP_GUARD: after its own hit, with no hit
	 * taken since, for up to OWN_HIT_WINDOW ticks, on the ground, within OWN_HIT_GUARD_REACH blocks and with its own sword under
	 * OWN_HIT_GUARD_CHARGE, the NETWORK's jump key is let go of (no take-off). A jump a rule pressed (the jump reset on their hit, rule r,
	 * comes after a hit taken, outside the window; J and the others) is never touched, nor the swing, the K2 and jump-in guards or the
	 * punish crit (B1 follows their hit: outside the window too).
	 *
	 * <p>The real rounds, every tick (profile fights; checked-1116-2048 against run35, the user's "near perfect"): take-offs in its own combo
	 * in lost rounds 9.5 vs 4.7 a minute, hit in that jump 55% vs 36%; of the new network's 20: 12 came 0-3 ticks after its hit, 14 with
	 * its sword under 0.9, 10 under 2 blocks, 14 pressed by the network alone (run35: 0, 4, 0, 2 of 14). Typical frame: a crit from the air
	 * lands, it lands 1.4-1.9 blocks from them, and 1-3 ticks later the network jumps with its sword at 0.32 while theirs reads 0.80; their
	 * sprint hit (C1) catches it in the air and it flies 5-8 blocks. The user's game (FightSession), the paired checks' side A and training
	 * alike (SchoolSession); the 23 Oct reference (B of the checks) untouched.
	 */
	public static final int OWN_HIT_WINDOW = 20;
	public static final float OWN_HIT_GUARD_REACH = 3.5f;
	public static final float OWN_HIT_GUARD_CHARGE = 0.9f;

	/** Run 122: in the window after its own hit: that hit at most OWN_HIT_WINDOW ticks old and no hit taken since (as SchoolSession's own combo). */
	public static boolean ownHitWindow(int sinceHitDealt, int sinceHitTaken) {
		return sinceHitDealt <= OWN_HIT_WINDOW && sinceHitTaken > sinceHitDealt;
	}

	/** Run 122: whether the guard lets go of a jump of the network's own now (see OWN_HIT_WINDOW). */
	public static boolean ownHitJumpGuard(int sinceHitDealt, int sinceHitTaken, boolean onGround, double reach, float charge) {
		return ownHitWindow(sinceHitDealt, sinceHitTaken) && onGround && reach <= OWN_HIT_GUARD_REACH && charge < OWN_HIT_GUARD_CHARGE;
	}

	private boolean ownHitJumpGuard;
	private double reachNow = Double.MAX_VALUE;
	/** Run 122: the network's jumps the guard let go of, and its jumps tried in the window at all (stripped or not). */
	public long ownHitJumpsStripped;
	public long ownHitJumpsTried;

	/** Run 122: the own-hit jump guard on or off (FightSession.OWN_HIT_JUMP_GUARD in the user's game). */
	public void ownHitJumpGuard(boolean on) {
		ownHitJumpGuard = on;
	}

	/** Run 122: eye to their hitbox this tick, for the guard (unknown: Double.MAX_VALUE, and the guard holds nothing). */
	public void reachNow(double reach) {
		reachNow = reach;
	}

	/** Run 124: an outside rule's heads and keys for the next {@link #decide} only (FightSession's CounterSpacing); see {@link #outsideRule}. */
	private int outsideHeads;
	private final int[] outsideKeys = new int[ActionSpace.HEADS];
	/** Run 124: decisions where an outside rule pressed keys (counter). */
	public long outsideTicks;

	/**
	 * Run 124 (CounterSpacing in the user's duels): for the next decision only, these heads ({@link ActionSpace} bits; never the jump or
	 * the camera) are pressed as {@code keys} has them, over the installed model's rules and the network. The swing is pressed only where it
	 * was open (the sword gate, the whiff guard, K2 and the jump-in hold). 0 heads: nothing.
	 */
	public void outsideRule(int heads, int[] keys) {
		outsideHeads = heads;
		if (heads != 0) {
			System.arraycopy(keys, 0, outsideKeys, 0, ActionSpace.HEADS);
		}
	}

	private static boolean jumpResetTold(dev.swordai.core.pvp.Technique t) {
		return t == dev.swordai.core.pvp.Technique.T9A || t == dev.swordai.core.pvp.Technique.T9B || t == dev.swordai.core.pvp.Technique.T9C;
	}

	/**
	 * One decision.
	 *
	 * @param mem the memory of this fight, as {@code Habits.write} writes it
	 */
	public int[] decide(MaskFrame frame, BodyState body, float[] mem) {
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
		ObsBuilder.writeActor(step.obs.base, frame, sight, body, motor, idea, hasAction ? action : null, ActionSpace.ALL_HEADS);
		System.arraycopy(mem, 0, step.obs.mem, 0, SchoolObs.MEM);
		int keys = SwingGate.keys(ActionSpace.ALL_HEADS, body.charge, swingMinCharge);
		// As the installed model: no swing at an opponent plainly out of reach or off the screen (Config.whiffGuard), unless
		// the game has them under the crosshair (the distance read off the screen is rough).
		// Since 24 Oct 22:15 the game's pick alone: no swing without them under the crosshair in reach (the on-screen distance
		// let through swings at an opponent aimed at but out of reach in the user's real fights).
		if (whiffGuard && !crosshairOn) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (swingHeld && sinceHitDealt > K2_COMBO_TICKS || jumpInHeld) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		int ruled = rules != null ? rules.ruledHeads() & keys : 0;
		if (rulesLetGo) {
			ruled &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		if (SchoolStages.PUNISH_TOLD_YIELD && inLift && learner.told() == dev.swordai.core.pvp.Technique.B1) {
			ruled &= ~SchoolStages.PUNISH_KEYS;
		}
		// Run 108: a jump reset of its own is not played in the user's duels (ownJumpResetGate): the rules keep their keys.
		boolean jrGate = ownJumpResetGate && jumpResetTold(learner.told());
		// The rules give way to a technique it learned while the decider has chosen it (SchoolStages#RULES_YIELD).
		if (ruled != 0 && !jrGate && SchoolStages.RULES_YIELD && sinceHitTaken > SchoolStages.YIELD_AFTER_HIT
				&& SchoolStages.runTaught(learner.told().ordinal()) && !(SchoolStages.STARTERS_TAUGHT && SchoolStages.starter(learner.told().ordinal()))) {
			ruled &= ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		}
		// Run 62: in its own combo, while the decider has chosen an uppercut, a lunge or hops, those keys are the network's.
		if (ruled != 0 && SchoolStages.COMBO_JUMPS_TAUGHT && SchoolStages.COMBO_JUMP_YIELD_IN_FIGHTS && SchoolStages.comboJump(learner.told().ordinal())
				&& sinceHitDealt <= OWN_COMBO_TICKS && sinceHitTaken > sinceHitDealt) {
			ruled &= ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		}
		// Run 65: in their combo, while the decider has chosen the jump reset (T9a) or the hit before the S (C4).
		if (ruled != 0 && !jrGate && SchoolStages.COMBO_ESCAPE_TAUGHT && SchoolStages.comboEscape(learner.told().ordinal())
				&& sinceHitTaken <= OWN_COMBO_TICKS && sinceHitDealt > sinceHitTaken) {
			ruled &= ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		}
		// Run 88 (SchoolSession.AIR_KEEP_YIELD), for an arm trained with it: from their 2nd hit, in the air, the keep is the network's.
		if (airKeepYield && theirRow >= 2 && !body.onGround && sinceHitTaken <= OWN_COMBO_TICKS && sinceHitDealt > sinceHitTaken
				&& (ruled & ActionSpace.mask(ActionSpace.ATTACK)) != 0 && (rules.rules() & 2) != 0 && rules.action()[ActionSpace.ATTACK] == 0) {
			ruled &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		keys &= ~ruled;
		if (ruled != 0) {
			int[] rule = rules.action();
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if ((ruled & (1 << h)) != 0) {
					action[h] = rule[h];
				}
			}
		}
		// Run 124 (CounterSpacing): an outside rule's keys for this tick, over the rules' and the network's; the swing only where it was open.
		int outside = outsideHeads & (keys | ruled) & ~ActionSpace.mask(ActionSpace.JUMP, ActionSpace.YAW, ActionSpace.PITCH);
		outsideHeads = 0;
		if (outside != 0) {
			ruled |= outside;
			keys &= ~outside;
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if ((outside & (1 << h)) != 0) {
					action[h] = outsideKeys[h];
				}
			}
			outsideTicks++;
		}
		learner.act(step, keys, ruled, false, temperature, action);
		// The decider may choose anew in act(): the jump reset told before or after it.
		if ((jrGate || ownJumpResetGate && jumpResetTold(learner.told())) && (ruled & ActionSpace.mask(ActionSpace.JUMP)) == 0
				&& action[ActionSpace.JUMP] == 1) {
			action[ActionSpace.JUMP] = 0;
			ownJumpResetsStripped++;
		}
		// Run 122: no take-off of the network's own right after its own hit while close with its sword not back (OWN_HIT_WINDOW).
		if (ownHitJumpGuard && (ruled & ActionSpace.mask(ActionSpace.JUMP)) == 0 && action[ActionSpace.JUMP] == 1 && body.onGround
				&& ownHitWindow(sinceHitDealt, sinceHitTaken)) {
			ownHitJumpsTried++;
			if (ownHitJumpGuard(sinceHitDealt, sinceHitTaken, body.onGround, reachNow, body.charge)) {
				action[ActionSpace.JUMP] = 0;
				ownHitJumpsStripped++;
			}
		}
		fairJump.apply(action, body.onGround, sinceHitTaken, takenStreak.hits());
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		if (rules != null) {
			rules.pressed(action, motor);
		}
		hasAction = true;
		return action;
	}

	public int[] action() {
		return action;
	}

	public float yawRate() {
		return motor.yawRate();
	}

	public float pitchRate() {
		return motor.pitchRate();
	}

	public void restHand() {
		motor.reset();
	}

	/** For a fight against nobody yet: nothing held. */
	public void release() {
		Arrays.fill(action, 0);
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}
}
