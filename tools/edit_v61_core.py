ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


# ---- JumpIn: the chase jump as the next hit of its own combo ------------------------------------
edit('core/src/main/java/dev/swordai/core/rl/JumpIn.java', [
    ('''	static final int CHASE_AFTER_HIT_TICKS = 15;

	private final boolean chase;''', '''	/**
	 * A jump after an opponent that is running is not made sooner than this after a hit of its own:
	 * an opponent that a hit has just thrown back is going away fast too, and is no runner.
	 */
	static final int CHASE_AFTER_HIT_TICKS = 15;

	private final boolean chase;
	/** See {@link #chaseInCombo}. */
	private boolean inCombo = true;''', 1),
    ('''	public void reset() {
		kind = NONE;''', '''	/**
	 * Whether the chase jump is also the next hit of a combo of its own: it has just landed a hit,
	 * the opponent is beyond its reach and backing off, and a run would catch it only slowly. Then
	 * the jump is made as soon as the way down will bring the opponent into reach, and the hit is a
	 * critical one; and from there again. Against a player who backs out of combos with S that is a
	 * combo of critical hits, and it is what the players of the first evening of 0.14.0 did to this
	 * fighter. False: no jump at an opponent it has hit in the last fifteen ticks, as before.
	 */
	public void chaseInCombo(boolean on) {
		inCombo = on;
	}

	public void reset() {
		kind = NONE;''', 1),
    ('''		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& closing <= closingMost && sinceDealt <= fade(CHASE_AFTER_HIT_TICKS) && d >= CHASE_FROM && chaseOff <= 0
				&& (d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))
						|| closing <= PURSUIT_CLOSING_MOST && away >= PURSUIT_SPEED && d <= PURSUIT_TO)) {''', '''		boolean longAfterHit = sinceDealt <= fade(CHASE_AFTER_HIT_TICKS);
		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& closing <= closingMost && d >= CHASE_FROM && chaseOff <= 0
				&& ((inCombo || longAfterHit) && d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))
						|| longAfterHit && closing <= PURSUIT_CLOSING_MOST && away >= PURSUIT_SPEED && d <= PURSUIT_TO)) {''', 1),
])

# ---- Fighter ------------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final FallWatch fallWatch = new FallWatch();
''', '''	private final FallWatch fallWatch = new FallWatch();
	/** The jump made for it on a hit of a combo it is taking; see {@link JumpReset}. Null where that is not a rule. */
	private final JumpReset jumpReset;
	/** The step back after a hit of its own that has not sent the opponent away; see {@link StepBack}. */
	private final StepBack stepBack;
''', 1),
    ('''				? new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, cfg.chaseClosingMost, seed ^ 0x5eedL) : null;
''', '''				? new JumpIn(cfg.chaseRule, cfg.faceRule, cfg.uppercutChance, cfg.chaseClosingMost, seed ^ 0x5eedL) : null;
		if (jumpIn != null) {
			jumpIn.chaseInCombo(cfg.chaseInCombo);
		}
		this.jumpReset = jumps && cfg.jumpResetChance > 0f && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				? new JumpReset(cfg.jumpResetChance, seed ^ 0x7e5e7L) : null;
		this.stepBack = cfg.stepBackRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD) && ActionSpace.unlocked(headMask, ActionSpace.SPRINT)
				? new StepBack() : null;
