package dev.swordai.core.curriculum;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonStyleTest {
	@Test
	void aPlayerWhoKeepsOutOfReachAndAnswersOnTheWayDownBecomesOneThatPlaysOnItsRhythm() {
		PersonStyle friend = new PersonStyle();
		friend.hitsDealt = 100;
		friend.hitsTaken = 95;
		friend.critsTaken = 70;
		friend.punishCrits = 40;
		friend.quietTicks = 400;
		friend.quietDistanceSum = 400 * 3.4;
		friend.knockSamples = 50;
		friend.knockSpeedSum = 50 * 0.33;
		friend.backOffs = 20;
		friend.chasedCrits = 1;
		friend.pingFights = 2;
		friend.pingSum = 2 * 80;
		assertTrue(friend.enough());
		BotProfile b = new BotProfile();
		friend.applyTo(b, new Random(1));
		assertTrue(b.timer && b.punish, "keeps its distance and answers on the way down");
		assertFalse(b.jumpIn, "and was never seen to chase");
		assertEquals(3.4f, b.preferredRange, 1e-4f);
		assertEquals(0.6f, friend.knockback(), 1e-4f);
		assertEquals(1, friend.lagTicks());
		assertTrue(b.critChance > 0.7f);
	}

	@Test
	void oneThatFollowsABackOffThroughTheAirChasesAndOneSeenLittleIsNotMadeIntoAScript() {
		PersonStyle chaser = new PersonStyle();
		chaser.hitsDealt = 40;
		chaser.hitsTaken = 50;
		chaser.backOffs = 10;
		chaser.chasedCrits = 6;
		chaser.quietTicks = 100;
		chaser.quietDistanceSum = 100 * 2.8;
		BotProfile b = new BotProfile();
		chaser.applyTo(b, new Random(1));
		assertTrue(b.jumpIn);
		assertFalse(b.timer);
		assertEquals(1f, chaser.knockback(), 0f, "too little seen of how far a hit sends it: all of it");
		PersonStyle stranger = new PersonStyle();
		stranger.hitsDealt = 10;
		stranger.hitsTaken = 5;
		assertFalse(stranger.enough());
	}
}
