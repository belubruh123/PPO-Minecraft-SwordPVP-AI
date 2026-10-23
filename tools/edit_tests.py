import io


def load(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    return s.replace('\r\n', '\n'), nl


def save(p, s, nl):
    io.open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', nl))


def rep(s, a, b, n=1):
    assert s.count(a) == n, (s.count(a), a[:90])
    return s.replace(a, b)


root = 'D:/swordai/'

p = root + 'core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java'
s, nl = load(p)
s = rep(s, '''		// A critical hit that comes a tick late is a slow hit on the ground; here that costs little.
		assertTrue(st.reward.sweep > 0f && st.reward.sweep <= 0.25f * stage("Combo escape").reward.sweep + 1e-6f);
''', '''		// A critical hit that comes a tick late is a slow hit on the ground; here that costs less. Not
		// little: it hardly jumps in a fight, and a sweep is then a hit made without the sprint behind it.
		assertTrue(st.reward.sweep >= 0.3f && st.reward.sweep <= 0.5f * stage("Combo escape").reward.sweep + 1e-6f);
''')
s = rep(s, '''		StepFacts air = facts(4f);
		air.clicked = true;
		air.chargeAtClick = 1f;
		air.inAir = true;
		assertEquals(quiet - w.airWhiff, new RewardModel().step(st, air, t), 1e-6f, "in the air it is not");
		assertEquals(1, t.airSwings);
		assertEquals(1, t.airWhiffs);
''', '''		StepFacts air = facts(4f);
		air.reachAtAction = 4f;
		air.clicked = true;
		air.chargeAtClick = 1f;
		air.inAir = true;
		assertEquals(quiet - w.airWhiff, new RewardModel().step(st, air, t), 1e-6f, "in the air it is not");
		assertEquals(1, t.airSwings);
		assertEquals(1, t.airWhiffs);
''')
s = rep(s, '''		assertEquals(2, t.airSwings);
		assertEquals(1, t.airWhiffs);
		assertEquals(0.5f, t.airWhiffRate(), 1e-6f);
	}
''', '''		assertEquals(2, t.airSwings);
		assertEquals(1, t.airWhiffs);
		assertEquals(0.5f, t.airWhiffRate(), 1e-6f);

		// And one made at an opponent at the edge of its reach, or a step beyond it, is an attempt. It is
		// counted as a miss and costs the sword's charge, nothing else: that is where an opponent stands
		// that is comboing it, and a person there is half a step too close every few swings. Charged for
		// each of these, it did not swing back at all.
		for (String name : new String[] {"Crits", "Duel", "Self-play"}) {
			StageSpec at = stage(name);
			assertTrue(at.reward.airWhiffBeyond > 3.2f && at.reward.airWhiffBeyond < 4f, name);
			for (float reach : new float[] {3.05f, at.reward.airWhiffBeyond - 0.05f}) {
				StepFacts attempt = facts(reach);
				attempt.reachAtAction = reach;
				attempt.clicked = true;
				attempt.chargeAtClick = 1f;
				attempt.inAir = true;
				Tally tried = new Tally();
				assertEquals(new RewardModel().step(at, facts(reach), new Tally()), new RewardModel().step(at, attempt, tried), 1e-6f, name + " at " + reach);
				assertEquals(1, tried.airWhiffs, name);
			}
			StepFacts hopeless = facts(at.reward.airWhiffBeyond + 0.2f);
			hopeless.reachAtAction = at.reward.airWhiffBeyond + 0.2f;
			hopeless.clicked = true;
			hopeless.chargeAtClick = 1f;
			hopeless.inAir = true;
			assertEquals(at.reward.airWhiff, new RewardModel().step(at, facts(at.reward.airWhiffBeyond + 0.2f), new Tally()) - new RewardModel().step(at, hopeless, new Tally()), 1e-6f, name);
			// An answer to a hit is paid for from the same distance.
			assertEquals(at.reward.airWhiffBeyond, at.reward.answerWithin, 0f, name);
			assertTrue(at.reward.answer > 0f, name);
		}
		// The stand-in rule: with no distance given, every miss in the air costs.
		RewardWeights plain = new RewardWeights();
		assertEquals(0f, plain.airWhiffBeyond);
	}
''')
save(p, s, nl)

p = root + 'core/src/test/java/dev/swordai/core/curriculum/FightRulesTest.java'
s, nl = load(p)
s = rep(s, '''		assertTrue(cfg.swingMinCharge > 0.9f && cfg.swingMinCharge < 1f, "a tick after the game would allow it, and not a tick more");
''', '''		assertTrue(cfg.swingMinCharge > 0.9f && cfg.swingMinCharge < 1f, "a tick after the game would allow it, and not a tick more");
		assertEquals(1f, cfg.swingMinChargeOnline, "on a server, one tick in hand: the weapon all the way back");
''')
i = s.rindex('}')
s = s[:i] + '''
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
}
'''
save(p, s, nl)

p = root + 'core/src/test/java/dev/swordai/core/rl/FighterTest.java'
s, nl = load(p)
print(s[s.index('	void theWeaponIsNeverSwungBeforeItIsBack'):][:1500])
