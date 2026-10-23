ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''		if (stepBack != null) {
			stepBack.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', '''		if (stepBack != null) {
			// The opponent of the next round is the one of the last.
			stepBack.newRound();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', 1),
])
T = 'src/client/java/dev/swordai/client/Trainer.java'
s = open(ROOT + T, encoding='utf-8').read()
a = '''			jumpIn = jumpInVsCopy;
			jumpIn.newRound();
		} else {'''
assert s.count(a) == 1
s = s.replace(a, '''			jumpIn = jumpInVsCopy;
			jumpIn.newRound();
			stepBack.newRound();
		} else {
			stepBack.reset();''')
a = '''		punishMemory.reset();
		stepBack.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		drillJumpOutOfZone = false;'''
assert s.count(a) == 1
s = s.replace(a, '''		punishMemory.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		drillJumpOutOfZone = false;''')
a = '''		punishMemory.reset();
		stepBack.reset();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		episodeSlow = false;'''
assert s.count(a) == 1
s = s.replace(a, '''		punishMemory.reset();
		stepBack.newRound();
		if (jumpReset != null) {
			jumpReset.reset();
		}
		episodeSlow = false;''')
open(ROOT + T, 'w', encoding='utf-8', newline='').write(s)

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		assertEquals(0, hit.plan(after(vec(true, 2.8f, 0.24f), 3, 0)));
		assertFalse(hit.on());
		assertEquals(0, hit.plan(after(vec(true, 2.8f, 0.3f), 4, 1)));
	}
'''
assert s.count(a) == 1
s = s.replace(a, '''		assertEquals(0, hit.plan(after(vec(true, 2.8f, 0.24f), 3, 0)));
		assertFalse(hit.on());
		assertEquals(0, hit.plan(after(vec(true, 2.8f, 0.3f), 4, 1)));
		// And that was an opponent answering its hit on the way down: from the next hit it lands it is
		// on its way out at once, before there is anything to read.
		assertTrue(hit.wary());
		for (int tick = 2; tick < 30; tick++) {
			hit.plan(after(vec(true, 3.0f, 1f), 100, tick));
		}
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.7f, 0f), 0, 30)));
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.8f, 0.08f), 1, 31)));
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 3.3f, 0.16f), 2, 32)));
		// A new round against the same opponent: still wary. Another opponent: not.
		hit.newRound();
		assertTrue(hit.wary());
		assertEquals(StepBack.BACK, hit.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
		// If the hit turns out to have sent the opponent flying, it goes after it.
		hit.plan(after(vec(true, 3.0f, 0.08f), 1, 1000));
		float[] flying = after(vec(true, 3.7f, 0.16f), 2, 1000);
		flying[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.6f * BodyState.VELOCITY_SCALE;
		assertEquals(0, hit.plan(flying));
		assertFalse(hit.on());
		assertEquals(0, hit.plan(after(vec(true, 4.0f, 0.24f), 3, 1000)));
		hit.reset();
		assertFalse(hit.wary());
		assertEquals(0, hit.plan(after(vec(true, 2.7f, 0f), 0, 1000)));
	}
''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('wary ok')
