import io


def load(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    return s.replace('\r\n', '\n'), nl


def save(p, s, nl):
    io.open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', nl))


def rep(s, a, b, n=1):
    assert s.count(a) == n, (s.count(a), a[:90])
    return s.replace(a, b)


root = 'D:/swordai/'

# ---- Config
p = root + 'core/src/main/java/dev/swordai/core/config/Config.java'
s, nl = load(p)
s = rep(s, '''	public float swingMinCharge = dev.swordai.core.rl.SwingGate.MIN_CHARGE;
''', '''	public float swingMinCharge = dev.swordai.core.rl.SwingGate.MIN_CHARGE;
	/**
	 * The same on someone's server, where it is at least this: the weapon all the way back, which is
	 * one tick more (thirteen after the last swing). A server counts the time between two swings in its
	 * own ticks, from when they reach it. If two of them arrive a tick or two closer together than they
	 * were made, or the server is running slow, a swing made the moment the weapon was back on the
	 * fighter's own screen is one made too early as the server counts it: no knockback, no critical
	 * hit, less damage. The tick in hand is what keeps that from happening.
	 */
	public float swingMinChargeOnline = 1f;
''')
s = rep(s, 'CURRENT_VERSION = 41;', 'CURRENT_VERSION = 42;')
save(p, s, nl)

# ---- Fighter
p = root + 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
s, nl = load(p)
s = rep(s, '	private final float swingMinCharge;\n', '	private float swingMinCharge;\n')
i = s.index('	public Fighter(Learner learner, Config cfg, int headMask, boolean ideasLearned, float temperature, long seed) {')
# insert a setter before the javadoc of that constructor
j = s.rfind('	/**', 0, i)
setter = '''	/**
	 * The fight is on someone's server: the weapon has to be at least this far back before it swings,
	 * if that is more than the rule already asks; see {@code Config.swingMinChargeOnline}.
	 */
	public void swingNoEarlierThan(float charge) {
		swingMinCharge = Math.max(swingMinCharge, charge);
	}

'''
s = s[:j] + setter + s[j:]
save(p, s, nl)

# ---- FightSession
p = root + 'src/client/java/dev/swordai/client/FightSession.java'
s, nl = load(p)
s = rep(s, '''		this.fighter = loaded.fighter;
''', '''		this.fighter = loaded.fighter;
		if (!mc.hasSingleplayerServer()) {
			// A tick in hand on someone's server, so that a swing is never an early one as the server counts it.
			fighter.swingNoEarlierThan(loaded.cfg.swingMinChargeOnline);
		}
''')
save(p, s, nl)

# ---- Trainer: the rule's threshold is a method, so that a fight on a server can ask for more
p = root + 'src/client/java/dev/swordai/client/Trainer.java'
s, nl = load(p)
s = rep(s, '''		return SwingGate.keys(keysOfTheFight(), chargeAtClick, cfg.swingMinCharge);
	}
''', '''		return SwingGate.keys(keysOfTheFight(), chargeAtClick, swingMinCharge());
	}

	/** How far back the weapon has to be before the attack key is the network's; more on someone's server. */
	float swingMinCharge() {
		return cfg.swingMinCharge;
	}
''')
save(p, s, nl)

# ---- FightLearnSession
p = root + 'src/client/java/dev/swordai/client/FightLearnSession.java'
s, nl = load(p)
s = rep(s, '''	@Override
	float bodyCharge() {
		return mc.player.getAttackStrengthScale(0f);
	}
''', '''	@Override
	float bodyCharge() {
		return mc.player.getAttackStrengthScale(0f);
	}

	@Override
	float swingMinCharge() {
		// A tick in hand on someone's server; see Config.swingMinChargeOnline.
		return mc.hasSingleplayerServer() ? cfg.swingMinCharge : Math.max(cfg.swingMinCharge, cfg.swingMinChargeOnline);
	}
''')
save(p, s, nl)
print('ok')
