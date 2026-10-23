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

# ---- Fighter
p = root + 'core/src/main/java/dev/swordai/core/rl/Fighter.java'
s, nl = load(p)
s = rep(s, '''	private final boolean answerRule;
''', '''	private final boolean answerRule;
	/** The game's pick as of this decision: the opponent under the crosshair and within reach; see {@link AnswerRule}. */
	private boolean crosshairOnOpponent;
	/** The swing of the last decision was the rule's, not the network's. */
	private boolean answerForced;
''')
s = rep(s, '''		boolean answer = answerRule && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK) && AnswerRule.applies(obs.vec);
''', '''		boolean answer = answerRule && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent);
		answerForced = answer;
''')
s = rep(s, '''	/**
	 * The fight is on someone's server: the weapon has to be at least this far back before it swings,''', '''	/**
	 * To be told before every decision: whether the game has the opponent under the crosshair and
	 * within reach right now. What the rule about answering a hit goes by; see {@link AnswerRule}.
	 */
	public void crosshairOnOpponent(boolean on) {
		crosshairOnOpponent = on;
	}

	/** Whether the swing of the last decision was made by that rule. Such a swing is only to be carried out if the opponent is still under the crosshair. */
	public boolean answerForced() {
		return answerForced;
	}

	/** The rule's swing was not carried out: by the time of the click the opponent was no longer under the crosshair. */
	public void answerCancelled() {
		answerForced = false;
		action[ActionSpace.ATTACK] = 0;
	}

	/**
	 * The fight is on someone's server: the weapon has to be at least this far back before it swings,''')
save(p, s, nl)

# ---- ReplicaBody
p = root + 'src/main/java/dev/swordai/server/ReplicaBody.java'
s, nl = load(p)
s = rep(s, '''	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate) {
		EntityPlayerActionPack pack''', '''	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate) {
		act(body, opponent, action, yawRate, pitchRate, false);
	}

	/**
	 * @param swingOnlyIfItReaches the swing of this action was not the network's own but made for it by
	 *                             the rule about answering a hit ({@code AnswerRule}): it is only made
	 *                             if the opponent is under the crosshair once the camera has turned
	 */
	public void act(ServerPlayer body, ServerPlayer opponent, int[] action, float yawRate, float pitchRate, boolean swingOnlyIfItReaches) {
		EntityPlayerActionPack pack''')
s = rep(s, '''		clickOnTarget = false;
		if (action[ActionSpace.ATTACK] == 1) {
			click(body, opponent);
		}
''', '''		clickOnTarget = false;
		swingLeftOut = false;
		if (action[ActionSpace.ATTACK] == 1) {
			if (swingOnlyIfItReaches && !aimedAt(body, opponent)) {
				swingLeftOut = true;
			} else {
				click(body, opponent);
			}
		}
''')
s = rep(s, '''	private boolean clickOnTarget;
''', '''	private boolean clickOnTarget;
	/** The swing asked for in the last {@link #act} was one to be made only if it reaches, and it would not have. */
	private boolean swingLeftOut;
''')
s = rep(s, '''	public boolean clickOnTarget() {
		return clickOnTarget;
	}
''', '''	public boolean clickOnTarget() {
		return clickOnTarget;
	}

	/** Whether the swing asked for in the last {@link #act} was left out because it would not have reached the opponent. */
	public boolean swingLeftOut() {
		return swingLeftOut;
	}

	/**
	 * What a client's crosshair would be on right now: whether a click made this instant, with the
	 * view as it is, would be on the opponent and within reach. The game's own pick, as in
	 * {@link #click}: the opponent where the client shows it, and no block in the way.
	 */
	public boolean aimedAt(ServerPlayer body, ServerPlayer opponent) {
		Vec3 from = body.getEyePosition();
		double entityRange = body.entityInteractionRange();
		double range = Math.max(entityRange, body.blockInteractionRange());
		HitResult block = body.pick(range, 1f, false);
		double limitSqr = range * range;
		if (block.getType() != HitResult.Type.MISS) {
			limitSqr = block.getLocation().distanceToSqr(from);
			range = Math.sqrt(limitSqr);
		}
		AABB target = shownBox(opponent).inflate(opponent.getPickRadius());
		Vec3 to = from.add(body.getLookAngle().scale(range));
		Optional<Vec3> entry = target.contains(from) ? Optional.of(from) : target.clip(from, to);
		return entry.isPresent() && entry.get().distanceToSqr(from) < limitSqr && entry.get().closerThan(from, entityRange);
	}
''')
save(p, s, nl)

