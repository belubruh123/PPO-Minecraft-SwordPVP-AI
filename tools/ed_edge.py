import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

R = 'D:/swordai/core/src/main/java/dev/swordai/core/'
e = Ed(R + 'rl/JumpIn.java')
e.rep("""	public static final int COUNTER = 6;
""", """	public static final int COUNTER = 6;
	/**
	 * The crit in: a running jump at an opponent standing still on the ground, taken from where its way down brings the
	 * fighter into reach in the last ticks of the fall, the sprint let go of just before, and the critical hit made as it
	 * comes into reach; see {@link #edgeCrit}.
	 */
	public static final int EDGE = 7;
""")
e.rep("""	static final int FLEE_GROUND_TICKS = 3;""", """	static final int FLEE_GROUND_TICKS = 3;
	/** The crit in ({@link #EDGE}): the reach, to the opponent's front as {@link #distance} reads it. */
	static final float EDGE_REACH = 3.0f;
	/** It comes into reach this many ticks after the take-off at the soonest and the latest: low in the fall. */
	static final float EDGE_K_MIN = 8.5f;
	static final float EDGE_K_MAX = 11f;
	/**
	 * How fast a running jump carries it, blocks a tick: the speed it closes at plus this, between these two. (A running
	 * jump at a run covers 2.8 blocks in nine ticks; one from a stand gets the jump's push alone.)
	 */
	static final float EDGE_PUSH = 0.08f;
	static final float EDGE_AIR_LEAST = 0.2f;
	static final float EDGE_AIR_MOST = 0.33f;
	/** The opponent moving towards or away from it at no more than this, blocks a tick: standing. */
	static final float EDGE_STILL = 0.06f;
	/** Not from farther than this. */
	static final float EDGE_TO = 7f;
	/** Its own sword this far back at least: all the way back by the swing, ten ticks on. */
	static final float EDGE_CHARGE = 0.3f;
	/** On the way down, W and the sprint key are let go of from this near: the sprint is off by the tick it comes into reach. */
	static final float EDGE_LET_GO = 3.4f;
	/** Hit in the air before its own swing: left alone for this long, and for good after {@link #EDGE_BURNS} of those. */
	static final int EDGE_OFF_TICKS = 100;
	static final int EDGE_BURNS = 4;""")
e.rep("""	/** Ticks the opponent has been on the ground, as its speed up or down shows it. */
	private int oppGroundTicks;
""", """	/** Ticks the opponent has been on the ground, as its speed up or down shows it. */
	private int oppGroundTicks;
	/** See {@link #edgeCrit}. */
	private boolean edge;
	private int edgeOff;
	private int edgeBurns;
""")
e.rep("""	public void onlyAtSpentOrFleeing(boolean on) {
		spentOrFleeing = on;
	}
""", """	public void onlyAtSpentOrFleeing(boolean on) {
		spentOrFleeing = on;
	}

	/**
	 * Whether it crits in ({@link #EDGE}): at an opponent standing still on the ground, a running jump taken from where the
	 * way down brings it into reach low in the fall, and the critical hit as it comes into reach; see {@code Config.edgeCritRule}.
	 * Against an opponent that stands and hits whatever comes into its reach the exchange is otherwise a sprint hit for a
	 * hit, 7 for 7 (fight traces, 22 October: 16.6 trades a fight, 0.3 crits); come down into its reach late in a jump
	 * and it is a critical hit for a hit, 10.5 for 7. Not near the top of the jump: there the fighter's eye is high above
	 * the opponent's head and its reach to it shorter than the opponent's to it, which is how every hop at that opponent
	 * was hit out of the air.
	 */
	public void edgeCrit(boolean on) {
		edge = on;
	}
""")
e.rep("""		counterOff = 0;
		counterBurns = 0;
		oppGroundTicks = 0;""", """		counterOff = 0;
		counterBurns = 0;
		edgeOff = 0;
		edgeBurns = 0;
		oppGroundTicks = 0;""")
e.rep("""		int counterBurnsWere = counterBurns;
		reset();
		counterOff = counterWas;
		counterBurns = counterBurnsWere;""", """		int counterBurnsWere = counterBurns;
		int edgeWas = edgeOff;
		int edgeBurnsWere = edgeBurns;
		reset();
		counterOff = counterWas;
		counterBurns = counterBurnsWere;
		edgeOff = edgeWas;
		edgeBurns = edgeBurnsWere;""")
