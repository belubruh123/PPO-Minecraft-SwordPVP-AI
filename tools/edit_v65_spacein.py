ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/env/StepFacts.java', [
('''public final class StepFacts {
''', '''public final class StepFacts {
	/** The opponent's sword counts as back from here on (see {@code ObsSpec.SC_OPP_CHARGE}). */
	public static final float OPP_READY = 0.9f;

''', 1),
('''	/** Ticks the plan still wanted the swing held back. */
	public int ideaHold;
''', '''	/** Ticks the plan still wanted the swing held back. */
	public int ideaHold;
	/** How far back the opponent's sword was when this step was chosen, 0..1, as read off its arm. */
	public float oppCharge = 1f;
	/**
	 * On this step it walked into reach (W held, the opponent in view) while the opponent's sword was
	 * on its way back, for the first time since that swing: spacing. See {@code RewardWeights.spaceIn}.
	 */
	public boolean walkedInOnCooldown;
''', 1),
('''		ideaHold = 0;
		hitsDealt = 0;''', '''		ideaHold = 0;
		oppCharge = 1f;
		walkedInOnCooldown = false;
		hitsDealt = 0;''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/RewardWeights.java', [
('''	/** Once the weapon is nearly recharged and the opponent is out of reach: W + sprint to close in. */
	public float reengage = 0f;
''', '''	/** Once the weapon is nearly recharged and the opponent is out of reach: W + sprint to close in. */
	public float reengage = 0f;
	/**
	 * Spacing: paid once per swing of the opponent's, on the step it walks into reach while that sword
	 * is on its way back ({@code StepFacts.walkedInOnCooldown}): out of reach while the other sword is
	 * ready, in while it comes back. While above zero, {@link #reengage} is paid only into such a sword;
	 * coming back in on a ready one is left to what comes of it. The user, 18 October: "Let it learn to
	 * space other". Zero (off) unless set.
	 */
	public float spaceIn = 0f;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
('''			} else if (!backing && f.charge >= REENGAGE_CHARGE && f.reachDist > w.rangeMax && f.forwardAxis > 0 && f.sprintKey) {
				r += w.reengage;
			}''', '''			} else if (!backing && f.charge >= REENGAGE_CHARGE && f.reachDist > w.rangeMax && f.forwardAxis > 0 && f.sprintKey
					&& (w.spaceIn <= 0f || f.oppCharge < StepFacts.OPP_READY)) {
				r += w.reengage;
			}''', 1),
('''		if (f.jumpKey && f.reachAtAction > w.jumpWithin && comboBefore < w.uppercutCombo) {''', '''		if (f.walkedInOnCooldown) {
			r += w.spaceIn;
		}

		if (f.jumpKey && f.reachAtAction > w.jumpWithin && comboBefore < w.uppercutCombo) {''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	private final int[] walkedIn = new int[4];
	private float spaceLastReach = 99f;
''', '''	private final int[] walkedIn = new int[4];
	private float spaceLastReach = 99f;
	/** The opponent's sword when the step being scored was chosen, and on the tick before; see StepFacts.walkedInOnCooldown. */
	private float oppChargeAtAction = 1f;
	private float spaceLastOppCharge = 1f;
	/** The spacing reward was paid since the opponent's last swing. */
	private boolean spacePaid;
''', 1),
('''			facts.reachAtAction = reachAtAction;
''', '''			facts.reachAtAction = reachAtAction;
			facts.oppCharge = oppChargeAtAction;
			facts.walkedInOnCooldown = !spacePaid && facts.enemyVisible && reachAtAction > REACH && facts.reachDist <= REACH
					&& facts.forwardAxis > 0 && oppChargeAtAction < StepFacts.OPP_READY;
			if (facts.walkedInOnCooldown) {
				spacePaid = true;
			}
''', 1),
('''		if (facts.enemyVisible) {
			if (spaceLastReach > REACH''', '''		float oppCharge = obs.vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_OPP_CHARGE];
		if (oppCharge < spaceLastOppCharge - 0.05f) {
			// The opponent swung again: walking in on the way back of this swing is paid once more.
			spacePaid = false;
		}
		spaceLastOppCharge = oppCharge;
		oppChargeAtAction = oppCharge;
		if (facts.enemyVisible) {
			if (spaceLastReach > REACH''', 1),
('''	void beginEpisode(Truth truth) {
		spaceLastReach = 99f;
''', '''	void beginEpisode(Truth truth) {
		spaceLastReach = 99f;
		oppChargeAtAction = 1f;
		spaceLastOppCharge = 1f;
		spacePaid = false;
''', 1),
])

C = 'core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java'
edit(C, [
('''	@Test
	void promotionWaitsForStepsThenForMetrics() {''', '''	@Test
	void spacingPaysWalkingInOnTheOpponentsCooldownAndGatesReengage() {
		StageSpec st = stage("Crits");
		st.reward.spaceIn = 0.3f;
		Tally t = new Tally();
		StepFacts in = facts(2.9f);
		in.walkedInOnCooldown = true;
		in.oppCharge = 0.4f;
		StepFacts plain = facts(2.9f);
		plain.oppCharge = 0.4f;
		assertEquals(0.3f, new RewardModel().step(st, in, t) - new RewardModel().step(st, plain, t), 1e-5);
		// Running back in once its own sword is back: paid into a sword that is coming back, not into a ready one.
		StepFacts readySword = facts(4f);
		readySword.charge = 0.9f;
		readySword.forwardAxis = 1;
		readySword.sprintKey = true;
		readySword.oppCharge = 1f;
		StepFacts coolingSword = facts(4f);
		coolingSword.charge = 0.9f;
		coolingSword.forwardAxis = 1;
		coolingSword.sprintKey = true;
		coolingSword.oppCharge = 0.5f;
		StepFacts idle = facts(4f);
		idle.charge = 0.9f;
		float base = new RewardModel().step(st, idle, t);
		assertEquals(0f, new RewardModel().step(st, readySword, t) - base, 1e-5);
		assertEquals(st.reward.reengage, new RewardModel().step(st, coolingSword, t) - base, 1e-5);
	}

	@Test
	void promotionWaitsForStepsThenForMetrics() {''', 1),
])
print('spacein ok')
