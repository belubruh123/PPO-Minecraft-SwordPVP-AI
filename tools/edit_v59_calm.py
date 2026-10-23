ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/AimRule.java', [
    ('''	/** Nearer than this, in degrees, it is on: the camera comes to rest. */
	static final float ON_DEG = 0.4f;
''', '''	/** Nearer than this, in degrees, it is on: the camera comes to rest. */
	static final float ON_DEG = 0.4f;
	/**
	 * On an opponent that is far away a hand at rest stays at rest until the crosshair is this far
	 * off it, in degrees: about the opponent's own width at twenty blocks to either side. There is
	 * nothing to hit from there, and a camera that follows every step the opponent takes is a camera
	 * that never stops moving.
	 */
	static final float FAR_START_DEG = 2.5f;
''', 1),
    ('''	public void plan(float[] vec, float yawPush, float pitchPush) {
		int at = ObsSpec.VEC_STATS;
		if (vec[at + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
			blindDir = 0;
			float yawErr = degrees(vec[at + ObsSpec.ST_ANY_X]);
			// The picture's y is up, the pitch is down.
			float pitchErr = -degrees(vec[at + ObsSpec.ST_ANY_Y]);
			int before = at + ObsSpec.STATS;
			if (vec[before + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
''', '''	public void plan(float[] vec, float yawPush, float pitchPush) {
		plan(vec, yawPush, pitchPush, false);
	}

	/**
	 * @param far the opponent is far away (see {@link FarRest}): the hand stays at rest until the
	 *            crosshair is well off it, and does not run ahead of where the opponent is going
	 */
	public void plan(float[] vec, float yawPush, float pitchPush, boolean far) {
		int at = ObsSpec.VEC_STATS;
		if (vec[at + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
			blindDir = 0;
			float yawErr = degrees(vec[at + ObsSpec.ST_ANY_X]);
			// The picture's y is up, the pitch is down.
			float pitchErr = -degrees(vec[at + ObsSpec.ST_ANY_Y]);
			if (far) {
				boolean yawResting = Math.abs(yawPush) < AimMotor.REST_BELOW;
				boolean pitchResting = Math.abs(pitchPush) < AimMotor.REST_BELOW;
				yawBin = nearest(ActionSpace.YAW_BINS, yawResting && Math.abs(yawErr) < FAR_START_DEG ? 0f : wanted(yawErr, yawPush));
				pitchBin = nearest(ActionSpace.PITCH_BINS, pitchResting && Math.abs(pitchErr) < FAR_START_DEG ? 0f : wanted(pitchErr, pitchPush));
				return;
			}
			int before = at + ObsSpec.STATS;
			if (vec[before + ObsSpec.ST_ANY_VISIBLE] >= 0.5f) {
''', 1),
])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''		boolean aim = aimRule != null && (afterHit && AimRule.applies(obs.vec) || farRest && FarRest.applies(obs.vec));
		if (aim) {
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush());
''', '''		boolean hitAim = aimRule != null && afterHit && AimRule.applies(obs.vec);
		boolean aim = hitAim || aimRule != null && farRest && FarRest.applies(obs.vec);
		if (aim) {
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim);
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		boolean aim = !episodeLockedDrill && stage.unlocked(ActionSpace.YAW) && stage.unlocked(ActionSpace.PITCH)
				&& (cfg.aimRule && cfg.answerRule && stage.unlocked(ActionSpace.ATTACK) && AimRule.applies(obs.vec)
						|| cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec));
		if (aim) {
			if (aimRule == null) {
				aimRule = new AimRule(cfg.aimResponse);
			}
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush());
''', '''		boolean camera = !episodeLockedDrill && stage.unlocked(ActionSpace.YAW) && stage.unlocked(ActionSpace.PITCH);
		boolean hitAim = camera && cfg.aimRule && cfg.answerRule && stage.unlocked(ActionSpace.ATTACK) && AimRule.applies(obs.vec);
		boolean aim = hitAim || camera && cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec);
		watchChase();
		if (aim) {
			if (aimRule == null) {
				aimRule = new AimRule(cfg.aimResponse);
			}
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim);
''', 1),
    ('''	/** Ticks since the last update on which the camera was the rule's; see {@code AimRule}. */
	long aimedTicks;
