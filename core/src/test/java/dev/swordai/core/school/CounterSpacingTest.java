package dev.swordai.core.school;

import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.BackNudge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run 124: the counter-spacing controller in their combo and as they close in, and its tally. */
class CounterSpacingTest {
	/** It at the origin facing +x on the ground, them on the ground with {@code reach} blocks from its eye to their box, coming at it at {@code vIn}. */
	private static Duel duel(double reach, double vIn) {
		Duel d = new Duel();
		d.me.x = 0;
		d.me.y = 0;
		d.me.z = 0;
		d.me.yaw = -90f;
		d.me.onGround = true;
		d.them.x = reach + 0.3;
		d.them.y = 0;
		d.them.z = 0;
		d.them.vx = -vIn;
		d.them.onGround = true;
		d.measure();
		return d;
	}

	/** In their combo: hit 3 ticks ago, none of its own since, its sword at {@code charge}. */
	private static CounterSpacing.Input theirCombo(Duel d, float charge, boolean pick) {
		CounterSpacing.Input in = new CounterSpacing.Input();
		in.duel = d;
		in.charge = charge;
		in.readyCharge = 0.95f;
		in.pick = pick;
		in.sinceTaken = 3;
		in.sinceDealt = 1000;
		return in;
	}

	@Test
	void moments() {
		CounterSpacing.Input in = theirCombo(duel(3.5, 0.0), 1f, false);
		assertEquals(CounterSpacing.THEIR_COMBO, CounterSpacing.moment(in));
		// Their hit's own tick is the jump reset's.
		in.sinceTaken = 0;
		assertEquals(CounterSpacing.NO_MOMENT, CounterSpacing.moment(in));
		// Its own combo is not this controller's (run 123's).
		in.sinceTaken = 1000;
		in.sinceDealt = 4;
		in.duel = duel(3.5, 0.3);
		assertEquals(CounterSpacing.NO_MOMENT, CounterSpacing.moment(in));
		// Closing in before a trade: they come at it within REACH + CLOSE_BAND of their reach.
		in.sinceDealt = 1000;
		assertEquals(CounterSpacing.CLOSING, CounterSpacing.moment(in));
		in.duel = duel(3.5, 0.0);
		assertEquals(CounterSpacing.NO_MOMENT, CounterSpacing.moment(in));
		in.duel = duel(4.5, 0.3);
		assertEquals(CounterSpacing.NO_MOMENT, CounterSpacing.moment(in));
		// Their combo past COMBO_RANGE: the engagement is over.
		CounterSpacing.Input far = theirCombo(duel(6.5, 0.0), 1f, false);
		assertEquals(CounterSpacing.NO_MOMENT, CounterSpacing.moment(far));
	}

	@Test
	void readySwordSprintsInAndSwingsAtThePick() {
		CounterSpacing c = new CounterSpacing();
		CounterSpacing.Plan p = c.plan(theirCombo(duel(2.9, 0.2), 1f, true));
		assertEquals(CounterSpacing.SWING, p.why);
		assertEquals(1, p.forward);
		assertEquals(1, p.sprint);
		assertEquals(0, p.strafe);
		assertEquals(1, p.attack);
		assertTrue((p.heads & (1 << ActionSpace.ATTACK)) != 0);
		assertEquals(0, p.heads & (1 << ActionSpace.JUMP));
		// No pick (out of reach, or the crosshair off them): the run-in, the swing left alone.
		p = c.plan(theirCombo(duel(3.6, 0.2), 1f, false));
		assertEquals(CounterSpacing.RUN_IN, p.why);
		assertEquals(0, p.heads & (1 << ActionSpace.ATTACK));
		// K2 or the jump-in guard: no swing.
		CounterSpacing.Input held = theirCombo(duel(2.9, 0.2), 1f, true);
		held.swingHeld = true;
		p = c.plan(held);
		assertEquals(CounterSpacing.RUN_IN, p.why);
		assertEquals(0, p.attack);
		// Only charged swings: 0.94 with the pick in reach is not a swing (and too close to sprint to the edge first).
		p = c.plan(theirCombo(duel(2.9, 0.2), 0.94f, true));
		assertNotEquals(CounterSpacing.SWING, p.why);
	}

	@Test
	void runsInEarlyToArriveAsTheSwordComesBack() {
		// 1.5 blocks out, they come at 0.28: about 2.7 ticks to the edge; the sword is back in under 2: start now.
		CounterSpacing.Input in = theirCombo(duel(4.5, 0.28), 0.8f, false);
		assertTrue(CounterSpacing.arrivesReady(in));
		assertEquals(CounterSpacing.RUN_IN, new CounterSpacing().plan(in).why);
		// The sword far from back at the edge: not yet.
		in = theirCombo(duel(3.4, 0.28), 0.5f, false);
		assertFalse(CounterSpacing.arrivesReady(in));
		// Already in reach: never early (the swing would not be there).
		in = theirCombo(duel(2.5, 0.0), 0.9f, false);
		assertFalse(CounterSpacing.arrivesReady(in));
	}

