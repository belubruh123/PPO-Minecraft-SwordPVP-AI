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

# ---- StageSpec
p = root + 'core/src/main/java/dev/swordai/core/curriculum/StageSpec.java'
s, nl = load(p)
i = s.index('	public float backNudgeRate')
j = s.index('\n', i) + 1
s = s[:j] + '''	/**
	 * Above zero: the push towards S in the ticks after a hit of a combo does not end with
	 * {@link #backNudgeSteps} (or, in a stage without such a stretch, is there from its start). It is
	 * kept at whatever it takes for S to be held on this share of those ticks, in training and in
	 * every fight it plays.
	 *
	 * <p>Not a lesson but a habit it is given, like the rule about the sword's charge. Measured
	 * (0.14.0, against a copy of itself that had a combo going on it): with the push held it was out
	 * by the opponent's second hit in two combos of three and the drill paid 0.085 a step; as the push
	 * was let down it was out of one in two and then one in three, the drill paid 0.04, and by the
	 * end S was held on none of those ticks. The move works and the network does not keep it: one
	 * escape model answers every opponent, and against the one that runs straight back in, backing
	 * off gains nothing. Left to itself it drops the move everywhere.
	 */
	public float backNudgeKeep = 0f;
''' + s[j:]
save(p, s, nl)

# ---- Curriculum
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Curriculum.java'
s, nl = load(p)
s = rep(s, '''		backNudge = current().backNudgeRate > 0f ? NUDGE_START : 0f;
''', '''		// A push that was already on (the stage before kept it) carries over at the strength it had.
		backNudge = current().backNudgeRate > 0f || current().backNudgeKeep > 0f ? Math.max(NUDGE_START, backNudge) : 0f;
''')
s = rep(s, '''		StageSpec st = current();
		if (st.backNudgeRate <= 0f || st.backNudgeSteps <= 0 || stepsInStage >= st.backNudgeSteps) {
			return 0f;
		}
		if (stepsInStage <= st.backNudgeHoldSteps) {
			return st.backNudgeRate;
		}
		return st.backNudgeRate * (st.backNudgeSteps - stepsInStage) / Math.max(1, st.backNudgeSteps - st.backNudgeHoldSteps);
''', '''		StageSpec st = current();
		// Never below what the stage keeps it at for good; see StageSpec.backNudgeKeep.
		if (st.backNudgeRate <= 0f || st.backNudgeSteps <= 0 || stepsInStage >= st.backNudgeSteps) {
			return st.backNudgeKeep;
		}
		if (stepsInStage <= st.backNudgeHoldSteps) {
			return Math.max(st.backNudgeKeep, st.backNudgeRate);
		}
		return Math.max(st.backNudgeKeep,
				st.backNudgeRate * (st.backNudgeSteps - stepsInStage) / Math.max(1, st.backNudgeSteps - st.backNudgeHoldSteps));
''')
save(p, s, nl)

# ---- Stages
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Stages.java'
s, nl = load(p)
s = rep(s, '''		st.backNudgeRate = 0.6f;
		st.backNudgeHoldSteps = 25_000;
		st.backNudgeSteps = 50_000;
''', '''		st.backNudgeRate = 0.6f;
		st.backNudgeHoldSteps = 25_000;
		st.backNudgeSteps = 50_000;
		// And from then on it is kept, at a lower mark: let go of altogether, the move was gone again
		// within a few thousand steps. See StageSpec.backNudgeKeep.
		st.backNudgeKeep = BACK_KEEP;
''')
s = rep(s, '''		st.reward.comboBack = 0.04f;
''', '''		st.reward.comboBack = 0.04f;
		// The push for S is kept on from the crit stage; see StageSpec.backNudgeKeep.
		st.backNudgeKeep = BACK_KEEP;
''')
s = rep(s, '''	private static final float ATTEMPT_WITHIN = 3.6f;
''', '''	private static final float ATTEMPT_WITHIN = 3.6f;
	/** From the crit stage on S stays pushed for in the ticks after a hit of a combo, to this share of them; see {@link StageSpec#backNudgeKeep}. */
	private static final float BACK_KEEP = 0.45f;
''')
save(p, s, nl)

# ---- Config version
p = root + 'core/src/main/java/dev/swordai/core/config/Config.java'
s, nl = load(p)
s = rep(s, 'CURRENT_VERSION = 43;', 'CURRENT_VERSION = 44;')
save(p, s, nl)

# ---- Report line
p = root + 'src/client/java/dev/swordai/client/Report.java'
s, nl = load(p)
s = rep(s, '''		if (stage.backNudgeRate <= 0f) {
			return null;
		}
		float target = curriculum.backNudgeTarget();
		if (target <= 0f) {''', '''		if (stage.backNudgeRate <= 0f && stage.backNudgeKeep <= 0f) {
			return null;
		}
		float target = curriculum.backNudgeTarget();
		if (stage.backNudgeKeep > 0f && (stage.backNudgeRate <= 0f || curriculum.stepsInStage() >= stage.backNudgeSteps)) {
			return String.format(Locale.ROOT, "S help: kept on, strength %.1f of at most %.0f; it is pushed to hold S on %.0f%% of the ticks right after a hit of a combo, "
							+ "in training and in every fight (left to itself it stops backing out of combos altogether)",
					curriculum.backNudge(), 12f, target * 100f);
		}
		if (target <= 0f) {''')
save(p, s, nl)

# ---- tests
p = root + 'core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java'
s, nl = load(p)
s = rep(s, '''		// The duel does not.
		c.setStage(8);
		assertEquals(0f, c.backNudge());
	}''', '''		// And from there on it is kept on: through the rest of that stage, and in the duel after it.
		StageSpec critStage = stages.get(7);
		assertTrue(critStage.backNudgeKeep > 0f && critStage.backNudgeKeep < critStage.backNudgeRate);
		assertEquals(critStage.backNudgeRate, c.backNudgeTarget());
		c.record(backBatch(critStage.backNudgeSteps + 1, 0, 0));
		assertEquals(critStage.backNudgeKeep, c.backNudgeTarget());
		assertTrue(c.backNudge() > 0f);
		c.record(backBatch(1000, 200, 0));
		float kept = c.backNudge();
		assertTrue(kept > Curriculum.NUDGE_START);
		c.setStage(8);
		assertEquals(stages.get(8).backNudgeKeep, c.backNudgeTarget());
		assertEquals(kept, c.backNudge(), 1e-6f, "at the strength it had");
		assertEquals(stages.get(8).backNudgeKeep, stages.get(9).backNudgeKeep, 0f);
		assertEquals(0f, combo.backNudgeKeep, "the stage that teaches it is passed on what it does unaided");
	}''')
save(p, s, nl)
print('ok')