''', '''	/** Ticks since the last update on which the camera was the rule's; see {@code AimRule}. */
	long aimedTicks;
	/**
	 * What became of each jump the chase rule made, since the last update: a critical hit; another
	 * hit; the opponent in reach and under its crosshair on the way down, but no swing; in reach but
	 * never under its crosshair; never in reach.
	 */
	long chaseEndCrit;
	long chaseEndHit;
	long chaseEndNoSwing;
	long chaseEndNoAim;
	long chaseEndFar;
	private boolean chaseWatch;
	private boolean chaseAir;
	private boolean chaseNear;
	private boolean chasePick;
''', 1),
    ('''	/**
	 * A scripted opponent drawn afresh: how quick it is''', '''	/** Follows each jump the chase rule makes from the take-off to the landing; see {@link #chaseEndCrit}. */
	private void watchChase() {
		if (chaseWatch) {
			if (facts.hitsDealt > 0) {
				if (facts.critDealt) {
					chaseEndCrit++;
				} else {
					chaseEndHit++;
				}
				chaseWatch = false;
			} else if (!groundAtAction) {
				chaseAir = true;
				if (facts.falling) {
					chaseNear |= facts.reachDist <= REACH;
					chasePick |= crosshairOnOpponent();
				}
			} else if (chaseAir || facts.hitsTaken > 0) {
				if (chasePick) {
					chaseEndNoSwing++;
				} else if (chaseNear) {
					chaseEndNoAim++;
				} else {
					chaseEndFar++;
				}
				chaseWatch = false;
			}
		}
		if (ruleJumpAtAction == JumpIn.CHASE) {
			chaseWatch = true;
			chaseAir = false;
			chaseNear = false;
			chasePick = false;
		}
	}

	/**
	 * A scripted opponent drawn afresh: how quick it is''', 1),
    ('''other {}, opponent out of reach {} | ticks on which the camera was a rule {}",''',
     '''other {}, opponent out of reach {} | ticks on which the camera was a rule {} | chase jumps ended: crit {}, another hit {}, under the crosshair but no swing {}, in reach but not under the crosshair {}, never in reach {}",''', 1),
    ('''				fallCrits, fallPlain, fallNoAim, fallNoSword, fallOther, fallFar, aimedTicks);
		aimedTicks = 0;
''', '''				fallCrits, fallPlain, fallNoAim, fallNoSword, fallOther, fallFar, aimedTicks,
				chaseEndCrit, chaseEndHit, chaseEndNoSwing, chaseEndNoAim, chaseEndFar);
		aimedTicks = 0;
		chaseEndCrit = 0;
		chaseEndHit = 0;
		chaseEndNoSwing = 0;
		chaseEndNoAim = 0;
		chaseEndFar = 0;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''	static final float FAR_AIM_FROM = 9f;
''', '''	static final float FAR_AIM_FROM = 11f;
''', 1),
])

p = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/agg2.py'
s = open(p, encoding='utf-8').read()
a = "RULES = re.compile("
assert s.count(a) == 1
s = s.replace(a, "CHASE = re.compile(r'chase jumps ended: crit (\\d+), another hit (\\d+), under the crosshair but no swing (\\d+), in reach but not under the crosshair (\\d+), never in reach (\\d+)')\nRULES = re.compile(", 1)
a = "    r = RULES.search(body)\n"
assert s.count(a) == 1
s = s.replace(a, "    c = CHASE.search(body)\n    out['chase'] = [int(x) for x in c.groups()] if c else [0] * 5\n    r = RULES.search(body)\n", 1)
a = "        rules = [0] * 3\n"
assert s.count(a) == 1
s = s.replace(a, "        rules = [0] * 3\n        chase = [0] * 5\n", 1)
a = "            rules = [x + y for x, y in zip(rules, p['rules'])]\n"
assert s.count(a) == 1
s = s.replace(a, a + "            chase = [x + y for x, y in zip(chase, p['chase'])]\n", 1)
a = "        rows.append('\\n'.join(lines))"
assert s.count(a) == 1
s = s.replace(a, """        if args.view in ('rules', 'falls', 'all'):
            lines.append('%s   CHASE jumps ended %d: crit %d, other hit %d, pick but no swing %d, in reach no aim %d, never in reach %d' % (
                head, sum(chase), chase[0], chase[1], chase[2], chase[3], chase[4]))
        rows.append('\\n'.join(lines))""", 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
