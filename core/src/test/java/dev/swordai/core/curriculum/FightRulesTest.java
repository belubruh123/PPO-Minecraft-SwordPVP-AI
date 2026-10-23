package dev.swordai.core.curriculum;

import dev.swordai.core.config.Config;
import dev.swordai.core.env.StepFacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What 0.14.0 changed about how a fight is scored and set up. */
class FightRulesTest {
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

	private static final int PLAIN = 0;
	private static final int SPRINT = 1;
	private static final int CRIT = 2;

	/** A fully charged hit of this kind. */
	private static float hit(RewardModel m, StageSpec st, int kind, Tally t) {
		StepFacts f = facts(2.8f);
		f.reachAtAction = 2.8f;
		f.clicked = true;
		f.clickOnTarget = true;
		f.chargeAtClick = 1f;
		f.hitsDealt = 1;
		f.hitCharge = 1f;
		f.sprintHit = kind == SPRINT;
		f.critDealt = kind == CRIT;
		return m.step(st, f, t);
	}

	/** The same tick with the swing made and nothing landed. */
	private static float miss(RewardModel m, StageSpec st) {
		StepFacts f = facts(2.8f);
		f.reachAtAction = 2.8f;
		f.clicked = true;
		f.chargeAtClick = 1f;
		return m.step(st, f, new Tally());
	}

	/**
	 * Once it can knock back, a hit that neither knocks back nor is critical earns nothing: not for
	 * itself, and not for the combo, which it is no part of. While hitting at all is still being
	 * learned, it earns what a hit earns.
	 */
	@Test
	void aHitThatNeitherKnocksBackNorIsCriticalEarnsNothingOnceItCanDoBetter() {
		for (String name : new String[] {"Hit timing", "Sprint-hit + W-tap"}) {
			StageSpec st = stage(name);
			assertEquals(1f, st.reward.plainHit, name);
			assertEquals(st.reward.hit, hit(new RewardModel(), st, PLAIN, new Tally()) - miss(new RewardModel(), st), 1e-5f, name);
		}
		for (String name : new String[] {"Combo escape", "Crits", "Duel", "Self-play"}) {
			StageSpec st = stage(name);
			assertEquals(0f, st.reward.plainHit, name);
			Tally t = new Tally();
			assertEquals(0f, hit(new RewardModel(), st, PLAIN, t) - miss(new RewardModel(), st), 1e-5f, name + ": worth no more than a miss");
			assertEquals(1, t.hitsDealt, "it is still counted as a hit");
			assertTrue(hit(new RewardModel(), st, SPRINT, new Tally()) - miss(new RewardModel(), st) >= st.reward.hit + st.reward.sprintHit - 1e-5f, name);

			// In a combo it counts for nothing: the sprint hit after it is paid as if it had not been made.
			RewardModel with = new RewardModel();
			RewardModel without = new RewardModel();
			hit(with, st, SPRINT, new Tally());
			hit(without, st, SPRINT, new Tally());
			hit(with, st, PLAIN, new Tally());
			assertEquals(hit(without, st, SPRINT, new Tally()), hit(with, st, SPRINT, new Tally()), 1e-5f, name);
			assertEquals(2, with.combo(), name);
		}
	}