	@Test
	void swordNotReadyTapsSNeverLong() {
		CounterSpacing c = new CounterSpacing();
		int longest = 0;
		int run = 0;
		int taps = 0;
		for (int t = 0; t < 40; t++) {
			CounterSpacing.Plan p = c.plan(theirCombo(duel(2.4, 0.2), 0.3f, false));
			assertTrue(p.why == CounterSpacing.TAP_BACK || p.why == CounterSpacing.REST, "inside their reach, not ready: " + p.why);
			assertEquals(0, p.sprint);
			assertNotEquals(0, p.strafe, "strafing so their swing misses (T6a)");
			run = p.forward == BackNudge.BACK ? run + 1 : 0;
			taps += run == 1 ? 1 : 0;
			longest = Math.max(longest, run);
			c.pressed(p.forward, p.strafe);
		}
		assertEquals(CounterSpacing.S_TAP_MOST, longest);
		assertTrue(taps >= 7, "taps " + taps);
	}

	@Test
	void sAlreadyHeldLongIsCutToARest() {
		CounterSpacing c = new CounterSpacing();
		for (int t = 0; t < 6; t++) {
			c.pressed(BackNudge.BACK, 0);
		}
		CounterSpacing.Plan p = c.plan(theirCombo(duel(2.4, 0.2), 0.3f, false));
		assertEquals(CounterSpacing.REST, p.why);
		assertEquals(0, p.forward);
	}

	@Test
	void strafeAwayFromTheirRunThenSwitches() {
		CounterSpacing c = new CounterSpacing();
		Duel d = duel(3.6, 0.0);
		// They run to its right (+z for one facing +x): it strafes left (A = 1) first.
		d.them.vz = 0.1;
		d.measure();
		CounterSpacing.Plan p = c.plan(theirCombo(d, 0.3f, false));
		assertEquals(CounterSpacing.HOLD, p.why);
		assertEquals(0, p.forward);
		assertEquals(1, p.strafe);
		int switchedAt = -1;
		for (int t = 0; t < 30 && switchedAt < 0; t++) {
			p = c.plan(theirCombo(d, 0.3f, false));
			if (p.strafe == 2) {
				switchedAt = t;
			}
			c.pressed(p.forward, p.strafe);
		}
		assertEquals(CounterSpacing.STRAFE_TICKS, switchedAt);
		d.them.vz = -0.1;
		d.measure();
		assertEquals(2, new CounterSpacing().plan(theirCombo(d, 0.3f, false)).strafe);
	}

	@Test
	void outPastTheBandWalksBackStraight() {
		CounterSpacing.Plan p = new CounterSpacing().plan(theirCombo(duel(4.8, 0.0), 0.2f, false));
		assertEquals(CounterSpacing.WALK_IN, p.why);
		assertEquals(1, p.forward);
		assertEquals(0, p.sprint);
		assertEquals(0, p.strafe);
	}

	@Test
	void standsAsideWhereTheRulesComeFirst() {
		CounterSpacing c = new CounterSpacing();
		CounterSpacing.Input in = theirCombo(duel(2.9, 0.2), 1f, true);
		in.yield = true;
		assertEquals(CounterSpacing.YIELD, c.plan(in).why);
		assertFalse(c.plan().active());
		in = theirCombo(duel(2.9, 0.2), 1f, true);
		in.duel.me.onGround = false;
		assertFalse(c.plan(in).active());
		in = theirCombo(duel(2.9, 0.2), 1f, true);
		in.duel.them.onGround = false;
		in.duel.them.vy = 0.3;
		assertFalse(c.plan(in).active(), "their jump-in rising: T8 / the rules");
		in = theirCombo(duel(2.9, 0.2), 1f, true);
		in.jumpIn = true;
		assertFalse(c.plan(in).active());
		in = theirCombo(duel(2.9, 0.2), 1f, true);
		in.themFalling = true;
		assertFalse(c.plan(in).active(), "K2");
		// Not facing them: W and S would go elsewhere.
		in = theirCombo(duel(2.9, 0.2), 1f, true);
		in.duel.me.yaw = 0f;
		CounterSpacing.Plan p = c.plan(in);
		assertEquals(CounterSpacing.TURN, p.why);
		assertFalse(p.active());
		// The rules' jump reset, punish crit, keep, run at a jumper and jumps come first; the answer swing and the back-out do not.
		assertTrue((CounterSpacing.RULES_FIRST & (1 << 3)) != 0);
		assertTrue((CounterSpacing.RULES_FIRST & (1 << 13)) != 0);
		assertTrue((CounterSpacing.RULES_FIRST & (1 << 1)) != 0);
		assertEquals(0, CounterSpacing.RULES_FIRST & ((1 << 0) | (1 << 4) | (1 << 5) | (1 << 9)));
	}

