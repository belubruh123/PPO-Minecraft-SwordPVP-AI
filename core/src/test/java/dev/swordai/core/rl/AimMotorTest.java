package dev.swordai.core.rl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimMotorTest {
	@Test
	void aTurnBuildsUpAndDiesAwayInsteadOfJumping() {
		AimMotor m = new AimMotor(0.5f);
		float[] up = new float[4];
		for (int i = 0; i < up.length; i++) {
			m.step(40f, 0f);
			up[i] = m.yawRate();
		}
		assertEquals(20f, up[0], 1e-5);
		assertEquals(30f, up[1], 1e-5);
		assertEquals(35f, up[2], 1e-5);
		assertEquals(37.5f, up[3], 1e-5);

		float previous = m.yawRate();
		int ticksToRest = 0;
		while (m.yawRate() != 0f) {
			m.step(0f, 0f);
			assertTrue(Math.abs(m.yawRate()) < Math.abs(previous), "it only ever slows down once nothing is wanted");
			previous = m.yawRate();
			ticksToRest++;
		}
		assertTrue(ticksToRest >= 5 && ticksToRest <= 9, "a full-speed turn takes a moment to stop, took " + ticksToRest);
	}

	@Test
	void aHandAtRestIgnoresSmallImpulsesButAnswersARealTurnAtOnce() {
		AimMotor m = new AimMotor(0.5f);
		// The three gentlest yaw choices, and a single stray larger one, do not move a resting camera.
		for (float wanted : new float[] {0.2f, -0.6f, 0.6f, 0.2f, 1.5f, 0f, -0.2f, 0f, -1.5f, 0f, 0f}) {
			m.step(wanted, wanted);
			assertEquals(0f, m.yawRate(), "yaw after wanting " + wanted);
			assertEquals(0f, m.pitchRate(), "pitch after wanting " + wanted);
		}
		// A flick starts on the very tick it is asked for.
		m.step(18f, 0f);
		assertEquals(9f, m.yawRate(), 0.1f);
		assertEquals(0f, m.pitchRate());

		// A small correction needs to be meant: wanted two ticks running.
		AimMotor fine = new AimMotor(0.5f);
		fine.step(-1.5f, 0f);
		assertEquals(0f, fine.yawRate());
		fine.step(-1.5f, 0f);
		assertEquals(-1.125f, fine.yawRate(), 1e-5);
	}

	@Test
	void aSlowTurnCanBeKeptUpOnceMoving() {
		AimMotor m = new AimMotor(0.5f);
		m.step(3.5f, 0f);
		for (int i = 0; i < 30; i++) {
			m.step(0.6f, 0f);
		}
		assertEquals(0.6f, m.yawRate(), 1e-3, "following a slowly walking opponent stays a glide, not stop-and-go");
		m.step(0f, 0f);
		m.step(0f, 0f);
		assertEquals(0f, m.yawRate(), "and letting go ends in a dead stop");
	}

	@Test
	void fullResponseTurnsExactlyAsAsked() {
		AimMotor m = new AimMotor(1f);
		m.step(0.2f, -0.5f);
		assertEquals(0.2f, m.yawRate());
		assertEquals(-0.5f, m.pitchRate());
		m.step(0f, 0f);
		assertEquals(0f, m.yawRate());
	}

	@Test
	void theAgentFeelsItsOwnHand() {
		AimMotor m = new AimMotor(0.5f);
		float[] vec = new float[ObsSpec.VEC];
		m.write(vec);
		assertEquals(0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_TURN_RATE_YAW]);
		m.step(40f, -18f);
		m.write(vec);
		assertEquals(Math.sqrt(0.5), vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_TURN_RATE_YAW], 1e-5);
		assertEquals(-Math.sqrt(0.5), vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_TURN_RATE_PITCH], 1e-5);
		// Still at rest, but the push is building: that is felt too.
		AimMotor rest = new AimMotor(0.5f);
		rest.step(1.5f, 0f);
		rest.write(vec);
		assertTrue(vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_TURN_RATE_YAW] > 0f);
	}
}
