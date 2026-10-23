package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.SyntheticEye;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FighterTest {
	private static final int FIGHT = ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH, ActionSpace.FORWARD,
			ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.ATTACK);

	private static BodyState body(float charge) {
		BodyState b = new BodyState();
		b.onGround = true;
		b.charge = charge;
		b.health = 1f;
		return b;
	}

	/** Runs an untrained network against a standing opponent; returns {swings, swings with the weapon ready, ticks strafing left, right}. */
	private static int[] spar(boolean assist) {
		Config cfg = new Config();
		cfg.fightMixAssist = assist;
		// With every swing left to the network, as before there was a rule about the weapon's charge:
		// what is tested here is the plan, against a network that never waits.
		cfg.swingMinCharge = 0f;
		// Flattens the untrained network's choices: it then clicks about every other tick and
		// strafes at random, which is the worst case the plan has to cope with.
		cfg.playTemperature = 50f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, false, 21);
			// With a plan that may hold swings back, as no stage's does any more: the helping still has to work.
			fighter.planHolds(true);
			assertEquals(assist, fighter.assists());
			MaskFrame frame = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3, 180f, 180f, 0f, frame);

			int[] out = new int[4];
			int sinceSwing = 100;
			float charge = 1f;
			for (int tick = 0; tick < 4000; tick++) {
				int[] a = fighter.decide(frame, body(charge));
				assertEquals(0, a[ActionSpace.JUMP], "a locked head never moves");
				if (a[ActionSpace.STRAFE] != 0) {
					out[1 + a[ActionSpace.STRAFE]]++;
				}
				sinceSwing++;
				if (a[ActionSpace.ATTACK] == 1) {
					out[0]++;
					if (charge >= MixIdea.READY_CHARGE) {
						out[1]++;
					}
					sinceSwing = 0;
				}
				// A sword recharges over twelve and a half ticks.
				charge = Math.min(1f, sinceSwing / 12.5f);
			}
			return out;
		}
	}

	@Test
	void anUntrainedPlanIsCarriedOutForIt() {
		int[] plain = spar(false);
		int[] helped = spar(true);
		assertTrue(plain[0] > 1000 && plain[1] < plain[0] * 0.1f, "left alone it never waits for the weapon: " + plain[1] + "/" + plain[0]);
		// Six swings in ten carry a wait, and those cannot come out before the weapon is ready.
		assertTrue(helped[0] > 100, "it still swings: " + helped[0]);
		assertTrue(helped[1] > helped[0] * 0.45f, "most swings now wait: " + helped[1] + "/" + helped[0]);
		// Eight swings in ten are followed by a strafe out and back, which fills the recharge.
		assertTrue(helped[2] + helped[3] > 3100, "it strafes nearly all the time: " + helped[2] + "+" + helped[3]);
		assertTrue(plain[2] + plain[3] < 2900, "more than it would by itself: " + plain[2] + "+" + plain[3]);
		assertTrue(helped[2] > 800 && helped[3] > 800, "both ways: " + helped[2] + "/" + helped[3]);
	}

	/** Swings an untrained network makes over 400 ticks with the opponent in front of it and the weapon at this charge. */
	private static int swingsAt(float charge, float swingMinCharge) {
		Config cfg = new Config();
		cfg.swingMinCharge = swingMinCharge;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			MaskFrame frame = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3, 180f, 180f, 0f, frame);
			int swings = 0;
			for (int tick = 0; tick < 400; tick++) {
				swings += fighter.decide(frame, body(charge))[ActionSpace.ATTACK];
			}
			return swings;
		}
	}

	@Test
	void theWeaponIsNeverSwungBeforeItIsBack() {
		// An untrained network swings now and then whatever the weapon is doing; the fighter does not.
		assertTrue(swingsAt(0.2f, 0f) > 0, "left to the network, it does swing early");
		assertEquals(0, swingsAt(0.2f, SwingGate.MIN_CHARGE));
		// Eleven ticks after the last swing a sword is at 0.88: enough for the game, a tick short of the rule.
		assertEquals(0, swingsAt(11f / 12.5f, SwingGate.MIN_CHARGE));
		assertTrue(swingsAt(12f / 12.5f, SwingGate.MIN_CHARGE) > 0, "twelve ticks after it, the swing is the network's again");
		assertTrue(swingsAt(1f, SwingGate.MIN_CHARGE) > 0);
		// Since 18 October the network has that tick: eleven, and not ten.
		assertEquals(0, swingsAt(10f / 12.5f, SwingGate.EARLY_CHARGE));
		assertTrue(swingsAt(11f / 12.5f, SwingGate.EARLY_CHARGE) > 0);
	}

	/** On someone's server it has a tick in hand: not at eleven ticks, but at twelve. */
	@Test
	void onAServerItHasATickInHand() {
		Config cfg = new Config();
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			fighter.swingNoEarlierThan(cfg.swingMinChargeOnline);
			MaskFrame frame = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3, 180f, 180f, 0f, frame);
			int atTwelve = 0;
			int atThirteen = 0;
			// Thirteen ticks on a server since the evening of 18 October (twelve gave weak hits at 105 ms).
			for (int tick = 0; tick < 400; tick++) {
				atTwelve += fighter.decide(frame, body(12f / 12.5f))[ActionSpace.ATTACK];
			}
			for (int tick = 0; tick < 400; tick++) {
				atThirteen += fighter.decide(frame, body(1f))[ActionSpace.ATTACK];
			}
			assertEquals(0, atTwelve);
			assertTrue(atThirteen > 0);
			// It never asks for less than the rule does.
			fighter.swingNoEarlierThan(0f);
			int early = 0;
			for (int tick = 0; tick < 400; tick++) {
				early += fighter.decide(frame, body(0.5f))[ActionSpace.ATTACK];
			}
			assertEquals(0, early);
		}
	}

	/** For a server that still counts its swings as early, it can be told to wait some ticks more once the weapon is back. */
	@Test
	void itCanBeToldToWaitSomeTicksMoreOnceTheWeaponIsBack() {
		assertEquals(0, new Config().swingExtraTicksOnline, "not unless a server needs it");
		for (int extra : new int[] {0, 2}) {
			Config cfg = new Config();
			try (Learner learner = new Learner(cfg, 7, 1)) {
				Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
				fighter.swingNoEarlierThan(cfg.swingMinChargeOnline, extra);
				MaskFrame frame = new MaskFrame();
				new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3, 180f, 180f, 0f, frame);
				// The weapon is back for two ticks at a time and then not: never long enough, with two ticks to wait.
				int swings = 0;
				for (int tick = 0; tick < 1200; tick++) {
					swings += fighter.decide(frame, body(tick % 3 == 2 ? 0.5f : 1f))[ActionSpace.ATTACK];
				}
				if (extra == 0) {
					assertTrue(swings > 0);
				} else {
					assertEquals(0, swings);
				}
				// Back for good, it swings.
				int later = 0;
				for (int tick = 0; tick < 400; tick++) {
					later += fighter.decide(frame, body(1f))[ActionSpace.ATTACK];
				}
				assertTrue(later > 0);
			}
		}
	}

	@Test
	void theRuleTakesTheAttackKeyAndNothingElse() {
		int all = ActionSpace.ALL_HEADS;
		assertEquals(all, SwingGate.keys(all, 1f, SwingGate.MIN_CHARGE));
		assertEquals(all & ~ActionSpace.mask(ActionSpace.ATTACK), SwingGate.keys(all, 0.5f, SwingGate.MIN_CHARGE));
		assertEquals(all, SwingGate.keys(all, 0.5f, 0f), "no rule, no key taken");
		int aim = ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		assertEquals(aim, SwingGate.keys(aim, 0f, SwingGate.MIN_CHARGE), "a fighter without the attack key loses nothing");
	}

	@Test
	void aTrainedPlanIsLeftToTheNetworkAndShownToIt() {
		Config cfg = new Config();
		cfg.swingMinCharge = 0f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			assertFalse(fighter.assists());
			MaskFrame frame = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3, 180f, 180f, 0f, frame);
			int earlySwings = 0;
			for (int tick = 0; tick < 400; tick++) {
				int[] a = fighter.decide(frame, body(0.2f));
				if (a[ActionSpace.ATTACK] == 1) {
					earlySwings++;
				}
				assertTrue(Math.abs(fighter.yawRate()) <= 40f && Math.abs(fighter.pitchRate()) <= 18f);
			}
			assertTrue(earlySwings > 0, "nothing is filtered: an untrained network does swing early");
		}
	}

	@Test
	void resetPutsTheHandAtRest() {
		Config cfg = new Config();
		try (Learner learner = new Learner(cfg, 9, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, false, 1);
			MaskFrame frame = new MaskFrame();
			for (int tick = 0; tick < 50; tick++) {
				fighter.decide(frame, body(1f));
			}
			fighter.reset();
			assertEquals(0f, fighter.yawRate());
			assertEquals(0f, fighter.pitchRate());
			assertEquals(ActionSpace.DEFAULTS[ActionSpace.YAW], fighter.action()[ActionSpace.YAW]);
		}
	}
}