	@Test
	void keepsAndReplaces() {
		for (Technique t : new Technique[] {Technique.B1, Technique.K2, Technique.K3, Technique.T9A, Technique.C7, Technique.T1C, Technique.T8}) {
			assertTrue(CounterSpacing.keeps(t), t.id);
		}
		for (Technique t : new Technique[] {Technique.BASE, Technique.T4, Technique.T6, Technique.T6A, Technique.T7, Technique.C3, Technique.C4}) {
			assertFalse(CounterSpacing.keeps(t), t.id);
		}
		assertTrue(CounterSpacing.replaces(Technique.C3));
		assertTrue(CounterSpacing.replaces(Technique.C4));
		assertTrue(CounterSpacing.replaces(Technique.C2));
		assertFalse(CounterSpacing.replaces(Technique.T6));
	}

	@Test
	void targetAndKeys() {
		CounterSpacing c = new CounterSpacing();
		CounterSpacing.Plan p = c.plan(theirCombo(duel(2.9, 0.2), 1f, true));
		float[] target = new float[ActionSpace.TOTAL_LOGITS];
		int heads = CounterSpacing.target(p, target);
		assertEquals(CounterSpacing.HEADS, heads);
		assertEquals(Teacher.SURE, target[ActionSpace.OFFSETS[ActionSpace.FORWARD] + 1], 1e-6);
		assertEquals(Teacher.SURE, target[ActionSpace.OFFSETS[ActionSpace.SPRINT] + 1], 1e-6);
		assertEquals(Teacher.SURE, target[ActionSpace.OFFSETS[ActionSpace.STRAFE]], 1e-6);
		assertEquals(Teacher.SURE, target[ActionSpace.OFFSETS[ActionSpace.ATTACK] + 1], 1e-6);
		int[] keys = p.keys(new int[ActionSpace.HEADS]);
		assertArrayEquals(new int[] {7, 5, 1, 0, 1, 0, 1}, keys);
		// Not ready: S (no sprint taught without W), the strafe either way, no swing taught.
		p = new CounterSpacing().plan(theirCombo(duel(2.4, 0.2), 0.3f, false));
		heads = CounterSpacing.target(p, target);
		assertEquals(ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.STRAFE), heads);
		assertEquals(Teacher.SURE, target[ActionSpace.OFFSETS[ActionSpace.FORWARD] + BackNudge.BACK], 1e-6);
		assertEquals(Teacher.SURE / 2f, target[ActionSpace.OFFSETS[ActionSpace.STRAFE] + 1], 1e-6);
		assertEquals(Teacher.SURE / 2f, target[ActionSpace.OFFSETS[ActionSpace.STRAFE] + 2], 1e-6);
	}

	@Test
	void tallyCountsTheirRunsBandsEntriesAndLongS() {
		CounterSpacing.Tally t = new CounterSpacing.Tally();
		// Their hit at 1.5 blocks, then theirs again 8 ticks later at 2.8, then its hit 6 ticks later (holding S 5 ticks between).
		t.tick(false, true, 1.5, 1f, 0.95f, false);
		for (int i = 0; i < 7; i++) {
			t.tick(false, false, 3.5, 1f, 0.95f, i < 5);
		}
		t.tick(false, true, 2.8, 1f, 0.95f, false);
		for (int i = 0; i < 5; i++) {
			t.tick(false, false, 3.4, 1f, 0.95f, false);
		}
		t.tick(true, false, 2.9, 1f, 0.95f, false);
		assertEquals(1, t.runs2);
		assertEquals(0, t.runs3);
		assertEquals(2, t.longest);
		assertEquals(1, t.after[0][0], "their next after the close hit");
		assertEquals(1, t.after[2][1], "its next after the edge hit");
		assertEquals(1, t.windows);
		assertEquals(1, t.longS);
		assertEquals(5, t.sTicks);
		// Into its reach with its sword ready (outside its own combo), then its hit first.
		CounterSpacing.Tally e = new CounterSpacing.Tally();
		e.tick(false, false, 4.0, 1f, 0.95f, false);
		e.tick(false, false, 2.9, 1f, 0.95f, false);
		e.tick(true, false, 2.7, 1f, 0.95f, false);
		assertEquals(1, e.entries[0]);
		// Back out and in again with the sword empty: not counted; nothing within COMBO_TICKS: neither.
		e.tick(false, false, 4.0, 0.2f, 0.95f, false);
		e.tick(false, false, 2.9, 0.2f, 0.95f, false);
		assertEquals(1, e.entries[0] + e.entries[1] + e.entries[2] + e.entries[3]);
		e.endFight();
		e.clear();
		e.tick(false, false, 4.0, 1f, 0.95f, false);
		for (int i = 0; i < CounterSpacing.COMBO_TICKS + 1; i++) {
			e.tick(false, false, 2.9, 1f, 0.95f, false);
		}
		assertEquals(1, e.entries[3]);
		assertTrue(e.line().contains("their runs of hits on it"));
	}

	@Test
	void switchOffDoesNothingWithoutADuel() {
		CounterSpacing.Input in = new CounterSpacing.Input();
		assertFalse(new CounterSpacing().plan(in).active());
	}
}
