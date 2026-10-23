ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


S = 'core/src/main/java/dev/swordai/core/rl/StepBack.java'
edit(S, [
    ('''	static final boolean READ = false;
''', '''	static final boolean READ = false;
	/**
	 * Never against an opponent that comes after it through the air: one that has hit it from the air
	 * within a second of its stepping back, or followed a hit of a combo with one from the air
	 * ({@link #chasedBy}). For a minute after that, kept across rounds. The user, 18 October: S "is
	 * ok as long as opponent didn't chase you, so it needs to know if they will chase you with crit.
	 * But still be careful most".
	 */
	static final int CHASER_TICKS = 1200;
	static final int CHASE_AFTER_STEP_TICKS = 20;
	/** Moving up or down at this much a tick or more, the opponent is off the ground; seen so within this many ticks before a hit, the hit came from the air. */
	static final float AIR_SPEED = 0.06f;
	static final int AIR_SEEN_TICKS = 6;
''', 1),
    ('''	private int wary;
	private boolean on;
''', '''	private int wary;
	private boolean on;
	private int chaser;
	private int sinceStep = 1000;
	private int airSeen;
''', 1),
    ('''	public void reset() {
		wary = 0;
		newRound();
	}''', '''	public void reset() {
		wary = 0;
		chaser = 0;
		newRound();
	}''', 1),
    ('''		unhit = 10_000;
		on = false;
	}''', '''		unhit = 10_000;
		on = false;
		sinceStep = 1000;
		airSeen = 0;
	}''', 1),
    ('''	/** Whether the opponent has lately answered one of its hits while its sword was on the way back. */
	public boolean wary() {
		return wary >= WARY_FROM;
	}''', '''	/** Whether the opponent has lately answered one of its hits while its sword was on the way back. */
	public boolean wary() {
		return wary >= WARY_FROM;
	}

	/** Whether the opponent has lately come after it through the air; see {@link #CHASER_TICKS}. */
	public boolean chaser() {
		return chaser > 0;
	}

	/** Another rule has found the opponent to follow its hits with a jump; see {@code JumpReset.chased}. */
	public void chasedBy(boolean chased) {
		if (chased) {
			chaser = CHASER_TICKS;
		}
	}''', 1),
    ('''		if (dealtAge < 1000) {
			dealtAge++;
		}
		if (scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fresh) {''', '''		if (dealtAge < 1000) {
			dealtAge++;
		}
		if (chaser > 0) {
			chaser--;
		}
		sinceStep = on ? 0 : Math.min(sinceStep + 1, 1000);
		boolean fromAir = airSeen > 0;
		if (airSeen > 0) {
			airSeen--;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f
				&& Math.abs(scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE >= AIR_SPEED) {
			airSeen = AIR_SEEN_TICKS;
		}
		if (scalar(vec, ObsSpec.SC_SINCE_HIT_TAKEN) >= fresh) {
			if (fromAir && sinceStep <= CHASE_AFTER_STEP_TICKS) {
				// Stepped back from, it came after the fighter through the air: never again, for a while.
				chaser = CHASER_TICKS;
			}''', 1),
    ('''			on = wary() && d <= IN;
			return on ? BACK : 0;''', '''			on = wary() && chaser <= 0 && d <= IN;
			return on ? BACK : 0;''', 1),
])
F = 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
edit(F, [
    ('''		int step = stepBack == null ? 0 : stepBack.plan(obs.vec);''', '''		if (stepBack != null && jumpReset != null) {
			stepBack.chasedBy(jumpReset.chased());
		}
		int step = stepBack == null ? 0 : stepBack.plan(obs.vec);''', 1),
])
edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		boolean wasWary = stepBack.wary();
		int step = ''', '''		boolean wasWary = stepBack.wary();
		if (jumpReset != null) {
			stepBack.chasedBy(jumpReset.chased());
		}
		int step = ''', 1),
])
print('stepchase ok')
