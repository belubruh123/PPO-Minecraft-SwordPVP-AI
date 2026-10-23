package dev.swordai.core.env;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSprintTest {
	/** One tick with a charged hit on the opponent, then the keys. Returns whether the server counted a sprint hit. */
	private static boolean hitThenMove(ClientSprint s, boolean forward, boolean sprintKey) {
		boolean knockback = s.server();
		// The server ends the sprint of whoever lands a sprint hit.
		s.serverHit(false);
		s.clientHit(true);
		s.move(forward, false, sprintKey, false);
		return knockback;
	}

	private static ClientSprint running() {
		ClientSprint s = new ClientSprint();
		s.move(true, false, true, false);
		assertTrue(s.client());
		assertTrue(s.server());
		return s;
	}

	@Test
	void holdingEverythingDownOnlyTheFirstHitKnocksBack() {
		ClientSprint s = running();
		assertTrue(hitThenMove(s, true, true));
		// The client stopped and restarted its sprint within the tick, so it never told the server.
		assertTrue(s.client());
		assertFalse(s.server());
		for (int i = 0; i < 5; i++) {
			s.move(true, false, true, false);
		}
		assertFalse(hitThenMove(s, true, true), "the server still has the sprint as over");
		assertTrue(s.client(), "while the body runs on");
	}

	@Test
	void lettingGoOfForwardForATickGetsTheSprintHitBack() {
		ClientSprint s = running();
		assertTrue(hitThenMove(s, true, true));
		s.move(false, false, true, false);
		assertFalse(s.client());
		s.move(true, false, true, false);
		assertTrue(s.server());
		assertTrue(hitThenMove(s, true, true));
	}

	@Test
	void aHitOnTheTickForwardIsReleasedStillCounts() {
		// The click reaches the server before that tick's movement does.
		ClientSprint s = running();
		assertTrue(hitThenMove(s, false, true), "W was let go on the very tick of the hit");
		assertFalse(s.client());
		s.move(true, false, true, false);
		assertTrue(hitThenMove(s, false, true), "and the tap already reset it for the next one");
	}

	@Test
	void aHitOnTheTickForwardIsPressedAgainDoesNotCountYet() {
		ClientSprint s = running();
		hitThenMove(s, true, true);
		s.move(false, false, true, false);
		// W and the click on the same tick: the server hears of the new sprint after the click.
		assertFalse(hitThenMove(s, true, true));
		assertTrue(s.server());
	}

	@Test
	void lettingGoOfTheSprintKeyOnTheHitAlsoResetsIt() {
		ClientSprint s = running();
		// Without the sprint key the client does not restart the sprint the hit ended, and says so.
		assertTrue(hitThenMove(s, true, false));
		assertFalse(s.client());
		s.move(true, false, true, false);
		assertTrue(s.client());
		assertTrue(s.server());
		assertTrue(hitThenMove(s, true, true));
	}

	@Test
	void theSprintKeyAloneDoesNotEndASprint() {
		ClientSprint s = running();
		for (int i = 0; i < ClientSprint.DOUBLE_TAP_TICKS + 1; i++) {
			s.move(true, false, false, false);
		}
		assertTrue(s.client(), "a sprint lasts until W is let go");
		s.move(false, false, false, false);
		assertFalse(s.client());
		s.move(true, false, false, false);
		assertFalse(s.server(), "and does not start again without the key or a double tap");
	}

	@Test
	void aQuickTapOfForwardCountsAsADoubleTap() {
		// W pressed, let go and pressed again within seven ticks sprints even without the sprint key.
		ClientSprint s = running();
		s.move(true, false, false, false);
		s.move(false, false, false, false);
		s.move(true, false, false, false);
		assertTrue(s.client());
		assertTrue(s.server());
	}

	@Test
	void tappingForwardTwiceStartsASprintWithoutTheKey() {
		ClientSprint s = new ClientSprint();
		s.move(true, false, false, false);
		assertFalse(s.client());
		s.move(false, false, false, false);
		s.move(true, false, false, false);
		assertTrue(s.client());
		assertTrue(s.server());

		// Too slow, and it is just walking.
		ClientSprint slow = new ClientSprint();
		slow.move(true, false, false, false);
		for (int i = 0; i < ClientSprint.DOUBLE_TAP_TICKS + 1; i++) {
			slow.move(false, false, false, false);
		}
		slow.move(true, false, false, false);
		assertFalse(slow.client());
	}

	@Test
	void anUnchargedHitChangesNothingAndAWallStopsTheSprint() {
		ClientSprint s = running();
		assertFalse(s.clientHit(false));
		assertTrue(s.client());
		s.move(true, false, true, true);
		assertFalse(s.client());
		assertFalse(s.server());
	}
}
