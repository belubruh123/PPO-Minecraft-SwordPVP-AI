ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/AimMotor.java', [
    ('''	/** Degrees per tick; positive looks down. */
	public float pitchRate() {
		return pitch.rate();
	}
''', '''	/** Degrees per tick; positive looks down. */
	public float pitchRate() {
		return pitch.rate();
	}

	/** The speed the hand is carrying on the yaw axis, moving or not yet; degrees per tick. */
	public float yawPush() {
		return yaw.push;
	}

	public float pitchPush() {
		return pitch.push;
	}
''', 1)])

edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	public boolean farRestRule = true;
''', '''	public boolean farRestRule = true;
	/**
	 * In the ticks after it has been hit, until it has hit back, the camera is brought onto the
	 * opponent for it; see {@code AimRule}.
	 */
	public boolean aimRule = true;
''', 1)])

edit('core/src/main/java/dev/swordai/core/rl/JumpIn.java', [
    ('''	static final int NOT_AFTER_HIT_TAKEN_TICKS = 12;
''', '''	static final int NOT_AFTER_HIT_TAKEN_TICKS = 12;
	/**
	 * Hit in the air of a jump made for it, that kind of jump is not made again for this long: the
	 * opponent is one that hits a fighter out of the air. Fifteen seconds for the jump at one that is
	 * backing off, thirty for the one in its face.
	 */
	static final int CHASE_OFF_TICKS = 300;
	static final int FACE_OFF_TICKS = 600;
''', 1),
    ('''	private int stays;
''', '''	private int stays;
	private int chaseOff;
	private int faceOff;
''', 1),
    ('''		stays = 0;
		stayJudged = false;
''', '''		stays = 0;
		chaseOff = 0;
		faceOff = 0;
		stayJudged = false;
''', 1),
    ('''		if (kind != NONE) {
			if (busy) {
				// Hit in the air: what follows is the swing it owes, which has a rule of its own.
				kind = NONE;
				return 0;
			}
''', '''		if (chaseOff > 0) {
			chaseOff--;
		}
		if (faceOff > 0) {
			faceOff--;
		}
		if (kind != NONE) {
			if (busy) {
				// Hit in the air: what follows is the swing it owes, which has a rule of its own. And
				// this opponent is one to be careful with.
				if (kind == FACE) {
					faceOff = FACE_OFF_TICKS;
				} else {
					chaseOff = CHASE_OFF_TICKS;
				}
				kind = NONE;
				return 0;
			}
''', 1),
    ('''		if (face && stayed() && !comboed''', '''		if (face && faceOff <= 0 && stayed() && !comboed''', 1),
    ('''		if (upperNext && notHitLately && combo >= 3''', '''		if (upperNext && chaseOff <= 0 && notHitLately && combo >= 3''', 1),
    ('''				&& (d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))
						|| closing <= PURSUIT_CLOSING_MOST && d <= PURSUIT_TO)) {''',
     '''				&& (chaseOff <= 0 && d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))
						|| closing <= PURSUIT_CLOSING_MOST && d <= PURSUIT_TO)) {''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/Tally.java', [
    ('''	public long farTurnCentiDeg;
	/** The ticks in which backing out''', '''	public long farTurnCentiDeg;
	/**
	 * What became of each fall it was knocked into by a hit: a critical hit on the way down; a hit
	 * that was not one, made first; the opponent in reach on the way down but never under its
	 * crosshair; that, but never with the sword back; anything else; and the opponent never in reach.
	 */
	public long fallCrits;
	public long fallPlain;
	public long fallNoAim;
	public long fallNoSword;
	public long fallOther;
	public long fallFar;
	/** The ticks in which backing out''', 1),
    ('''		farTurnCentiDeg += t.farTurnCentiDeg;
''', '''		farTurnCentiDeg += t.farTurnCentiDeg;
		fallCrits += t.fallCrits;
		fallPlain += t.fallPlain;
		fallNoAim += t.fallNoAim;
		fallNoSword += t.fallNoSword;
		fallOther += t.fallOther;
		fallFar += t.fallFar;
''', 2),
    ('''		farTurnCentiDeg = 0;
''', '''		farTurnCentiDeg = 0;
		fallCrits = 0;
		fallPlain = 0;
		fallNoAim = 0;
		fallNoSword = 0;
		fallOther = 0;
		fallFar = 0;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [
    ('''	private final boolean farRest;
''', '''	private final boolean farRest;
	/** The camera brought onto the opponent after a hit; see {@link AimRule}. Null where that is not a rule. */
	private final AimRule aimRule;
''', 1),
    ('''		this.farRest = cfg.farRestRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD);
''', '''		this.farRest = cfg.farRestRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD);
		this.aimRule = cfg.aimRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.ATTACK)
				&& ActionSpace.unlocked(headMask, ActionSpace.YAW) && ActionSpace.unlocked(headMask, ActionSpace.PITCH)
				? new AimRule(cfg.aimResponse) : null;
