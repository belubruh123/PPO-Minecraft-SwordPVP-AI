package dev.swordai.core.school;

import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Technique;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 70 (Fable): in THEIR combo the teacher meets their jump-in from beyond reach with its own jump along with it (K3, the crit
 * on the way down); outside their combo it lets the jump-in land for the punish crit as before.
 */
class JumpAlongTest {
	private static final Technique[] FAMILY = {Technique.K3, Technique.B1, Technique.T9, Technique.T9A, Technique.T8, Technique.C3,
			Technique.T4, Technique.T5};

	/** Them {@code flat} blocks in front of me, both on the ground, them walking in at {@code vIn} a tick. */
	private static void place(Duel d, double flat, double vIn) {
		d.me.x = 0;
		d.me.y = 0;
		d.me.z = 0;
		d.me.vx = 0;
		d.me.vy = 0;
		d.me.vz = 0;
		d.me.onGround = true;
		d.them.x = flat;
		d.them.y = 0;
		d.them.z = 0;
		d.them.vx = -vIn;
		d.them.vy = 0;
		d.them.vz = 0;
		d.them.onGround = true;
		d.measure();
	}

	/** Their take-off at me from 4 blocks on tick 20, after their sprint hit on tick 8 (or none): what the teacher does on that tick. */
	private static Teacher drive(boolean theirHitFirst) {
		Teacher t = new Teacher();
		t.start(FAMILY, 0.9f, 5L);
		Duel d = new Duel();
		Events e = new Events();
		d.me.charge = 1f;
		d.them.charge = 1f;
		for (int tick = 0; tick <= 20; tick++) {
			e.clear();
			place(d, 5.0 - 0.05 * tick, 0.05);
			if (theirHitFirst && tick == 8) {
				e.theyHit = Events.SPRINT;
				d.me.sinceHurt = 0;
			} else if (theirHitFirst && tick > 8) {
				d.me.sinceHurt = tick - 8;
			}
			if (tick == 20) {
				// The ground flag to air, rising, coming at me.
				place(d, 4.0, 0.3);
				d.them.onGround = false;
				d.them.vy = 0.42;
				d.them.sinceJump = 0;
				d.them.airTicks = 1;
				d.measure();
			}
			t.tick(d, e, false);
		}
		return t;
	}

	@Test
	void inTheirComboTheTeacherJumpsAlongWithTheirJumpIn() {
		Teacher t = drive(true);
		assertEquals(Technique.K3, t.technique(), "the jump along (K3) at their take-off in their combo");
		assertTrue(t.keys().jump, "the jump on the take-off tick");
		assertTrue(!t.keys().sprint, "no sprint: the swing on the way down must be a crit");
	}

	@Test
	void outsideTheirComboTheJumpInIsLetLandAsBefore() {
		Teacher t = drive(false);
		assertNotEquals(Technique.K3, t.technique(), "no jump along outside their combo");
		assertTrue(!t.keys().jump, "no jump at a neutral jump-in: let it land, punish crit");
	}

	@Test
	void theJumpAlongIsTaughtIntoItsOwnLayer() {
		org.junit.jupiter.api.Assumptions.assumeTrue(SchoolStages.JUMP_ALONG_TAUGHT, "the jump along is not taught");
		assertEquals(SchoolStages.K3_GATED_HEADS, SchoolStages.gatedHeads(Technique.K3.ordinal()));
		assertEquals(SchoolStages.K3_GATED_HEADS, SchoolStages.taughtHeads(Technique.K3.ordinal()));
		assertTrue(SchoolStages.runTaught(Technique.K3.ordinal()));
	}

	/** Run 71: the jump and crit again after its punish crit (T1c) and the jump right after its attack (T9b) have their own layers. */
	@Test
	void theCounterJumpsAreTaughtIntoTheirOwnLayers() {
		org.junit.jupiter.api.Assumptions.assumeTrue(SchoolStages.COUNTER_JUMPS_TAUGHT, "the counter jumps are not taught");
		assertEquals(SchoolStages.T1C_GATED_HEADS, SchoolStages.gatedHeads(Technique.T1C.ordinal()));
		assertEquals(SchoolStages.T1C_GATED_HEADS, SchoolStages.taughtHeads(Technique.T1C.ordinal()));
		assertEquals(SchoolStages.T9B_GATED_HEADS, SchoolStages.gatedHeads(Technique.T9B.ordinal()));
		assertEquals(SchoolStages.T9B_GATED_HEADS, SchoolStages.taughtHeads(Technique.T9B.ordinal()));
		assertTrue(SchoolStages.runTaught(Technique.T1C.ordinal()) && SchoolStages.runTaught(Technique.T9B.ordinal()));
		// A technique with no layer of its own keeps every head in the shared network, as before.
		assertEquals(0, SchoolStages.gatedHeads(Technique.T7.ordinal()));
		assertEquals(dev.swordai.core.rl.ActionSpace.ALL_HEADS, SchoolStages.taughtHeads(Technique.T7.ordinal()));
	}
}
