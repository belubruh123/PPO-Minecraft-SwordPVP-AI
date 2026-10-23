ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/rl/SwingGate.java', [
    ('''	/** The default for {@code Config.swingMinCharge}: a sword twelve ticks after its last swing. */
	public static final float MIN_CHARGE = 0.95f;
''', '''	/** The default for {@code Config.ruleSwingCharge}: a sword twelve ticks after its last swing. Up to 18 October, the network's line too. */
	public static final float MIN_CHARGE = 0.95f;
	/**
	 * The default for {@code Config.swingMinCharge}, since 18 October: eleven ticks after the last
	 * swing, 0.88 on its own screen, which the game makes 0.92 when the swing is made (it adds half a
	 * tick): more than the nine tenths it asks for knockback and a critical hit, and nine tenths of
	 * the damage. The user's words: a hit with the sword 90% back "is considered ok in game". A
	 * player who has found out that it swings the moment its sword is all the way back, and never
	 * before, can time his own hit to come down a tick before that; the tick is the network's to
	 * use or not. Swings a rule makes for it still wait for {@link #MIN_CHARGE}.
	 */
	public static final float EARLY_CHARGE = 0.88f;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/config/Config.java', [
    ('''	/**
	 * The least charge the weapon must have for the network to be given the attack key at all; see
	 * {@code SwingGate}. Zero leaves every swing to the network, as up to 0.13.3.
	 */
	public float swingMinCharge = dev.swordai.core.rl.SwingGate.MIN_CHARGE;''', '''	/**
	 * The least charge the weapon must have for the network to be given the attack key at all; see
	 * {@code SwingGate}. Zero leaves every swing to the network, as up to 0.13.3. Eleven ticks after
	 * the last swing since 18 October ({@code SwingGate.EARLY_CHARGE}); twelve before.
	 */
	public float swingMinCharge = dev.swordai.core.rl.SwingGate.EARLY_CHARGE;
	/**
	 * The least charge for a swing a rule makes for it (the answer it owes, the run at an opponent on
	 * its way up): the sword all the way back, twelve ticks after the last swing. Whether to swing a
	 * tick sooner is the network's to learn, not a rule's.
	 */
	public float ruleSwingCharge = dev.swordai.core.rl.SwingGate.MIN_CHARGE;''', 1),
    ('''	public float swingMinChargeOnline = 1f;''', '''	public float swingMinChargeOnline = 0.96f;
	/**
	 * The same for the swings a rule makes, on someone's server: thirteen ticks, as every swing was up
	 * to 18 October. The network's own may come a tick sooner ({@link #swingMinChargeOnline}, twelve
	 * ticks: on a connection that brings two swings a tick closer together the server counts eleven,
	 * 0.92, which is still more than the nine tenths it asks for).
	 */
	public float ruleSwingChargeOnline = 1f;''', 1),
])

F = 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
edit(F, [
    ('''	private float swingMinCharge;
	private int swingExtraTicks;
''', '''	private float swingMinCharge;
	/** The least charge for a swing a rule makes; see {@code Config.ruleSwingCharge}. */
	private float ruleMinCharge;
	private int swingExtraTicks;
''', 1),
    ('''		this.swingMinCharge = cfg.swingMinCharge;
''', '''		this.swingMinCharge = cfg.swingMinCharge;
		this.ruleMinCharge = Math.max(cfg.swingMinCharge, cfg.ruleSwingCharge);
''', 1),
    ('''		swingMinCharge = Math.max(swingMinCharge, charge);
		swingExtraTicks = Math.max(0, extraTicks);
	}
''', '''		swingMinCharge = Math.max(swingMinCharge, charge);
		ruleMinCharge = Math.max(ruleMinCharge, swingMinCharge);
		swingExtraTicks = Math.max(0, extraTicks);
	}

	/** The fight is on someone's server: a swing a rule makes waits for at least this; see {@code Config.ruleSwingChargeOnline}. */
	public void rulesNoEarlierThan(float charge) {
		ruleMinCharge = Math.max(ruleMinCharge, charge);
	}
''', 1),
    ('''		int keys = SwingGate.keys(headMask, waiting ? 0f : body.charge, swingMinCharge);
		// And it is pressed for it where it owes the opponent an answer and one is there to be made; see AnswerRule.
		boolean owes = answerRule && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent);''', '''		int keys = SwingGate.keys(headMask, waiting ? 0f : body.charge, swingMinCharge);
		// A swing a rule makes waits for the sword to be all the way back; see Config.ruleSwingCharge.
		boolean ruleReady = !waiting && body.charge >= ruleMinCharge;
		// And it is pressed for it where it owes the opponent an answer and one is there to be made; see AnswerRule.
		boolean owes = answerRule && swingMinCharge > 0f && ruleReady && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent);''', 1),
    ('''		boolean meetSwing = meet && meetSwings && !owes && crosshairOnOpponent && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''',
     '''		boolean meetSwing = meet && meetSwings && !owes && ruleReady && crosshairOnOpponent && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''', 1),
])

edit('src/client/java/dev/swordai/client/FightSession.java', [
    ('''			fighter.swingNoEarlierThan(loaded.cfg.swingMinChargeOnline, loaded.cfg.swingExtraTicksOnline);
''', '''			fighter.swingNoEarlierThan(loaded.cfg.swingMinChargeOnline, loaded.cfg.swingExtraTicksOnline);
			fighter.rulesNoEarlierThan(loaded.cfg.ruleSwingChargeOnline);
