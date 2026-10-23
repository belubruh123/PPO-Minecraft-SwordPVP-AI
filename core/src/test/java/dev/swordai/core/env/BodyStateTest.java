package dev.swordai.core.env;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyStateTest {
	/** In training, and for a fighter that has learned what health is, health is shown as it is. */
	@Test
	void healthIsShownAsItIsUnlessPatchedUp() {
		BodyState b = new BodyState();
		b.setHealth(1f);
		assertEquals(1f, b.health);
		b.setHealth(0.7f);
		assertEquals(0.7f, b.health);
		b.setHealth(0.7f);
		assertEquals(0.7f, b.health, "and it stays where it is");
	}

	/**
	 * A fighter from a stage where it could not lose health was patched up every tick there: it only
	 * ever saw full health with a dip on the tick it was hit. In a real fight it is shown the same.
	 */
	@Test
	void aPatchedUpFighterOnlyEverSeesTheDipOfAHit() {
		BodyState b = new BodyState();
		b.patchedUp = true;
		b.setHealth(0.5f);
		assertEquals(1f, b.health, "taking over half dead is not a hit");
		b.setHealth(0.5f);
		assertEquals(1f, b.health);
		b.setHealth(0.4f);
		assertEquals(0.9f, b.health, 1e-6f, "the tick it is hit, it sees what the hit took");
		b.setHealth(0.4f);
		assertEquals(1f, b.health, "and the tick after, full health again, as when it was patched up");
		b.setHealth(1f);
		assertEquals(1f, b.health, "healing or a respawn is not a hit either");
		b.setHealth(0f);
		assertEquals(0f, b.health);
	}

	/** The opponent's sword, as told by its arm: back twelve and a half ticks after a swing, and back if it was never seen to swing. */
	@Test
	void theOpponentsSwordIsReadOffItsArm() {
		BodyState b = new BodyState();
		assertEquals(1f, b.opponentCharge(), "never seen to swing");
		b.noteOpponentSwing(true, -1);
		assertEquals(0f, b.opponentCharge(), "it has just swung");
		for (int t = 0; t < 5; t++) {
			b.noteOpponentSwing(true, t);
		}
		assertEquals(5f / 12.5f, b.opponentCharge(), 1e-6f, "the swing going on is the same one");
		b.noteOpponentSwing(false, 0);
		b.noteOpponentSwing(false, 0);
		assertEquals(7f / 12.5f, b.opponentCharge(), 1e-6f);
		b.noteOpponentSwing(true, -1);
		assertEquals(0f, b.opponentCharge(), "a new one");
		b.noteOpponentSwing(true, 0);
		b.noteOpponentSwing(true, 1);
		b.noteOpponentSwing(true, -1);
		assertEquals(0f, b.opponentCharge(), "started over before the last one was done");
		for (int t = 0; t < 20; t++) {
			b.noteOpponentSwing(false, 0);
		}
		assertEquals(1f, b.opponentCharge());
		b.resetOpponentSwing();
		assertEquals(1f, b.opponentCharge());
	}
}
