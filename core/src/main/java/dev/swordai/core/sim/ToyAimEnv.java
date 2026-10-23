package dev.swordai.core.sim;

import dev.swordai.core.curriculum.BotProfile;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.SightTracker;
import dev.swordai.core.vision.ViewGeometry;

import java.util.Arrays;
import java.util.Random;

/**
 * A stand-in for the game that needs no Minecraft: a camera that can turn, and a target that sits
 * somewhere around it and occasionally hops. It produces the same mask statistics and ground truth
 * the mod does, which is enough to prove the learner end to end. It does not model movement or combat.
 *
 * <p>Like the real game it can show the agent an image that is one tick old, i.e. taken before the
 * agent's latest camera move.
 */
public final class ToyAimEnv {
	private static final float HEAD = 0.5f;
	private static final float BODY_WIDTH = 0.6f;
	private static final float BODY_BELOW_HEAD_CENTRE = 1.55f;

	private final Random rng;
	private final StageSpec stage;
	private final int stageIndex;
	private final int frameDelay;
	private final boolean useSightTracker;
	private final SightTracker sight = new SightTracker();
	private final AimMotor motor;

	private float yaw;
	private float pitch;
	private float targetYaw;
	private float targetPitch;
	private float distance;
	private float strafeDegPerTick;
	private int sinceHop;
	private boolean justHopped;

	// Ring of rendered frames, newest at 'head'.
	private final float[][] frameStats;
	private final float[] frameYaw;
	private final float[] framePitch;
	private int head;

	private final float[] previousShown = new float[ObsSpec.STATS];
	private final int[] lastAction = new int[ActionSpace.HEADS];
	private boolean hasLastAction;
	private final float[] view = new float[3];

	public ToyAimEnv(StageSpec stage, int stageIndex, long seed) {
		this(stage, stageIndex, seed, 0, true);
	}

	public ToyAimEnv(StageSpec stage, int stageIndex, long seed, int frameDelay, boolean useSightTracker) {
		this(stage, stageIndex, seed, frameDelay, useSightTracker, 0.5f);
	}

	/**
	 * @param frameDelay      0 shows the current view; 1 shows the view from before the latest action
	 * @param useSightTracker false feeds the raw statistics with no camera compensation and no memory,
	 *                        to measure what those two are worth
	 * @param aimResponse     see {@link AimMotor}; 1 turns the camera by exactly what the action says
	 */
	public ToyAimEnv(StageSpec stage, int stageIndex, long seed, int frameDelay, boolean useSightTracker, float aimResponse) {
		this.stage = stage;
		this.stageIndex = stageIndex;
		this.rng = new Random(seed);
		this.frameDelay = frameDelay;
		this.useSightTracker = useSightTracker;
		this.motor = new AimMotor(aimResponse);
		int n = frameDelay + 1;
		this.frameStats = new float[n][ObsSpec.STATS];
		this.frameYaw = new float[n];
		this.framePitch = new float[n];
		reset();
	}

	public void reset() {
		yaw = rng.nextFloat() * 360f - 180f;
		pitch = stage.unlocked(ActionSpace.PITCH) ? (rng.nextFloat() * 2f - 1f) * stage.startPitchRangeDeg : 0f;
		hop();
		for (int i = 0; i < frameStats.length; i++) {
			head = i;
			render();
		}
		sight.reset();
		motor.reset();
		Arrays.fill(previousShown, 0f);
		hasLastAction = false;
	}

	/** The camera's heading, for tests and traces. */
	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	private void hop() {
		float bearing = (rng.nextFloat() * 2f - 1f) * stage.bot.maxBearingDeg;
		targetYaw = ViewGeometry.wrapDegrees(yaw + bearing);
		targetPitch = stage.unlocked(ActionSpace.PITCH) ? (rng.nextFloat() * 2f - 1f) * 25f : 0f;
		distance = stage.bot.minDistance + rng.nextFloat() * (stage.bot.maxDistance - stage.bot.minDistance);
		boolean moves = !BotProfile.STAND.equals(stage.bot.movement);
		strafeDegPerTick = moves ? (rng.nextBoolean() ? 1f : -1f) * (0.3f + rng.nextFloat() * 0.9f) : 0f;
		sinceHop = 0;
		justHopped = true;
	}

