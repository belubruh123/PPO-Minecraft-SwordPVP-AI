package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.RewardModel;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Stages;
import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.SyntheticEye;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hit, and not yet having hit back, the fighter swings as soon as its sword is back and the game has
 * the opponent under its crosshair. A rule, so that a combo is never sat through with a hit there to
 * be made.
 */
class AnswerRuleTest {
	private static final int FIGHT = ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH, ActionSpace.FORWARD,
			ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.ATTACK);

	private static BodyState body(float charge) {
		BodyState b = new BodyState();
		b.onGround = true;
		b.charge = charge;
		b.health = 1f;
		return b;
	}

	/** The picture of an opponent standing this far in front. */
	private static MaskFrame seen(double blocks) {
		MaskFrame frame = new MaskFrame();
		new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, blocks, 180f, 180f, 0f, frame);
		return frame;
	}

	private static float[] vec(boolean hit) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = hit ? 1f / 3f : 0f;
		return v;
	}

	@Test
	void itGoesByTheGamesOwnPickAndByItsOwnCountOfHits() {
		// Hit, and the game has the opponent under the crosshair within reach: a swing is asked for.
		assertTrue(AnswerRule.applies(vec(true), true));
		// Hit, but nothing under the crosshair: a swing would be thrown away, and none is asked for.
		assertFalse(AnswerRule.applies(vec(true), false));
		// Not hit, or having hit back: the swing is the network's own business.
		assertFalse(AnswerRule.applies(vec(false), true));
		assertFalse(AnswerRule.applies(vec(false), false));
		assertTrue(AnswerRule.owesAnAnswer(vec(true)));
	}

	@Test
	void hitItSwingsTheMomentItsSwordIsBackAndTheOpponentIsUnderItsCrosshair() {
		Config cfg = new Config();
		assertTrue(cfg.answerRule);
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			MaskFrame near = seen(3.0);
			// Not hit: an untrained network swings now and then, far from every tick, crosshair on the opponent or not.
			fighter.crosshairOnOpponent(true);
			int free = 0;
			for (int tick = 0; tick < 200; tick++) {
				free += fighter.decide(near, body(1f))[ActionSpace.ATTACK];
				assertFalse(fighter.answerForced());
			}
			assertTrue(free < 150, "left to the network: " + free);
			// Hit: with the sword back and the opponent under its crosshair, every tick is a swing until it lands one.
			fighter.hitTaken();
			for (int tick = 0; tick < 20; tick++) {
				assertEquals(1, fighter.decide(near, body(1f))[ActionSpace.ATTACK], "tick " + tick);
				assertTrue(fighter.answerForced());
			}
			// The sword not back: no swing, the other rule says.
			fighter.hitTaken();
			for (int tick = 0; tick < 10; tick++) {
				assertEquals(0, fighter.decide(near, body(0.5f))[ActionSpace.ATTACK]);
				assertFalse(fighter.answerForced());
			}
			// The opponent not under the crosshair: nothing is forced, and what it swings is its own.
			fighter.hitTaken();
			fighter.crosshairOnOpponent(false);
			int atNothing = 0;
			for (int tick = 0; tick < 20; tick++) {
				atNothing += fighter.decide(near, body(1f))[ActionSpace.ATTACK];
				assertFalse(fighter.answerForced());
			}
			assertTrue(atNothing < 20);
			// By the time of the click the opponent has gone from under the crosshair: the swing is not made.
			fighter.hitTaken();
			fighter.crosshairOnOpponent(true);
			assertEquals(1, fighter.decide(near, body(1f))[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
			fighter.answerCancelled();
			assertFalse(fighter.answerForced());
			assertEquals(0, fighter.action()[ActionSpace.ATTACK]);
			// It has hit back: it owes nothing, and the swing is its own again.
			fighter.hitTaken();
			fighter.decide(near, body(1f));
			fighter.hitDealt();
			int after = 0;
			for (int tick = 0; tick < 200; tick++) {
				after += fighter.decide(near, body(1f))[ActionSpace.ATTACK];
			}
			assertTrue(after < 150, "its own again: " + after);
		}
		// Switched off, nothing is forced.
		Config off = new Config();
		off.answerRule = false;
		try (Learner learner = new Learner(off, 7, 1)) {
			Fighter fighter = new Fighter(learner, off, FIGHT, true, 21);
			fighter.hitTaken();
			fighter.crosshairOnOpponent(true);
			int swings = 0;
			for (int tick = 0; tick < 20; tick++) {
				swings += fighter.decide(seen(3.0), body(1f))[ActionSpace.ATTACK];
				assertFalse(fighter.answerForced());
			}
			assertTrue(swings < 20);
		}
	}

	private static BodyState inTheAir(float velUp, boolean sprinting) {
		BodyState b = body(1f);
		b.onGround = false;
		b.velUp = velUp;
		b.sprinting = sprinting;
		return b;
	}

	/**
	 * Hit into the air with the opponent under its crosshair, it keeps the swing for the way down: not
	 * on the way up, the sprint let go of on the first tick coming down, and then the swing, which is
	 * the punish crit.
	 */
	@Test
	void inTheAirTheSwingItOwesIsKeptForTheWayDown() {
		Config cfg = new Config();
		assertTrue(cfg.punishRule);
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			MaskFrame near = seen(2.5);
			fighter.crosshairOnOpponent(true);
			fighter.hitTaken();
			// On the way up: no swing, tick after tick, although it owes one and the opponent is there.
			for (int tick = 0; tick < 8; tick++) {
				assertEquals(0, fighter.decide(near, inTheAir(0.3f, true))[ActionSpace.ATTACK], "tick " + tick);
				assertFalse(fighter.answerForced());
			}
			// Coming down and still sprinting: no swing yet, and W and the sprint key are let go of.
			for (int tick = 0; tick < 8; tick++) {
				int[] action = fighter.decide(near, inTheAir(-0.2f, true));
				assertEquals(0, action[ActionSpace.ATTACK]);
				assertEquals(0, action[ActionSpace.FORWARD]);
				assertEquals(0, action[ActionSpace.SPRINT]);
				assertFalse(fighter.answerForced());
			}
			// Coming down with the sprint off: now.
			assertEquals(1, fighter.decide(near, inTheAir(-0.2f, false))[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
			// Hit a second time in a row without having hit back, it is being comboed: the swing is made
			// at once, also in the air, and where backing out of a combo is a rule S is held with it.
			fighter.backOutRule(true);
			fighter.hitTaken();
			int[] action = fighter.decide(near, inTheAir(0.3f, true));
			assertEquals(BackNudge.BACK, action[ActionSpace.FORWARD]);
			assertEquals(1, action[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
			// Nothing under its crosshair: nothing is kept and nothing forced, in the air or out of it.
			fighter.crosshairOnOpponent(false);
			fighter.decide(near, inTheAir(-0.2f, false));
			assertFalse(fighter.answerForced());
		}
		// Switched off, the swing is made the moment the rule applies, as before.
		Config off = new Config();
		off.punishRule = false;
		try (Learner learner = new Learner(off, 7, 1)) {
			Fighter fighter = new Fighter(learner, off, FIGHT, true, 21);
			fighter.crosshairOnOpponent(true);
			fighter.hitTaken();
			assertEquals(1, fighter.decide(seen(2.5), inTheAir(0.3f, true))[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
		}
	}

	/**
	 * With {@code Config.keepWithin}: thrown up by a hit, the swing is kept for the way down only with the opponent
	 * near; farther off it is made at once, while the opponent is still under the crosshair.
	 */
	@Test
	void thrownUpWithTheOpponentFartherThanKeepWithinTheSwingIsMadeAtOnce() {
		Config cfg = new Config();
		cfg.keepWithin = 2.3f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			fighter.crosshairOnOpponent(true);
			// Near: kept for the way down, as before.
			fighter.hitTaken();
			for (int tick = 0; tick < 4; tick++) {
				assertEquals(0, fighter.decide(seen(2.0), inTheAir(0.3f, true))[ActionSpace.ATTACK], "near, tick " + tick);
				assertFalse(fighter.answerForced());
			}
			// Carried out to three blocks on the way up: the swing now.
			assertEquals(1, fighter.decide(seen(3.0), inTheAir(0.25f, true))[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
		}
		float[] far = new float[ObsSpec.VEC];
		MaskFrame frame = seen(3.0);
		ObsBuilder.writeStats(far, frame.stats, frame.stats);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		assertEquals(AnswerRule.KEEP, AnswerRule.when(far, true, false, false, 0f), "0: kept, as before");
		assertEquals(AnswerRule.NOW, AnswerRule.when(far, true, false, false, 2.3f));
	}

	private static StepFacts facts() {
		StepFacts f = new StepFacts();
		f.reachDist = 2.8f;
		f.reachAtAction = 2.8f;
		f.enemyVisible = true;
		f.charge = 1f;
		return f;
	}

	/** How many of the opponent's combos it sat through without one swing is counted; that number is meant to be nought. */
	@Test
	void aComboSatThroughWithoutASwingIsCounted() {
		StageSpec st = Stages.defaults().get(7);
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		// Two hits in a row and never a swing, until the opponent's combo runs out.
		for (int i = 0; i < 2; i++) {
			StepFacts hit = facts();
			hit.hitsTaken = 1;
			m.step(st, hit, t);
		}
		for (int tick = 0; tick < 40; tick++) {
			m.step(st, facts(), t);
		}
		assertEquals(1, t.combosOver);
		assertEquals(1, t.combosUnanswered);
		// The same, but it swung back, at the air: answered, landed or not.
		for (int i = 0; i < 2; i++) {
			StepFacts hit = facts();
			hit.hitsTaken = 1;
			m.step(st, hit, t);
		}
		StepFacts swing = facts();
		swing.clicked = true;
		swing.chargeAtClick = 1f;
		m.step(st, swing, t);
		for (int tick = 0; tick < 40; tick++) {
			m.step(st, facts(), t);
		}
		assertEquals(2, t.combosOver);
		assertEquals(1, t.combosUnanswered);
		// And one that its own hit ended.
		for (int i = 0; i < 3; i++) {
			StepFacts hit = facts();
			hit.hitsTaken = 1;
			m.step(st, hit, t);
		}
		StepFacts back = facts();
		back.clicked = true;
		back.clickOnTarget = true;
		back.chargeAtClick = 1f;
		back.hitsDealt = 1;
		back.hitCharge = 1f;
		back.sprintHit = true;
		m.step(st, back, t);
		assertEquals(3, t.combosOver);
		assertEquals(1, t.combosUnanswered);
		// One hit is no combo, swung at or not.
		StepFacts single = facts();
		single.hitsTaken = 1;
		m.step(st, single, t);
		for (int tick = 0; tick < 40; tick++) {
			m.step(st, facts(), t);
		}
		assertEquals(3, t.combosOver);
		// A swing made before the combo began answers nothing.
		RewardModel fresh = new RewardModel();
		Tally u = new Tally();
		fresh.step(st, swing, u);
		for (int i = 0; i < 2; i++) {
			StepFacts hit = facts();
			hit.hitsTaken = 1;
			fresh.step(st, hit, u);
		}
		fresh.endEpisode(u, false, false);
		assertEquals(1, u.combosOver);
		assertEquals(1, u.combosUnanswered);
		Tally sum = new Tally();
		sum.add(t);
		sum.add(u);
		assertEquals(4, sum.combosOver);
		assertEquals(2, sum.combosUnanswered);
		sum.clear();
		assertEquals(0, sum.combosOver + sum.combosUnanswered);
	}

	@Test
	void anOpponentBackingStraightOutAfterOneHitIsNotOwedASwingButACombo() {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		// It walks backwards at 0.15 a tick while the fighter stands.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.15f * dev.swordai.core.env.BodyState.VELOCITY_SCALE;
		assertTrue(AnswerRule.applies(v, true));
		assertTrue(AnswerRule.opponentBacking(v));
		assertFalse(AnswerRule.applies(v, true, true), "spared");
		assertTrue(AnswerRule.applies(v, true, false), "the switch off: as before");
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		assertTrue(AnswerRule.applies(v, true, true), "a combo is always answered");
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.15f * dev.swordai.core.env.BodyState.VELOCITY_SCALE;
		assertTrue(AnswerRule.applies(v, true, true), "one that comes on is answered");
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 0f;
		assertFalse(AnswerRule.opponentBacking(v));
	}
}
