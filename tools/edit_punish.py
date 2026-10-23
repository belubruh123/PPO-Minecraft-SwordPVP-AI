import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/AnswerRule.java', [
    ('''	/** Whether the fighter has been hit and has not hit back since, as it can tell itself. */
''',
     '''	/** What the rule does with the swing it owes: makes it now. */
	public static final int NOW = 0;
	/** Keeps it: no swing on this tick. */
	public static final int KEEP = 1;
	/** Keeps it, and lets go of W and of the sprint key on this tick, so that the next is a critical hit. */
	public static final int LET_GO = 2;

	/**
	 * When the swing it owes is made, with the opponent under its crosshair. On the ground: now. In
	 * the air it is kept for the way down, where it is a critical hit, the punish crit: half as much
	 * damage again. A hit throws a fighter up for four or five ticks and it comes down for as many;
	 * on the way up the swing is not made, on the first tick of the way down the sprint is let go of
	 * (the game makes no critical hit of a sprinting player's swing, and hears of the sprint ending at
	 * the end of that tick), and on the next the swing is made.
	 *
	 * <p>Up to the first fights of 0.14.0 against a person the swing was made the moment the rule
	 * applied, which in the air is on the way up: a hit, and the sword gone for the fall. The punish
	 * crit was then made only where the rule had not applied first, and a player who goes round the
	 * fighter making critical hits, each of which leaves it in the air with that player in reach, got
	 * plain hits back for them.
	 */
	public static int inTheAir(float[] vec) {
		if (!JumpNudge.inAir(vec)) {
			return NOW;
		}
		if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] >= 0f) {
			return KEEP;
		}
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] >= 0.5f ? LET_GO : NOW;
	}

	/** Whether the fighter has been hit and has not hit back since, as it can tell itself. */
''', 1),
])

edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean answerRule = true;
''',
     '''	public boolean answerRule = true;
	/**
	 * In the air, the swing that rule makes is kept for the way down and made as a critical hit, the
	 * punish crit; see {@code AnswerRule.inTheAir}. False makes it the moment the rule applies.
	 */
	public boolean punishRule = true;
''', 1),
])
p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 58', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final boolean answerRule;
''',
     '''	private final boolean answerRule;
	private final boolean punishRule;
''', 1),
    ('''		this.answerRule = cfg.answerRule;
''',
     '''		this.answerRule = cfg.answerRule;
		this.punishRule = cfg.punishRule;
''', 1),
    ('''		boolean answer = answerRule && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent);
		answerForced = answer;
		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		boolean backOut = backOutRule && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		learner.act(obs, keys, false, false, temperature, action);
		if (assist) {
			idea.assist(action, headMask, visible);
		}
		if (answer) {
			action[ActionSpace.ATTACK] = 1;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
''',
     '''		boolean owes = answerRule && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent);
		// In the air it is kept for the way down and made as a critical hit; see AnswerRule.inTheAir.
		int when = owes && punishRule ? AnswerRule.inTheAir(obs.vec) : AnswerRule.NOW;
		boolean answer = owes && when == AnswerRule.NOW;
		boolean keep = owes && when != AnswerRule.NOW;
		boolean letGo = owes && when == AnswerRule.LET_GO;
		answerForced = answer;
		if (owes) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		boolean backOut = backOutRule && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		learner.act(obs, keys, false, false, temperature, action);
		if (assist) {
			idea.assist(action, headMask, visible);
		}
		if (answer) {
			action[ActionSpace.ATTACK] = 1;
		}
		if (keep) {
			action[ActionSpace.ATTACK] = 0;
		}
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		boolean answer = cfg.answerRule && swingMinCharge() > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent());
		answerAtAction = answer;
		if (answer) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
''',
     '''		boolean owes = cfg.answerRule && swingMinCharge() > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent());
		// In the air it is kept for the way down and made as a critical hit; see AnswerRule.inTheAir.
		int when = owes && cfg.punishRule ? AnswerRule.inTheAir(obs.vec) : AnswerRule.NOW;
		boolean answer = owes && when == AnswerRule.NOW;
		boolean keep = owes && when != AnswerRule.NOW;
		boolean letGo = owes && when == AnswerRule.LET_GO;
		answerAtAction = answer;
		if (owes) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
''', 1),
    ('''		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
''',
     '''		if (answer) {
			action[ActionSpace.ATTACK] = 1;
			forcedAnswers++;
		}
		if (keep) {
			action[ActionSpace.ATTACK] = 0;
		}
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
''', 1),
])

# ---- test ---------------------------------------------------------------------------------------
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/AnswerRuleTest.java'
s = open(p, encoding='utf-8').read()
a = '''	private static StepFacts facts() {
'''
b = '''	private static BodyState inTheAir(float velUp, boolean sprinting) {
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
			for (int tick = 0; tick < 20; tick++) {
				assertEquals(0, fighter.decide(near, inTheAir(0.3f, true))[ActionSpace.ATTACK], "tick " + tick);
				assertFalse(fighter.answerForced());
			}
			// Coming down and still sprinting: no swing yet, and W and the sprint key are let go of.
			for (int tick = 0; tick < 20; tick++) {
				int[] action = fighter.decide(near, inTheAir(-0.2f, true));
				assertEquals(0, action[ActionSpace.ATTACK]);
				assertEquals(0, action[ActionSpace.FORWARD]);
				assertEquals(0, action[ActionSpace.SPRINT]);
				assertFalse(fighter.answerForced());
			}
			// Coming down with the sprint off: now.
			assertEquals(1, fighter.decide(near, inTheAir(-0.2f, false))[ActionSpace.ATTACK]);
			assertTrue(fighter.answerForced());
			// Where backing out of a combo is a rule as well, S stays held through it.
			fighter.backOutRule(true);
			fighter.hitTaken();
			fighter.decide(near, inTheAir(0.3f, true));
			fighter.hitTaken();
			int[] action = fighter.decide(near, inTheAir(-0.2f, true));
			assertEquals(BackNudge.BACK, action[ActionSpace.FORWARD]);
			assertEquals(0, action[ActionSpace.SPRINT]);
			assertEquals(0, action[ActionSpace.ATTACK]);
			// Nothing under its crosshair: nothing is kept and nothing forced, in the air or out of it.
			fighter.crosshairOnOpponent(false);
			fighter.decide(near, inTheAir(-0.2f, false));
			assertFalse(fighter.answerForced());
		}
		assertEquals(AnswerRule.NOW, AnswerRule.inTheAir(new float[ObsSpec.VEC]) == AnswerRule.NOW ? AnswerRule.NOW : -1,
				"an empty observation has it in the air and still: read as coming to rest, not going up");
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

	private static StepFacts facts() {
'''
assert s.count(a) == 1
s = s.replace(a, b)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
