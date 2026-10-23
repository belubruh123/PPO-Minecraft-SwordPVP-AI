ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/JumpReset.java', [
    ('''	 * @param vec  what the network is shown on this tick
	 * @param keys the keys that are the network's on this tick
	 * @return''', '''	 * @param vec    what the network is shown on this tick
	 * @param keys   the keys that are the network's on this tick
	 * @param hitNow the body's own game has learned of a hit on it since the last decision: it has the
	 *               knockback and its feet are still on the ground. On a server that is the tick the
	 *               player is seen to flinch.
	 * @return''', 1),
    ('''	public int plan(float[] vec, int keys) {
		tookOff = false;
		boolean ground = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f;
		if (vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] >= 1f - 0.5f / BodyState.EVENT_FADE_TICKS) {
			// Hit on this very tick.
			left = 0;''', '''	public int plan(float[] vec, int keys, boolean hitNow) {
		tookOff = false;
		boolean ground = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] >= 0.5f;
		if (hitNow) {
			left = 0;''', 1),
    ('''	public void reset() {
		left = 0;''', '''	/** Whether what the network is shown says it was hit on this very tick. */
	public static boolean hitOnThisTick(float[] vec) {
		return vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] >= 1f - 0.5f / BodyState.EVENT_FADE_TICKS;
	}

	public void reset() {
		left = 0;''', 1),
])
edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('int reset = jumpReset == null ? 0 : jumpReset.plan(obs.vec, keys);',
     'int reset = jumpReset == null ? 0 : jumpReset.plan(obs.vec, keys, JumpReset.hitOnThisTick(obs.vec));', 1),
])
p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = open(p, encoding='utf-8').read()
for a, b in (('rule.plan(first, ALL)', 'rule.plan(first, ALL, true)'),
             ('rule.plan(second, ALL)', 'rule.plan(second, ALL, true)'),
             ('rule.plan(up, ALL)', 'rule.plan(up, ALL, false)'),
             ('rule.plan(down, ALL)', 'rule.plan(down, ALL, false)'),
             ('rule.plan(inAir, ALL)', 'rule.plan(inAir, ALL, true)'),
             ('rule.plan(second, ALL & ~ActionSpace.mask(ActionSpace.JUMP))', 'rule.plan(second, ALL & ~ActionSpace.mask(ActionSpace.JUMP), true)'),
             ('never.plan(second, ALL)', 'never.plan(second, ALL, true)'),
             ('half.plan(second, ALL)', 'half.plan(second, ALL, true)')):
    assert s.count(a) >= 1, a
    s = s.replace(a, b)
open(p, 'w', encoding='utf-8', newline='').write(s)

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''			reset = jumpReset.plan(obs.vec, keys);
		}''', '''			boolean shown = JumpReset.hitOnThisTick(obs.vec);
			boolean hitNow = bodyHitNow();
			// When the body's own game learns of a hit, against when the trainer does.
			if (shown && hitNow) {
				hitNowSame++;
				hitNowAwaited = false;
			} else if (shown) {
				hitNowAwaited = true;
			} else if (hitNow) {
				if (hitNowAwaited) {
					hitNowLater++;
				} else {
					hitNowAlone++;
				}
				hitNowAwaited = false;
			}
			reset = jumpReset.plan(obs.vec, keys, hitNow);
		}''', 1),
    ('''	/** How far the weapon has recharged, 0 to 1, as the body's own client counts it. */
	abstract float bodyCharge();''', '''	/**
	 * Whether the body's own game has learned of a hit on it since the last tick: it has the knockback
	 * and its feet are still on the ground. What a jump made with the hit goes by ({@link JumpReset}).
	 * A body on the server knows on the tick it is shown; the user's character a moment later, when
	 * its game has been told, and that is the tick a fight on someone's server goes by.
	 */
	boolean bodyHitNow() {
		return JumpReset.hitOnThisTick(obs.vec);
	}

	private int hitNowSame;
	private int hitNowLater;
	private int hitNowAlone;
	private boolean hitNowAwaited;

	/** How far the weapon has recharged, 0 to 1, as the body's own client counts it. */
	abstract float bodyCharge();''', 1),
    ('''chase jumps ended: crit {}, another hit {}, under the crosshair but no swing {}, in reach but not under the crosshair {}, never in reach {}",''',
     '''chase jumps ended: crit {}, another hit {}, under the crosshair but no swing {}, in reach but not under the crosshair {}, never in reach {} | its own game learned of a hit: on the tick it was shown {}, later {}, without {}",''', 1),
    ('''				chaseEndCrit, chaseEndHit, chaseEndNoSwing, chaseEndNoAim, chaseEndFar);
		aimedTicks = 0;''', '''				chaseEndCrit, chaseEndHit, chaseEndNoSwing, chaseEndNoAim, chaseEndFar, hitNowSame, hitNowLater, hitNowAlone);
		hitNowSame = 0;
		hitNowLater = 0;
		hitNowAlone = 0;
		aimedTicks = 0;''', 1),
])
edit('src/client/java/dev/swordai/client/TrainingSession.java', [
    ('''		// While a fight is on, the opponent reaches this screen as late as the fight's lag says.
		SeenLag.follow(truth.botUuid());''', '''		// Whether this game has just learned of a hit on its player: as a fight on a server sees it (FightSession).
		int hurt = mc.player.hurtTime;
		hurtNow = hurt > lastHurt;
		lastHurt = hurt;
		// While a fight is on, the opponent reaches this screen as late as the fight's lag says.
		SeenLag.follow(truth.botUuid());''', 1),
    ('''	@Override
	float bodyCharge() {''', '''	private int lastHurt;
	private boolean hurtNow;

	@Override
	boolean bodyHitNow() {
		return hurtNow;
	}

	@Override
	float bodyCharge() {''', 1),
])
print('hitnow ok')