	/** Builds the observation for the current moment. Call exactly once per step (and once after a reset). */
	public void observe(Obs obs) {
		Arrays.fill(obs.peri, (byte) 0);
		Arrays.fill(obs.fovea, (byte) 0);
		Arrays.fill(obs.vec, 0f);
		Arrays.fill(obs.priv, 0f);

		int shown = Math.floorMod(head - frameDelay, frameStats.length);
		if (useSightTracker) {
			sight.update(frameStats[shown], frameYaw[shown], framePitch[shown], yaw, pitch);
			sight.write(obs.vec);
		} else {
			ObsBuilder.writeStats(obs.vec, frameStats[shown], previousShown);
			System.arraycopy(frameStats[shown], 0, previousShown, 0, ObsSpec.STATS);
		}
		ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_PITCH, pitch / 90f);
		ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_ON_GROUND, 1f);
		ObsBuilder.writeScalar(obs.vec, ObsSpec.SC_HEALTH, 1f);
		motor.write(obs.vec);
		ObsBuilder.writeLastAction(obs.vec, hasLastAction ? lastAction : null);
		ObsBuilder.writeHeadMask(obs.vec, stage.headMask);

		obs.priv[ObsSpec.PV_YAW_ERR] = ViewGeometry.wrapDegrees(targetYaw - yaw) / 180f;
		obs.priv[ObsSpec.PV_PITCH_ERR] = (targetPitch - pitch) / 90f;
		obs.priv[ObsSpec.PV_REACH_DIST] = distance / 8f;
		ObsBuilder.writeStage(obs.priv, stageIndex);
	}

	/** Applies an action, advances one tick and reports what happened. */
	public void step(int[] action, StepFacts facts) {
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		float dYaw = motor.yawRate();
		float pitchBefore = pitch;
		yaw = ViewGeometry.wrapDegrees(yaw + dYaw);
		pitch = Math.max(-90f, Math.min(90f, pitch + motor.pitchRate()));
		float dPitch = pitch - pitchBefore;

		justHopped = false;
		targetYaw = ViewGeometry.wrapDegrees(targetYaw + strafeDegPerTick);
		sinceHop++;
		if (stage.bot.hopIntervalTicks > 0 && sinceHop >= stage.bot.hopIntervalTicks) {
			hop();
		}

		head = (head + 1) % frameStats.length;
		render();
		float[] stats = frameStats[head];

		facts.clear();
		facts.yawErrDeg = ViewGeometry.wrapDegrees(targetYaw - yaw);
		facts.pitchErrDeg = targetPitch - pitch;
		double[] d = direction(targetYaw, targetPitch);
		float headErr = ViewGeometry.angleTo(d[0], d[1], d[2], yaw, pitch);
		float headHalfAngle = angle(HEAD * 0.5f);
		facts.yawOnHead = Math.abs(facts.yawErrDeg) <= headHalfAngle;
		facts.onHead = headErr <= headHalfAngle;
		// The hitbox as angles around the head centre: half the body width to either side, half a head
		// above it and the rest of the body below (positive pitch looks down).
		facts.yawMissDeg = Math.max(0f, Math.abs(facts.yawErrDeg) - angle(BODY_WIDTH * 0.5f));
		float overTheTop = facts.pitchErrDeg - headHalfAngle;
		float underTheFeet = -facts.pitchErrDeg - angle(BODY_BELOW_HEAD_CENTRE);
		float pitchMiss = Math.max(0f, Math.max(overTheTop, underTheFeet));
		facts.missDeg = (float) Math.hypot(facts.yawMissDeg, pitchMiss);
		facts.onBody = facts.missDeg <= 0f;
		facts.enemyVisible = stats[ObsSpec.ST_ANY_VISIBLE] > 0f;
		facts.reachDist = distance - BODY_WIDTH * 0.5f;
		facts.discontinuity = justHopped;
		facts.yawTurnDeg = dYaw;
		facts.pitchTurnDeg = dPitch;
		facts.agentPitchDeg = pitch;
	}

	/** Renders the current view into the ring slot at {@code head}. */
	private void render() {
		float[] s = frameStats[head];
		frameYaw[head] = yaw;
		framePitch[head] = pitch;
		Arrays.fill(s, 0f);
		double[] d = direction(targetYaw, targetPitch);
		ViewGeometry.toView(d[0] * distance, d[1] * distance, d[2] * distance, yaw, pitch, view);
		float x = ViewGeometry.screenX(view[0], view[2]);
		float y = ViewGeometry.screenY(view[1], view[2]);
		if (Float.isNaN(x) || Math.abs(x) > ObsSpec.ASPECT || Math.abs(y) > 1f) {
			return;
		}
		float unit = ViewGeometry.apparentSize(1f, view[2]);
		s[ObsSpec.ST_HEAD_VISIBLE] = 1f;
		s[ObsSpec.ST_HEAD_X] = x;
		s[ObsSpec.ST_HEAD_Y] = y;
		s[ObsSpec.ST_HEAD_AREA] = HEAD * unit * 0.5f;
		ObsSpec.deriveStats(s);
		s[ObsSpec.ST_ANY_VISIBLE] = 1f;
		s[ObsSpec.ST_ANY_X] = x;
		s[ObsSpec.ST_ANY_Y] = y - 0.65f * unit;
		s[ObsSpec.ST_LEFT] = x - BODY_WIDTH * 0.5f * unit;
		s[ObsSpec.ST_RIGHT] = x + BODY_WIDTH * 0.5f * unit;
		s[ObsSpec.ST_TOP] = y + HEAD * 0.5f * unit;
		s[ObsSpec.ST_BOTTOM] = y - BODY_BELOW_HEAD_CENTRE * unit;
		s[ObsSpec.ST_ANY_AREA] = (float) Math.sqrt(BODY_WIDTH * 1.8f) * unit * 0.5f;
	}

	/** The angle, in degrees, that a length at the target's distance covers. */
	private float angle(float blocks) {
		return (float) Math.toDegrees(Math.atan2(blocks, distance));
	}

	/** Unit vector for a Minecraft yaw/pitch. */
	private static double[] direction(float yawDeg, float pitchDeg) {
		double yw = Math.toRadians(yawDeg);
		double pt = Math.toRadians(pitchDeg);
		return new double[] {-Math.sin(yw) * Math.cos(pt), -Math.sin(pt), Math.cos(yw) * Math.cos(pt)};
	}
}
