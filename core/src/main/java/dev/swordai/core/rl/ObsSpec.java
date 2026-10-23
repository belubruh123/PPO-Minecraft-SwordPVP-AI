package dev.swordai.core.rl;

/**
 * Shapes of everything the networks consume.
 *
 * <p>The actor sees only pixel-derived data (two masks plus statistics of those masks) and a short
 * vector of things a player knows about their own body. It is never given distance, reach, an
 * in-reach flag or the opponent's position. The critic additionally gets ground truth, but it only
 * exists during training.
 */
public final class ObsSpec {
	/** Whole view, head + body channels, channel-last. */
	public static final int PERI_W = 48;
	public static final int PERI_H = 27;
	/** Centre square, head + body channels, channel-last. */
	public static final int FOVEA = 32;
	public static final int CHANNELS = 2;
	public static final int CH_HEAD = 0;
	public static final int CH_BODY = 1;

	public static final int PERI_SIZE = PERI_W * PERI_H * CHANNELS;
	public static final int FOVEA_SIZE = FOVEA * FOVEA * CHANNELS;

	/**
	 * Vertical field of view of the picture the network gets, unless the config says otherwise; see
	 * {@code ViewGeometry.setFov}. How large the opponent looks at a given range depends on it, so a
	 * network only plays right on the field of view it learned with.
	 */
	public static final float FOV_DEG = 90f;
	/** The periphery mask always covers a 16:9 region centred on the crosshair. */
	public static final float ASPECT = 16f / 9f;
	/** Side of the fovea square as a fraction of frame height. */
	public static final float FOVEA_FRACTION = 0.30f;

	// ---- mask statistics, per frame -------------------------------------------------------------
	// Positions are in units of half the frame height, measured from the crosshair: x right, y up.
	public static final int ST_HEAD_VISIBLE = 0;
	public static final int ST_HEAD_X = 1;
	public static final int ST_HEAD_Y = 2;
	/** The head offset again through tanh at two zoom levels, so sub-degree errors are not tiny numbers. */
	public static final int ST_HEAD_X_MID = 3;
	public static final int ST_HEAD_Y_MID = 4;
	public static final int ST_HEAD_X_FINE = 5;
	public static final int ST_HEAD_Y_FINE = 6;
	public static final int ST_HEAD_AREA = 7;
	public static final int ST_ANY_VISIBLE = 8;
	public static final int ST_ANY_X = 9;
	public static final int ST_ANY_Y = 10;
	public static final int ST_LEFT = 11;
	public static final int ST_RIGHT = 12;
	public static final int ST_TOP = 13;
	public static final int ST_BOTTOM = 14;
	public static final int ST_ANY_AREA = 15;
	public static final int STATS = 16;
	public static final float MID_GAIN = 6f;
	public static final float FINE_GAIN = 36f;
	/** Current frame and the one before it, so apparent motion is observable. */
	public static final int STAT_FRAMES = 2;