# ---- BotPilot
p = root + 'src/main/java/dev/swordai/server/BotPilot.java'
s, nl = load(p)
s = rep(s, '''		body.sense(bot, opponent, floorY);
		int[] action = fighter.decide(body.frame(), body.state());
		body.act(bot, opponent, action, fighter.yawRate(), fighter.pitchRate());
''', '''		body.sense(bot, opponent, floorY);
		// What its crosshair is on, for the rule about answering a hit; see AnswerRule.
		fighter.crosshairOnOpponent(body.aimedAt(bot, opponent));
		int[] action = fighter.decide(body.frame(), body.state());
		body.act(bot, opponent, action, fighter.yawRate(), fighter.pitchRate(), fighter.answerForced());
		if (body.swingLeftOut()) {
			fighter.answerCancelled();
		}
''')
save(p, s, nl)

# ---- Trainer
p = root + 'src/client/java/dev/swordai/client/Trainer.java'
s, nl = load(p)
s = rep(s, '''		boolean answer = cfg.answerRule && swingMinCharge() > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK) && AnswerRule.applies(obs.vec);
''', '''		boolean answer = cfg.answerRule && swingMinCharge() > 0f && ActionSpace.unlocked(keys, ActionSpace.ATTACK)
				&& AnswerRule.applies(obs.vec, crosshairOnOpponent());
		answerAtAction = answer;
''')
s = rep(s, '''	/** Swings the rule made since the last update: hit, sword back, opponent under the crosshair and near; see {@code AnswerRule}. */
	long forcedAnswers;
''', '''	/** Swings the rule made since the last update: hit, sword back, opponent under the crosshair and in reach; see {@code AnswerRule}. */
	long forcedAnswers;
	/** The swing of the step just chosen is the rule's, not the network's: it is only made if the opponent is still under the crosshair at the click. */
	boolean answerAtAction;
''')
s = rep(s, '''	/** How far back the weapon has to be before the attack key is the network's; more on someone's server. */''', '''	/** Whether the body's own game has the opponent under the crosshair and within reach right now; see {@code AnswerRule}. */
	boolean crosshairOnOpponent() {
		return false;
	}

	/** The rule's swing was not carried out: at the click the opponent was no longer under the crosshair. Nothing was swung. */
	void answerCancelled() {
		if (answerAtAction) {
			answerAtAction = false;
			clicked = false;
			action[ActionSpace.ATTACK] = 0;
			forcedAnswers--;
		}
	}

	/** How far back the weapon has to be before the attack key is the network's; more on someone's server. */''')
save(p, s, nl)

# ---- TrainingSession
p = root + 'src/client/java/dev/swordai/client/TrainingSession.java'
s, nl = load(p)
s = rep(s, '''		actedThisTick = false;
		AgentController.hold(mc, action);
		return clicked;
	}
''', '''		actedThisTick = false;
		AgentController.hold(mc, action);
		if (clicked && answerAtAction && !crosshairOnOpponent()) {
			// The rule's swing is only made at an opponent that is under the crosshair now that the camera has turned.
			answerCancelled();
		}
		return clicked;
	}

	@Override
	boolean crosshairOnOpponent() {
		return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == currentBot;
	}
''')
save(p, s, nl)

