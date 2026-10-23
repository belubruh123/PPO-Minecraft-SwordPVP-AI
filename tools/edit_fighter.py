p = r'D:\swordai\core\src\main\java\dev\swordai\core\rl\Fighter.java'
s = open(p, encoding='utf-8').read()


def rep(old, new, cnt=1):
    global s
    assert s.count(old) == cnt, (old[:200], s.count(old))
    s = s.replace(old, new)


rep('''	private final boolean meetRule;
''', '''	private final boolean meetRule;
	/** No swing at an opponent coming in through the air with its sword ready; see {@link CritTrade}. Null where that is not a rule. */
	private final CritTrade critTrade;
	/** The punish crit made at once after the crit that swing was held for; see {@code Config.instantPunish}. */
	private final boolean instantPunish;
''')
rep('''		this.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f || cfg.counterCritRule || cfg.edgeCritRule || tactics)''',
    '''		this.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f || cfg.counterCritRule || cfg.edgeCritRule || cfg.clashJumpRule || tactics)''')
rep('''		this.stepBack = (cfg.stepBackRule || tactics)''', '''		this.critTrade = cfg.holdOnFallingJumper && ActionSpace.unlocked(headMask, ActionSpace.ATTACK) ? new CritTrade(cfg.holdKnockbackDelay) : null;
		this.instantPunish = cfg.instantPunish && critTrade != null && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				&& ActionSpace.unlocked(headMask, ActionSpace.SPRINT);
		this.stepBack = (cfg.stepBackRule || tactics)''')
rep('''		if (comboStrafe != null) {
			comboStrafe.reset();
		}
		if (stepBack != null) {''', '''		if (comboStrafe != null) {
			comboStrafe.reset();
		}
		if (critTrade != null) {
			critTrade.reset();
		}
		if (stepBack != null) {''')
rep('''		// An opponent coming at it through the air, still on its way up, is run at and hit at once; see MeetJump.
		boolean meet = meetRule && swingMinCharge > 0f && MeetJump.applies(obs.vec, meetWhenComing) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE);''',
    '''		// An opponent coming in through the air with its sword ready: no swing at it (its crit is let land, and punished),
		// and no running at it to knock it away, unless that is certain; see CritTrade. Followed on every decision.
		boolean holdCrit = critTrade != null && swingMinCharge > 0f && critTrade.holds(obs.vec) && ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		boolean incoming = critTrade != null && critTrade.incoming() && !critTrade.knockAway();
		// An opponent coming at it through the air, still on its way up, is run at and hit at once; see MeetJump.
		boolean meet = meetRule && swingMinCharge > 0f && MeetJump.applies(obs.vec, meetWhenComing) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE)
				&& !incoming;''')
rep('''		boolean letGo = owes && when == AnswerRule.LET_GO;
		answerForced = answer;
		// With Config.answerNudge the rule pushes the network's swing instead of pressing it.
		boolean nudged = owes && answerNudge > 0f;
		if (owes && !nudged) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}''', '''		boolean letGo = owes && when == AnswerRule.LET_GO;
		// Hit by the crit it held its swing for: the punish crit at once; see CritTrade.punish.
		int punish = CritTrade.NONE;
		if (instantPunish && critTrade.heldWithin(PUNISH_AFTER_HELD_TICKS) && AnswerRule.owesAnAnswer(obs.vec)
				&& (!BackNudge.inCombo(obs.vec) || fallWatch.proven())) {
			punish = CritTrade.punish(obs.vec);
		}
		if (punish != CritTrade.NONE) {
			answer = owes && punish == CritTrade.SWING;
			keep = owes && !answer;
			letGo = false;
		}
		if (holdCrit && punish == CritTrade.NONE) {
			answer = false;
			keep = owes;
			letGo = false;
		}
		answerForced = answer;
		// With Config.answerNudge the rule pushes the network's swing instead of pressing it.
		boolean nudged = owes && answerNudge > 0f && punish == CritTrade.NONE && !holdCrit;
		if (owes && !nudged || holdCrit || punish != CritTrade.NONE) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (punish != CritTrade.NONE) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}''')
rep('''		if (reset != 0 || letGo) {
			// The jump made with a hit, and the critical hit it is about to make, come first.
			meet = false;
		}''', '''		if (reset != 0 || letGo || punish != CritTrade.NONE) {
			// The jump made with a hit, and the critical hit it is about to make, come first.
			meet = false;
		}''')
rep('''		boolean hold = held && !owes && !backOut && reset == 0 && !dodge && !meet && !letGo''',
    '''		boolean hold = held && !owes && !backOut && reset == 0 && !dodge && !meet && !letGo && !(jumpIn != null && jumpIn.clashes() && incoming)''')
rep('''		boolean busy = owes || backOut || reset != 0 || dodge || hold;''', '''		boolean busy = owes || backOut || reset != 0 || dodge || hold || punish != CritTrade.NONE;''')
rep('''		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerForced = true;
		}
		if (action[ActionSpace.ATTACK] == 1) {''', '''		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerForced = true;
		}
		if (holdCrit && (jump & JumpIn.SWING) == 0) {
			action[ActionSpace.ATTACK] = 0;
		}
		if (punish != CritTrade.NONE) {
			action[ActionSpace.FORWARD] = punish == CritTrade.LET_GO ? 0 : 1;
			action[ActionSpace.SPRINT] = 0;
			action[ActionSpace.ATTACK] = answer ? 1 : 0;
		}
		if (action[ActionSpace.ATTACK] == 1) {''')
rep('''| (dodge ? 1 << 10 : 0) | (hold ? 1 << 11 : 0);''', '''| (dodge ? 1 << 10 : 0) | (hold ? 1 << 11 : 0)
				| (holdCrit ? 1 << 12 : 0) | (punish != CritTrade.NONE ? 1 << 13 : 0);''')
open(p, 'w', encoding='utf-8').write(s)

# JumpIn: whether the clash is on, for the edge hold
q = r'D:\swordai\core\src\main\java\dev\swordai\core\rl\JumpIn.java'
t = open(q, encoding='utf-8').read()
old = '''	public void clashJump(boolean on) {
		clash = on;
	}
'''
assert t.count(old) == 1
t = t.replace(old, old + '''
	/** Whether it makes the clash jump; see {@link #clashJump}. */
	public boolean clashes() {
		return clash;
	}
''')
open(q, 'w', encoding='utf-8').write(t)
print('ok')
