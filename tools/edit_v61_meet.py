ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean chaseInCombo = true;
''', '''	public boolean chaseInCombo = true;
	/**
	 * An opponent that is coming at it through the air, still on its way up, is run at and hit the
	 * moment it is under the crosshair, if the sword is back; see {@code MeetJump}. A critical hit
	 * is only made on the way down, and this is the hit that gets there first.
	 */
	public boolean meetJumpRule = true;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final StepBack stepBack;
''', '''	private final StepBack stepBack;
	private final boolean meetRule;
''', 1),
    ('''		this.stepBack = cfg.stepBackRule''', '''		this.meetRule = cfg.meetJumpRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				&& ActionSpace.unlocked(headMask, ActionSpace.SPRINT) && ActionSpace.unlocked(headMask, ActionSpace.ATTACK);
		this.stepBack = cfg.stepBackRule''', 1),
    ('''		fallWatch.tick(obs.vec);
		int when = owes && punishRule ? AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven()) : AnswerRule.NOW;''',
     '''		fallWatch.tick(obs.vec);
		// An opponent coming at it through the air, still on its way up, is run at and hit at once; see MeetJump.
		boolean meet = meetRule && swingMinCharge > 0f && MeetJump.applies(obs.vec) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE);
		int when = owes && punishRule && !meet ? AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven()) : AnswerRule.NOW;''', 1),
    ('''		int reset = jumpReset == null ? 0 : jumpReset.plan(obs.vec, keys, JumpReset.hitOnThisTick(obs.vec));
		// Where it does not, S is held in the ticks after that hit; see Config.backRule.
		boolean backOut = backOutRule && reset == 0 && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);''',
     '''		int reset = jumpReset == null ? 0 : jumpReset.plan(obs.vec, keys, JumpReset.hitOnThisTick(obs.vec));
		if (reset != 0) {
			meet = false;
		}
		// Where it does not, S is held in the ticks after that hit; see Config.backRule.
		boolean backOut = backOutRule && reset == 0 && !meet && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);''', 1),
    ('''		int jump = jumpIn == null ? 0 : jumpIn.plan(obs.vec, keys, crosshairOnOpponent, busy, dealtRow);
		keys = JumpIn.keys(keys, jump);
		if (busy || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE''', '''		int jump = jumpIn == null ? 0 : jumpIn.plan(obs.vec, keys, crosshairOnOpponent, busy || meet, dealtRow);
		keys = JumpIn.keys(keys, jump);
		boolean meetSwing = meet && !owes && crosshairOnOpponent && ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		if (meet) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		if (meetSwing) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (busy || meet || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE''', 1),
    ('''		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		JumpIn.apply(action, jump);''', '''		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (meet) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 1;
		}
		if (meetSwing) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			action[ActionSpace.ATTACK] = 1;
			answerForced = true;
		}
		JumpIn.apply(action, jump);''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''import dev.swordai.core.rl.Learner;
''', '''import dev.swordai.core.rl.Learner;
import dev.swordai.core.rl.MeetJump;
''', 1),
    ('''		punishMemory.tick(obs.vec);
		int when = owes && cfg.punishRule ? AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven()) : AnswerRule.NOW;''',
     '''		punishMemory.tick(obs.vec);
		// An opponent coming at it through the air, still on its way up, is run at and hit at once; see MeetJump.
		boolean meet = cfg.meetJumpRule && cfg.answerRule && !episodeLockedDrill && swingMinCharge() > 0f && stage.unlocked(ActionSpace.JUMP)
				&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)
				&& MeetJump.applies(obs.vec) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE);
		int when = owes && cfg.punishRule && !meet ? AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven()) : AnswerRule.NOW;''', 1),
    ('''		// Where it does not, S is held for it in the ticks after that hit; see Config.backRule.
		boolean backOut = backRuleNow && reset == 0 && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);''',
     '''		if (reset != 0) {
			meet = false;
		}
		// Where it does not, S is held for it in the ticks after that hit; see Config.backRule.
		boolean backOut = backRuleNow && reset == 0 && !meet && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);''', 1),
    ('''			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0, dealtRow);''',
     '''			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0 || meet, dealtRow);''', 1),
    ('''		if (owes || backOut || reset != 0 || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE
				|| !ActionSpace.unlocked(keys, ActionSpace.FORWARD) || !ActionSpace.unlocked(keys, ActionSpace.SPRINT)) {
			step = 0;
		}''', '''		boolean meetSwing = meet && !owes && crosshairOnOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		if (meet) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
			meetTicks++;
		}
		if (meetSwing) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (owes || backOut || reset != 0 || meet || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE
				|| !ActionSpace.unlocked(keys, ActionSpace.FORWARD) || !ActionSpace.unlocked(keys, ActionSpace.SPRINT)) {
			step = 0;
		}''', 1),
    ('''		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		JumpIn.apply(action, jump);''', '''		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (meet) {
			action[ActionSpace.FORWARD] = 1;
			action[ActionSpace.SPRINT] = 1;
			if (owes && answer) {
				meetSwings++;
			}
		}
		if (meetSwing) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			action[ActionSpace.ATTACK] = 1;
			answerAtAction = true;
			forcedAnswers++;
			meetSwings++;
		}
		JumpIn.apply(action, jump);''', 1),
    ('''	private int waryBegan;
''', '''	private int waryBegan;
	/** Ticks on which it ran in at an opponent that was on its way up, and the swings made there; see {@link MeetJump}. */
	private int meetTicks;
	private int meetSwings;
''', 1),
    ('''found to answer its hits on the way down {} times",''', '''found to answer its hits on the way down {} times | ran in at an opponent on its way up on {} ticks, swings {}",''', 1),
    ('''chaseEndFar, hitNowSame, hitNowLater, hitNowAlone, waryBegan);
		waryBegan = 0;''', '''chaseEndFar, hitNowSame, hitNowLater, hitNowAlone, waryBegan, meetTicks, meetSwings);
		meetTicks = 0;
		meetSwings = 0;
		waryBegan = 0;''', 1),
])
edit('src/client/java/dev/swordai/client/Report.java', [
    ('''		add(on, cfg.stepBackRule,''', '''		add(on, cfg.meetJumpRule && cfg.answerRule, "an opponent jumping at it is run at and hit while it is still on its way up (meetJumpRule)");
		add(on, cfg.stepBackRule,''', 1),
])

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {'''
assert s.count(a) == 1
s = s.replace(a, '''	/** An opponent on its way up and coming, with the sword back and its feet on the ground: it is run at and hit. */
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
		float[] inAir = v.clone();
		inAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		assertFalse(MeetJump.applies(inAir));
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

	@Test
	void itDoesNotJumpAtAnOpponentThatIsComingInOrOneOutOfRangeOfAJump() {''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('meet ok')