e.rep("""		if (counterOff > 0 && --counterOff == 0 && counterBurns >= 2) {
			counterBurns = 0;
		}
		if (kind != NONE) {""", """		if (counterOff > 0 && --counterOff == 0 && counterBurns >= 2) {
			counterBurns = 0;
		}
		if (edgeOff > 0) {
			edgeOff--;
		}
		if (kind == EDGE && (busy || unhitTicks == 0)) {
			// Hit in the air of it: a trade if its own hit has landed in the last three ticks (the crit in is a crit for a
			// hit), and too late if not.
			if (unhitTicks == 0 && sinceDealt < fade(3)) {
				edgeOff = ++edgeBurns >= EDGE_BURNS ? OFF_FOR_GOOD_TICKS : EDGE_OFF_TICKS;
			}
			kind = NONE;
			return 0;
		}
		if (kind != NONE) {""")
e.rep("""				return inTheAir(keys, onOpponent, velUp, sprinting);""",
      """				return inTheAir(keys, onOpponent, velUp, sprinting, distance(vec));""")
e.rep("""		if (chase && jumpable && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE""",
      """		// The crit in: at an opponent standing still, from where the way down brings it into reach late in the fall.
		if (edge && edgeOff <= 0 && notHitLately && side <= SIDE_MOST && charge >= EDGE_CHARGE && oppGroundTicks >= FLEE_GROUND_TICKS
				&& Math.abs(away) <= EDGE_STILL && d <= EDGE_TO) {
			float carried = Math.max(EDGE_AIR_LEAST, Math.min(EDGE_AIR_MOST, Math.max(0f, closing) + EDGE_PUSH));
			float ticks = (d - EDGE_REACH) / carried;
			if (ticks >= EDGE_K_MIN && ticks <= EDGE_K_MAX) {
				start(EDGE);
				return JUMP | RUN | NO_SWING;
			}
		}
		if (chase && jumpable && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE""")
e.rep("""	private int inTheAir(int keys, boolean onOpponent, float velUp, boolean sprinting) {
		boolean canSwing = ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		boolean falling = velUp < 0f;""", """	private int inTheAir(int keys, boolean onOpponent, float velUp, boolean sprinting, float d) {
		boolean canSwing = ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		boolean falling = velUp < 0f;
		if (kind == EDGE) {
			if (!falling) {
				return NO_JUMP | RUN | NO_SWING;
			}
			if (letGone || onOpponent || d <= EDGE_LET_GO) {
				// Coming down near the reach: W and the sprint off, so that the swing, the moment it is in reach, is a crit.
				letGone = true;
				return NO_JUMP | LET_GO | (onOpponent && !sprinting && canSwing ? SWING : NO_SWING);
			}
			return NO_JUMP | RUN | NO_SWING;
		}""")
e.save()

e = Ed(R + 'config/Config.java')
e.rep("""	public boolean jumpAtReadyOnlyFleeing = false;
""", """	public boolean jumpAtReadyOnlyFleeing = false;
	/**
	 * The crit in ({@code JumpIn.EDGE}): at an opponent standing still on the ground, a running jump timed to come down into
	 * its reach late in the fall, and the critical hit as it does. Since 22 October, from the fight traces: against the
	 * one that stands and hits, every exchange was a hit for a hit (16.6 trades a fight, 0.3 crits of its own).
	 */
	public boolean edgeCritRule = false;
""")
e.save()

e = Ed(R + 'rl/Fighter.java')
e.rep("""			jumpIn.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
""", """			jumpIn.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
			jumpIn.edgeCrit(cfg.edgeCritRule);
""")
e.rep("""		this.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f || cfg.counterCritRule || tactics)""",
      """		this.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f || cfg.counterCritRule || cfg.edgeCritRule || tactics)""")
e.save()

e = Ed('D:/swordai/src/client/java/dev/swordai/client/Trainer.java')
e.rep("""		made.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
""", """		made.onlyAtSpentOrFleeing(cfg.jumpAtReadyOnlyFleeing);
		made.edgeCrit(cfg.edgeCritRule);
""")
e.save()
