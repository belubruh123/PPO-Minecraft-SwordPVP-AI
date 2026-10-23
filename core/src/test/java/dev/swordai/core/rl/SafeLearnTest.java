package dev.swordai.core.rl;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learning from fights against people with the trained network kept safe ({@link SafeLearn}). */
class SafeLearnTest {
	/** One fight of a minute by the one named, dealing and taking this much damage a minute. */
	private static void fight(SafeLearn s, boolean base, float dealtPerMinute, float takenPerMinute) {
		for (int t = 0; t < 1200; t++) {
			s.tick(base, dealtPerMinute / 1200f, takenPerMinute / 1200f);
		}
		s.fightOver(base);
	}

	@Test
	void theTwoTakeTurnsFightByFightTheLearningCopyFirst() {
		SafeLearn s = new SafeLearn();
		assertFalse(s.nextIsBase());
		fight(s, false, 400, 400);
		assertTrue(s.nextIsBase());
		fight(s, true, 400, 400);
		assertFalse(s.nextIsBase());
	}

	@Test
	void aCopyThatDoesClearlyWorseIsThrownAwayAfterThreeFightsEach() {
		SafeLearn s = new SafeLearn();
		for (int i = 0; i < 2; i++) {
			fight(s, false, 300, 450);
			fight(s, true, 420, 400);
		}
		assertEquals(SafeLearn.Verdict.NONE, s.verdict(), "two fights each say nothing yet");
		fight(s, false, 300, 450);
		fight(s, true, 420, 400);
		assertEquals(SafeLearn.Verdict.ROLLBACK, s.verdict());
		s.restart();
		assertEquals(0, s.fights(true));
		assertEquals(SafeLearn.Verdict.NONE, s.verdict());
	}

	@Test
	void aCopyTakesOverOnlyWhenItDoesClearlyBetterOverEightFightsEach() {
		SafeLearn s = new SafeLearn();
		for (int i = 0; i < 7; i++) {
			fight(s, false, 460, 380);
			fight(s, true, 420, 400);
		}
		assertEquals(SafeLearn.Verdict.NONE, s.verdict(), "seven fights each are not enough");
		fight(s, false, 460, 380);
		fight(s, true, 420, 400);
		assertEquals(SafeLearn.Verdict.PROMOTE, s.verdict());
		// Only a little better is not enough.
		SafeLearn close = new SafeLearn();
		for (int i = 0; i < 8; i++) {
			fight(close, false, 430, 400);
			fight(close, true, 420, 400);
		}
		assertEquals(SafeLearn.Verdict.NONE, close.verdict());
	}

	@Test
	void aCopyThatIsNoBetterAfterSixteenFightsEachStartsAgain() {
		SafeLearn s = new SafeLearn();
		for (int i = 0; i < 16; i++) {
			fight(s, false, 400, 410);
			fight(s, true, 420, 400);
		}
		assertEquals(SafeLearn.Verdict.ROLLBACK, s.verdict());
	}

	@Test
	void theCountIsKeptAcrossSessions() {
		SafeLearn s = new SafeLearn();
		fight(s, false, 500, 300);
		s.promotions = 2;
		Properties p = new Properties();
		s.save(p);
		SafeLearn back = new SafeLearn();
		back.load(p);
		assertEquals(1, back.fights(false));
		assertEquals(200f, back.net(false), 0.5f);
		assertTrue(back.nextIsBase());
		assertEquals(2, back.promotions);
	}
}
