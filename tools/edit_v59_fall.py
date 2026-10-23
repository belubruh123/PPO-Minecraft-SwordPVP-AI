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
''', '''	/** For this many ticks after a hit the swing can be kept on the ground, waiting for the hit to throw the fighter up. */
	public static final int WAIT_TICKS = 2;

	/**
	 * When the swing it owes is made while its feet are on the ground. That is the tick of the hit
	 * itself, and perhaps the one after: then the hit has thrown it into the air. Made now, the swing
	 * is a trade, hit for hit. Against an opponent that was still in reach the last time it came down
	 * from a hit ({@link FallWatch}), it is kept for the way down instead, where it is a critical hit.
	 *
	 * <p>Up to the first numbers of the rules of 0.14.0 it was always made now: of the falls after a
	 * hit with the opponent in reach, a quarter began with the sword already spent on a plain hit.
	 */
	public static int onTheGround(float[] vec, boolean punishable) {
		boolean justHit = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN]
				>= 1f - (WAIT_TICKS + 0.5f) / dev.swordai.core.env.BodyState.EVENT_FADE_TICKS;
		return punishable && justHit ? KEEP : NOW;
	}

	/** When the swing it owes is made, on the ground or in the air; see {@link #onTheGround} and {@link #inTheAir}. */
	public static int when(float[] vec, boolean punishable) {
		return JumpNudge.inAir(vec) ? inTheAir(vec) : onTheGround(vec, punishable);
	}

	/** Whether the fighter has been hit and has not hit back since, as it can tell itself. */
''', 1),
])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final AimRule aimRule;
	private final boolean afterHit;
''', '''	private final AimRule aimRule;
	private final boolean afterHit;
	private final FallWatch fallWatch = new FallWatch();
''', 1),
    ('''		int when = owes && punishRule ? AnswerRule.inTheAir(obs.vec) : AnswerRule.NOW;
''', '''		fallWatch.tick(obs.vec);
		int when = owes && punishRule ? AnswerRule.when(obs.vec, fallWatch.punishable()) : AnswerRule.NOW;
''', 1),
    ('''		if (aimRule != null) {
			aimRule.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', '''		if (aimRule != null) {
			aimRule.reset();
		}
		fallWatch.reset();
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	private AimRule aimRule;
''', '''	private AimRule aimRule;
	private final FallWatch punishMemory = new FallWatch();
''', 1),
    ('''		int when = owes && cfg.punishRule ? AnswerRule.inTheAir(obs.vec) : AnswerRule.NOW;
''', '''		punishMemory.tick(obs.vec);
		int when = owes && cfg.punishRule ? AnswerRule.when(obs.vec, punishMemory.punishable()) : AnswerRule.NOW;
''', 1),
    ('''		if (aimRule != null) {
			aimRule.reset();
		}
		drillJumpOutOfZone = false;
''', '''		if (aimRule != null) {
			aimRule.reset();
		}
		punishMemory.reset();
		drillJumpOutOfZone = false;
''', 1),
])
s = open(ROOT + 'src/client/java/dev/swordai/client/Trainer.java', encoding='utf-8').read()
if 'import dev.swordai.core.rl.FallWatch;' not in s:
    s = s.replace('import dev.swordai.core.rl.FarRest;', 'import dev.swordai.core.rl.FallWatch;\nimport dev.swordai.core.rl.FarRest;', 1)
    open(ROOT + 'src/client/java/dev/swordai/client/Trainer.java', 'w', encoding='utf-8', newline='').write(s)
print('ok')
