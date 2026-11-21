package dev.swordai.core.rl;

/** Shapes and indices of all actor (pixel + observable body) and training-only critic inputs. */
public final class ObsSpec {
	public static final int PERI_W = 48, PERI_H = 27, FOVEA = 32, CHANNELS = 2;
	public static final int CH_HEAD = 0, CH_BODY = 1;
	public static final int PERI_SIZE = PERI_W * PERI_H * CHANNELS;
	public static final int FOVEA_SIZE = FOVEA * FOVEA * CHANNELS;
	public static final float FOV_DEG = 90f, ASPECT = 16f / 9f, FOVEA_FRACTION = 0.30f;

	// Mask statistics (per frame)
	public static final int ST_HEAD_VISIBLE = 0, ST_HEAD_X = 1, ST_HEAD_Y = 2;
	public static final int ST_HEAD_X_MID = 3, ST_HEAD_Y_MID = 4, ST_HEAD_X_FINE = 5, ST_HEAD_Y_FINE = 6;
	public static final int ST_HEAD_AREA = 7, ST_ANY_VISIBLE = 8, ST_ANY_X = 9, ST_ANY_Y = 10;
	public static final int ST_LEFT = 11, ST_RIGHT = 12, ST_TOP = 13, ST_BOTTOM = 14, ST_ANY_AREA = 15;
	public static final int STATS = 16, STAT_FRAMES = 2;
	public static final float MID_GAIN = 6f, FINE_GAIN = 36f;

	// Observable body + combat scalars
	public static final int SC_HEIGHT = 0, SC_ON_GROUND = 1, SC_VEL_FORWARD = 2, SC_VEL_RIGHT = 3, SC_VEL_UP = 4;
	public static final int SC_PITCH = 5, SC_ATTACK_CHARGE = 6, SC_HEALTH = 7, SC_SPRINTING = 8;
	public static final int SC_SINCE_HIT_DEALT = 9, SC_SINCE_HIT_TAKEN = 10;
	public static final int SC_REL_VEL_FORWARD = 11, SC_REL_VEL_RIGHT = 12, SC_REL_VEL_UP = 13;
	public static final int SC_FRAME_STALE = 14, SC_OPP_HURT = 15;
	public static final int SC_TURNED_YAW = 16, SC_TURNED_PITCH = 17;
	public static final int SC_LAST_SEEN_SIDE = 18, SC_LAST_SEEN_UP = 19, SC_SINCE_SEEN = 20;
	public static final int SC_TURN_RATE_YAW = 21, SC_TURN_RATE_PITCH = 22;
	public static final int SC_ON_TARGET = 23, SC_IDEA_HOLD = 24;
	public static final int SC_HITS_TAKEN_ROW = 25, SC_OPP_CHARGE = 26;
	public static final int SC_STYLE_AWAY = 27, SC_STYLE_EDGE = 28, SC_STYLE_DIST = 29;
	// Step D additions: explicit falling, opp vertical motion/ground, combo/exchange/crit-chain
	public static final int SC_OWN_FALLING = 30, SC_OPP_ON_GROUND = 31, SC_OPP_VEL_UP = 32, SC_OPP_FALLING = 33;
	public static final int SC_HITS_DEALT_ROW = 34, SC_EXCHANGE_OWN_HITS = 35, SC_EXCHANGE_OPP_HITS = 36, SC_CRIT_CHAIN = 37;
	public static final int SCALARS = 38;
	public static final int[] SCALARS_BEFORE = {16, 21, 23, 25, 26, 27, 30};

	public static final int VEC_STATS = 0;
	public static final int VEC_SCALARS = VEC_STATS + STATS * STAT_FRAMES;
	public static final int VEC_LAST_ACTION = VEC_SCALARS + SCALARS;
	public static final int VEC_HEAD_MASK = VEC_LAST_ACTION + ActionSpace.TOTAL_LOGITS;
	public static final int VEC = VEC_HEAD_MASK + ActionSpace.HEADS;

	// Privileged critic inputs (training only)
	public static final int PV_YAW_ERR = 0, PV_PITCH_ERR = 1, PV_REACH_DIST = 2;
	public static final int PV_REL_RIGHT = 3, PV_REL_UP = 4, PV_REL_FORWARD = 5;
	public static final int PV_BOT_VEL_RIGHT = 6, PV_BOT_VEL_UP = 7, PV_BOT_VEL_FORWARD = 8;
	public static final int PV_BOT_HEALTH = 9, PV_BOT_CHARGE = 10, PV_BOT_SPRINTING = 11, PV_BOT_ON_GROUND = 12;
	public static final int PV_ON_HEAD = 13, PV_ON_BODY = 14, PV_IN_REACH = 15;
	public static final int PV_EPISODE_TIME = 16, PV_BOT_SKILL = 17, PV_COMBO = 18, PV_BOT_ATTACKS = 19;
	public static final int PV_STAGE = 20, PV_STAGE_SLOTS = 8, PRIV = PV_STAGE + PV_STAGE_SLOTS;
	public static final int CRITIC_IN = VEC + PRIV;

	private ObsSpec() {
	}

	public static void deriveStats(float[] stats) {
		float x = stats[ST_HEAD_X], y = stats[ST_HEAD_Y];
		stats[ST_HEAD_X_MID] = (float) Math.tanh(MID_GAIN * x);
		stats[ST_HEAD_Y_MID] = (float) Math.tanh(MID_GAIN * y);
		stats[ST_HEAD_X_FINE] = (float) Math.tanh(FINE_GAIN * x);
		stats[ST_HEAD_Y_FINE] = (float) Math.tanh(FINE_GAIN * y);
	}
}
