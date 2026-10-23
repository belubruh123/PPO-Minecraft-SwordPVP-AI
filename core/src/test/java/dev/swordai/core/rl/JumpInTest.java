package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.SyntheticEye;
import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The jumps that are made for the fighter ({@link JumpIn}), and the camera left alone on a far opponent ({@link FarRest}). */
class JumpInTest {
	private static final int ALL = ActionSpace.ALL_HEADS;

	/** What it is shown: on the ground or off it, with the opponent this far away, straight ahead. */
	private static float[] vec(boolean ground, float distance, float charge) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		float height = ViewGeometry.apparentSize(1.8f, distance);
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.1f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = -0.9f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_LEFT] = -0.17f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_RIGHT] = 0.17f * height;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = ground ? 1f : 0f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = charge;
		return v;
	}

	private static float[] running(float distance, float closing) {
		float[] v = vec(true, distance, 1f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 1f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.28f * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -closing * BodyState.VELOCITY_SCALE;
		return v;
	}

	private static float[] air(float distance, float velUp, boolean sprinting, float charge) {
		float[] v = vec(false, distance, charge);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = velUp * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = sprinting ? 1f : 0f;
		return v;
	}

	private static boolean has(int plan, int flag) {
		return (plan & flag) != 0;
	}

	@Test
	void howFarAwayIsReadOffTheOpponentsHeightOnScreen() {
		for (double blocks : new double[] {2.0, 3.5, 4.5, 8.0, 20.0}) {
			MaskFrame frame = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, blocks, 180f, 180f, 0f, frame);
			float[] v = new float[ObsSpec.VEC];
			ObsBuilder.writeStats(v, frame.stats, frame.stats);
			// To its front, which is a quarter of a block nearer than its middle.
			assertEquals(blocks - 0.25, JumpIn.distance(v), 0.08 * blocks + 0.05, "at " + blocks);
		}
		assertEquals(Float.MAX_VALUE, JumpIn.distance(new float[ObsSpec.VEC]), "not on screen: very far");
	}

	@Test
	void atARunAfterAnOpponentItIsNotCatchingItJumpsAndMakesTheHitOnTheWayDownACriticalHit() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		// A few ticks at a run with the opponent backing off, four blocks away.
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			assertEquals(JumpIn.NONE, rule.kind());
			plan = rule.plan(running(3.5f, 0.065f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		assertEquals(JumpIn.CHASE, rule.tookOff());
		// On the way up W and the sprint stay down and the sword is kept, opponent in reach or not.
		plan = rule.plan(air(3.6f, 0.3f, true, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING) && has(plan, JumpIn.NO_JUMP));
		assertEquals(JumpIn.NONE, rule.tookOff(), "only the tick it takes off on");
		// Coming down with nothing in reach: still at a run.
		plan = rule.plan(air(3.4f, -0.08f, true, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		// The opponent in reach: the sprint is let go of, and on the next tick it swings.
		plan = rule.plan(air(3.2f, -0.15f, true, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(3.1f, -0.23f, false, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.SWING));
		// Down again: its keys are its own.
		assertEquals(0, rule.plan(vec(true, 3.0f, 0.1f), ALL, true, false, 1));
		assertEquals(JumpIn.NONE, rule.kind());
	}

	/** What it is shown this many ticks after a hit of its own, and this many after one it took. */
	private static float[] after(float[] v, int sinceDealt, int sinceTaken) {
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = Math.max(0f, 1f - sinceDealt / BodyState.EVENT_FADE_TICKS);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = Math.max(0f, 1f - sinceTaken / BodyState.EVENT_FADE_TICKS);
		return v;
	}

	/**
	 * The chase jump as the next hit of a combo of its own: eight ticks after its hit, the opponent
	 * backing off beyond its reach. Not after an opponent that is only being thrown back by that hit.
	 */
	@Test
	void theChaseJumpIsAlsoTheNextHitOfItsOwnCombo() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			float[] v = after(running(3.5f, 0.065f), 8 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.6f;
			plan = rule.plan(v, ALL, false, false, 1);
		}
		assertTrue(has(plan, JumpIn.JUMP), "after one that backs off");
		assertEquals(JumpIn.CHASE, rule.tookOff());
		// Switched off, it waits out the fifteen ticks as before.
		JumpIn old = new JumpIn(true, true, 0f, 1);
		old.chaseInCombo(false);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			float[] v = after(running(3.5f, 0.065f), 8 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.6f;
			assertEquals(0, old.plan(v, ALL, false, false, 1));
		}
		// Thrown back by the hit, the opponent is going away fast, and is no runner: no jump from far off.
		JumpIn thrown = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = after(running(5.0f, -0.2f), 6 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.6f;
			assertEquals(0, thrown.plan(v, ALL, false, false, 1), "tick " + tick);
		}
	}

	/** Hit a second time in a row with its feet on the ground, it jumps with that hit, W and the sprint key down. */
	@Test
	void aHitOfAComboItIsTakingIsJumpedWith() {
		JumpReset rule = new JumpReset(1f, 3);
		// The first hit: nothing. That one it takes as it comes.
		float[] first = after(vec(true, 2.5f, 1f), 100, 0);
		first[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertEquals(0, rule.plan(first, ALL, true));
		assertFalse(rule.tookOff());
		// The second, on the tick it learns of it: the jump, W and the sprint.
		float[] second = after(vec(true, 2.5f, 1f), 100, 0);
		second[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		assertTrue(BackNudge.inCombo(second));
		int plan = rule.plan(second, ALL, true);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN));
		assertTrue(rule.tookOff());
		// In the air of it W and the sprint stay down, and the jump key is up.
		float[] up = after(air(2.2f, 0.3f, true, 1f), 100, 1);
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		plan = rule.plan(up, ALL, false);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_JUMP) && !has(plan, JumpIn.JUMP));
		assertFalse(rule.tookOff());
		// Down again: its keys are its own.
		float[] down = after(vec(true, 2.0f, 1f), 100, 12);
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 2f / 3f;
		assertEquals(0, rule.plan(down, ALL, false));
		assertEquals(0, rule.plan(down, ALL, false));
		// A hit that finds it in the air cannot be jumped with.
		float[] inAir = after(air(2.5f, 0.1f, true, 1f), 100, 0);
		inAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		assertEquals(0, rule.plan(inAir, ALL, true));
		// Nor where the jump key is not its own, and at a chance of nought never.
		assertEquals(0, rule.plan(second, ALL & ~ActionSpace.mask(ActionSpace.JUMP), true));
		JumpReset never = new JumpReset(0f, 3);
		assertEquals(0, never.plan(second, ALL, true));
		// An opponent that follows a hit with a second one made from the air is one that does: against
		// that one the first hit is jumped with too, for a while, if that is asked for.
		JumpReset wary = new JumpReset(1f, 5);
		wary.chasers(true);
		assertEquals(0, wary.plan(first, ALL, true), "nothing is known of it yet");
		float[] jumping = after(vec(true, 3.5f, 1f), 100, 8);
		jumping[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		jumping[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = -0.2f * BodyState.VELOCITY_SCALE;
		assertEquals(0, wary.plan(jumping, ALL, false));
		assertFalse(wary.chased());
		assertTrue(has(wary.plan(second, ALL, true), JumpIn.JUMP));
		assertTrue(wary.chased());
		wary.plan(down, ALL, false);
		wary.plan(down, ALL, false);
		assertTrue(has(wary.plan(first, ALL, true), JumpIn.JUMP), "the first hit too, now");
		wary.newRound();
		assertTrue(wary.chased());
		wary.reset();
		assertFalse(wary.chased());
		assertEquals(0, wary.plan(first, ALL, true));
		// Not asked for, it stays with the second hit and later ones, whatever the opponent does.
		JumpReset plain = new JumpReset(1f, 5);
		plain.plan(jumping, ALL, false);
		assertTrue(has(plain.plan(second, ALL, true), JumpIn.JUMP));
		assertTrue(plain.chased());
		plain.plan(down, ALL, false);
		plain.plan(down, ALL, false);
		assertEquals(0, plain.plan(first, ALL, true));
		// A second hit from an opponent that was on the ground all along says nothing.
		JumpReset walked = new JumpReset(1f, 5);
		walked.chasers(true);
		walked.plan(after(vec(true, 3.5f, 1f), 100, 8), ALL, false);
		assertTrue(has(walked.plan(second, ALL, true), JumpIn.JUMP));
		assertFalse(walked.chased());
		// A hit that finds its sword on the way back from a hit of its own, six ticks before: the
		// opponent's answer to that hit. Jumped with, if that is asked for; a first hit otherwise is not.
		JumpReset answers = new JumpReset(1f, 5);
		float[] countered = after(vec(true, 2.5f, 0.5f), 6, 0);
		countered[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertEquals(0, answers.plan(countered, ALL, true), "not asked for");
		answers.counters(1f);
		assertTrue(has(answers.plan(countered, ALL, true), JumpIn.JUMP));
		assertEquals(0, answers.plan(first, ALL, true), "its sword was back: that one it takes as it comes");
		float[] longAgo = after(vec(true, 2.5f, 0.5f), 16, 0);
		longAgo[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertEquals(0, answers.plan(longAgo, ALL, true));
		// At a share of the hits it is that share.
		JumpReset half = new JumpReset(0.5f, 11);
		int made = 0;
		for (int i = 0; i < 400; i++) {
			if (half.plan(second, ALL, true) != 0) {
				made++;
			}
		}
		assertTrue(made > 150 && made < 250, "about half: " + made);
	}

	/** A fighter jumps with the second hit in a row and holds W, where it used to hold S. */
	@Test
	void theFighterJumpsWithTheSecondHitWhereItUsedToBackOff() {
		dev.swordai.core.config.Config cfg = new dev.swordai.core.config.Config();
		assertTrue(cfg.jumpResetChance > 0f && !cfg.stepBackRule && cfg.chaseInCombo);
		cfg.jumpResetChance = 1f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, ALL, true, 21);
			fighter.backOutRule(true);
			MaskFrame near = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 2.8, 180f, 180f, 0f, near);
			BodyState body = new BodyState();
			body.onGround = true;
			body.charge = 0.3f;
			body.health = 1f;
			fighter.hitTaken();
			fighter.decide(near, body);
			fighter.hitTaken();
			int[] action = fighter.decide(near, body);
			assertEquals(1, action[ActionSpace.JUMP]);
			assertEquals(1, action[ActionSpace.FORWARD]);
			assertEquals(1, action[ActionSpace.SPRINT]);
			body.onGround = false;
			body.velUp = 0.3f;
			action = fighter.decide(near, body);
			assertEquals(0, action[ActionSpace.JUMP]);
			assertEquals(1, action[ActionSpace.FORWARD], "W, not S");
			// A hit that finds it in the air: S, as before.
			fighter.hitTaken();
			action = fighter.decide(near, body);
			assertEquals(BackNudge.BACK, action[ActionSpace.FORWARD]);
		}
		cfg.jumpResetChance = 0f;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, ALL, true, 21);
			fighter.backOutRule(true);
			MaskFrame near = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 2.8, 180f, 180f, 0f, near);
			BodyState body = new BodyState();
			body.onGround = true;
			body.charge = 0.3f;
			body.health = 1f;
			fighter.hitTaken();
			fighter.decide(near, body);
			fighter.hitTaken();
			assertEquals(BackNudge.BACK, fighter.decide(near, body)[ActionSpace.FORWARD], "switched off, it backs off");
		}
	}

	/** Its own hit lands, and so many ticks later it is hit: an answer to that hit. */
	private static void answered(StepBack rule, int ticksLater) {
		rule.plan(after(vec(true, 2.6f, 0f), 0, 100));
		for (int tick = 1; tick < ticksLater; tick++) {
			rule.plan(after(vec(true, 2.8f, 0.08f * tick), tick, 100 + tick));
		}
		rule.plan(after(vec(true, 2.8f, 0.08f * ticksLater), ticksLater, 0));
		for (int tick = 1; tick < 20; tick++) {
			rule.plan(after(vec(true, 3.0f, 1f), 100, tick));
		}
	}

	/**
	 * From an opponent that answers its hits while its sword is on the way back, it steps back after
	 * each hit of its own until the sword is nearly back. Against anyone else it does not.
	 */
	@Test
	void fromAnOpponentThatAnswersItsHitsItStepsBack() {
		StepBack rule = new StepBack();
		// Its own hit lands, and the opponent stays where it is: nothing, for nothing is known of it.
		assertEquals(0, rule.plan(after(vec(true, 2.6f, 0f), 0, 5)));
		for (int tick = 1; tick <= 12; tick++) {
			assertEquals(0, rule.plan(after(vec(true, 2.8f, 0.08f * tick), tick, 5 + tick)), "tick " + tick);
		}
		// Hit on the tick after its own hit: a trade, and nothing learned.
		answered(rule, 1);
		answered(rule, 1);
		assertFalse(rule.wary());
		// Hit six ticks after a hit of its own, once: anyone who fights back does that now and then.
		answered(rule, 6);
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		// And again within a few seconds: this one answers its hits.
		answered(rule, 6);
		assertTrue(rule.wary());
		// From the next hit it lands it is on its way out at once.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		assertTrue(rule.on());
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.8f, 0.08f), 1, 31)));
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.3f, 0.16f), 2, 32)));
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.8f, 0.4f), 5, 35)));
		// Out of its reach: it stays where it is, and does not come in.
		assertEquals(StepBack.STAND, rule.plan(after(vec(true, 4.2f, 0.5f), 6, 36)));
		assertEquals(0, rule.plan(after(vec(true, 4.8f, 0.6f), 7, 37)), "far enough off its keys are its own");
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 3.8f, 0.7f), 8, 38)), "and back again if the opponent comes after it");
		// The sword nearly back: in it goes.
		assertEquals(0, rule.plan(after(vec(true, 3.8f, 0.85f), 10, 40)));
		assertFalse(rule.on());
		assertEquals(0, rule.plan(after(vec(true, 3.0f, 0.9f), 11, 41)));
		// A new round against the same opponent: still wary.
		rule.newRound();
		assertTrue(rule.wary());
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		// If the hit turns out to have sent the opponent flying, it goes after it.
		rule.plan(after(vec(true, 3.0f, 0.08f), 1, 1000));
		float[] flying = after(vec(true, 3.7f, 0.16f), 2, 1000);
		flying[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.6f * BodyState.VELOCITY_SCALE;
		assertEquals(0, rule.plan(flying));
		assertFalse(rule.on());
		assertEquals(0, rule.plan(after(vec(true, 4.0f, 0.24f), 3, 1000)));
		// Nor does it back away from one that is running from it.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		for (int tick = 1; tick <= 3; tick++) {
			assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f + 0.1f * tick, 0.08f * tick), tick, 1000)));
		}
		float[] runs = after(vec(true, 3.2f, 0.32f), 4, 1000);
		runs[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.3f * BodyState.VELOCITY_SCALE;
		assertEquals(0, rule.plan(runs));
		assertFalse(rule.on());
		// Hit while it is stepping back: that is over, and the answer has rules of its own.
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		assertEquals(StepBack.BACK, rule.plan(after(vec(true, 2.8f, 0.08f), 1, 1000)));
		assertEquals(0, rule.plan(after(vec(true, 2.8f, 0.16f), 2, 0)));
		assertFalse(rule.on());
		// Ten seconds and more without another answer, and it is no longer taken for one that does.
		for (int tick = 1; tick < 800; tick++) {
			rule.plan(after(vec(true, 6.0f, 1f), 100, Math.min(tick, 100)));
		}
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		// Another opponent: nothing is known of it.
		answered(rule, 6);
		answered(rule, 6);
		answered(rule, 6);
		assertTrue(rule.wary());
		rule.reset();
		assertFalse(rule.wary());
		assertEquals(0, rule.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
	}

	/** An opponent on its way up and coming, with the sword back and its feet on the ground: it is run at and hit. */
	@Test
	void anOpponentJumpingAtItIsMetOnTheWayUp() {
		float[] v = vec(true, 4.2f, 1f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.4f * BodyState.VELOCITY_SCALE;
		assertTrue(MeetJump.applies(v));
		// Not one that is coming down (that one is there already), nor one that is not coming.
		float[] down = v.clone();
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = -0.2f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(down));
		float[] away = v.clone();
		away[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.3f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(away));
		// Not with the sword on its way back, from the air, at one out of sight or one far off.
		float[] spent = v.clone();
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.5f;
		assertFalse(MeetJump.applies(spent));
		assertEquals(4, MeetJump.how(v));
		assertEquals(0, MeetJump.how(down));
		assertEquals(3, MeetJump.how(away));
		assertEquals(2, MeetJump.how(spent));
		// In the air too: W and the sprint key are held from before it is down.
		float[] inAir = v.clone();
		inAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		assertTrue(MeetJump.applies(inAir));
		float[] unseen = v.clone();
		unseen[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 0f;
		assertFalse(MeetJump.applies(unseen));
		float[] far = vec(true, 9f, 1f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.4f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(far));

		// The fighter: W and the sprint key, and the swing the moment the opponent is under its crosshair,
		// also where it would have kept that swing for its own way down, and where it would have held S.
		dev.swordai.core.config.Config cfg = new dev.swordai.core.config.Config();
		assertTrue(cfg.meetJumpRule);
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, ALL, true, 21);
			fighter.backOutRule(true);
			MaskFrame near = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3.4, 180f, 180f, 0f, near);
			BodyState body = new BodyState();
			body.onGround = true;
			body.charge = 1f;
			body.health = 1f;
			body.oppVelUp = 0.3f;
			body.oppVelForward = -0.4f;
			fighter.crosshairOnOpponent(false);
			int[] action = fighter.decide(near, body);
			assertEquals(1, action[ActionSpace.FORWARD]);
			assertEquals(1, action[ActionSpace.SPRINT]);
			assertFalse(fighter.answerForced());
			fighter.crosshairOnOpponent(true);
			action = fighter.decide(near, body);
			assertEquals(1, action[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
			assertEquals(1, action[ActionSpace.FORWARD]);
		}
		cfg.meetJumpRule = false;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, ALL, true, 21);
			MaskFrame near = new MaskFrame();
			new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, 3.4, 180f, 180f, 0f, near);
			BodyState body = new BodyState();
			body.onGround = true;
			body.charge = 1f;
			body.health = 1f;
			body.oppVelUp = 0.3f;
			body.oppVelForward = -0.4f;
			fighter.crosshairOnOpponent(true);
			int forced = 0;
			for (int tick = 0; tick < 20; tick++) {
				fighter.decide(near, body);
				forced += fighter.answerForced() ? 1 : 0;
			}
			assertEquals(0, forced, "switched off, nothing is forced");
		}
	}

	/** At a run towards an opponent five blocks off or more, it closes with running jumps, one as it lands from the last. */
	@Test
	void fromFiveBlocksOffItClosesWithRunningJumps() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		rule.approachHops(true);
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = rule.plan(running(7.0f, 0.28f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN), "at one that stands, seven blocks off");
		assertEquals(JumpIn.HOP, rule.tookOff());
		rule.plan(air(6.0f, 0.3f, true, 1f), ALL, false, false, 0);
		rule.plan(air(5.0f, -0.2f, true, 1f), ALL, false, false, 0);
		// Down again, still more than five blocks off: straight into the next one.
		plan = rule.plan(running(5.5f, 0.28f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.JUMP), "the next one as it lands");
		// Hit out of the air at the end of one, it leaves them alone for a few seconds only.
		rule.plan(air(4.0f, 0.3f, true, 1f), ALL, false, false, 0);
		float[] hitInAir = air(3.0f, -0.1f, true, 1f);
		hitInAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		rule.plan(hitInAir, ALL, false, true, 0);
		for (int tick = 0; tick < JumpIn.HOP_OFF_TICKS - 1; tick++) {
			assertEquals(0, rule.plan(running(7.0f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP, "tick " + tick);
		}
		boolean again = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS && !again; tick++) {
			again = has(rule.plan(running(7.0f, 0.28f), ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(again, "and then again");
		assertEquals(JumpIn.HOP, rule.tookOff());
		// Going at it at a walk is enough: the rule runs and jumps for it.
		JumpIn walker = new JumpIn(true, true, 0f, 1);
		walker.approachHops(true);
		float[] walking = running(7.0f, 0.15f);
		walking[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		walking[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.15f * BodyState.VELOCITY_SCALE;
		assertEquals(0, walker.plan(walking, ALL, false, false, 0) & JumpIn.JUMP, "not on the first tick");
		assertEquals(0, walker.plan(walking, ALL, false, false, 0) & JumpIn.JUMP, "nor the second");
		plan = walker.plan(walking, ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN), "then it runs and jumps");
		assertEquals(JumpIn.HOP, walker.tookOff());
		// Not standing, nor going the other way.
		JumpIn stander = new JumpIn(true, true, 0f, 1);
		stander.approachHops(true);
		float[] standing = running(7.0f, 0f);
		standing[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		standing[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0f;
		for (int tick = 0; tick < 10; tick++) {
			assertEquals(0, stander.plan(standing, ALL, false, false, 0) & JumpIn.JUMP);
		}
		// Not at one that is coming in at a run itself, nor without being asked to.
		JumpIn meeting = new JumpIn(true, true, 0f, 1);
		meeting.approachHops(true);
		JumpIn plain = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			assertEquals(0, meeting.plan(running(8.0f, 0.56f), ALL, false, false, 0));
			assertEquals(0, plain.plan(running(7.0f, 0.28f), ALL, false, false, 0));
		}
	}

	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick < 20; tick++) {
			// Coming in itself: the two close on each other faster than a run, and the sprint hit is the hit to make.
			assertEquals(0, rule.plan(running(3.7f, 0.45f), ALL, false, false, 0));
			// Backing off with its eyes on the fighter, too far for the way down to bring it into reach;
			// and already in reach.
			assertEquals(0, rule.plan(running(7.0f, 0.065f), ALL, false, false, 0));
			assertEquals(0, rule.plan(running(3.0f, 0f), ALL, false, false, 0));
		}
		// One that runs away as fast as the fighter runs has its back turned: after that one it jumps
		// from far off as well, and again the moment it lands.
		JumpIn pursuit = new JumpIn(true, true, 0f, 1);
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			plan = pursuit.plan(running(7.0f, 0f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP));
		// Not after one it is not closing on because it is itself slow: that one may be backing off
		// with its sword ready, not running.
		JumpIn slow = new JumpIn(true, true, 0f, 1);
		float[] crawl = running(7.0f, 0f);
		crawl[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.21f * BodyState.VELOCITY_SCALE;
		for (int tick = 0; tick < 3 * JumpIn.RUN_TICKS; tick++) {
			assertEquals(0, slow.plan(crawl, ALL, false, false, 0));
		}
		pursuit.plan(air(6.8f, 0.2f, true, 1f), ALL, false, false, 0);
		pursuit.plan(air(6.5f, -0.2f, true, 1f), ALL, false, false, 0);
		assertTrue(has(pursuit.plan(running(6.3f, 0.02f), ALL, false, false, 0), JumpIn.JUMP));
		// One that holds its ground a step out of reach: it jumps in on that one too, unless it is set
		// to jump only at an opponent that is backing off.
		JumpIn atAny = new JumpIn(true, true, 0f, 1);
		JumpIn narrow = new JumpIn(true, true, 0f, 0.15f, 1);
		int wide = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			wide = atAny.plan(running(4.0f, 0.28f), ALL, false, false, 0);
			assertEquals(0, narrow.plan(running(4.0f, 0.28f), ALL, false, false, 0));
		}
		assertTrue(has(wide, JumpIn.JUMP));
		// Not with the sword still on its way back, and not while another rule has the tick.
		float[] spent = running(3.5f, 0.06f);
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.2f;
		for (int tick = 0; tick < 20; tick++) {
			assertEquals(0, rule.plan(spent, ALL, false, false, 0));
			assertEquals(0, rule.plan(running(3.5f, 0.06f), ALL, false, true, 0));
		}
		// Switched off, never.
		JumpIn off = new JumpIn(false, false, 0f, 1);
		for (int tick = 0; tick < 20; tick++) {
			assertEquals(0, off.plan(running(3.5f, 0.06f), ALL, false, false, 0));
		}
		// And the jump key has to be one it has: a stage before the jump is learned.
		int noJump = ALL & ~ActionSpace.mask(ActionSpace.JUMP);
		JumpIn early = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick < 20; tick++) {
			assertEquals(0, early.plan(running(3.5f, 0.06f), noJump, false, false, 0));
		}
	}

	@Test
	void withTheOpponentStillInItsFaceAfterAHitItJumpsForACriticalHit() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		float[] v = vec(true, 1.8f, 0.3f);
		// Four ticks after its own hit landed.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f - 4f / BodyState.EVENT_FADE_TICKS;
		// The first such hit: it does not know yet whether this opponent stays. Nothing.
		assertEquals(0, rule.plan(v, ALL, true, false, 1));
		// Eleven ticks after it, the sword coming back, the opponent is still on top of it: it stays.
		float[] later = vec(true, 1.9f, 0.9f);
		later[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f - 11f / BodyState.EVENT_FADE_TICKS;
		assertEquals(0, rule.plan(later, ALL, true, false, 1));
		assertFalse(rule.stayed(), "once could be a knockback that is late");
		// And after its next hit the same.
		float[] fresh = vec(true, 1.8f, 0.05f);
		fresh[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
		assertEquals(0, rule.plan(fresh, ALL, true, false, 2));
		assertEquals(0, rule.plan(v, ALL, true, false, 2));
		assertEquals(0, rule.plan(later, ALL, true, false, 2));
		assertTrue(rule.stayed());
		// Its third hit lands, and four ticks later the opponent is in its face again: now it jumps.
		assertEquals(0, rule.plan(fresh, ALL, true, false, 3));
		int plan = rule.plan(v, ALL, true, false, 3);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		assertEquals(JumpIn.FACE, rule.tookOff());
		// W and the sprint key stay up; the swing waits for the way down with the sword back.
		int swingLocked = ALL & ~ActionSpace.mask(ActionSpace.ATTACK);
		plan = rule.plan(air(2.0f, 0.2f, false, 0.6f), swingLocked, true, false, 1);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(2.0f, -0.1f, false, 0.9f), swingLocked, true, false, 1);
		assertTrue(has(plan, JumpIn.NO_SWING), "the sword is not back");
		plan = rule.plan(air(2.0f, -0.2f, false, 1f), ALL, true, false, 1);
		assertTrue(has(plan, JumpIn.SWING));
		// Not at an opponent that has hit it in the last two seconds: that one gets the punish crit.
		JumpIn hitLately = new JumpIn(true, true, 0f, 1);
		float[] struck = vec(true, 1.8f, 0.9f);
		struck[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		hitLately.plan(struck, ALL, true, true, 0);
		for (int hits = 1; hits <= 3; hits++) {
			hitLately.plan(fresh, ALL, true, false, hits);
			assertEquals(0, hitLately.plan(v, ALL, true, false, hits));
			hitLately.plan(later, ALL, true, false, hits);
		}
		assertTrue(hitLately.stayed());
		// An opponent on a slow connection stands where it was hit for a moment and then goes flying:
		// by the time the sword is back it is gone, so it has not stayed, and no jump is made at it.
		JumpIn other = new JumpIn(true, true, 0f, 1);
		float[] gone = vec(true, 5.5f, 0.9f);
		gone[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f - 11f / BodyState.EVENT_FADE_TICKS;
		assertEquals(0, other.plan(gone, ALL, false, false, 1));
		assertFalse(other.stayed());
		assertEquals(0, other.plan(fresh, ALL, true, false, 2));
		assertEquals(0, other.plan(v, ALL, true, false, 2));
		assertEquals(JumpIn.NONE, other.kind());
	}

	@Test
	void hitInTheAirOfSuchAJumpTheSwingItOwesTakesOver() {
		JumpIn rule = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			rule.plan(running(3.5f, 0.06f), ALL, false, false, 0);
		}
		assertEquals(JumpIn.CHASE, rule.kind());
		assertEquals(0, rule.plan(air(3.5f, 0.2f, true, 1f), ALL, true, true, 0));
		assertEquals(JumpIn.NONE, rule.kind());
		// Hit in the air with nothing to swing back at, it is the same: that jump is over and counted.
		JumpIn unanswered = new JumpIn(true, true, 0f, 1);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			unanswered.plan(running(3.5f, 0.06f), ALL, false, false, 0);
		}
		assertEquals(JumpIn.CHASE, unanswered.kind());
		float[] struckInAir = air(3.6f, 0.2f, true, 1f);
		struckInAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		assertEquals(0, unanswered.plan(struckInAir, ALL, false, false, 0));
		assertEquals(JumpIn.NONE, unanswered.kind());
		for (int tick = 0; tick < 3 * JumpIn.RUN_TICKS; tick++) {
			assertEquals(0, unanswered.plan(running(3.5f, 0.06f), ALL, false, false, 0));
		}
		// And it is careful with that opponent: no such jump again for a while, in the next round either.
		rule.newRound();
		for (int tick = 0; tick < 3 * JumpIn.RUN_TICKS; tick++) {
			assertEquals(0, rule.plan(running(3.5f, 0.06f), ALL, false, false, 0));
		}
		// Against a new opponent it starts afresh.
		rule.reset();
		int plan = 0;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS; tick++) {
			plan = rule.plan(running(3.5f, 0.06f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP));
	}

	@Test
	void anUppercutFollowsSomeHitsOfACombo() {
		JumpIn rule = new JumpIn(false, false, 1f, 1);
		// Its third hit in a row has just landed.
		float[] hit = running(3.5f, 0.1f);
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.1f;
		assertEquals(0, rule.plan(hit, ALL, false, false, 3));
		// At a run after the opponent as the sword comes back: it jumps.
		float[] after = running(4.0f, 0.1f);
		after[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 0.6f;
		after[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.7f;
		// From too far off for the way up to bring the opponent into reach as the sword comes back: no jump.
		float[] tooFar = running(4.8f, 0.1f);
		tooFar[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 0.6f;
		tooFar[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.7f;
		JumpIn far = new JumpIn(false, false, 1f, 1);
		far.plan(hit, ALL, false, false, 3);
		assertEquals(0, far.plan(tooFar, ALL, false, false, 3));
		int plan = rule.plan(after, ALL, false, false, 3);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN));
		assertEquals(JumpIn.UPPER, rule.tookOff());
		// Going up at a sprint with the sword back and the opponent in reach: the uppercut.
		plan = rule.plan(air(3.2f, 0.3f, true, 1f), ALL, true, false, 3);
		assertTrue(has(plan, JumpIn.SWING) && has(plan, JumpIn.RUN));
		// Not after a single hit: that is no combo yet.
		JumpIn early = new JumpIn(false, false, 1f, 1);
		early.plan(hit, ALL, false, false, 1);
		assertEquals(0, early.plan(after, ALL, false, false, 1));
		assertEquals(0, early.plan(after, ALL, false, false, 1));
	}

	@Test
	void farAwayTheCameraIsSteeredOntoTheOpponentAndComesToRestThere() {
		assertTrue(FarRest.applies(vec(true, 20f, 1f)));
		// Near, the aim is its own; and so it is with nothing on screen.
		assertFalse(FarRest.applies(vec(true, 5f, 1f)));
		assertFalse(FarRest.applies(new float[ObsSpec.VEC]));
		// Six degrees to the right of the crosshair: it turns right, most of the way at once, and not past it.
		float degree = ViewGeometry.apparentSize((float) Math.tan(Math.toRadians(1.0)), 1f);
		AimRule rule = new AimRule(0.5f);
		AimMotor hand = new AimMotor(0.5f);
		float off = 6f;
		float turnedInAll = 0f;
		int flips = 0;
		float last = 0f;
		for (int tick = 0; tick < 30; tick++) {
			float[] v = vec(true, 20f, 1f);
			v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_X] = (float) Math.tan(Math.toRadians(off)) * degree / (float) Math.tan(Math.toRadians(1.0));
			v[ObsSpec.VEC_STATS + ObsSpec.STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
			v[ObsSpec.VEC_STATS + ObsSpec.STATS + ObsSpec.ST_ANY_X] = v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_X];
			rule.plan(v, hand.yawPush(), hand.pitchPush(), true);
			hand.step(ActionSpace.YAW_BINS[rule.yawBin()], ActionSpace.PITCH_BINS[rule.pitchBin()]);
			float rate = hand.yawRate();
			if (rate != 0f && last != 0f && Math.signum(rate) != Math.signum(last)) {
				flips++;
			}
			if (rate != 0f) {
				last = rate;
			}
			off -= rate;
			turnedInAll += Math.abs(rate);
			assertEquals(0f, hand.pitchRate(), 1e-6f, "it is level already");
		}
		assertTrue(Math.abs(off) <= 2f, "all but on it: " + off);
		assertEquals(0, flips, "never back the other way");
		assertEquals(0f, hand.yawRate(), 1e-6f, "and at rest");
		assertTrue(turnedInAll <= 6.5f);
		// From rest, with the crosshair two degrees beside a far opponent, the hand stays where it is.
		AimRule still = new AimRule(0.5f);
		AimMotor rest = new AimMotor(0.5f);
		for (int tick = 0; tick < 10; tick++) {
			float[] v = vec(true, 20f, 1f);
			v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_X] = (float) Math.tan(Math.toRadians(2.0)) * degree / (float) Math.tan(Math.toRadians(1.0));
			still.plan(v, rest.yawPush(), rest.pitchPush(), true);
			rest.step(ActionSpace.YAW_BINS[still.yawBin()], ActionSpace.PITCH_BINS[still.pitchBin()]);
			assertEquals(0f, rest.yawRate(), 1e-6f);
		}
	}

	@Test
	void hitFromOutOfThePictureItTurnsToWhereTheHitCameFrom() {
		AimRule rule = new AimRule(0.5f);
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 0.5f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		assertTrue(AimRule.applies(v));
		// Thrown to the right: the hit came from the left, and that is where it turns, as fast as the hand goes.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_RIGHT] = 0.3f * BodyState.VELOCITY_SCALE;
		rule.plan(v, 0f, 0f);
		assertEquals(0, rule.yawBin());
		// It goes on that way although it is no longer being thrown.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_RIGHT] = 0f;
		rule.plan(v, -20f, 0f);
		assertEquals(0, rule.yawBin());
		// Seen before on the right: that is where it looks, whichever way the hit threw it.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE] = 1f;
		rule.plan(v, 0f, 0f);
		assertEquals(ActionSpace.YAW_BINS.length - 1, rule.yawBin());
		// Long after the hit, or having hit back, the camera is its own again.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 0f;
		assertFalse(AimRule.applies(v));
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 0f;
		assertFalse(AimRule.applies(v));
	}
	@Test
	void againstAnOpponentThatWasInReachOnTheWayDownLastTimeTheSwingIsKeptForTheWayDown() {
		FallWatch watch = new FallWatch();
		// Hit, thrown up, and coming down with the opponent two blocks off: that one stays.
		float[] hit = vec(true, 2.0f, 1f);
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		watch.tick(hit);
		assertFalse(watch.punishable());
		assertEquals(AnswerRule.NOW, AnswerRule.when(hit, watch.punishable(), watch.proven()), "the first time it trades");
		float[] down = air(2.2f, -0.2f, false, 1f);
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 7f / BodyState.EVENT_FADE_TICKS;
		watch.tick(down);
		float[] landed = vec(true, 2.2f, 1f);
		landed[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 11f / BodyState.EVENT_FADE_TICKS;
		watch.tick(landed);
		assertTrue(watch.punishable());
		// Hit again with its feet still on the ground: now the swing waits for the way down.
		assertEquals(AnswerRule.KEEP, AnswerRule.when(hit, watch.punishable(), watch.proven()));
		// Down again without having had a swing, with the opponent there: it swings. (Up to 14 October it
		// waited for that opponent's next hit for as long as the last one was fresh; asked for, it still does.)
		float[] still = vec(true, 2.0f, 1f);
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 12f / BodyState.EVENT_FADE_TICKS;
		assertEquals(AnswerRule.NOW, AnswerRule.when(still, watch.punishable(), watch.proven()));
		assertEquals(AnswerRule.KEEP, AnswerRule.when(still, watch.punishable(), watch.proven(), true));
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 0f;
		assertEquals(AnswerRule.NOW, AnswerRule.when(still, watch.punishable(), watch.proven(), true));
		// Hit a second time in a row without having hit back, it is being comboed: the swing is made
		// at once, on the ground or on the way up, whatever the opponent.
		float[] second = vec(true, 2.0f, 1f);
		second[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		second[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		assertTrue(BackNudge.inCombo(second));
		assertEquals(AnswerRule.NOW, AnswerRule.when(second, true, false));
		float[] up = air(2.0f, 0.3f, true, 1f);
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		assertEquals(AnswerRule.NOW, AnswerRule.when(up, true, false));
		// Unless a punish crit has been made on this opponent: then the next one is made on the way down too.
		assertEquals(AnswerRule.KEEP, AnswerRule.when(up, true, true));
		assertEquals(AnswerRule.KEEP, AnswerRule.when(second, true, true));
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertFalse(BackNudge.inCombo(up));
		assertEquals(AnswerRule.KEEP, AnswerRule.when(up, true, false), "after one hit it is kept for the way down");
		// What makes an opponent one a punish crit has been made on: a hit of its own, landed in the air
		// on the way down from that opponent's hit.
		FallWatch made = new FallWatch();
		made.tick(hit);
		assertFalse(made.proven());
		made.tick(down);
		float[] scored = air(2.2f, -0.3f, false, 0.05f);
		scored[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 8f / BodyState.EVENT_FADE_TICKS;
		scored[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
		made.tick(scored);
		assertTrue(made.proven() && made.punishable());
		// A hit traded from the ground on the tick it was hit proves nothing, and the fall after it
		// still says whether the opponent stays in reach.
		FallWatch traded = new FallWatch();
		traded.tick(hit);
		float[] tradedUp = air(2.1f, 0.3f, true, 0.05f);
		tradedUp[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 1f / BodyState.EVENT_FADE_TICKS;
		tradedUp[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
		traded.tick(tradedUp);
		float[] tradedDown = air(2.2f, -0.2f, false, 0.5f);
		tradedDown[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 7f / BodyState.EVENT_FADE_TICKS;
		traded.tick(tradedDown);
		traded.tick(landed);
		assertFalse(traded.proven());
		assertTrue(traded.punishable());
		// And what ends it: a fall with the sword back and the opponent in reach that comes to nothing.
		made.tick(hit);
		made.tick(down);
		made.tick(landed);
		assertFalse(made.proven());
		// A fall with the sword still on its way back says nothing either way.
		made.tick(hit);
		made.tick(down);
		made.tick(scored);
		assertTrue(made.proven());
		made.tick(hit);
		float[] spent = air(2.2f, -0.2f, false, 0.4f);
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 7f / BodyState.EVENT_FADE_TICKS;
		made.tick(spent);
		made.tick(landed);
		assertTrue(made.proven());
		// An opponent whose hit sends it out of reach: nothing to keep the swing for.
		watch.tick(hit);
		float[] flung = air(5.0f, -0.2f, false, 1f);
		flung[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 7f / BodyState.EVENT_FADE_TICKS;
		watch.tick(flung);
		watch.tick(landed);
		assertFalse(watch.punishable());
		assertEquals(AnswerRule.NOW, AnswerRule.when(hit, watch.punishable(), watch.proven()));
	}

	/** Crit back: the opponent has just swung, its own sword is nearly back, the opponent at the edge of the reach. */
	@Test
	void itCritsBackInTheOpponentsCooldown() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.counterCrit(true);
		float[] v = after(vec(true, 3.0f, 0.6f), 100, 3);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.2f;
		int plan = rule.plan(v, ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		assertEquals(JumpIn.COUNTER, rule.tookOff());
		// On the way down with the opponent in reach: the sprint let go of, then the critical hit.
		rule.plan(air(3.0f, 0.3f, true, 0.8f), ALL, true, false, 0);
		plan = rule.plan(air(2.8f, -0.15f, true, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(2.7f, -0.23f, false, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.SWING));

		// Not with the opponent's sword nearly back, nor its own far from back, nor from close up, nor switched off.
		float[] ready = v.clone();
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.8f;
		assertEquals(0, fresh().plan(ready, ALL, true, false, 0));
		float[] spent = v.clone();
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.3f;
		assertEquals(0, fresh().plan(spent, ALL, true, false, 0));
		float[] close = after(vec(true, 2.0f, 1f), 100, 3);
		close[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.2f;
		assertEquals(0, fresh().plan(close, ALL, true, false, 0));
		assertEquals(0, new JumpIn(false, false, 0f, 1).plan(v, ALL, true, false, 0));

		// Hit in the air of one: it leaves them alone for a while.
		JumpIn burnt = fresh();
		burnt.plan(v, ALL, true, false, 0);
		burnt.plan(after(air(3.0f, 0.2f, true, 0.7f), 100, 0), ALL, false, false, 0);
		assertEquals(JumpIn.NONE, burnt.kind());
		assertEquals(0, burnt.plan(v, ALL, true, false, 0));
	}

	/**
	 * With {@code Config.jumpAtReadyOnlyFleeing}: no chase and no hop at an opponent that stands its ground with its
	 * sword ready (it hits a running jump out of the air), but at one whose sword is spent, or that runs away, as before.
	 */
	@Test
	void atAnOpponentStandingItsGroundWithItsSwordReadyItDoesNotJump() {
		// The chase: four blocks off, backing off at a walk.
		for (float opp : new float[] {0f, 0.3f, 0.6f, 1f}) {
			JumpIn rule = new JumpIn(true, true, 0f, 1);
			rule.onlyAtSpentOrFleeing(true);
			boolean jumped = false;
			for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
				float[] v = running(3.5f, 0.065f);
				v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = opp;
				jumped |= has(rule.plan(v, ALL, false, false, 0), JumpIn.JUMP);
			}
			assertEquals(opp < 0.5f, jumped, "its sword at " + opp);
		}
		// Running away from it at a run, sword ready or not, long after a hit of its own: after it.
		JumpIn after = new JumpIn(true, true, 0f, 1);
		after.onlyAtSpentOrFleeing(true);
		boolean jumped = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2 && !jumped; tick++) {
			float[] v = running(3.5f, 0f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			jumped = has(after.plan(v, ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(jumped, "at one running away");
		// Going off as fast, but flying back from its own hit a few ticks ago: no jump at that ready sword.
		JumpIn thrown = new JumpIn(true, true, 0f, 1);
		thrown.onlyAtSpentOrFleeing(true);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = after(running(3.5f, 0f), 3 + tick, 100);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			assertEquals(0, thrown.plan(v, ALL, false, false, 1) & JumpIn.JUMP, "thrown back, tick " + tick);
		}
		// Long after the hit, but still in the air from it, going off fast: no jump either.
		JumpIn flying = new JumpIn(true, true, 0f, 1);
		flying.onlyAtSpentOrFleeing(true);
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = running(3.5f, 0f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = (tick % 2 == 0 ? -0.2f : 0.15f) * BodyState.VELOCITY_SCALE;
			assertEquals(0, flying.plan(v, ALL, false, false, 1) & JumpIn.JUMP, "in the air, tick " + tick);
		}
		// The hop from seven blocks off at one that stands with its sword ready: not made; with it spent, made.
		for (float opp : new float[] {0f, 1f}) {
			JumpIn rule = new JumpIn(true, true, 0f, 1);
			rule.approachHops(true);
			rule.onlyAtSpentOrFleeing(true);
			boolean hopped = false;
			for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
				float[] v = running(7.0f, 0.28f);
				v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = opp;
				hopped |= has(rule.plan(v, ALL, false, false, 0), JumpIn.JUMP);
			}
			assertEquals(opp < 0.5f, hopped, "hop, its sword at " + opp);
		}
		// Not set: at the ready one too, as before.
		JumpIn old = new JumpIn(true, true, 0f, 1);
		boolean oldJumped = false;
		for (int tick = 0; tick <= JumpIn.RUN_TICKS + 2; tick++) {
			float[] v = running(3.5f, 0.065f);
			v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
			oldJumped |= has(old.plan(v, ALL, false, false, 0), JumpIn.JUMP);
		}
		assertTrue(oldJumped);
	}

	/**
	 * The crit in: at an opponent standing still, a running jump from where the way down brings it into reach late in the
	 * fall; on the way down W and the sprint are let go of near the reach, and the swing made as it comes into reach.
	 */
	@Test
	void atAnOpponentStandingStillItCritsInLateInTheFall() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.edgeCrit(true);
		// At a run (0.28 a tick, closing as fast: the opponent stands): from 7.4 blocks, about ten ticks to the reach.
		int plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = rule.plan(running(7.4f, 0.28f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING), "takes off");
		assertEquals(JumpIn.EDGE, rule.tookOff());
		// Going up: at a run, no swing.
		plan = rule.plan(air(5.0f, 0.3f, true, 0.8f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		// Coming down, still well out of reach: at a run.
		plan = rule.plan(air(4.0f, -0.1f, true, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.RUN) && has(plan, JumpIn.NO_SWING));
		// Near the reach: W and the sprint let go of, no swing yet (nothing in reach, and still sprinting).
		plan = rule.plan(air(3.3f, -0.2f, true, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		// In reach, the sprint off: the crit.
		plan = rule.plan(air(3.0f, -0.3f, false, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.SWING));
		// Not from too near (it would come into reach going up), nor at one that is moving, nor when not asked to.
		for (float[] v : new float[][] {running(4.8f, 0.28f), running(7.4f, 0.1f)}) {
			JumpIn other = new JumpIn(false, false, 0f, 1);
			other.edgeCrit(true);
			for (int tick = 0; tick < 6; tick++) {
				assertEquals(0, other.plan(v, ALL, false, false, 0) & JumpIn.JUMP);
			}
		}
		JumpIn off = new JumpIn(false, false, 0f, 1);
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(0, off.plan(running(7.4f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP);
		}
		// Hit in the air of one before its own swing: left alone for a while.
		JumpIn burnt = new JumpIn(false, false, 0f, 1);
		burnt.edgeCrit(true);
		plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS && !has(plan, JumpIn.JUMP); tick++) {
			plan = burnt.plan(running(7.4f, 0.28f), ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.JUMP));
		burnt.plan(after(air(4.5f, 0.2f, true, 1f), 100, 0), ALL, false, false, 0);
		assertEquals(JumpIn.NONE, burnt.kind());
		for (int tick = 0; tick < JumpIn.EDGE_OFF_TICKS - 2; tick++) {
			assertEquals(0, burnt.plan(running(7.4f, 0.28f), ALL, false, false, 0) & JumpIn.JUMP, "tick " + tick);
		}
	}

	/**
	 * The crit in from a stand: coming up to a still opponent with its sword ready, W is let go of where the fighter stops
	 * just outside its reach; standing there, a jump in place, and W alone from the top of it, the swing as it is in reach.
	 */
	@Test
	void atTheEdgeOfAStillOpponentItJumpsInPlaceAndDriftsInForTheCrit() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.edgeCrit(true);
		// From a stand 4.4 off: walked in. Coming in at a run from 3.45: W let go of, so that it stops at the edge.
		float[] far = running(4.4f, 0.05f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.05f * BodyState.VELOCITY_SCALE;
		int plan = 0;
		for (int tick = 0; tick < JumpIn.FLEE_GROUND_TICKS; tick++) {
			plan = rule.plan(far, ALL, false, false, 0);
		}
		assertTrue(has(plan, JumpIn.WALK), "walked in from a stand");
		float[] coming = running(3.45f, 0.28f);
		coming[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		assertTrue(has(rule.plan(coming, ALL, false, false, 0), JumpIn.LET_GO), "W let go of: it stops at the edge");
		// Still coasting in at the edge: W stays let go of until it has stopped.
		float[] coasting = running(3.2f, 0.15f);
		coasting[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		coasting[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0.15f * BodyState.VELOCITY_SCALE;
		int coast = rule.plan(coasting, ALL, false, false, 0);
		assertTrue(has(coast, JumpIn.LET_GO) && !has(coast, JumpIn.JUMP), "coasting");
		// Standing at 3.2: the jump in place.
		float[] edge = running(3.2f, 0f);
		edge[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		edge[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		edge[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0f;
		plan = rule.plan(edge, ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.JUMP) && has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING), "jump in place");
		assertEquals(JumpIn.EDGE, rule.tookOff());
		// Up: W and the sprint up. Down: W alone; in reach, the swing.
		plan = rule.plan(air(3.2f, 0.3f, false, 0.9f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.LET_GO) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(3.1f, -0.1f, false, 1f), ALL, false, false, 0);
		assertTrue(has(plan, JumpIn.WALK) && has(plan, JumpIn.NO_SWING));
		plan = rule.plan(air(3.0f, -0.3f, false, 1f), ALL, true, false, 0);
		assertTrue(has(plan, JumpIn.WALK) && has(plan, JumpIn.SWING));
		// The keys: W down, the sprint up.
		int[] action = new int[ActionSpace.HEADS];
		action[ActionSpace.SPRINT] = 1;
		JumpIn.apply(action, JumpIn.WALK);
		assertEquals(1, action[ActionSpace.FORWARD]);
		assertEquals(0, action[ActionSpace.SPRINT]);
		// Not at an opponent whose sword is spent (that one is gone in at at once), nor one that is moving.
		float[] spent = running(3.2f, 0f);
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.3f;
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = 0f;
		JumpIn other = new JumpIn(false, false, 0f, 1);
		other.edgeCrit(true);
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(0, other.plan(spent, ALL, false, false, 0));
		}
	}

	private static JumpIn fresh() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.counterCrit(true);
		return rule;
	}

	/** Thrown back by a hit it is not closing on anyone; an opponent jumping in at it is met all the same. */
	@Test
	void aJumpCritComingInIsMetWhileItIsStillThrownBack() {
		float[] v = vec(true, 3.6f, 1f);
		// It is carried back at 0.2 a tick, the opponent comes in at 0.22: closing 0.02.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_FORWARD] = -0.2f * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.02f * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(v));
		assertTrue(MeetJump.applies(v, true));
		assertEquals(4, MeetJump.how(v, true));
		// One that jumps where it stands is not coming.
		float[] still = v.clone();
		still[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.2f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(still, true));
	}

	/** A jump made at it with its own sword not back: it gets out of the way instead of meeting it. */
	@Test
	void aJumpCritComingInWithItsSwordNotBackIsSteppedOutOf() {
		float[] v = vec(true, 3.6f, 0.4f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.25f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(v, true));
		assertTrue(MeetJump.dodges(v, true));
		// With the sword back it is met instead; one coming down, going away or far off is no jump at it.
		float[] ready = v.clone();
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 1f;
		assertFalse(MeetJump.dodges(ready, true));
		assertTrue(MeetJump.applies(ready, true));
		float[] down = v.clone();
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = -0.2f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.dodges(down, true));
		float[] away = v.clone();
		away[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.4f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.dodges(away, true));
		float[] far = vec(true, 7f, 0.4f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.25f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.dodges(far, true));
	}

	/** Just out of reach of a ready sword it does not walk in; it does once that sword is spent, or after a second. */
	@Test
	void itHoldsAtTheEdgeOfAReadySwordAndGoesInOnceItIsSpent() {
		float[] v = vec(true, 3.5f, 1f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		EdgeHold rule = new EdgeHold();
		assertTrue(rule.plan(v));
		float[] spent = v.clone();
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.3f;
		assertFalse(rule.plan(spent), "the opponent has swung: in");
		// In reach already, far off, or in its own combo: not held.
		float[] near = vec(true, 2.5f, 1f);
		near[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		assertFalse(EdgeHold.holds(near));
		float[] far = vec(true, 6f, 1f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		assertFalse(EdgeHold.holds(far));
		float[] combo = after(v.clone(), 5, 100);
		assertFalse(EdgeHold.holds(combo));
		// It waits a second at most, then goes in.
		EdgeHold waits = new EdgeHold();
		int heldTicks = 0;
		for (int tick = 0; tick < 40; tick++) {
			if (waits.plan(v)) {
				heldTicks++;
			}
		}
		assertEquals(EdgeHold.MOST_TICKS, heldTicks);
	}
}