''', 1),
])

T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
    ('''	float swingMinCharge() {
		return cfg.swingMinCharge;
	}
''', '''	float swingMinCharge() {
		return cfg.swingMinCharge;
	}

	/** The least charge for a swing a rule makes; see {@code Config.ruleSwingCharge}. */
	float ruleSwingCharge() {
		return Math.max(swingMinCharge(), cfg.ruleSwingCharge);
	}
''', 1),
    ('''		boolean owes = cfg.answerRule && swingMinCharge() > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent());''', '''		boolean owes = cfg.answerRule && swingMinCharge() > 0f && chargeAtClick >= ruleSwingCharge() && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent());''', 1),
    ('''		boolean meetSwing = meet && meetGround && !owes && crosshairOnOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''',
     '''		boolean meetSwing = meet && meetGround && !owes && chargeAtClick >= ruleSwingCharge() && crosshairOnOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK);''', 1),
])
edit('src/client/java/dev/swordai/client/FightLearnSession.java', [
    ('''		return mc.hasSingleplayerServer() ? cfg.swingMinCharge : Math.max(cfg.swingMinCharge, cfg.swingMinChargeOnline);
	}
''', '''		return mc.hasSingleplayerServer() ? cfg.swingMinCharge : Math.max(cfg.swingMinCharge, cfg.swingMinChargeOnline);
	}

	@Override
	float ruleSwingCharge() {
		return mc.hasSingleplayerServer() ? super.ruleSwingCharge() : Math.max(super.ruleSwingCharge(), cfg.ruleSwingChargeOnline);
	}
''', 1),
])
edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [
    ('''	private static final float FULL_CHARGE = 0.9f;
''', '''	private static final float FULL_CHARGE = 0.9f;
	/** The same on its own screen, where the game has not yet added its half tick: eleven ticks after the last swing. */
	private static final float FULL_CHARGE_AT_CLICK = dev.swordai.core.rl.SwingGate.EARLY_CHARGE - 0.005f;
''', 1),
    ('''f.reachAtAction <= w.answerWithin && f.chargeAtClick >= FULL_CHARGE) {''', '''f.reachAtAction <= w.answerWithin && f.chargeAtClick >= FULL_CHARGE_AT_CLICK) {''', 1),
])

edit('core/src/test/java/dev/swordai/core/curriculum/FightRulesTest.java', [
    ('''		assertTrue(cfg.swingMinCharge > 0.9f && cfg.swingMinCharge < 1f, "a tick after the game would allow it, and not a tick more");
		assertEquals(1f, cfg.swingMinChargeOnline, "on a server, one tick in hand: the weapon all the way back");''',
     '''		assertTrue(cfg.swingMinCharge > 0.85f && cfg.swingMinCharge < 0.9f, "eleven ticks: the game adds half a tick and makes it more than nine tenths");
		assertTrue(cfg.ruleSwingCharge > 0.9f && cfg.ruleSwingCharge < 1f, "a rule's swing waits a tick more");
		assertEquals(0.96f, cfg.swingMinChargeOnline, 1e-6f, "on a server, one tick in hand: twelve ticks");
		assertEquals(1f, cfg.ruleSwingChargeOnline, "and a rule's swing the weapon all the way back");''', 1),
])
FT = 'core/src/test/java/dev/swordai/core/rl/FighterTest.java'
edit(FT, [
    ('''		assertTrue(swingsAt(1f, SwingGate.MIN_CHARGE) > 0);
	}
''', '''		assertTrue(swingsAt(1f, SwingGate.MIN_CHARGE) > 0);
		// Since 18 October the network has that tick: eleven, and not ten.
		assertEquals(0, swingsAt(10f / 12.5f, SwingGate.EARLY_CHARGE));
		assertTrue(swingsAt(11f / 12.5f, SwingGate.EARLY_CHARGE) > 0);
	}
''', 1),
    ('''	/** On someone's server it has a tick in hand: not at twelve ticks, where its own screen has the weapon back, but at thirteen. */
	@Test
	void onAServerItWaitsForTheWeaponToBeAllTheWayBack() {''', '''	/** On someone's server it has a tick in hand: not at eleven ticks, but at twelve. */
	@Test
	void onAServerItHasATickInHand() {''', 1),
    ('''			int atTwelve = 0;
			int atThirteen = 0;
			for (int tick = 0; tick < 400; tick++) {
				atTwelve += fighter.decide(frame, body(12f / 12.5f))[ActionSpace.ATTACK];
			}
			for (int tick = 0; tick < 400; tick++) {
				atThirteen += fighter.decide(frame, body(1f))[ActionSpace.ATTACK];
			}
			assertEquals(0, atTwelve);
			assertTrue(atThirteen > 0);''', '''			int atTwelve = 0;
			int atThirteen = 0;
			for (int tick = 0; tick < 400; tick++) {
				atTwelve += fighter.decide(frame, body(11f / 12.5f))[ActionSpace.ATTACK];
			}
			for (int tick = 0; tick < 400; tick++) {
				atThirteen += fighter.decide(frame, body(12f / 12.5f))[ActionSpace.ATTACK];
			}
			assertEquals(0, atTwelve);
			assertTrue(atThirteen > 0);''', 1),
])
print('gate ok')
