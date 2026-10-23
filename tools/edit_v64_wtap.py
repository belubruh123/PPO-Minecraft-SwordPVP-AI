ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


M = 'core/src/main/java/dev/swordai/core/rl/MixIdea.java'
edit(M, [
(''' * opponent while its combo lands and has to keep turning to keep it in front; the opponent has to
 * keep turning to hit back at all. Then comes another circle, to either side, now and then with a
 * swing in between that is made going straight in.
 *''', ''' * opponent while its combo lands and has to keep turning to keep it in front; the opponent has to
 * keep turning to hit back at all. Then comes another circle, to either side, or a straight run.
	 *
 * <p>A straight run is {@link #MIN_STRAIGHT_SWINGS} to {@link #MAX_STRAIGHT_SWINGS} swings with no
 * strafe at all: the W-tap combo, where the distance is kept with W alone (let go of after the hit,
 * pressed again to come back to the edge of reach as the sword comes back). The user, 18 October:
 * the strafe "dont need to be circle", "W tap combo as well cause like high tier player does it",
 * though "circle combo does do really well". About a quarter of the swings; up to then one swing
 * in seven between circles was made straight in, and nothing was asked of the keys on it.
 *''', 1),
('''	/** Between two circles, the chance of a swing with no strafe plan, so going straight in stays part of the game. */
	private static final float FREE_CHANCE = 0.15f;''', '''	/** After a circle or a straight run, the chance that a straight run comes next (a quarter of the swings). */
	private static final float STRAIGHT_CHANCE = 0.35f;
	/** A straight run lasts this many swings. */
	public static final int MIN_STRAIGHT_SWINGS = 2;
	public static final int MAX_STRAIGHT_SWINGS = 5;''', 1),
('''	/** Swings the running circle still has to come after the current one. */
	private int circleLeft;''', '''	/** Swings the running circle, or straight run, still has to come after the current one. */
	private int circleLeft;''', 1),
('''		if (circling && circleLeft > 0) {
			// The circle goes on: the same side for this swing too.
			circleLeft--;
		} else if (rng.nextFloat() < FREE_CHANCE) {
			side = 0;
			circling = false;
		} else {''', '''		if (circleLeft > 0) {
			// The circle, or the straight run, goes on: the same for this swing too.
			circleLeft--;
		} else if (rng.nextFloat() < STRAIGHT_CHANCE) {
			side = 0;
			circling = false;
			circleLeft = MIN_STRAIGHT_SWINGS - 1 + rng.nextInt(MAX_STRAIGHT_SWINGS - MIN_STRAIGHT_SWINGS + 1);
		} else {''', 1),
])

R = 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java'
edit(R, [
('''			if (f.ideaStrafe != 0 && f.enemyVisible && f.reachDist <= w.mixWithin && !running) {
				tally.mixTicks++;
				if (f.strafeAxis == f.ideaStrafe) {
					r += w.mixStrafe;
					tally.mixFollowed++;
				} else if (f.strafeAxis != 0) {
					r -= w.mixStrafe;
				}
			}''', '''			if (f.ideaStrafe != 0 && f.enemyVisible && f.reachDist <= w.mixWithin && !running) {
				tally.mixTicks++;
				if (f.strafeAxis == f.ideaStrafe) {
					r += w.mixStrafe;
					tally.mixFollowed++;
				} else if (f.strafeAxis != 0) {
					r -= w.mixStrafe;
				}
			} else if (f.enemyVisible && f.reachDist <= w.mixWithin && !running) {
				// A straight run, the W-tap combo (see MixIdea): no strafe. Charged as going the wrong way
				// round is; not strafing is not paid, since it is what a fighter does by itself.
				tally.mixTicks++;
				if (f.strafeAxis == 0) {
					tally.mixFollowed++;
				} else {
					r -= w.mixStrafe;
				}
			}''', 1),
])

T = 'core/src/test/java/dev/swordai/core/rl/MixIdeaTest.java'
edit(T, [
('''		assertTrue(free > 60 && free < 500, free + " swings straight in");
		assertTrue(left > 2000 && right > 2000, left + "/" + right);''', '''		assertTrue(free > 1000 && free < 2200, free + " swings straight in, about a quarter");
		assertTrue(left > 1500 && right > 1500, left + "/" + right);''', 1),
])
print('wtap ok')
