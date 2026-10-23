package dev.swordai.core.curriculum;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fights it lost, fought again until it wins them ({@link LostCases}). */
class LostCasesTest {
	private static LostCases.Case setUp(int stage, int kind) {
		LostCases.Case c = new LostCases.Case();
		c.stage = stage;
		c.kind = kind;
		c.bot = new BotProfile();
		return c;
	}

	@Test
	void aFightThatWasLostIsKeptAndOneThatWasWonIsNot() {
		LostCases lost = new LostCases();
		assertFalse(lost.freshFight(setUp(7, 1), 300, 200, 600), "won");
		assertFalse(lost.freshFight(setUp(7, 1), 100, 300, 50), "too short to say");
		assertTrue(lost.freshFight(setUp(7, 1), 200, 300, 600));
		assertEquals(1, lost.size());
		// 100 damage more taken in half a minute: lost by 200 a minute.
		assertEquals(200f, lost.cases().get(0).lostBy, 0.01f);
	}

	@Test
	void itIsLetGoOnlyOnceItHasBeenWonTwiceInARow() {
		LostCases lost = new LostCases();
		LostCases.Case c = setUp(7, 12);
		lost.freshFight(c, 200, 300, 600);
		assertFalse(lost.replayed(c, 300, 200, 600));
		assertFalse(lost.replayed(c, 200, 300, 600), "lost again: the count starts over");
		assertEquals(0, c.wonInRow);
		assertEquals(2, c.losses);
		assertFalse(lost.replayed(c, 300, 200, 600));
		assertTrue(lost.replayed(c, 300, 200, 600));
		assertEquals(0, lost.size());
		assertEquals(1, lost.beatenEver);
		assertEquals(1, lost.beatenCases().size());
		assertTrue(c.beaten);
	}

	@Test
	void onceAllAreBeatenTheyAreFoughtAgainAndOneLostGoesBack() {
		LostCases lost = new LostCases();
		LostCases.Case a = setUp(7, 1);
		LostCases.Case b = setUp(7, 12);
		lost.freshFight(a, 200, 300, 600);
		lost.freshFight(b, 200, 300, 600);
		for (LostCases.Case c : new LostCases.Case[] {a, b}) {
			lost.replayed(c, 300, 200, 600);
			lost.replayed(c, 300, 200, 600);
		}
		assertEquals(0, lost.size());
		assertFalse(lost.allBeaten(), "not fought again yet");
		Random rng = new Random(5);
		// Both are fought again in turn, the one fought again least first.
		LostCases.Case first = lost.pick(rng, 7);
		assertNotNull(first);
		lost.replayed(first, 300, 200, 600);
		LostCases.Case second = lost.pick(rng, 7);
		assertNotNull(second);
		assertTrue(second != first);
		lost.replayed(second, 300, 200, 600);
		assertTrue(lost.allBeaten());
		// Lost again: back on the list, and it is what comes up.
		lost.replayed(first, 100, 300, 600);
		assertEquals(1, lost.size());
		assertFalse(first.beaten);
		assertEquals(1, lost.beatenCases().size());
		assertFalse(lost.allBeaten());
		assertNull(lost.pick(rng, 4));
	}

	@Test
	void theWorstLossesComeUpMostAndOnlyInTheirOwnStage() {
		LostCases lost = new LostCases();
		LostCases.Case bad = setUp(7, 7);
		LostCases.Case mild = setUp(7, 12);
		LostCases.Case other = setUp(4, 0);
		lost.freshFight(bad, 100, 600, 1200);
		lost.freshFight(mild, 290, 300, 1200);
		lost.freshFight(other, 100, 600, 1200);
		Random rng = new Random(3);
		int badPicks = 0;
		for (int i = 0; i < 1000; i++) {
			LostCases.Case c = lost.pick(rng, 7);
			assertNotNull(c);
			assertTrue(c.stage == 7);
			if (c == bad) {
				badPicks++;
			}
		}
		// Priorities 520 against 30, and every third pick or so the one picked longest ago.
		assertTrue(badPicks > 600 && badPicks < 800, "picked " + badPicks);
		assertSame(other, lost.pick(rng, 4));
		assertNull(lost.pick(rng, 2));
	}

