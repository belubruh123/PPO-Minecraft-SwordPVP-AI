package dev.swordai.core.pvp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 100 (agent 42): the crit spammer replica of the user's lost real rounds (Specialists#critSpammer) in the toy fight, against
 * the punish critter (a fighter that lets a jump-in land and punish-crits it, as the AI's guard and B1 do). The lost rounds: their
 * hit back within 12 ticks of its punish crit 79% (won rounds 53%), their jump-in take-off 3.5 blocks (p25 2.6, p75 4.7).
 */
class CritSpammerTest {
	/** Per side 1 (the bot under test) against side 0 (the punish critter): punish crits of side 0, those hit back within 12, take-offs. */
	static final class Tally {
		int fights, punish, punishOnItsJump, back12, back30, crits, critsInRowMost;
		final List<Double> takeOff = new ArrayList<>();
		float dealt0, dealt1;

		double back12Share() {
			return punish == 0 ? 0 : back12 / (double) punish;
		}

		double medianTakeOff() {
			List<Double> t = new ArrayList<>(takeOff);
			t.sort(null);
			return t.isEmpty() ? 0 : t.get(t.size() / 2);
		}

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT, "fights %d: punish crits %d (on it in the air %d), hit back within 12 %d (%.0f%%), within 30 %d; "
					+ "crits by it %d, most in a row %d; take-offs %d median %.2f; damage 0 %.0f, 1 %.0f", fights, punish, punishOnItsJump, back12, 100 * back12Share(), back30,
					crits, critsInRowMost, takeOff.size(), medianTakeOff(), dealt0, dealt1);
		}
	}

	static Tally fight(java.util.function.Function<Random, Style> bot, int fights) {
		Tally t = new Tally();
		for (int f = 0; f < fights; f++) {
			Random r = new Random(1000L + f);
			Style ai = Specialists.draw(Specialists.PUNISH_CRITTER, r);
			ai.set(Technique.B1, 0.95f);
			Bout b = new Bout(ai, bot.apply(r), 6.0, 77L + f);
			b.run(600);
			t.fights++;
			int lastHitOn0 = -1000, punishAt = -1000, lastHitBy0 = -1000, row = 0;
			boolean counted = true;
			for (int i = 0; i < b.ticks.size(); i++) {
				Bout.Tick k = b.ticks.get(i);
				Events e0 = k.events[0];
				if (e0.iHit != Events.NONE) {
					lastHitBy0 = k.t;
					row = 0;
					if (e0.iHit == Events.CRIT && k.t - lastHitOn0 <= 15) {
						t.punish++;
						t.punishOnItsJump += i > 0 && !b.ticks.get(i - 1).body[1].onGround ? 1 : 0;
						punishAt = k.t;
						counted = false;
					}
				}
				if (e0.theyHit != Events.NONE) {
					lastHitOn0 = k.t;
					if (e0.theyHit == Events.CRIT) {
						t.crits++;
						row++;
						t.critsInRowMost = Math.max(t.critsInRowMost, row);
					}
					if (!counted && k.t - punishAt <= 30) {
						counted = true;
						t.back30++;
						t.back12 += k.t - punishAt <= 12 ? 1 : 0;
					}
				}
				if (i > 0 && b.ticks.get(i - 1).body[1].onGround && !k.body[1].onGround && k.t - lastHitBy0 > 4 && b.ticks.get(i - 1).reach < 8) {
					t.takeOff.add(b.ticks.get(i - 1).reach);
				}
			}
			t.dealt0 += b.fight.dealt[0];
			t.dealt1 += b.fight.dealt[1];
		}
		return t;
	}

	@Test
	void theReplicaDoubleCritsThroughThePunishCritAndJumpsInFromAboutThreeAndAHalf() {
		Tally spam = fight(Specialists::critSpammer, 60);
		Tally dbl = fight(r -> Specialists.draw(Specialists.DOUBLE_CRITTER, r), 60);
		System.out.println("crit spammer: " + spam);
		System.out.println("double critter: " + dbl);
		// The toy's knockback of a crit carries the bot about 2 blocks out (real punish crits barely moved them: their hit back came at
		// 1.6 blocks), so the toy's hit-back share stays under the real 79%; what is checked is the behaviour, against the double critter.
		assertTrue(spam.punish >= 40, "punish crits to judge by: " + spam);
		assertTrue(spam.punishOnItsJump >= 0.4 * spam.punish, "punish crits that met its double crit jump (real 17 of 33): " + spam);
		assertTrue(spam.back30 >= 0.75 * spam.punish, "hit back within 30 ticks of the punish crit (real 32 of 33): " + spam);
		assertTrue(spam.back12Share() >= dbl.back12Share(), "hit back within 12 at least the double critter's: " + spam + " / " + dbl);
		assertTrue(spam.crits >= 1.5 * dbl.crits, "its crits, against the double critter's: " + spam + " / " + dbl);
		assertTrue(spam.medianTakeOff() >= 2.4 && spam.medianTakeOff() <= 4.8, "take-off distance (real median 3.5): " + spam);
	}

	@Test
	void onlyTheReplicaHasTheCritSpamOn() {
		Random r = new Random(5);
		for (int bot = 0; bot < Specialists.KINDS; bot++) {
			Style s = Specialists.draw(bot, r);
			assertTrue(!s.critSpam && s.critFromSpread == 0f && s.answerByCrit < 0f, Specialists.NAMES[bot]);
		}
		assertTrue(!Specialists.comboable(r).critSpam && !Specialists.comboBreaker(r).critSpam);
		Style s = Specialists.critSpammer(r);
		assertTrue(s.critSpam && s.copy().critSpam && s.copy().critFromSpread == s.critFromSpread && s.copy().answerByCrit == s.answerByCrit);
	}
}
