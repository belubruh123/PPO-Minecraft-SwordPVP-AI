ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


# ---- the chase window, from what a running jump really gains -------------------------------------
edit('core/src/main/java/dev/swordai/core/rl/JumpIn.java', [
    ('''	/** The farthest it jumps at an opponent that keeps its distance from it entirely. */
	static final float CHASE_TO = 3.8f;
	/** And this many ticks' worth of what its run is closing on top of that: it goes on closing in the air. */
	static final float CHASE_TICKS_CLOSING = 8f;
	static final float CHASE_TO_MOST = 4.9f;
''', '''	/**
	 * The farthest it jumps at an opponent that keeps its distance from it entirely. A running jump
	 * gains little on a run: its first tick covers 0.48 of a block instead of 0.28, and after that
	 * the air carries it at 0.29 a tick. By the tick it is coming down with the sprint let go of, that
	 * is a quarter of a block more than the run would have made. (The first version of this took it
	 * for a whole block, jumped from 3.8 to 4.3 blocks at an opponent backing off, and came down out
	 * of reach: one critical hit from 29 such jumps.)
	 */
	static final float CHASE_TO = 3.1f;
	/** And this many ticks' worth of what its run is closing on top of that: it goes on closing in the air. */
	static final float CHASE_TICKS_CLOSING = 8f;
	static final float CHASE_TO_MOST = 4.3f;
''', 1),
    (''' * <li><b>The chase.</b> It is at a run after an opponent a few steps beyond its reach, and the run
 * is not catching it: the opponent is backing off or running. A run closes on a player walking
 * backwards by a block in fifteen ticks and on one that runs not at all; a running jump is carried
 * forward a block farther than a run in the same time. So it jumps, keeps W and the sprint key
 * down on the way up, and on the way down, once the opponent is in its reach and under its
 * crosshair, lets go of both for a tick and swings on the next: a critical hit, which leaves the
 * opponent where it is instead of sending it on its way. If the opponent is never in reach it
 * lands still running, and nothing was spent but the jump.</li>''',
     ''' * <li><b>The chase.</b> It is at a run after an opponent a step or two beyond its reach, and the
 * run is hardly catching it: the opponent is backing off or running. From where the way down of a
 * jump will bring that opponent into reach, it jumps, keeps W and the sprint key down on the way
 * up, and on the way down, once the opponent is in its reach and under its crosshair, lets go of
 * both for a tick and swings on the next: a critical hit, half as much damage again as the sprint
 * hit the run would have come to, and one that leaves the opponent where it is instead of sending
 * it on its way. If the opponent is never in reach it lands still running, and nothing was spent
 * but the jump. After an opponent that runs with its back turned, as fast as the fighter itself,
 * it makes one running jump after the other from any distance: a run never gets nearer to that
 * one, and jumps taken in a row are carried a quarter faster than a run.</li>''', 1),
])

# ---- far away the camera is steered, not only held ------------------------------------------------
full = ROOT + 'core/src/main/java/dev/swordai/core/rl/FarRest.java'
open(full, 'w', encoding='utf-8', newline='').write('''package dev.swordai.core.rl;

/**
 * A steady hand on an opponent that is far away. With the opponent on screen and a long way off,
 * the camera is not the network's to move: it is brought onto the opponent and kept there by
 * {@link AimRule}'s plain steering, which turns towards it by a share of what is left on every
 * tick, follows it smoothly while it walks across the picture, and rests while it does not.
 *
 * <p>Why it is a rule. The hand model ({@link AimMotor}) starts a turn at a degree a tick and no
 * less, so the smallest correction it can make from rest is a good degree. Twenty blocks away the
 * whole opponent is a degree and a half wide. The network, paid for a crosshair on the opponent,
 * went on correcting there, each correction as large as the target, one way and then the other:
 * the picture shook. Nothing can be hit from there; what a player does is keep the opponent about
 * in the middle of the screen with a quiet hand.
 *
 * <p>It goes by what the network itself is shown: how tall the opponent is on screen, which says
 * how far away it is, and where it is from the crosshair.
 */
public final class FarRest {
	/** Farther away than this, as {@link JumpIn#distance} reads it. */
	public static final float FROM = 9f;

	private FarRest() {
	}

	/** Whether the camera is steered for it on this tick. */
	public static boolean applies(float[] vec) {
		return vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] >= 0.5f && JumpIn.distance(vec) >= FROM && JumpIn.distance(vec) < Float.MAX_VALUE;
	}
}
''')

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''		this.aimRule = cfg.aimRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.ATTACK)
				&& ActionSpace.unlocked(headMask, ActionSpace.YAW) && ActionSpace.unlocked(headMask, ActionSpace.PITCH)
				? new AimRule(cfg.aimResponse) : null;
''', '''		this.afterHit = cfg.aimRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.ATTACK);
		this.aimRule = (afterHit || farRest) && ActionSpace.unlocked(headMask, ActionSpace.YAW) && ActionSpace.unlocked(headMask, ActionSpace.PITCH)
				? new AimRule(cfg.aimResponse) : null;
''', 1),
    ('''	private final AimRule aimRule;
''', '''	private final AimRule aimRule;
	private final boolean afterHit;
