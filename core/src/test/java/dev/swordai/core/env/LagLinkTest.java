package dev.swordai.core.env;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LagLinkTest {
	/** With no delay and no stops, what is sent in a tick arrives in that tick. */
	@Test
	void aStraightConnectionHandsThingsOnAtOnce() {
		LagLink<Integer> link = new LagLink<>();
		link.set(0, 0f, 0);
		assertTrue(link.straight());
		Random rng = new Random(1);
		List<Integer> got = new ArrayList<>();
		for (int t = 0; t < 5; t++) {
			link.send(t);
			link.tick(rng, got::add);
			assertEquals(t + 1, got.size());
			assertEquals(t, got.get(t));
		}
	}

	/** A delay of three ticks: each thing arrives exactly three ticks after it was sent, in order. */
	@Test
	void everythingArrivesTheSetNumberOfTicksLate() {
		LagLink<Integer> link = new LagLink<>();
		link.set(3, 0f, 0);
		assertFalse(link.straight());
		assertTrue(link.holding());
		Random rng = new Random(1);
		List<Integer> got = new ArrayList<>();
		for (int t = 0; t < 10; t++) {
			link.send(t);
			link.tick(rng, got::add);
			assertEquals(Math.max(0, t - 2), got.size(), "tick " + t);
		}
		for (int i = 0; i < got.size(); i++) {
			assertEquals(i, got.get(i));
		}
	}

	/** A connection that stutters hands on nothing for a few ticks and then all of it at once, nothing lost. */
	@Test
	void aStutteringConnectionHoldsThingsBackAndThenHandsThemOverTogether() {
		LagLink<Integer> link = new LagLink<>();
		link.set(1, 0.3f, 4);
		Random rng = new Random(7);
		List<Integer> got = new ArrayList<>();
		int emptyTicks = 0;
		int mostAtOnce = 0;
		int longestGap = 0;
		int gap = 0;
		for (int t = 0; t < 2000; t++) {
			link.send(t);
			int before = got.size();
			link.tick(rng, got::add);
			int arrived = got.size() - before;
			mostAtOnce = Math.max(mostAtOnce, arrived);
			if (arrived == 0) {
				emptyTicks++;
				gap++;
				longestGap = Math.max(longestGap, gap);
			} else {
				gap = 0;
			}
		}
		link.flush(got::add);
		assertEquals(2000, got.size());
		for (int i = 0; i < got.size(); i++) {
			assertEquals(i, got.get(i));
		}
		assertTrue(emptyTicks > 400, "ticks with nothing: " + emptyTicks);
		assertTrue(mostAtOnce >= 3, "most at once: " + mostAtOnce);
		// Stops can follow one another, but each is short.
		assertTrue(longestGap < 40, "longest gap: " + longestGap);
	}

	/** After a new start nothing from before arrives, and a stop that was going on is over. */
	@Test
	void clearingForgetsWhatWasOnItsWay() {
		LagLink<Integer> link = new LagLink<>();
		link.set(4, 0f, 0);
		Random rng = new Random(1);
		List<Integer> got = new ArrayList<>();
		link.send(1);
		link.tick(rng, got::add);
		link.send(2);
		link.tick(rng, got::add);
		link.clear();
		assertEquals(0, link.waiting());
		for (int t = 0; t < 10; t++) {
			link.tick(rng, got::add);
		}
		assertTrue(got.isEmpty());
	}

	/** Changing the delay never lets a later thing overtake an earlier one. */
	@Test
	void aShorterDelayDoesNotChangeTheOrder() {
		LagLink<Integer> link = new LagLink<>();
		link.set(5, 0f, 0);
		Random rng = new Random(1);
		List<Integer> got = new ArrayList<>();
		link.send(1);
		link.tick(rng, got::add);
		link.set(0, 0f, 0);
		link.send(2);
		assertTrue(link.holding());
		link.tick(rng, got::add);
		assertTrue(got.isEmpty());
		for (int t = 0; t < 5; t++) {
			link.tick(rng, got::add);
		}
		assertEquals(List.of(1, 2), got);
	}
}
