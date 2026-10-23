ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/JumpReset.java', [
    (''' * <p>Only ever in a combo, never on the first hit: a fighter that takes no hit at its full
 * knockback cannot be comboed at all, by anyone, and that is not a fight. And only this share of
 * those hits ({@code Config.jumpResetChance}).
 *''', ''' * <p>Only ever in a combo, never on the first hit: a fighter that takes no hit at its full
 * knockback cannot be comboed at all, by anyone, and that is not a fight. And only this share of
 * those hits ({@code Config.jumpResetChance}).
	 *
 * <p>With one exception ({@link #chasers}): an opponent that has followed a hit with a second one
 * made from the air, which is the running jump and the critical hit, is one that does. Against
 * that opponent, for the next twenty seconds, the first hit is jumped with as well. Its second hit
 * is on its way the moment the first has landed, and a fighter that has jumped with the first is
 * coming down on it, sword back, while it is still going up.
 *''', 1),
    ('''	static final int AIR_TICKS = 14;

	private final float chance;
	private final Random rng;
	private int left;
	private boolean airborne;
	private boolean tookOff;
''', '''	static final int AIR_TICKS = 14;
	/** Moving up or down at this much a tick or more, the opponent is off the ground. */
	static final float AIR_SPEED = 0.06f;
	/** A hit was made from the air if the opponent was seen off the ground within this many ticks before it. */
	static final int AIR_SEEN_TICKS = 6;
	/** How long an opponent that has done it is taken for one that follows its hits through the air: twenty seconds. */
	static final int CHASED_TICKS = 400;

	private final float chance;
	private final Random rng;
	private boolean chasers;
	private int left;
	private boolean airborne;
	private boolean tookOff;
	private int airSeen;
	private int chased;
''', 1),
    ('''	public void reset() {
		left = 0;
		airborne = false;
		tookOff = false;
	}
''', '''	/**
	 * Whether the first hit is jumped with too against an opponent that has lately followed a hit
	 * with one made from the air; see {@code Config.jumpResetChasers}.
	 */
	public void chasers(boolean on) {
		chasers = on;
	}

	/** Whether the opponent has lately followed a hit with one made from the air. */
	public boolean chased() {
		return chased > 0;
	}

	public void reset() {
		chased = 0;
		newRound();
	}

	/** A new round against the same opponent: what it has found out about how that one follows its hits is kept. */
	public void newRound() {
		left = 0;
		airborne = false;
		tookOff = false;
		airSeen = 0;
	}
''', 1),
    ('''		tookOff = false;
		boolean ground = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f;
		if (hitNow) {
			left = 0;
			if (chance > 0f && ground && BackNudge.inCombo(vec) && ActionSpace.unlocked(keys, ActionSpace.JUMP)''',
     '''		tookOff = false;
		boolean ground = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f;
		boolean fromAir = airSeen > 0;
		if (airSeen > 0) {
			airSeen--;
		}
		if (chased > 0) {
			chased--;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
			// The opponent's own speed up or down: what the fighter sees of it, less its own.
			float up = (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] + vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP]) / BodyState.VELOCITY_SCALE;
			if (Math.abs(up) >= AIR_SPEED) {
				airSeen = AIR_SEEN_TICKS;
			}
		}
		if (hitNow) {
			left = 0;
			boolean combo = BackNudge.inCombo(vec);
			if (combo && fromAir) {
				// A second hit in a row, made from the air: this one comes after its hits with a jump.
				chased = CHASED_TICKS;
			}
			if (chance > 0f && ground && (combo || chasers && chased > 0) && ActionSpace.unlocked(keys, ActionSpace.JUMP)''', 1),
])
edit('core/src/main/java/dev/swordai/core/rl/MeetJump.java', [
    ('''		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ON_GROUND) < 0.5f
				|| scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {
			return false;
		}''', '''		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {
			return false;
		}''', 1),
    (''' * <p>Why. A critical hit''', ''' * <p>In the air it is W and the sprint key alone (the swing there has its own rule): thrown back by
 * a hit, the way towards the opponent begins before it is down, and every tick of it counts.
 *
 * <p>Why. A critical hit''', 1),
    ('''		if (scalar(vec, ObsSpec.SC_ON_GROUND) < 0.5f) {
			return 1;
		}
		if (scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {''', '''		if (scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {''', 1),
    ('''1: there is one, but its own feet are off the ground; 2: its sword''', '''2: there is one, but its sword''', 1),
])
edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public float jumpResetChance = 1f;
''', '''	public float jumpResetChance = 1f;
	/**
	 * Against an opponent that has followed a hit with a second one made from the air in the last
	 * twenty seconds (the running jump and the critical hit), the first hit is jumped with as well;
	 * see {@code JumpReset}. False: only ever from the second hit in a row, against anyone.
	 */
	public boolean jumpResetChasers = true;