	@Test
	void aKnockoutDecidesAFightHoweverShort() {
		LostCases lost = new LostCases();
		assertTrue(lost.freshFight(setUp(8, 7), 0, 20, 90, true));
		LostCases.Case c = lost.cases().get(0);
		assertTrue(lost.replayed(c, 20, 0, 80, true) == false && c.wonInRow == 1);
		assertTrue(lost.replayed(c, 20, 4, 120, true));
	}

	@Test
	void everyKeptFightGetsItsTurn() {
		LostCases lost = new LostCases();
		LostCases.Case bad = setUp(7, 5);
		lost.freshFight(bad, 0, 600, 1200);
		for (int i = 0; i < 9; i++) {
			lost.freshFight(setUp(7, 10), 290, 300, 1200);
		}
		Random rng = new Random(11);
		java.util.Set<LostCases.Case> seen = new java.util.HashSet<>();
		for (int i = 0; i < 200; i++) {
			seen.add(lost.pick(rng, 7));
		}
		assertEquals(10, seen.size());
	}

	@Test
	void aFullListMakesRoomWithAParkedOneElseTheMildestLoss() {
		LostCases lost = new LostCases();
		LostCases.Case hopeless = setUp(7, 5);
		lost.freshFight(hopeless, 0, 900, 1200);
		for (int i = 0; i < LostCases.PARK_REPLAYS; i++) {
			lost.replayed(hopeless, 0, 900, 1200);
		}
		assertTrue(hopeless.parked());
		LostCases.Case mild = setUp(7, 1);
		lost.freshFight(mild, 299, 300, 1200);
		for (int i = 2; i < LostCases.MOST; i++) {
			lost.freshFight(setUp(7, 1), 200, 300, 1200);
		}
		assertEquals(LostCases.MOST, lost.size());
		lost.freshFight(setUp(7, 1), 200, 300, 1200);
		assertEquals(LostCases.MOST, lost.size());
		assertFalse(lost.cases().contains(hopeless), "parked goes first");
		lost.freshFight(setUp(7, 1), 200, 300, 1200);
		assertFalse(lost.cases().contains(mild), "then the mildest loss");
		assertEquals(0, lost.parked());
	}

	@Test
	void aFightNeverWonIsParkedAndComesUpLess() {
		LostCases lost = new LostCases();
		LostCases.Case hopeless = setUp(7, 5);
		LostCases.Case other = setUp(7, 12);
		lost.freshFight(hopeless, 0, 600, 1200);
		lost.freshFight(other, 0, 600, 1200);
		for (int i = 0; i < LostCases.PARK_REPLAYS; i++) {
			lost.replayed(hopeless, 0, 600, 1200);
			lost.replayed(other, 0, 600, 1200);
		}
		lost.replayed(other, 600, 0, 1200);
		assertTrue(hopeless.parked());
		assertFalse(other.parked(), "won once");
		Random rng = new Random(2);
		int hopelessPicks = 0;
		for (int i = 0; i < 1000; i++) {
			if (lost.pick(rng, 7) == hopeless) {
				hopelessPicks++;
			}
		}
		assertTrue(hopelessPicks < 200, "picked " + hopelessPicks);
	}

	@Test
	void aPickedFightIsFoughtSeveralTimesRunning() {
		LostCases lost = new LostCases();
		for (int i = 0; i < 20; i++) {
			lost.freshFight(setUp(7, 12), 200, 300, 1200);
		}
		Random rng = new Random(4);
		LostCases.Case first = lost.pick(rng, 7);
		for (int i = 1; i < LostCases.STICK; i++) {
			assertSame(first, lost.pick(rng, 7));
		}
		// Beaten on the way: the next pick is another.
		LostCases.Case c = lost.pick(rng, 7);
		lost.replayed(c, 300, 200, 1200);
		lost.replayed(c, 300, 200, 1200);
		assertTrue(c.beaten);
		assertTrue(lost.pick(rng, 7) != c);
	}

