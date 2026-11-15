package dev.swordai.core.pvp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

/** Agent 49: a person's limits for the bots (Human, Style#human). */
class HumanBotsTest {
	@Test
	void reactionIsDrawnPerReactionAroundTheFightsOwn() {
		Random r = new Random(5);
		int[] v = new int[20000];
		for (int i = 0; i < v.length; i++) {
			v[i] = Human.reaction(4, r);
		}
		Arrays.sort(v);
		int p10 = v[v.length / 10];
		int p50 = v[v.length / 2];
		int p90 = v[9 * v.length / 10];
		assertEquals(4, p50, "median at the fight's own reaction");
		assertTrue(p10 <= 3 && p90 >= 6, "spread from one reaction to the next: p10 " + p10 + " p90 " + p90);
		assertTrue(v[0] >= 1, "never under a tick");
		// The same stream gives the same reactions.
		Random a = new Random(9);
		Random b = new Random(9);
		for (int i = 0; i < 100; i++) {
			assertEquals(Human.reaction(5, a), Human.reaction(5, b));
		}
	}

	@Test
	void windowsMisjudgeTheDistanceAndClickEarlyOrLate() {
		Style s = Human.humanize(Specialists.draw(Specialists.MIXER, new Random(3)), 77L);
		Random r = new Random(1);
		int n = 20000;
		double sum = 0;
		int early = 0;
		int late = 0;
		for (int i = 0; i < n; i++) {
			double e = Human.distError(s, r);
			assertTrue(Math.abs(e) <= Human.DIST_ERR_MOST + 1e-9);
			sum += e;
			float c = Human.clickCharge(s, r);
			early += c < s.swingCharge ? 1 : 0;
			assertTrue(c >= Human.EARLY_LEAST && c <= Math.max(Human.EARLY_MOST, s.swingCharge));
			int l = Human.lateTicks(s, c, r);
			assertTrue(l >= 0 && l <= Human.LATE_MOST);
			late += l > 0 ? 1 : 0;
		}
		assertEquals(s.distBias, sum / n, 0.03, "the misjudgement centred on the fight's bias");
		assertEquals(s.earlyClick, early / (double) n, 0.02, "early clicks at the fight's share");
		assertTrue(late > n / 10, "some clicks late");
	}

	@Test
	void humanizeKeepsTheTechniquesAndIsDeterministic() {
		for (int bot = 0; bot < Specialists.KINDS; bot++) {
			Style s = Specialists.draw(bot, new Random(100 + bot));
			Style h = Human.humanize(s, 42L + bot);
			Style h2 = Human.humanize(s, 42L + bot);
			assertTrue(h.human && !s.human, "a copy, the original untouched");
			assertArrayEquals(s.p, h.p, "the techniques' chances as they were");
			assertEquals(s.reaction, h.reaction);
			assertEquals(s.lag, h.lag);
			assertEquals(s.critOpen, h.critOpen);
			assertEquals(h.describe(), h2.describe(), "the same seed, the same limits");
			assertTrue(h.humanAdapts);
			assertFalse(Human.applies(h), "never humanized twice");
		}
		assertFalse(Human.humanize(Specialists.comboable(new Random(1)), 1L).humanAdapts, "the comboable scaffold does not adapt");
		assertFalse(Human.humanize(Specialists.comboBreaker(new Random(1)), 1L).humanAdapts);
	}

	@Test
	void aHumanizedFightIsTheSameForTheSameSeed() {
		for (long seed = 1; seed <= 5; seed++) {
			Style a = Specialists.draw(Specialists.MIXER, new Random(seed));
			Style b = Human.humanize(Specialists.draw(Specialists.JUMP_IN_CRITTER, new Random(seed + 50)), seed);
			Bout x = new Bout(a, b, 6.0, seed);
			Bout y = new Bout(a.copy(), b.copy(), 6.0, seed);
			x.run(400);
			y.run(400);
			assertEquals(x.trace(), y.trace());
		}
	}

	@Test
	void theFailureMemoryCutsWhatKeepsFailingAndForgivesWhatWorks() {
		PvpPlayer p = new PvpPlayer();
		Style s = Human.humanize(Specialists.draw(Specialists.JUMP_IN_CRITTER, new Random(2)), 2L);
		p.start(s, 2L);
		int open = Technique.BASE.ordinal();
		assertEquals(1f, p.humanScale(Technique.BASE));
		p.remember(open, true);
		p.remember(open, true);
		assertEquals(1f, p.humanScale(Technique.BASE), "two tries are not yet a habit");
		p.remember(open, true);
		float cut = p.humanScale(Technique.BASE);
		assertTrue(cut < 0.5f && cut >= Human.CUT_LEAST, "three failures in a row: tried far less, never never: " + cut);
		for (int i = 0; i < 4; i++) {
			p.remember(open, false);
		}
		assertEquals(1f, p.humanScale(Technique.BASE), "once it works again it comes back");
		// Off for a bot that is not a person.
		PvpPlayer q = new PvpPlayer();
		q.start(Specialists.draw(Specialists.JUMP_IN_CRITTER, new Random(2)), 2L);
		for (int i = 0; i < 5; i++) {
			q.remember(open, true);
		}
		assertEquals(1f, q.humanScale(Technique.BASE));
	}

	/** A specialist that is a person turns to the document's counters of what the other keeps doing (part 7: jump-ins -> T8, K2). */
	@Test
	void aHumanSpecialistAdaptsToARepeatedJumpIn() {
		int adapted = 0;
		int fights = 12;
		for (long seed = 1; seed <= fights; seed++) {
			Style jumper = Specialists.draw(Specialists.JUMP_IN_CRITTER, new Random(seed));
			jumper.critOpen = 0.95f;
			Style plain = Specialists.draw(Specialists.THREE_BLOCK, new Random(seed + 7));
			Style person = Human.humanize(plain, seed);
			Bout off = new Bout(jumper, plain, 7.0, seed);
			Bout on = new Bout(jumper.copy(), person, 7.0, seed);
			float most = 0f;
			for (int t = 0; t < 1200; t++) {
				off.step();
				on.step();
				assertEquals(plain.p(Technique.T8), off.player[1].chanceNow(Technique.T8), "a specialist that is not a person never adapts");
				most = Math.max(most, on.player[1].chanceNow(Technique.T8));
			}
			adapted += most > Math.max(0.1f, plain.p(Technique.T8)) ? 1 : 0;
		}
		assertTrue(adapted >= fights / 2, "the person took up T8 against the repeated jump-in in " + adapted + " of " + fights);
	}
}