''', 1),
])
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''		this.jumpReset = jumps && cfg.jumpResetChance > 0f && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				? new JumpReset(cfg.jumpResetChance, seed ^ 0x7e5e7L) : null;
''', '''		this.jumpReset = jumps && cfg.jumpResetChance > 0f && ActionSpace.unlocked(headMask, ActionSpace.FORWARD)
				? new JumpReset(cfg.jumpResetChance, seed ^ 0x7e5e7L) : null;
		if (jumpReset != null) {
			jumpReset.chasers(cfg.jumpResetChasers);
		}
''', 1),
    ('''		if (jumpReset != null) {
			jumpReset.reset();
		}
		if (stepBack != null) {
			// The opponent of the next round is the one of the last.''', '''		if (jumpReset != null) {
			jumpReset.newRound();
		}
		if (stepBack != null) {
			// The opponent of the next round is the one of the last.''', 1),
    ('''		int when = owes && punishRule && !meet ? AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven()) : AnswerRule.NOW;''',
     '''		// On the ground the swing is made at once; in the air it stays the swing it would have been.
		boolean meetSwings = meet && body.onGround;
		int when = owes && punishRule && !meetSwings ? AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven()) : AnswerRule.NOW;''', 1),
    ('''		if (reset != 0) {
			meet = false;
		}
		// Where it does not, S is held in the ticks after that hit; see Config.backRule.''', '''		if (reset != 0 || letGo) {
			// The jump made with a hit, and the critical hit it is about to make, come first.
			meet = false;
		}
		// Where it does not, S is held in the ticks after that hit; see Config.backRule.''', 1),
    ('''		boolean meetSwing = meet && !owes && crosshairOnOpponent && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''',
     '''		boolean meetSwing = meet && meetSwings && !owes && crosshairOnOpponent && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''', 1),
])
T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''		int when = owes && cfg.punishRule && !meet ? AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven()) : AnswerRule.NOW;''',
     '''		// On the ground the swing is made at once; in the air it stays the swing it would have been.
		boolean meetGround = meet && groundAtAction;
		int when = owes && cfg.punishRule && !meetGround ? AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven()) : AnswerRule.NOW;''', 1),
    ('''		if (reset != 0) {
			meet = false;
		}
		// Where it does not, S is held for it in the ticks after that hit; see Config.backRule.''', '''		if (reset != 0 || letGo) {
			// The jump made with a hit, and the critical hit it is about to make, come first.
			meet = false;
		}
		// Where it does not, S is held for it in the ticks after that hit; see Config.backRule.''', 1),
    ('''		boolean meetSwing = meet && !owes && crosshairOnOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''',
     '''		boolean meetSwing = meet && meetGround && !owes && crosshairOnOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''', 1),
    ('''				jumpReset = new JumpReset(cfg.jumpResetChance, rng.nextLong());
''', '''				jumpReset = new JumpReset(cfg.jumpResetChance, rng.nextLong());
				jumpReset.chasers(cfg.jumpResetChasers);
''', 1),
    ('''			jumpIn = jumpInVsCopy;
			jumpIn.newRound();
			stepBack.newRound();
		} else {
			stepBack.reset();''', '''			jumpIn = jumpInVsCopy;
			jumpIn.newRound();
			stepBack.newRound();
			if (jumpReset != null) {
				jumpReset.newRound();
			}
		} else {
			stepBack.reset();
			if (jumpReset != null) {
				jumpReset.reset();
			}''', 1),
    ('''		punishMemory.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		drillJumpOutOfZone = false;''', '''		punishMemory.reset();
		drillJumpOutOfZone = false;''', 1),
    ('''		punishMemory.reset();
		stepBack.newRound();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		episodeSlow = false;''', '''		punishMemory.reset();
		stepBack.newRound();
		if (jumpReset != null) {
			jumpReset.newRound();
		}
		episodeSlow = false;''', 1),
])
edit('src/client/java/dev/swordai/client/Report.java', [
    ('''"hit a second time in a row, it jumps with %.0f%% of those hits, W held, and is thrown half as far (jumpResetChance)", cfg.jumpResetChance * 100f));''',
     '''"hit a second time in a row, it jumps with %.0f%% of those hits, W held, and is thrown half as far (jumpResetChance)%s", cfg.jumpResetChance * 100f,
				cfg.jumpResetChasers ? "; with the first hit too against an opponent that has just followed a hit with a jump and a critical hit (jumpResetChasers)" : ""));''', 1),
])

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		// At a share of the hits it is that share.
		JumpReset half = new JumpReset(0.5f, 11);'''
assert s.count(a) == 1
s = s.replace(a, '''		// An opponent that follows a hit with a second one made from the air is one that does: against
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
		// At a share of the hits it is that share.
		JumpReset half = new JumpReset(0.5f, 11);''')
a = '''		float[] inAir = v.clone();
		inAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		assertFalse(MeetJump.applies(inAir));'''
assert s.count(a) == 1
s = s.replace(a, '''		// In the air too: W and the sprint key are held from before it is down.
		float[] inAir = v.clone();
		inAir[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		assertTrue(MeetJump.applies(inAir));''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('chasers ok')