	@Test
	void aMutatedFightIsTheSameKindWithItsSkillMovedALittle() {
		LostCases.Case from = setUp(7, 12);
		from.bot.reactionTicks = 2;
		from.bot.aimNoiseDeg = 1f;
		from.bot.critChance = 0.9f;
		from.botKnockback = 0.5f;
		from.agentLag = 3;
		from.seed = 42L;
		from.losses = 4;
		Random rng = new Random(9);
		for (int i = 0; i < 200; i++) {
			LostCases.Case m = LostCases.mutate(from, rng);
			assertEquals(12, m.kind);
			assertEquals(7, m.stage);
			assertTrue(m.bot != from.bot);
			assertTrue(Math.abs(m.bot.reactionTicks - 2) <= 1);
			assertTrue(m.bot.aimNoiseDeg >= 0.8f - 1e-4f && m.bot.aimNoiseDeg <= 1.2f + 1e-4f);
			assertTrue(m.bot.critChance >= 0.8f - 1e-4f && m.bot.critChance <= 1f);
			assertTrue(m.botKnockback >= 0.45f - 1e-4f && m.botKnockback <= 0.55f + 1e-4f);
			assertTrue(Math.abs(m.agentLag - 3) <= 1);
			assertEquals(0L, m.seed);
			assertEquals(0, m.losses);
		}
		assertEquals(2, from.bot.reactionTicks, "the one it came from is left as it was");
	}

	/**
	 * Prioritised level replay: drawn by what the last fighting had to teach, the one with the most most
	 * often, and one never scored before any that has been.
	 */
	@Test
	void byPotentialTheOneWithMostToTeachComesUpMost() {
		LostCases lost = new LostCases();
		lost.byPotential = true;
		LostCases.Case little = setUp(7, 1);
		LostCases.Case much = setUp(7, 1);
		LostCases.Case worst = setUp(7, 1);
		lost.freshFight(little, 200, 300, 600);
		lost.freshFight(much, 280, 300, 600);
		// Lost by the most, but it taught the least: by potential that is not what comes up.
		lost.freshFight(worst, 0, 600, 600);
		little.potential = 0.2f;
		much.potential = 0.9f;
		worst.potential = 0.1f;
		Random rng = new Random(5);
		int muchPicked = 0;
		int worstPicked = 0;
		for (int i = 0; i < 3000; i++) {
			// One pick at a time, not three of the same running.
			LostCases.Case c = lost.pick(rng, 7);
			if (c == much) {
				muchPicked++;
			} else if (c == worst) {
				worstPicked++;
			}
			lost.replayed(c, 300, 200, 100);
			lost = rebuild(lost, little, much, worst);
		}
		// A third of the picks go to the one waited longest, so the ranks show in the rest: about 2 to 1 here.
		assertTrue(muchPicked > 1.5 * worstPicked, "much " + muchPicked + ", worst " + worstPicked);
		LostCases.Case fresh = setUp(7, 1);
		int freshPicked = 0;
		muchPicked = 0;
		for (int i = 0; i < 3000; i++) {
			lost = rebuild(lost, little, much, worst, fresh);
			LostCases.Case c = lost.pick(rng, 7);
			if (c == fresh) {
				freshPicked++;
			} else if (c == much) {
				muchPicked++;
			}
		}
		assertTrue(freshPicked > muchPicked, "never scored: tried first (" + freshPicked + " vs " + muchPicked + ")");
	}

	private static LostCases rebuild(LostCases old, LostCases.Case... cs) {
		LostCases fresh = new LostCases();
		fresh.byPotential = true;
		for (LostCases.Case c : cs) {
			fresh.cases().add(c);
		}
		return fresh;
	}
}
