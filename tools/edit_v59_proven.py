ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/FallWatch.java', [
    (''' * <p>Read from what the network is shown. Not thread-safe; one per fighter.
 */''', ''' * <p>And it remembers one thing more ({@link #proven}): whether the last such fall on which it had
 * its sword back ended in a hit of its own, made in the air. That is an opponent a punish crit has
 * been made on, not only one that looked near enough for it. Against that one the swing is kept
 * for the way down even in the middle of its combo ({@link AnswerRule#when}); the first fall that
 * comes to nothing with the sword back, and it is no longer.
 *
 * <p>Read from what the network is shown. Not thread-safe; one per fighter.
 */''', 1),
    ('''	private boolean sawNear;
	private boolean lastNear;
''', '''	private boolean sawNear;
	/** On the way down the opponent was in reach with the sword back: a punish crit was there to be made. */
	private boolean sawReady;
	private boolean lastNear;
	private boolean proven;
''', 1),
    ('''		sawNear = false;
		lastNear = false;
	}
''', '''		sawNear = false;
		sawReady = false;
		lastNear = false;
		proven = false;
	}
''', 1),
    ('''			watching = true;
			fell = false;
			sawNear = false;
			return;
		}
		if (!watching) {
			return;
		}
		if (!ground) {
			if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] < 0f) {
				fell = true;
				sawNear |= JumpIn.distance(vec) <= NEAR;
			}
		} else if (fell || sinceTaken <= 1f - 6f / BodyState.EVENT_FADE_TICKS) {
			// Down again (or it was never thrown up at all).
			lastNear = fell && sawNear;
			watching = false;
		}
''', '''			watching = true;
			fell = false;
			sawNear = false;
			sawReady = false;
			return;
		}
		if (!watching) {
			return;
		}
		if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] >= 1f - 1.5f / BodyState.EVENT_FADE_TICKS) {
			// It has just landed a hit of its own. Made on the way down, that was the punish crit:
			// this opponent can be punished. (Made from the ground, on the tick it was hit, it says nothing.)
			if (fell) {
				proven = true;
				lastNear = true;
			}
			watching = false;
			return;
		}
		if (!ground) {
			if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] < 0f) {
				fell = true;
				boolean near = JumpIn.distance(vec) <= NEAR;
				sawNear |= near;
				sawReady |= near && vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] >= SwingGate.MIN_CHARGE;
			}
		} else if (fell || sinceTaken <= 1f - 6f / BodyState.EVENT_FADE_TICKS) {
			// Down again (or it was never thrown up at all).
			lastNear = fell && sawNear;
			if (fell && sawReady) {
				// The sword was back, the opponent was there, and nothing came of it.
				proven = false;
			}
			watching = false;
		}
''', 1),
    ('''	/** Whether the opponent was in reach on the way down from its last hit. */
	public boolean punishable() {
		return lastNear;
	}
''', '''	/** Whether the opponent was in reach on the way down from its last hit. */
	public boolean punishable() {
		return lastNear || proven;
	}

	/** Whether the last fall on which it had its sword back ended in a hit of its own, made in the air. */
	public boolean proven() {
		return proven;
	}
''', 1),
])

edit('core/src/main/java/dev/swordai/core/rl/AnswerRule.java', [
    ('''	 * <p>Keeping the swing is for the first hit only. Hit a second time in a row without having hit
	 * back, it is being comboed, and then the hit that is there is the hit that is made: now, on the
	 * ground or on the way up, critical or not. What it is held to first is that no combo goes by
	 * without a swing back, and a swing kept for a fall on which the opponent is then out of reach is
	 * a combo sat through. (With the swing kept whenever the last fall had the opponent near, one
	 * combo in seven went by without a swing, where it had been one in twenty-five.)
	 */
	public static int when(float[] vec, boolean punishable) {
		if (BackNudge.inCombo(vec)) {
			return NOW;
		}
''', '''	 * <p>Keeping the swing is for the first hit. Hit a second time in a row without having hit
	 * back, it is being comboed, and then the hit that is there is the hit that is made: now, on the
	 * ground or on the way up, critical or not. What it is held to first is that no combo goes by
	 * without a swing back, and a swing kept for a fall on which the opponent is then out of reach is
	 * a combo sat through. (With the swing kept whenever the last fall had the opponent near, one
	 * combo in seven went by without a swing, where it had been one in twenty-five.)
	 *
	 * <p>Unless a punish crit has been made on this opponent: the last fall on which the sword was
	 * back ended in one ({@link FallWatch#proven}). Then the fall is where the next one is made too,
	 * second hit or not. (Without that, against the opponents that stay on top of it and make
	 * critical hits, a fall with the sword still on its way back was followed by a plain hit made on
	 * the way up of the next: three falls in four ending in a critical hit became six in ten.)
	 *
	 * @param punishable the opponent was in reach on the way down the last time
	 * @param proven     and a punish crit has been made on it
	 */
	public static int when(float[] vec, boolean punishable, boolean proven) {
		if (BackNudge.inCombo(vec) && !proven) {
			return NOW;
		}
''', 1),
])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''AnswerRule.when(obs.vec, fallWatch.punishable())''', '''AnswerRule.when(obs.vec, fallWatch.punishable(), fallWatch.proven())''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''AnswerRule.when(obs.vec, punishMemory.punishable())''', '''AnswerRule.when(obs.vec, punishMemory.punishable(), punishMemory.proven())''', 1),
])

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
s = s.replace('AnswerRule.when(hit, watch.punishable())', 'AnswerRule.when(hit, watch.punishable(), watch.proven())')
s = s.replace('AnswerRule.when(still, watch.punishable())', 'AnswerRule.when(still, watch.punishable(), watch.proven())')
a = '''		assertEquals(AnswerRule.NOW, AnswerRule.when(second, true));
		float[] up = air(2.0f, 0.3f, true, 1f);
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		assertEquals(AnswerRule.NOW, AnswerRule.when(up, true));
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertFalse(BackNudge.inCombo(up));
		assertEquals(AnswerRule.KEEP, AnswerRule.when(up, true), "after one hit it is kept for the way down");
'''
assert s.count(a) == 1
b = '''		assertEquals(AnswerRule.NOW, AnswerRule.when(second, true, false));
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
'''
s = s.replace(a, b)
s = s.replace('''		assertEquals(AnswerRule.NOW, AnswerRule.when(hit, watch.punishable(), watch.proven()), "the first time it trades");''',
              '''		assertEquals(AnswerRule.NOW, AnswerRule.when(hit, watch.punishable(), watch.proven()), "the first time it trades");''')
open(p, 'w', encoding='utf-8', newline='').write(s)

p = ROOT + 'README.md'
s = open(p, encoding='utf-8').read()
a = '''      regardless, one combo in seven went by like that. (Made from the ground a moment before the'''
assert s.count(a) == 1
s = s.replace(a, '''      regardless, one combo in seven went by like that. The exception is an opponent a punish
      crit has been made on: if its last fall with the sword back ended in one, the next is made
      on the way down too, second hit or not, and the first such fall that comes to nothing ends
      the exception. (Made from the ground a moment before the''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
