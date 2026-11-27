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

	/** Writes everything the acting network gets for one decision (slots & terrain are filled by the caller). */
	public static void writeActor(Obs obs, MaskFrame frame, SightTracker sight, BodyState body, AimMotor motor,
			int[] lastAction, int headMask) {
		Arrays.fill(obs.vec, 0f);
		System.arraycopy(frame.peri, 0, obs.peri, 0, ObsSpec.PERI_SIZE);
		System.arraycopy(frame.fovea, 0, obs.fovea, 0, ObsSpec.FOVEA_SIZE);
		sight.update(frame.stats, frame.viewYaw, frame.viewPitch, body.yaw, body.pitch);
		sight.write(obs.vec);
		body.write(obs.vec, frame.enemyVisible());
		motor.write(obs.vec);
		writeLastAction(obs.vec, lastAction);
		writeHeadMask(obs.vec, headMask);
	}

	/** Writes training-only privileged critic inputs (never reaches the acting network). */
	public static void writePrivileged(float[] p, StepFacts facts, BodyState body, float relRight, float relUp, float relForward,
			float botHealth, float botCharge, boolean botSprinting, boolean botOnGround, boolean inReach, SessionClock clock, int stageIndex) {
		writePrivileged(p, facts, body, relRight, relUp, relForward, botHealth, botCharge, botSprinting, botOnGround,
				inReach, clock, stageIndex, false, 0f, false, 0f, false, false);
	}

	public static void writePrivileged(float[] p, StepFacts facts, BodyState body, float relRight, float relUp, float relForward,
			float botHealth, float botCharge, boolean botSprinting, boolean botOnGround, boolean inReach, SessionClock clock, int stageIndex,
			boolean botShieldUp, float botShieldCd, boolean ownShieldUp, float ownShieldCd, boolean kitHasShield, boolean kitHasAxe) {
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
		p[ObsSpec.PV_BOT_SHIELD_UP] = botShieldUp ? 1f : 0f;
		p[ObsSpec.PV_BOT_SHIELD_CD] = botShieldCd;
		p[ObsSpec.PV_OWN_SHIELD_UP] = ownShieldUp ? 1f : 0f;
		p[ObsSpec.PV_OWN_SHIELD_CD] = ownShieldCd;
		p[ObsSpec.PV_KIT_HAS_SHIELD] = kitHasShield ? 1f : 0f;
		p[ObsSpec.PV_KIT_HAS_AXE] = kitHasAxe ? 1f : 0f;
	}

	public static void writeStage(float[] priv, int stageIndex) {
		boolean all = stageIndex > ObsSpec.PV_STAGE_SLOTS;
		Arrays.fill(priv, ObsSpec.PV_STAGE, ObsSpec.PV_STAGE + ObsSpec.PV_STAGE_SLOTS, all ? 1f : 0f);
		if (stageIndex >= 0 && stageIndex < ObsSpec.PV_STAGE_SLOTS) {
			priv[ObsSpec.PV_STAGE + stageIndex] = 1f;
		}
	}
}
