package dev.swordai.core.rl;

/** Shapes and indices of all actor (pixel + observable body + inventory slots) and training-only critic inputs. */
public final class ObsSpec {
	public static final int PERI_W = 48, PERI_H = 27, FOVEA = 32, CHANNELS = 2;
	public static final int CH_HEAD = 0, CH_BODY = 1;
	public static final int PERI_SIZE = PERI_W * PERI_H * CHANNELS;
	public static final int FOVEA_SIZE = FOVEA * FOVEA * CHANNELS;
	public static final float FOV_DEG = 90f, ASPECT = 16f / 9f, FOVEA_FRACTION = 0.30f;

	// AlphaStar-style permutation-equivariant inventory slot tensor (9 hotbar + 1 offhand + 27 backpack = 37 slots)
	public static final int HOTBAR_SLOTS = 9, OFFHAND_SLOT = 9, BACKPACK_SLOTS = 27;
	public static final int TOTAL_SLOTS = HOTBAR_SLOTS + 1 + BACKPACK_SLOTS;
	public static final int SF_PRESENT = 0;
	public static final int SF_SWORD = 1, SF_AXE = 2, SF_SHIELD = 3, SF_GAP = 4, SF_POTION = 5;
	public static final int SF_PEARL = 6, SF_WEB = 7, SF_TOTEM = 8, SF_ARMOR_XP = 9, SF_OTHER = 10;
	public static final int SF_TIER = 11, SF_COUNT = 12, SF_DURABILITY = 13, SF_COOLDOWN = 14, SF_SELECTED = 15;
	public static final int SLOT_FEATS = 16;
	public static final int SLOTS_SIZE = TOTAL_SLOTS * SLOT_FEATS;

	// Egocentric yaw-aligned 5x5 local terrain heightmap + hazard scan (Rudin et al. CoRL 2022 / ANYmal height-scan):
	// Channel 0 (25): relative step height clamp((groundY - myY)/2, -1..+1): +0.5 = 1-block step up, +1 = wall, -0.5 = 1-block drop.
	// Channel 1 (25): foot/body block hazard: 0 clear, +1 cobweb/slowdown, +0.5 water, -1 lava/fire.
	public static final int TERRAIN_GRID = 5;
	public static final int TERRAIN_CELLS = TERRAIN_GRID * TERRAIN_GRID;
	public static final int TERRAIN_CHANNELS = 2;
	public static final int TERRAIN_SIZE = TERRAIN_CELLS * TERRAIN_CHANNELS;

	// Mask statistics (per frame)
	public static final int ST_HEAD_VISIBLE = 0, ST_HEAD_X = 1, ST_HEAD_Y = 2;
	public static final int ST_HEAD_X_MID = 3, ST_HEAD_Y_MID = 4, ST_HEAD_X_FINE = 5, ST_HEAD_Y_FINE = 6;
	public static final int ST_HEAD_AREA = 7, ST_ANY_VISIBLE = 8, ST_ANY_X = 9, ST_ANY_Y = 10;
	public static final int ST_LEFT = 11, ST_RIGHT = 12, ST_TOP = 13, ST_BOTTOM = 14, ST_ANY_AREA = 15;
	public static final int STATS = 16, STAT_FRAMES = 2;
	public static final float MID_GAIN = 6f, FINE_GAIN = 36f;

	// Observable body + combat scalars (0..37 = legacy Sword scalars; 38..50 = SMP observable item/shield/cursor scalars)
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
	public static final int SC_OWN_FALLING = 30, SC_OPP_ON_GROUND = 31, SC_OPP_VEL_UP = 32, SC_OPP_FALLING = 33;
	public static final int SC_HITS_DEALT_ROW = 34, SC_EXCHANGE_OWN_HITS = 35, SC_EXCHANGE_OPP_HITS = 36, SC_CRIT_CHAIN = 37;
	// SMP Phase 1-3 human-observable item, shield, and GUI cursor scalars
	public static final int SC_OWN_USING = 38, SC_OWN_USE_TICKS = 39, SC_OWN_SHIELD_CD = 40;
	public static final int SC_OWN_HELD_SWORD = 41, SC_OWN_HELD_AXE = 42;
	public static final int SC_OPP_HELD_SWORD = 43, SC_OPP_HELD_AXE = 44, SC_OPP_USING = 45, SC_OPP_USE_TICKS = 46, SC_OPP_SHIELD_DISABLED = 47;
	public static final int SC_INV_OPEN = 48, SC_CURSOR_X = 49, SC_CURSOR_Y = 50;
	public static final int LEGACY_SCALARS = 38;
	public static final int SCALARS = 51;
	public static final int[] SCALARS_BEFORE = {16, 21, 23, 25, 26, 27, 30, 38};

	public static final int VEC_STATS = 0;
	public static final int VEC_SCALARS = VEC_STATS + STATS * STAT_FRAMES;
	public static final int VEC_LAST_ACTION = VEC_SCALARS + SCALARS;
	public static final int VEC_HEAD_MASK = VEC_LAST_ACTION + ActionSpace.TOTAL_LOGITS;
	public static final int VEC = VEC_HEAD_MASK + ActionSpace.HEADS;

	public static final int LEGACY_VEC_LAST_ACTION = VEC_SCALARS + LEGACY_SCALARS;
	public static final int LEGACY_VEC_HEAD_MASK = LEGACY_VEC_LAST_ACTION + ActionSpace.LEGACY_TOTAL_LOGITS;
	public static final int LEGACY_VEC = LEGACY_VEC_HEAD_MASK + ActionSpace.LEGACY_HEADS;

	// Privileged critic inputs (training only)
	public static final int PV_YAW_ERR = 0, PV_PITCH_ERR = 1, PV_REACH_DIST = 2;
	public static final int PV_REL_RIGHT = 3, PV_REL_UP = 4, PV_REL_FORWARD = 5;
	public static final int PV_BOT_VEL_RIGHT = 6, PV_BOT_VEL_UP = 7, PV_BOT_VEL_FORWARD = 8;
	public static final int PV_BOT_HEALTH = 9, PV_BOT_CHARGE = 10, PV_BOT_SPRINTING = 11, PV_BOT_ON_GROUND = 12;
	public static final int PV_ON_HEAD = 13, PV_ON_BODY = 14, PV_IN_REACH = 15;
	public static final int PV_EPISODE_TIME = 16, PV_BOT_SKILL = 17, PV_COMBO = 18, PV_BOT_ATTACKS = 19;
	public static final int PV_STAGE = 20, PV_STAGE_SLOTS = 8;
	public static final int LEGACY_PRIV = PV_STAGE + PV_STAGE_SLOTS;
	public static final int PV_BOT_SHIELD_UP = 28, PV_BOT_SHIELD_CD = 29;
	public static final int PV_OWN_SHIELD_UP = 30, PV_OWN_SHIELD_CD = 31;
	public static final int PV_KIT_HAS_SHIELD = 32, PV_KIT_HAS_AXE = 33;
	public static final int PRIV = 34;
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
