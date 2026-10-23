package dev.swordai.core.rl;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixIdeaTest {
	@Test
	void withoutAPlanEverythingReadsZero() {
		MixIdea idea = new MixIdea();
		float[] vec = new float[ObsSpec.VEC];
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_IDEA_STRAFE] = 9f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_IDEA_HOLD] = 9f;
		idea.write(vec);
		assertEquals(0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_IDEA_STRAFE]);
		assertEquals(0f, vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_IDEA_HOLD]);
		int[] action = {7, 5, 1, 0, 1, 0, 1};
		idea.assist(action, ActionSpace.ALL_HEADS, true);
		assertEquals(1, action[ActionSpace.ATTACK]);
		assertEquals(0, action[ActionSpace.STRAFE]);
	}

	/** No stage holds swings back any more, and a plan does not unless it is told it may. */
	@Test
	void aPlanHoldsNoSwingBackUnlessItIsAllowedTo() {
		Random rng = new Random(3);
		MixIdea idea = new MixIdea();
		idea.start(rng);
		for (int swing = 0; swing < 500; swing++) {
			idea.swung(rng);
			idea.tick(rng, 1f);
			assertEquals(0, idea.hold());
		}
	}

	@Test
	void theHoldCountsDownOnlyOnceTheWeaponIsReady() {
		Random rng = new Random(3);
		MixIdea idea = new MixIdea();
		idea.allowHolds(true);
		// Find a swing with a real wait.
		idea.start(rng);
		for (int tries = 0; idea.hold() < 3; tries++) {
			assertTrue(tries < 1000, "no swing with a wait came up");
			idea.swung(rng);
		}
		int planned = idea.hold();
		for (int i = 0; i < 5; i++) {
			idea.tick(rng, 0.5f);
			assertEquals(planned, idea.hold(), "charging does not use up the wait");
		}
		for (int left = planned; left >= 1; left--) {
			idea.tick(rng, 1f);
			assertEquals(left, idea.hold());
		}
		idea.tick(rng, 1f);
		assertEquals(0, idea.hold());
		idea.tick(rng, 1f);
		assertEquals(0, idea.hold(), "once free, it stays free until the swing");
	}

	/** Advances the plan through one recharge of a sword (twelve and a half ticks), returning the strafe at each tick. */
	private static int[] recharge(MixIdea idea, Random rng) {
		int[] strafe = new int[14];
		for (int tick = 0; tick < strafe.length; tick++) {
			idea.tick(rng, Math.min(1f, tick / 12.5f));
			strafe[tick] = idea.strafe();
		}
		return strafe;
	}

	/**
	 * The strafe goes round the opponent: one side, held through the whole swing, the recharge and
	 * while the weapon is ready, and through the next swings too, for several swings on end. Now and
	 * then a swing in between has no side at all.
	 */
	@Test
	void theStrafeGoesRoundTheOpponentOneSideHeldForSwingsOnEnd() {
		Random rng = new Random(11);
		MixIdea idea = new MixIdea();
		idea.start(rng);

		int free = 0;
		int left = 0;
		int right = 0;
		int run = 0;
		int runSide = 0;
		int shortest = Integer.MAX_VALUE;
		int longest = 0;
		for (int swing = 0; swing < 6000; swing++) {
			int side = 0;
			boolean first = true;
			for (float charge = 0.08f; charge <= 1.001f; charge += 0.08f) {
				idea.tick(rng, charge);
				if (first) {
					side = idea.strafe();
					first = false;
				}
				assertEquals(side, idea.strafe(), "the same side at " + charge + " recharged");
				assertEquals(side != 0, idea.circling());
			}
			if (side == 0) {
				free++;
			} else if (side < 0) {
				left++;
			} else {
				right++;
			}
			if (side != 0 && side == runSide) {
				run++;
			} else {
				if (runSide != 0) {
					shortest = Math.min(shortest, run);
					longest = Math.max(longest, run);
				}
				runSide = side;
				run = side != 0 ? 1 : 0;
			}
			idea.swung(rng);
		}
		assertTrue(free > 1000 && free < 2200, free + " swings straight in, about a quarter");
		assertTrue(left > 1500 && right > 1500, left + "/" + right);
		// Two circles to the same side one after the other read as one longer one; none is ever shorter.
		assertTrue(shortest >= MixIdea.MIN_CIRCLE_SWINGS, "shortest " + shortest);
		assertTrue(longest >= MixIdea.MAX_CIRCLE_SWINGS, "longest " + longest);
	}

	@Test
	void assistStrafesAsPlannedAndDropsAnEarlySwing() {
		Random rng = new Random(5);
		MixIdea idea = new MixIdea();
		idea.allowHolds(true);
		idea.start(rng);
		// A swing with a side to go round on and a wait, just after it was made.
		int tries = 0;
		do {
			assertTrue(tries++ < 1000, "no such swing came up");
			idea.swung(rng);
			idea.tick(rng, 0.1f);
		} while (idea.strafe() == 0 || idea.hold() == 0);
		int side = idea.strafe();

		int[] action = {7, 5, 1, 0, 1, 0, 1};
		idea.assist(action, ActionSpace.ALL_HEADS, true);
		assertEquals(side < 0 ? 1 : 2, action[ActionSpace.STRAFE]);
		assertEquals(0, action[ActionSpace.ATTACK], "too early: the swing is held");
		assertEquals(1, action[ActionSpace.FORWARD], "everything else is the network's own");

		// Half recharged: the same side still. The key is held, not tapped out and back.
		idea.tick(rng, 0.6f);
		int[] back = {7, 5, 1, 0, 1, 0, 0};
		idea.assist(back, ActionSpace.ALL_HEADS, true);
		assertEquals(side < 0 ? 1 : 2, back[ActionSpace.STRAFE]);

		// Out of sight the strafe is left alone, and a locked strafe head stays locked.
		int[] blind = {7, 5, 1, 0, 1, 0, 0};
		idea.assist(blind, ActionSpace.ALL_HEADS, false);
		assertEquals(0, blind[ActionSpace.STRAFE]);
		int[] locked = {7, 5, 0, 0, 0, 0, 0};
		idea.assist(locked, ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH), true);
		assertEquals(0, locked[ActionSpace.STRAFE]);

		// Ready and the wait over: the swing goes through, and the side is still held.
		for (int i = 0; i <= MixIdea.MAX_HOLD_TICKS; i++) {
			idea.tick(rng, 1f);
		}
		int[] ready = {7, 5, 1, 1, 1, 0, 1};
		idea.assist(ready, ActionSpace.ALL_HEADS, true);
		assertEquals(1, ready[ActionSpace.ATTACK]);
		assertEquals(side < 0 ? 1 : 2, ready[ActionSpace.STRAFE]);
		assertFalse(idea.hold() > 0);
	}
}