	/**
	 * The rule that pays the two kinds of hit taken in turn. No stage uses it (it taught going after an
	 * opponent that had been knocked away with a jump, which lost more hits than it won), but it is
	 * there to be set.
	 */
	@Test
	void theTwoKindsOfHitTakenInTurnCanBePaidAndNoStagePaysIt() {
		for (StageSpec st : Stages.defaults()) {
			assertEquals(0f, st.reward.critFollow, st.name);
			assertEquals(0f, st.reward.sprintFollow, st.name);
		}
		StageSpec st = stage("Crits");
		StageSpec base = stage("Crits");
		st.reward.critFollow = 1.5f;
		st.reward.sprintFollow = 1f;
		RewardModel m = new RewardModel();
		RewardModel b = new RewardModel();
		Tally t = new Tally();
		assertEquals(0f, hit(m, st, SPRINT, t) - hit(b, base, SPRINT, new Tally()), 1e-5f, "the first hit of a combo follows nothing");
		assertEquals(1.5f, hit(m, st, CRIT, t) - hit(b, base, CRIT, new Tally()), 1e-5f, "a critical hit after a sprint hit");
		assertEquals(0f, hit(m, st, CRIT, t) - hit(b, base, CRIT, new Tally()), 1e-5f, "a critical hit after a critical hit");
		assertEquals(1f, hit(m, st, SPRINT, t) - hit(b, base, SPRINT, new Tally()), 1e-5f, "a sprint hit after a critical hit");
		assertEquals(0f, hit(m, st, SPRINT, t) - hit(b, base, SPRINT, new Tally()), 1e-5f, "a sprint hit after a sprint hit");
		assertEquals(1, t.critFollows);
		assertEquals(1, t.sprintFollows);
		// Hit in between, the combo is over and the next hit follows nothing.
		StepFacts taken = facts(2.8f);
		taken.hitsTaken = 1;
		m.step(st, taken, t);
		b.step(base, taken, new Tally());
		assertEquals(0f, hit(m, st, CRIT, t) - hit(b, base, CRIT, new Tally()), 1e-5f);
		assertEquals(1, t.critFollows);
		Tally sum = new Tally();
		sum.add(t);
		assertEquals(1, sum.critFollows);
		assertEquals(1, sum.sprintFollows);
		sum.clear();
		assertEquals(0, sum.critFollows + sum.sprintFollows);
	}

