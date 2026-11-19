package dev.swordai.core.config;

/**
 * Runtime settings saved in {@code swordai/config.json}.
 * Only real user-facing game/arena settings live here — no rule toggles or reward knobs.
 */
public final class Config {
	public static final int CURRENT_VERSION = 64;
	public static final int MIN_ARENA_RADIUS = 20;

	public int configVersion = CURRENT_VERSION;
	/** Vertical field of view the game is held at while the agent plays. */
	public int fov = 90;
	/** Field of view of the picture the network receives (<= fov). */
	public int visionFov = 90;
	/** Arena tick speed multiplier during training (1 = real time). */
	public float trainSpeed = 4f;
	/** Small-window height in rows when trainSpeed > 1 (0 = minimum 270). */
	public int speedWindowRows = 0;
	/** Arena floor half-size in blocks. */
	public int arenaRadius = MIN_ARENA_RADIUS;
	/** Fake player opponent name in the arena. */
	public String botName = "SwordBot";
	/** Show the opponent's real skin/armour over the colour-keyed vision buffer. */
	public boolean showOpponentSkin = true;
	/** Camera turn responsiveness per tick (see AimMotor). */
	public float aimResponse = 0.5f;
	/** Sampling temperature at play time (1.0 = exact trained policy). */
	public float playTemperature = 1f;
	/** Arena sword item id. */
	public String kitSword = "minecraft:diamond_sword";
	/** Arena armour tier: none, iron, diamond, netherite. */
	public String kitArmor = "iron";
}
