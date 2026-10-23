ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''	private long spaceCoolTicks;
	private double spaceCoolSum;
''', '''	private long spaceCoolTicks;
	private double spaceCoolSum;
	/**
	 * Spacing as a player sees it: the times it walked into reach (W held, the opponent in view) with the
	 * opponent's sword back [0] and with it on its way back [1]; against the 0.14.0 model alone [2], [3].
	 * Spacing is the second more than the first. The distance kept above cannot show it: right after the
	 * opponent's hit its sword is on its way back and the fighter is knocked away, so it reads farther.
	 */
	private final int[] walkedIn = new int[4];
	private float spaceLastReach = 99f;
''', 1),
('''		if (groundAtAction && facts.enemyVisible && facts.reachDist < 6f && clock.ticksSinceHitTaken >= 6 && clock.ticksSinceHitDealt >= 6) {
''', '''		if (facts.enemyVisible) {
			if (spaceLastReach > REACH && facts.reachDist <= REACH && facts.forwardAxis > 0) {
				int cooling = obs.vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_OPP_CHARGE] >= 0.9f ? 0 : 1;
				walkedIn[cooling]++;
				if (episodeVsOldModel) {
					walkedIn[2 + cooling]++;
				}
			}
			spaceLastReach = facts.reachDist;
		}
		if (groundAtAction && facts.enemyVisible && facts.reachDist < 6f && clock.ticksSinceHitTaken >= 6 && clock.ticksSinceHitDealt >= 6) {
''', 1),
('''	void beginEpisode(Truth truth) {
		IntegratedServer server = mc.getSingleplayerServer();
''', '''	void beginEpisode(Truth truth) {
		spaceLastReach = 99f;
		IntegratedServer server = mc.getSingleplayerServer();
''', 1),
('''| straight runs {} ticks | fights against the 0.14.0 model: won {} lost {}",''',
 '''| straight runs {} ticks | fights against the 0.14.0 model: won {} lost {} | walked into reach on a ready sword {}, on one coming back {} (against the 0.14.0 model {} / {})",''', 1),
('''spaceCoolTicks, straightTicks, oldModelWins, oldModelLosses);
		straightTicks = 0;''', '''spaceCoolTicks, straightTicks, oldModelWins, oldModelLosses,
				walkedIn[0], walkedIn[1], walkedIn[2], walkedIn[3]);
		java.util.Arrays.fill(walkedIn, 0);
		straightTicks = 0;''', 1),
])
print('walkedin ok')
