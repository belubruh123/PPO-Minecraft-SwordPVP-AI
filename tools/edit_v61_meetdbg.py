ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/MeetJump.java', [
    ('''	/** The sword back, or back by the time the opponent is in reach. */
	static final float CHARGE = 0.9f;
''', '''	/** The sword back, or back by the time the opponent is in reach: three or four ticks from it. */
	static final float CHARGE = 0.7f;
''', 1),
    ('''	private static float scalar(float[] vec, int index) {''', '''	/**
	 * For the log: how far this observation gets through what {@link #applies} asks. 0: no opponent
	 * on its way up within range; 1: there is one; 2: and its own feet are on the ground; 3: and its
	 * sword is far enough back; 4: and the two are closing, which is {@link #applies}.
	 */
	public static int how(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || JumpIn.distance(vec) > WITHIN
				|| (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE < RISING) {
			return 0;
		}
		if (scalar(vec, ObsSpec.SC_ON_GROUND) < 0.5f) {
			return 1;
		}
		if (scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {
			return 2;
		}
		return -scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) / BodyState.VELOCITY_SCALE >= CLOSING ? 4 : 3;
	}

	private static float scalar(float[] vec, int index) {''', 1),
])
T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''		boolean meet = cfg.meetJumpRule && cfg.answerRule && !episodeLockedDrill''', '''		if (stage.unlocked(ActionSpace.JUMP) && !episodeLockedDrill) {
			meetHow[MeetJump.how(obs.vec)]++;
		}
		boolean meet = cfg.meetJumpRule && cfg.answerRule && !episodeLockedDrill''', 1),
    ('''	private int meetTicks;
	private int meetSwings;
''', '''	private int meetTicks;
	private int meetSwings;
	/** Ticks by how far they got through what {@link MeetJump#applies} asks; see {@link MeetJump#how}. */
	private final int[] meetHow = new int[5];
''', 1),
    ('''ran in at an opponent on its way up on {} ticks, swings {}",''', '''ran in at an opponent on its way up on {} ticks, swings {} (ticks with one on its way up within range {}: its own feet off the ground {}, sword not back {}, not closing {})",''', 1),
    ('''hitNowAlone, waryBegan, meetTicks, meetSwings);
		meetTicks = 0;''', '''hitNowAlone, waryBegan, meetTicks, meetSwings,
				meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3]);
		java.util.Arrays.fill(meetHow, 0);
		meetTicks = 0;''', 1),
])
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
a = '''		float[] spent = v.clone();
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.5f;
		assertFalse(MeetJump.applies(spent));'''
assert s.count(a) == 1
s = s.replace(a, '''		float[] spent = v.clone();
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 0.5f;
		assertFalse(MeetJump.applies(spent));
		assertEquals(4, MeetJump.how(v));
		assertEquals(0, MeetJump.how(down));
		assertEquals(3, MeetJump.how(away));
		assertEquals(2, MeetJump.how(spent));''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('meetdbg ok')