	/**
	 * On a server the delay between two fighters is one thing: a hit moves the opponent only once it
	 * has gone to the opponent's game and come back, and what each sees of the other is old by the way
	 * between them. From the stage combos are learned in, each fight draws one such connection. From
	 * the stage after it, every other fight is against an opponent on a slow one, whose knockback
	 * comes a quarter to half a second late; the rest are ordinary, none to a fifth of a second.
	 */
	@Test
	void fromTheComboStageOnEachFightHasOneConnectionAndEveryOtherOneIsSlow() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < stages.size(); i++) {
			StageSpec st = stages.get(i);
			if (i < 5) {
				assertEquals(0, st.pathLagTicks, st.name);
				assertEquals(0f, st.slowShare, st.name);
			} else {
				// Ordinary: the knockback is twice the way late, so none to four ticks.
				assertTrue(st.pathLagTicks >= 1 && st.pathLagTicks <= 3, st.name);
				if (i == 5) {
					assertEquals(0f, st.slowShare, st.name + ": the combo is still being learned");
				} else {
					assertTrue(st.slowShare >= 0.3f && st.slowShare <= 0.6f, st.name);
					assertTrue(st.slowKnockbackMinTicks > 2 * st.pathLagTicks, st.name + ": later than any ordinary one");
					assertTrue(st.slowKnockbackMinTicks <= 6 && st.slowKnockbackMaxTicks >= 10, st.name + ": 0.3 to 0.5 seconds are six to ten ticks");
					assertTrue(st.slowKnockbackMaxTicks <= 12, st.name);
				}
			}
		}
		// A run that was in the crit stage under rules without such opponents begins it again.
		assertTrue(stages.get(7).lessonChangedIn >= 41);
	}

	/**
	 * A fight on a server begins across an arena, not a few steps apart. From the second stage on a
	 * share of the targets and of the fights are that far off, and the arena is large enough for it.
	 */
	@Test
	void aShareOfTheFightsBeginsAcrossTheArena() {
		List<StageSpec> stages = Stages.defaults();
		assertEquals(0f, stages.get(0).bot.farChance, "the first stage is about a target that is in view and near");
		for (int i = 1; i < stages.size(); i++) {
			BotProfile bot = stages.get(i).bot;
			assertTrue(bot.farChance >= 0.15f && bot.farChance <= 0.3f, stages.get(i).name);
			assertTrue(bot.farMinDistance >= 9f && bot.farMaxDistance >= 20f && bot.farMaxDistance > bot.farMinDistance, stages.get(i).name);
			// The fighter starts within a third of the way from the middle to the wall, and the opponent has to fit inside the walls.
			assertTrue(bot.farMaxDistance <= 1.35f * Config.MIN_ARENA_RADIUS, stages.get(i).name);
			assertEquals(bot.farChance, bot.copy().farChance, 0f);
		}
		assertTrue(new Config().arenaRadius >= Config.MIN_ARENA_RADIUS);
		assertEquals(0f, stages.get(7).bot.drill().farChance, 0f, "a combo drill starts on top of it, always");
	}

	/** Learning from fights against people is something to ask for, not something that happens. */
	@Test
	void fightsAgainstPeopleTeachItOnlyIfAskedTo() {
		Config cfg = new Config();
		assertFalse(cfg.learnFromPeople);
		assertTrue(cfg.swingMinCharge > 0.85f && cfg.swingMinCharge < 0.9f, "eleven ticks: the game adds half a tick and makes it more than nine tenths");
		assertTrue(cfg.ruleSwingCharge > 0.9f && cfg.ruleSwingCharge < 1f, "a rule's swing waits a tick more");
		assertEquals(1f, cfg.swingMinChargeOnline, 1e-6f, "on a server, two ticks in hand: thirteen ticks, as 0.14.0 (twelve gave weak hits at 105 ms)");
		assertEquals(1f, cfg.ruleSwingChargeOnline, "and a rule's swing the weapon all the way back");
		assertTrue(Config.PEOPLE_AND_ARENA_FROM <= Config.CURRENT_VERSION);
	}

	/**
	 * A scripted opponent is late with a swing now and then, as a person is: for a tick or a few it
	 * stands in reach with its weapon back and does not swing. Those are the ticks in which a swing
	 * back lands. From the stage after combos are learned; a copy of itself needs no such thing.
	 */
	@Test
	void theScriptedOpponentIsLateWithASwingNowAndThen() {
		List<StageSpec> stages = Stages.defaults();
		for (int i = 0; i < stages.size(); i++) {
			BotProfile bot = stages.get(i).bot;
			if (i == 6 || i == 7) {
				assertTrue(bot.swingSlip >= 0.2f && bot.swingSlip <= 0.5f, stages.get(i).name);
				assertTrue(bot.swingSlipTicks >= 2 && bot.swingSlipTicks <= 4, stages.get(i).name);
				assertEquals(bot.swingSlip, bot.copy().swingSlip, 0f);
				assertEquals(bot.swingSlipTicks, bot.copy().swingSlipTicks);
			} else {
				assertEquals(0f, bot.swingSlip, stages.get(i).name);
			}
		}
	}

	/**
	 * What is asked of it in a combo is counted directly: of the opponent's combos that got to two
	 * hits with it free to act, how many got a third. Out by the second hit is the mark.
	 */
	@Test
	void whetherItIsOutOfAComboByTheSecondHitIsCounted() {
		StageSpec st = stage("Crits");
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		// One hit is no combo.
		taken(m, st, t);
		assertEquals(0, t.twoHitCombos);
		// A second: a combo at two. It hits back before a third: out by the second hit.
		taken(m, st, t);
		assertEquals(1, t.twoHitCombos);
		hit(m, st, SPRINT, t);
		assertEquals(0, t.threeHitCombos);
		assertEquals(1f, t.outByTwoRate(), 1e-6f);
		// The next one runs to four hits: it is one combo, and it got a third.
		for (int i = 0; i < 4; i++) {
			taken(m, st, t);
		}
		assertEquals(2, t.twoHitCombos);
		assertEquals(1, t.threeHitCombos);
		assertEquals(0.5f, t.outByTwoRate(), 1e-6f);

		// In a drill it is handed some hits into a combo. Handed two, the combo is at two as it gets
		// its keys, and a third is its doing. Handed one, it is at two only if it takes another.
		// Handed three or more, it could not have been out by the second, and the combo is not counted.
		RewardModel two = new RewardModel();
		Tally drill = new Tally();
		two.startComboed(st, 2, drill);
		assertEquals(1, drill.twoHitCombos);
		taken(two, st, drill);
		assertEquals(1, drill.threeHitCombos);
		RewardModel one = new RewardModel();
		one.startComboed(st, 1, drill);
		assertEquals(1, drill.twoHitCombos);
		taken(one, st, drill);
		assertEquals(2, drill.twoHitCombos);
		assertEquals(1, drill.threeHitCombos);
		RewardModel four = new RewardModel();
		four.startComboed(st, 4, drill);
		taken(four, st, drill);
		taken(four, st, drill);
		assertEquals(2, drill.twoHitCombos);
		assertEquals(1, drill.threeHitCombos);
		// The third hit costs once, on top of what any hit of a combo costs; the fourth costs no more than that.
		RewardModel paid = new RewardModel();
		RewardModel free = new RewardModel();
		StageSpec noThird = stage("Crits");
		noThird.reward.comboThird = 0f;
		Tally pt = new Tally();
		float[] with = new float[4];
		float[] without = new float[4];
		for (int i = 0; i < 4; i++) {
			StepFacts f = facts(2.8f);
			f.hitsTaken = 1;
			with[i] = paid.step(st, f, pt);
			StepFacts g = facts(2.8f);
			g.hitsTaken = 1;
			without[i] = free.step(noThird, g, new Tally());
		}
		assertEquals(without[0], with[0], 1e-5f);
		assertEquals(without[1], with[1], 1e-5f);
		assertEquals(without[2] - st.reward.comboThird, with[2], 1e-5f);
		assertEquals(without[3], with[3], 1e-5f);
		// The combo drills of the crit stage begin one or two hits in, so that the third is its doing.
		assertEquals(2, st.drillHits);
		assertEquals(1, st.drillMinHits);

		// And they add up and clear like every other count.
		Tally sum = new Tally();
		sum.add(drill);
		sum.addCombosTaken(drill);
		assertEquals(4, sum.twoHitCombos);
		assertEquals(2, sum.threeHitCombos);
		sum.clear();
		assertEquals(0, sum.twoHitCombos + sum.threeHitCombos);
	}

	private static void taken(RewardModel m, StageSpec st, Tally t) {
		StepFacts f = facts(2.8f);
		f.hitsTaken = 1;
		m.step(st, f, t);
	}
	/** A run at the opponent and then a jump; {@code closing} is how much nearer each tick of the run brings it. */
	private static float[] runAndJump(StageSpec st, float closing, boolean crit, Tally t) {
		RewardModel m = new RewardModel();
		float reach = 4f + 8 * closing;
		float atCrit = 0f;
		float rest = 0f;
		for (int i = 0; i < 24; i++) {
			boolean ground = i < 8 || i >= 20;
			if (i < 8) {
				reach -= closing;
			}
			StepFacts f = facts(i < 8 ? reach : 4f - 0.1f * (i - 8));
			f.reachAtAction = f.reachDist;
			f.forwardAxis = 1;
			f.sprintKey = i < 14;
			f.onGround = ground;
			f.inAir = !ground;
			f.falling = i >= 14 && i < 20;
			f.jumpKey = i == 8;
			f.tookOff = i == 8;
			if (crit && i == 16) {
				f.clicked = true;
				f.clickOnTarget = true;
				f.chargeAtClick = 1f;
				f.hitsDealt = 1;
				f.hitCharge = 1f;
				f.critDealt = true;
				atCrit = m.step(st, f, t);
			} else {
				rest += m.step(st, f, t);
			}
		}
		return new float[] {atCrit, rest};
	}

	/**
	 * Running after an opponent and not getting nearer, a jump is free to try and a critical hit that
	 * comes of it earns more; at one that stands, or one it is closing on, a jump costs what it always did.
	 */
	@Test
	void aJumpAtAnOpponentItIsNotCatchingIsFreeToTry() {
		StageSpec st = stage("Crits");
		RewardWeights w = st.reward;
		assertTrue(w.chaseCrit > 0f && w.wastedJump > 0f);
		assertTrue(w.chaseFrom >= 3f && w.chaseTo > w.chaseFrom && w.chaseTo <= w.jumpWithin);
		// The opponent backs off as fast as the fighter runs: the jump is one made in a chase.
		Tally away = new Tally();
		float[] awayMiss = runAndJump(st, 0f, false, away);
		assertEquals(1, away.chaseJumps);
		assertEquals(0, away.chaseCrits);
		// The opponent stands: every tick of the run brings it a quarter of a block nearer. An ordinary jump.
		Tally standing = new Tally();
		float[] standingMiss = runAndJump(st, 0.28f, false, standing);
		assertEquals(0, standing.chaseJumps);
		assertEquals(1, standing.jumpsEnded);
		assertEquals(1, away.jumpsEnded);
		// With a critical hit at the end of it: the chase pays more, by exactly what it says.
		Tally awayHit = new Tally();
		Tally standingHit = new Tally();
		float chased = runAndJump(st, 0f, true, awayHit)[0];
		float plain = runAndJump(st, 0.28f, true, standingHit)[0];
		assertEquals(1, awayHit.chaseCrits);
		assertEquals(0, standingHit.chaseCrits);
		assertEquals(w.chaseCrit, chased - plain, 1e-4f);
		// And the one that came to nothing was not charged in the chase, and was at the one that stood:
		// from the moment of the jump the two fights are the same, so that is all that is between them.
		RewardModel a = new RewardModel();
		RewardModel b = new RewardModel();
		float fromJumpAway = 0f;
		float fromJumpStanding = 0f;
		float reachA = 4f;
		float reachB = 4f + 8 * 0.28f;
		for (int i = 0; i < 24; i++) {
			boolean ground = i < 8 || i >= 20;
			if (i < 8) {
				reachB -= 0.28f;
			}
			for (int which = 0; which < 2; which++) {
				StepFacts f = facts(i < 8 ? (which == 0 ? reachA : reachB) : 4f - 0.1f * (i - 8));
				f.reachAtAction = f.reachDist;
				f.forwardAxis = 1;
				f.sprintKey = i < 14;
				f.onGround = ground;
				f.inAir = !ground;
				f.falling = i >= 14 && i < 20;
				f.jumpKey = i == 8;
				f.tookOff = i == 8;
				float r = (which == 0 ? a : b).step(st, f, new Tally());
				if (i >= 9) {
					if (which == 0) {
						fromJumpAway += r;
					} else {
						fromJumpStanding += r;
					}
				}
			}
		}
		assertEquals(w.wastedJump, fromJumpAway - fromJumpStanding, 1e-4f);
		assertTrue(awayMiss[1] > standingMiss[1] - 10f);
		// It has just hit the opponent: that one is flying off from the hit, not running, and a jump after it is an ordinary one.
		RewardModel m = new RewardModel();
		Tally t = new Tally();
		hit(m, st, SPRINT, t);
		for (int i = 0; i < 10; i++) {
			StepFacts f = facts(4f);
			f.reachAtAction = 4f;
			f.forwardAxis = 1;
			f.sprintKey = true;
			f.onGround = i < 8;
			f.inAir = i >= 8;
			f.jumpKey = i == 8;
			f.tookOff = i == 8;
			m.step(st, f, t);
		}
		assertEquals(0, t.chaseJumps);
		// The duel pays for it too, at its own scale.
		assertTrue(stage("Duel").reward.chaseCrit > 0f && stage("Self-play").reward.chaseCrit > 0f);
	}
}
