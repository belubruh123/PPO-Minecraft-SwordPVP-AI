ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean farRestRule = true;
''', '''	public boolean farRestRule = true;
	/**
	 * With the opponent off the screen for three ticks, the camera turns to the side it was last seen
	 * on and comes level until it is found; see {@code LostRule}. Since 18 October.
	 */
	public boolean lostRule = true;
''', 1),
])
F = 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
edit(F, [
    ('''	private final AimRule aimRule;
''', '''	private final AimRule aimRule;
	/** See {@link LostRule}; null where the camera is not the rules' to turn. */
	private final LostRule lostRule;
''', 1),
    ('''		this.aimRule = (afterHit || farRest) && ActionSpace.unlocked(headMask, ActionSpace.YAW) && ActionSpace.unlocked(headMask, ActionSpace.PITCH)
				? new AimRule(cfg.aimResponse) : null;
''', '''		boolean camera = ActionSpace.unlocked(headMask, ActionSpace.YAW) && ActionSpace.unlocked(headMask, ActionSpace.PITCH);
		this.aimRule = (afterHit || farRest || cfg.lostRule) && camera ? new AimRule(cfg.aimResponse) : null;
		this.lostRule = cfg.lostRule && camera ? new LostRule() : null;
''', 1),
    ('''		if (aimRule != null) {
			aimRule.reset();
		}
		fallWatch.reset();''', '''		if (aimRule != null) {
			aimRule.reset();
		}
		if (lostRule != null) {
			lostRule.reset();
		}
		fallWatch.reset();''', 1),
    ('''		boolean hitAim = aimRule != null && afterHit && (AimRule.applies(obs.vec) || jumpIn != null && jumpIn.kind() != JumpIn.NONE);
		boolean aim = hitAim || aimRule != null && farRest && FarRest.applies(obs.vec);
		if (aim) {
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim);''', '''		boolean hitAim = aimRule != null && afterHit && (AimRule.applies(obs.vec) || jumpIn != null && jumpIn.kind() != JumpIn.NONE);
		// And with the opponent off the screen it is turned to find it; see LostRule.
		boolean lost = lostRule != null && lostRule.applies(obs.vec);
		boolean aim = hitAim || lost || aimRule != null && farRest && FarRest.applies(obs.vec);
		if (aim) {
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim && !lost);''', 1),
])
T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	private AimRule aimRule;
''', '''	private AimRule aimRule;
	/** See {@link LostRule}. */
	private final LostRule lostRule = new LostRule();
''', 1),
    ('''		boolean aim = hitAim || camera && cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec);
		watchChase();
		if (aim) {
			if (aimRule == null) {
				aimRule = new AimRule(cfg.aimResponse);
			}
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim);''', '''		// And with the opponent off the screen it is turned to find it; see LostRule.
		boolean lost = lostRule.applies(obs.vec) && camera && cfg.lostRule;
		boolean aim = hitAim || lost || camera && cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec);
		watchChase();
		if (aim) {
			if (aimRule == null) {
				aimRule = new AimRule(cfg.aimResponse);
			}
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush(), !hitAim && !lost);''', 1),
    ('''			jumpIn = jumpInVsOthers;
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}''', '''			jumpIn = jumpInVsOthers;
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		lostRule.reset();''', 1),
    ('''			jumpIn.newRound();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		punishMemory.reset();
		stepBack.newRound();''', '''			jumpIn.newRound();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		lostRule.reset();
		punishMemory.reset();
		stepBack.newRound();''', 1),
])
print('lost ok')
