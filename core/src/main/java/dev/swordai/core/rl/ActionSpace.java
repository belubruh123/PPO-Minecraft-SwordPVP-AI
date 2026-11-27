package dev.swordai.core.rl;

/**
 * Eleven factorised categorical heads, one decision per game tick (wired once for Sword -> SMP Phases 1-3):
 * - YAW/PITCH: camera velocity bins when playing; GUI cursor velocity bins when inventory is open (OpenAI VPT).
 * - FORWARD/STRAFE/SPRINT/JUMP/ATTACK: core melee movement and left-click.
 * - USE_ITEM: right-click (shield block, eat gap, throw pearl, place web, GUI right-click).
 * - HOTBAR_SLOT: 0 = keep current, 1..9 = press hotbar key 1..9 (scored by AlphaStar Pointer Network over slot embeddings).
 * - OFFHAND_SWAP: press F (swap mainhand/offhand, or hover-offhand swap in inventory).
 * - INVENTORY: press E (open/close inventory; masked off in Phase 1, unlocked in Phase 2 without changing checkpoint shape).
 */
public final class ActionSpace {
	public static final int YAW = 0;
	public static final int PITCH = 1;
	public static final int FORWARD = 2;
	public static final int STRAFE = 3;
	public static final int SPRINT = 4;
	public static final int JUMP = 5;
	public static final int ATTACK = 6;
	public static final int USE_ITEM = 7;
	public static final int HOTBAR_SLOT = 8;
	public static final int OFFHAND_SWAP = 9;
	public static final int INVENTORY = 10;
	public static final int HEADS = 11;

	public static final int LEGACY_HEADS = 7;
	public static final int LEGACY_TOTAL_LOGITS = 38;

	public static final String[] NAMES = {
			"yaw", "pitch", "forward", "strafe", "sprint", "jump", "attack",
			"use_item", "hotbar_slot", "offhand_swap", "inventory"
	};

	/** Degrees per tick; positive yaw turns right, positive pitch looks down (Minecraft convention). */
	public static final float[] YAW_BINS = {-40f, -18f, -8f, -3.5f, -1.5f, -0.6f, -0.2f, 0f, 0.2f, 0.6f, 1.5f, 3.5f, 8f, 18f, 40f};
	public static final float[] PITCH_BINS = {-18f, -8f, -3.5f, -1.5f, -0.5f, 0f, 0.5f, 1.5f, 3.5f, 8f, 18f};

	/**
	 * FORWARD: 0 none, 1 W, 2 S. STRAFE: 0 none, 1 A (left), 2 D (right).
	 * HOTBAR_SLOT: 0 keep, 1..9 select hotbar slot 0..8. Others: 0 off, 1 on.
	 */
	public static final int[] SIZES = {YAW_BINS.length, PITCH_BINS.length, 3, 3, 2, 2, 2, 2, 10, 2, 2};
	public static final int[] OFFSETS = new int[HEADS];
	public static final int TOTAL_LOGITS;

	/** What a locked head does: no turn, no keys, keep hotbar slot. */
	public static final int[] DEFAULTS = {7, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0};

	public static final int ALL_HEADS = (1 << HEADS) - 1;
	/** Legacy 7 Sword-only heads (no shield/hotbar/offhand/inventory). */
	public static final int SWORD_HEADS = (1 << LEGACY_HEADS) - 1;
	/** SMP Phase 1 active heads: all combat + shield + pointer hotbar + offhand; INVENTORY unlocked in Phase 2. */
	public static final int PHASE1_HEADS = ALL_HEADS & ~(1 << INVENTORY);

	static {
		int off = 0;
		for (int h = 0; h < HEADS; h++) {
			OFFSETS[h] = off;
			off += SIZES[h];
		}
		TOTAL_LOGITS = off;
	}

	private ActionSpace() {
	}

	public static int mask(int... heads) {
		int m = 0;
		for (int h : heads) {
			m |= 1 << h;
		}
		return m;
	}

	public static boolean unlocked(int headMask, int head) {
		return (headMask & (1 << head)) != 0;
	}

	public static float yawDegrees(int[] action) {
		return YAW_BINS[action[YAW]];
	}

	public static float pitchDegrees(int[] action) {
		return PITCH_BINS[action[PITCH]];
	}

	/** +1 forward (W), -1 back (S), 0 neither. */
	public static int forwardAxis(int[] action) {
		return action[FORWARD] == 1 ? 1 : action[FORWARD] == 2 ? -1 : 0;
	}

	/** -1 left (A), +1 right (D), 0 neither. */
	public static int strafeAxis(int[] action) {
		return action[STRAFE] == 1 ? -1 : action[STRAFE] == 2 ? 1 : 0;
	}
}