''', 1),
    ('''		// And the camera is left alone on an opponent that is far away; see FarRest.
		if (farRest && FarRest.applies(obs.vec)) {
			keys = FarRest.keys(keys);
		}
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		boolean aim = aimRule != null && AimRule.applies(obs.vec);
''', '''		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		// And it is steered for it on an opponent that is far away; see FarRest.
		boolean aim = aimRule != null && (afterHit && AimRule.applies(obs.vec) || farRest && FarRest.applies(obs.vec));
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''			if (cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec)) {
				keys = FarRest.keys(keys);
			}
		}
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		boolean aim = !episodeLockedDrill && cfg.aimRule && cfg.answerRule && stage.unlocked(ActionSpace.ATTACK)
				&& stage.unlocked(ActionSpace.YAW) && stage.unlocked(ActionSpace.PITCH) && AimRule.applies(obs.vec);
''', '''		}
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		// And it is steered for it on an opponent that is far away; see FarRest.
		boolean aim = !episodeLockedDrill && stage.unlocked(ActionSpace.YAW) && stage.unlocked(ActionSpace.PITCH)
				&& (cfg.aimRule && cfg.answerRule && stage.unlocked(ActionSpace.ATTACK) && AimRule.applies(obs.vec)
						|| cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec));
''', 1),
])

# ---- what is counted as far-away aim: only once the crosshair is about on it -----------------------
edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''		if (f.enemyVisible && f.reachDist > FAR_AIM_FROM && !f.discontinuity) {
''', '''		if (f.enemyVisible && f.reachDist > FAR_AIM_FROM && !f.discontinuity && f.missDeg <= FAR_AIM_WITHIN_DEG) {
''', 1),
    ('''	static final float FAR_AIM_FROM = 9f;
''', '''	static final float FAR_AIM_FROM = 9f;
	/** And only with the crosshair no farther from it than this: holding the aim, not turning to find it. */
	static final float FAR_AIM_WITHIN_DEG = 4f;
''', 1),
])

# ---- tests ------------------------------------------------------------------------------------
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
i = s.index('	@Test\n	void theCameraIsLeftAloneOnAnOpponentThatIsFarAwayAndUnderTheCrosshair() {')
s = s[:i] + '''	@Test
	void farAwayTheCameraIsSteeredOntoTheOpponentAndComesToRestThere() {
		assertTrue(FarRest.applies(vec(true, 20f, 1f)));
		// Near, the aim is its own; and so it is with nothing on screen.
		assertFalse(FarRest.applies(vec(true, 5f, 1f)));
		assertFalse(FarRest.applies(new float[ObsSpec.VEC]));
		// Six degrees to the right of the crosshair: it turns right, most of the way at once, and not past it.
		float degree = ViewGeometry.apparentSize((float) Math.tan(Math.toRadians(1.0)), 1f);
		AimRule rule = new AimRule(0.5f);
		AimMotor hand = new AimMotor(0.5f);
		float off = 6f;
		float turnedInAll = 0f;
		int flips = 0;
		float last = 0f;
		for (int tick = 0; tick < 30; tick++) {
			float[] v = vec(true, 20f, 1f);
			v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_X] = (float) Math.tan(Math.toRadians(off)) * degree / (float) Math.tan(Math.toRadians(1.0));
			v[ObsSpec.VEC_STATS + ObsSpec.STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
			v[ObsSpec.VEC_STATS + ObsSpec.STATS + ObsSpec.ST_ANY_X] = v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_X];
			rule.plan(v, hand.yawPush(), hand.pitchPush());
			hand.step(ActionSpace.YAW_BINS[rule.yawBin()], ActionSpace.PITCH_BINS[rule.pitchBin()]);
			float rate = hand.yawRate();
			if (rate != 0f && last != 0f && Math.signum(rate) != Math.signum(last)) {
				flips++;
			}
			if (rate != 0f) {
				last = rate;
			}
			off -= rate;
			turnedInAll += Math.abs(rate);
			assertEquals(0f, hand.pitchRate(), 1e-6f, "it is level already");
		}
		assertTrue(Math.abs(off) <= 2f, "all but on it: " + off);
		assertEquals(0, flips, "never back the other way");
		assertEquals(0f, hand.yawRate(), 1e-6f, "and at rest");
		assertTrue(turnedInAll <= 6.5f);
	}

	@Test
	void hitFromOutOfThePictureItTurnsToWhereTheHitCameFrom() {
		AimRule rule = new AimRule(0.5f);
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 0.5f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		assertTrue(AimRule.applies(v));
		// Thrown to the right: the hit came from the left, and that is where it turns, as fast as the hand goes.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_RIGHT] = 0.3f * BodyState.VELOCITY_SCALE;
		rule.plan(v, 0f, 0f);
		assertEquals(0, rule.yawBin());
		// It goes on that way although it is no longer being thrown.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_RIGHT] = 0f;
		rule.plan(v, -20f, 0f);
		assertEquals(0, rule.yawBin());
		// Seen before on the right: that is where it looks, whichever way the hit threw it.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_LAST_SEEN_SIDE] = 1f;
		rule.plan(v, 0f, 0f);
		assertEquals(ActionSpace.YAW_BINS.length - 1, rule.yawBin());
		// Long after the hit, or having hit back, the camera is its own again.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 0f;
		assertFalse(AimRule.applies(v));
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 0f;
		assertFalse(AimRule.applies(v));
	}
}
'''
s = s.replace('running(3.7f, 0.065f)', 'running(3.5f, 0.065f)').replace('running(3.6f, 0f)', 'running(3.5f, 0.06f)')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
