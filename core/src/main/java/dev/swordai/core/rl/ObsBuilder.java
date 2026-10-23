package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.vision.MaskFrame;

import java.util.Arrays;

/** Packs the pieces of an observation into the layout declared in {@link ObsSpec}. */
public final class ObsBuilder {
	private ObsBuilder() {
	}

	/** Copies the masks and writes the statistics of the current and previous frame. */
	public static void writeVision(Obs obs, MaskFrame current, float[] previousStats) {
		System.arraycopy(current.peri, 0, obs.peri, 0, ObsSpec.PERI_SIZE);
		System.arraycopy(current.fovea, 0, obs.fovea, 0, ObsSpec.FOVEA_SIZE);
		writeStats(obs.vec, current.stats, previousStats);
	}

	public static void writeStats(float[] vec, float[] currentStats, float[] previousStats) {
		System.arraycopy(currentStats, 0, vec, ObsSpec.VEC_STATS, ObsSpec.STATS);
		System.arraycopy(previousStats, 0, vec, ObsSpec.VEC_STATS + ObsSpec.STATS, ObsSpec.STATS);
	}

	public static void writeScalar(float[] vec, int scalar, float value) {
		vec[ObsSpec.VEC_SCALARS + scalar] = value;
	}

	/** One-hot of the action taken on the previous step; all zero when {@code action} is null. */
	public static void writeLastAction(float[] vec, int[] action) {
		Arrays.fill(vec, ObsSpec.VEC_LAST_ACTION, ObsSpec.VEC_LAST_ACTION + ActionSpace.TOTAL_LOGITS, 0f);
		if (action == null) {
			return;
		}
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			vec[ObsSpec.VEC_LAST_ACTION + ActionSpace.OFFSETS[h] + action[h]] = 1f;
		}
	}

	/** Which controls the agent currently has. */
	public static void writeHeadMask(float[] vec, int headMask) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			vec[ObsSpec.VEC_HEAD_MASK + h] = ActionSpace.unlocked(headMask, h) ? 1f : 0f;
		}
	}

	/**
	 * Writes everything the acting network gets for one decision: the picture, where the opponent is
	 * on it (shifted by however far the camera has turned since the picture was taken), its own body,
	 * the camera hand, its plan, its last action and its controls. The same for a body on the screen
	 * and a body without one; only how {@code frame} and {@code body} were filled differs.
	 *
	 * <p>Call once per decision: the sight tracker remembers the last sighting.
	 *
	 * @param body       its own state now, with the hit clocks set
	 * @param lastAction the action taken on the previous tick, or null at the start of an episode
	 */
	public static void writeActor(Obs obs, MaskFrame frame, SightTracker sight, BodyState body, AimMotor motor, MixIdea idea,
			int[] lastAction, int headMask) {
		Arrays.fill(obs.vec, 0f);
		System.arraycopy(frame.peri, 0, obs.peri, 0, ObsSpec.PERI_SIZE);
		System.arraycopy(frame.fovea, 0, obs.fovea, 0, ObsSpec.FOVEA_SIZE);
		sight.update(frame.stats, frame.viewYaw, frame.viewPitch, body.yaw, body.pitch);
		sight.write(obs.vec);
		// Relative velocity is something a player reads off the screen, so it is only written while
		// the opponent is actually on screen.
		body.write(obs.vec, frame.enemyVisible());
		motor.write(obs.vec);
		idea.write(obs.vec);
		writeLastAction(obs.vec, lastAction);
		writeHeadMask(obs.vec, headMask);
	}

	/**
	 * Writes what only the critic gets: ground truth about the opponent. Never reaches the acting network.
	 *
	 * @param relRight,relUp,relForward the opponent's eyes relative to the agent's, in the agent's horizontal view frame, blocks
	 * @param inReach                   the crosshair is on the opponent and it is within reach
	 */
	public static void writePrivileged(float[] p, StepFacts facts, BodyState body, float relRight, float relUp, float relForward,
			float botHealth, float botCharge, boolean botSprinting, boolean botOnGround, boolean inReach, SessionClock clock, int stageIndex) {
		Arrays.fill(p, 0f);
		p[ObsSpec.PV_YAW_ERR] = facts.yawErrDeg / 180f;
		p[ObsSpec.PV_PITCH_ERR] = facts.pitchErrDeg / 90f;
		p[ObsSpec.PV_REACH_DIST] = Math.min(2f, facts.reachDist / 8f);
		p[ObsSpec.PV_REL_RIGHT] = relRight / 8f;
		p[ObsSpec.PV_REL_UP] = relUp / 8f;
		p[ObsSpec.PV_REL_FORWARD] = relForward / 8f;
		p[ObsSpec.PV_BOT_VEL_RIGHT] = body.oppVelRight * BodyState.VELOCITY_SCALE;
		p[ObsSpec.PV_BOT_VEL_UP] = body.oppVelUp * BodyState.VELOCITY_SCALE;
		p[ObsSpec.PV_BOT_VEL_FORWARD] = body.oppVelForward * BodyState.VELOCITY_SCALE;
		p[ObsSpec.PV_BOT_HEALTH] = botHealth / 20f;
		p[ObsSpec.PV_BOT_CHARGE] = botCharge;
		p[ObsSpec.PV_BOT_SPRINTING] = botSprinting ? 1f : 0f;
		p[ObsSpec.PV_BOT_ON_GROUND] = botOnGround ? 1f : 0f;
		p[ObsSpec.PV_ON_HEAD] = facts.onHead ? 1f : 0f;
		p[ObsSpec.PV_ON_BODY] = facts.onBody ? 1f : 0f;
		p[ObsSpec.PV_IN_REACH] = inReach ? 1f : 0f;
		p[ObsSpec.PV_EPISODE_TIME] = clock.episodeFraction;
		p[ObsSpec.PV_BOT_SKILL] = clock.botSkill;
		p[ObsSpec.PV_COMBO] = Math.min(5, clock.combo) / 5f;
		p[ObsSpec.PV_BOT_ATTACKS] = clock.botAttacks ? 1f : 0f;
		writeStage(p, stageIndex);
	}

	/**
	 * Which task is being played, for the critic. The first eight stages are a one-hot; the ninth is
	 * no slot set and the tenth is every slot set, which keeps all ten apart in eight slots.
	 */
	public static void writeStage(float[] priv, int stageIndex) {
		boolean all = stageIndex > ObsSpec.PV_STAGE_SLOTS;
		Arrays.fill(priv, ObsSpec.PV_STAGE, ObsSpec.PV_STAGE + ObsSpec.PV_STAGE_SLOTS, all ? 1f : 0f);
		if (stageIndex >= 0 && stageIndex < ObsSpec.PV_STAGE_SLOTS) {
			priv[ObsSpec.PV_STAGE + stageIndex] = 1f;
		}
	}
}
