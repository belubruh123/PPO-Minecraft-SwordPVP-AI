package dev.swordai.core.curriculum;

import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.ActionSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurriculumTest {
	private static StageSpec stage(String name) {
		for (StageSpec s : Stages.defaults()) {
			if (s.name.equals(name)) {
				return s;
			}
		}
		throw new AssertionError("no stage " + name);
	}

	private static StepFacts facts(float reach) {
		StepFacts f = new StepFacts();
		f.reachDist = reach;
		f.enemyVisible = true;
		f.charge = 1f;
		return f;
	}

	@Test
	void stagesUnlockHeadsCumulatively() {
		List<StageSpec> stages = Stages.defaults();
		assertEquals(10, stages.size());
		int previous = 0;
		for (StageSpec s : stages) {
			assertEquals(previous, previous & s.headMask, s.name + " must keep every head the stage before it had");
			previous = s.headMask;
		}
		assertEquals(ActionSpace.mask(ActionSpace.YAW), stages.get(0).headMask);
		assertTrue(stages.get(2).unlocked(ActionSpace.PITCH));
		assertFalse(stages.get(3).unlocked(ActionSpace.ATTACK));
		assertEquals(ActionSpace.ALL_HEADS, stages.get(7).headMask);
		assertEquals(ActionSpace.ALL_HEADS, stages.get(8).headMask);
		assertEquals(ActionSpace.ALL_HEADS, stages.get(9).headMask);
	}

	@Test
	void jumpingStaysLockedUntilTheCritStage() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < 7; i++) {
			assertFalse(stages.get(i).unlocked(ActionSpace.JUMP), stages.get(i).name + " must not jump");
		}
		assertTrue(stages.get(4).unlocked(ActionSpace.ATTACK) && stages.get(5).unlocked(ActionSpace.SPRINT));
		assertEquals("Crits", stages.get(7).name);
		assertTrue(stages.get(7).unlocked(ActionSpace.JUMP));
		assertTrue(stages.get(8).unlocked(ActionSpace.JUMP));
	}

	@Test
	void fightingStagesLearnFromSeveralFightsAtATime() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < 5; i++) {
			assertEquals(0, stages.get(i).rolloutSteps, stages.get(i).name + " uses the default rollout");
			assertEquals(1f, stages.get(i).entropyScale);
		}
		for (int i = 5; i < 10; i++) {
			assertTrue(stages.get(i).rolloutSteps >= 3 * stages.get(5).episodeTicks, stages.get(i).name);
			assertTrue(stages.get(i).entropyScale < 1f);
		}
		// Aim keeps being paid in the sprint-hit stage at least as well as in the stage before it.
		assertTrue(stages.get(5).reward.onTarget >= stages.get(4).reward.onTarget);
		assertTrue(stages.get(5).reward.hold >= stages.get(4).reward.hold);
	}

	@Test
	void horizontalStageIgnoresPitchError() {
		StageSpec st = stage("Horizontal aim");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts f = facts(5f);
		f.yawErrDeg = 0f;
		f.pitchErrDeg = 30f;
		f.missDeg = 28f;
		f.yawOnHead = true;
		float r = rm.step(st, f, t);
		assertEquals(st.reward.onTarget + st.reward.visible + st.reward.hold / 20f, r, 1e-6,
				"being on the opponent left-to-right is all that counts while pitch is locked");
		assertEquals(1, t.onTargetTicks);
		assertEquals(1, t.onHeadTicks);
		assertEquals(0.0, t.aimErrorSum, 1e-9);
	}

	@Test
	void aimProgressIsPaidOnlyWithoutDiscontinuity() {
		StageSpec st = stage("Horizontal aim");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts f = facts(5f);
		f.yawErrDeg = 43f;
		f.yawMissDeg = 40f;
		float first = rm.step(st, f, t);
		f.yawErrDeg = 33f;
		f.yawMissDeg = 30f;
		float improved = rm.step(st, f, t);
		// Ten degrees closer: paid for the progress, still charged for what is left.
		assertEquals(st.reward.aimProgress * 10f - st.reward.aimError * 30f / 45f + st.reward.visible, improved, 1e-5);
		assertTrue(improved > first);

		f.yawErrDeg = 103f;
		f.yawMissDeg = 100f;
		f.discontinuity = true;
		float hop = rm.step(st, f, t);
		assertEquals(st.reward.visible - st.reward.aimError, hop, 1e-5, "a target hop must not be scored as the agent losing aim");
	}

	@Test
	void losingSightOfTheOpponentCostsRewardAndEventuallyRestartsTheEpisode() {
		StageSpec st = stage("Vertical aim");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts seen = facts(5f);
		seen.missDeg = 50f;
		StepFacts unseen = facts(5f);
		unseen.missDeg = 50f;
		unseen.enemyVisible = false;
		rm.step(st, seen, t);
		assertEquals(st.reward.visible, rm.step(st, seen, t) - rm.step(st, unseen, t), 1e-6);

		for (int i = 1; i < st.lostResetTicks; i++) {
			assertFalse(rm.lostTooLong(st), "tick " + i);
			rm.step(st, unseen, t);
		}
		assertTrue(rm.lostTooLong(st));
		rm.step(st, seen, t);
		assertFalse(rm.lostTooLong(st), "seeing the opponent again clears the counter");

		for (int i = 0; i < st.lostResetTicks; i++) {
			rm.step(st, unseen, t);
		}
		assertTrue(rm.lostTooLong(st));
		rm.endEpisode(t, false, false);
		assertFalse(rm.lostTooLong(st), "a new episode starts with a clean counter");
	}

	@Test
	void stayingOnTheOpponentPaysMoreThanTouchingIt() {
		StageSpec st = stage("Horizontal aim");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts on = facts(5f);
		StepFacts off = facts(5f);
		off.yawErrDeg = 10f;
		off.yawMissDeg = 10f;

		// The very first tick has nothing to compare with, so it cannot yet earn the still bonus.
		float first = rm.step(st, on, t);
		float previous = first;
		for (int i = 2; i <= 20; i++) {
			float r = rm.step(st, on, t);
			assertTrue(r > previous, "tick " + i + " of a hold must pay more than the one before");
			previous = r;
		}
		assertEquals(st.reward.hold * 19f / 20f + st.reward.still, previous - first, 1e-5, "full bonus after one second");
		assertEquals(previous, rm.step(st, on, t), 1e-6, "and it stays at full while the hold lasts");
		assertEquals(20, t.stillTicks);

		rm.step(st, off, t);
		assertEquals(1, t.holds);
		// Coming back starts the build-up again. The step earns aim progress for the 10 degrees
		// regained, and no still bonus, because the crosshair was swept onto the opponent.
		float back = rm.step(st, on, t);
		assertEquals(first + st.reward.aimProgress * 10f, back, 1e-5);
		assertEquals(22f, t.meanHold(), 1e-6, "22 ticks on the opponent in one finished hold so far");
	}

	@Test
	void restingOnTheOpponentPaysMoreThanSlidingAcrossIt() {
		StageSpec st = stage("Horizontal aim");
		st.reward.smooth = 0f;
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts f = facts(5f);
		rm.step(st, f, t);
		float resting = rm.step(st, f, t);

		// Still on the opponent, but the crosshair moved half a degree across it.
		f.yawErrDeg = 0.5f;
		float sliding = rm.step(st, f, t);
		// The hold bonus keeps growing (third tick of the hold), only the still bonus shrinks.
		assertEquals(st.reward.still * 0.5f - st.reward.hold / 20f, resting - sliding, 1e-5, "half a degree costs half the bonus");

		f.yawErrDeg = 0.6f;
		float creeping = rm.step(st, f, t);
		assertTrue(creeping < resting && creeping > sliding, "a tenth of a degree is cheaper but not free");

		f.yawErrDeg = 3.6f;
		float swept = rm.step(st, f, t);
		assertEquals(st.reward.onTarget + st.reward.visible + st.reward.hold * 5f / 20f, swept, 1e-5, "three degrees a tick earns none of it");
		assertEquals(2, t.stillTicks, "the tick at rest and the one that barely crept count as steady");
		assertEquals(0.4f, t.still(), 1e-6);
	}

	@Test
	void anySpotOnTheOpponentIsAsGoodAsTheHead() {
		StageSpec st = stage("Vertical aim");
		StepFacts head = facts(5f);
		head.onBody = true;
		head.onHead = true;
		StepFacts legs = facts(5f);
		legs.onBody = true;
		legs.yawErrDeg = 2f;
		legs.pitchErrDeg = -14f;

		RewardModel a = new RewardModel();
		RewardModel b = new RewardModel();
		Tally t = new Tally();
		a.step(st, head, t);
		b.step(st, legs, t);
		assertEquals(a.step(st, head, t), b.step(st, legs, t), 1e-6, "nothing pulls the crosshair towards the middle or the head");
		assertEquals(0.0, t.aimErrorSum, 1e-9, "on the opponent is zero error wherever it is");
	}

	@Test
	void changingCameraSpeedCostsButKeepingItDoesNot() {
		StageSpec st = stage("Horizontal tracking");
		st.reward.search = 0f;
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		rm.step(st, blind(0f), t);
		float steadyStop = rm.step(st, blind(0f), t);
		float start = rm.step(st, blind(8f), t);
		assertEquals(st.reward.smooth * 0.5f, steadyStop - start, 1e-5, "eight degrees a tick faster is half the full charge");
		assertEquals(steadyStop, rm.step(st, blind(8f), t), 1e-5, "a steady sweep costs nothing");
		assertEquals(st.reward.smooth, steadyStop - rm.step(st, blind(-30f), t), 1e-5, "a hard reversal costs all of it");
	}

	@Test
	void lookingAroundIsPaidPerDegreeTowardsWhereTheOpponentWent() {
		StageSpec st = stage("Horizontal tracking");
		st.reward.smooth = 0f;
		float w = st.reward.search;

		// The opponent was last seen to the right, then left the screen.
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts seen = facts(5f);
		seen.yawErrDeg = 60f;
		seen.yawMissDeg = 57f;
		rm.step(st, seen, t);

		float still = rm.step(st, blind(0f), t);
		assertEquals(w, rm.step(st, blind(40f), t) - still, 1e-5, "a full-speed turn towards it earns the whole amount");
		assertEquals(w * 0.5f, rm.step(st, blind(20f), t) - still, 1e-5, "half the angle, half the reward");
		assertEquals(0f, rm.step(st, blind(-40f), t) - still, 1e-5, "turning back the other way earns nothing");
		assertEquals(w, rm.step(st, blind(40f), t) - still, 1e-5, "the committed direction is still the one that pays");

		rm.step(st, seen, t);
		assertEquals(1, t.reacquires);
		assertEquals(5, t.reacquireTicks);
		assertEquals(5f, t.meanReacquire(), 1e-6);

		// With no memory at all, the first turn picks the direction and has to be kept.
		RewardModel fresh = new RewardModel();
		float base = fresh.step(st, blind(0f), t);
		assertEquals(w, fresh.step(st, blind(-40f), t) - base, 1e-5);
		assertEquals(0f, fresh.step(st, blind(40f), t) - base, 1e-5);
		assertEquals(w, fresh.step(st, blind(-40f), t) - base, 1e-5);
	}

	private static StepFacts blind(float yawTurn) {
		StepFacts f = facts(5f);
		f.enemyVisible = false;
		f.yawErrDeg = 60f;
		f.yawMissDeg = 57f;
		f.yawTurnDeg = yawTurn;
		return f;
	}

	@Test
	void staringAtTheSkyWhileTheOpponentIsOutOfViewIsPenalised() {
		StageSpec st = stage("Vertical aim");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts level = facts(5f);
		level.enemyVisible = false;
		level.missDeg = 80f;
		StepFacts up = facts(5f);
		up.enemyVisible = false;
		up.missDeg = 80f;
		up.agentPitchDeg = -45f;
		rm.step(st, level, t);
		assertEquals(st.reward.blindPitch, rm.step(st, level, t) - rm.step(st, up, t), 1e-6);
	}

	@Test
	void rangeScoreFadesOutsideTheBand() {
		RewardWeights w = new RewardWeights();
		w.rangeMin = 2.4f;
		w.rangeMax = 3.0f;
		w.rangeFalloff = 1.5f;
		assertEquals(1f, RewardModel.rangeScore(2.7f, w));
		assertEquals(1f, RewardModel.rangeScore(3.0f, w));
		assertEquals(0.5f, RewardModel.rangeScore(3.75f, w), 1e-5);
		assertEquals(0f, RewardModel.rangeScore(6f, w));
		assertEquals(0.6f, RewardModel.rangeScore(1.8f, w), 1e-5);
	}

	@Test
	void aSwingThatMissesIsFreeButSpamIsNot() {
		for (String name : new String[] {"Hit timing", "Sprint-hit + W-tap", "Combo escape", "Crits", "Duel", "Self-play"}) {
			StageSpec st = stage(name);
			assertEquals(0f, st.reward.whiff, name + " does not charge for a miss");
			assertTrue(st.reward.spam > 0f, name + " charges for spam");
			float quiet = swing(st, false, false, 1f, new Tally());

			// The weapon was ready and nothing was there: no charge at all.
			Tally miss = new Tally();
			assertEquals(quiet, swing(st, true, false, 1f, miss), 1e-6f, name);
			assertEquals(1, miss.clicks);
			assertEquals(0, miss.clicksOnTarget);
			assertEquals(0, miss.spamClicks);

			// Clicking again before it has recovered is charged, in the air and on the opponent alike.
			Tally spam = new Tally();
			assertEquals(quiet - st.reward.spam, swing(st, true, false, 0.2f, spam), 1e-5f, name);
			assertEquals(quiet - st.reward.spam, swing(st, true, true, 0.2f, spam), 1e-5f, name);
			assertEquals(2, spam.spamClicks);
			assertEquals(1f, spam.spamRate());
		}
	}

	private static float swing(StageSpec st, boolean clicked, boolean onTarget, float charge, Tally t) {
		StepFacts f = facts(6f);
		f.missDeg = 20f;
		f.clicked = clicked;
		f.clickOnTarget = onTarget;
		f.chargeAtClick = charge;
		return new RewardModel().step(st, f, t);
	}

	@Test
	void hitTimingIsPassedOnCleanSwingsNotOnNeverMissing() {
		Curriculum c = new Curriculum(Stages.defaults());
		c.setStage(4);
		assertEquals(0f, c.current().promote.minClickAccuracy, "missing is allowed");
		Tally t = new Tally();
		t.steps = 45_000;
		t.clicks = 2000;
		// Most swings hit nothing, which is fine.
		t.clicksOnTarget = 700;
		t.hitsDealt = 700;
		t.fullChargeHits = 650;
		t.spamClicks = 600;
		c.record(t);
		assertTrue(c.promotionBlocker().startsWith("spam clicks 30%"), c.promotionBlocker());

		c.setStage(4);
		t.spamClicks = 200;
		c.record(t);
		assertNull(c.promotionBlocker());
		assertTrue(c.tryPromote());
	}

	@Test
	void fullChargeHitsAreWorthMoreThanWeakOnes() {
		StageSpec st = stage("Hit timing");
		float full = hitReward(st, 1f);
		float weak = hitReward(st, 0.5f);
		assertEquals(st.reward.hit * 0.75f, full - weak, 1e-5, "reward follows charge squared");
	}

	private static float hitReward(StageSpec st, float charge) {
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts f = facts(2.8f);
		f.hitsDealt = 1;
		f.hitCharge = charge;
		return rm.step(st, f, t);
	}

	@Test
	void wTapWindowRewardsReleasingWAndBackingOff() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardWeights w = st.reward;

		// Agent A hits and then W-taps: releases W and presses S.
		RewardModel a = new RewardModel();
		Tally ta = new Tally();
		// Agent B hits and keeps holding W into the opponent.
		RewardModel b = new RewardModel();
		Tally tb = new Tally();

		StepFacts hit = facts(2.8f);
		hit.hitsDealt = 1;
		hit.hitCharge = 1f;
		hit.sprintHit = true;
		hit.forwardAxis = 1;
		hit.sprintKey = true;
		float ra = a.step(st, hit, ta);
		float rb = b.step(st, hit, tb);
		assertEquals(ra, rb, 1e-6);

		float tapTotal = 0f;
		float holdTotal = 0f;
		for (int i = 0; i < w.tapWindowTicks; i++) {
			StepFacts tap = facts(2.8f);
			tap.forwardAxis = -1;
			tapTotal += a.step(st, tap, ta);
			StepFacts hold = facts(2.8f);
			hold.forwardAxis = 1;
			hold.sprintKey = true;
			holdTotal += b.step(st, hold, tb);
		}
		assertEquals(w.tap * w.tapWindowTicks, tapTotal - holdTotal, 1e-4);
		assertEquals(1, ta.tapWindows);
		assertEquals(1, ta.tapsDone);
		assertEquals(1, tb.tapWindows);
		assertEquals(0, tb.tapsDone);

		// Strafing with W released counts the same as S; only releasing W earns half.
		RewardModel c = new RewardModel();
		Tally tc = new Tally();
		c.step(st, hit, tc);
		StepFacts strafe = facts(2.8f);
		strafe.strafeAxis = 1;
		StepFacts idle = facts(2.8f);
		float strafeR = c.step(st, strafe, tc);
		float idleR = c.step(st, idle, tc);
		assertEquals(0.5f * w.tap, strafeR - idleR, 1e-5);

		// After the window the tap bonus stops.
		StepFacts late = facts(2.8f);
		late.forwardAxis = -1;
		late.charge = 0f;
		StepFacts lateIdle = facts(2.8f);
		lateIdle.charge = 0f;
		float lateTap = a.step(st, late, ta);
		float lateNothing = a.step(st, lateIdle, ta);
		assertEquals(lateTap, lateNothing, 1e-6);
	}

	@Test
	void combosGrowAndBreakWhenHit() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		float prev = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < 3; i++) {
			StepFacts hit = facts(2.8f);
			hit.hitsDealt = 1;
			hit.hitCharge = 1f;
			float r = rm.step(st, hit, t);
			assertTrue(r > prev, "each hit in a combo is worth more than the last");
			prev = r;
			for (int k = 0; k < 12; k++) {
				rm.step(st, facts(2.8f), t);
			}
		}
		assertEquals(3, rm.combo());
		StepFacts taken = facts(2.8f);
		taken.hitsTaken = 1;
		rm.step(st, taken, t);
		assertEquals(0, rm.combo());
		assertEquals(1, t.combos);
		assertEquals(3, t.comboHits);
		assertEquals(3f, t.meanCombo(), 1e-6);
		assertEquals(3f, t.hitRatio(), 1e-6);
	}

	@Test
	void reengageRewardsSprintingBackInOnceRecharged() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardModel rm = new RewardModel();
		Tally t = new Tally();
		StepFacts idle = facts(4f);
		idle.charge = 0.9f;
		StepFacts rush = facts(4f);
		rush.charge = 0.9f;
		rush.forwardAxis = 1;
		rush.sprintKey = true;
		assertEquals(st.reward.reengage, rm.step(st, rush, t) - rm.step(st, idle, t), 1e-5);
	}

	@Test
	void spacingPaysWalkingInOnTheOpponentsCooldownAndGatesReengage() {
		StageSpec st = stage("Crits");
		st.reward.spaceIn = 0.3f;
		Tally t = new Tally();
		StepFacts in = facts(2.9f);
		in.walkedInOnCooldown = true;
		in.oppCharge = 0.4f;
		StepFacts plain = facts(2.9f);
		plain.oppCharge = 0.4f;
		assertEquals(0.3f, new RewardModel().step(st, in, t) - new RewardModel().step(st, plain, t), 1e-5);
		// Running back in once its own sword is back: paid into a sword that is coming back, not into a ready one.
		StepFacts readySword = facts(4f);
		readySword.charge = 0.9f;
		readySword.forwardAxis = 1;
		readySword.sprintKey = true;
		readySword.oppCharge = 1f;
		StepFacts coolingSword = facts(4f);
		coolingSword.charge = 0.9f;
		coolingSword.forwardAxis = 1;
		coolingSword.sprintKey = true;
		coolingSword.oppCharge = 0.5f;
		StepFacts idle = facts(4f);
		idle.charge = 0.9f;
		float base = new RewardModel().step(st, idle, t);
		assertEquals(0f, new RewardModel().step(st, readySword, t) - base, 1e-5);
		assertEquals(st.reward.reengage, new RewardModel().step(st, coolingSword, t) - base, 1e-5);
	}

	@Test
	void promotionWaitsForStepsThenForMetrics() {
		List<StageSpec> stages = Stages.defaults();
		Curriculum c = new Curriculum(stages);
		Promotion p = stages.get(0).promote;

		Tally poor = new Tally();
		poor.steps = 2048;
		poor.onTargetTicks = 200;
		poor.aimErrorSum = 2048 * 30.0;
		c.record(poor);
		assertTrue(c.promotionBlocker().contains("more steps"));
		assertFalse(c.tryPromote());

		while (c.stepsInStage() < p.minSteps) {
			c.record(poor);
		}
		assertTrue(c.promotionBlocker().startsWith("on-target"), c.promotionBlocker());
		assertFalse(c.tryPromote());

		// On the opponent most of the time, but fidgeting there: the stage is about coming to rest.
		Tally twitchy = new Tally();
		twitchy.steps = 2048;
		twitchy.onTargetTicks = 1700;
		twitchy.stillTicks = 300;
		twitchy.aimErrorSum = 2048 * 1.0;
		for (int i = 0; i < 8; i++) {
			c.record(twitchy);
		}
		assertTrue(c.promotionBlocker().startsWith("steady aim"), c.promotionBlocker());

		// Good rollouts push the bad ones out of the window.
		Tally good = new Tally();
		good.steps = 2048;
		good.onTargetTicks = 1700;
		good.stillTicks = 1300;
		good.aimErrorSum = 2048 * 1.0;
		for (int i = 0; i < 8; i++) {
			c.record(good);
		}
		assertNull(c.promotionBlocker());
		assertTrue(c.tryPromote());
		assertEquals(1, c.stageIndex());
		assertEquals(0, c.stepsInStage());
		assertNotNull(c.promotionBlocker());
	}

	@Test
	void rehearsalOnlyPicksEarlierStages() {
		Curriculum c = new Curriculum(Stages.defaults());
		Random rng = new Random(3);
		for (int i = 0; i < 50; i++) {
			assertEquals(0, c.pickEpisodeStage(rng, 0.5f));
		}
		c.setStage(4);
		int rehearsed = 0;
		for (int i = 0; i < 2000; i++) {
			int s = c.pickEpisodeStage(rng, 0.25f);
			assertTrue(s <= 4);
			if (s < 4) {
				rehearsed++;
			}
		}
		assertTrue(rehearsed > 350 && rehearsed < 650, "about a quarter should be rehearsal, got " + rehearsed);
	}

	@Test
	void aBotThatScalesWithSkillGetsSharperWhenTheAgentWins() {
		Curriculum last = new Curriculum(Stages.defaults());
		last.setStage(9);
		assertEquals("final stage", last.promotionBlocker());

		// No stage uses a sharpening scripted bot any more, but a config still can.
		List<StageSpec> stages = Stages.defaults();
		StageSpec scripted = stages.get(8);
		scripted.bot.movement = BotProfile.FIGHT;
		scripted.bot.fixedCopy = false;
		scripted.bot.sprint = true;
		scripted.bot.wtap = true;
		scripted.bot.scalesWithSkill = true;
		Curriculum c = new Curriculum(stages);
		c.setStage(8);
		Tally winning = new Tally();
		winning.steps = 2000;
		winning.wins = 8;
		winning.losses = 2;
		c.record(winning);
		assertTrue(c.adaptSkill());
		assertEquals(0.1f, c.botSkill(), 1e-6);
		assertFalse(c.adaptSkill(), "needs fresh fights before changing again");

		BotProfile easy = c.current().bot.atSkill(0f);
		BotProfile hard = c.current().bot.atSkill(1f);
		assertTrue(hard.reactionTicks < easy.reactionTicks);
		assertTrue(hard.aimNoiseDeg < easy.aimNoiseDeg);
		assertTrue(hard.turnRateDeg > easy.turnRateDeg);
		assertTrue(easy.sprint && easy.wtap && hard.wtap);

		// Against a copy of itself there is no skill to turn up.
		Curriculum duel = new Curriculum(Stages.defaults());
		duel.setStage(8);
		duel.record(winning);
		assertFalse(duel.adaptSkill());
	}

	@Test
	void stateSurvivesSaveAndLoad(@TempDir Path dir) throws Exception {
		Curriculum a = new Curriculum(Stages.defaults());
		a.setStage(3);
		Tally t = new Tally();
		t.steps = 4096;
		a.record(t);
		Path file = dir.resolve("curriculum.properties");
		a.save(file);

		Curriculum b = new Curriculum(Stages.defaults());
		b.load(file);
		assertEquals(3, b.stageIndex());
		assertEquals(4096, b.stepsInStage());
	}

	@Test
	void aPlanIsOnlyHandedOutOnceTheBasicsAreLearned() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < 5; i++) {
			assertFalse(stages.get(i).ideas, stages.get(i).name + " has no plan to follow");
			assertEquals(0f, stages.get(i).reward.mixStrafe);
			assertEquals(0f, stages.get(i).reward.mixTiming);
		}
		// Going round the opponent with A or D held belongs to the combo, and is learned with it.
		assertEquals("Sprint-hit + W-tap", stages.get(5).name);
		assertTrue(stages.get(5).ideas && stages.get(5).reward.mixStrafe > 0f);
		assertTrue(stages.get(5).promote.minMixStrafe > 0.34f, "more than pressing a strafe key at random comes to");
		assertTrue(stages.get(5).promote.styleOptionalAfterSteps > stages.get(5).promote.minSteps);
		assertEquals("Combo escape", stages.get(6).name);
		assertTrue(stages.get(6).ideas && stages.get(7).ideas && stages.get(8).ideas && stages.get(9).ideas);
		// It is the sprint-hit fight with a plan on top: same controls, same hit rewards.
		assertEquals(stages.get(5).headMask, stages.get(6).headMask);
		assertEquals(stages.get(5).reward.sprintHit, stages.get(6).reward.sprintHit);

		Curriculum c = new Curriculum(stages);
		c.setStage(5);
		assertFalse(c.ideasLearned());
		c.setStage(6);
		assertTrue(c.ideasLearned());
	}

	@Test
	void strafingWithThePlanPaysAndAgainstItCosts() {
		StageSpec st = stage("Combo escape");
		float with = mixStep(st, 1, 1, false, 0);
		float none = mixStep(st, 1, 0, false, 0);
		float against = mixStep(st, 1, -1, false, 0);
		assertEquals(st.reward.mixStrafe, with - none, 1e-5f);
		assertEquals(st.reward.mixStrafe, none - against, 1e-5f);
		// No plan for the strafe: all three are worth the same.
		assertEquals(mixStep(st, 0, 1, false, 0), mixStep(st, 0, -1, false, 0), 1e-6f);
		// It is the best paid thing a tick can hold: more than standing at range, or letting go of W after a hit.
		assertTrue(st.reward.mixStrafe > st.reward.range && st.reward.mixStrafe > st.reward.tap);

		// Strafing is footwork for a fight. Far from the opponent the plan pays nothing, costs nothing
		// and is not counted, so that it cannot be collected on from across the arena.
		Tally far = new Tally();
		float[] farPay = new float[2];
		for (int i = 0; i < 2; i++) {
			StepFacts f = facts(st.reward.mixWithin + 1f);
			f.ideaOn = true;
			f.ideaStrafe = 1;
			f.strafeAxis = i == 0 ? 1 : -1;
			farPay[i] = new RewardModel().step(st, f, far);
		}
		assertEquals(farPay[0], farPay[1], 1e-6f);
		assertEquals(0, far.mixTicks);

		Tally t = new Tally();
		RewardModel m = new RewardModel();
		for (int i = 0; i < 10; i++) {
			StepFacts f = facts(2.8f);
			f.onBody = true;
			f.ideaOn = true;
			f.ideaStrafe = -1;
			f.strafeAxis = i < 7 ? -1 : 1;
			m.step(st, f, t);
		}
		assertEquals(0.7f, t.mixStrafe(), 1e-6f);
	}

	/**
	 * A plan can hold a swing back, and a stage can charge for swinging early. No stage does any more:
	 * a ready weapon is swung.
	 */
	@Test
	void swingingBeforeThePlanSaysSoCosts() {
		for (StageSpec each : Stages.defaults()) {
			assertEquals(0f, each.reward.mixTiming, each.name + " does not hold swings back");
			assertEquals(0f, each.promote.minMixTiming, each.name);
		}
		StageSpec st = stage("Combo escape");
		st.reward.mixTiming = 0.3f;
		float held = mixStep(st, 0, 0, true, 0);
		float early = mixStep(st, 0, 0, true, 3);
		assertEquals(st.reward.mixTiming, held - early, 1e-5f);

		Tally t = new Tally();
		RewardModel m = new RewardModel();
		for (int hold : new int[] {0, 0, 0, 2}) {
			StepFacts f = facts(2.8f);
			f.onBody = true;
			f.ideaOn = true;
			f.clicked = true;
			f.clickOnTarget = true;
			f.chargeAtClick = 1f;
			f.ideaHold = hold;
			m.step(st, f, t);
		}
		assertEquals(0.75f, t.mixTiming(), 1e-6f);
		// A stage without a plan never counts or charges any of this.
		Tally plain = new Tally();
		StepFacts f = facts(2.8f);
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = 1f;
		new RewardModel().step(stage("Sprint-hit + W-tap"), f, plain);
		assertEquals(0, plain.plannedClicks);
	}

	private static float mixStep(StageSpec st, int ideaStrafe, int strafeAxis, boolean clicked, int ideaHold) {
		StepFacts f = facts(2.8f);
		f.onBody = true;
		f.ideaOn = true;
		f.ideaStrafe = ideaStrafe;
		f.strafeAxis = strafeAxis;
		f.clicked = clicked;
		f.clickOnTarget = clicked;
		f.chargeAtClick = 1f;
		f.ideaHold = ideaHold;
		return new RewardModel().step(st, f, new Tally());
	}

	@Test
	void theComboEscapeStageIsPassedByBackingOutOfCombosWithS() {
		List<StageSpec> stages = Stages.defaults();
		assertEquals("Combo escape", stages.get(6).name);
		Promotion p = stages.get(6).promote;
		assertEquals(0f, p.minMixStrafe, "the strafe plan is a habit there now, not what the stage is passed on");
		assertTrue(p.minBackRate > 0f && p.minOutRate > 0f);
		Curriculum c = new Curriculum(stages);
		c.setStage(6);
		Tally t = new Tally();
		t.steps = 80_000;
		t.hitsDealt = 2000;
		t.sprintHits = 1200;
		t.hitsTaken = 1000;
		// Strafing as planned no better than chance: nobody asks.
		t.mixTicks = 30_000;
		t.mixFollowed = 9_000;
		t.comboedHits = 10;
		c.record(t);
		assertTrue(c.promotionBlocker().startsWith("20 more hits of combos on it needed"), c.promotionBlocker());

		c.setStage(6);
		t.comboedHits = 400;
		t.comboedHitsFollowed = 240;
		t.backTicks = 4000;
		t.backHeld = 1200;
		c.record(t);
		assertTrue(c.promotionBlocker().startsWith("holding S when comboed 30% < 50%"), c.promotionBlocker());
		assertTrue(c.promotionBlocker().contains("80,000 more steps"), "and it says when that stops being asked: " + c.promotionBlocker());
		assertFalse(c.tryPromote());

		// The habit alone is not it: it also has to work.
		c.setStage(6);
		t.backHeld = 3200;
		c.record(t);
		assertTrue(c.promotionBlocker().startsWith("combo hits it got out after 40% < 55%"), c.promotionBlocker());
		assertFalse(c.tryPromote());

		c.setStage(6);
		t.comboedHitsFollowed = 120;
		c.record(t);
		assertNull(c.promotionBlocker());
		assertTrue(c.tryPromote());
		assertEquals("Crits", c.current().name);
		assertNull(c.passedShortOf(), "passed on its numbers");
	}

	/**
	 * A habit that will not form must not keep the fighter from everything after it. Once the stage
	 * has been played well over twice as long as it takes at the least, it is passed without the plan
	 * being followed, as long as the fighting itself holds up; and it says that this is what happened.
	 */
	@Test
	void aStageWhoseHabitWillNotFormIsLeftBehindAfterItsFullTime() {
		List<StageSpec> stages = Stages.defaults();
		Curriculum c = new Curriculum(stages);
		c.setStage(6);
		int limit = stages.get(6).promote.styleOptionalAfterSteps;
		assertEquals(160_000, limit);
		Tally t = new Tally();
		t.steps = 10_000;
		t.hitsDealt = 400;
		t.sprintHits = 240;
		t.hitsTaken = 200;
		t.comboedHits = 100;
		t.comboedHitsFollowed = 60;
		t.backTicks = 1000;
		t.backHeld = 320;
		for (int i = 0; i < 15; i++) {
			c.record(t);
		}
		assertEquals(150_000, c.stepsInStage());
		assertTrue(c.promotionBlocker().startsWith("holding S when comboed"), c.promotionBlocker());
		assertFalse(c.tryPromote());

		c.record(t);
		assertEquals(160_000, c.stepsInStage());
		assertNull(c.promotionBlocker());
		assertTrue(c.tryPromote());
		assertEquals("Crits", c.current().name);
		assertTrue(c.passedShortOf().startsWith("holding S when comboed 32% < 50%"), c.passedShortOf());

		// What is about whether it can fight at all is never waived: a fighter whose own hits have
		// stopped being sprint hits stays. How many it takes is not asked in this stage: its opponent's
		// follow-up cannot be traded with, only got away from.
		assertEquals(0f, stages.get(6).promote.minHitRatio);
		c.setStage(6);
		t.hitsTaken = 600;
		t.sprintHits = 100;
		for (int i = 0; i < 13; i++) {
			c.record(t);
		}
		assertTrue(c.promotionBlocker().startsWith("sprint hits"), c.promotionBlocker());
		assertFalse(c.tryPromote());

		// The crit stage has no such way out: critical hits are what it is for, and it is not left
		// without them. The stages before the combo stages have none either.
		assertEquals(0, stages.get(7).promote.styleOptionalAfterSteps);
		for (int i = 0; i < 5; i++) {
			assertEquals(0, stages.get(i).promote.styleOptionalAfterSteps, stages.get(i).name);
		}
	}

	/** One tick on a stage with the crosshair on or off the opponent, the weapon at a given charge, the camera turning or not. */
	private static float comboTick(StageSpec st, boolean onTarget, float charge, float yawTurn, boolean visible, Tally t) {
		RewardModel m = new RewardModel();
		StepFacts first = facts(2.8f);
		first.onBody = onTarget;
		first.missDeg = onTarget ? 0f : 15f;
		first.charge = charge;
		first.enemyVisible = visible;
		m.step(st, first, new Tally());
		StepFacts f = facts(2.8f);
		f.onBody = onTarget;
		f.missDeg = onTarget ? 0f : 15f;
		f.charge = charge;
		f.enemyVisible = visible;
		f.yawTurnDeg = yawTurn;
		return m.step(st, f, t);
	}

	private static float rechargeTick(StageSpec st, boolean circling, float yawTurn) {
		RewardModel m = new RewardModel();
		StepFacts first = facts(2.8f);
		first.charge = 0.3f;
		first.enemyVisible = true;
		m.step(st, first, new Tally());
		StepFacts f = facts(2.8f);
		f.charge = 0.3f;
		f.enemyVisible = true;
		f.yawTurnDeg = yawTurn;
		f.ideaCircle = circling;
		return m.step(st, f, new Tally());
	}

	/** Going round the opponent cannot be done without turning, so a still camera is not what is paid for then. */
	@Test
	void whileCirclingTheCameraIsNotPaidForStandingStill() {
		StageSpec st = stage("Combo escape");
		float turning = st.reward.smooth * 3f / 16f;
		assertEquals(st.reward.calm + turning, rechargeTick(st, false, 0f) - rechargeTick(st, false, 3f), 1e-5f);
		assertEquals(turning, rechargeTick(st, true, 0f) - rechargeTick(st, true, 3f), 1e-5f);
	}

	@Test
	void betweenSwingsTheCrosshairIsFreeInTheComboStages() {
		for (String name : new String[] {"Combo escape", "Crits", "Duel"}) {
			StageSpec st = stage(name);
			assertTrue(st.reward.aimFromCharge > 0f && st.reward.calm > 0f, name);
			// Recharging: looking at the opponent and looking past it are worth exactly the same.
			assertEquals(comboTick(st, true, 0.3f, 0f, true, new Tally()), comboTick(st, false, 0.3f, 0f, true, new Tally()), 1e-6f, name);
			// Ready again: being on the opponent pays, being off it costs.
			float on = comboTick(st, true, 1f, 0f, true, new Tally());
			float off = comboTick(st, false, 1f, 0f, true, new Tally());
			assertEquals(st.reward.onTarget + st.reward.hold / 10f + st.reward.aimError * 15f / 45f, on - off, 1e-5f, name);
			// The switch is at the stage's own charge, a few ticks before the sword is ready.
			assertTrue(comboTick(st, true, st.reward.aimFromCharge, 0f, true, new Tally())
					> comboTick(st, false, st.reward.aimFromCharge, 0f, true, new Tally()), name);
		}
		// The stages before still want the crosshair on the opponent all the time.
		StageSpec basics = stage("Sprint-hit + W-tap");
		assertEquals(0f, basics.reward.aimFromCharge);
		assertTrue(comboTick(basics, true, 0.3f, 0f, true, new Tally()) > comboTick(basics, false, 0.3f, 0f, true, new Tally()));
	}

	@Test
	void aCameraLeftAloneWhileRechargingPays() {
		StageSpec st = stage("Combo escape");
		float still = comboTick(st, false, 0.3f, 0f, true, new Tally());
		float slow = comboTick(st, false, 0.3f, 1.5f, true, new Tally());
		float fast = comboTick(st, false, 0.3f, 20f, true, new Tally());
		assertTrue(still > slow && slow > fast);
		// Half the bonus at half the limit, plus what the change of camera speed costs anyway.
		assertEquals(st.reward.calm * 0.5f + st.reward.smooth * 1.5f / 16f, still - slow, 1e-5f);
		// Not while the opponent is off screen: then it has to be found, not waited for.
		assertEquals(st.reward.visible + st.reward.calm, still - comboTick(st, false, 0.3f, 0f, false, new Tally()), 1e-5f);
		// And not once the sword is ready: then turning onto the opponent is the point.
		float readyStill = comboTick(st, true, 1f, 0f, true, new Tally());
		float readyTurning = comboTick(st, true, 1f, 1.5f, true, new Tally());
		assertEquals(st.reward.smooth * 1.5f / 16f, readyStill - readyTurning, 1e-5f);
	}

	@Test
	void theReportCountsCalmRechargesAndAimWhenReady() {
		StageSpec st = stage("Combo escape");
		Tally t = new Tally();
		comboTick(st, false, 0.3f, 0f, true, t);
		comboTick(st, false, 0.3f, 4f, true, t);
		comboTick(st, true, 1f, 0f, true, t);
		comboTick(st, false, 1f, 0f, true, t);
		comboTick(st, true, 0.9f, 2f, true, t);
		assertEquals(2, t.rechargeTicks);
		assertEquals(0.5f, t.rechargeCalm(), 1e-6f);
		assertEquals(3, t.readyTicks);
		assertEquals(2f / 3f, t.readyOnTarget(), 1e-6f);
	}

	@Test
	void theOpponentGetsHarderAfterTheSprintHitStage() {
		List<StageSpec> stages = Stages.defaults();
		BotProfile basics = stages.get(5).bot;
		assertEquals(0f, basics.critChance);
		for (int i = 6; i < 8; i++) {
			BotProfile b = stages.get(i).bot;
			String name = stages.get(i).name;
			assertTrue(b.attacks && b.sprint, name + ": it hits back with sprint hits");
			assertTrue(b.reactionTicks < basics.reactionTicks, name + ": it reacts sooner");
			assertTrue(b.aimNoiseDeg < basics.aimNoiseDeg && b.turnRateDeg > basics.turnRateDeg, name + ": it aims better");
		}
		// In the stage about getting out of combos the bot keeps one going: its next swing after a hit
		// that landed takes no reaction time. In the crit stage it leaves room again.
		assertEquals(0, basics.followUpTicks);
		assertTrue(stages.get(6).bot.followUpTicks >= 20);
		// That follow-up has a sword's reach and no more, and it is thrown on rhythm: at a fighter that
		// has backed out it misses, which is the opening backing out is for.
		assertEquals(0f, stages.get(6).bot.followUpReach);
		assertTrue(stages.get(6).bot.followUpWhiff >= 1f && stages.get(6).bot.followUpWhiff <= 2f);
		assertEquals(stages.get(6).bot.followUpWhiff, stages.get(6).bot.copy().followUpWhiff);
		assertEquals(stages.get(6).bot.dodgeChance, stages.get(6).bot.copy().dodgeChance);
		assertTrue(stages.get(6).bot.dodgeChance > 0f);
		assertEquals(0f, basics.followUpWhiff);
		assertEquals(0f, basics.dodgeChance);
		assertEquals(0f, basics.followUpReach);
		assertEquals(0f, stages.get(7).bot.followUpReach);
		assertEquals(stages.get(6).bot.followUpTicks, stages.get(6).bot.copy().followUpTicks);
		assertEquals(0, stages.get(7).bot.followUpTicks);
		assertTrue(stages.get(7).bot.reactionTicks > stages.get(6).bot.reactionTicks);
		// The opponent jumps in for critical hits from the combo stage on, a stage before the fighter
		// learns to; not before.
		assertTrue(stages.get(6).bot.critChance >= 0.2f);
		assertEquals(0f, stages.get(5).bot.critChance);
		assertTrue(stages.get(6).reward.critTaken > 0f);
		assertTrue(stages.get(7).bot.critChance >= 0.35f, "the first opponent that crits does it often enough to be met");
		// Taking a critical hit costs more than taking a hit, there where damage is not counted.
		assertTrue(stages.get(7).reward.critTaken > 0f);
		assertEquals(0f, stages.get(5).reward.critTaken);
		StepFacts hit = facts(2.8f);
		hit.hitsTaken = 1;
		StepFacts crit = facts(2.8f);
		crit.hitsTaken = 1;
		crit.critsTaken = 1;
		assertEquals(-stages.get(7).reward.critTaken,
				new RewardModel().step(stages.get(7), crit, new Tally()) - new RewardModel().step(stages.get(7), hit, new Tally()), 1e-6f);
		assertEquals(stages.get(7).bot.critChance, stages.get(7).bot.copy().critChance);
		// After that no scripted bot is hard enough: the duel and self-play are fought against itself.
		for (int i = 8; i < 10; i++) {
			assertEquals(BotProfile.SELF, stages.get(i).bot.movement, stages.get(i).name);
			assertTrue(stages.get(i).bot.attacks, stages.get(i).name);
		}
		assertTrue(stages.get(8).bot.fixedCopy && stages.get(8).bot.copy().fixedCopy);
		assertFalse(stages.get(9).bot.fixedCopy);
	}

	private static float critHit(StageSpec st, boolean crit, boolean punish, float reach, boolean sprintHit, Tally t) {
		StepFacts f = facts(2.8f);
		f.onBody = true;
		f.hitsDealt = 1;
		f.hitCharge = 1f;
		f.critDealt = crit;
		f.punishCrit = punish;
		f.reachAtAction = reach;
		f.sprintHit = sprintHit;
		return new RewardModel().step(st, f, t);
	}

	@Test
	void aCriticalHitPaysMoreThanASprintHitAndAPunishCritMoreStill() {
		StageSpec st = stage("Crits");
		float plain = critHit(st, false, false, 2.8f, false, new Tally());
		Tally t = new Tally();
		float punish = critHit(st, true, true, 2.8f, false, t);
		float close = critHit(st, true, false, 1.2f, false, t);
		float far = critHit(st, true, false, 2.8f, false, t);
		float sprint = critHit(st, false, false, 2.8f, true, t);
		// A hit that neither knocks back nor is critical earns nothing here: a critical hit is ahead of
		// it by the hit itself and by what a critical hit is worth on top.
		assertEquals(0f, st.reward.plainHit);
		assertEquals(st.reward.crit + st.reward.hit, far - plain, 1e-5f);
		assertEquals(far, close, 1e-6f, "a critical hit is a critical hit, from wherever in reach it was made");
		assertEquals(st.reward.punishCrit, punish - far, 1e-5f);
		// This is the stage for it: here a critical hit is worth clearly more than a sprint hit.
		assertTrue(far >= sprint + 0.5f && punish > far, plain + " " + sprint + " " + far + " " + punish);
		assertEquals(3, t.critHits);
		assertEquals(1, t.punishCrits);
		assertEquals(0.75f, t.critShare(), 1e-6f);

		// The stage before pays nothing extra for any of it: there a critical hit is a hit that counts,
		// worth what a sprint hit is worth less the knockback.
		StageSpec before = stage("Combo escape");
		assertEquals(before.reward.hit, critHit(before, true, true, 1.2f, false, new Tally()) - critHit(before, false, false, 2.8f, false, new Tally()), 1e-6f);
		assertEquals(before.reward.sprintHit, critHit(before, false, false, 2.8f, true, new Tally()) - critHit(before, true, true, 1.2f, false, new Tally()), 1e-6f);
		// In the duel the damage is what a critical hit is worth; a little is still paid to keep the habit.
		for (String name : new String[] {"Duel", "Self-play"}) {
			assertTrue(stage(name).reward.crit > 0f && stage(name).reward.crit < st.reward.crit, name);
		}
	}

	/** What a critical hit is made from is paid for tick by tick: coming down, sprint off, as the sword comes back. */
	@Test
	void comingDownWithTheSprintOffAsTheSwordComesBackIsPaidFor() {
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		assertTrue(w.critReady >= 2 * w.primed, "worth more than keeping the sprint, or the sprint is never let go");
		float reach = 2.8f;
		StepFacts down = ready(reach, false);
		down.falling = true;
		assertEquals(w.critReady, new RewardModel().step(st, down, new Tally()) - new RewardModel().step(st, ready(reach, false), new Tally()), 1e-6f);

		// Coming down with the sprint still on is no critical hit: that is paid as the sprint hit it will be.
		StepFacts sprinting = ready(reach, true);
		sprinting.falling = true;
		assertEquals(new RewardModel().step(st, ready(reach, true), new Tally()), new RewardModel().step(st, sprinting, new Tally()), 1e-6f);

		// Not while the sword is still recharging, and not with the opponent out of reach.
		StepFacts early = ready(reach, false);
		early.falling = true;
		early.charge = 0.5f;
		StepFacts sameEarly = ready(reach, false);
		sameEarly.charge = 0.5f;
		assertEquals(new RewardModel().step(st, sameEarly, new Tally()), new RewardModel().step(st, early, new Tally()), 1e-6f);
		StepFacts away = ready(w.critReadyWithin + 0.5f, false);
		away.falling = true;
		assertEquals(new RewardModel().step(st, ready(w.critReadyWithin + 0.5f, false), new Tally()), new RewardModel().step(st, away, new Tally()), 1e-6f);

		// Hopping about ready is not something to collect on: a few ticks per landed hit, shared with
		// what having the sprint in place pays.
		RewardModel m = new RewardModel();
		RewardModel plain = new RewardModel();
		float paid = 0f;
		for (int tick = 0; tick < 30; tick++) {
			StepFacts f = ready(reach, false);
			f.falling = true;
			paid += m.step(st, f, new Tally()) - plain.step(st, ready(reach, false), new Tally());
		}
		assertEquals(6 * w.critReady, paid, 1e-5f);
		assertEquals(0f, m.step(st, ready(reach, true), new Tally()) - plain.step(st, ready(reach, false), new Tally()), 1e-6f, "and nothing is left for the sprint either");

		// Before the crit stage nothing of the kind is paid.
		StageSpec before = stage("Combo escape");
		StepFacts d = ready(reach, false);
		d.falling = true;
		assertEquals(new RewardModel().step(before, ready(reach, false), new Tally()), new RewardModel().step(before, d, new Tally()), 1e-6f);
	}

	/** A jump lasts as long as the sword takes to come back: one made as it swings sets up the critical hit. */
	@Test
	void theJumpMadeAFewTicksAfterTheSwingIsPaidOncePerSwing() {
		// No stage pays for the jump itself any more (see aJumpIsJudgedByWhatComesOfIt); the rule is still there to be set.
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		assertEquals(0f, w.critJump, "a jump is paid by the critical hit it ends in, not for being made");
		w.critJump = 0.2f;
		w.wastedJump = 0f;

		RewardModel m = new RewardModel();
		RewardModel plain = new RewardModel();
		// No swing yet: a jump sets nothing up.
		assertEquals(0f, jumpPay(m, plain, st, false, 0.32f, 2.8f), 1e-6f);
		// It swings, and takes off four ticks later with the sword a third recharged.
		swing(m, st);
		swing(plain, st);
		assertEquals(w.critJump, jumpPay(m, plain, st, false, 0.32f, 2.8f), 1e-6f);
		assertEquals(0f, jumpPay(m, plain, st, false, 0.4f, 2.8f), 1e-6f, "once per swing");
		// Taking off on the very tick of the swing, or the tick after, is too early: it has landed
		// again before the sword is back.
		assertEquals(0f, jumpPay(m, plain, st, true, 1f, 2.8f), 1e-6f);
		assertEquals(0f, jumpPay(m, plain, st, false, 0.08f, 2.8f), 1e-6f);
		// Too late after the swing, the sword is back while it is still on its way up; and from across the arena there is nothing to hit.
		assertEquals(0f, jumpPay(m, plain, st, false, 0.8f, 2.8f), 1e-6f);
		assertEquals(-w.farJump, jumpPay(m, plain, st, false, 0.32f, w.jumpWithin + 1f), 1e-6f);
		assertEquals(w.critJump, jumpPay(m, plain, st, false, 0.32f, 2.8f), 1e-6f, "the swing's one jump was still to be had");

		// Holding the key in the air is not a jump.
		swing(m, st);
		swing(plain, st);
		StepFacts held = facts(2.8f);
		held.reachAtAction = 2.8f;
		held.jumpKey = true;
		held.chargeAtClick = 0.32f;
		StepFacts none = facts(2.8f);
		none.reachAtAction = 2.8f;
		none.chargeAtClick = 0.32f;
		assertEquals(0f, m.step(st, held, new Tally()) - plain.step(st, none, new Tally()), 1e-6f);
	}

	private static void swing(RewardModel m, StageSpec st) {
		StepFacts f = facts(2.8f);
		f.reachAtAction = 2.8f;
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = 1f;
		m.step(st, f, new Tally());
	}

	/** What taking off pays over not doing so, on one tick of two models that have seen the same so far. */
	private static float jumpPay(RewardModel m, RewardModel plain, StageSpec st, boolean withSwing, float chargeAtAction, float reach) {
		StepFacts jump = facts(reach);
		jump.reachAtAction = reach;
		jump.jumpKey = true;
		jump.tookOff = true;
		jump.clicked = withSwing;
		jump.clickOnTarget = withSwing;
		jump.chargeAtClick = chargeAtAction;
		StepFacts stay = facts(reach);
		stay.reachAtAction = reach;
		stay.clicked = withSwing;
		stay.clickOnTarget = withSwing;
		stay.chargeAtClick = chargeAtAction;
		return m.step(st, jump, new Tally()) - plain.step(st, stay, new Tally());
	}

	@Test
	void jumpingOnlyCostsFarFromTheOpponent() {
		StageSpec st = stage("Crits");
		assertTrue(st.reward.jumpWithin > 3.5f, "wherever a hit could follow, a jump is free");
		StepFacts still = facts(2.8f);
		still.reachAtAction = 2.8f;
		StepFacts nearJump = facts(2.8f);
		nearJump.reachAtAction = 2.8f;
		nearJump.jumpKey = true;
		StepFacts farJump = facts(2.8f);
		farJump.reachAtAction = st.reward.jumpWithin + 0.5f;
		farJump.jumpKey = true;
		float base = new RewardModel().step(st, still, new Tally());
		assertEquals(base, new RewardModel().step(st, nearJump, new Tally()), 1e-6f);
		assertEquals(st.reward.farJump, base - new RewardModel().step(st, farJump, new Tally()), 1e-6f);
		// A critical hit that comes a tick late is a slow hit on the ground; here that costs less. Not
		// little: it hardly jumps in a fight, and a sweep is then a hit made without the sprint behind it.
		assertTrue(st.reward.sweep >= 0.3f && st.reward.sweep <= 0.5f * stage("Combo escape").reward.sweep + 1e-6f);
	}

	/** Just out of a sword's reach: a jump from here, at an opponent that stays here, is a jump at nothing. */
	private static final float OUT_OF_REACH = 3.6f;

	/** One tick in the air or on the ground with nothing else happening and the opponent just out of reach, as the model under test and a plain one see it. */
	private static float tick(RewardModel m, RewardModel plain, StageSpec st, boolean tookOff, boolean onGround, Tally t) {
		return tick(m, plain, st, tookOff, onGround, t, OUT_OF_REACH);
	}

	private static float tick(RewardModel m, RewardModel plain, StageSpec st, boolean tookOff, boolean onGround, Tally t, float reach) {
		StepFacts f = facts(reach);
		f.reachAtAction = reach;
		f.jumpKey = tookOff;
		f.tookOff = tookOff;
		f.onGround = onGround;
		StepFacts same = facts(reach);
		same.reachAtAction = reach;
		same.onGround = onGround;
		return m.step(st, f, t) - plain.step(st, same, new Tally());
	}

	/**
	 * A jump is judged by what comes of it. One made at an opponent that is not in its face, and back
	 * on the ground without a critical hit, costs something, once; one that ends in a critical hit
	 * costs nothing. So it is the jump made where it was not a sure thing that is unlearned, and not
	 * the jump as such.
	 */
	@Test
	void aJumpIsJudgedByWhatComesOfIt() {
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		assertTrue(w.wastedJump > 0f);
		// Small next to what a critical hit earns: enough that hopping about is not free, not so much
		// that a jump is only worth making where it is certain. (0.13.1 to 0.13.3: more than half of
		// what a critical hit earns, from anywhere but right in the opponent's face. Fifteen updates
		// into the stage's own fights it had stopped jumping.)
		assertTrue(w.wastedJump <= 0.2f * (w.crit + w.hit), "wasted jump " + w.wastedJump);
		assertEquals(0f, w.jumpFreeWithin, "and no line is drawn for where a jump may be made from");
		assertEquals(0f, stage("Combo escape").reward.wastedJump, "nothing of the kind before the jump is unlocked");
		assertTrue(stage("Duel").reward.wastedJump > 0f && stage("Duel").reward.wastedJump < w.wastedJump);

		// It takes off with the opponent out of reach, is in the air for a while, and lands with nothing done: charged on landing, once.
		RewardModel m = new RewardModel();
		RewardModel plain = new RewardModel();
		Tally t = new Tally();
		float paid = tick(m, plain, st, true, false, t);
		for (int i = 0; i < 10; i++) {
			paid += tick(m, plain, st, false, false, t);
		}
		assertEquals(0f, paid, 1e-6f, "nothing is charged while it is still in the air");
		assertEquals(-w.wastedJump, tick(m, plain, st, false, true, t), 1e-6f);
		assertEquals(0f, tick(m, plain, st, false, true, t), 1e-6f, "once");
		assertEquals(1, t.jumps);
		assertEquals(1, t.jumpsEnded);
		assertEquals(0, t.jumpCrits);
		assertEquals(0f, t.jumpCritRate(), 1e-6f);

		// The same jump with a critical hit landed on the way down: that is what a jump is for.
		m = new RewardModel();
		plain = new RewardModel();
		t = new Tally();
		tick(m, plain, st, true, false, t);
		for (int i = 0; i < 7; i++) {
			tick(m, plain, st, false, false, t);
		}
		StepFacts crit = facts(2.8f);
		crit.reachAtAction = 2.8f;
		crit.clicked = true;
		crit.clickOnTarget = true;
		crit.chargeAtClick = 1f;
		crit.hitsDealt = 1;
		crit.hitCharge = 1f;
		crit.critDealt = true;
		m.step(st, crit, t);
		plain.step(st, crit, new Tally());
		float after = 0f;
		for (int i = 0; i < 6; i++) {
			after += tick(m, plain, st, false, i >= 2, t);
		}
		assertEquals(0f, after, 1e-6f, "nothing is charged for a jump that ended in a critical hit");
		assertEquals(1, t.jumpsEnded);
		assertEquals(1, t.jumpCrits);
		assertEquals(1f, t.jumpCritRate(), 1e-6f);

		// A hit that is not critical does not make the jump worth it.
		m = new RewardModel();
		plain = new RewardModel();
		t = new Tally();
		tick(m, plain, st, true, false, t);
		StepFacts early = facts(2.8f);
		early.reachAtAction = 2.8f;
		early.hitsDealt = 1;
		early.hitCharge = 1f;
		m.step(st, early, t);
		plain.step(st, early, new Tally());
		assertEquals(-w.wastedJump, tick(m, plain, st, false, true, t), 1e-6f);

		// The opponent was put somewhere else in the middle of the jump: nothing is said of that jump.
		m = new RewardModel();
		plain = new RewardModel();
		t = new Tally();
		tick(m, plain, st, true, false, t);
		StepFacts hop = facts(5f);
		hop.discontinuity = true;
		m.step(st, hop, t);
		plain.step(st, hop, new Tally());
		assertEquals(0f, tick(m, plain, st, false, true, t), 1e-6f);
		assertEquals(0, t.jumpsEnded);
		// Nor of one made in the moment after, while it may still be seeing the opponent where it was.
		tick(m, plain, st, true, false, t);
		assertEquals(0f, tick(m, plain, st, false, true, t), 1e-6f);
		assertEquals(0, t.jumpsEnded);
		// Once that moment has passed, jumps count again.
		for (int i = 0; i < 10; i++) {
			tick(m, plain, st, false, true, t);
		}
		tick(m, plain, st, true, false, t);
		assertEquals(-w.wastedJump, tick(m, plain, st, false, true, t), 1e-6f);
		assertEquals(1, t.jumpsEnded);

		// It took off with the opponent in its face, and the opponent got out of reach while it was in
		// the air. Nothing came of the jump: it costs what any such jump costs, which is little.
		m = new RewardModel();
		plain = new RewardModel();
		t = new Tally();
		float inFace = tick(m, plain, st, true, false, t, RewardModel.IN_FACE - 0.1f);
		for (int i = 0; i < 10; i++) {
			inFace += tick(m, plain, st, false, false, t);
		}
		inFace += tick(m, plain, st, false, true, t);
		assertEquals(-w.wastedJump, inFace, 1e-6f, "a jump that comes to nothing costs the same wherever it was made");
		assertEquals(1, t.jumpsEnded);
		assertEquals(0, t.jumpCrits);
		assertEquals(0, t.farJumps);

		// A stage that draws a line (as the crit stage did up to 0.13.3) leaves the jump from within it uncharged.
		StageSpec lined = stage("Crits");
		lined.reward.jumpFreeWithin = RewardModel.IN_FACE;
		m = new RewardModel();
		plain = new RewardModel();
		float free = tick(m, plain, lined, true, false, new Tally(), RewardModel.IN_FACE - 0.1f);
		for (int i = 0; i < 10; i++) {
			free += tick(m, plain, lined, false, false, new Tally());
		}
		free += tick(m, plain, lined, false, true, new Tally());
		assertEquals(0f, free, 1e-6f);

		// From sprint-hit range, with the opponent in reach the whole time and nothing landed: the same.
		m = new RewardModel();
		plain = new RewardModel();
		t = new Tally();
		float fromRange = tick(m, plain, st, true, false, t, 2.8f);
		for (int i = 0; i < 10; i++) {
			fromRange += tick(m, plain, st, false, false, t, 2.8f);
		}
		fromRange += tick(m, plain, st, false, true, t, 2.8f);
		assertEquals(-w.wastedJump, fromRange, 1e-6f);
		assertEquals(0, t.farJumps, "it was not too far off for a jump, and is not counted as one made from there");

		// The key was pressed and it never left the ground: there was no jump.
		m = new RewardModel();
		plain = new RewardModel();
		t = new Tally();
		float none = tick(m, plain, st, true, true, t);
		for (int i = 0; i < 5; i++) {
			none += tick(m, plain, st, false, true, t);
		}
		assertEquals(0f, none, 1e-6f);
		assertEquals(0, t.jumpsEnded);
	}

	/** A whole jump, twelve ticks, from this far off and with the opponent this far off as it comes down; returns what the jump cost over not jumping. */
	private static float jumpAt(StageSpec st, float from, float reachComingDown, Tally t) {
		RewardModel m = new RewardModel();
		RewardModel plain = new RewardModel();
		float paid = 0f;
		for (int i = 0; i < 14; i++) {
			StepFacts f = facts(i >= 6 && i < 12 ? reachComingDown : from);
			f.reachAtAction = from;
			f.jumpKey = i == 0;
			f.tookOff = i == 0;
			f.falling = i >= 6 && i < 12;
			f.onGround = i >= 12;
			StepFacts same = facts(f.reachDist);
			same.reachAtAction = from;
			same.falling = f.falling;
			same.onGround = f.onGround;
			paid += m.step(st, f, t) - plain.step(st, same, new Tally());
		}
		return paid;
	}

	/**
	 * A jump that comes to nothing costs the same small amount wherever it was made from and wherever
	 * the opponent was as it came down: no line is drawn any more. What is counted apart, and costs
	 * for as long as the key is held, is a jump made from farther off than a jump carries.
	 */
	@Test
	void aJumpThatComesToNothingCostsTheSameWhereverItWasMadeFrom() {
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		Tally t = new Tally();
		// (What standing ready in the air pays is the same for the plain model, which is in the air too.)
		assertEquals(-w.wastedJump, jumpAt(st, 4f, 3.6f, t), 1e-5f, "from out of reach, and out of reach all the way down");
		assertEquals(-w.wastedJump, jumpAt(st, 4f, 2.7f, t), 1e-5f, "it took off four blocks away and came down in reach with nothing landed");
		assertEquals(-w.wastedJump, jumpAt(st, 2.6f, 2.6f, t), 1e-5f, "from sprint-hit range");
		assertEquals(-w.wastedJump, jumpAt(st, 1.5f, 1.5f, t), 1e-5f, "from in its face");
		assertEquals(-w.wastedJump, jumpAt(st, 1.5f, 3.6f, t), 1e-5f, "from in its face, and the opponent got away");
		assertEquals(5, t.jumpsEnded);
		assertEquals(0, t.jumpCrits);
		assertEquals(0, t.farJumps, "none of them was made from farther off than a jump carries");
		// A jump taken off at a run carries three and a half blocks, and a sword reaches three more.
		assertTrue(w.jumpWithin >= 6f && w.jumpWithin <= 8f, "jump within " + w.jumpWithin);
		float far = w.jumpWithin + 1f;
		assertEquals(-w.wastedJump - w.farJump, jumpAt(st, far, far, t), 1e-5f, "from across the arena the key costs as well");
		assertEquals(1, t.farJumps);
	}

	/**
	 * Held for the first crit drill, the jump is wanted only with the opponent right in its face. One
	 * taken off from farther away is charged there, also when the opponent is within a sword's reach
	 * the whole time: that is the range sprint hits are made at, and a fighter that jumps there has
	 * no combos. In a fight the same jump is charged the same way; what differs is what is counted as
	 * standing "too far off for a jump", which in a fight means out of reach.
	 */
	@Test
	void inTheFirstCritDrillAJumpFromFartherThanInItsFaceIsCharged() {
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		float zone = 2f;
		for (boolean drill : new boolean[] {true, false}) {
			RewardModel m = new RewardModel();
			RewardModel plain = new RewardModel();
			Tally t = new Tally();
			float paid = 0f;
			// Six ticks of standing at sprint-hit range, then a jump from there; the opponent is in reach all the way down.
			for (int i = 0; i < 20; i++) {
				StepFacts f = facts(2.5f);
				f.reachAtAction = 2.5f;
				f.critZone = drill ? zone : 0f;
				f.jumpKey = i == 6;
				f.tookOff = i == 6;
				f.inAir = i > 6 && i < 18;
				f.falling = i >= 12 && i < 18;
				f.onGround = i < 6 || i >= 18;
				StepFacts same = facts(2.5f);
				same.reachAtAction = 2.5f;
				same.inAir = f.inAir;
				same.falling = f.falling;
				same.onGround = f.onGround;
				paid += m.step(st, f, t) - plain.step(st, same, new Tally());
			}
			assertEquals(-w.wastedJump, paid, 1e-5f, "a jump from sprint-hit range that lands nothing is a jump for nothing");
			if (drill) {
				assertEquals(1, t.farJumps);
				assertTrue(t.farGroundTicks >= 7, "and every tick of standing there counts as one it should not jump on");
			} else {
				assertEquals(0, t.farJumps, "in a fight the opponent was in reach, and it is not counted as jumping at one that is not");
				assertEquals(0, t.farGroundTicks);
			}
		}
		// From within the zone the drill charges nothing, whatever comes of the jump.
		RewardModel m = new RewardModel();
		RewardModel plain = new RewardModel();
		float paid = 0f;
		for (int i = 0; i < 14; i++) {
			StepFacts f = facts(1.5f);
			f.reachAtAction = 1.5f;
			f.critZone = zone;
			f.jumpKey = i == 0;
			f.tookOff = i == 0;
			f.falling = i >= 6 && i < 12;
			f.onGround = i >= 12;
			StepFacts same = facts(1.5f);
			same.reachAtAction = 1.5f;
			same.falling = f.falling;
			same.onGround = f.onGround;
			paid += m.step(st, f, new Tally()) - plain.step(st, same, new Tally());
		}
		assertEquals(0f, paid, 1e-5f);
	}

	/**
	 * A swing in the air at nothing costs: the weapon is thrown away in mid-air, and gone when the
	 * opponent arrives. A swing on the ground that misses still costs nothing but the charge.
	 */
	@Test
	void aSwingInTheAirAtNothingCostsAndOneOnTheGroundDoesNot() {
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		assertTrue(w.airWhiff > w.wastedJump, "worse than the jump it was made in: it also throws the sword away");
		assertEquals(0f, w.whiff, "a miss on the ground is not charged in any stage");
		assertEquals(0f, stage("Combo escape").reward.airWhiff);
		assertTrue(stage("Duel").reward.airWhiff > 0f && stage("Duel").reward.airWhiff < w.airWhiff);
		Tally t = new Tally();
		float quiet = new RewardModel().step(st, facts(4f), new Tally());

		StepFacts ground = facts(4f);
		ground.clicked = true;
		ground.chargeAtClick = 1f;
		assertEquals(quiet, new RewardModel().step(st, ground, t), 1e-6f, "on the ground a miss is free");
		assertEquals(0, t.airSwings);

		StepFacts air = facts(4f);
		air.reachAtAction = 4f;
		air.clicked = true;
		air.chargeAtClick = 1f;
		air.inAir = true;
		assertEquals(quiet - w.airWhiff, new RewardModel().step(st, air, t), 1e-6f, "in the air it is not");
		assertEquals(1, t.airSwings);
		assertEquals(1, t.airWhiffs);

		// One that reaches the opponent costs nothing, whatever comes of it.
		StepFacts reached = facts(2.6f);
		reached.clicked = true;
		reached.clickOnTarget = true;
		reached.chargeAtClick = 1f;
		reached.inAir = true;
		assertEquals(new RewardModel().step(st, facts(2.6f), new Tally()), new RewardModel().step(st, reached, t), 1e-6f);
		assertEquals(2, t.airSwings);
		assertEquals(1, t.airWhiffs);
		assertEquals(0.5f, t.airWhiffRate(), 1e-6f);

		// A stage can let a miss at an opponent at the edge of its reach go uncharged (airWhiffBeyond).
		// None does: tried, with an answer to a hit paid for whether it landed or not, it threw its sword
		// away in the air after every hit it took. Swinging back where a hit is there is the rule's now.
		for (String name : new String[] {"Crits", "Duel", "Self-play"}) {
			assertEquals(0f, stage(name).reward.airWhiffBeyond, name);
			assertTrue(stage(name).reward.airWhiff > 0f, name);
			assertTrue(stage(name).reward.answerWithin < 3.5f, name);
		}
		StageSpec lenient = stage("Crits");
		lenient.reward.airWhiffBeyond = 3.6f;
		for (float reach : new float[] {3.05f, 3.55f}) {
			StepFacts attempt = facts(reach);
			attempt.reachAtAction = reach;
			attempt.clicked = true;
			attempt.chargeAtClick = 1f;
			attempt.inAir = true;
			Tally tried = new Tally();
			assertEquals(new RewardModel().step(lenient, facts(reach), new Tally()), new RewardModel().step(lenient, attempt, tried), 1e-6f, "at " + reach);
			assertEquals(1, tried.airWhiffs);
		}
		StepFacts hopeless = facts(3.8f);
		hopeless.reachAtAction = 3.8f;
		hopeless.clicked = true;
		hopeless.chargeAtClick = 1f;
		hopeless.inAir = true;
		assertEquals(lenient.reward.airWhiff, new RewardModel().step(lenient, facts(3.8f), new Tally()) - new RewardModel().step(lenient, hopeless, new Tally()), 1e-6f);
		// The stand-in rule: with no distance given, every miss in the air costs.
		RewardWeights plain = new RewardWeights();
		assertEquals(0f, plain.airWhiffBeyond);
	}

	/** How often it jumps with the opponent too far off for a jump to carry it there is counted directly. */
	@Test
	void jumpsMadeWithTheOpponentTooFarOffAreCounted() {
		StageSpec st = stage("Crits");
		float away = st.reward.jumpWithin + 1f;
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		for (int i = 0; i < 50; i++) {
			StepFacts f = facts(away);
			f.reachAtAction = away;
			f.onGround = true;
			f.jumpKey = i == 10;
			f.tookOff = i == 10;
			m.step(st, f, t);
		}
		assertEquals(50, t.farGroundTicks);
		assertEquals(1, t.farJumps);
		assertEquals(0.02f, t.farJumpRate(), 1e-6f);
		// Within a jump's carry (out of a sword's reach or not), or already in the air, is not such a tick.
		StepFacts near = facts(4.5f);
		near.reachAtAction = 4.5f;
		near.onGround = true;
		near.jumpKey = true;
		near.tookOff = true;
		m.step(st, near, t);
		StepFacts flying = facts(away);
		flying.reachAtAction = away;
		flying.inAir = true;
		m.step(st, flying, t);
		assertEquals(50, t.farGroundTicks);
		assertEquals(1, t.farJumps);
		// Nor are the ticks right after the opponent was put somewhere else: it may still be seen where it was.
		StepFacts hop = facts(away);
		hop.reachAtAction = away;
		hop.onGround = true;
		hop.discontinuity = true;
		m.step(st, hop, t);
		assertEquals(50, t.farGroundTicks);
	}

	private static float hitFor(RewardModel m, StageSpec st, float damage, float charge, boolean crit, Tally t) {
		StepFacts f = facts(2.8f);
		f.reachAtAction = 2.8f;
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = charge;
		f.hitsDealt = 1;
		f.hitCharge = charge;
		f.sprintHit = !crit && charge > 0.9f;
		f.critDealt = crit;
		f.damageDealt = damage;
		return m.step(st, f, t);
	}

	/**
	 * The other way to a critical hit's damage. Where a critical hit is not a sure thing the jump is
	 * left alone and the sprint-hit fighter plays on; when the sprint hits of its combo then add up to
	 * what the critical hit would have done, that was the right call and is paid as a critical hit is.
	 * A sprint hit that stays alone did less than a critical hit and earns none of it.
	 */
	@Test
	void aComboOfSprintHitsThatDoesACriticalHitsDamageIsPaidAsOne() {
		// No stage pays this any more: it made leaving the jump alone worth as much as jumping, and the
		// jump was left alone (0.13.2: not one critical hit in 130 updates). The rule is still there to be set.
		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			assertEquals(0f, stage(name).reward.comboAsCrit, name);
		}
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		w.comboAsCrit = w.crit;
		// Through diamond armour a diamond sword does about 1.9 a hit and 3.2 with a critical hit.
		float plainHit = 1.89f;
		float critHit = 3.2f;

		// What the same hits earn with the damage unknown: every term but this one.
		RewardModel paid = new RewardModel();
		RewardModel base = new RewardModel();
		Tally t = new Tally();
		// A critical hit of its own, so that it is known what one does.
		assertEquals(0f, hitFor(paid, st, critHit, 1f, true, t) - hitFor(base, st, 0f, 1f, true, new Tally()), 1e-5f, "a critical hit is paid as a critical hit, not here");
		// Hit while it waits: the combo is over, and a new one begins with the next hit.
		StepFacts taken = facts(2.8f);
		taken.hitsTaken = 1;
		paid.step(st, taken, t);
		base.step(st, taken, new Tally());

		assertEquals(0f, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f,
				"one sprint hit does less than a critical hit");
		assertEquals(w.comboAsCrit, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f,
				"followed up, the two have done more than one: the combo was as good as the critical hit");
		assertEquals(0f, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f,
				"the third hit does not reach a second critical hit's worth (5.7 of 6.4)");
		assertEquals(w.comboAsCrit, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f,
				"the fourth does: a long combo goes on being paid, so there is nothing to gain by breaking one off");
		assertEquals(2, t.comboCritWorths);
		// A tap in the middle of it counts for nothing here, as it counts for nothing in the combo.
		assertEquals(0f, hitFor(paid, st, 0.4f, 0.3f, false, t) - hitFor(base, st, 0f, 0.3f, false, new Tally()), 1e-5f);
		// A critical hit in the middle of a combo is paid as itself and adds nothing to the sprint hits' count.
		assertEquals(0f, hitFor(paid, st, critHit, 1f, true, t) - hitFor(base, st, 0f, 1f, true, new Tally()), 1e-5f);
		assertEquals(0f, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f,
				"five sprint hits in all: 9.45, just short of three critical hits' worth");
		assertEquals(w.comboAsCrit, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f,
				"and the sixth makes it three");

		// The combo ends (it is hit): the count starts again, and a single hit after that earns none.
		paid.step(st, taken, t);
		base.step(st, taken, new Tally());
		assertEquals(0f, hitFor(paid, st, plainHit, 1f, false, t) - hitFor(base, st, 0f, 1f, false, new Tally()), 1e-5f);
		assertEquals(3, t.comboCritWorths);

		// Before it has ever landed a critical hit, one is taken to do half as much again as a full hit: two hits still make one.
		RewardModel fresh = new RewardModel();
		RewardModel freshBase = new RewardModel();
		assertEquals(0f, hitFor(fresh, st, 7f, 1f, false, new Tally()) - hitFor(freshBase, st, 0f, 1f, false, new Tally()), 1e-5f);
		assertEquals(w.comboAsCrit, hitFor(fresh, st, 7f, 1f, false, new Tally()) - hitFor(freshBase, st, 0f, 1f, false, new Tally()), 1e-5f);
		assertEquals(w.comboAsCrit, hitFor(fresh, st, 7f, 1f, false, new Tally()) - hitFor(freshBase, st, 0f, 1f, false, new Tally()), 1e-5f, "21 is two of 10.5");
	}

	/**
	 * A punish crit is counted against the chances there were of one: hits that knocked the fighter
	 * up and left the opponent under its crosshair and in reach as it came down with the weapon back.
	 */
	@Test
	void punishCritsAreCountedAgainstTheChancesThereWereOfOne() {
		StageSpec st = stage("Crits");
		assertTrue(st.reward.punishCrit >= 1f, "worth clearly more than a critical hit from a jump: it needs no jump and cannot be seen coming");
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		StepFacts hit = facts(2.8f);
		hit.hitsTaken = 1;
		m.step(st, hit, t);
		// On the way up, and then coming down with the opponent out of reach: no chance yet.
		StepFacts up = facts(3.4f);
		up.knockedUp = true;
		m.step(st, up, t);
		assertEquals(0, t.punishChances);
		// Coming down with everything in place, for three ticks: one chance, not three.
		for (int i = 0; i < 3; i++) {
			StepFacts open = facts(2.6f);
			open.knockedUp = true;
			open.critOpen = true;
			m.step(st, open, t);
		}
		assertEquals(1, t.punishChances);
		assertEquals(0, t.punishCrits);
		assertEquals(0f, t.punishRate(), 1e-6f);

		// The next hit, and this time it is taken.
		m.step(st, hit, t);
		StepFacts punish = facts(2.6f);
		punish.knockedUp = true;
		punish.critOpen = true;
		punish.hitsDealt = 1;
		punish.hitCharge = 1f;
		punish.critDealt = true;
		punish.punishCrit = true;
		m.step(st, punish, t);
		assertEquals(2, t.punishChances);
		assertEquals(1, t.punishCrits);
		assertEquals(0.5f, t.punishRate(), 1e-6f);
		assertEquals(1f, t.punishChanceRate(), 1e-6f);

		// A critical hit in its own jump with the opponent in reach is no chance of a punish crit: nothing knocked it up.
		StepFacts own = facts(2.6f);
		own.critOpen = true;
		m.step(st, hit, t);
		m.step(st, own, t);
		assertEquals(2, t.punishChances);
	}

	/** Held in place for the punish drill, being hit is the drill's doing: it is counted, and nothing is charged for it. */
	@Test
	void hitsItCannotAvoidInADrillAreNotCharged() {
		StageSpec st = stage("Crits");
		assertTrue(st.reward.hitTaken > 0f && st.reward.comboedHit > 0f && st.reward.critTaken > 0f);
		// The same stage with being hit costing nothing at all: what a held fighter's hits come to.
		StageSpec free = stage("Crits");
		free.reward.hitTaken = 0f;
		free.reward.comboedHit = 0f;
		free.reward.comboThird = 0f;
		free.reward.critTaken = 0f;
		RewardModel held = new RewardModel();
		RewardModel uncharged = new RewardModel();
		Tally t = new Tally();
		for (int i = 0; i < 3; i++) {
			StepFacts hit = facts(2.8f);
			hit.hitsTaken = 1;
			hit.critsTaken = 1;
			StepFacts same = facts(2.8f);
			same.hitsTaken = 1;
			same.critsTaken = 1;
			hit.cannotAvoid = true;
			assertEquals(uncharged.step(free, same, new Tally()), held.step(st, hit, t), 1e-6f, "hit " + i);
		}
		assertEquals(3, t.hitsTaken);
		assertEquals(3, t.critsTaken);
		// In a fight the same hits cost what they always cost.
		StepFacts hit = facts(2.8f);
		hit.hitsTaken = 1;
		assertTrue(new RewardModel().step(st, hit, new Tally()) < new RewardModel().step(st, facts(2.8f), new Tally()) - 1f);
	}

	/**
	 * The crit stage's own opponent stays on top of the fighter after most of its critical hits. When
	 * it backed off after every hit there was never anybody in reach to punish.
	 */
	@Test
	void theOpponentThatCritsStaysCloseEnoughToBePunished() {
		List<StageSpec> stages = Stages.defaults();
		BotProfile bot = stages.get(7).bot;
		assertTrue(bot.critChance > 0f && bot.critPress >= 0.5f && bot.critPress < 1f, "most of the time, not always: sometimes there is nothing to punish");
		assertTrue(bot.sprint, "staying on top of someone is done at a run");
		assertEquals(bot.critPress, bot.copy().critPress, 0f);
		for (int i = 0; i < 7; i++) {
			assertEquals(0f, stages.get(i).bot.critPress, stages.get(i).name);
		}
		BotProfile far = new BotProfile();
		far.farChance = 0.5f;
		far.farMinDistance = 3.8f;
		far.farMaxDistance = 6.5f;
		BotProfile copy = far.copy();
		assertEquals(0.5f, copy.farChance, 0f);
		assertEquals(3.8f, copy.farMinDistance, 0f);
		assertEquals(6.5f, copy.farMaxDistance, 0f);
		assertEquals(0f, far.drill().farChance, 0f, "a combo drill starts on top of it, always");
	}

	@Test
	void theCritStageIsPassedByLandingCriticalHitsAndKeepingItsDrillsPassed() {
		List<StageSpec> stages = Stages.defaults();
		Promotion p = stages.get(7).promote;
		assertTrue(stages.get(7).goal.contains("critical hits"), stages.get(7).goal);
		assertEquals(0f, p.minPunishRate, "punish crits are taught in a drill of their own, not asked for again here");
		assertEquals(0f, p.minSprintHits, "nor is the share of sprint hits, which critical hits take from");
		// Not a large share of critical hits: against the opponent that a sprint hit knocks away, the
		// combo is the right choice. They are asked of the fights against the one that cannot be kept
		// away, which are four in ten.
		assertTrue(p.minCritShare >= 0.05f && p.minCritShare <= 0.2f);
		assertTrue(stages.get(7).traderShare >= 0.05f && stages.get(7).traderShare <= 0.5f);
		// And against the one in a corner, the one that takes no knockback and the odd ones, with a
		// share left for the opponent a sprint hit knocks away.
		StageSpec crit = stages.get(7);
		assertTrue(crit.cornerShare >= 0.05f && crit.noKnockShare >= 0.05f && crit.oddShare >= 0.05f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare <= 0.85f);
		// And the two it lost to on its first evening against people: the one that comes after it with
		// a running jump and a critical hit, and the one that answers its hits with a critical hit.
		assertTrue(crit.jumperShare >= 0.1f && crit.punisherShare >= 0.1f && crit.backerShare >= 0.05f && crit.timerShare >= 0.1f
				&& crit.spacerShare >= 0.05f && crit.whifferShare >= 0.05f);
		assertTrue(crit.traderShare + crit.runnerShare + crit.circlerShare + crit.cornerShare + crit.noKnockShare + crit.oddShare
				+ crit.jumperShare + crit.punisherShare + crit.backerShare + crit.timerShare + crit.spacerShare + crit.whifferShare + crit.peopleShare <= 0.97f,
				"a share is left for the opponent a sprint hit knocks away");
		assertEquals(5, crit.bot.dodgeTicks);
		BotProfile backer = crit.bot.copy();
		backer.dodgeTicks = 12;
		assertEquals(12, backer.copy().dodgeTicks);
		BotProfile jumper = crit.bot.copy();
		jumper.jumpIn = true;
		jumper.punish = true;
		jumper.timer = true;
		jumper.spacer = true;
		assertTrue(jumper.copy().jumpIn && jumper.copy().punish && jumper.copy().timer && jumper.copy().spacer);
		assertFalse(crit.bot.jumpIn || crit.bot.punish || crit.bot.timer || crit.bot.spacer);
		assertTrue(crit.besideShare > 0f && crit.randomShare > 0f && crit.runnerKeepsAway > 0f);
		// And the two a jump is for: one that keeps running out of the fight, one that goes round it
		// making critical hits. With the trader they leave a share for the opponent that punishes a jump.
		assertTrue(stages.get(7).runnerShare >= 0.1f && stages.get(7).circlerShare >= 0.1f);
		assertTrue(stages.get(7).traderShare + stages.get(7).runnerShare + stages.get(7).circlerShare <= 0.8f);
		BotProfile runner = stages.get(7).bot.copy();
		runner.fleeChance = stages.get(7).runnerFlee;
		runner.orbit = true;
		assertEquals(stages.get(7).runnerFlee, runner.copy().fleeChance);
		assertTrue(runner.copy().orbit);
		assertTrue(runner.copy().fleeTurned > 0f && runner.copy().fleeTurned < 0.5f, "most times it backs off with its sword ready");
		assertTrue(stages.get(7).traderKnockbackMax <= 0.5f, "back before the sword is");
		for (int i = 0; i < stages.size(); i++) {
			assertEquals(i == 7 ? stages.get(7).traderShare : 0f, stages.get(i).traderShare, stages.get(i).name);
		}
		assertEquals(0f, p.minJumpCritRate);
		Curriculum c = new Curriculum(stages);
		c.setStage(7);
		passCritDrills(c);
		Tally t = new Tally();
		// Half the time the stage asks for, and then the other half.
		t.steps = stages.get(7).promote.minSteps / 2;
		t.hitsDealt = 3000;
		t.sprintHits = 2700;
		t.hitsTaken = 2000;
		t.critHits = 30;
		t.jumpsEnded = 400;
		t.jumpCrits = 30;
		c.record(t);
		assertTrue(c.promotionBlocker().contains("more steps needed"), c.promotionBlocker());
		c.record(t);
		assertTrue(c.promotionBlocker().startsWith("critical hits 1% < 8%"), c.promotionBlocker());
		assertFalse(c.promotionBlocker().contains("more steps"), "and waiting does not get it past that: " + c.promotionBlocker());
		assertFalse(c.tryPromote());
		// However long it stays.
		for (int i = 0; i < 20; i++) {
			c.record(t);
		}
		assertTrue(c.promotionBlocker().startsWith("critical hits 1% < 8%"), c.promotionBlocker());
		assertFalse(c.tryPromote());

		// Critical hits enough in its fights. The drills go on, and are still passed: nothing in the way.
		c.setStage(7);
		passCritDrills(c);
		t.steps = stages.get(7).promote.minSteps + 10_000;
		t.critHits = 600;
		t.jumpsEnded = 2000;
		t.jumpCrits = 600;
		c.record(t);
		assertNull(c.critDrillsKept());
		assertNull(c.promotionBlocker(), "many of its jumps in a fight come to nothing, and that is not asked about");

		// In the fights it has stopped jumping, and now does not jump in the drill either: two minutes of
		// the first drill and a handful of hits. The first drill was a lesson, and it is not asked to stay
		// passed: against the opponent a sprint hit knocks away a jump loses more hits than its critical
		// hit is worth (0.14.0), and one network makes that call for every opponent.
		assertFalse(stages.get(7).critDrillFirstKept);
		Tally held = goodDrills();
		Tally gone = new Tally();
		gone.steps = Curriculum.KEEP_JUMP_STEPS;
		gone.hitsDealt = 5;
		gone.critHits = 5;
		assertEquals(-1, c.recordCritDrills(gone, held));
		assertNull(c.critDrillsKept());
		assertNull(c.promotionBlocker());
		assertFalse(c.critDrillsOnly(), "the lessons are not taken again");

		// A stage that does ask for it (this is what 0.13.2 did, and it went unseen).
		List<StageSpec> asking = Stages.defaults();
		asking.get(7).critDrillFirstKept = true;
		Curriculum k = new Curriculum(asking);
		k.setStage(7);
		passCritDrills(k);
		k.record(t);
		assertNull(k.promotionBlocker());
		assertEquals(-1, k.recordCritDrills(gone, held));
		assertTrue(k.critDrillsKept().startsWith("the crit-in-its-face drill"), k.critDrillsKept());
		assertTrue(k.promotionBlocker().startsWith("the crit-in-its-face drill, which it still does, is no longer passed"), k.promotionBlocker());
		assertFalse(k.tryPromote());
		assertFalse(k.critDrillsOnly(), "the lessons are not taken again: the drills go on among the fights, and it has to get it back there");
		// Hits again, and plenty, but they are sprint hits: a fifth critical is not what the drill is kept on.
		Tally sprinting = goodDrills();
		sprinting.critHits = 20;
		assertEquals(-1, k.recordCritDrills(sprinting, held));
		assertTrue(k.promotionBlocker().startsWith("the crit-in-its-face drill"), k.promotionBlocker());
		// Too little of the drill yet to say anything new: what was last found stands.
		Tally little = new Tally();
		little.steps = 300;
		little.hitsDealt = 10;
		little.critHits = 10;
		assertEquals(-1, k.recordCritDrills(little, new Tally()));
		assertTrue(k.promotionBlocker().startsWith("the crit-in-its-face drill"), k.promotionBlocker());
		// Kept on less than the lesson asked: in the fights it learns when not to jump, and some of that comes back with it.
		Tally fewer = goodDrills();
		fewer.critHits = 40;
		assertTrue(stages.get(7).critDrillKeep < stages.get(7).critDrillPass && 0.4f >= stages.get(7).critDrillKeep && 0.4f < stages.get(7).critDrillPass);
		assertEquals(-1, k.recordCritDrills(fewer, held));
		assertNull(k.critDrillsKept(), "four in ten is not the lesson passed, but it is the lesson kept");
		assertEquals(-1, k.recordCritDrills(gone, held));
		assertNotNull(k.critDrillsKept());
		// And it has it back.
		assertEquals(-1, k.recordCritDrills(held, held));
		assertNull(k.critDrillsKept());
		assertNull(k.promotionBlocker());

		// The punish crit does have to stay: it leaves the chances it is given.
		Tally left = goodDrills();
		left.punishChances = 30;
		left.punishCrits = 6;
		assertEquals(-1, c.recordCritDrills(held, left));
		assertTrue(c.promotionBlocker().startsWith("the punish-crit drill, which it still does, is no longer passed"), c.promotionBlocker());
		assertFalse(c.tryPromote());
		assertEquals(-1, c.recordCritDrills(held, held));
		assertNull(c.promotionBlocker());
		assertTrue(c.tryPromote());
		assertEquals("Duel", c.current().name);
		assertNull(c.critDrillsKept(), "a stage without the drills has nothing to keep");
	}

	/** Whether the drills are still passed is remembered over a restart: one that has forgotten the punish crit is not let through by being stopped and started. */
	@Test
	void whetherTheCritDrillsAreStillPassedIsSaved(@TempDir Path dir) throws Exception {
		List<StageSpec> stages = Stages.defaults();
		Curriculum c = new Curriculum(stages);
		c.setStage(7);
		passCritDrills(c);
		Tally left = goodDrills();
		left.punishChances = 30;
		left.punishCrits = 6;
		c.recordCritDrills(new Tally(), left);
		assertTrue(c.critDrillsKept().startsWith("the punish-crit drill"));
		Path file = dir.resolve("curriculum.properties");
		c.save(file);
		Curriculum again = new Curriculum(stages);
		again.load(file);
		assertFalse(again.critDrillsOnly());
		assertTrue(again.critDrillsKept().startsWith("the punish-crit drill"), again.critDrillsKept());

		// And the first drill, in a stage that asks for it to be kept.
		stages.get(7).critDrillFirstKept = true;
		Curriculum k = new Curriculum(stages);
		k.setStage(7);
		passCritDrills(k);
		Tally gone = new Tally();
		gone.steps = Curriculum.KEEP_JUMP_STEPS;
		k.recordCritDrills(gone, new Tally());
		assertTrue(k.critDrillsKept().startsWith("the crit-in-its-face drill"));
		k.save(file);
		Curriculum kAgain = new Curriculum(stages);
		kAgain.load(file);
		assertTrue(kAgain.critDrillsKept().startsWith("the crit-in-its-face drill"), kAgain.critDrillsKept());
	}

	private static float landed(RewardModel m, StageSpec st, float charge, Tally t) {
		StepFacts f = facts(2.8f);
		f.reachAtAction = 2.8f;
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = charge;
		f.hitsDealt = 1;
		f.hitCharge = charge;
		// A sprint hit when the weapon was back, as its combos are made of; a tap knocks nobody back.
		f.sprintHit = charge > 0.9f;
		return m.step(st, f, t);
	}

	/**
	 * A swing made before the weapon is back is a tap. Landed in the middle of a combo it earns
	 * nothing for the combo, does not carry it on, and costs more than it brings: tapping away at an
	 * opponent from close up is a loss on every swing.
	 */
	@Test
	void aTapThatLandsIsALossAndNoPartOfACombo() {
		for (String name : new String[] {"Sprint-hit + W-tap", "Combo escape", "Crits", "Duel"}) {
			StageSpec st = stage(name);
			RewardWeights w = st.reward;
			assertTrue(w.spam >= 0.5f * w.hit, name + ": a tap costs at least half of what a full hit is worth");
			assertTrue(w.combo > 0f, name);
			RewardModel full = new RewardModel();
			RewardModel tap = new RewardModel();
			RewardModel none = new RewardModel();
			for (RewardModel m : new RewardModel[] {full, tap, none}) {
				landed(m, st, 1f, new Tally());
				landed(m, st, 1f, new Tally());
			}
			float quiet = none.step(st, facts(2.8f), new Tally());
			float third = landed(full, st, 1f, new Tally());
			float weak = landed(tap, st, 0.3f, new Tally());
			assertTrue(third > quiet + w.hit, name + ": a full third hit earns the hit and the combo");
			assertTrue(weak < quiet - 0.3f * w.hit, name + ": a tap that lands is a loss, " + weak + " against " + quiet);
			// The hit after it: for the one that tapped, the combo stands where it stood before the tap.
			float fourth = landed(full, st, 1f, new Tally());
			float afterTap = landed(tap, st, 1f, new Tally());
			assertEquals(w.combo, fourth - afterTap, 1e-5f, name);
		}
	}

	/** A batch of crit drills in which everything asked for is done. */
	private static Tally goodDrills() {
		Tally drills = new Tally();
		drills.steps = 1200;
		drills.hitsDealt = 100;
		drills.critHits = 95;
		drills.jumpsEnded = 100;
		drills.jumpCrits = 95;
		drills.farGroundTicks = 800;
		drills.farJumps = 3;
		drills.hitsTaken = 60;
		drills.punishChances = 40;
		drills.punishCrits = 36;
		return drills;
	}

	/** Takes the crit stage through its two drills: the first on one good batch, the second on as many as it is judged over. */
	private static void passCritDrills(Curriculum c) {
		Tally drills = goodDrills();
		assertEquals(Curriculum.DRILL_RANGE, c.recordCritDrills(drills, drills));
		int passed = -1;
		int batches = 0;
		while (passed < 0 && batches < 20) {
			passed = c.recordCritDrills(drills, drills);
			batches++;
		}
		assertEquals(Curriculum.DRILL_PUNISH, passed);
		assertTrue(batches * drills.punishChances >= Curriculum.PUNISH_LESSON_CHANCES, "not on its first batch: " + batches);
		assertTrue(c.punishJudgedChances() >= Curriculum.PUNISH_LESSON_CHANCES);
		assertFalse(c.critDrillLeft());
		assertFalse(c.critDrillsOnly());
		assertEquals(-1, c.recordCritDrills(drills, drills), "nothing left to pass");
		assertNull(c.critDrillsKept());
	}

	@Test
	void critsTakenAreCountedWithTheHits() {
		StageSpec st = stage("Crits");
		Tally t = new Tally();
		StepFacts f = facts(2.8f);
		f.hitsTaken = 2;
		f.critsTaken = 1;
		new RewardModel().step(st, f, t);
		assertEquals(2, t.hitsTaken);
		assertEquals(1, t.critsTaken);
		t.punishChances = 2;
		t.punishCrits = 3;
		assertEquals(1f, t.punishRate(), "never more than every chance taken");
		t.punishChances = 6;
		assertEquals(0.5f, t.punishRate(), 1e-6f, "counted per chance it had, not per critical hit it took");
	}

	@Test
	void theDuelIsAgainstItselfAndEndsOnceThatCopyIsBeatenFourTimesInFive() {
		Curriculum c = new Curriculum(Stages.defaults());
		c.setStage(8);
		assertEquals("Duel", c.current().name);
		assertEquals(BotProfile.SELF, c.current().bot.movement, "no scripted bot: it fights a copy of itself");
		assertTrue(c.selfPlay());
		assertTrue(c.fixedCopy(), "and that copy stays the fighter it was when the duel began");
		assertFalse(c.current().bot.scalesWithSkill);
		Promotion p = c.current().promote;
		assertFalse(p.endless);
		assertEquals(0.80f, p.minWinRate);
		assertEquals(0f, p.minBotSkill);
		// Entering the stage asks for a new copy, made from the agent as it is now.
		assertTrue(c.freshCopyWanted());
		assertTrue(c.takeFreshCopy());
		assertFalse(c.freshCopyWanted());

		// However far ahead it gets, it does not take the copy's place: beating it is what ends the stage.
		for (int round = 0; round < 5; round++) {
			Tally t = new Tally();
			t.steps = 14_000;
			t.wins = 9;
			t.losses = 1;
			c.record(t);
			assertEquals(Curriculum.SelfPlay.UNDECIDED, c.judgeSelfPlay());
		}
		assertEquals(1, c.generation());
		assertNull(c.promotionBlocker());

		// Only just ahead of the copy, it stays.
		Curriculum even = new Curriculum(Stages.defaults());
		even.setStage(8);
		Tally start = new Tally();
		start.steps = 40_000;
		even.record(start);
		Tally close = new Tally();
		close.steps = 29_000;
		close.wins = 13;
		close.losses = 9;
		even.record(close);
		assertTrue(even.promotionBlocker().startsWith("win rate"), even.promotionBlocker());

		// And a handful of fights is not enough to say.
		Curriculum few = new Curriculum(Stages.defaults());
		few.setStage(8);
		few.record(start);
		Tally quiet = new Tally();
		quiet.steps = 31_000;
		quiet.wins = 5;
		few.record(quiet);
		assertEquals("15 more decided fights needed", few.promotionBlocker());

		// A fighter that has all but stopped winning against the copy starts again from it.
		Curriculum lost = new Curriculum(Stages.defaults());
		lost.setStage(8);
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(lost, 1, 19));
		assertEquals(Curriculum.SelfPlay.CHALLENGER_REPLACED, fights(lost, 1, 9));

		// Won: self-play follows, against a new copy of the fighter that won the duel.
		assertTrue(c.tryPromote());
		assertEquals("Self-play", c.current().name);
		assertTrue(c.selfPlay());
		assertFalse(c.fixedCopy());
		assertTrue(c.freshCopyWanted(), "the duel's copy is the one it has just beaten; the next has to be itself as it is now");
		assertEquals(0, c.selfWins() + c.selfLosses());
		assertEquals("final stage", c.promotionBlocker());
	}

	private static Curriculum.SelfPlay fights(Curriculum c, int wins, int losses) {
		Tally t = new Tally();
		t.steps = 1800;
		t.wins = wins;
		t.losses = losses;
		c.record(t);
		return c.judgeSelfPlay();
	}

	@Test
	void inSelfPlayTheWinnerSurvives() {
		Curriculum c = new Curriculum(Stages.defaults());
		c.setStage(9);
		assertEquals(1, c.generation());
		// Too few fights to say anything, however one-sided.
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(c, 10, 0));
		// Twenty fights, thirteen won: the challenger takes the copy's place and the count starts over.
		assertEquals(Curriculum.SelfPlay.CHALLENGER_WINS, fights(c, 3, 7));
		assertEquals(2, c.generation());
		assertEquals(0, c.selfWins() + c.selfLosses());

		// Level pegging decides nothing; the older fights fade so that recent form counts.
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(c, 10, 10));
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(c, 10, 10));
		assertEquals(20, c.selfWins() + c.selfLosses());
		// A good run after that is enough.
		assertEquals(Curriculum.SelfPlay.CHALLENGER_WINS, fights(c, 14, 0));
		assertEquals(3, c.generation());

		// A challenger that has all but stopped winning is replaced by the copy, twice at most in a row.
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(c, 1, 19));
		assertEquals(Curriculum.SelfPlay.CHALLENGER_REPLACED, fights(c, 1, 9));
		assertEquals(3, c.generation());
		assertEquals(Curriculum.SelfPlay.CHALLENGER_REPLACED, fights(c, 2, 28));
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(c, 2, 28));

		// Outside the stages against a copy there is nothing to judge.
		Curriculum crits = new Curriculum(Stages.defaults());
		crits.setStage(7);
		assertEquals("Crits", crits.current().name);
		assertFalse(crits.selfPlay());
		assertFalse(crits.freshCopyWanted());
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(crits, 30, 0));
		assertEquals(0, crits.selfWins());
	}

	@Test
	void selfPlayProgressSurvivesSaveAndLoad(@TempDir Path dir) throws Exception {
		Curriculum a = new Curriculum(Stages.defaults());
		a.setStage(9);
		fights(a, 15, 5);
		fights(a, 6, 4);
		Path file = dir.resolve("curriculum.properties");
		a.save(file);
		Curriculum b = new Curriculum(Stages.defaults());
		b.load(file);
		assertEquals(2, b.generation());
		assertEquals(6, b.selfWins());
		assertEquals(4, b.selfLosses());
		assertTrue(b.selfPlay());
		// Whether a new copy is still owed is kept too: here it is, nobody has taken it yet.
		assertTrue(b.freshCopyWanted());
		b.takeFreshCopy();
		b.save(file);
		Curriculum d = new Curriculum(Stages.defaults());
		d.load(file);
		assertFalse(d.freshCopyWanted());
	}

	@Test
	void everyStageHasItsOwnCodeForTheCritic() {
		List<StageSpec> stages = Stages.defaults();
		java.util.Set<String> codes = new java.util.HashSet<>();
		for (int i = 0; i < stages.size(); i++) {
			float[] priv = new float[dev.swordai.core.rl.ObsSpec.PRIV];
			dev.swordai.core.rl.ObsBuilder.writeStage(priv, i);
			StringBuilder code = new StringBuilder();
			for (int k = 0; k < dev.swordai.core.rl.ObsSpec.PV_STAGE_SLOTS; k++) {
				code.append(priv[dev.swordai.core.rl.ObsSpec.PV_STAGE + k] > 0f ? '1' : '0');
			}
			assertTrue(codes.add(code.toString()), stages.get(i).name + " shares the code " + code);
		}
		assertEquals(10, codes.size());
	}

	private static float comboHit(StageSpec st, int hitsBefore, boolean uppercut, boolean sweep, Tally t) {
		RewardModel m = new RewardModel();
		for (int i = 0; i < hitsBefore; i++) {
			StepFacts f = facts(2.8f);
			f.onBody = true;
			f.hitsDealt = 1;
			f.hitCharge = 1f;
			f.sprintHit = true;
			m.step(st, f, new Tally());
		}
		StepFacts f = facts(2.8f);
		f.onBody = true;
		f.hitsDealt = 1;
		f.hitCharge = 1f;
		f.sprintHit = !sweep;
		f.uppercut = uppercut;
		f.sweep = sweep;
		return m.step(st, f, t);
	}

	@Test
	void anUppercutPaysOnceTheComboIsThreeHitsLong() {
		StageSpec st = stage("Duel");
		assertEquals(3, st.reward.uppercutCombo);
		Tally t = new Tally();
		assertEquals(comboHit(st, 2, false, false, new Tally()), comboHit(st, 2, true, false, t), 1e-6f, "too early in the combo: nothing extra");
		assertEquals(st.reward.uppercut, comboHit(st, 3, true, false, t) - comboHit(st, 3, false, false, new Tally()), 1e-5f);
		assertEquals(st.reward.uppercut, comboHit(st, 5, true, false, t) - comboHit(st, 5, false, false, new Tally()), 1e-5f);
		assertEquals(3, t.uppercuts);
		assertTrue(stage("Duel").reward.uppercut > 0f && stage("Self-play").reward.uppercut > 0f);
		assertEquals(0f, stage("Combo escape").reward.uppercut, "jumping is still locked there");
		assertEquals(0f, stage("Crits").reward.uppercut, "and there the jump is for one thing only: the critical hit");

		// The jump it takes is not charged as a far jump once the combo is that long.
		RewardModel m = new RewardModel();
		for (int i = 0; i < 3; i++) {
			StepFacts hit = facts(2.8f);
			hit.hitsDealt = 1;
			hit.hitCharge = 1f;
			hit.sprintHit = true;
			m.step(st, hit, new Tally());
		}
		float from = st.reward.jumpWithin + 1f;
		StepFacts quiet = facts(2.8f);
		quiet.reachAtAction = from;
		StepFacts jump = facts(2.8f);
		jump.reachAtAction = from;
		jump.jumpKey = true;
		float a = m.step(st, quiet, new Tally());
		float b = m.step(st, jump, new Tally());
		assertEquals(a, b, 1e-6f);
	}

	@Test
	void aSweepIsAWeakHitAndCostsMostOfItsWorth() {
		StageSpec st = stage("Sprint-hit + W-tap");
		assertTrue(st.reward.sweep > 0.5f && st.reward.sweep < st.reward.hit, "punished, but still a hit");
		Tally t = new Tally();
		float sprint = comboHit(st, 0, false, false, new Tally());
		float sweep = comboHit(st, 0, false, true, t);
		assertEquals(st.reward.sweep + st.reward.sprintHit, sprint - sweep, 1e-5f);
		assertEquals(1, t.sweepHits);
		assertEquals(1f, t.sweepShare());
		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			assertTrue(stage(name).reward.sweep > 0f, name);
		}
		assertEquals(0f, stage("Hit timing").reward.sweep, "it has not been taught to sprint into its hits yet");
	}

	/** A hit landed after taking {@code taken} hits in a row; returns its reward. */
	private static float hitBack(StageSpec st, int taken, int quietTicksBefore, boolean sweep, boolean heldBack, Tally t) {
		RewardModel m = new RewardModel();
		for (int i = 0; i < taken; i++) {
			StepFacts hurt = facts(2.8f);
			hurt.hitsTaken = 1;
			m.step(st, hurt, t);
		}
		for (int i = 0; i < quietTicksBefore; i++) {
			m.step(st, facts(2.8f), t);
		}
		StepFacts f = facts(2.8f);
		f.onBody = true;
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = 1f;
		f.hitsDealt = 1;
		f.hitCharge = 1f;
		f.sweep = sweep;
		f.ideaOn = st.ideas;
		f.ideaHold = heldBack ? 3 : 0;
		return m.step(st, f, t);
	}

	@Test
	void hittingBackWhileBeingComboedPaysExtra() {
		StageSpec st = stage("Sprint-hit + W-tap");
		assertEquals(2, st.reward.comboBreakAfter);
		assertTrue(st.reward.comboBreak > 0f);
		float plain = hitBack(st, 0, 0, false, false, new Tally());
		// Every swing made in answer to a hit is paid a little, whatever the hit count; see the test of that.
		float answer = st.reward.answer;
		assertEquals(plain + answer, hitBack(st, 1, 5, false, false, new Tally()), 1e-5f, "one hit taken is a trade, not a combo");

		// Two hits in a row is a combo. The hit back is paid extra; only standing in range is not paid
		// any more from then on, since that is where the next hit lands.
		float broken = st.reward.comboBreak + answer - st.reward.range;
		Tally t = new Tally();
		assertEquals(broken, hitBack(st, 2, 5, false, false, t) - plain, 1e-5f);
		assertEquals(1, t.combosTaken);
		assertEquals(1, t.comboBreaks);
		assertEquals(1f, t.breakRate());
		assertEquals(broken, hitBack(st, 3, 5, false, false, new Tally()) - plain, 1e-5f, "a longer combo is still one combo");
		assertEquals(broken, hitBack(st, 6, 5, false, false, new Tally()) - plain, 1e-5f, "however long");

		// The hit that breaks a combo is never charged as a sweep, however slow it was.
		float sweptBreak = hitBack(st, 2, 5, true, false, new Tally());
		float sweptPlain = hitBack(st, 0, 0, true, false, new Tally());
		assertEquals(broken + st.reward.sweep, sweptBreak - sweptPlain, 1e-5f);

		// Left alone for long enough, the opponent's combo is over and a hit is just a hit again.
		Tally late = new Tally();
		assertEquals(plain, hitBack(st, 3, 40, false, false, late), 1e-5f);
		assertEquals(1, late.combosTaken);
		assertEquals(0, late.comboBreaks);
		assertEquals(0f, late.breakRate());

		// After the hit back the count starts over: the next hit is an ordinary one.
		RewardModel m = new RewardModel();
		Tally run = new Tally();
		for (int i = 0; i < 2; i++) {
			StepFacts hurt = facts(2.8f);
			hurt.hitsTaken = 1;
			m.step(st, hurt, run);
		}
		StepFacts first = facts(2.8f);
		first.hitsDealt = 1;
		first.hitCharge = 1f;
		StepFacts second = facts(2.8f);
		second.hitsDealt = 1;
		second.hitCharge = 1f;
		float a = m.step(st, first, run);
		float b = m.step(st, second, run);
		assertEquals(st.reward.comboBreak - st.reward.range - st.reward.combo, a - b, 1e-5f);
		assertEquals(1, run.comboBreaks);

		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			assertTrue(stage(name).reward.comboBreak > 0f, name);
		}
		assertEquals(0f, stage("Hit timing").reward.comboBreak, "the dummy there does not hit back");
	}

	@Test
	void aSwingWhileBeingComboedIsNeverTooEarlyForThePlan() {
		StageSpec st = stage("Combo escape");
		// No stage charges for an early swing any more; this is the rule for one that would.
		st.reward.mixTiming = 0.3f;
		float onTime = hitBack(st, 0, 0, false, false, new Tally());
		float early = hitBack(st, 0, 0, false, true, new Tally());
		assertEquals(st.reward.mixTiming, onTime - early, 1e-5f, "normally a swing the plan wanted held back is charged");

		Tally t = new Tally();
		float broken = hitBack(st, 2, 5, false, true, t);
		assertEquals(st.reward.comboBreak + st.reward.answer - st.reward.range, broken - onTime, 1e-5f, "while being comboed it is not");
		assertEquals(1, t.plannedClicks);
		assertEquals(1, t.timedClicks);
	}

	private static void takeHits(RewardModel m, StageSpec st, int hits, Tally t) {
		for (int i = 0; i < hits; i++) {
			StepFacts hurt = facts(2.8f);
			hurt.hitsTaken = 1;
			m.step(st, hurt, t);
		}
	}

	/** Nothing happens for some ticks with the opponent at {@code reach}; returns what those ticks paid. */
	private static float quiet(RewardModel m, StageSpec st, float reach, int ticks, Tally t) {
		float sum = 0f;
		for (int i = 0; i < ticks; i++) {
			sum += m.step(st, facts(reach), t);
		}
		return sum;
	}

	private static float chargedHit(RewardModel m, StageSpec st, Tally t) {
		StepFacts f = facts(2.8f);
		f.onBody = true;
		f.hitsDealt = 1;
		f.hitCharge = 1f;
		return m.step(st, f, t);
	}

	/**
	 * The other way out of a combo, for when the opponent is too far to swing at: let the knockback
	 * carry it clear, and come back with a hit.
	 */
	@Test
	void backingOutOfAComboPaysOnceItHasComeBackWithAHit() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardWeights w = st.reward;
		assertTrue(w.comboEscape > 0f && w.comboSpace > 0f);
		assertTrue(w.comboEscape > 26 * w.comboSpace * 2, "most of what backing out is worth is held back");
		assertEquals(2, w.comboEscapeAfter, "from the second hit in a row: that is when the opponent has a combo going");
		float far = w.comboSpaceFrom + 1f;
		assertEquals(0f, RewardModel.rangeScore(far, w), "no pay for fighting range out there");
		float idle26 = quiet(new RewardModel(), st, far, 26, new Tally());
		float idle30 = quiet(new RewardModel(), st, far, 30, new Tally());
		float plainHit = chargedHit(new RewardModel(), st, new Tally());

		// Two hits in, then out of reach until the opponent's combo has run out.
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		takeHits(m, st, 2, t);
		assertEquals(1, t.combosTaken);
		assertEquals(1, t.longCombosTaken);
		assertEquals(26 * w.comboSpace, quiet(m, st, far, 26, t) - idle26, 1e-5f, "only the small pay for being out of reach so far");
		assertEquals(0, t.comboEscapes);
		assertEquals(idle30, quiet(m, st, far, 30, t), 1e-5f, "and staying away after that earns nothing");
		// It pays off when it comes back and lands a charged hit.
		assertEquals(w.comboEscape, chargedHit(m, st, t) - plainHit, 1e-5f);
		assertEquals(1, t.comboEscapes);
		assertEquals(1f, t.escapeRate());
		// (The second tick on the opponent in a row also earns one more step of the hold pay.)
		assertEquals(w.combo + w.hold / 20f, chargedHit(m, st, t) - plainHit, 1e-5f, "once: the next hit is only the second of a combo");

		// Having fought back, it may back out again. (A few ticks first, so the pay for the footwork
		// after its own hits has run out and does not get mixed in.)
		quiet(m, st, far, 10, t);
		takeHits(m, st, 2, t);
		assertEquals(26 * w.comboSpace, quiet(m, st, far, 26, t) - idle26, 1e-5f);

		// One hit is a trade, not a combo: nothing about backing off is paid.
		RewardModel early = new RewardModel();
		Tally few = new Tally();
		takeHits(early, st, 1, few);
		assertEquals(idle30, quiet(early, st, far, 30, few), 1e-5f);
		assertEquals(plainHit, chargedHit(early, st, few), 1e-5f);
		assertEquals(0, few.comboEscapes);
		assertEquals(0, few.longCombosTaken);

		// A combo that ends while it stood where it was is not one it backed out of: the opponent
		// missed, or waited. Nor is one it was only out of reach of for a moment.
		for (int clearTicks : new int[] {0, 3}) {
			RewardModel stood = new RewardModel();
			Tally s = new Tally();
			takeHits(stood, st, 2, s);
			quiet(stood, st, far, clearTicks, s);
			quiet(stood, st, 2.8f, 30, s);
			assertEquals(plainHit, chargedHit(stood, st, s), 1e-5f, clearTicks + " ticks out of reach");
			assertEquals(0, s.comboEscapes);
		}

		// From the second hit on, standing at fighting range is no longer paid for: it is the opponent's range too.
		float inRange = quiet(new RewardModel(), st, 2.8f, 10, new Tally());
		RewardModel deep = new RewardModel();
		takeHits(deep, st, 2, new Tally());
		assertEquals(10 * w.range, inRange - quiet(deep, st, 2.8f, 10, new Tally()), 1e-5f);
		RewardModel shallow = new RewardModel();
		takeHits(shallow, st, 1, new Tally());
		assertEquals(inRange, quiet(shallow, st, 2.8f, 10, new Tally()), 1e-5f, "after one hit it still is");

		// Hitting back ends the combo the other way; that is a break, not an escape.
		Tally broke = new Tally();
		hitBack(st, 2, 5, false, false, broke);
		assertEquals(1, broke.comboBreaks);
		assertEquals(0, broke.comboEscapes);

		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			assertTrue(stage(name).reward.comboEscape > 0f, name);
			assertEquals(2, stage(name).reward.comboEscapeAfter, name);
		}
		assertEquals(0f, stage("Hit timing").reward.comboEscape);
	}

	@Test
	void backingOffBeingHitAgainAndBackingOffAgainNeverPays() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardWeights w = st.reward;
		float far = w.comboSpaceFrom + 1f;
		float idle10 = quiet(new RewardModel(), st, far, 10, new Tally());
		float idle26 = quiet(new RewardModel(), st, far, 26, new Tally());
		float idle30 = quiet(new RewardModel(), st, far, 30, new Tally());
		float plainHit = chargedHit(new RewardModel(), st, new Tally());
		float inRange = quiet(new RewardModel(), st, 2.8f, 10, new Tally());

		// It gets away, and is then comboed again before it has landed anything.
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		takeHits(m, st, 2, t);
		quiet(m, st, far, 30, t);
		takeHits(m, st, 2, t);
		assertEquals(2, t.longCombosTaken);
		// Backing off a second time earns nothing at all, however well it goes.
		assertEquals(idle30, quiet(m, st, far, 30, t), 1e-5f, "no pay for being out of reach a second time");
		// And what the first time had earned is gone: the hit that follows is just a hit.
		assertEquals(plainHit, chargedHit(m, st, t), 1e-5f);
		assertEquals(0, t.comboEscapes);
		assertEquals(0f, t.escapeRate());

		// While it cannot be paid for backing off, it is paid for standing and fighting as usual.
		RewardModel stay = new RewardModel();
		takeHits(stay, st, 2, new Tally());
		quiet(stay, st, far, 30, new Tally());
		takeHits(stay, st, 2, new Tally());
		assertEquals(inRange, quiet(stay, st, 2.8f, 10, new Tally()), 1e-5f);

		// Any number of rounds of it adds up to nothing but the hits taken.
		RewardModel farm = new RewardModel();
		Tally farmed = new Tally();
		takeHits(farm, st, 2, farmed);
		quiet(farm, st, far, 30, farmed);
		float later = 0f;
		for (int round = 0; round < 5; round++) {
			takeHits(farm, st, 2, farmed);
			later += quiet(farm, st, far, 30, farmed);
		}
		assertEquals(5 * idle30, later, 1e-4f);
		assertEquals(0, farmed.comboEscapes);

		// Hit once more on the way out is another matter: the combo is still on, and so is the way out
		// of it. A person's combo is longer than two hits, and it must be possible to leave it late.
		RewardModel caught = new RewardModel();
		Tally c = new Tally();
		takeHits(caught, st, 2, c);
		assertEquals(10 * w.comboSpace, quiet(caught, st, far, 10, c) - idle10, 1e-5f);
		takeHits(caught, st, 1, c);
		assertEquals(26 * w.comboSpace, quiet(caught, st, far, 26, c) - idle26, 1e-5f, "still paid for being out of reach");
		assertEquals(w.comboEscape, chargedHit(caught, st, c) - plainHit, 1e-5f, "and for coming back with a hit");
		assertEquals(1, c.comboEscapes);

		// A hit that is not fully charged does not cash it in.
		RewardModel poke = new RewardModel();
		Tally p = new Tally();
		takeHits(poke, st, 2, p);
		quiet(poke, st, far, 30, p);
		StepFacts weak = facts(2.8f);
		weak.onBody = true;
		weak.hitsDealt = 1;
		weak.hitCharge = 0.4f;
		StepFacts sameWeak = facts(2.8f);
		sameWeak.onBody = true;
		sameWeak.hitsDealt = 1;
		sameWeak.hitCharge = 0.4f;
		assertEquals(new RewardModel().step(st, sameWeak, new Tally()), poke.step(st, weak, p), 1e-5f);
		assertEquals(0, p.comboEscapes);
	}

	private static StepFacts ready(float reach, boolean serverSprinting) {
		StepFacts f = facts(reach);
		f.serverSprinting = serverSprinting;
		return f;
	}

	@Test
	void havingTheSprintReadyUpCloseIsPaidForAFewTicksPerHit() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardWeights w = st.reward;
		assertTrue(w.primed > 0f);
		float close = 1.2f;

		// Sword charged, opponent in its face, and the server has it sprinting: the next hit will knock back.
		RewardModel m = new RewardModel();
		RewardModel plain = new RewardModel();
		Tally t = new Tally();
		for (int tick = 0; tick < 6; tick++) {
			assertEquals(w.primed, m.step(st, ready(close, true), t) - plain.step(st, ready(close, false), new Tally()), 1e-6f, "tick " + tick);
		}
		// Standing ready is not something to collect on: after a few ticks it earns nothing more.
		for (int tick = 0; tick < 20; tick++) {
			assertEquals(0f, m.step(st, ready(close, true), t) - plain.step(st, ready(close, false), new Tally()), 1e-6f);
		}
		// Each landed hit buys the next few ticks.
		chargedHit(m, st, t);
		chargedHit(plain, st, new Tally());
		assertEquals(w.primed, m.step(st, ready(close, true), t) - plain.step(st, ready(close, false), new Tally()), 1e-6f);

		// Not while the sword is still recharging, and not with the opponent far off.
		RewardModel other = new RewardModel();
		RewardModel base = new RewardModel();
		StepFacts recharging = ready(close, true);
		recharging.charge = 0.5f;
		StepFacts same = ready(close, false);
		same.charge = 0.5f;
		assertEquals(0f, other.step(st, recharging, new Tally()) - base.step(st, same, new Tally()), 1e-6f);
		assertEquals(0f, other.step(st, ready(w.primedWithin + 1f, true), new Tally()) - base.step(st, ready(w.primedWithin + 1f, false), new Tally()), 1e-6f);

		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			assertTrue(stage(name).reward.primed > 0f, name);
		}
		assertEquals(0f, stage("Hit timing").reward.primed);
	}

	@Test
	void hitsFromVeryCloseAreCountedAndTheSprintStageAsksForSprintBehindThem() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		for (int i = 0; i < 4; i++) {
			StepFacts f = facts(1.5f);
			f.hitsDealt = 1;
			f.hitCharge = 1f;
			f.reachAtAction = i < 3 ? 1.5f : 2.9f;
			f.sprintHit = i == 0 || i == 3;
			m.step(st, f, t);
		}
		assertEquals(3, t.closeHits, "the one made from the edge of reach is not a close one");
		assertEquals(1, t.closeSprintHits);
		assertEquals(1f / 3f, t.closeSprint(), 1e-6f);

		// The stage is not passed while its close hits have no sprint behind them.
		Curriculum c = new Curriculum(Stages.defaults());
		c.setStage(5);
		assertEquals("Sprint-hit + W-tap", c.current().name);
		assertTrue(c.current().promote.minCloseSprint > 0f);
		Tally good = new Tally();
		good.steps = 70_000;
		good.mixTicks = 10_000;
		good.mixFollowed = 7_000;
		good.hitsDealt = 3000;
		good.sprintHits = 2400;
		good.hitsTaken = 1000;
		good.combos = 1000;
		good.comboHits = 3000;
		good.tapWindows = 3000;
		good.tapsDone = 2400;
		good.closeHits = 400;
		good.closeSprintHits = 40;
		c.record(good);
		assertTrue(c.promotionBlocker().startsWith("sprint hits from close 10%"), c.promotionBlocker());

		c.setStage(5);
		good.closeSprintHits = 300;
		c.record(good);
		assertNull(c.promotionBlocker());

		// A fighter the opponent hardly ever gets that close to is not held to it.
		c.setStage(5);
		good.closeHits = 5;
		good.closeSprintHits = 0;
		c.record(good);
		assertNull(c.promotionBlocker());
	}

	@Test
	void fromTheSprintStageOnTheBotSometimesKeepsPushingIn() {
		for (String name : new String[] {"Sprint-hit + W-tap", "Combo escape", "Crits"}) {
			BotProfile bot = stage(name).bot;
			assertTrue(bot.rushChance > 0.2f && bot.rushChance < 0.6f, name);
			assertEquals(bot.rushChance, bot.copy().rushChance, name);
			assertEquals(bot.rushChance, bot.atSkill(0.7f).rushChance, name);
		}
		assertEquals(0f, stage("Hit timing").bot.rushChance);
	}

	private static Curriculum.SelfPlay fights(Curriculum c, int wins, int losses, int oldWins, int oldLosses) {
		Tally t = new Tally();
		t.steps = 1800;
		t.wins = wins;
		t.losses = losses;
		t.oldWins = oldWins;
		t.oldLosses = oldLosses;
		c.record(t);
		return c.judgeSelfPlay(true);
	}

	@Test
	void toTakeTheCopysPlaceItMustAlsoHoldItsOwnAgainstTheCopiesBeforeIt() {
		// Beating the newest copy with something the older ones would have punished only sends the
		// fighters round in a circle, so that alone is not enough.
		Curriculum c = new Curriculum(Stages.defaults());
		c.setStage(9);
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(c, 16, 4, 2, 8), "ahead of the newest, but losing to the older ones");
		assertEquals(1, c.generation());
		assertEquals(10, c.oldWins() + c.oldLosses());

		// Too few fights against the older ones to tell: it waits for more.
		Curriculum few = new Curriculum(Stages.defaults());
		few.setStage(9);
		assertEquals(Curriculum.SelfPlay.UNDECIDED, fights(few, 16, 4, 3, 0));
		assertEquals(Curriculum.SelfPlay.CHALLENGER_WINS, fights(few, 3, 1, 3, 2), "eight of them now, six won");
		assertEquals(2, few.generation());
		assertEquals(0, few.oldWins() + few.oldLosses());

		// Ahead of both: it takes the copy's place.
		Curriculum both = new Curriculum(Stages.defaults());
		both.setStage(9);
		assertEquals(Curriculum.SelfPlay.CHALLENGER_WINS, fights(both, 15, 5, 5, 4));

		// With no older copies yet (the very first generation) there is nothing more to ask.
		Curriculum first = new Curriculum(Stages.defaults());
		first.setStage(9);
		Tally t = new Tally();
		t.steps = 1800;
		t.wins = 15;
		t.losses = 5;
		first.record(t);
		assertEquals(Curriculum.SelfPlay.CHALLENGER_WINS, first.judgeSelfPlay(false));
	}

	@Test
	void theFightsAgainstOlderCopiesSurviveSaveAndLoad(@TempDir Path dir) throws Exception {
		Curriculum a = new Curriculum(Stages.defaults());
		a.setStage(9);
		fights(a, 5, 5, 3, 1);
		Path file = dir.resolve("curriculum.properties");
		a.save(file);
		Curriculum b = new Curriculum(Stages.defaults());
		b.load(file);
		assertEquals(3, b.oldWins());
		assertEquals(1, b.oldLosses());
	}

	@Test
	void fightingItselfItStillRehearsesTheScriptedFights() {
		Random rng = new Random(11);
		for (int stageIndex = 8; stageIndex <= 9; stageIndex++) {
			Curriculum c = new Curriculum(Stages.defaults());
			c.setStage(stageIndex);
			int[] picked = new int[10];
			for (int i = 0; i < 4000; i++) {
				picked[c.pickEpisodeStage(rng, 0.12f)]++;
			}
			int rehearsed = 4000 - picked[stageIndex];
			assertTrue(rehearsed > 1050 && rehearsed < 1350, "about three fights in ten, got " + rehearsed);
			// Only the fights against the scripted bots: the one that rushes in, the crit jumper, the W-tapper.
			assertEquals(rehearsed, picked[5] + picked[6] + picked[7]);
			assertTrue(picked[5] > 130 && picked[6] > 130, picked[5] + ", " + picked[6]);
			// The crit jumper most of all, about one fight in five: a copy of itself crits no more than it does.
			assertTrue(picked[7] > 650 && picked[7] < 950, "" + picked[7]);
		}
		// Before that, rehearsal is as it was: any earlier stage, as often as the config says.
		Curriculum crits = new Curriculum(Stages.defaults());
		crits.setStage(7);
		int earlier = 0;
		int drills = 0;
		for (int i = 0; i < 4000; i++) {
			int pick = crits.pickEpisodeStage(rng, 0.12f);
			if (pick < 7) {
				earlier++;
			}
			if (pick < 5) {
				drills++;
			}
		}
		assertTrue(earlier > 380 && earlier < 580, "" + earlier);
		assertTrue(drills > 0);
	}

	@Test
	void standingOffAndRunningOutOfTimeCostMoreThanTheyPay() {
		for (String name : new String[] {"Duel", "Self-play"}) {
			StageSpec st = stage(name);
			RewardWeights w = st.reward;
			assertTrue(w.stall > 0f && w.draw > 0f, name);
			// Circling out of reach with the crosshair on the opponent: what aiming pays is less than
			// what standing off costs, once the grace period is over.
			RewardModel m = new RewardModel();
			Tally t = new Tally();
			float early = 0f;
			for (int tick = 0; tick < w.stallAfterTicks; tick++) {
				StepFacts f = facts(5f);
				f.onBody = true;
				early += m.step(st, f, t);
			}
			float late = 0f;
			for (int tick = 0; tick < 100; tick++) {
				StepFacts f = facts(5f);
				f.onBody = true;
				late += m.step(st, f, t);
			}
			assertTrue(early >= 0f, name + ": closing in at the start of a fight is not punished");
			assertTrue(late < 0f, name + ": standing off pays " + late + " per hundred ticks");

			// Any hit, by either side, is a fight again.
			StepFacts hurt = facts(5f);
			hurt.hitsTaken = 1;
			m.step(st, hurt, t);
			StepFacts after = facts(5f);
			after.onBody = true;
			assertTrue(m.step(st, after, t) > 0f, name);

			// A fight that runs out of time costs both of them.
			RewardModel fresh = new RewardModel();
			StepFacts quiet = facts(5f);
			StepFacts over = facts(5f);
			over.timedOut = true;
			Tally d = new Tally();
			float a = new RewardModel().step(st, quiet, new Tally());
			float b = fresh.step(st, over, d);
			assertEquals(w.draw, a - b, 1e-5f, name);
			assertEquals(1, d.draws);
			assertTrue(w.draw > w.ko * 0.5f && w.draw < w.ko, name + ": worse than half a loss, better than a loss");
		}
		Tally t = new Tally();
		t.wins = 6;
		t.losses = 2;
		t.draws = 2;
		assertEquals(0.2f, t.drawRate(), 1e-6f);
		assertEquals(0.75f, t.winRate(), 1e-6f, "draws do not count as fights won or lost");
		// Not engaging costs in every stage that is a fight, from the sprint-hit stage on; before that there is nothing to engage.
		for (String name : new String[] {"Sprint-hit + W-tap", "Combo escape", "Crits", "Duel", "Self-play"}) {
			assertTrue(stage(name).reward.stall > 0f, name);
			// And being hit costs at least what a full hit earns, but not more than a full sprint hit in a combo does.
			RewardWeights w = stage(name).reward;
			assertTrue(w.hitTaken >= w.hit, name + ": a hit taken costs " + w.hitTaken + ", a hit given earns " + w.hit);
			assertTrue(w.hitTaken <= w.hit + w.sprintHit + 4 * w.combo + 0.01f, name);
			// The second hit in a row and each one after it costs more still.
			assertTrue(w.comboedHit >= 0.4f * w.hitTaken, name);
		}
		assertEquals(0f, stage("Hit timing").reward.stall);
	}

	@Test
	void aComboDrillStartsSomeHitsIntoTheOpponentsCombo() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardWeights w = st.reward;
		float plain = hitBack(st, 0, 0, false, false, new Tally());

		// Two hits behind from the first step: a hit back is a combo broken, as in any fight.
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		m.startComboed(st, 2, t);
		assertEquals(1, t.combosTaken);
		assertEquals(1, t.longCombosTaken);
		assertEquals(0, t.hitsTaken, "hits it could do nothing about are not charged");
		assertEquals(w.comboBreak + w.answer - w.range, drillHitBack(m, st, t) - plain, 1e-5f);
		assertEquals(1, t.comboBreaks);
		assertEquals(1f, t.breakRate());

		// One hit behind is not a combo yet; the next hit taken makes it one.
		RewardModel one = new RewardModel();
		Tally single = new Tally();
		one.startComboed(st, 1, single);
		assertEquals(0, single.combosTaken);
		takeHits(one, st, 1, single);
		assertEquals(1, single.combosTaken);
		assertEquals(1, single.hitsTaken);

		// Backing out of it is paid for too, by the same rules as ever: a little for being out of
		// reach, the rest once it has come back with a charged hit.
		float far = w.comboSpaceFrom + 1f;
		float idle26 = quiet(new RewardModel(), st, far, 26, new Tally());
		float plainHit = chargedHit(new RewardModel(), st, new Tally());
		RewardModel deep = new RewardModel();
		Tally run = new Tally();
		deep.startComboed(st, 4, run);
		assertEquals(1, run.combosTaken);
		assertEquals(1, run.longCombosTaken);
		assertEquals(26 * w.comboSpace, quiet(deep, st, far, 26, run) - idle26, 1e-5f);
		assertEquals(w.comboEscape, chargedHit(deep, st, run) - plainHit, 1e-5f);
		assertEquals(1, run.comboEscapes);

		// And being hit once more on the way out does not close the way out.
		RewardModel caught = new RewardModel();
		Tally again = new Tally();
		caught.startComboed(st, 4, again);
		takeHits(caught, st, 1, again);
		quiet(caught, st, far, 30, again);
		assertEquals(w.comboEscape, chargedHit(caught, st, again) - plainHit, 1e-5f);
		assertEquals(1, again.comboEscapes);

		// Nothing landed before it got its keys: an ordinary start.
		RewardModel none = new RewardModel();
		Tally clean = new Tally();
		none.startComboed(st, 0, clean);
		assertEquals(plain, drillHitBack(none, st, clean), 1e-5f);
		assertEquals(0, clean.combosTaken);

		// A drill that was set up and never played leaves nothing behind for the next fight.
		RewardModel dropped = new RewardModel();
		dropped.startComboed(st, 3, new Tally());
		dropped.reset();
		Tally after = new Tally();
		assertEquals(plain, drillHitBack(dropped, st, after), 1e-5f);
		assertEquals(0, after.comboBreaks);
	}

	/** A batch of fighting: so many hits, so many of them sprint hits, made in so many combos. */
	private static Tally comboBatch(int hits, int sprintHits, int combos) {
		Tally t = new Tally();
		t.steps = 1800;
		t.hitsDealt = hits;
		t.sprintHits = sprintHits;
		t.combos = combos;
		t.comboHits = hits;
		return t;
	}

	private static float drillHitBack(RewardModel m, StageSpec st, Tally t) {
		StepFacts f = facts(2.8f);
		f.onBody = true;
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = 1f;
		f.hitsDealt = 1;
		f.hitCharge = 1f;
		return m.step(st, f, t);
	}

	@Test
	void fromTheSprintStageOnPartOfTheFightsAreComboDrillsAgainstItself() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < stages.size(); i++) {
			StageSpec st = stages.get(i);
			if (i < 5) {
				assertEquals(0f, st.drillShare, st.name + ": nothing hits back yet, so there is no combo to get out of");
				continue;
			}
			// Half of the fights at most, except where getting out of combos is the whole lesson.
			assertTrue(st.drillShare > 0f && st.drillShare <= ("Combo escape".equals(st.name) ? 0.7f : 0.5f), st.name);
			assertTrue(st.drillMinHits >= 1 && st.drillMinHits <= st.drillHits, st.name);
			assertTrue(st.drillTicks >= 100 && st.drillTicks < st.episodeTicks, st.name);
			assertTrue(st.drillHits >= st.reward.comboEscapeAfter, st.name + ": some drills must start deep enough to run from");
			assertTrue(st.reward.comboBreak > 0f && st.reward.comboEscape > 0f, st.name);

			// The drill's opponent is a copy of itself within a step of hitting it; the rest of the
			// stage's rules are kept, and the stage's own opponent is left as it was.
			String movement = st.bot.movement;
			float distance = st.bot.minDistance;
			BotProfile drill = st.bot.drill();
			assertEquals(BotProfile.SELF, drill.movement);
			assertTrue(drill.attacks);
			assertTrue(drill.maxDistance <= 3.5f && drill.minDistance >= 2f);
			assertEquals(0, drill.maxPillar);
			assertEquals(0, drill.hopIntervalTicks);
			assertEquals(st.bot.agentImmortal, drill.agentImmortal);
			assertEquals(st.bot.botImmortal, drill.botImmortal);
			assertEquals(movement, st.bot.movement);
			assertEquals(distance, st.bot.minDistance);
		}
		// A fighter new to the sprint-hit stage cannot combo yet, and neither can its copy: the drills
		// wait until its own fights against the scripted bot show that it does.
		StageSpec sprintStage = stage("Sprint-hit + W-tap");
		assertTrue(sprintStage.drillFromSprintHits > 0f && sprintStage.drillFromCombo >= 2f);
		assertTrue(sprintStage.drillFromSprintHits <= sprintStage.promote.minSprintHits, "they must be running before the stage is passed");
		assertTrue(sprintStage.drillFromCombo <= sprintStage.promote.minCombo);
		Curriculum c = new Curriculum(stages);
		c.setStage(4);
		assertFalse(c.drillsReady());
		assertNull(c.drillBlocker(), "a stage without drills has nothing to wait for");
		c.setStage(5);
		assertFalse(c.drillsReady(), "nothing is known about it yet");
		assertNotNull(c.drillBlocker());
		c.record(comboBatch(40, 4, 40));
		assertFalse(c.drillsReady(), "plain hits, one at a time: " + c.drillBlocker());
		assertTrue(c.drillBlocker().startsWith("sprint hits"), c.drillBlocker());
		c.setStage(5);
		c.record(comboBatch(40, 30, 40));
		assertFalse(c.drillsReady(), "sprint hits, but no two in a row: " + c.drillBlocker());
		assertTrue(c.drillBlocker().startsWith("combo"), c.drillBlocker());
		c.setStage(5);
		c.record(comboBatch(10, 9, 3));
		assertFalse(c.drillsReady(), "too few hits to tell");
		c.record(comboBatch(40, 30, 13));
		assertTrue(c.drillsReady(), c.drillBlocker());
		assertNull(c.drillBlocker());
		// If its own combos fall apart again, the drills stop until they are back.
		for (int i = 0; i < 20; i++) {
			c.record(comboBatch(40, 4, 40));
		}
		assertFalse(c.drillsReady());
		// Later stages are only reached by a fighter that combos, so there they run from the first fight.
		for (int i = 6; i < stages.size(); i++) {
			c.setStage(i);
			assertTrue(c.drillsReady(), stages.get(i).name);
		}

		// Where getting out of combos is the lesson, each drill starts inside a combo. There are fewer of
		// them than fights against the scripted bot: a copy can be traded hits with, and that is not the lesson.
		StageSpec escape = stage("Combo escape");
		assertTrue(escape.drillShare < 0.5f);
		assertEquals(escape.reward.comboEscapeAfter, escape.drillMinHits);
		assertEquals(1, stage("Sprint-hit + W-tap").drillMinHits);
		// Against the scripted bots it is seldom comboed, so it drills more there than against itself.
		assertTrue(stage("Sprint-hit + W-tap").drillShare > stage("Duel").drillShare);
		assertEquals(stage("Duel").drillShare, stage("Self-play").drillShare);

		// Of a drill only what it says about being comboed is added to the stage's numbers.
		Tally fights = new Tally();
		fights.hitsDealt = 30;
		fights.hitsTaken = 10;
		fights.wins = 4;
		fights.combosTaken = 1;
		Tally drills = new Tally();
		drills.steps = 400;
		drills.hitsDealt = 5;
		drills.hitsTaken = 12;
		drills.losses = 2;
		drills.combosTaken = 5;
		drills.comboBreaks = 3;
		drills.longCombosTaken = 2;
		drills.comboEscapes = 1;
		drills.hitsToAnswer = 8;
		drills.answers = 6;
		drills.comboedHits = 8;
		drills.comboedHitsFollowed = 2;
		drills.backTicks = 40;
		drills.backHeld = 30;
		fights.addCombosTaken(drills);
		assertEquals(0.75f, fights.outRate(), 1e-6f);
		assertEquals(0.75f, fights.backRate(), 1e-6f);
		assertEquals(0.75f, fights.answerRate(), 1e-6f);
		assertEquals(6, fights.combosTaken);
		assertEquals(0.5f, fights.breakRate(), 1e-6f);
		assertEquals(0.5f, fights.escapeRate(), 1e-6f);
		assertEquals(3f, fights.hitRatio(), 1e-6f, "the drills, started from behind, do not count against the stage");
		assertEquals(10, fights.hitsTaken);
		assertEquals(0, fights.losses);
		assertEquals(0, fights.steps);
	}

	/** A swing with nothing under the crosshair, made with the opponent at {@code reach}. */
	private static float airSwing(RewardModel m, StageSpec st, float reach, float chargeAtClick, Tally t) {
		StepFacts f = facts(reach);
		f.reachAtAction = reach;
		f.clicked = true;
		f.clickOnTarget = false;
		f.chargeAtClick = chargeAtClick;
		return m.step(st, f, t);
	}

	@Test
	void everyHitTakenIsToBeAnsweredWithAChargedSwingLandedOrNot() {
		StageSpec st = stage("Sprint-hit + W-tap");
		RewardWeights w = st.reward;
		assertTrue(w.answer > 0f);
		assertTrue(w.answer * 4 < w.hitTaken, "being hit must never be worth it for the swing after it");
		assertTrue(w.answerWithin > 3f, "a swing that falls short still counts");
		float miss = airSwing(new RewardModel(), st, 2.8f, 1f, new Tally());
		float earlyMiss = airSwing(new RewardModel(), st, 2.8f, 0.2f, new Tally());
		assertEquals(w.spam, miss - earlyMiss, 1e-5f, "normally a swing before the weapon is back costs");

		// Hit once, it swings at the air: that is paid, once.
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		takeHits(m, st, 1, t);
		assertEquals(1, t.hitsToAnswer);
		assertEquals(w.answer, airSwing(m, st, 2.8f, 1f, t) - miss, 1e-5f);
		assertEquals(1, t.answers);
		assertEquals(1f, t.answerRate());
		assertEquals(miss, airSwing(m, st, 2.8f, 1f, t), 1e-5f, "one swing per hit taken, however many it makes");
		// Being under attack excuses nothing: a swing before the weapon is back costs what it always costs.
		assertEquals(earlyMiss, airSwing(m, st, 2.8f, 0.2f, t), 1e-5f);
		assertEquals(1, t.spamClicks);

		// And such a tap answers nothing. The answer is the first swing made with the weapon charged.
		RewardModel panic = new RewardModel();
		Tally taps = new Tally();
		takeHits(panic, st, 1, taps);
		assertEquals(earlyMiss, airSwing(panic, st, 2.8f, 0.2f, taps), 1e-5f);
		assertEquals(earlyMiss, airSwing(panic, st, 2.8f, 0.5f, taps), 1e-5f);
		assertEquals(0, taps.answers);
		assertEquals(2, taps.spamClicks);
		assertEquals(w.answer, airSwing(panic, st, 2.8f, 1f, taps) - miss, 1e-5f);
		assertEquals(1, taps.answers);

		// A swing made on the very tick the hit arrives was not made in answer to it; the next one is.
		RewardModel same = new RewardModel();
		Tally sameTick = new Tally();
		StepFacts both = facts(2.8f);
		both.hitsTaken = 1;
		both.clicked = true;
		both.chargeAtClick = 1f;
		same.step(st, both, sameTick);
		assertEquals(0, sameTick.answers);
		assertEquals(w.answer, airSwing(same, st, 2.8f, 1f, sameTick) - miss, 1e-5f);

		// With the opponent far off there is nothing to answer at; once it is near again, there is.
		RewardModel far = new RewardModel();
		Tally away = new Tally();
		takeHits(far, st, 1, away);
		float missFar = airSwing(new RewardModel(), st, 6f, 1f, new Tally());
		assertEquals(missFar, airSwing(far, st, 6f, 1f, away), 1e-5f);
		assertEquals(0, away.answers);
		// Near means in reach, give or take: a swing at an opponent a block out of reach answers nothing.
		assertTrue(w.answerWithin < 3.5f);
		assertEquals(airSwing(new RewardModel(), st, 4f, 1f, new Tally()), airSwing(far, st, 4f, 1f, away), 1e-5f);
		assertEquals(0, away.answers);
		assertEquals(w.answer, airSwing(far, st, 3.1f, 1f, away) - airSwing(new RewardModel(), st, 3.1f, 1f, new Tally()), 1e-5f);

		// It has a second for it. After that the hit went unanswered, and swinging is charged as ever.
		RewardModel slow = new RewardModel();
		Tally late = new Tally();
		takeHits(slow, st, 1, late);
		quiet(slow, st, 2.8f, w.answerWindowTicks + 1, late);
		assertEquals(earlyMiss, airSwing(slow, st, 2.8f, 0.2f, late), 1e-5f);
		assertEquals(0, late.answers);
		assertEquals(1, late.hitsToAnswer);
		assertEquals(0f, late.answerRate());
		assertEquals(1, late.spamClicks);

		// Each new hit is a new one to answer.
		RewardModel again = new RewardModel();
		Tally two = new Tally();
		takeHits(again, st, 1, two);
		airSwing(again, st, 2.8f, 1f, two);
		takeHits(again, st, 1, two);
		// (Two hits in a row: standing in range is no longer paid, which is the only other difference.)
		assertEquals(w.answer - w.range, airSwing(again, st, 2.8f, 1f, two) - miss, 1e-5f);
		assertEquals(2, two.answers);
		assertEquals(2, two.hitsToAnswer);

		// A combo drill starts with a hit just taken: that one is to be answered too.
		RewardModel drill = new RewardModel();
		Tally drilled = new Tally();
		drill.startComboed(st, 3, drilled);
		assertEquals(1, drilled.hitsToAnswer);
		assertEquals(w.answer - w.range, airSwing(drill, st, 2.8f, 1f, drilled) - miss, 1e-5f);

		// Nothing is carried into the next fight.
		RewardModel ended = new RewardModel();
		takeHits(ended, st, 1, new Tally());
		ended.endEpisode(new Tally(), false, false);
		assertEquals(miss, airSwing(ended, st, 2.8f, 1f, new Tally()), 1e-5f);

		for (int i = 0; i < Stages.defaults().size(); i++) {
			RewardWeights r = Stages.defaults().get(i).reward;
			if (i < 5) {
				assertEquals(0f, r.answer, "nothing hits it there");
			} else {
				assertTrue(r.answer > 0f, Stages.defaults().get(i).name);
				// A hit costs its charge, or else the health it takes (a sword through diamond armour: nearly two points).
				assertTrue(r.answer * 4 < r.hitTaken + r.damage * 1.9f, Stages.defaults().get(i).name + ": never worth being hit for");
			}
		}
	}

	@Test
	void justHitThePlanMayBeBrokenAndTheAimCountsAgain() {
		StageSpec st = stage("Combo escape");
		RewardWeights w = st.reward;
		// A swing the plan wanted held back: charged normally, free in the second after a hit taken.
		StepFacts early = facts(2.8f);
		early.clicked = true;
		early.chargeAtClick = 1f;
		early.ideaOn = true;
		early.ideaHold = 3;
		float charged = new RewardModel().step(st, early, new Tally());
		StepFacts onTime = facts(2.8f);
		onTime.clicked = true;
		onTime.chargeAtClick = 1f;
		onTime.ideaOn = true;
		float free = new RewardModel().step(st, onTime, new Tally());
		assertEquals(w.mixTiming, free - charged, 1e-5f);
		RewardModel hit = new RewardModel();
		Tally t = new Tally();
		takeHits(hit, st, 1, t);
		assertEquals(w.answer, hit.step(st, early, t) - free, 1e-5f, "one hit taken is enough to swing when it likes");
		assertEquals(1, t.timedClicks);

		// While the sword recharges the crosshair is its own business; just hit, it is to be back on the opponent.
		StepFacts on = facts(2.8f);
		on.charge = 0.2f;
		on.onBody = true;
		StepFacts off = facts(2.8f);
		off.charge = 0.2f;
		off.missDeg = 20f;
		assertEquals(new RewardModel().step(st, on, new Tally()), new RewardModel().step(st, off, new Tally()), 1e-5f);
		RewardModel a = new RewardModel();
		takeHits(a, st, 1, new Tally());
		RewardModel b = new RewardModel();
		takeHits(b, st, 1, new Tally());
		float onIt = a.step(st, on, new Tally());
		float offIt = b.step(st, off, new Tally());
		assertTrue(onIt - offIt >= w.onTarget, "on the opponent pays again: " + onIt + " vs " + offIt);
	}

	/** One tick with the opponent at {@code reach} and the forward key as given; returns its reward. */
	private static float walk(RewardModel m, StageSpec st, float reach, int forwardAxis, Tally t) {
		StepFacts f = facts(reach);
		// A sword still recharging, so that nothing about being ready to hit gets mixed in.
		f.charge = 0.3f;
		f.forwardAxis = forwardAxis;
		return m.step(st, f, t);
	}

	/**
	 * What stage 7 teaches: once the opponent has a combo going, S. It is paid in the ticks right
	 * after each hit of the combo, which are the ones that decide where the next swing finds it.
	 */
	@Test
	void holdingSRightAfterAHitOfAComboIsPaidFor() {
		StageSpec st = stage("Combo escape");
		RewardWeights w = st.reward;
		assertTrue(w.comboBack > 0f);
		assertTrue(st.goal.contains("hold S"), st.goal);
		assertTrue(w.comboBack * w.comboBackTicks < w.hitTaken, "a hit's worth of it stays below what the hit cost: being hit is never a way to collect");
		assertTrue(w.comboEscape > w.comboBreak, "all the way out and back in with a hit is worth more than hitting back from inside");
		float reach = 2.8f;

		// One hit is not a combo: S is worth what any other key is.
		RewardModel one = new RewardModel();
		RewardModel oneToo = new RewardModel();
		takeHits(one, st, 1, new Tally());
		takeHits(oneToo, st, 1, new Tally());
		assertEquals(walk(oneToo, st, reach, 0, new Tally()), walk(one, st, reach, -1, new Tally()), 1e-6f);

		// From the second hit in a row: every tick of S pays, for as long as the opponent is within reach.
		RewardModel back = new RewardModel();
		RewardModel stand = new RewardModel();
		RewardModel forward = new RewardModel();
		Tally t = new Tally();
		takeHits(back, st, 2, t);
		takeHits(stand, st, 2, new Tally());
		takeHits(forward, st, 2, new Tally());
		for (int tick = 0; tick < w.comboBackTicks; tick++) {
			float standing = walk(stand, st, reach, 0, new Tally());
			assertEquals(w.comboBack, walk(back, st, reach, -1, t) - standing, 1e-6f, "tick " + tick);
			assertEquals(standing, walk(forward, st, reach, 1, new Tally()), 1e-6f, "W earns what standing does: nothing");
		}
		assertEquals(w.comboBack, walk(back, st, reach, -1, t) - walk(stand, st, reach, 0, new Tally()), 1e-6f,
				"and on, until it is out of reach: the opponent may be coming after it");
		assertEquals(w.comboBackTicks + 1, t.backTicks);
		assertEquals(1f, t.backRate(), 1e-6f);

		// Each further hit of the combo opens it again.
		takeHits(back, st, 1, t);
		takeHits(stand, st, 1, new Tally());
		assertEquals(w.comboBack, walk(back, st, reach, -1, t) - walk(stand, st, reach, 0, new Tally()), 1e-6f);

		// Once it is out of the opponent's reach, S is not paid but charged for, and those ticks are not counted.
		assertEquals(3f, w.comboBackWithin);
		assertTrue(w.comboBackFar > 0f);
		RewardModel gone = new RewardModel();
		RewardModel goneToo = new RewardModel();
		Tally far = new Tally();
		takeHits(gone, st, 2, far);
		takeHits(goneToo, st, 2, new Tally());
		assertEquals(walk(goneToo, st, w.comboBackWithin + 1f, 0, new Tally()) - w.comboBackFar, walk(gone, st, w.comboBackWithin + 1f, -1, far), 1e-6f);
		assertEquals(0, far.backTicks);

		// The share of those ticks S was held is what the report shows and the stage is passed on.
		RewardModel half = new RewardModel();
		Tally h = new Tally();
		takeHits(half, st, 2, h);
		for (int tick = 0; tick < 12; tick++) {
			walk(half, st, reach, tick < 3 ? -1 : 1, h);
		}
		assertEquals(0.25f, h.backRate(), 1e-6f);

		// A hit of its own ends the opponent's combo, and with it the pay for backing off.
		RewardModel broke = new RewardModel();
		RewardModel brokeToo = new RewardModel();
		takeHits(broke, st, 2, new Tally());
		takeHits(brokeToo, st, 2, new Tally());
		chargedHit(broke, st, new Tally());
		chargedHit(brokeToo, st, new Tally());
		assertEquals(walk(brokeToo, st, reach, 0, new Tally()), walk(broke, st, reach, -1, new Tally()) - w.tap * 0.5f, 1e-6f,
				"only what letting go of W after its own hit pays, as ever");

		// Before this stage S is not taught. After it, it stays paid: as much in the crit stage, where
		// little else keeps it up (0.14.0: paid a little there, it was gone by the duel), less from the duel on.
		assertEquals(0f, stage("Sprint-hit + W-tap").reward.comboBack);
		// (Up to the first evening against people it stayed paid after it. Backing off is what a player
		// who follows with a running jump wants of it; from the crit stage on it is not paid.)
		for (String name : new String[] {"Crits", "Duel", "Self-play"}) {
			assertEquals(0f, stage(name).reward.comboBack, name);
			assertTrue(stage(name).reward.comboThird > 0f && stage(name).reward.comboedHit > 0f, name + ": a combo still costs");
		}
		// And the third hit of a combo costs once more, wherever combos are met: out by the second is the mark.
		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			assertTrue(stage(name).reward.comboThird > 0f, name);
		}
		assertEquals(0f, stage("Sprint-hit + W-tap").reward.comboThird);
	}

	/** In those same ticks, running back at the opponent with the sword ready is not paid: that is the next hit. */
	@Test
	void comingStraightBackInRightAfterAComboHitIsNotPaid() {
		StageSpec st = stage("Combo escape");
		RewardWeights w = st.reward;
		assertTrue(w.reengage > 0f);
		float out = w.rangeMax + 1f;
		StepFacts in = facts(out);
		in.forwardAxis = 1;
		in.sprintKey = true;
		assertEquals(w.reengage, new RewardModel().step(st, in, new Tally()) - new RewardModel().step(st, facts(out), new Tally()), 1e-6f, "normally it is");

		RewardModel m = new RewardModel();
		RewardModel same = new RewardModel();
		takeHits(m, st, 2, new Tally());
		takeHits(same, st, 2, new Tally());
		for (int tick = 0; tick < w.comboBackTicks; tick++) {
			StepFacts f = facts(out);
			f.forwardAxis = 1;
			f.sprintKey = true;
			assertEquals(0f, m.step(st, f, new Tally()) - same.step(st, facts(out), new Tally()), 1e-6f, "tick " + tick);
		}
		// Once it has had the time to get clear, coming back in is what it is paid for again.
		StepFacts f = facts(out);
		f.forwardAxis = 1;
		f.sprintKey = true;
		assertEquals(w.reengage, m.step(st, f, new Tally()) - same.step(st, facts(out), new Tally()), 1e-6f);
	}

	/**
	 * Getting out and coming back need not wait for the opponent's combo to run out by the clock:
	 * clear of it for a few ticks and then a charged hit before the opponent lands another is the
	 * same thing done faster.
	 */
	@Test
	void backingOutAndComingBackWithAChargedHitIsAnEscapeWithoutWaiting() {
		StageSpec st = stage("Combo escape");
		RewardWeights w = st.reward;
		float far = w.comboSpaceFrom + 0.5f;
		float plainHit = chargedHit(new RewardModel(), st, new Tally());

		RewardModel m = new RewardModel();
		Tally t = new Tally();
		takeHits(m, st, 2, t);
		quiet(m, st, far, 8, t);
		// (Fighting range is not paid while it is in the opponent's combo, so that much is missing from the hit's tick.)
		assertEquals(w.comboEscape - w.range, chargedHit(m, st, t) - plainHit, 1e-5f);
		assertEquals(1, t.comboEscapes);
		assertEquals(0, t.comboBreaks, "paid as an escape, not as a hit back from inside as well");
		assertEquals(1f, t.escapeRate());

		// Out of reach for a moment only is not having backed out: that hit is a hit back.
		RewardModel brief = new RewardModel();
		Tally b = new Tally();
		takeHits(brief, st, 2, b);
		quiet(brief, st, far, 3, b);
		assertEquals(w.comboBreak - w.range, chargedHit(brief, st, b) - plainHit, 1e-5f);
		assertEquals(0, b.comboEscapes);
		assertEquals(1, b.comboBreaks);

		// Caught on the way out, the count of ticks clear starts again: it has to get out of this hit too.
		RewardModel caught = new RewardModel();
		Tally c = new Tally();
		takeHits(caught, st, 2, c);
		quiet(caught, st, far, 8, c);
		takeHits(caught, st, 1, c);
		quiet(caught, st, far, 2, c);
		assertEquals(w.comboBreak - w.range, chargedHit(caught, st, c) - plainHit, 1e-5f);
		assertEquals(0, c.comboEscapes);

		// A weak hit cashes nothing in.
		RewardModel poke = new RewardModel();
		Tally p = new Tally();
		takeHits(poke, st, 2, p);
		quiet(poke, st, far, 8, p);
		StepFacts weak = facts(2.8f);
		weak.onBody = true;
		weak.hitsDealt = 1;
		weak.hitCharge = 0.4f;
		poke.step(st, weak, p);
		assertEquals(0, p.comboEscapes);
		assertEquals(1, p.comboBreaks);
	}

	/** After each hit that leaves it in a combo, either another follows or it got out. That share is what the stage is passed on. */
	@Test
	void gettingOutIsCountedPerHitOfTheCombo() {
		StageSpec st = stage("Combo escape");
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		takeHits(m, st, 1, t);
		assertEquals(0, t.comboedHits, "one hit is not a combo");
		takeHits(m, st, 1, t);
		assertEquals(1, t.comboedHits);
		assertEquals(1f, t.outRate(), "nothing has followed it yet");
		takeHits(m, st, 1, t);
		assertEquals(2, t.comboedHits);
		assertEquals(1, t.comboedHitsFollowed);
		assertEquals(0.5f, t.outRate(), 1e-6f);
		// It hits back; the opponent starts over.
		chargedHit(m, st, t);
		takeHits(m, st, 2, t);
		assertEquals(3, t.comboedHits);
		assertEquals(1, t.comboedHitsFollowed);
		// The combo runs out with it away: out again.
		quiet(m, st, 5f, 30, t);
		takeHits(m, st, 1, t);
		assertEquals(3, t.comboedHits, "a first hit again");
		assertEquals(2f / 3f, t.outRate(), 1e-6f);

		// A drill that starts inside a combo starts counted as in one.
		RewardModel drill = new RewardModel();
		Tally d = new Tally();
		drill.startComboed(st, 3, d);
		assertEquals(1, d.comboedHits);
		assertEquals(0, d.comboedHitsFollowed);
		assertEquals(0f, new Tally().outRate(), "and with no combos on it there is nothing to say");
	}

	/** While it is getting out of a combo nothing else is asked of its feet: the strafe plan waits. */
	@Test
	void theStrafePlanWaitsWhileItGetsOutOfACombo() {
		StageSpec st = stage("Combo escape");
		assertTrue(st.ideas && st.reward.mixStrafe > 0f, "the plan is still handed out and paid for there");
		RewardModel m = new RewardModel();
		RewardModel same = new RewardModel();
		Tally t = new Tally();
		takeHits(m, st, 2, t);
		takeHits(same, st, 2, new Tally());
		StepFacts against = facts(2.8f);
		against.ideaOn = true;
		against.ideaStrafe = 1;
		against.strafeAxis = -1;
		StepFacts with = facts(2.8f);
		with.ideaOn = true;
		with.ideaStrafe = 1;
		with.strafeAxis = 1;
		assertEquals(same.step(st, with, new Tally()), m.step(st, against, t), 1e-6f);
		assertEquals(0, t.mixTicks);
	}

	private static Tally jumpBatch(int steps, int windowTicks, int windowJumps) {
		Tally t = new Tally();
		t.steps = steps;
		t.jumpWindowTicks = windowTicks;
		t.jumpWindowJumps = windowJumps;
		return t;
	}

	/** The stages, with the crit stage given the steered push towards the jump that it had up to 0.12.3. */
	private static List<StageSpec> stagesWithASteeredJumpPush() {
		List<StageSpec> stages = Stages.defaults();
		stages.get(7).jumpNudgeRate = 0.5f;
		stages.get(7).jumpNudgeHoldSteps = 40_000;
		stages.get(7).jumpNudgeSteps = 80_000;
		return stages;
	}

	/**
	 * The crit stage starts the jump key afresh, and nothing pushes for the jump any more, in its
	 * fights or in its drills: a push makes jumping a habit before the fighter has met an opponent it
	 * should not jump at, and that habit does not come out again.
	 */
	@Test
	void theCritStageStartsTheJumpAfreshAndPushesForItNowhere() {
		List<StageSpec> stages = Stages.defaults();
		StageSpec crits = stages.get(7);
		assertEquals(ActionSpace.mask(ActionSpace.JUMP), crits.reopenHeads);
		for (int i = 0; i < stages.size(); i++) {
			assertEquals(0f, stages.get(i).jumpNudgeRate, stages.get(i).name);
			if (i != 7) {
				assertEquals(0, stages.get(i).reopenHeads, stages.get(i).name);
			}
		}
		Curriculum c = new Curriculum(stages);
		c.setStage(7);
		assertEquals(ActionSpace.mask(ActionSpace.JUMP), c.takeReopenHeads());
		assertEquals(0f, c.jumpNudge());
		assertTrue(c.critDrillsOnly());
		passCritDrills(c);
		assertEquals(0f, c.jumpNudge());
	}

	/** The steered push itself, for a stage that asks for one: as hard as it takes, for a set stretch, then gone. */
	@Test
	void aSteeredJumpPushGrowsWhileItIsLeantAgainstAndThenLetsGo() {
		List<StageSpec> stages = stagesWithASteeredJumpPush();
		StageSpec crits = stages.get(7);
		Curriculum c = new Curriculum(stages);
		c.setStage(6);
		assertEquals(0f, c.jumpNudge(), "no jumping in the stage before");
		assertEquals(0, c.takeReopenHeads());

		c.setStage(7);
		assertEquals(ActionSpace.mask(ActionSpace.JUMP), c.takeReopenHeads());
		assertEquals(0, c.takeReopenHeads(), "once");
		assertEquals(crits.jumpNudgeRate, c.jumpNudgeTarget());
		assertEquals(Curriculum.NUDGE_START, c.jumpNudge());

		// It leans against jumping: the push grows, update after update, up to its limit.
		c.record(jumpBatch(1000, 200, 0));
		assertEquals(Curriculum.NUDGE_START + Curriculum.NUDGE_GAIN * crits.jumpNudgeRate, c.jumpNudge(), 1e-5f);
		for (int i = 0; i < 20; i++) {
			c.record(jumpBatch(1000, 200, 0));
		}
		assertEquals(Curriculum.NUDGE_MAX, c.jumpNudge());
		// It jumps more than asked: the push shrinks, down to nothing.
		for (int i = 0; i < 20; i++) {
			c.record(jumpBatch(100, 200, 200));
		}
		assertEquals(0f, c.jumpNudge());
		// Right on target it stays as it is; too few such ticks to tell, likewise.
		c.record(jumpBatch(100, 200, 0));
		float at = c.jumpNudge();
		c.record(jumpBatch(100, 200, 100));
		assertEquals(at, c.jumpNudge(), 1e-6f);
		c.record(jumpBatch(100, Curriculum.NUDGE_MIN_TICKS - 1, 0));
		assertEquals(at, c.jumpNudge(), 1e-6f);

		// After the stretch it is held for, what it aims at comes down, and at the end it is gone for good.
		c.record(jumpBatch(crits.jumpNudgeHoldSteps, 0, 0));
		assertTrue(c.jumpNudgeTarget() < crits.jumpNudgeRate && c.jumpNudgeTarget() > 0f);
		c.record(jumpBatch(crits.jumpNudgeSteps, 0, 0));
		assertEquals(0f, c.jumpNudgeTarget());
		assertEquals(0f, c.jumpNudge());
		c.record(jumpBatch(1000, 200, 0));
		assertEquals(0f, c.jumpNudge(), "however little it jumps now");
	}

	@Test
	void theJumpPushAndAControlStillToBeReopenedSurviveSaveAndLoad(@TempDir Path dir) throws Exception {
		Curriculum a = new Curriculum(stagesWithASteeredJumpPush());
		a.setStage(7);
		a.record(jumpBatch(1000, 200, 0));
		Path file = dir.resolve("curriculum.properties");
		a.save(file);
		Curriculum b = new Curriculum(stagesWithASteeredJumpPush());
		b.load(file);
		assertEquals(7, b.stageIndex());
		assertEquals(-1, b.sentBackFrom());
		assertFalse(b.stageBegunAgain());
		assertEquals(a.jumpNudge(), b.jumpNudge(), 1e-6f);
		assertTrue(b.jumpNudge() > Curriculum.NUDGE_START);
		// The stage was set by hand with nothing running: the control is reopened by whoever trains next.
		assertEquals(ActionSpace.mask(ActionSpace.JUMP), b.takeReopenHeads());
		b.save(file);
		Curriculum d = new Curriculum(stagesWithASteeredJumpPush());
		d.load(file);
		assertEquals(0, d.takeReopenHeads());
	}

	private static void writeState(Path file, int stage, long steps, int rules) throws Exception {
		java.util.Properties p = new java.util.Properties();
		p.setProperty("stage", Integer.toString(stage));
		p.setProperty("stepsInStage", Long.toString(steps));
		if (rules > 0) {
			p.setProperty("rules", Integer.toString(rules));
		}
		try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file)) {
			p.store(w, "test");
		}
	}

	/**
	 * In 0.7.0 the stage about getting out of combos was left on its time limit with nothing learned.
	 * It teaches that differently now, so a run that has gone on to the crit stage goes back to it.
	 */
	@Test
	void aRunFromBeforeAStageChangedWhatItTeachesGoesBackToThatStage(@TempDir Path dir) throws Exception {
		List<StageSpec> stages = Stages.defaults();
		assertEquals(30, stages.get(5).lessonChangedIn);
		assertEquals(28, stages.get(6).lessonChangedIn);
		assertEquals(43, stages.get(7).lessonChangedIn);
		// The crit stage, and only it, has drills against an opponent that stands still.
		for (int i = 0; i < stages.size(); i++) {
			assertEquals(i == 7 ? 0.3f : 0f, stages.get(i).critDrillShare, stages.get(i).name);
		}
		assertTrue(dev.swordai.core.config.Config.CURRENT_VERSION >= 28);
		Path file = dir.resolve("curriculum.properties");

		writeState(file, 7, 21_600, 22);
		Curriculum c = new Curriculum(stages);
		c.load(file);
		// To the earliest stage whose lesson has changed since: the one combos are learned in.
		assertEquals(5, c.stageIndex());
		assertEquals("Sprint-hit + W-tap", c.current().name);
		assertEquals(7, c.sentBackFrom());
		assertEquals(0, c.stepsInStage());
		assertFalse(c.stageBegunAgain());
		// Saved, it is a run of this version, and stays where it is from then on.
		c.save(file);
		Curriculum again = new Curriculum(stages);
		again.load(file);
		assertEquals(5, again.stageIndex());
		assertEquals(-1, again.sentBackFrom());

		// In the changed stage itself its time there starts again.
		writeState(file, 5, 50_000, 22);
		Curriculum in = new Curriculum(stages);
		in.load(file);
		assertEquals(5, in.stageIndex());
		assertEquals(-1, in.sentBackFrom());
		assertTrue(in.stageBegunAgain());
		assertEquals(0, in.stepsInStage());

		// A run that is fighting itself is left there: going back is for its owner to decide.
		writeState(file, 8, 30_000, 22);
		Curriculum duel = new Curriculum(stages);
		duel.load(file);
		assertEquals(8, duel.stageIndex());
		assertEquals(-1, duel.sentBackFrom());
		assertEquals(30_000, duel.stepsInStage());

		// Earlier stages have nothing to do with it; a state saved by this version is taken as it is.
		writeState(file, 4, 9_000, 22);
		Curriculum early = new Curriculum(stages);
		early.load(file);
		assertEquals(4, early.stageIndex());
		assertEquals(9_000, early.stepsInStage());
		writeState(file, 7, 21_600, dev.swordai.core.config.Config.CURRENT_VERSION);
		Curriculum current = new Curriculum(stages);
		current.load(file);
		assertEquals(7, current.stageIndex());
		assertEquals(21_600, current.stepsInStage());

		// A run saved since those stages last changed keeps its place and its clock through a later
		// update: nothing is begun again, so no control is started afresh and no help repeated.
		for (int at = 6; at <= 7; at++) {
			writeState(file, at, 33_000, at == 6 ? 32 : 43);
			Curriculum kept = new Curriculum(stages);
			kept.load(file);
			assertEquals(at, kept.stageIndex());
			assertEquals(-1, kept.sentBackFrom());
			assertFalse(kept.stageBegunAgain());
			assertEquals(33_000, kept.stepsInStage());
			assertEquals(0, kept.takeReopenHeads());
		}

		// Since 0.13.1 the crit stage teaches when to jump and when not together, from a jump key that
		// has no habit yet. A run that was in it under the rules before begins it again, from its first
		// drill, with the jump started afresh; whether it had passed the one drill there was then no
		// longer counts.
		java.util.Properties old = new java.util.Properties();
		old.setProperty("stage", "7");
		old.setProperty("stepsInStage", "33000");
		old.setProperty("rules", "33");
		old.setProperty("critDrillsPassed", "true");
		try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file)) {
			old.store(w, "test");
		}
		Curriculum crits = new Curriculum(stages);
		crits.load(file);
		assertEquals(7, crits.stageIndex());
		assertTrue(crits.stageBegunAgain());
		assertEquals(0, crits.stepsInStage());
		assertTrue(crits.critDrillsOnly());
		assertEquals(Curriculum.DRILL_RANGE, crits.critDrillLesson());
		assertEquals(ActionSpace.mask(ActionSpace.JUMP), crits.takeReopenHeads());

		// The same for a run of 0.13.2 that had passed both drills and then, in the stage's fights,
		// stopped jumping: its jump is started afresh and taught again, under rules that do not
		// charge it for a jump the opponent stepped away from.
		old.setProperty("rules", "36");
		old.remove("critDrillsPassed");
		old.setProperty("critDrill", "2");
		old.setProperty("stepsInStage", "162783");
		try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file)) {
			old.store(w, "test");
		}
		Curriculum forgot = new Curriculum(stages);
		forgot.load(file);
		assertEquals(7, forgot.stageIndex());
		assertTrue(forgot.stageBegunAgain());
		assertEquals(0, forgot.stepsInStage());
		assertTrue(forgot.critDrillsOnly());
		assertEquals(Curriculum.DRILL_RANGE, forgot.critDrillLesson());
		assertEquals(ActionSpace.mask(ActionSpace.JUMP), forgot.takeReopenHeads());

		// And for one of 0.13.3 or of the first runs of 0.14.0: their first drill taught another jump than
		// this one's, and none of their opponents was on a slow connection.
		for (String rules : new String[] {"37", "38", "39", "40", "41", "42"}) {
			old.setProperty("rules", rules);
			try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file)) {
				old.store(w, "test");
			}
			Curriculum again39 = new Curriculum(stages);
			again39.load(file);
			assertTrue(again39.stageBegunAgain(), rules);
			assertTrue(again39.critDrillsOnly(), rules);
			assertEquals(ActionSpace.mask(ActionSpace.JUMP), again39.takeReopenHeads(), rules);
		}
	}

	/** From the stage about combos on, fights have a few ticks of lag where the mod can make it. */
	@Test
	void fromTheComboEscapeStageOnFightsHaveLag() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < stages.size(); i++) {
			StageSpec st = stages.get(i);
			if (i < 6) {
				assertEquals(0, st.lagTicks, st.name);
				assertEquals(0, st.seenLagTicks, st.name);
				assertEquals(0f, st.lagStutter, st.name);
				// The opponent's own lag and the knockback it takes begin a stage earlier, where combos are learned.
				if (i < 5) {
					assertEquals(0, st.hitLagTicks, st.name);
					assertEquals(0f, st.knockbackMin, st.name);
				} else {
					assertTrue(st.hitLagTicks >= 1 && st.hitLagTicks <= 3, st.name);
					assertTrue(st.knockbackMin >= 0.5f && st.knockbackMin < 1f, st.name);
				}
			} else {
				assertTrue(st.lagTicks >= 2 && st.lagTicks <= 4, st.name);
				// What the fighter sees of its opponent is the later of the two on a server, and comes in fits and starts.
				assertTrue(st.seenLagTicks >= st.lagTicks && st.seenLagTicks <= 6, st.name);
				assertTrue(st.lagStutter > 0f && st.lagStutter <= 0.2f, st.name);
				// And a hit knocks the opponent back a little late, as it does a player with a connection of its own.
				assertTrue(st.hitLagTicks >= 1 && st.hitLagTicks <= 3, st.name);
			}
		}
	}

	/**
	 * A fight against a real opponent is played with every key and with combos and critical hits all
	 * paid for, from the stage before the one that has every key on; before that, as the run's own stage.
	 */
	@Test
	void aFightAgainstARealOpponentIsPlayedWithEveryKeyOnceTheRunIsInTheComboStage() {
		List<StageSpec> stages = Stages.defaults();
		Curriculum c = new Curriculum(stages);
		for (int i = 0; i < stages.size(); i++) {
			c.setStage(i);
			int fight = c.fightStageIndex();
			if (i < 6) {
				assertEquals(i, fight);
			} else {
				assertEquals(Math.max(i, 7), fight);
				assertEquals(ActionSpace.ALL_HEADS, stages.get(fight).headMask);
				assertTrue(stages.get(fight).reward.crit > 0f && stages.get(fight).reward.comboThird > 0f, stages.get(fight).name);
			}
		}
		// Only if asked to: what a minute of fighting against a person taught it was measured, and it was harm.
		assertFalse(new dev.swordai.core.config.Config().learnFromPeople);
		assertTrue(new dev.swordai.core.config.Config().fightRolloutSteps <= 900);
	}

	/**
	 * The crit stage begins with nothing but crit drills: the jump first (when to make one and when
	 * not, together), then the punish crit. Each has to be passed before the next begins, and the
	 * stage cannot be passed before the last is, however long it lasts.
	 */
	@Test
	void theCritStageFightsNothingButItsDrillsUntilTheLastIsPassed(@TempDir Path dir) throws Exception {
		List<StageSpec> stages = Stages.defaults();
		StageSpec crits = stages.get(7);
		assertTrue(crits.critDrillPass >= 0.4f && crits.critDrillPass <= 0.7f, "its sprint hits still land there too: half is the lesson");
		assertEquals(0f, crits.critDrillFarJumpMax, "where it may not jump is no longer part of the drill: the fights teach that");
		assertTrue(crits.critDrillPunishPass >= 0.5f);
		assertEquals(0, crits.promote.styleOptionalAfterSteps, "no way out of this stage by waiting");
		assertTrue(crits.promote.minCritShare > 0f);
		Curriculum c = new Curriculum(stages);
		c.setStage(6);
		assertFalse(c.critDrillsOnly());
		assertNull(c.critDrillGoal());
		c.setStage(7);
		assertTrue(c.critDrillsOnly());
		assertEquals(Curriculum.DRILL_RANGE, c.critDrillLesson());
		assertTrue(c.promotionBlocker().startsWith("the crit drills come first"), c.promotionBlocker());
		assertTrue(c.promotionBlocker().contains("drill 1 of 2"), c.promotionBlocker());
		assertFalse(c.tryPromote());

		// ---- 1. the crit in its face: half of its hits on the opponent that pushes into it critical ----
		Tally drills = new Tally();
		drills.hitsDealt = 200;
		drills.critHits = 60;
		assertEquals(-1, c.recordCritDrills(drills, drills), "three in ten is not enough");
		drills.hitsDealt = Curriculum.CRIT_DRILL_MIN_HITS - 1;
		drills.critHits = Curriculum.CRIT_DRILL_MIN_HITS - 1;
		assertEquals(-1, c.recordCritDrills(drills, drills), "nor are a handful of hits: one that has stopped jumping altogether has learned nothing");
		// It is still in the first drill after a save.
		Path file = dir.resolve("curriculum.properties");
		c.save(file);
		Curriculum again = new Curriculum(stages);
		again.load(file);
		assertTrue(again.critDrillsOnly());
		assertEquals(Curriculum.DRILL_RANGE, again.critDrillLesson());
		drills.hitsDealt = 200;
		drills.critHits = 110;
		// How often it jumps with the opponent far off is not asked here any more.
		drills.farGroundTicks = 800;
		drills.farJumps = 24;
		assertEquals(Curriculum.DRILL_RANGE, c.recordCritDrills(drills, drills));

		// A stage that still asks it (as the crit stage did up to 0.13.3) holds a fighter that jumps at the far opponent.
		List<StageSpec> lined = Stages.defaults();
		lined.get(7).critDrillPass = 0.8f;
		lined.get(7).critDrillFarJumpMax = 0.01f;
		Curriculum old = new Curriculum(lined);
		old.setStage(7);
		Tally jumpy = new Tally();
		jumpy.hitsDealt = 200;
		jumpy.critHits = 170;
		jumpy.farGroundTicks = 800;
		jumpy.farJumps = 24;
		assertEquals(-1, old.recordCritDrills(jumpy, jumpy), "3% of the ticks it stands out of reach is still jumping at nothing");
		jumpy.farGroundTicks = Curriculum.CRIT_DRILL_MIN_FAR_TICKS - 1;
		jumpy.farJumps = 0;
		assertEquals(-1, old.recordCritDrills(jumpy, jumpy), "too little time out of reach to tell");
		jumpy.farGroundTicks = 800;
		jumpy.farJumps = 6;
		assertEquals(Curriculum.DRILL_RANGE, old.recordCritDrills(jumpy, jumpy));
		assertTrue(old.critDrillGoal().contains("the punish crit"), old.critDrillGoal());
		assertTrue(c.critDrillsOnly(), "one drill passed is not the drills passed");
		assertEquals(Curriculum.DRILL_PUNISH, c.critDrillLesson());
		assertTrue(c.promotionBlocker().contains("drill 2 of 2"), c.promotionBlocker());
		assertFalse(c.tryPromote());
		c.save(file);
		again.load(file);
		assertEquals(Curriculum.DRILL_PUNISH, again.critDrillLesson());

		// ---- 2. the punish crit: of the chances the opponent's hits leave, most have to be taken ----
		// And over a hundred and fifty of them, however well the first batch goes: in 0.13.2 it was
		// passed on its first twenty-eight chances, which was all the practice it ever got.
		Tally jump = new Tally();
		Tally punish = new Tally();
		punish.hitsTaken = 40;
		punish.punishChances = 28;
		punish.punishCrits = 28;
		assertEquals(-1, c.recordCritDrills(jump, punish), "twenty-eight out of twenty-eight is a good start and not the lesson");
		assertEquals(28, c.punishChancesSoFar());
		assertEquals(28, c.punishCritsSoFar());
		assertTrue(c.critDrillsOnly());
		// What it has so far is kept over a restart.
		c.save(file);
		again.load(file);
		assertEquals(28, again.punishChancesSoFar());
		assertEquals(28, again.punishCritsSoFar());
		// Then it stops taking them: a third of the chances, all told, is not enough, and it starts over.
		punish.hitsTaken = 200;
		punish.punishChances = 140;
		punish.punishCrits = 28;
		assertEquals(-1, c.recordCritDrills(jump, punish), "a third of the chances is not enough");
		assertEquals(0, c.punishChancesSoFar(), "judged, not passed, and gathered afresh");
		assertEquals(168, c.punishJudgedChances());
		assertEquals(56, c.punishJudgedCrits());
		punish.punishChances = Curriculum.PUNISH_LESSON_CHANCES - 1;
		punish.punishCrits = Curriculum.PUNISH_LESSON_CHANCES - 1;
		assertEquals(-1, c.recordCritDrills(jump, punish), "nor is one chance short of the number it is judged over");
		punish.punishChances = 60;
		punish.punishCrits = 0;
		// 149 of 209: seven in ten.
		assertEquals(Curriculum.DRILL_PUNISH, c.recordCritDrills(jump, punish));
		assertEquals(209, c.punishJudgedChances());
		assertEquals(149, c.punishJudgedCrits());
		assertFalse(c.critDrillLeft());
		assertFalse(c.critDrillsOnly());
		assertEquals(Curriculum.DRILLS, c.critDrillLesson());
		assertNull(c.critDrillGoal());
		assertFalse(c.promotionBlocker().startsWith("the crit drill"), c.promotionBlocker());
		c.save(file);
		again.load(file);
		assertFalse(again.critDrillsOnly());
		// Begun again, the stage begins with the first drill again.
		c.setStage(7);
		assertTrue(c.critDrillsOnly());
		assertEquals(Curriculum.DRILL_RANGE, c.critDrillLesson());
	}

	/**
	 * The punish drill teaches something only if the opponent is still in reach when the fighter comes
	 * down. If it hardly ever is, that is the drill's fault: the fighter is not kept in it for ever,
	 * the lesson is left and said to have been.
	 */
	@Test
	void aPunishDrillThatLeavesNoChancesIsLeftAndNotWaitedOnForEver() {
		Curriculum c = new Curriculum(Stages.defaults());
		c.setStage(7);
		Tally good = new Tally();
		good.hitsDealt = 100;
		good.critHits = 95;
		good.jumpsEnded = 100;
		good.jumpCrits = 95;
		good.farGroundTicks = 800;
		c.recordCritDrills(good, good);
		assertEquals(Curriculum.DRILL_PUNISH, c.critDrillLesson());

		// Chances there are, and it does not take them: that is the fighter's to learn, however long it takes.
		Tally missed = new Tally();
		missed.hitsTaken = 150;
		missed.punishChances = 60;
		missed.punishCrits = 5;
		for (int i = 0; i < 20; i++) {
			assertEquals(-1, c.recordCritDrills(new Tally(), missed));
		}
		assertTrue(c.critDrillsOnly());

		// No chances to speak of: after enough hits to be sure of that, the lesson is left.
		c.setStage(7);
		c.recordCritDrills(good, good);
		Tally none = new Tally();
		none.hitsTaken = 150;
		none.punishChances = 3;
		int batches = 0;
		int left = -1;
		while (left < 0 && batches < 20) {
			left = c.recordCritDrills(new Tally(), none);
			batches++;
		}
		assertEquals(Curriculum.DRILL_PUNISH, left);
		assertTrue(c.critDrillLeft());
		assertTrue(batches * 150 >= Curriculum.PUNISH_DRILL_TRIAL_HITS, "not on the first batch: " + batches);
		assertFalse(c.critDrillsOnly());
	}

	private static Tally backBatch(int steps, int backTicks, int backHeld) {
		Tally t = new Tally();
		t.steps = steps;
		t.backTicks = backTicks;
		t.backHeld = backHeld;
		return t;
	}

	/**
	 * The combo stage pushes the policy to hold S right after a hit of a combo, for a set stretch, and
	 * then lets go; no other stage does, and no control is started afresh for it.
	 */
	@Test
	void theComboStagePushesForSUntilItHasBeenTriedAndThenLetsGo(@TempDir Path dir) throws Exception {
		List<StageSpec> stages = Stages.defaults();
		StageSpec combo = stages.get(6);
		assertEquals(0, combo.reopenHeads, "the forward key holds everything it knows about spacing");
		assertTrue(combo.backNudgeRate > 0f && combo.backNudgeHoldSteps > 0 && combo.backNudgeSteps > combo.backNudgeHoldSteps);
		assertTrue(combo.promote.minSteps > combo.backNudgeSteps, "passed on what it does unaided");
		assertTrue(combo.promote.minBackRate < combo.backNudgeRate);
		for (int i = 0; i < stages.size(); i++) {
			if (i != 6 && i != 7) {
				assertEquals(0f, stages.get(i).backNudgeRate, stages.get(i).name);
			}
		}
		StageSpec crits = stages.get(7);
		// The crit stage pushes for it once more, for a shorter while: nothing else there keeps it up.
		assertTrue(crits.backNudgeRate > 0f && crits.backNudgeSteps > crits.backNudgeHoldSteps && crits.backNudgeSteps <= combo.backNudgeSteps);
		assertTrue(crits.promote.minSteps > crits.backNudgeSteps, "passed on what it does unaided");
		assertTrue(crits.promote.minSteps > crits.jumpNudgeSteps, "passed on what it does unaided");

		Curriculum c = new Curriculum(stages);
		c.setStage(5);
		assertEquals(0f, c.backNudge());
		c.setStage(6);
		assertEquals(combo.backNudgeRate, c.backNudgeTarget());
		assertEquals(Curriculum.NUDGE_START, c.backNudge());
		assertEquals(0f, c.jumpNudge());

		// S is not held: the push grows. It is held more than asked: the push shrinks to nothing.
		c.record(backBatch(1000, 200, 0));
		assertEquals(Curriculum.NUDGE_START + Curriculum.NUDGE_GAIN * combo.backNudgeRate, c.backNudge(), 1e-5f);
		for (int i = 0; i < 20; i++) {
			c.record(backBatch(100, 200, 200));
		}
		assertEquals(0f, c.backNudge());
		c.record(backBatch(100, Curriculum.BACK_MIN_TICKS - 1, 0));
		assertEquals(0f, c.backNudge(), "too few such ticks to steer by");

		// It survives a save, and after the stretch it is held for both come down and end.
		c.record(backBatch(100, 200, 0));
		float at = c.backNudge();
		assertTrue(at > 0f);
		Path file = dir.resolve("curriculum.properties");
		c.save(file);
		Curriculum again = new Curriculum(stages);
		again.load(file);
		assertEquals(at, again.backNudge(), 1e-6f);
		c.record(backBatch(combo.backNudgeHoldSteps, 0, 0));
		assertTrue(c.backNudgeTarget() < combo.backNudgeRate && c.backNudgeTarget() > 0f);
		c.record(backBatch(combo.backNudgeSteps, 0, 0));
		assertEquals(0f, c.backNudgeTarget());
		assertEquals(0f, c.backNudge());

		// The stage after pushes for it once more from its start, and not for the jump.
		c.setStage(7);
		assertEquals(Curriculum.NUDGE_START, c.backNudge());
		assertEquals(0f, c.jumpNudge());
		// And from there on it is kept on: through the rest of that stage, and in the duel after it.
		StageSpec critStage = stages.get(7);
		assertTrue(critStage.backNudgeKeep > 0f && critStage.backNudgeKeep < critStage.backNudgeRate);
		assertEquals(critStage.backNudgeRate, c.backNudgeTarget());
		c.record(backBatch(critStage.backNudgeSteps + 1, 0, 0));
		assertEquals(critStage.backNudgeKeep, c.backNudgeTarget());
		assertTrue(c.backNudge() > 0f);
		c.record(backBatch(1000, 200, 0));
		float kept = c.backNudge();
		assertTrue(kept > Curriculum.NUDGE_START);
		c.setStage(8);
		assertEquals(stages.get(8).backNudgeKeep, c.backNudgeTarget());
		assertEquals(kept, c.backNudge(), 1e-6f, "at the strength it had");
		assertEquals(stages.get(8).backNudgeKeep, stages.get(9).backNudgeKeep, 0f);
		assertEquals(0f, combo.backNudgeKeep, "the stage that teaches it is passed on what it does unaided");
	}
}