''', 1),
    ('''		if (jumpIn != null) {
			jumpIn.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', '''		if (jumpIn != null) {
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
	}

	/** Whether its plan may hold swings back''', 1),
    ('''		if (farRest && FarRest.applies(obs.vec)) {
			keys = FarRest.keys(keys);
		}
		learner.act(obs, keys, false, false, temperature, action);
''', '''		if (farRest && FarRest.applies(obs.vec)) {
			keys = FarRest.keys(keys);
		}
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		boolean aim = aimRule != null && AimRule.applies(obs.vec);
		if (aim) {
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush());
			keys &= ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		}
		learner.act(obs, keys, false, false, temperature, action);
		if (aim) {
			action[ActionSpace.YAW] = aimRule.yawBin();
			action[ActionSpace.PITCH] = aimRule.pitchBin();
		}
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	private JumpIn jumpIn;
''', '''	private JumpIn jumpIn;
	/** The camera brought onto the opponent after a hit; see {@code AimRule}. */
	private AimRule aimRule;
''', 1),
    ('''	long fallNoSword;
	long fallOther;
	private boolean fallWatch;
''', '''	long fallNoSword;
	long fallOther;
	/** Ticks since the last update on which the camera was the rule's; see {@code AimRule}. */
	long aimedTicks;
	private boolean fallWatch;
''', 1),
    ('''				if (fallCrit) {
					fallCrits++;
				} else if (fallHit) {
					fallPlain++;
				} else if (fallTicks > 0) {
					if (!fallNear) {
						fallFar++;
					} else if (!fallPick) {
						fallNoAim++;
					} else if (!fallSword) {
						fallNoSword++;
					} else {
						fallOther++;
					}
				}
''', '''				Tally t = episodeTally();
				if (fallCrit) {
					fallCrits++;
					t.fallCrits++;
				} else if (fallHit) {
					fallPlain++;
					t.fallPlain++;
				} else if (fallTicks > 0) {
					if (!fallNear) {
						fallFar++;
						t.fallFar++;
					} else if (!fallPick) {
						fallNoAim++;
						t.fallNoAim++;
					} else if (!fallSword) {
						fallNoSword++;
						t.fallNoSword++;
					} else {
						fallOther++;
						t.fallOther++;
					}
				}
''', 1),
    ('''			if (cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec)) {
				keys = FarRest.keys(keys);
			}
		}
''', '''			if (cfg.farRestRule && stage.unlocked(ActionSpace.FORWARD) && FarRest.applies(obs.vec)) {
				keys = FarRest.keys(keys);
			}
		}
		// Hit and not yet having hit back, the camera is brought onto the opponent for it; see AimRule.
		boolean aim = !episodeLockedDrill && cfg.aimRule && cfg.answerRule && stage.unlocked(ActionSpace.ATTACK)
				&& stage.unlocked(ActionSpace.YAW) && stage.unlocked(ActionSpace.PITCH) && AimRule.applies(obs.vec);
		if (aim) {
			if (aimRule == null) {
				aimRule = new AimRule(cfg.aimResponse);
			}
			aimRule.plan(obs.vec, motor.yawPush(), motor.pitchPush());
			keys &= ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
			aimedTicks++;
		}
