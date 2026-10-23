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

# ---- RewardWeights: an attempt in the air near the edge of reach is not charged
p = root + 'core/src/main/java/dev/swordai/core/curriculum/RewardWeights.java'
s, nl = load(p)
s = rep(s, '''	public float airWhiff = 0f;
''', '''	public float airWhiff = 0f;
	/**
	 * Above zero: a swing in the air that reaches nothing is only charged {@link #airWhiff} if the
	 * opponent was farther away than this when it was made. Nearer than that it was an attempt: the
	 * opponent was at the edge of the sword's reach or a step beyond it, which is where an opponent
	 * that is comboing the fighter stands, and where a person is a tick late or half a step too close
	 * every few swings. Such a swing costs the sword's charge and nothing else, so that whether it is
	 * worth making is learned from how often it lands. Zero: every swing in the air at nothing is charged.
	 *
	 * <p>Up to the first runs of 0.14.0 every one was charged, at 1.0. Comboed by a person, who keeps
	 * at three blocks, it was in the air from one hit to the next and did not swing once.
	 */
	public float airWhiffBeyond = 0f;
''')
save(p, s, nl)

# ---- RewardModel
p = root + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java'
s, nl = load(p)
s = rep(s, '''				if (!f.clickOnTarget) {
					r -= w.airWhiff;
					tally.airWhiffs++;
				}
''', '''				if (!f.clickOnTarget) {
					// Not an attempt at an opponent at the edge of its reach: that costs the charge and no more.
					if (w.airWhiffBeyond <= 0f || f.reachAtAction > w.airWhiffBeyond) {
						r -= w.airWhiff;
					}
					tally.airWhiffs++;
				}
''')
save(p, s, nl)

# ---- BotProfile: the slip
p = root + 'core/src/main/java/dev/swordai/core/curriculum/BotProfile.java'
s, nl = load(p)
s = rep(s, '''	public float followUpWhiff = 0f;
''', '''	public float followUpWhiff = 0f;
	/**
	 * The chance that a swing of its own comes late: it has the agent in reach and under its crosshair,
	 * its weapon is back, and it takes one to {@link #swingSlipTicks} ticks more to swing. A person does
	 * that every few swings, also in the middle of a combo, and for those ticks stands within the
	 * agent's reach with nothing on the way. A swing back made then lands first and ends the combo.
	 * Without it the scripted opponent's follow-up came on the first tick it could, every time, and a
	 * swing back was never worth making. Not for a swing made on the way down from a jump: that one has
	 * five ticks to be made in.
	 */
	public float swingSlip = 0f;
	public int swingSlipTicks = 3;
''')
s = rep(s, '''		b.followUpWhiff = followUpWhiff;
''', '''		b.followUpWhiff = followUpWhiff;
		b.swingSlip = swingSlip;
		b.swingSlipTicks = swingSlipTicks;
''')
save(p, s, nl)

# ---- BotBrain
p = root + 'src/main/java/dev/swordai/server/BotBrain.java'
s, nl = load(p)
s = rep(s, '''	/** Ticks left in which its next swing follows up the hit it has just landed, with no reaction time. */
	private int followUpTicks;
''', '''	/** Ticks left in which its next swing follows up the hit it has just landed, with no reaction time. */
	private int followUpTicks;
	/** How many ticks late its next swing comes; see {@link BotProfile#swingSlip}. */
	private int slipTicks;
''')
s = rep(s, '''		critPlan = followUpTicks <= 0 && profile.critChance > 0f && rng.nextFloat() < profile.critChance;
		critPlanTicks = 0;
''', '''		critPlan = followUpTicks <= 0 && profile.critChance > 0f && rng.nextFloat() < profile.critChance;
		critPlanTicks = 0;
		slipTicks = profile.swingSlip > 0f && rng.nextFloat() < profile.swingSlip ? 1 + rng.nextInt(Math.max(1, profile.swingSlipTicks)) : 0;
''')
s = rep(s, '''		boolean planned = followUpTicks > 0 || (critPlan && critJump >= 0);
		if (!canHit || (!planned && inReachTicks < profile.reactionTicks)) {
			return;
		}
''', '''		boolean jumped = critPlan && critJump >= 0;
		boolean planned = followUpTicks > 0 || jumped;
		// And now and then it is late with it, as a person is; see BotProfile.swingSlip.
		int needed = (planned ? 0 : profile.reactionTicks) + (jumped ? 0 : slipTicks);
		if (!canHit || inReachTicks < needed) {
			return;
		}
''')
save(p, s, nl)

# ---- Stages
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Stages.java'
s, nl = load(p)
# combo escape (inherited by crits)
s = rep(s, '''		st.pathLagTicks = PATH_LAG;
		st.slowShare = SLOW_SHARE;
''', '''		st.pathLagTicks = PATH_LAG;
		st.slowShare = SLOW_SHARE;
		// One swing in three of its own comes a tick to three late: a person's rhythm is not a
		// machine's, and those are the ticks in which swinging back at an opponent that is comboing the
		// fighter lands. An answer is paid for from a little beyond the sword's reach, since that is
		// where such an opponent stands until it slips. See BotProfile.swingSlip, RewardWeights.answer.
		st.bot.swingSlip = SWING_SLIP;
		st.reward.answer = 0.3f;
		st.reward.answerWithin = ATTEMPT_WITHIN;
''', 2)
s = rep(s, '''	private static final float SLOW_SHARE = 0.5f;
''', '''	private static final float SLOW_SHARE = 0.5f;
	/** How often a scripted opponent is late with a swing, from the stage after combos are learned; see {@link BotProfile#swingSlip}. */
	private static final float SWING_SLIP = 0.35f;
	/** A swing at an opponent no farther off than this is an attempt, whether or not it lands: a sword's reach and a good half block. */
	private static final float ATTEMPT_WITHIN = 3.6f;
''')
s = rep(s, '''		st.reward.airWhiff = 1.0f;
''', '''		st.reward.airWhiff = 1.0f;
		// But only at an opponent that is plainly out of reach. At one at the edge of it the swing is an
		// attempt and costs the sword's charge, no more: comboed by a person it is in the air from one
		// hit to the next, and with every miss there charged it did not swing back once.
		st.reward.airWhiffBeyond = ATTEMPT_WITHIN;
''')
s = rep(s, '''		st.reward.airWhiff = 0.15f;
''', '''		st.reward.airWhiff = 0.15f;
		st.reward.airWhiffBeyond = ATTEMPT_WITHIN;
''')
save(p, s, nl)
print('ok')
