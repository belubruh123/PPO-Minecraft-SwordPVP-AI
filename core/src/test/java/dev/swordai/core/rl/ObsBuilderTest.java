package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.SyntheticEye;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObsBuilderTest {
	private static BodyState body() {
		BodyState b = new BodyState();
		b.yaw = 12f;
		b.pitch = -4f;
		b.height = 0.4f;
		b.onGround = false;
		b.setMotion(0.1, 0.05, 0.2);
		b.setOpponentMotion(-0.05, 0.0, 0.1);
		b.charge = 0.6f;
		b.health = 0.8f;
		b.sprinting = true;
		b.ticksSinceHitDealt = 3;
		b.ticksSinceHitTaken = 7;
		return b;
	}

	private static MaskFrame frame() {
		MaskFrame frame = new MaskFrame();
		new SyntheticEye().look(0, 1.62, 0, 10f, -3f, 0.4, 0, 3, 180f, 180f, 0f, frame);
		assertTrue(frame.enemyVisible());
		return frame;
	}

	/**
	 * Training on the screen and training without it hand the network its observation through one
	 * writer. It has to put down exactly what the screen's own code put down before there was a
	 * second body: the pieces one by one, in the order that code had them.
	 */
	@Test
	void theSharedWriterPutsDownWhatTheScreenAlwaysDid() {
		MaskFrame frame = frame();
		int headMask = ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH, ActionSpace.FORWARD, ActionSpace.ATTACK);
		int[] last = new int[ActionSpace.HEADS];
		last[ActionSpace.YAW] = 3;
		last[ActionSpace.ATTACK] = 1;
		Random plan = new Random(5);

		Obs want = new Obs();
		Arrays.fill(want.vec, 9f);
		SightTracker sightA = new SightTracker();
		AimMotor motorA = new AimMotor(0.5f);
		motorA.step(8f, -2f);
		MixIdea ideaA = new MixIdea();
		ideaA.start(new Random(5));
		BodyState bodyA = body();
		Arrays.fill(want.vec, 0f);
		System.arraycopy(frame.peri, 0, want.peri, 0, ObsSpec.PERI_SIZE);
		System.arraycopy(frame.fovea, 0, want.fovea, 0, ObsSpec.FOVEA_SIZE);
		sightA.update(frame.stats, frame.viewYaw, frame.viewPitch, bodyA.yaw, bodyA.pitch);
		sightA.write(want.vec);
		bodyA.write(want.vec, frame.enemyVisible());
		motorA.write(want.vec);
		ideaA.write(want.vec);
		ObsBuilder.writeLastAction(want.vec, last);
		ObsBuilder.writeHeadMask(want.vec, headMask);

		Obs got = new Obs();
		Arrays.fill(got.vec, 9f);
		SightTracker sightB = new SightTracker();
		AimMotor motorB = new AimMotor(0.5f);
		motorB.step(8f, -2f);
		MixIdea ideaB = new MixIdea();
		ideaB.start(plan);
		ObsBuilder.writeActor(got, frame, sightB, body(), motorB, ideaB, last, headMask);

		assertArrayEquals(want.vec, got.vec);
		assertArrayEquals(want.peri, got.peri);
		assertArrayEquals(want.fovea, got.fovea);
		// Nothing of it is left at whatever the array held before.
		for (float v : got.vec) {
			assertTrue(v != 9f);
		}

		// At the start of an episode there is no last action; the rest is the same.
		Obs first = new Obs();
		ObsBuilder.writeActor(first, frame, new SightTracker(), body(), new AimMotor(0.5f), new MixIdea(), null, headMask);
		for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
			assertEquals(0f, first.vec[ObsSpec.VEC_LAST_ACTION + i]);
		}
	}

	@Test
	void whatOnlyTheCriticGetsIsWrittenWhole() {
		StepFacts facts = new StepFacts();
		facts.yawErrDeg = 45f;
		facts.pitchErrDeg = -9f;
		facts.reachDist = 4f;
		facts.onBody = true;
		facts.onHead = false;
		SessionClock clock = new SessionClock();
		clock.episodeFraction = 0.25f;
		clock.botSkill = 0.5f;
		clock.combo = 9;
		clock.botAttacks = true;
		BodyState b = body();
		float[] p = new float[ObsSpec.PRIV];
		Arrays.fill(p, 9f);
		ObsBuilder.writePrivileged(p, facts, b, 1.6f, -0.8f, 4f, 15f, 0.7f, true, false, true, clock, 5);

		assertEquals(0.25f, p[ObsSpec.PV_YAW_ERR], 1e-6f);
		assertEquals(-0.1f, p[ObsSpec.PV_PITCH_ERR], 1e-6f);
		assertEquals(0.5f, p[ObsSpec.PV_REACH_DIST], 1e-6f);
		assertEquals(0.2f, p[ObsSpec.PV_REL_RIGHT], 1e-6f);
		assertEquals(-0.1f, p[ObsSpec.PV_REL_UP], 1e-6f);
		assertEquals(0.5f, p[ObsSpec.PV_REL_FORWARD], 1e-6f);
		assertEquals(b.oppVelForward * BodyState.VELOCITY_SCALE, p[ObsSpec.PV_BOT_VEL_FORWARD], 1e-6f);
		assertEquals(0.75f, p[ObsSpec.PV_BOT_HEALTH], 1e-6f);
		assertEquals(0.7f, p[ObsSpec.PV_BOT_CHARGE], 1e-6f);
		assertEquals(1f, p[ObsSpec.PV_BOT_SPRINTING]);
		assertEquals(0f, p[ObsSpec.PV_BOT_ON_GROUND]);
		assertEquals(0f, p[ObsSpec.PV_ON_HEAD]);
		assertEquals(1f, p[ObsSpec.PV_ON_BODY]);
		assertEquals(1f, p[ObsSpec.PV_IN_REACH]);
		assertEquals(0.25f, p[ObsSpec.PV_EPISODE_TIME], 1e-6f);
		assertEquals(0.5f, p[ObsSpec.PV_BOT_SKILL], 1e-6f);
		assertEquals(1f, p[ObsSpec.PV_COMBO], 1e-6f, "the combo count is capped at five");
		assertEquals(1f, p[ObsSpec.PV_BOT_ATTACKS]);
		assertEquals(1f, p[ObsSpec.PV_STAGE + 5], "the stage it is playing");
		for (float v : p) {
			assertTrue(v != 9f, "every slot is written, none left over from the step before");
		}
	}
}
