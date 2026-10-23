package dev.swordai.core.school;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.TakenStreak;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.MixIdea;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.SightTracker;
import dev.swordai.core.rl.SwingGate;
import dev.swordai.core.vision.MaskFrame;

import java.util.Arrays;

/**
 * The network of the technique school playing a fight that is not training: the user's fights on a
 * server, and its side of a paired evaluation. It sees exactly what it sees in training (the picture,
 * its own body, the memory of the fight that the caller keeps, see {@link #decide}), and nothing
 * presses a key for it: the one limit kept is the game's own, no swing before the sword is back
 * ({@link SwingGate}).
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
		learner.act(step, keys, false, temperature, action);
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
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
