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
		assertTrue(SchoolStages.runTaught(Technique.T1C.ordinal()));
		// Run 102 (15 Nov): T9b, a jump reset of its own, is taught only with SchoolStages.JUMP_RESETS_TAUGHT (the user: no jump reset
		// unless certain, above 95%).
		assertEquals(SchoolStages.JUMP_RESETS_TAUGHT, SchoolStages.runTaught(Technique.T9B.ordinal()));
		// A technique with no layer of its own keeps every head in the shared network, as before. Agent 42 (15 Nov): this used T7,
		// but run 99 (SchoolStages.STARTERS_TAUGHT) made T7 a stun starter whose lesson is only its sprint hit's keys, so T7's taught
		// heads became STARTER_GATED_HEADS (92) by intent, not ALL_HEADS (127): the 14 Nov 22:21 redeploy failed on that outdated
		// expectation. T6 (run 89b: taught, not gated) is the plain case now; the starters are checked on their own below.
		assertEquals(0, SchoolStages.gatedHeads(Technique.T6.ordinal()));
		assertEquals(dev.swordai.core.rl.ActionSpace.ALL_HEADS, SchoolStages.taughtHeads(Technique.T6.ordinal()));
	}

	/**
	 * Run 99: a stun starter (T4, T5, T7, T8) is taught only the W/S, strafe, sprint and swing keys (no jump). Agent 45 (run 104, 15 Nov):
	 * this expected gatedHeads 0 (the shared trunk), which is what run 99's code did, not what its comment meant ("into their own option
	 * layers alone"); run 104 gates them (a sprint taught in the trunk spreads into the lift, where the punish crit lets it go) and puts them
	 * in RUN_TAUGHT, which run 99 never did (pull 0: nothing was taught at their moments).
	 */
	@Test
	void theStunStartersAreTaughtTheirSprintHitKeysOnly() {
		org.junit.jupiter.api.Assumptions.assumeTrue(SchoolStages.STARTERS_TAUGHT && SchoolStages.GATED, "the starters are not taught");
		for (Technique t : new Technique[] {Technique.T4, Technique.T5, Technique.T7, Technique.T8}) {
			assertEquals(SchoolStages.STARTER_GATED_HEADS, SchoolStages.taughtHeads(t.ordinal()), t + "'s taught keys");
			assertEquals(SchoolStages.ALL_TO_70 ? SchoolStages.STARTER_GATED_HEADS : 0, SchoolStages.gatedHeads(t.ordinal()), t + "'s option layer");
			assertEquals(SchoolStages.ALL_TO_70, SchoolStages.runTaught(t.ordinal()), t + " in RUN_TAUGHT");
		}
		assertEquals(0, SchoolStages.STARTER_GATED_HEADS & (1 << dev.swordai.core.rl.ActionSpace.JUMP), "no jump in a starter's lesson");
	}

	/**
	 * Run 104: every included technique is taught with a pull above 0, all but B1 and T6 through their own layers only (nothing new reaches
	 * the trunk), the excluded ones (the get-out part, the jump resets) get no priority, and the priority falls as the rate rises: the
	 * smallest rate is trained most, and much more under 50%.
	 */
	@Test
	void everyIncludedTechniqueIsTaughtByItsPriority() {
		org.junit.jupiter.api.Assumptions.assumeTrue(SchoolStages.ALL_TO_70 && SchoolStages.GATED, "run 104 is off");
		SchoolStages st = new SchoolStages();
		float[] pulls = st.techPulls();
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (SchoolStages.included(i)) {
				assertTrue(pulls[i] > 0f, t + " is pulled");
				if (t != Technique.B1 && t != Technique.T6) {
					assertTrue(SchoolStages.gatedHeads(i) != 0, t + " has its own layer");
					assertEquals(SchoolStages.gatedHeads(i), SchoolStages.taughtHeads(i), t + "'s keys go into its layer alone");
				}
			}
			if (SchoolStages.excluded(i)) {
				assertEquals(0f, st.priority(i), 0f, t + " is excluded");
			}
			// Run 104b (the user, 15 Nov 16:00): the jump-in counters are learned later: review priority only, no pull boost.
			if (SchoolStages.later(i)) {
				assertEquals(SchoolStages.REVIEW_PRIORITY, st.priority(i), 1e-6f, t + " is for later");
				assertEquals(1f, st.pullBoost(i), 0f, t + " has no pull boost");
			}
		}
		for (Technique t : new Technique[] {Technique.C1, Technique.C7, Technique.T6A, Technique.K2}) {
			assertEquals(0, SchoolStages.gatedHeads(t.ordinal()) & (1 << dev.swordai.core.rl.ActionSpace.JUMP), t + " has no jump in its lesson");
		}
		// T4 done on 1 in 10 of its moments, T7 on 4 in 10, T6 on 9 in 10, C7 (not favoured) on 1 in 10.
		for (int k = 0; k < 20; k++) {
			st.moment(Technique.T4.ordinal(), k % 10 == 0, false);
			st.moment(Technique.T7.ordinal(), k % 10 < 4, false);
			st.moment(Technique.T6.ordinal(), k % 10 < 9, false);
			st.moment(Technique.C7.ordinal(), k % 10 == 0, false);
		}
		float t4 = st.priority(Technique.T4.ordinal());
		float t7 = st.priority(Technique.T7.ordinal());
		float t6 = st.priority(Technique.T6.ordinal());
		assertTrue(t4 > 2f * t7, "under 50% the smallest rate weighs much more: " + t4 + " vs " + t7);
		assertTrue(t7 > t6, "T7 under 70% before T6 over it");
		assertEquals(SchoolStages.REVIEW_PRIORITY, t6, 1e-6f, "over 70%: reviewed only");
		assertTrue(t4 > st.priority(Technique.C7.ordinal()), "the starters favoured");
		assertTrue(st.pullBoost(Technique.T4.ordinal()) > st.pullBoost(Technique.T7.ordinal()), "the smaller rate pulls more");
		assertEquals(1f, st.pullBoost(Technique.B1.ordinal()), 0f, "the punish crit's pull as it was");
		java.util.Random rng = new java.util.Random(5);
		st.reached = SchoolStages.FAMILIES - 1;
		for (int k = 0; k < 50; k++) {
			int b = st.weakestBot(rng);
			assertTrue(b >= 0 && b < dev.swordai.core.pvp.Specialists.KINDS);
		}
		String line = st.run104Line();
		assertTrue(line.contains("T4 10% (20)"), line);
	}
}
