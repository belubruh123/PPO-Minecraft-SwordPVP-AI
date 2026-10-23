import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/JumpIn.java')
e.rep("""	/** The crit in ({@link #EDGE}): the reach, to the opponent's front as {@link #distance} reads it. */
	static final float EDGE_REACH = 3.0f;""", """	/**
	 * The crit in ({@link #EDGE}): the reach, to the opponent's front as {@link #distance} reads it. Measured in the traces
	 * of 22 October: of 3,917 hits landed, none farther than 3.0 and all but 54 nearer than 2.95; misses begin at 2.9.
	 */
	static final float EDGE_REACH = 2.95f;""")
e.rep("""	static final float EDGE_STAND_FROM = 3.05f;
	static final float EDGE_STAND_TO = 3.4f;
	static final float EDGE_STAND_SPEED = 0.06f;""", """	static final float EDGE_STAND_FROM = 2.98f;
	static final float EDGE_STAND_TO = 3.3f;
	static final float EDGE_STAND_SPEED = 0.08f;""")
e.rep("""	static final float EDGE_STOP_AT = 3.25f;""", """	static final float EDGE_STOP_AT = 3.12f;""")
e.rep("""			if (d >= EDGE_STAND_FROM && d <= EDGE_STAND_TO && Math.abs(closing) <= EDGE_STAND_SPEED) {
				start(EDGE);
				edgeStand = true;
				return JUMP | LET_GO | NO_SWING;
			}""", """			if (d >= EDGE_STAND_FROM && d <= EDGE_STAND_TO) {
				if (Math.abs(closing) <= EDGE_STAND_SPEED) {
					start(EDGE);
					edgeStand = true;
					return JUMP | LET_GO | NO_SWING;
				}
				// Still coasting in: let it come to a stop (22 October, first trial: W was handed back while it coasted,
				// and it walked on into the opponent's reach); going back: in again at a walk.
				return closing > 0f ? LET_GO : WALK;
			}""")
e.save()
