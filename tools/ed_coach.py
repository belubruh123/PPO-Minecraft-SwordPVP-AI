import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'

e = Ed(R + 'config/Config.java')
e.rep("""	public boolean dodgeJumpRule = false;
""", """	public boolean dodgeJumpRule = false;
	/**
	 * The swing owed for a hit taken is kept for the fighter's way down (the punish crit, {@code AnswerRule.inTheAir})
	 * only with the opponent nearer than this, as {@code JumpIn.distance} reads it; farther off it is made at once,
	 * while the opponent is still under the crosshair. 0: always kept, as before 22 October. From the fight traces
	 * of 22 October: thrown up by a hit at 2.6 blocks or more, the crit came on 28% of those swings against the
	 * spacer and 10% against the one that stands and hits, and against those two the swing was never made at all
	 * on 42% and 30% of the openings where its sword was spent and the fighter's back (the knockback carried the
	 * fighter out of reach while it waited). Nearer than 2.2 the crit came on 68 to 97% of them, against every kind.
	 */
	public float keepWithin = 0f;
	/**
	 * The chase and the hop ({@code JumpIn.CHASE}, {@code JumpIn.HOP}) are only made at an opponent whose sword is
	 * not ready, or that is running away; never at one standing its ground with its sword ready. From the fight
	 * traces of 22 October: a running jump at a ready opponent that holds its ground is hit out of the air before it
	 * comes down (in the air the fighter's eye is higher and its reach to a grounded opponent shorter, and such an
	 * opponent hits whatever comes into its reach): the one that stands and hits did so on 98% of the chases and
	 * all the hops, the spacer on 74% of the chases, the timer on all of them.
	 */
	public boolean jumpAtReadyOnlyFleeing = false;
""")
e.save()

e = Ed(R + 'rl/AnswerRule.java')
e.rep("""	public static int when(float[] vec, boolean punishable, boolean proven, boolean wait) {
		if (BackNudge.inCombo(vec) && !proven) {
			return NOW;
		}
		return JumpNudge.inAir(vec) ? inTheAir(vec) : onTheGround(vec, punishable, wait);
	}
""", """	public static int when(float[] vec, boolean punishable, boolean proven, boolean wait) {
		return when(vec, punishable, proven, wait, 0f);
	}

	/**
	 * @param keepWithin the swing is kept for the way down only with the opponent nearer than this ({@code Config.keepWithin});
	 *                   farther off it is made now, while there is a hit there to be made. 0: always kept.
	 */
	public static int when(float[] vec, boolean punishable, boolean proven, boolean wait, float keepWithin) {
		if (BackNudge.inCombo(vec) && !proven) {
			return NOW;
		}
		if (!JumpNudge.inAir(vec)) {
			return onTheGround(vec, punishable, wait);
		}
		int air = inTheAir(vec);
		return air == KEEP && keepWithin > 0f && JumpIn.distance(vec) >= keepWithin ? NOW : air;
	}
""")
e.save()

e = Ed(R + 'rl/JumpIn.java')
e.rep("""	static final float HOP_HEADING_SPEED = 0.08f;
""", """	static final float HOP_HEADING_SPEED = 0.08f;
	/** The opponent's sword counts as ready from here on, for {@link #onlyAtSpentOrFleeing}. */
	static final float OPP_READY = 0.9f;
	/** And the opponent counts as running away going off at this much a tick or more, blocks. */
	static final float FLEEING = 0.2f;
""")
e.rep("""	/** See {@link #counterCrit}. */
	private boolean counter;
""", """	/** See {@link #counterCrit}. */
	private boolean counter;
	/** See {@link #onlyAtSpentOrFleeing}. */
	private boolean spentOrFleeing;
""")
e.rep("""	public void counterCrit(boolean on) {
		counter = on;
	}
""", """	public void counterCrit(boolean on) {
		counter = on;
	}

	/**
	 * Whether the chase and the hop are made only at an opponent whose sword is not ready ({@link #OPP_READY}) or
	 * that is running away ({@link #FLEEING}); see {@code Config.jumpAtReadyOnlyFleeing}. An opponent standing its
	 * ground with its sword ready hits a running jump out of the air before it comes down.
	 */
	public void onlyAtSpentOrFleeing(boolean on) {
		spentOrFleeing = on;
	}
""")
e.rep("""		boolean longAfterHit = sinceDealt <= fade(CHASE_AFTER_HIT_TICKS);
""", """		boolean longAfterHit = sinceDealt <= fade(CHASE_AFTER_HIT_TICKS);
		// Not at an opponent that stands its ground with its sword ready, if so set.
		boolean jumpable = !spentOrFleeing || scalar(vec, ObsSpec.SC_OPP_CHARGE) < OPP_READY || away >= FLEEING;
""")
e.rep("""		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit)) {""", """		if (chase && jumpable && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit)) {""")
e.rep("""		if (chase && hops && notHitLately && (ranFor >= RUN_TICKS || headedFor >= HOP_HEADING_TICKS) && side <= SIDE_MOST""",
      """		if (chase && hops && jumpable && notHitLately && (ranFor >= RUN_TICKS || headedFor >= HOP_HEADING_TICKS) && side <= SIDE_MOST""")
e.save()

e = Ed(R + 'rl/Fighter.java')
e.rep("""	private final boolean punishWait;
""", """	private final boolean punishWait;
	private final float keepWithin;
""")
e.rep("""			jumpIn.counterCrit(cfg.counterCritRule);
		}
""", """			jumpIn.counterCrit(cfg.counterCritRule);
			jumpIn.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
		}
""")
e.rep("""		this.punishWait = cfg.punishWaitOnGround;
""", """		this.punishWait = cfg.punishWaitOnGround;
		this.keepWithin = cfg.keepWithin;
""")
e.rep("""AnswerRule.when(obs.vec, fallWatch.punishable(), punishInCombo && fallWatch.proven(), punishWait) : AnswerRule.NOW;""",
      """AnswerRule.when(obs.vec, fallWatch.punishable(), punishInCombo && fallWatch.proven(), punishWait, keepWithin) : AnswerRule.NOW;""")
e.save()

e = Ed('D:/swordai/src/client/java/dev/swordai/client/Trainer.java')
e.rep("""		made.counterCrit(cfg.counterCritRule);
		return made;""", """		made.counterCrit(cfg.counterCritRule);
		made.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
		return made;""")
e.rep("""cfg.punishInCombo && punishMemory.proven(), cfg.punishWaitOnGround) : AnswerRule.NOW;""",
      """cfg.punishInCombo && punishMemory.proven(), cfg.punishWaitOnGround, cfg.keepWithin) : AnswerRule.NOW;""")
e.save()
