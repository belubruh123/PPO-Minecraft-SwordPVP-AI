package dev.swordai.core.school;

import dev.swordai.core.nn.Param;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 122 (agent 56; the user, 16 Nov 22:05: "always get a sprint hit stun and then jump for no reason"): the own-hit jump guard
 * (SchoolFighter#ownHitJumpGuard), its "no jump" lesson on the attempt alone (SchoolPpo#ownHitNoJumpApplies), and the crit chains
 * (T1, T1b, T1c, T2, C6) neither taught nor told (SchoolStages#CRIT_CHAINS_TAUGHT, Chooser#untold).
 */
class OwnHitJumpGuardTest {
	private static final int[] CHAINS = {Technique.T1.ordinal(), Technique.T1B.ordinal(), Technique.T1C.ordinal(), Technique.T2.ordinal(),
			Technique.C6.ordinal()};

	@Test
	void theWindowIsItsOwnHitWithNoHitTakenSince() {
		assertTrue(SchoolFighter.ownHitWindow(1, 1000), "the tick after its hit");
		assertTrue(SchoolFighter.ownHitWindow(SchoolFighter.OWN_HIT_WINDOW, 1000), "up to 20 ticks");
		assertFalse(SchoolFighter.ownHitWindow(SchoolFighter.OWN_HIT_WINDOW + 1, 1000), "not after");
		assertFalse(SchoolFighter.ownHitWindow(5, 2), "their hit since: the jump reset on their hit (rule r) is outside it");
		assertFalse(SchoolFighter.ownHitWindow(3, 3), "a trade (both on the same tick): outside it");
		assertFalse(SchoolFighter.ownHitWindow(1000, 1000), "no hit at all");
	}

	@Test
	void theGuardLetsGoOfTheJumpOnlyCloseOnTheGroundWithTheSwordNotBack() {
		// The real rounds' frame: 1-3 ticks after its crit, 1.4-1.9 blocks, its sword 0.32.
		assertTrue(SchoolFighter.ownHitJumpGuard(2, 1000, true, 1.6, 0.32f));
		assertTrue(SchoolFighter.ownHitJumpGuard(20, 1000, true, 3.5, 0.89f), "the edges are in");
		assertFalse(SchoolFighter.ownHitJumpGuard(2, 1000, true, 3.6, 0.32f), "farther than 3.5: a take-off there is charged in training, not stripped");
		assertFalse(SchoolFighter.ownHitJumpGuard(2, 1000, true, 1.6, 0.9f), "its sword back (0.9+): not stripped");
		assertFalse(SchoolFighter.ownHitJumpGuard(2, 1000, false, 1.6, 0.32f), "in the air: no take-off to strip");
		assertFalse(SchoolFighter.ownHitJumpGuard(21, 1000, true, 1.6, 0.32f), "after the window");
		// The punish crit (B1) and the jump reset on their hit both follow a hit taken: never in the window.
		assertFalse(SchoolFighter.ownHitJumpGuard(4, 0, true, 1.6, 0.32f), "their hit on this tick (the jump reset r)");
		assertFalse(SchoolFighter.ownHitJumpGuard(4, 1, true, 1.6, 0.6f), "lifted by their hit (the punish crit's lift)");
		assertFalse(SchoolFighter.ownHitJumpGuard(4, 1000, true, Double.MAX_VALUE, 0.32f), "distance unknown: nothing held");
	}

	@Test
	void theNoJumpLessonIsOnTheAttemptAlone() {
		int all = ActionSpace.ALL_HEADS;
		assertTrue(SchoolPpo.ownHitNoJumpApplies(1f, 1, all), "its own jump pressed in the window");
		assertFalse(SchoolPpo.ownHitNoJumpApplies(1f, 0, all), "no jump pressed: no lesson on the tick (it does not spill)");
		assertFalse(SchoolPpo.ownHitNoJumpApplies(0f, 1, all), "outside the window (no pull on the step)");
		assertFalse(SchoolPpo.ownHitNoJumpApplies(1f, 1, all & ~ActionSpace.mask(ActionSpace.JUMP)), "a rule's jump (the head locked)");
	}

	@Test
	void theCritChainsAreNeitherIncludedNorTaughtWhileOff() {
		SchoolStages stages = new SchoolStages();
		float[] pulls = stages.techPulls();
		for (int t : CHAINS) {
			String id = Technique.ALL[t].id;
			assertEquals(!SchoolStages.CRIT_CHAINS_TAUGHT, SchoolStages.critChainOff(t), id);
			if (SchoolStages.CRIT_CHAINS_TAUGHT) {
				continue;
			}
			assertFalse(SchoolStages.included(t), id + " off the user's list");
			assertTrue(SchoolStages.excluded(t), id);
			assertFalse(SchoolStages.favoured(t), id);
			assertFalse(SchoolStages.runTaught(t), id + " no pull");
			assertEquals(0f, pulls[t], id);
			assertEquals(0, SchoolStages.gatedHeads(t), id + " no option layer taught or added");
			assertEquals(0f, stages.priority(t), id + " no fights drawn for it");
			assertTrue(SchoolStages.momentless(t), id);
		}
		if (SchoolStages.CRIT_CHAINS_TAUGHT) {
			return;
		}
		// Kept: the first jump-in crit, the punish crit, the golden rule, the jump along, the jump resets, the counters.
		for (Technique t : new Technique[] {Technique.T1A, Technique.B1, Technique.K2, Technique.K3, Technique.T9, Technique.T9A, Technique.T9B,
				Technique.T9C, Technique.C1, Technique.C3, Technique.C4, Technique.T4, Technique.T5, Technique.T6, Technique.T6A, Technique.T7}) {
			assertFalse(SchoolStages.critChainOff(t.ordinal()), t.id);
			assertTrue(SchoolStages.included(t.ordinal()), t.id + " still on the list");
		}
		assertEquals(SchoolStages.CRIT_GATED_HEADS, SchoolStages.gatedHeads(Technique.T1A.ordinal()), "T1a keeps its layer");
		assertTrue(SchoolStages.counterJump(Technique.T9B.ordinal()), "T9b keeps run 71's layer");
		assertFalse(SchoolStages.counterJump(Technique.T1C.ordinal()), "T1c's does not");
		assertTrue(SchoolStages.runTaught(Technique.B1.ordinal()));
		// C2 follows only a double crit: on the list, but no fights drawn for it while the chains are off.
		int c2 = Technique.C2.ordinal();
		assertTrue(SchoolStages.included(c2));
		assertTrue(SchoolStages.momentless(c2));
		assertEquals(0f, stages.priority(c2));
	}

	@Test
	void theDeciderNeverTellsACritChainEvenWhenItLikesItMost() {
		if (!Chooser.UNTOLD_MASK || SchoolStages.CRIT_CHAINS_TAUGHT) {
			return;
		}
		Chooser c = new Chooser(7L);
		Chooser.Workspace ws = c.newWorkspace();
		// Its bias for the standing crit made by far the likeliest.
		for (Param p : c.params.params()) {
			if (p.name.equals("choose.out.b")) {
				p.w[Technique.T2.ordinal()] = 100f;
				p.w[Technique.T1C.ordinal()] = 90f;
			}
		}
		Random rng = new Random(3);
		float[] vec = new float[ObsSpec.VEC];
		float[] mem = new float[SchoolObs.MEM];
		float[] side = new float[Predictor.OUT];
		float[] before = new float[SchoolNet.INTENT];
		before[Technique.BASE.ordinal()] = 1f;
		for (int n = 0; n < 200; n++) {
			for (int i = 0; i < vec.length; i++) {
				vec[i] = rng.nextFloat();
			}
			int best = c.forward(ws, vec, mem, side, before);
			int drawn = c.draw(ws, rng.nextFloat());
			for (int t : CHAINS) {
				assertNotEquals(t, best, "its likeliest");
				assertNotEquals(t, drawn, "its drawn choice");
				assertEquals(0f, ws.chances[t], 1e-12f);
			}
		}
		assertTrue(Chooser.untold(Technique.T2.ordinal()));
		assertFalse(Chooser.untold(Technique.T1A.ordinal()));
		assertFalse(Chooser.untold(Technique.B1.ordinal()));
	}
}
