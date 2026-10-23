package dev.swordai.core.env;

import dev.swordai.core.curriculum.RewardModel;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Stages;
import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TakenStreakTest {
	@Test
	void countsHitsTakenInARowUntilItLandsOneOrTheComboRunsOut() {
		TakenStreak s = new TakenStreak();
		s.tick(0, 0);
		assertEquals(0, s.hits());
		s.tick(0, 1);
		s.tick(0, 0);
		s.tick(0, 1);
		assertEquals(2, s.hits());
		assertEquals(2f / 3f, s.shown(), 1e-6f);
		s.tick(0, 1);
		s.tick(0, 1);
		assertEquals(4, s.hits());
		assertEquals(1f, s.shown(), "a longer combo looks the same");

		// A hit of its own ends the opponent's combo; one taken on the same tick begins the next.
		s.tick(1, 0);
		assertEquals(0, s.hits());
		s.tick(0, 1);
		s.tick(1, 1);
		assertEquals(1, s.hits());

		// And it runs out by itself when the opponent stops landing.
		for (int i = 0; i < TakenStreak.COMBO_TICKS; i++) {
			s.tick(0, 0);
			assertEquals(1, s.hits(), "tick " + i);
		}
		s.tick(0, 0);
		assertEquals(0, s.hits());

		s.start(3);
		assertEquals(3, s.hits());
		s.reset();
		assertEquals(0, s.hits());
	}

	/**
	 * What the network is shown and what the reward goes by are counted in two places. They must
	 * never disagree, or it is paid for something it cannot see.
	 */
	@Test
	void theNetworkIsShownTheSameComboTheRewardGoesBy() {
		StageSpec st = null;
		for (StageSpec each : Stages.defaults()) {
			if (each.name.equals("Combo escape")) {
				st = each;
			}
		}
		Random rng = new Random(12);
		RewardModel rewards = new RewardModel();
		TakenStreak shown = new TakenStreak();
		Tally t = new Tally();
		for (int tick = 0; tick < 20_000; tick++) {
			if (tick % 700 == 0) {
				// A drill: it starts some hits into the opponent's combo.
				rewards.endEpisode(t, false, false);
				shown.reset();
				int hits = rng.nextInt(5);
				rewards.startComboed(st, hits, t);
				shown.start(hits);
			}
			StepFacts f = new StepFacts();
			f.reachDist = 2f + rng.nextFloat() * 4f;
			f.enemyVisible = true;
			f.charge = rng.nextFloat();
			f.forwardAxis = rng.nextInt(3) - 1;
			f.hitsTaken = rng.nextInt(9) == 0 ? 1 : 0;
			f.hitsDealt = rng.nextInt(14) == 0 ? 1 : 0;
			f.hitCharge = rng.nextFloat();
			rewards.step(st, f, t);
			shown.tick(f.hitsDealt, f.hitsTaken);
			assertEquals(rewards.takenStreak(), shown.hits(), "tick " + tick);
		}
		assertEquals(25, ObsSpec.SC_HITS_TAKEN_ROW, "the scalar added in 0.7.0: the hits taken in a row");
	}
}
