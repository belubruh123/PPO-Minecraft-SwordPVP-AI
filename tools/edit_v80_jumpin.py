"""20 Oct 10:55: a reward for having the sword ready when the opponent jumps in at it (and a cost for having it down):
the counter to the spacer, which jumps in on its own clock whatever the fighter's sword is doing."""
import io

ROOT = 'D:/swordai/'


def edit(path, pairs):
    p = ROOT + path
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:80], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('edited', path)


edit('core/src/main/java/dev/swordai/core/rl/MeetJump.java', [(
    '''	private static boolean approaching(float[] vec, boolean coming) {''', '''	/**
	 * Whether the opponent is jumping in at the fighter now (on its way up, coming, near enough to land
	 * on it): 0 not, 1 with the fighter's sword not back in time to meet it, 2 with it back. For the
	 * reward that teaches it to keep its sword for that moment ({@code RewardWeights.jumpInReady}).
	 */
	public static int jumpIn(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || JumpIn.distance(vec) > WITHIN) {
			return 0;
		}
		float up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;
		if (up < RISING || !approaching(vec, true)) {
			return 0;
		}
		return scalar(vec, ObsSpec.SC_ATTACK_CHARGE) >= CHARGE ? 2 : 1;
	}

	private static boolean approaching(float[] vec, boolean coming) {''')])

edit('core/src/main/java/dev/swordai/core/env/StepFacts.java', [(
    '''	public boolean walkedInOnReady;
''', '''	public boolean walkedInOnReady;
	/**
	 * The opponent began a jump in at it on this step: 0 not, 1 with the fighter's sword down, 2 with it
	 * ready to meet the jump. See {@code RewardWeights.jumpInReady}.
	 */
	public int oppJumpIn;
'''), (
    '''		walkedInOnReady = false;
''', '''		walkedInOnReady = false;
		oppJumpIn = 0;
''')])

edit('core/src/main/java/dev/swordai/core/curriculum/RewardWeights.java', [(
    '''	public float spaceOut = 0f;
''', '''	public float spaceOut = 0f;
	/**
	 * Paid when the opponent jumps in at it while its sword is ready to meet the jump (a hit then knocks
	 * the opponent out of the air and its critical hit never lands), taken when the sword is down. The
	 * spacer comes in on its own clock whatever the fighter's sword is doing; trading at its landing
	 * leaves the sword down for its next jump in (20 October). Zero (off) unless set.
	 */
	public float jumpInReady = 0f;
''')])

edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [(
    '''		if (f.walkedInOnReady) {
			r -= w.spaceOut;
		}''', '''		if (f.walkedInOnReady) {
			r -= w.spaceOut;
		}
		if (f.oppJumpIn == 2) {
			r += w.jumpInReady;
		} else if (f.oppJumpIn == 1) {
			r -= w.jumpInReady;
		}''')])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [(
    '''		if (stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill) {
			meetHow[MeetJump.how(obs.vec, cfg.meetWhenComing)]++;
		}''', '''		if (stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill) {
			meetHow[MeetJump.how(obs.vec, cfg.meetWhenComing)]++;
		}
		// The opponent jumping in at it: counted once a jump, with the sword ready or not; see RewardWeights.jumpInReady.
		int jumpIn = stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill ? MeetJump.jumpIn(obs.vec) : 0;
		jumpInAtAction = 0;
		if (jumpIn == 0) {
			if (++jumpInQuiet >= 4) {
				jumpInSeen = false;
			}
		} else {
			jumpInQuiet = 0;
			if (!jumpInSeen) {
				jumpInSeen = true;
				jumpInAtAction = jumpIn;
				jumpIns[jumpIn - 1]++;
			}
		}'''), (
    '''	private int meetTicks;
''', '''	private int meetTicks;
	/** See RewardWeights.jumpInReady: this step's jump in at it, if one began, and whether one is under way. */
	private int jumpInAtAction;
	private boolean jumpInSeen;
	private int jumpInQuiet;
	/** Jumps in at it since the last update: with the sword down [0], with it ready [1]. */
	private final int[] jumpIns = new int[2];
'''), (
    '''			facts.walkedInOnReady = facts.enemyVisible && reachAtAction > REACH && facts.reachDist <= REACH''', '''			facts.oppJumpIn = jumpInAtAction;
			facts.walkedInOnReady = facts.enemyVisible && reachAtAction > REACH && facts.reachDist <= REACH'''), (
    '''		if (cfg.silWeight > 0f) {
			SwordAi.LOGGER.info("self-imitation: {}", learner.silLine());
		}''', '''		if (cfg.silWeight > 0f) {
			SwordAi.LOGGER.info("self-imitation: {}", learner.silLine());
		}
		SwordAi.LOGGER.info("jumps in at it: met with the sword ready {}, with it down {}", jumpIns[1], jumpIns[0]);
		jumpIns[0] = 0;
		jumpIns[1] = 0;''')])