# ---- FightLearnSession
p = root + 'src/client/java/dev/swordai/client/FightLearnSession.java'
s, nl = load(p)
s = rep(s, '''		actedThisTick = false;
		AgentController.hold(mc, action);
		return clicked;
	}
''', '''		actedThisTick = false;
		AgentController.hold(mc, action);
		if (clicked && answerAtAction && !crosshairOnOpponent()) {
			// The rule's swing is only made at an opponent that is under the crosshair now that the camera has turned.
			answerCancelled();
		}
		return clicked;
	}

	@Override
	boolean crosshairOnOpponent() {
		return opponent != null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == opponent;
	}
''')
if 'import net.minecraft.world.phys.EntityHitResult;' not in s:
    i = s.index('import net.minecraft.')
    s = s[:i] + 'import net.minecraft.world.phys.EntityHitResult;\n' + s[i:]
save(p, s, nl)

# ---- ReplicaTrainer
p = root + 'src/client/java/dev/swordai/client/ReplicaTrainer.java'
s, nl = load(p)
s = rep(s, '''		replica.act(agent, bot, action, yawRate, pitchRate);
		lastYawTurn = replica.yawTurned();
''', '''		replica.act(agent, bot, action, yawRate, pitchRate, answerAtAction);
		if (replica.swingLeftOut()) {
			answerCancelled();
		}
		lastYawTurn = replica.yawTurned();
''')
s = rep(s, '''	@Override
	boolean clickWasOnTarget() {
		return replica.clickOnTarget();
	}
''', '''	@Override
	boolean clickWasOnTarget() {
		return replica.clickOnTarget();
	}

	@Override
	boolean crosshairOnOpponent() {
		return agent != null && bot != null && replica.aimedAt(agent, bot);
	}
''')
save(p, s, nl)

# ---- FightSession
p = root + 'src/client/java/dev/swordai/client/FightSession.java'
s, nl = load(p)
s = rep(s, '''		observer.fillBody(body, me, opp, groundY);
		int[] action = fighter.decide(observer.frame, body);
''', '''		observer.fillBody(body, me, opp, groundY);
		// What the crosshair is on, for the rule about answering a hit; see AnswerRule.
		fighter.crosshairOnOpponent(onOpponent());
		int[] action = fighter.decide(observer.frame, body);
''')
s = rep(s, '''		actedThisTick = false;
		AgentController.hold(mc, fighter.action());
		return clicked;
	}
''', '''		actedThisTick = false;
		AgentController.hold(mc, fighter.action());
		if (clicked && fighter.answerForced() && !onOpponent()) {
			// The rule's swing is only made at an opponent that is under the crosshair now that the camera has turned.
			fighter.answerCancelled();
			clicked = false;
			clicks--;
		}
		return clicked;
	}

	/** The game's own pick: the opponent is under the crosshair and within reach. */
	private boolean onOpponent() {
		return opponent != null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == opponent;
	}
''')
save(p, s, nl)

# ---- Curriculum: the push for S is steered on fewer ticks
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Curriculum.java'
s, nl = load(p)
s = rep(s, '''	static final int NUDGE_MIN_TICKS = 40;
''', '''	static final int NUDGE_MIN_TICKS = 40;
	/**
	 * The same for the push towards S, which has far fewer ticks to go by: the ones right after a hit
	 * of a combo with the opponent still within three blocks, and a hit throws the fighter beyond
	 * that in a tick or two. With forty asked for, most updates of a fight against a copy of itself
	 * had too few and the push stayed where it was (0.14.0: three updates in eighteen moved it).
	 */
	static final int BACK_MIN_TICKS = 12;
''')
s = rep(s, '''		} else if (batch.backTicks >= NUDGE_MIN_TICKS) {''', '''		} else if (batch.backTicks >= BACK_MIN_TICKS) {''')
save(p, s, nl)

p = root + 'core/src/test/java/dev/swordai/core/curriculum/CurriculumTest.java'
s, nl = load(p)
s = rep(s, '''		c.record(backBatch(100, Curriculum.NUDGE_MIN_TICKS - 1, 0));''', '''		c.record(backBatch(100, Curriculum.BACK_MIN_TICKS - 1, 0));''')
save(p, s, nl)
print('ok')
