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
		takenStreak.reset();
		dealtSince = 0;
		takenSince = 0;
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	private boolean whiffGuard = true;
	private boolean crosshairOn;
	private Fighter rules;
	private boolean swingHeld;
	/**
	 * Within this many ticks of its own hit, an opponent in the air is its combo's (lifted by that hit), not one jumping in:
	 * the golden rule's guard stays off there. The user, 25 Oct 09:25: the guard made it forget to combo (its hits stunned 16%
	 * to the installed model's 24%, sprint hits 48% to 83% in the 09:11 check).
	 */
	public static final int K2_COMBO_TICKS = 15;

	/**
	 * The installed model's fight-time rules over this network: on a tick one of its rules sets keys, those keys are the
	 * rule's. The caller has {@code rules} decide each tick before {@link #decide}; this keeps it on the keys really pressed.
	 */
	public void rulesFrom(Fighter rules) {
		this.rules = rules;
	}

	/** No swing this tick: a sprint hit would land on one falling by the time it gets there (the golden rule, K2). */
	public void holdSwing(boolean hold) {
		swingHeld = hold;
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
	}

	public void hitTaken() {
		sinceHitTaken = 0;
		takenSince++;
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
		if (swingHeld && sinceHitDealt > K2_COMBO_TICKS) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		int ruled = rules != null ? rules.ruledHeads() & keys : 0;
		// The rules give way to a technique it learned while the decider has chosen it (SchoolStages#RULES_YIELD).
		if (ruled != 0 && SchoolStages.RULES_YIELD && SchoolStages.runTaught(learner.told().ordinal())) {
			ruled &= ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
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
		learner.act(step, keys, ruled, false, temperature, action);
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
