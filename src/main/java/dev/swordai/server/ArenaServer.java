package dev.swordai.server;

import carpet.patches.EntityPlayerMPFake;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.BotProfile;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Everything that happens on the integrated server while the agent trains: the arena, the sparring
 * bot, episode resets and the bookkeeping of who hit whom.
 *
 * <p>The same arena also hosts sparring ({@link #startSpar}): there the player plays their own
 * character and the fake player is driven by the trained network instead of the scripted brain.
 *
 * <p>And it hosts training without the screen ({@link #startHeadless}): there the agent's body is a
 * second fake player, played from the server thread by an {@link ArenaDriver}, and the user's own
 * character is left alone.
 *
 * <p>Every method except {@link #truth()} must run on the server thread; the client reaches them
 * through {@code server.execute(...)}.
 */
public final class ArenaServer {
	/** The opponent's name unless the config gives another. */
	public static final String BOT_NAME = "SwordBot";
	/** Training without the screen: the name of the fake player that is the agent's body, unless the config gives another. */
	public static final String AGENT_NAME = "SwordAgent";
	private static final int BOT_RETRY_TICKS = 100;
	private static final int BOT_TIMEOUT_TICKS = 400;
	private static final int PLACEMENT_ATTEMPTS = 40;
	private static final float NORMAL_TICK_RATE = 20f;

	/** How far from the middle each fighter starts a sparring round. */
	private static final double SPAR_START_OFFSET = 4.0;

	private static volatile Truth truth = Truth.INACTIVE;
	private static volatile String sparStatus = "";
	private static volatile String sparDetail = "";
	private static ArenaServer active;
	private final SceneRecorder scenes = new SceneRecorder();
	/**
	 * The arena the last run left standing. The next run that starts near it uses it again, so that
	 * stopping and starting does not take the whole thing down and put it up each time.
	 */
	private static Arena standing;

	private final MinecraftServer server;
	private final UUID agentId;
	private final Config cfg;
	private final Arena arena;
	private final BotBrain brain = new BotBrain();
	/** The technique school's bot, in the fights whose plan has a style; see {@link EpisodePlan#style}. */
	private final TechniqueBot tech = new TechniqueBot();
	private boolean techPlays;
	/** The kind of the agent's and the bot's attack under way ({@code Events} kinds), for the hit that follows. */
	private int agentKind;
	private int botKind;
	private final Random rng = new Random();
	private final List<BlockPos> agentPillar = new ArrayList<>();
	private final List<BlockPos> botPillar = new ArrayList<>();
	/** Non-null while sparring: the trained network that plays the fake player. */
	private final SparPilot pilot;
	/** Non-null while training without the screen: what plays the agent's fake player. */
	private final ArenaDriver driver;
	private final String botName;
	private final String agentName;
	/** Training without the screen: the agent's body. */
	private ServerPlayer fakeAgent;
	private Vec3 agentLastPos;
	private int ticksWithoutAgent;
	/** The server's tick rate before it was changed, or negative while it has not been. */
	private float savedTickRate = -1f;
	/**
	 * Self-play: a saved copy of the agent's own network. It plays the bot in the episodes whose plan
	 * asks for it; in all others the scripted brain does.
	 */
	private BotPilot[] rivals = new BotPilot[0];
	/** The copy that plays the bot in the running episode. */
	private BotPilot rival;
	private boolean rivalPlays;
	/** The outside bot plays the bot in this episode; see {@link #setOutside}. */
	private boolean outsidePlays;
	/** The level it was last set to, or null. */
	private String outsideLevel;
	/** In the running fight who moves first is drawn on every tick; see {@link EpisodePlan#mixedTurns}. */
	private boolean mixedTurns;
	private boolean sparStarted;
	private boolean roundOver;
	private int countdown;
	private int playerRounds;
	private int aiRounds;
	/** Sparring: what each side's hits were. Index 0 is the network, 1 the player. */
	private final HitCount[] sparHits = {new HitCount(), new HitCount()};

	private ServerPlayer bot;
	/** Where the bot was a tick ago, or null right after it was put somewhere. */
	private Vec3 botLastPos;
	/** How far each fake player has fallen; the game does not count it for them. */
	private final FakeFall botFall = new FakeFall();
	private final FakeFall agentFall = new FakeFall();
	private int ticksWithoutBot;
	private BotProfile profile = EpisodePlan.dummy().bot;
	private boolean idle = true;
	private int episodeId;
	private int hopId;
	private int hopTimer;

	// What the world looked like before we touched it, for stop().
	private final Map<GameRule<Boolean>, Boolean> savedRules = new LinkedHashMap<>();
	private GameType savedMode;
	private ItemStack[] savedInventory;
	private Vec3 savedPos;
	private float savedYaw;
	private float savedPitch;

	private ArenaServer(MinecraftServer server, UUID agentId, Config cfg, Arena arena, SparPilot pilot, ArenaDriver driver, String ownerName) {
		this.server = server;
		this.agentId = agentId;
		this.cfg = cfg;
		this.arena = arena;
		this.pilot = pilot;
		this.driver = driver;
		// Two players of one name cannot be in a world, so a name that is taken falls back to the usual one.
		String bot = playerName(cfg.botName, BOT_NAME);
		String agent = playerName(cfg.agentName, AGENT_NAME);
		if (bot.equalsIgnoreCase(ownerName)) {
			bot = BOT_NAME;
		}
		if (agent.equalsIgnoreCase(ownerName) || agent.equalsIgnoreCase(bot)) {
			agent = AGENT_NAME.equalsIgnoreCase(bot) ? BOT_NAME : AGENT_NAME;
		}
		this.botName = bot;
		this.agentName = agent;
	}

	/** {@code name} if the game accepts it as a player's name (3 to 16 letters, digits or underscores), else {@code fallback}. */
	public static String playerName(String name, String fallback) {
		return name != null && name.matches("[A-Za-z0-9_]{3,16}") ? name : fallback;
	}

	/** The agent's body: the user's own character, or the fake player that stands in for it. */
	private ServerPlayer agent() {
		return driver != null ? fakeAgent : server.getPlayerList().getPlayer(agentId);
	}

	private boolean isAgent(Entity entity) {
		return driver != null ? entity == fakeAgent : entity.getUUID().equals(agentId);
	}

	/** Safe from any thread. */
	public static Truth truth() {
		return truth;
	}

	/** The sparring score as text, or empty before the first round. Safe from any thread. */
	public static String sparStatus() {
		return sparStatus;
	}

	/** What kind of hits each side has landed while sparring, as text. Safe from any thread. */
	public static String sparDetail() {
		return sparDetail;
	}

	// ---- lifecycle ------------------------------------------------------------------------------

	public static void start(MinecraftServer server, UUID agentId, Config cfg) {
		start(server, agentId, cfg, null, null);
	}

	/** The same arena, but the player fights for themselves and {@code pilot} plays the fake player. */
	public static void startSpar(MinecraftServer server, UUID playerId, Config cfg, SparPilot pilot) {
		start(server, playerId, cfg, pilot, null);
	}

	/**
	 * The same arena above {@code ownerId}'s head, but the agent's body is a fake player of its own,
	 * played by {@code driver} from this thread. The owner's character, inventory and position are
	 * not touched.
	 */
	public static void startHeadless(MinecraftServer server, UUID ownerId, Config cfg, ArenaDriver driver) {
		start(server, ownerId, cfg, null, driver);
	}

	private static void start(MinecraftServer server, UUID agentId, Config cfg, SparPilot pilot, ArenaDriver driver) {
		sparStatus = "";
		sparDetail = "";
		if (active != null) {
			active.shutdown();
		}
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (agent == null) {
			truth = Truth.failed("could not find you on the integrated server");
			return;
		}
		ServerLevel level = agent.level();
		if (level.dimension() != Level.OVERWORLD) {
			truth = Truth.failed("the arena needs the Overworld; go there and try again");
			return;
		}
		int floorY = Math.min(200, level.getMaxY() - 40);
		// Never smaller than the fights that start far apart need.
		Arena arena = arenaFor(server, level, agent, floorY, Math.max(Config.MIN_ARENA_RADIUS, cfg.arenaRadius));
		ArenaServer session = new ArenaServer(server, agentId, cfg, arena, pilot, driver, agent.getScoreboardName());
		try {
			session.setUp(agent);
			active = session;
			CombatLog.clear();
			session.publish();
		} catch (RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not set up the arena", e);
			// Undo whatever part of the setup had already happened.
			session.shutdown();
			truth = Truth.failed("arena setup failed: " + e);
		}
	}

	/**
	 * The arena for a new run: the one still standing from the last, if it is in this world, of the
	 * size asked for and whoever starts the run is at or near it. Otherwise that one comes down and
	 * a new one goes up above them.
	 */
	private static Arena arenaFor(MinecraftServer server, ServerLevel level, ServerPlayer owner, int floorY, int radius) {
		Arena old = standing;
		standing = null;
		if (old != null && old.level.getServer() == server) {
			if (old.level == level && old.radius == radius && old.floorY == floorY && old.near(owner.getX(), owner.getZ())) {
				return old;
			}
			try {
				old.remove();
			} catch (RuntimeException e) {
				SwordAi.LOGGER.warn("SwordAI could not take the previous arena down", e);
			}
		}
		return new Arena(level, (int) Math.floor(owner.getX()), floorY, (int) Math.floor(owner.getZ()), radius);
	}

	public static void stop(MinecraftServer server) {
		if (active != null) {
			active.shutdown();
		}
	}

	/** The world is closing: the run is ended, and the arena it left standing is taken out of the world before it is saved. */
	public static void onServerStopping(MinecraftServer server) {
		stop(server);
		Arena left = standing;
		standing = null;
		if (left != null && left.level.getServer() == server) {
			try {
				left.remove();
			} catch (RuntimeException e) {
				SwordAi.LOGGER.warn("SwordAI could not take the arena down", e);
			}
		}
	}

	/** The agent's connection is closing: put their things back before the player file is saved. */
	public static void onPlayerLeft(ServerPlayer player, MinecraftServer server) {
		if (active != null && player.getUUID().equals(active.agentId)) {
			active.shutdown();
		}
	}

	private void setUp(ServerPlayer agent) {
		ServerLevel level = arena.level;
		Inventory inv = agent.getInventory();
		if (driver == null) {
			savedPos = agent.position();
			savedYaw = agent.getYRot();
			savedPitch = agent.getXRot();
			savedMode = agent.gameMode.getGameModeForPlayer();
			savedInventory = new ItemStack[inv.getContainerSize()];
			for (int i = 0; i < savedInventory.length; i++) {
				savedInventory[i] = inv.getItem(i).copy();
			}
		}

		arena.build();

		GameRules rules = level.getGameRules();
		setRule(rules, GameRules.ADVANCE_TIME, false);
		setRule(rules, GameRules.ADVANCE_WEATHER, false);
		setRule(rules, GameRules.SPAWN_MOBS, false);
		setRule(rules, GameRules.NATURAL_HEALTH_REGENERATION, false);
		setRule(rules, GameRules.KEEP_INVENTORY, true);
		setRule(rules, GameRules.IMMEDIATE_RESPAWN, true);
		setRule(rules, GameRules.FALL_DAMAGE, false);
		setRule(rules, GameRules.PVP, true);
		setRule(rules, GameRules.SHOW_DEATH_MESSAGES, false);
		// Noon and clear skies: constant, bright lighting for the colour key.
		level.setDayTime(6000L);
		level.setWeatherParameters(6_000_000, 0, false, false);

		if (driver == null) {
			agent.setGameMode(GameType.SURVIVAL);
			agent.removeAllEffects();
			inv.clearContent();
			equip(agent);
			agent.teleportTo(level, arena.centerX, arena.floorY, arena.centerZ, Set.of(), 0f, 0f, true);
			heal(agent);
		} else {
			// The user's own character stays as and where it is; the agent gets a body of its own.
			spawnFake(agentName, -4);
		}

		spawnFake(botName, 4);
	}

	private void setRule(GameRules rules, GameRule<Boolean> rule, boolean value) {
		savedRules.put(rule, rules.get(rule));
		rules.set(rule, value, server);
	}

	/** Asks Carpet for a fake player of this name, {@code offsetX} blocks from the middle; it arrives some ticks later. */
	private void spawnFake(String name, int offsetX) {
		ServerPlayer existing = server.getPlayerList().getPlayerByName(name);
		if (existing instanceof EntityPlayerMPFake) {
			return;
		}
		if (existing != null) {
			throw new IllegalStateException("a real player named " + name + " is online");
		}
		if (EntityPlayerMPFake.isSpawningPlayer(name)) {
			return;
		}
		Vec3 pos = new Vec3(arena.centerX + offsetX, arena.floorY, arena.centerZ);
		boolean accepted = EntityPlayerMPFake.createFake(name, server, pos, offsetX > 0 ? 90.0 : -90.0, 0.0, Level.OVERWORLD, GameType.SURVIVAL, false);
		if (!accepted) {
			throw new IllegalStateException("Carpet refused to spawn the fake player " + name);
		}
	}

	/** The fake player of this name once it has arrived, made ready for the arena; null until then. */
	private ServerPlayer adoptFake(String name) {
		ServerPlayer candidate = server.getPlayerList().getPlayerByName(name);
		if (!(candidate instanceof EntityPlayerMPFake)) {
			return null;
		}
		candidate.setGameMode(GameType.SURVIVAL);
		candidate.getInventory().clearContent();
		equip(candidate);
		heal(candidate);
		return candidate;
	}

	private void shutdown() {
		shutdown(null);
	}

	/** @param error why the arena failed, or null when it is simply being stopped */
	private void shutdown(String error) {
		boolean wasActive = active == this;
		active = null;
		KnockbackLag.off();
		truth = error == null ? Truth.INACTIVE : Truth.failed(error);
		sparStatus = "";
		sparDetail = "";
		if (wasActive && (driver != null || pilot != null)) {
			// First of all: what was learned is saved while everything is still in place.
			try {
				if (driver != null) {
					driver.arenaClosed(error);
				} else {
					pilot.arenaClosed(error);
				}
			} catch (RuntimeException e) {
				SwordAi.LOGGER.error("SwordAI could not close the training cleanly", e);
			}
		}
		try {
			if (savedTickRate > 0f) {
				server.tickRateManager().setTickRate(savedTickRate);
				savedTickRate = -1f;
			}
			if (bot instanceof EntityPlayerMPFake fake && !fake.isRemoved()) {
				fake.kill(net.minecraft.network.chat.Component.literal("SwordAI stopped"));
			}
			bot = null;
			if (fakeAgent instanceof EntityPlayerMPFake fake && !fake.isRemoved()) {
				fake.kill(net.minecraft.network.chat.Component.literal("SwordAI stopped"));
			}
			fakeAgent = null;
			ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
			// The arena itself stays up for the next run; only what a fight left in it goes.
			arena.clearPillar(agentPillar);
			arena.clearPillar(botPillar);
			standing = arena;
			if (agent != null) {
				restore(agent);
			}
			GameRules rules = arena.level.getGameRules();
			for (Map.Entry<GameRule<Boolean>, Boolean> e : savedRules.entrySet()) {
				rules.set(e.getKey(), e.getValue(), server);
			}
			savedRules.clear();
		} catch (RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not fully restore the world", e);
		}
	}

	private void restore(ServerPlayer agent) {
		if (savedInventory == null) {
			return;
		}
		Inventory inv = agent.getInventory();
		inv.clearContent();
		for (int i = 0; i < savedInventory.length && i < inv.getContainerSize(); i++) {
			inv.setItem(i, savedInventory[i]);
		}
		savedInventory = null;
		if (savedMode != null) {
			agent.setGameMode(savedMode);
		}
		heal(agent);
		if (savedPos != null) {
			agent.teleportTo(agent.level(), savedPos.x, savedPos.y, savedPos.z, Set.of(), savedYaw, savedPitch, true);
		}
	}

	// ---- commands from the client ---------------------------------------------------------------

	/** Starts a fresh episode: both fighters repositioned, healed, and the bot given its behaviour. */
	public static void reset(EpisodePlan plan) {
		ArenaServer s = active;
		if (s == null || s.bot == null) {
			return;
		}
		ServerPlayer agent = s.agent();
		if (agent == null) {
			return;
		}
		s.profile = plan.bot;
		if (plan.seed != 0L) {
			// The same draws from the start as a fight it lost, to fight that one again exactly; see LostCases.
			s.rng.setSeed(plan.seed);
			s.brain.reseed(plan.seed * 0x9E3779B97F4A7C15L + 1L);
		}
		s.brain.setProfile(plan.bot);
		s.brain.setLag(plan.botLag);
		s.techPlays = plan.style != null;
		if (s.techPlays) {
			s.tech.start(plan.style, plan.seed != 0L ? plan.seed : s.rng.nextLong());
			// The bot feels a hit when its knockback reaches it, at least a tick late; see TechniqueBot.
			KnockbackLag.set(s.bot, s.tech.ping(), 1f);
		} else {
			s.tech.stop();
			KnockbackLag.set(s.bot, plan.botPing, plan.botKnockback);
		}
		KnockbackLag.setAgent(agent, plan.agentKnockLag);
		s.setIdleNow(false);
		s.rival = s.rivals.length == 0 ? null : s.rivals[Math.max(0, Math.min(s.rivals.length - 1, plan.rivalIndex))];
		s.rivalPlays = s.rival != null && BotProfile.SELF.equals(plan.bot.movement);
		s.mixedTurns = plan.mixedTurns;
		s.setOutside(plan.bot.outside);
		if (s.rivalPlays) {
			s.rival.reset();
			s.rival.setLag(plan.botLag);
		}

		s.arena.clearPillar(s.agentPillar);
		double span = s.arena.radius * 0.35;
		double ax = s.arena.centerX + (s.rng.nextDouble() * 2 - 1) * span;
		double az = s.arena.centerZ + (s.rng.nextDouble() * 2 - 1) * span;
		double ay = s.arena.floorY;
		if (plan.agentMaxPillar > 0 && s.rng.nextBoolean()) {
			ax = Math.floor(ax) + 0.5;
			az = Math.floor(az) + 0.5;
			ay = s.arena.buildPillar(ax, az, 1 + s.rng.nextInt(plan.agentMaxPillar), s.agentPillar);
		}
		// The view direction is kept: the bot is placed relative to it anyway, and a camera that
		// snaps to a random heading every episode looks exactly like the agent looking away.
		float yaw = Float.isNaN(plan.startYaw) ? ViewGeometry.wrapDegrees(agent.getYRot()) : plan.startYaw;
		if (Float.isNaN(plan.startYaw) && plan.seedYaw && plan.seed != 0L) {
			// From a generator of its own, so the fight's other draws are the ones they always were.
			yaw = new java.util.Random(plan.seed ^ 0x5EED_9A3DL).nextFloat() * 360f - 180f;
		}
		double cornerX = 0;
		double cornerZ = 0;
		if (plan.cornerStart) {
			// The opponent in a corner, the agent four to eight blocks out from it and looking its way,
			// give or take forty degrees.
			double inset = s.arena.radius - CORNER_INSET;
			double sx = s.rng.nextBoolean() ? 1 : -1;
			double sz = s.rng.nextBoolean() ? 1 : -1;
			cornerX = s.arena.centerX + sx * inset;
			cornerZ = s.arena.centerZ + sz * inset;
			double out = Math.atan2(-sz, -sx) + Math.toRadians((s.rng.nextDouble() * 2 - 1) * 30.0);
			double far = 4.0 + s.rng.nextDouble() * 4.0;
			ax = cornerX + Math.cos(out) * far;
			az = cornerZ + Math.sin(out) * far;
			ay = s.arena.floorY;
			s.arena.clearPillar(s.agentPillar);
			yaw = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(cornerX - ax, cornerZ - az) + (s.rng.nextFloat() * 2f - 1f) * 40f);
		}
		float pitch = (s.rng.nextFloat() * 2f - 1f) * plan.startPitchRangeDeg;
		plan.usedYaw = yaw;
		agent.teleportTo(s.arena.level, ax, ay, az, Set.of(), yaw, pitch, true);
		if (s.driver != null) {
			// A fake player has no client to stop it: it would slide on with the speed it had.
			agent.setDeltaMovement(Vec3.ZERO);
			agent.setYHeadRot(yaw);
			s.agentLastPos = null;
		}
		heal(agent);
		heal(s.bot);
		if (plan.bot.jumpInAlways) {
			// The jump-in drill begins with its sword all the way back: one exchange is a fight, and the last one's swing
			// was still coming back (23 Oct: 55% of the drill's fights began with it under 0.9).
			((dev.swordai.mixin.LivingEntityAccessor) s.bot).swordai$setAttackStrengthTicker(1000);
		}
		if (plan.drillLift) {
			// The punish crit drill: its sword all the way back, the agent's part of the way (it is lifted before it can swing).
			((dev.swordai.mixin.LivingEntityAccessor) s.bot).swordai$setAttackStrengthTicker(1000);
			((dev.swordai.mixin.LivingEntityAccessor) agent).swordai$setAttackStrengthTicker(plan.drillAgentTicker);
		}
		// Put back anything that went missing since the last episode.
		s.equip(agent);
		s.equip(s.bot);

		if (plan.cornerStart) {
			s.moveBot(cornerX, cornerZ, 0, ax, az);
		} else {
			s.placeBotRandomly(ax, az, yaw);
		}
		if (plan.scene != null) {
			// A scene: both fighters exactly where, how fast and how ready they were at that moment of an earlier fight.
			SceneRecorder.apply(s.arena.level, agent, plan.scene, 0);
			SceneRecorder.apply(s.arena.level, s.bot, plan.scene, SceneRecorder.BODY);
			s.botLastPos = null;
			s.agentLastPos = null;
		}
		if (!Float.isNaN(plan.startYaw)) {
			// A lost fight set up again: where it starts, to be seen to be the same every time.
			SwordAi.LOGGER.info(String.format(java.util.Locale.ROOT, "lost fight again from seed %x: agent %.2f %.2f facing %.1f, opponent %.2f %.2f",
					plan.seed, ax, az, yaw, s.bot.getX(), s.bot.getZ()));
		}
		CombatLog.clear();
		s.episodeId++;
		s.publish();
	}

	/**
	 * Self-test helper: puts a motionless bot at an exact distance and bearing from the agent.
	 *
	 * @param agentYaw the yaw the client is currently looking along
	 */
	public static void place(float distance, float bearingDeg, float agentYaw) {
		ArenaServer s = active;
		if (s == null || s.bot == null) {
			return;
		}
		ServerPlayer agent = s.server.getPlayerList().getPlayer(s.agentId);
		if (agent == null) {
			return;
		}
		s.profile = EpisodePlan.dummy().bot;
		s.brain.setProfile(s.profile);
		s.techPlays = false;
		s.tech.stop();
		s.rivalPlays = false;
		s.setOutside(null);
		s.setIdleNow(true);
		s.arena.clearPillar(s.agentPillar);
		// Centre the agent so the bot fits inside the walls at any bearing.
		agent.teleportTo(s.arena.level, s.arena.centerX, s.arena.floorY, s.arena.centerZ, Set.of(), agentYaw, 0f, true);
		double rad = Math.toRadians(agentYaw + bearingDeg);
		s.moveBot(s.arena.centerX - Math.sin(rad) * distance, s.arena.centerZ + Math.cos(rad) * distance, 0, s.arena.centerX, s.arena.centerZ);
		CombatLog.clear();
		s.episodeId++;
		s.publish();
	}

	/**
	 * Self-play: hands over the copies of the agent that play the bot from the next episode on. The
	 * first is the newest; the episode's plan says which one it is to be.
	 */
	public static void setRivals(BotPilot[] rivals) {
		ArenaServer s = active;
		if (s != null && s.pilot == null) {
			s.rivals = rivals;
			s.rival = null;
			s.rivalPlays = false;
		}
	}

	/**
	 * Runs the world at this many times its normal speed while the arena stands; 1 puts it back.
	 * Everything in a fight is counted in ticks, so a fight goes the same way at any speed.
	 */
	public static void setSpeed(float speed) {
		ArenaServer s = active;
		if (s == null) {
			return;
		}
		ServerTickRateManager ticks = s.server.tickRateManager();
		if (s.savedTickRate < 0f) {
			s.savedTickRate = ticks.tickrate();
		}
		ticks.setTickRate(Math.max(1f, speed) * NORMAL_TICK_RATE);
	}

	/** While idle the bot stands still and never hops; used during PPO updates and pauses. */
	public static void setIdle(boolean idle) {
		if (active != null) {
			active.setIdleNow(idle);
		}
	}

	private void setIdleNow(boolean value) {
		idle = value;
		brain.setIdle(value);
		if (outsidePlays && bot != null) {
			// While the agent cannot move (an update, a pause), the outside bot does not fight either.
			botOption("combat " + !value);
		}
		if (value && rivalPlays && bot != null) {
			rival.idle(bot);
		}
	}

	// ---- the outside bot ------------------------------------------------------------------------

	/** The game runs on Carpet PvP, which has the {@code /bot} command, and not on Carpet. */
	public static boolean hasBotCommand(MinecraftServer server) {
		return server != null && server.getCommands().getDispatcher().getRoot().getChild("bot") != null;
	}

	/**
	 * The outside bot: Carpet PvP's own fighter plays the arena's bot at this level ("beginner" to
	 * "expert"), with the kit the arena gives it, or with null no longer does and the scripted brain has it
	 * again. Set through its {@code /bot option} command, so nothing of Carpet PvP is needed to build this.
	 * Where the game has no such command the brain plays on.
	 */
	private void setOutside(String level) {
		boolean on = level != null && bot != null && hasBotCommand(server);
		if (on) {
			if (!level.equals(outsideLevel)) {
				for (String option : new String[] {"combatStyle melee", "difficulty " + level, "autoTarget true", "targetPlayers true",
						"targetBots false", "targetMobs false", "revenge true", "targetRange 32", "retreatHealth 0", "autoTotem false",
						"autoShield false", "autoFood false"}) {
					botOption(option);
				}
				outsideLevel = level;
			}
			botOption("combat true");
		} else if (outsidePlays && bot != null) {
			botOption("combat false");
		}
		outsidePlays = on;
	}

	private void botOption(String option) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "bot option " + botName + " " + option);
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("SwordAI: the outside bot did not take '{}'", option, e);
		}
	}

	// ---- bot placement --------------------------------------------------------------------------

	/** How far in from the walls the opponent of a fight that begins at a corner stands. */
	private static final double CORNER_INSET = 1.4;

	private void placeBotRandomly(double ax, double az, float agentYaw) {
		double x = ax;
		double z = az;
		boolean found = false;
		// Near or far is drawn once: a far spot that does not fit inside the walls is tried again as a
		// far spot at another bearing, not turned into a near one.
		boolean far = profile.farChance > 0f && rng.nextFloat() < profile.farChance;
		double least = far ? profile.farMinDistance : profile.minDistance;
		double most = far ? profile.farMaxDistance : profile.maxDistance;
		for (int i = 0; i < PLACEMENT_ATTEMPTS && !found; i++) {
			double d = least + rng.nextDouble() * Math.max(0, most - least);
			// To either side, no nearer straight ahead than the profile says.
			double least0 = Math.max(0f, Math.min(profile.minBearingDeg, profile.maxBearingDeg));
			double bearing = (least0 + rng.nextDouble() * (profile.maxBearingDeg - least0)) * (rng.nextBoolean() ? 1 : -1);
			double rad = Math.toRadians(agentYaw + bearing);
			x = ax - Math.sin(rad) * d;
			z = az + Math.cos(rad) * d;
			found = arena.inside(x, z, 1.5);
		}
		if (!found) {
			// The requested cone does not fit inside the walls from here; stay as close to it as possible.
			x = arena.clampX(x);
			z = arena.clampZ(z);
		}
		int pillar = 0;
		if (profile.maxPillar > 0 && rng.nextInt(5) >= 2) {
			pillar = 1 + rng.nextInt(profile.maxPillar);
		}
		moveBot(x, z, pillar, ax, az);
	}

	private void moveBot(double x, double z, int pillar, double faceX, double faceZ) {
		arena.clearPillar(botPillar);
		double y = arena.floorY;
		boolean raised = false;
		if (pillar > 0) {
			x = Math.floor(x) + 0.5;
			z = Math.floor(z) + 0.5;
			boolean onAgentColumn = Math.floor(x) == Math.floor(faceX) && Math.floor(z) == Math.floor(faceZ);
			if (!onAgentColumn) {
				y = arena.buildPillar(x, z, pillar, botPillar);
				raised = true;
			}
		}
		// A bot on a one-block column would simply walk off it; it stays put until the next hop.
		brain.setAnchored(raised);
		brain.setPost(x, z);
		float yaw = ViewGeometry.yawTo(faceX - x, faceZ - z);
		bot.teleportTo(arena.level, x, y, z, Set.of(), yaw, 0f, true);
		botLastPos = null;
		bot.setDeltaMovement(Vec3.ZERO);
		bot.setYHeadRot(yaw);
		hopTimer = 0;
		hopId++;
	}

	// ---- per tick -------------------------------------------------------------------------------

	public static void onServerTick(MinecraftServer server) {
		ArenaServer s = active;
		if (s != null && s.server == server) {
			try {
				s.tick();
			} catch (RuntimeException e) {
				SwordAi.LOGGER.error("SwordAI arena tick failed", e);
				s.shutdown("arena error: " + e);
			}
		}
	}

	private void tick() {
		KnockbackLag.tick();
		// Whoever started this has to stay: the arena stands above them and is taken down when they go.
		if (server.getPlayerList().getPlayer(agentId) == null) {
			shutdown();
			return;
		}
		if (bot == null || bot.isRemoved()) {
			bot = adoptFake(botName);
			if (bot != null) {
				ticksWithoutBot = 0;
				moveBot(arena.centerX + 4, arena.centerZ, 0, arena.centerX, arena.centerZ);
			} else if (!waitForFake(botName, 4, ++ticksWithoutBot, "the sparring bot (" + botName + ")")) {
				return;
			}
		}
		if (driver != null && (fakeAgent == null || fakeAgent.isRemoved())) {
			fakeAgent = adoptFake(agentName);
			if (fakeAgent != null) {
				ticksWithoutAgent = 0;
				agentLastPos = null;
				fakeAgent.teleportTo(arena.level, arena.centerX - 4, arena.floorY, arena.centerZ, Set.of(), -90f, 0f, true);
				fakeAgent.setDeltaMovement(Vec3.ZERO);
				fakeAgent.setYHeadRot(-90f);
			} else if (!waitForFake(agentName, -4, ++ticksWithoutAgent, "the agent's fake player (" + agentName + ")")) {
				return;
			}
		}
		ServerPlayer agent = agent();
		if (bot == null || agent == null) {
			publish();
			if (driver != null) {
				driver.arenaTick(fakeAgent, bot, arena.floorY);
			}
			return;
		}

		// A real player's client reports how far it moved each tick, and the game reads that when it
		// decides whether a hit is a sweep: a sword hit by someone moving slower than a sprint is one.
		// A fake player reports nothing and would count as standing still, so every hit of its that is
		// not a sprint-knockback hit came out as a sweep, however fast it was really running.
		Vec3 botPos = bot.position();
		if (botLastPos == null) {
			botFall.reset();
		}
		bot.setKnownMovement(botLastPos == null ? Vec3.ZERO : botPos.subtract(botLastPos));
		botLastPos = botPos;
		// Nor does the game count how far a fake player has fallen, and without that none of its hits
		// is ever a critical hit; see FakeFall.
		botFall.tick(bot);
		if (driver != null) {
			Vec3 agentPos = agent.position();
			if (agentLastPos == null) {
				agentFall.reset();
			}
			agent.setKnownMovement(agentLastPos == null ? Vec3.ZERO : agentPos.subtract(agentLastPos));
			agentLastPos = agentPos;
			agentFall.tick(agent);
		}

		if (pilot != null) {
			tickSpar(agent);
			publish();
			return;
		}

		upkeep(agent, profile.agentImmortal);
		upkeep(bot, profile.botImmortal);

		if (!idle && profile.hopIntervalTicks > 0 && ++hopTimer >= profile.hopIntervalTicks) {
			placeBotRandomly(agent.getX(), agent.getZ(), agent.getYRot());
		}
		if (rivalPlays && !idle && driver != null && mixedTurns && rng.nextBoolean()) {
			// The agent's move comes first on this tick, and the copy's after it.
			publish();
			driver.arenaTick(agent, bot, arena.floorY);
			if (active == this && rivalPlays && !idle && bot != null && !bot.isRemoved()) {
				rival.tick(bot, agent, arena.floorY);
			}
			return;
		}
		if (outsidePlays && !idle) {
			// Carpet PvP's own fighter has it; see setOutside.
		} else if (rivalPlays && !idle) {
			rival.tick(bot, agent, arena.floorY);
		} else if (techPlays && !idle) {
			tech.tick(bot, agent);
		} else {
			// Also while a self-play episode is on hold: the brain then only stands and looks.
			brain.tick(bot, agent, arena);
		}
		publish();
		if (driver != null) {
			// The agent chooses its move after the opponent, on the same tick; both then move together.
			driver.arenaTick(agent, bot, arena.floorY);
		}
	}

	/**
	 * A fake player that has not arrived yet: asks for it again now and then, and gives up after a
	 * while. Returns false when it has given up and the arena is gone.
	 */
	private boolean waitForFake(String name, int offsetX, int ticksWaited, String what) {
		if (ticksWaited > BOT_TIMEOUT_TICKS) {
			shutdown(what + " never appeared (is Carpet's fake player support working?)");
			return false;
		}
		if (ticksWaited % BOT_RETRY_TICKS == 0) {
			spawnFake(name, offsetX);
		}
		return true;
	}

	// ---- sparring --------------------------------------------------------------------------------

	private void tickSpar(ServerPlayer player) {
		if (!sparStarted || roundOver) {
			if (roundOver) {
				// Before anyone is moved: a pilot that learns scores its last move against the fight as it ended.
				pilot.roundOver(bot, player, arena.floorY);
			}
			sparStarted = true;
			roundOver = false;
			beginRound(player);
		}
		// Nobody is hurt during the stand-off before a round.
		boolean standOff = countdown > 0;
		upkeep(player, standOff);
		upkeep(bot, standOff);
		if (standOff) {
			pilot.idle(bot);
			if (--countdown % 20 == 0) {
				player.displayClientMessage(Component.literal(countdown == 0 ? "Fight!" : Integer.toString(countdown / 20)), true);
			}
			if (countdown == 0) {
				pilot.reset();
			}
			return;
		}
		pilot.tick(bot, player, arena.floorY);
	}

	/** Both fighters back to their corners, patched up, facing each other. */
	private void beginRound(ServerPlayer player) {
		arena.clearPillar(agentPillar);
		double px = arena.centerX - SPAR_START_OFFSET;
		double bx = arena.centerX + SPAR_START_OFFSET;
		player.teleportTo(arena.level, px, arena.floorY, arena.centerZ, Set.of(), ViewGeometry.yawTo(bx - px, 0), 0f, true);
		player.setDeltaMovement(Vec3.ZERO);
		moveBot(bx, arena.centerZ, 0, px, arena.centerZ);
		heal(player);
		heal(bot);
		equip(player);
		equip(bot);
		pilot.idle(bot);
		countdown = Math.max(20, cfg.sparCountdownTicks);
		player.displayClientMessage(Component.literal(Integer.toString(countdown / 20)), true);
		sparStatus = "you " + playerRounds + " : " + aiRounds + " SwordAI";
	}

	private void sparKnockout(boolean botDown) {
		if (countdown > 0 || roundOver) {
			return;
		}
		if (botDown) {
			playerRounds++;
		} else {
			aiRounds++;
		}
		if (pilot.learns()) {
			// From the fake player's side: the person going down is the knockout it dealt.
			CombatLog.knockout(!botDown);
		}
		roundOver = true;
		sparStatus = "you " + playerRounds + " : " + aiRounds + " SwordAI";
		ServerPlayer player = server.getPlayerList().getPlayer(agentId);
		if (player != null) {
			player.sendSystemMessage(Component.literal("[SwordAI] " + (botDown ? "You won that round. " : "SwordAI won that round. ")
					+ "Score: " + sparStatus));
		}
	}

	private void upkeep(ServerPlayer player, boolean immortal) {
		// Sprinting needs a full food bar; hunger is not part of what is being learned.
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		if (immortal && player.getHealth() < player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
		if (player.getY() < arena.floorY - 3) {
			player.teleportTo(arena.level, arena.centerX, arena.floorY, arena.centerZ, Set.of(), player.getYRot(), 0f, true);
		}
	}

	private void equip(ServerPlayer player) {
		Identifier swordId = Identifier.tryParse(cfg.kitSword == null ? "" : cfg.kitSword);
		Item sword = swordId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(swordId);
		if (sword == Items.AIR) {
			sword = Items.DIAMOND_SWORD;
		}
		ItemStack wantedSword = kitItem(sword);
		if (!ItemStack.matches(player.getInventory().getItem(0), wantedSword)) {
			player.getInventory().setItem(0, wantedSword);
		}
		player.getInventory().setSelectedSlot(0);
		Item[] armor = switch (cfg.kitArmor == null ? "none" : cfg.kitArmor) {
			case "diamond" -> new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			case "netherite" -> new Item[] {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
			case "iron" -> new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
			default -> null;
		};
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < slots.length; i++) {
			ItemStack wanted = armor == null ? ItemStack.EMPTY : kitItem(armor[i]);
			if (!ItemStack.matches(player.getItemBySlot(slots[i]), wanted)) {
				player.setItemSlot(slots[i], wanted);
			}
		}
	}

	/**
	 * A kit item that never wears out. A sword lasts some 1,500 hits and armour a few hundred, which
	 * is minutes of sparring; a fighter left bare-handed can swing two and a half times as often
	 * and learns a different game.
	 */
	private static ItemStack kitItem(Item item) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		return stack;
	}

	private static void heal(ServerPlayer player) {
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		player.invulnerableTime = 0;
		player.hurtTime = 0;
		player.resetFallDistance();
	}

	private void publish() {
		ServerPlayer b = bot;
		ServerPlayer agent = agent();
		if (b != null && agent != null && pilot == null) {
			scenes.note(server.getTickCount(), agent, b);
		}
		truth = new Truth(
				true,
				b != null,
				b == null ? null : b.getUUID(),
				episodeId,
				hopId,
				arena.centerX,
				arena.floorY,
				arena.centerZ,
				arena.radius,
				b == null ? 0f : b.getHealth(),
				b == null ? 0f : b.getAttackStrengthScale(0f),
				b != null && b.isSprinting(),
				b != null && b.onGround(),
				b != null && !b.onGround() && b.getY() < b.yo,
				agent != null && agent.isSprinting(),
				server.getTickCount(),
				techPlays ? TechniqueBot.lastTechnique() : -1,
				techPlays ? TechniqueBot.lastKeys() : 0,
				null);
	}

	// ---- combat hooks ---------------------------------------------------------------------------

	/** Both fighters on server tick {@code tick} (Truth#tick), or null: see SceneRecorder. */
	public static double[] sceneAt(long tick) {
		ArenaServer s = active;
		return s == null ? null : s.scenes.at(tick);
	}

	/** Called at the start of every server-side player attack. */
	public static void onAttack(Player attacker, float charge, boolean sprinting, boolean crit, boolean rising, boolean sweep) {
		ArenaServer s = active;
		if (s == null) {
			return;
		}
		if (s.pilot != null) {
			// Sparring: nothing is scored, but what kind of hits each side lands is worth knowing.
			boolean byBot = attacker == s.bot;
			if (byBot || s.isAgent(attacker)) {
				s.sparHits[byBot ? 0 : 1].add(charge, sprinting, crit, sweep);
				s.describeSpar();
				if (s.pilot.learns()) {
					// The fake player is the one learning here, so the log is kept from its side.
					if (byBot) {
						CombatLog.agentSwung(charge, sprinting, crit, rising, sweep);
					} else {
						CombatLog.botSwung(crit);
					}
				}
			}
			return;
		}
		if (s.isAgent(attacker)) {
			CombatLog.agentSwung(charge, sprinting, crit, rising, sweep);
			s.agentKind = dev.swordai.core.pvp.Events.kindOf(charge, sprinting, crit);
		} else if (attacker == s.bot) {
			s.botKind = dev.swordai.core.pvp.Events.kindOf(charge, sprinting, crit);
			CombatLog.botSwung(crit, s.botKind == dev.swordai.core.pvp.Events.SPRINT);
		}
	}

	private void describeSpar() {
		sparDetail = "SwordAI: " + sparHits[0].describe() + "; " + pilot.swingSummary() + ". You: " + sparHits[1].describe() + ".";
	}

	/**
	 * Swings that reached the other fighter, by what they came to. A swing only counts as a hit of
	 * some kind once it has done damage; one that lands while the other is still flashing from the
	 * last hit does none, and is counted as that. The same way the training numbers are counted.
	 */
	private static final class HitCount {
		private static final int KNOCKBACK = 0;
		private static final int CRIT = 1;
		private static final int SWEEP = 2;
		private static final int UNCHARGED = 3;
		private static final int PLAIN = 4;

		private final int[] kinds = new int[5];
		private int reached;
		private int harmless;
		/** The kind of the swing that has reached and not been settled yet, or -1. */
		private int pending = -1;

		void add(float charge, boolean sprinting, boolean crit, boolean sweep) {
			if (pending >= 0) {
				harmless++;
			}
			reached++;
			pending = charge <= 0.9f ? UNCHARGED : sprinting ? KNOCKBACK : crit ? CRIT : sweep ? SWEEP : PLAIN;
		}

		/** The swing that just reached did damage. */
		void damaged() {
			if (pending >= 0) {
				kinds[pending]++;
				pending = -1;
			}
		}

		String describe() {
			int hits = kinds[KNOCKBACK] + kinds[CRIT] + kinds[SWEEP] + kinds[UNCHARGED] + kinds[PLAIN];
			return hits + " hits (" + kinds[KNOCKBACK] + " sprint knockback, " + kinds[CRIT] + " crit, " + kinds[SWEEP] + " sweep, "
					+ kinds[UNCHARGED] + " before the sword was charged, " + kinds[PLAIN] + " plain) and " + (reached - hits)
					+ " more swings that reached but did no damage";
		}
	}

	public static void onDamage(LivingEntity victim, DamageSource source, float dealt) {
		ArenaServer s = active;
		if (s == null || s.bot == null) {
			return;
		}
		Entity attacker = source.getEntity();
		if (attacker == null) {
			return;
		}
		boolean botWasHit = victim == s.bot && s.isAgent(attacker);
		boolean botDidHit = s.isAgent(victim) && attacker == s.bot;
		if (s.pilot != null) {
			// Sparring: the fake player is the fighter, and these are its hit clocks.
			if (botWasHit) {
				s.pilot.hitTaken();
				s.sparHits[1].damaged();
				s.describeSpar();
				if (s.pilot.learns()) {
					CombatLog.botDamagedAgent(dealt);
				}
			} else if (botDidHit) {
				s.pilot.hitDealt();
				s.sparHits[0].damaged();
				s.describeSpar();
				if (s.pilot.learns()) {
					CombatLog.agentDamagedBot(dealt);
				}
			}
			return;
		}
		if (s.rivalPlays) {
			// The copy playing the bot keeps its own hit clocks, like any fighter.
			if (botWasHit) {
				s.rival.hitTaken();
			} else if (botDidHit) {
				s.rival.hitDealt();
			}
		}
		if (botWasHit) {
			CombatLog.agentDamagedBot(dealt);
			if (s.techPlays) {
				s.tech.tookHit(s.agentKind);
			} else if (!s.rivalPlays) {
				s.brain.tookHit();
			}
		} else if (botDidHit) {
			CombatLog.botDamagedAgent(dealt);
			if (s.techPlays) {
				s.tech.landedHit(s.botKind);
			} else if (!s.rivalPlays) {
				s.brain.landedHit();
			}
		}
	}

	/** Nobody dies in the arena: a lethal hit is recorded as a knockout and the victim is patched up. */
	public static boolean allowDeath(LivingEntity entity) {
		ArenaServer s = active;
		if (s == null) {
			return true;
		}
		boolean isBot = entity == s.bot;
		boolean isAgent = s.isAgent(entity);
		if (!isBot && !isAgent) {
			return true;
		}
		entity.setHealth(entity.getMaxHealth());
		if (s.pilot != null) {
			s.sparKnockout(isBot);
		} else {
			CombatLog.knockout(isBot);
		}
		return false;
	}
}