''', 1),
    ('''		fallWatch.reset();
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', '''		fallWatch.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		if (stepBack != null) {
			stepBack.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', 1),
    ('''		boolean backOut = backOutRule && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		// The jumps that are made for it, and its keys in the air of them; see JumpIn.
		int jump = jumpIn == null ? 0 : jumpIn.plan(obs.vec, keys, crosshairOnOpponent, owes || backOut, dealtRow);
		keys = JumpIn.keys(keys, jump);
''', '''		// A hit of a combo it is taking: it jumps with it, W and the sprint key down; see JumpReset.
		int reset = jumpReset == null ? 0 : jumpReset.plan(obs.vec, keys);
		// Where it does not, S is held in the ticks after that hit; see Config.backRule.
		boolean backOut = backOutRule && reset == 0 && ActionSpace.unlocked(keys, ActionSpace.FORWARD) && BackNudge.applies(obs.vec);
		if (backOut) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD);
		}
		if (letGo) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
		keys = JumpIn.keys(keys, reset);
		// After a hit of its own that has not sent the opponent away it steps back; see StepBack.
		int step = stepBack == null ? 0 : stepBack.plan(obs.vec);
		boolean busy = owes || backOut || reset != 0;
		// The jumps that are made for it, and its keys in the air of them; see JumpIn.
		int jump = jumpIn == null ? 0 : jumpIn.plan(obs.vec, keys, crosshairOnOpponent, busy, dealtRow);
		keys = JumpIn.keys(keys, jump);
		if (busy || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE
				|| !ActionSpace.unlocked(keys, ActionSpace.FORWARD) || !ActionSpace.unlocked(keys, ActionSpace.SPRINT)) {
			step = 0;
		}
		if (step != 0) {
			keys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
		}
''', 1),
    ('''		if (keep) {
			action[ActionSpace.ATTACK] = 0;
		}
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		JumpIn.apply(action, jump);''', '''		if (keep) {
			action[ActionSpace.ATTACK] = 0;
		}
		JumpIn.apply(action, reset);
		if (letGo) {
			action[ActionSpace.FORWARD] = 0;
			action[ActionSpace.SPRINT] = 0;
		}
		if (backOut) {
			action[ActionSpace.FORWARD] = BackNudge.BACK;
		}
		if (step != 0) {
			action[ActionSpace.FORWARD] = step == StepBack.BACK ? BackNudge.BACK : 0;
			action[ActionSpace.SPRINT] = 0;
		}
		JumpIn.apply(action, jump);''', 1),
])

# ---- Config -------------------------------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('public static final int CURRENT_VERSION = 60;', 'public static final int CURRENT_VERSION = 61;', 1),
    ('''	public boolean backRule = true;
''', '''	public boolean backRule = true;
	/**
	 * The share of the hits of a combo it is taking (the second in a row and every later one) that it
	 * jumps with, W and the sprint key down, on the tick it learns of the hit: a jump reset, which
	 * halves how far the hit throws it, so that it comes down with the opponent in its reach and a
	 * swing to make; see {@code JumpReset}. On the rest, and on a hit that finds it in the air, S is
	 * held as {@link #backRule} has it. Never on the first hit. 0: never, which is how it was up to
	 * the first evening against people, when backing off with S was all it did about a combo and a
	 * player who came after it with a running jump and a critical hit had it for as long as he liked.
	 */
	public float jumpResetChance = 1f;
	/**
	 * After a hit of its own that has not sent the opponent away (part of the knockback, or none), it
	 * walks backwards out of that opponent's reach until its sword is nearly back; see
	 * {@code StepBack}. What it keeps clear of is the critical hit such an opponent makes on its way
	 * down, which there is no swing to answer with.
	 */
	public boolean stepBackRule = true;
	/**
	 * The chase jump ({@link #chaseRule}) is also the next hit of a combo of its own, at an opponent
	 * that backs off after being hit; see {@code JumpIn.chaseInCombo}.
	 */
	public boolean chaseInCombo = true;
''', 1),
])

# ---- BotProfile, StageSpec, Stages --------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/curriculum/BotProfile.java', [
    ('''	/** Something odd it does all fight long; one of the {@code ANTIC_} names, or empty. */
	public String antic = "";
''', '''	/**
	 * Its jumps for a critical hit are running jumps, taken from as far off as the way down will
	 * bring the agent into its reach: up to five and a half blocks from an agent that stands, four
	 * from one that is backing off. It sprints up to the top of the jump, lets go of the sprint and
	 * swings on the way down, and is off the ground again as soon as its sword is far enough back.
	 * What a player does to one that backs out of combos with S: every hit after the first is a
	 * critical one, and backing off only makes room for the next jump.
	 */
	public boolean jumpIn = false;
	/**
	 * Hit by the agent, it keeps its swing for its own way down and makes it a critical hit: the
	 * punish crit. Where it takes the full knockback the agent is out of its reach by then; where it
	 * takes part of it (as on a server that hands out less), the agent is right there with its sword
	 * on the way back.
	 */
	public boolean punish = false;
	/** Something odd it does all fight long; one of the {@code ANTIC_} names, or empty. */
	public String antic = "";
''', 1),
    ('''		b.holdGround = holdGround;
		b.antic = antic;''', '''		b.holdGround = holdGround;
		b.jumpIn = jumpIn;
		b.punish = punish;
		b.antic = antic;''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float cornerShare = 0f;
	public float noKnockShare = 0f;
	public float oddShare = 0f;
''', '''	public float cornerShare = 0f;
	public float noKnockShare = 0f;
	public float oddShare = 0f;
	/**
	 * And against the two that beat it on the first evening against people: one that comes after it
	 * with a running jump and a critical hit whenever it backs off ({@code BotProfile.jumpIn}), and
	 * one that takes part of the knockback and answers each of its hits with a critical hit made on
	 * the way down ({@code BotProfile.punish}).
	 */
	public float jumperShare = 0f;
	public float punisherShare = 0f;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''		st.traderShare = 0.12f;
''', '''		st.traderShare = 0.1f;
''', 1),
    ('''		st.runnerShare = 0.2f;
		st.circlerShare = 0.15f;
''', '''		st.runnerShare = 0.14f;
		st.circlerShare = 0.12f;
''', 1),
    ('''		st.cornerShare = 0.1f;
		st.noKnockShare = 0.12f;
		st.oddShare = 0.13f;
''', '''		st.cornerShare = 0.08f;
		st.noKnockShare = 0.1f;
		st.oddShare = 0.1f;
		// And the two it lost to on the first evening against people; see StageSpec.jumperShare.
		st.jumperShare = 0.15f;
		st.punisherShare = 0.12f;
''', 1),
])

