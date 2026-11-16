package dev.swordai.core.school;

import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.rl.ActionSpace;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run 123: the combo spacing controller's decisions (ComboSpacing) and its counters (ComboTally). */
class ComboSpacingTest {
	private static final float READY = 0.95f;
	private static final int LAG = 1;
	private static final int K2_LAG = 3;

	/**
	 * Them {@code eyeToBox} blocks from my eye (both on the ground, on the x axis), coming at me at {@code vIn} a tick; my sword and
	 * sprint as given.
	 */
	private static Duel place(double eyeToBox, double vIn, float myCharge, boolean sprinting) {
		Duel d = new Duel();
		d.me.x = 0;
		d.me.y = 0;
		d.me.z = 0;
		d.me.onGround = true;
		d.me.charge = myCharge;
		d.me.sprinting = sprinting;
		// Eye to box on the flat: the gap between my middle and their box's near face.
		d.them.x = eyeToBox + 0.3;
		d.them.y = 0;
		d.them.z = 0;
		d.them.vx = -vIn;
		d.them.onGround = true;
		d.them.charge = 0.2f;
		d.measure();
		return d;
	}

	/** A controller {@code ticks} ticks into its combo (its hit, then ticks-1 quiet ticks far away). */
	private static ComboSpacing after(int ticks) {
		ComboSpacing c = new ComboSpacing();
		c.hitDealt();
		for (int i = 1; i < ticks; i++) {
			c.tick(place(4.0, 0, 0.2f, false), READY, false, false, K2_LAG, LAG);
		}
		return c;
	}

	private static int[] keys(ComboSpacing c) {
		int[] a = new int[ActionSpace.HEADS];
		a[ActionSpace.JUMP] = 1;
		a[ActionSpace.ATTACK] = 1;
		c.press(a);
		return a;
	}