''', 1),
    ('''		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerAtAction = true;
			forcedAnswers++;
		}
		if (groundAtAction) {
''', '''		JumpIn.apply(action, jump);
		if ((jump & JumpIn.SWING) != 0) {
			// Like the swing it owes: only carried out with the opponent still under the crosshair.
			answerAtAction = true;
			forcedAnswers++;
		}
		if (aim) {
			action[ActionSpace.YAW] = aimRule.yawBin();
			action[ActionSpace.PITCH] = aimRule.pitchBin();
		}
		if (groundAtAction) {
''', 1),
    ('''		if (jumpIn != null) {
			jumpIn.reset();
		}
		drillJumpOutOfZone = false;
''', '''		if (jumpIn != null) {
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		drillJumpOutOfZone = false;
''', 1),
    ('''found again %d times in %d ticks, in view %d",''',
     '''found again %d times in %d ticks, in view %d, falls: crit %d plain %d noaim %d nosword %d other %d far %d",''', 1),
    ('''t.reacquires, t.reacquireTicks, t.visibleTicks);''', '''t.reacquires, t.reacquireTicks, t.visibleTicks,
				t.fallCrits, t.fallPlain, t.fallNoAim, t.fallNoSword, t.fallOther, t.fallFar);''', 1),
    ('''other {}, opponent out of reach {}",''', '''other {}, opponent out of reach {} | ticks on which the camera was a rule {}",''', 1),
    ('''				fallCrits, fallPlain, fallNoAim, fallNoSword, fallOther, fallFar);
''', '''				fallCrits, fallPlain, fallNoAim, fallNoSword, fallOther, fallFar, aimedTicks);
		aimedTicks = 0;
''', 1),
])
s = open(ROOT + 'src/client/java/dev/swordai/client/Trainer.java', encoding='utf-8').read()
if 'import dev.swordai.core.rl.AimRule;' not in s:
    s = s.replace('import dev.swordai.core.rl.AnswerRule;', 'import dev.swordai.core.rl.AimRule;\nimport dev.swordai.core.rl.AnswerRule;', 1)
    open(ROOT + 'src/client/java/dev/swordai/client/Trainer.java', 'w', encoding='utf-8', newline='').write(s)

p = 'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/agg2.py'
s = open(p, encoding='utf-8').read()
a = "(?:, found again (?P<ra>\\d+) times in (?P<rat>\\d+) ticks, in view (?P<vis>\\d+))?)?')"
assert s.count(a) == 1
s = s.replace(a, "(?:, found again (?P<ra>\\d+) times in (?P<rat>\\d+) ticks, in view (?P<vis>\\d+)(?:, falls: crit (?P<fc>\\d+) plain (?P<fp>\\d+) noaim (?P<fna>\\d+) nosword (?P<fns>\\d+) other (?P<fo>\\d+) far (?P<ffar>\\d+))?)?)?')")
a = """    return 'chase %2.0f(crit %2.0f) face %2.0f(crit %2.0f) upper %2.0f(hit %2.0f) hitInAir %2.0f upc %2.0f | %3.0f/%-3.0f c%3.0f%% sw%2.0f early%1.0f' % (
        a['rcj'], a['rcc'], a['rfj'], a['rfc'], a['ruj'], a['ruh'], a['rhit'], a['upc'], a['h'] / m, a['t'] / m,
        pct(a['c'], a['h']), a['sw'], a['early'])"""
assert s.count(a) == 1
s = s.replace(a, """    near = a.get('fc', 0) + a.get('fp', 0) + a.get('fna', 0) + a.get('fns', 0) + a.get('fo', 0)
    return 'chase %2.0f(crit %2.0f) face %2.0f(crit %2.0f) upper %2.0f(hit %2.0f) hitInAir %2.0f upc %2.0f | sw%2.0f early%1.0f | falls in reach %3.0f: crit %2.0f%% plain %2.0f%% noaim %2.0f%% nosword %2.0f%% other %2.0f%% (far %3.0f)' % (
        a['rcj'], a['rcc'], a['rfj'], a['rfc'], a['ruj'], a['ruh'], a['rhit'], a['upc'], a['sw'], a['early'],
        near, pct(a.get('fc', 0), near), pct(a.get('fp', 0), near), pct(a.get('fna', 0), near), pct(a.get('fns', 0), near),
        pct(a.get('fo', 0), near), a.get('ffar', 0))""")
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