# ---- StepFacts, Tally, RewardModel --------------------------------------------------------------
edit('core/src/main/java/dev/swordai/core/env/StepFacts.java', [
    ('''	public int ruleJump;
''', '''	public int ruleJump;
	/** On the step that is being scored, W and S were the rule's that steps back after a hit of its own; see {@code StepBack}. */
	public boolean stepBack;
''', 1),
    ('''		ruleJump = 0;
''', '''		ruleJump = 0;
		stepBack = false;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/Tally.java', [
    ('''	public long ruleJumpsHit;
''', '''	public long ruleJumpsHit;
	/** Jumps made with a hit of a combo it was taking; see {@code JumpReset}. */
	public long resetJumps;
	/** Ticks on which it was stepping back after a hit of its own; see {@code StepBack}. */
	public long stepBackTicks;
	/** Hits taken four to thirteen ticks after a hit of its own: its sword on the way back and nothing to answer with. */
	public long counterHits;
''', 1),
    ('''		ruleJumpsHit += t.ruleJumpsHit;
''', '''		ruleJumpsHit += t.ruleJumpsHit;
		resetJumps += t.resetJumps;
		stepBackTicks += t.stepBackTicks;
		counterHits += t.counterHits;
''', 2),
    ('''		ruleJumpsHit = 0;
''', '''		ruleJumpsHit = 0;
		resetJumps = 0;
		stepBackTicks = 0;
		counterHits = 0;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''			} else if (jumpRule == JumpIn.UPPER) {
				tally.upperRuleJumps++;
			}
''', '''			} else if (jumpRule == JumpIn.UPPER) {
				tally.upperRuleJumps++;
			} else if (jumpRule == JumpReset.KIND) {
				tally.resetJumps++;
			}
''', 1),
    ('''			if (jumpAge >= 0 && jumpRule != 0) {
				// Hit in the air of a jump a rule made for it.
				tally.ruleJumpsHit++;
			}
''', '''			if (jumpAge >= 0 && jumpRule != 0 && jumpRule != JumpReset.KIND) {
				// Hit in the air of a jump a rule made for it.
				tally.ruleJumpsHit++;
			}
			if (ticksSinceHit >= COUNTER_FROM_TICKS && ticksSinceHit <= COUNTER_TO_TICKS) {
				tally.counterHits++;
			}
''', 1),
    ('''		if (f.jumpWindow) {
			tally.jumpWindowTicks++;''', '''		if (f.stepBack) {
			tally.stepBackTicks++;
		}
		if (f.jumpWindow) {
			tally.jumpWindowTicks++;''', 1),
    ('''	private int ticksSinceHit = Integer.MAX_VALUE / 2;
''', '''	private int ticksSinceHit = Integer.MAX_VALUE / 2;
	/** A hit taken this long after a hit of its own found it with its sword on the way back; see {@code Tally.counterHits}. */
	private static final int COUNTER_FROM_TICKS = 4;
	private static final int COUNTER_TO_TICKS = 13;
''', 1),
])
s = open(ROOT + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', encoding='utf-8').read()
if 'import dev.swordai.core.rl.JumpReset;' not in s:
    a = 'import dev.swordai.core.rl.JumpIn;\n'
    assert s.count(a) == 1
    s = s.replace(a, a + 'import dev.swordai.core.rl.JumpReset;\n')
    open(ROOT + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', 'w', encoding='utf-8', newline='').write(s)
print('core ok')