	@Test
	void offUntilItsHitAndOffAfterTheirs() {
		ComboSpacing c = new ComboSpacing();
		assertFalse(c.tick(place(2.8, 0, 1f, true), READY, true, false, K2_LAG, LAG));
		c.hitDealt();
		assertTrue(c.tick(place(2.8, 0, 0.1f, true), READY, true, false, K2_LAG, LAG));
		c.hitTaken();
		assertFalse(c.tick(place(2.8, 0, 1f, true), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.OFF, c.decision());
	}

	@Test
	void wTapRightAfterItsHit() {
		ComboSpacing c = new ComboSpacing();
		c.hitDealt();
		assertTrue(c.tick(place(2.8, 0, 0.08f, false), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.W_TAP, c.decision());
		int[] a = keys(c);
		assertEquals(0, a[ActionSpace.FORWARD]);
		assertEquals(0, a[ActionSpace.SPRINT]);
		assertEquals(0, a[ActionSpace.JUMP]);
		assertEquals(0, a[ActionSpace.ATTACK]);
	}

	@Test
	void spacesBackWithSWhileTheSwordComesBack() {
		ComboSpacing c = after(6);
		// A jump reset left them at 2.0 with its sword at 0.5: S, no sprint, no swing.
		assertTrue(c.tick(place(2.0, 0, 0.5f, false), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.S_TAP, c.decision());
		int[] a = keys(c);
		assertEquals(2, a[ActionSpace.FORWARD]);
		assertEquals(0, a[ActionSpace.SPRINT]);
		assertEquals(0, a[ActionSpace.ATTACK]);
	}

	@Test
	void reSprintsThenSwingsAtTheEdge() {
		ComboSpacing c = after(14);
		// Its sword back at the edge, but not sprinting (after its S): W and the sprint again, no swing yet.
		assertTrue(c.tick(place(2.8, 0, 1f, false), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.RUN_UP, c.decision());
		int[] a = keys(c);
		assertEquals(1, a[ActionSpace.FORWARD]);
		assertEquals(1, a[ActionSpace.SPRINT]);
		assertEquals(0, a[ActionSpace.ATTACK]);
		// The next tick, sprinting, a run-up's first step nearer (2.68): the swing.
		assertTrue(c.tick(place(2.68, 0, 1f, true), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.SWING, c.decision());
		a = keys(c);
		assertEquals(1, a[ActionSpace.ATTACK]);
		assertEquals(1, a[ActionSpace.FORWARD]);
		assertEquals(1, a[ActionSpace.SPRINT]);
	}

	@Test
	void swingsAtTheEdgeTheTickTheSwordIsReady() {
		ComboSpacing c = after(13);
		assertTrue(c.tick(place(2.9, 0, 0.96f, true), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.SWING, c.decision());
		assertTrue(c.attack());
	}

	@Test
	void noSwingTooCloseWithTheirSwordEmpty() {
		ComboSpacing c = after(13);
		// Sprinting, its sword ready, them at 2.2, their sword not back, not coming in. Run 123b (SWING_ANY_IN_REACH): a ready sword
		// with the pick on them swings anyway (the user: swing when ready in reach); before it, S back to the edge.
		assertTrue(c.tick(place(2.2, 0, 1f, true), READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.SWING_ANY_IN_REACH ? ComboSpacing.SWING : ComboSpacing.S_TAP, c.decision());
		assertEquals(ComboSpacing.SWING_ANY_IN_REACH, c.attack());
	}

	@Test
	void hitsFirstWhenTheyComeInWithTheirSwordReady() {
		ComboSpacing c = after(13);
		Duel d = place(2.2, 0.2, 1f, true);
		d.them.charge = 1f;
		assertTrue(c.tick(d, READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.SWING, c.decision());
	}

	@Test
	void noSwingOutOfReachOrWithoutThePick() {
		ComboSpacing c = after(13);
		assertTrue(c.tick(place(3.4, 0, 1f, true), READY, false, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.CHASE, c.decision());
		assertFalse(c.attack());
		assertEquals(1, keys(c)[ActionSpace.FORWARD]);
		// At the edge, sprinting, sword ready, but the game's pick not on them: no swing (the whiff guard).
		assertTrue(c.tick(place(2.8, 0, 1f, true), READY, false, false, K2_LAG, LAG));
		assertFalse(c.attack());
		// Held by a guard: no swing either.
		assertTrue(c.tick(place(2.8, 0, 1f, true), READY, true, true, K2_LAG, LAG));
		assertFalse(c.attack());
	}

	@Test
	void chasesThemWhenTheyFlewFar() {
		ComboSpacing c = after(8);
		// Its sword 3 ticks from back, them 5.5 away going away: in at a full sprint.
		assertTrue(c.tick(place(5.5, -0.3, 0.72f, true), READY, false, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.CHASE, c.decision());
		int[] a = keys(c);
		assertEquals(1, a[ActionSpace.FORWARD]);
		assertEquals(1, a[ActionSpace.SPRINT]);
	}

	@Test
	void waitsWhenARunNowWouldComeTooEarly() {
		ComboSpacing c = after(6);
		// Its sword 8 ticks from back, them at 3.2 standing: a sprint now would be on top of them early; it stands.
		assertTrue(c.tick(place(3.2, 0, 0.3f, false), READY, false, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.WAIT, c.decision());
		assertEquals(0, keys(c)[ActionSpace.FORWARD]);
	}

	@Test
	void k2NoSwingAtOneFallingAndBackOffFromOneFallingAtIt() {
		ComboSpacing c = after(13);
		Duel d = place(2.8, 0, 1f, true);
		d.them.onGround = false;
		d.them.y = 0.8;
		d.them.vy = -0.2;
		d.measure();
		assertTrue(c.tick(d, READY, true, false, K2_LAG, LAG));
		assertFalse(c.attack());
		// Falling AT it: S (kept out of reach until they land).
		d.them.vx = -0.3;
		d.measure();
		assertTrue(c.tick(d, READY, true, false, K2_LAG, LAG));
		assertEquals(ComboSpacing.HELD, c.decision());
		assertEquals(2, keys(c)[ActionSpace.FORWARD]);
	}

	@Test
	void neverJumpsAndNeverSwingsUncharged() {
		Random r = new Random(123);
		for (int n = 0; n < 2000; n++) {
			ComboSpacing c = after(1 + r.nextInt(20));
			Duel d = place(0.5 + 6 * r.nextDouble(), -0.6 + 1.0 * r.nextDouble(), r.nextFloat(), r.nextBoolean());
			d.them.onGround = r.nextBoolean();
			d.them.vy = d.them.onGround ? 0 : -0.4 + 0.8 * r.nextDouble();
			d.them.charge = r.nextFloat();
			d.measure();
			boolean pick = d.reach <= Duel.REACH && r.nextBoolean();
			c.tick(d, READY, pick, r.nextInt(5) == 0, K2_LAG, r.nextInt(4));
			int[] a = keys(c);
			if (c.active()) {
				assertEquals(0, a[ActionSpace.JUMP]);
				if (c.attack()) {
					assertTrue(d.me.charge >= READY, "swing uncharged");
					assertTrue(pick, "swing without the pick");
					assertTrue(d.reach <= Duel.REACH, "swing out of reach");
				}
			}
		}
	}

	@Test
	void endsOutOfRangeOnceSettledOrAfterHoldMost() {
		ComboSpacing c = new ComboSpacing();
		c.hitDealt();
		for (int i = 1; i < ComboSpacing.RANGE_QUIET; i++) {
			assertTrue(c.tick(place(6.0, -0.2, 0.5f, true), READY, false, false, K2_LAG, LAG), "a flight past 5 is not the end yet");
		}
		assertFalse(c.tick(place(6.0, 0, 1f, true), READY, false, false, K2_LAG, LAG));
		ComboSpacing c2 = new ComboSpacing();
		c2.hitDealt();
		int held = 0;
		for (int i = 0; i < 100; i++) {
			held += c2.tick(place(3.5, 0, 0.5f, true), READY, false, false, K2_LAG, LAG) ? 1 : 0;
		}
		assertEquals(ComboSpacing.HOLD_MOST, held);
	}

	@Test
	void targetIsItsKeysSure() {
		ComboSpacing c = after(13);
		c.tick(place(2.9, 0, 1f, true), READY, true, false, K2_LAG, LAG);
		float[] t = new float[ActionSpace.TOTAL_LOGITS];
		assertEquals(ComboSpacing.HEADS, c.target(t));
		assertEquals(1f, t[ActionSpace.OFFSETS[ActionSpace.ATTACK] + 1]);
		assertEquals(1f, t[ActionSpace.OFFSETS[ActionSpace.FORWARD] + 1]);
		assertEquals(1f, t[ActionSpace.OFFSETS[ActionSpace.SPRINT] + 1]);
		assertEquals(1f, t[ActionSpace.OFFSETS[ActionSpace.JUMP]]);
	}

	@Test
	void tallyCountsCombosAndBands() {
		ComboTally t = new ComboTally();
		// Three hits in a row (sprint at 2.8, 2.7, crit), then theirs 3 ticks after the third; then a lone sprint hit at 1.5 and theirs 2
		// ticks later; then a sprint hit at 2.3 and nothing within 20.
		t.tick();
		t.ownHit(Events.SPRINT, 2.8);
		for (int i = 0; i < 14; i++) {
			t.tick();
		}
		t.ownHit(Events.SPRINT, 2.7);
		for (int i = 0; i < 14; i++) {
			t.tick();
		}
		t.ownHit(Events.CRIT, 1.0);
		for (int i = 0; i < 3; i++) {
			t.tick();
		}
		t.theirHit();
		t.tick();
		t.ownHit(Events.SPRINT, 1.5);
		t.tick();
		t.tick();
		t.theirHit();
		t.tick();
		t.ownHit(Events.SPRINT, 2.3);
		for (int i = 0; i < 25; i++) {
			t.tick();
		}
		t.endFight();
		assertEquals(1, t.combos(2));
		assertEquals(1, t.combos(3));
		assertEquals(0, t.combos(5));
		assertEquals(3, t.longest());
		assertEquals(3.0, t.mean(), 1e-9);
		assertEquals(2, t.hits(ComboTally.SPRINT_EDGE));
		assertEquals(2, t.followed(ComboTally.SPRINT_EDGE));
		assertEquals(0, t.theirsNext(ComboTally.SPRINT_EDGE));
		assertEquals(1, t.theirsNext(ComboTally.CRIT));
		assertEquals(1, t.theirsNext(ComboTally.SPRINT_NEAR));
		assertEquals(1, t.hits(ComboTally.SPRINT_MID));
		assertEquals(0, t.followed(ComboTally.SPRINT_MID));
		assertEquals(0.5, t.edgeShare(), 1e-9);
		assertTrue(t.line().contains("combos 2+ 1, 3+ 1"));
	}
}
