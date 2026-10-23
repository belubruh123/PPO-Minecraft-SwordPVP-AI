ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/ObsSpec.java', [
    ('''	public static final int SC_HITS_TAKEN_ROW = 25;
	public static final int SCALARS = 26;''', '''	public static final int SC_HITS_TAKEN_ROW = 25;
	/**
	 * How far back the opponent's sword is, as far as can be told from the last time its arm was seen
	 * to swing: 0 just after a swing, 1 once a sword would be back (twelve and a half ticks), and 1 if
	 * it has not been seen to swing. A player in a real fight sees every swing of the other's arm,
	 * misses too, and judges the distance by it: out of reach while that sword is back, in again while
	 * it is not. Since 18 October; see {@code BodyState.noteOpponentSwing}.
	 */
	public static final int SC_OPP_CHARGE = 26;
	public static final int SCALARS = 27;''', 1),
    ('''	 * Scalar counts of earlier versions (0.1.0, 0.1.1 to 0.1.2, 0.1.3 to 0.1.4, and 0.2.0 to 0.6.1). New''',
     '''	 * Scalar counts of earlier versions (0.1.0, 0.1.1 to 0.1.2, 0.1.3 to 0.1.4, 0.2.0 to 0.6.1, and up to
	 * 18 October 2026 in 0.14.0). New''', 1),
    ('''	public static final int[] SCALARS_BEFORE = {16, 21, 23, 25};''', '''	public static final int[] SCALARS_BEFORE = {16, 21, 23, 25, 26};''', 1),
])
edit('core/src/main/java/dev/swordai/core/env/BodyState.java', [
    ('''	public int ticksSinceHitTaken = 1000;
''', '''	public int ticksSinceHitTaken = 1000;
	/** Ticks since the opponent's arm was last seen to swing; see {@code ObsSpec.SC_OPP_CHARGE}. */
	public int oppTicksSinceSwing = 1000;
	private boolean oppWasSwinging;
	private int oppLastSwingTime;
''', 1),
    ('''	/**
	 * Writes the body scalars into an observation vector.''', '''	/** A sword is back this many ticks after a swing. */
	public static final float SWORD_TICKS = 12.5f;

	/**
	 * One tick of watching the opponent's arm: {@code swinging} and {@code swingTime} as the game has
	 * them for that player. A swing has begun when the arm starts swinging, or starts over.
	 */
	public void noteOpponentSwing(boolean swinging, int swingTime) {
		boolean began = swinging && (!oppWasSwinging || swingTime < oppLastSwingTime);
		oppTicksSinceSwing = began ? 0 : Math.min(oppTicksSinceSwing + 1, 1000);
		oppWasSwinging = swinging;
		oppLastSwingTime = swingTime;
	}

	/** A new fight: nothing is known of the opponent's sword. */
	public void resetOpponentSwing() {
		oppTicksSinceSwing = 1000;
		oppWasSwinging = false;
		oppLastSwingTime = 0;
	}

	/** The opponent's sword, 0 to 1, as {@code ObsSpec.SC_OPP_CHARGE} has it. */
	public float opponentCharge() {
		return Math.min(1f, oppTicksSinceSwing / SWORD_TICKS);
	}

	/**
	 * Writes the body scalars into an observation vector.''', 1),
    ('''		ObsBuilder.writeScalar(v, ObsSpec.SC_HITS_TAKEN_ROW, hitsTakenInRow);
''', '''		ObsBuilder.writeScalar(v, ObsSpec.SC_HITS_TAKEN_ROW, hitsTakenInRow);
		ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_CHARGE, opponentCharge());
''', 1),
])
edit('src/client/java/dev/swordai/client/Observer.java', [
    ('''	void resetEpisode() {
		sight.reset();
	}''', '''	void resetEpisode() {
		sight.reset();
		body.resetOpponentSwing();
	}''', 1),
    ('''		body.charge = me.getAttackStrengthScale(0f);
		body.setHealth(me.getHealth() / Math.max(1f, me.getMaxHealth()));''', '''		body.charge = me.getAttackStrengthScale(0f);
		body.noteOpponentSwing(opponent.swinging, opponent.swingTime);
		body.setHealth(me.getHealth() / Math.max(1f, me.getMaxHealth()));''', 1),
])
edit('src/client/java/dev/swordai/client/ReplicaTrainer.java', [
    ('''		BodyState body = replica.state();
		body.ticksSinceHitDealt = clock.ticksSinceHitDealt;''', '''		BodyState body = replica.state();
		body.noteOpponentSwing(bot.swinging, bot.swingTime);
		body.ticksSinceHitDealt = clock.ticksSinceHitDealt;''', 1),
])
print('oppsword ok')

L = 'core/src/test/java/dev/swordai/core/rl/LearnerTest.java'
edit(L, [
    ('''		assertEquals(ObsSpec.SCALARS - 1, ObsSpec.SC_HITS_TAKEN_ROW, "the newest scalar is the last one");''',
     '''		assertEquals(ObsSpec.SCALARS - 1, ObsSpec.SC_OPP_CHARGE, "the newest scalar is the last one");''', 1),
    ('''		int slot = ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW;''', '''		int slot = ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE;''', 1),
    ('''				// With the new input saying it is in a combo, W/S is the escape model's, which is new;
				// every other key is pressed as before.
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					if (h != ActionSpace.FORWARD) {
						assertEquals(before[i][h], after[h], "probe " + i + " with the new input set, " + ActionSpace.NAMES[h]);
					}
				}''', '''				assertArrayEquals(before[i], after, "probe " + i + " with the new input set");''', 1),
])
B = 'core/src/test/java/dev/swordai/core/env/BodyStateTest.java'
s = open(ROOT + B, encoding='utf-8').read()
i = s.rstrip().rfind('}')
s = s[:i] + '''
	/** The opponent's sword, as told by its arm: back twelve and a half ticks after a swing, and back if it was never seen to swing. */
	@Test
	void theOpponentsSwordIsReadOffItsArm() {
		BodyState b = new BodyState();
		assertEquals(1f, b.opponentCharge(), "never seen to swing");
		b.noteOpponentSwing(true, -1);
		assertEquals(0f, b.opponentCharge(), "it has just swung");
		for (int t = 0; t < 5; t++) {
			b.noteOpponentSwing(true, t);
		}
		assertEquals(5f / 12.5f, b.opponentCharge(), 1e-6f, "the swing going on is the same one");
		b.noteOpponentSwing(false, 0);
		b.noteOpponentSwing(false, 0);
		assertEquals(7f / 12.5f, b.opponentCharge(), 1e-6f);
		b.noteOpponentSwing(true, -1);
		assertEquals(0f, b.opponentCharge(), "a new one");
		b.noteOpponentSwing(true, 0);
		b.noteOpponentSwing(true, 1);
		b.noteOpponentSwing(true, -1);
		assertEquals(0f, b.opponentCharge(), "started over before the last one was done");
		for (int t = 0; t < 20; t++) {
			b.noteOpponentSwing(false, 0);
		}
		assertEquals(1f, b.opponentCharge());
		b.resetOpponentSwing();
		assertEquals(1f, b.opponentCharge());
	}
}
'''
open(ROOT + B, 'w', encoding='utf-8', newline='').write(s)
print('tests ok')