	// ---- self scalars ---------------------------------------------------------------------------
	public static final int SC_HEIGHT = 0;
	public static final int SC_ON_GROUND = 1;
	public static final int SC_VEL_FORWARD = 2;
	public static final int SC_VEL_RIGHT = 3;
	public static final int SC_VEL_UP = 4;
	public static final int SC_PITCH = 5;
	public static final int SC_ATTACK_CHARGE = 6;
	public static final int SC_HEALTH = 7;
	public static final int SC_SPRINTING = 8;
	public static final int SC_SINCE_HIT_DEALT = 9;
	public static final int SC_SINCE_HIT_TAKEN = 10;
	/** Opponent velocity relative to the agent, in the agent's view frame; zero when unseen. */
	public static final int SC_REL_VEL_FORWARD = 11;
	public static final int SC_REL_VEL_RIGHT = 12;
	public static final int SC_REL_VEL_UP = 13;
	public static final int SC_FRAME_STALE = 14;
	/** No longer written (the agent's swing is never locked out); the slot stays so the layout does not shift. */
	public static final int SC_UNUSED_15 = 15;
	/** How far the camera has turned since the image being shown was taken (the agent's own motion). */
	public static final int SC_TURNED_YAW = 16;
	public static final int SC_TURNED_PITCH = 17;
	/** Memory of the last sighting: which side of the crosshair the opponent was on, and how long ago. */
	public static final int SC_LAST_SEEN_SIDE = 18;
	public static final int SC_LAST_SEEN_UP = 19;
	public static final int SC_SINCE_SEEN = 20;
	/** The speed the camera is carrying from the previous tick; see {@link AimMotor}. */
	public static final int SC_TURN_RATE_YAW = 21;
	public static final int SC_TURN_RATE_PITCH = 22;
	/**
	 * A plan the agent is handed, not something it senses: which way to strafe right now (out to a
	 * random side after a swing, back again before the next) and how many more ticks to hold the next
	 * swing. Neither says anything about the opponent; zero while there is no plan. See {@link MixIdea}.
	 */
	public static final int SC_IDEA_STRAFE = 23;
	public static final int SC_IDEA_HOLD = 24;
	/**
	 * How many hits it has taken in a row without landing one: the opponent's combo, counted from what
	 * happened to its own body. See {@code TakenStreak}.
	 */
	public static final int SC_HITS_TAKEN_ROW = 25;
	/**
	 * How far back the opponent's sword is, as far as can be told from the last time its arm was seen
	 * to swing: 0 just after a swing, 1 once a sword would be back (twelve and a half ticks), and 1 if
	 * it has not been seen to swing. A player in a real fight sees every swing of the other's arm,
	 * misses too, and judges the distance by it: out of reach while that sword is back, in again while
	 * it is not. Since 18 October; see {@code BodyState.noteOpponentSwing}.
	 */
	public static final int SC_OPP_CHARGE = 26;
	/**
	 * What this opponent has been doing over the last three seconds or so, as running averages kept by
	 * {@code BodyState} and started afresh with every fight: how fast it has been moving away from the
	 * fighter, how much of the time it has stood still at the edge of the reach, and how far off it has
	 * been (an eighth of the blocks). The network sees one moment; these tell an opponent that waits at
	 * the edge and jumps in on its own clock from one that walks in. Since 20 October.
	 */
	public static final int SC_STYLE_AWAY = 27;
	public static final int SC_STYLE_EDGE = 28;
	public static final int SC_STYLE_DIST = 29;
	public static final int SCALARS = 30;
	/**
	 * Scalar counts of earlier versions (0.1.0, 0.1.1 to 0.1.2, 0.1.3 to 0.1.4, 0.2.0 to 0.6.1, up to
	 * 18 October 2026 in 0.14.0, and up to 0.15.3). New
	 * scalars are only ever added after the existing ones, which is what lets an older checkpoint be
	 * upgraded on load.
	 */
	public static final int[] SCALARS_BEFORE = {16, 21, 23, 25, 26, 27};

	// ---- actor vector layout --------------------------------------------------------------------
	public static final int VEC_STATS = 0;
	public static final int VEC_SCALARS = VEC_STATS + STATS * STAT_FRAMES;
	public static final int VEC_LAST_ACTION = VEC_SCALARS + SCALARS;
	public static final int VEC_HEAD_MASK = VEC_LAST_ACTION + ActionSpace.TOTAL_LOGITS;
	public static final int VEC = VEC_HEAD_MASK + ActionSpace.HEADS;

	// ---- privileged critic inputs (training only) -----------------------------------------------
	public static final int PV_YAW_ERR = 0;
	public static final int PV_PITCH_ERR = 1;
	public static final int PV_REACH_DIST = 2;
	public static final int PV_REL_RIGHT = 3;
	public static final int PV_REL_UP = 4;
	public static final int PV_REL_FORWARD = 5;
	public static final int PV_BOT_VEL_RIGHT = 6;
	public static final int PV_BOT_VEL_UP = 7;
	public static final int PV_BOT_VEL_FORWARD = 8;
	public static final int PV_BOT_HEALTH = 9;
	public static final int PV_BOT_CHARGE = 10;
	public static final int PV_BOT_SPRINTING = 11;
	public static final int PV_BOT_ON_GROUND = 12;
	public static final int PV_ON_HEAD = 13;
	public static final int PV_ON_BODY = 14;
	public static final int PV_IN_REACH = 15;
	public static final int PV_EPISODE_TIME = 16;
	public static final int PV_BOT_SKILL = 17;
	public static final int PV_COMBO = 18;
	public static final int PV_BOT_ATTACKS = 19;
	/**
	 * One-hot of the task being played. Eight slots tell ten stages apart: the ninth is the code with
	 * no slot set and the tenth the one with all of them set; see {@code ObsBuilder.writeStage}. (More
	 * slots would change the critic's input size and with it every saved checkpoint.)
	 */
	public static final int PV_STAGE = 20;
	public static final int PV_STAGE_SLOTS = 8;
	public static final int PRIV = PV_STAGE + PV_STAGE_SLOTS;

	public static final int CRITIC_IN = VEC + PRIV;

	private ObsSpec() {
	}

	/** Fills the zoomed head-offset entries from {@link #ST_HEAD_X} / {@link #ST_HEAD_Y}. */
	public static void deriveStats(float[] stats) {
		float x = stats[ST_HEAD_X];
		float y = stats[ST_HEAD_Y];
		stats[ST_HEAD_X_MID] = (float) Math.tanh(MID_GAIN * x);
		stats[ST_HEAD_Y_MID] = (float) Math.tanh(MID_GAIN * y);
		stats[ST_HEAD_X_FINE] = (float) Math.tanh(FINE_GAIN * x);
		stats[ST_HEAD_Y_FINE] = (float) Math.tanh(FINE_GAIN * y);
	}
}
