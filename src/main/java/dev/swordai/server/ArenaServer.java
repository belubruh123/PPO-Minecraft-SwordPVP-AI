package dev.swordai.server;

import carpet.patches.EntityPlayerMPFake;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.league.InverseDynamics;
import dev.swordai.core.vision.ViewGeometry;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Integrated-server arena hosting league training, paired checks, key-moment scene replays, and
 * AlphaZero outcome search. All methods except {@link #truth()} run on the server thread.
 */
public final class ArenaServer {
	public static final String BOT_NAME = "SwordBot";
	private static final int BOT_RETRY_TICKS = 100;
	private static final int BOT_TIMEOUT_TICKS = 400;
	private static final int PLACEMENT_ATTEMPTS = 40;
	private static final float NORMAL_TICK_RATE = 20f;

	private static volatile Truth truth = Truth.INACTIVE;
	private static ArenaServer active;
	private static Arena standing;
	public static volatile long agentTickerSetAt = -1;
	public static volatile int scenesPutBack;

	private final MinecraftServer server;
	private final UUID agentId;
	private final Config cfg;
	private final Arena arena;
	private final String botName;
	private final SceneRecorder scenes = new SceneRecorder();
	private final SparBot sparBot = new SparBot();
	private final FakeFall botFall = new FakeFall();
	private final Random rng = new Random();

	private ServerPlayer bot;
	private Vec3 botLastPos;
	private int ticksWithoutBot;
	private SparPilot activePilot;
	private double[] pendingScene;
	private EpisodePlan pendingPlan;
	private boolean idle = true;
	private int episodeId;
	private int hopId;
	private float savedTickRate = -1f;

	private final Map<GameRule<Boolean>, Boolean> savedRules = new LinkedHashMap<>();
	private GameType savedMode;
	private ItemStack[] savedInventory;
	private Vec3 savedPos;
	private float savedYaw;
	private float savedPitch;

	private ArenaServer(MinecraftServer server, UUID agentId, Config cfg, Arena arena, String ownerName) {
		this.server = server;
		this.agentId = agentId;
		this.cfg = cfg;
		this.arena = arena;
		String name = playerName(cfg.botName, BOT_NAME);
		this.botName = name.equalsIgnoreCase(ownerName) ? BOT_NAME : name;
	}

	public static String playerName(String name, String fallback) {
		return name != null && name.matches("[A-Za-z0-9_]{3,16}") ? name : fallback;
	}

	public static Truth truth() {
		return truth;
	}

	public static void setInverseDynamics(InverseDynamics idm) {
		if (active != null) {
			active.sparBot.setInverseDynamics(idm);
		}
	}

	public static void start(MinecraftServer server, UUID agentId, Config cfg) {
		if (active != null) {
			active.shutdown(null);
		}
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (agent == null) {
			truth = Truth.failed("could not find you on the integrated server");
			return;
		}
		ServerLevel level = agent.level();
		if (level.dimension() != Level.OVERWORLD) {
			truth = Truth.failed("the arena needs the Overworld");
			return;
		}
		int floorY = Math.min(200, level.getMaxY() - 40);
		Arena arena = arenaFor(server, level, agent, floorY, Math.max(Config.MIN_ARENA_RADIUS, cfg.arenaRadius));
		ArenaServer session = new ArenaServer(server, agentId, cfg, arena, agent.getScoreboardName());
		try {
			session.setUp(agent);
			active = session;
			CombatLog.clear();
			session.publish();
		} catch (RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not set up the arena", e);
			session.shutdown("arena setup failed: " + e);
		}
	}

	private static Arena arenaFor(MinecraftServer server, ServerLevel level, ServerPlayer owner, int floorY, int radius) {
		Arena old = standing;
		standing = null;
		if (old != null && old.level.getServer() == server) {
			if (old.level == level && old.radius == radius && old.floorY == floorY && old.near(owner.getX(), owner.getZ())) {
				return old;
			}
			try {
				old.remove();
			} catch (RuntimeException ignored) {
			}
		}
		return new Arena(level, (int) Math.floor(owner.getX()), floorY, (int) Math.floor(owner.getZ()), radius);
	}

	public static void stop(MinecraftServer server) {
		if (active != null) {
			active.shutdown(null);
		}
	}

	public static void onServerStopping(MinecraftServer server) {
		stop(server);
		Arena left = standing;
		standing = null;
		if (left != null && left.level.getServer() == server) {
			try {
				left.remove();
			} catch (RuntimeException ignored) {
			}
		}
	}

	public static void onPlayerLeft(ServerPlayer player, MinecraftServer server) {
		if (active != null && player.getUUID().equals(active.agentId)) {
			active.shutdown(null);
		}
	}

	private void setUp(ServerPlayer agent) {
		ServerLevel level = arena.level;
		Inventory inv = agent.getInventory();
		savedPos = agent.position();
		savedYaw = agent.getYRot();
		savedPitch = agent.getXRot();
		savedMode = agent.gameMode.getGameModeForPlayer();
		savedInventory = new ItemStack[inv.getContainerSize()];
		for (int i = 0; i < savedInventory.length; i++) {
			savedInventory[i] = inv.getItem(i).copy();
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
		level.setDayTime(6000L);
		level.setWeatherParameters(6_000_000, 0, false, false);

		agent.setGameMode(GameType.SURVIVAL);
		agent.removeAllEffects();
		inv.clearContent();
		equip(agent);
		agent.teleportTo(level, arena.centerX, arena.floorY, arena.centerZ, Set.of(), 0f, 0f, true);
		heal(agent);
		spawnFake(botName, 4);
	}

	private void setRule(GameRules rules, GameRule<Boolean> rule, boolean value) {
		savedRules.put(rule, rules.get(rule));
		rules.set(rule, value, server);
	}

	private void spawnFake(String name, int offsetX) {
		ServerPlayer existing = server.getPlayerList().getPlayerByName(name);
		if (existing instanceof EntityPlayerMPFake || EntityPlayerMPFake.isSpawningPlayer(name)) {
			return;
		}
		if (existing != null) {
			throw new IllegalStateException("a real player named " + name + " is online");
		}
		Vec3 pos = new Vec3(arena.centerX + offsetX, arena.floorY, arena.centerZ);
		if (!EntityPlayerMPFake.createFake(name, server, pos, 90.0, 0.0, Level.OVERWORLD, GameType.SURVIVAL, false)) {
			throw new IllegalStateException("Carpet refused to spawn " + name);
		}
	}

	private void shutdown(String error) {
		active = null;
		KnockbackLag.off();
		truth = error == null ? Truth.INACTIVE : Truth.failed(error);
		try {
			if (savedTickRate > 0f) {
				server.tickRateManager().setTickRate(savedTickRate);
				savedTickRate = -1f;
			}
			if (bot instanceof EntityPlayerMPFake fake && !fake.isRemoved()) {
				fake.kill(Component.literal("SwordAI stopped"));
			}
			bot = null;
			standing = arena;
			ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
			if (agent != null && savedInventory != null) {
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
			GameRules rules = arena.level.getGameRules();
			for (Map.Entry<GameRule<Boolean>, Boolean> e : savedRules.entrySet()) {
				rules.set(e.getKey(), e.getValue(), server);
			}
			savedRules.clear();
		} catch (RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not fully restore world", e);
		}
	}

	public static void reset(EpisodePlan plan) {
		ArenaServer s = active;
		if (s == null || s.bot == null) {
			return;
		}
		ServerPlayer agent = s.server.getPlayerList().getPlayer(s.agentId);
		if (agent == null) {
			return;
		}
		if (plan.seed != 0L) {
			s.rng.setSeed(plan.seed);
		}
		s.activePilot = plan.pilot;
		if (s.activePilot != null) {
			s.activePilot.reset();
			s.activePilot.setLag(plan.botLag);
		} else {
			s.sparBot.start(plan.sparStyle, plan.seed != 0L ? plan.seed : s.rng.nextLong(), plan.botLag);
		}
		KnockbackLag.set(s.bot, plan.botPing, 1f);
		KnockbackLag.setAgent(agent, plan.agentKnockLag);
		s.setIdleNow(false);

		double span = s.arena.radius * 0.35;
		double ax = s.arena.centerX + (s.rng.nextDouble() * 2 - 1) * span;
		double az = s.arena.centerZ + (s.rng.nextDouble() * 2 - 1) * span;
		float yaw = Float.isNaN(plan.startYaw) ? ViewGeometry.wrapDegrees(agent.getYRot()) : plan.startYaw;
		if (Float.isNaN(plan.startYaw) && plan.seedYaw && plan.seed != 0L) {
			yaw = new Random(plan.seed ^ 0x5EED_9A3DL).nextFloat() * 360f - 180f;
		}
		plan.usedYaw = yaw;
		agent.teleportTo(s.arena.level, ax, s.arena.floorY, az, Set.of(), yaw, 0f, true);
		heal(agent);
		heal(s.bot);
		s.equip(agent);
		s.equip(s.bot);
		s.placeBot(ax, az, yaw, plan.minDistance, plan.maxDistance, plan.maxBearingDeg);

		if (plan.scene != null) {
			double[] scene = placed(plan.scene, s.arena, plan.seed == 0L);
			SceneRecorder.apply(s.arena.level, agent, scene, 0);
			SceneRecorder.apply(s.arena.level, s.bot, scene, SceneRecorder.BODY);
			s.pendingScene = scene;
			s.pendingPlan = plan;
			s.setIdleNow(true);
			s.botLastPos = null;
		} else {
			s.pendingScene = null;
			s.pendingPlan = null;
		}
		CombatLog.clear();
		s.episodeId++;
		s.publish();
	}

	/** Re-applies a saved scene once the client settles so both fighters start at exact velocities. */
	public static void putBackScene() {
		ArenaServer s = active;
		if (s == null || s.bot == null || s.pendingScene == null) {
			scenesPutBack++;
			return;
		}
		ServerPlayer agent = s.server.getPlayerList().getPlayer(s.agentId);
		if (agent == null) {
			return;
		}
		double[] scene = s.pendingScene;
		EpisodePlan plan = s.pendingPlan;
		s.pendingScene = null;
		s.pendingPlan = null;
		SceneRecorder.apply(s.arena.level, agent, scene, 0);
		SceneRecorder.apply(s.arena.level, s.bot, scene, SceneRecorder.BODY);
		if (s.activePilot != null) {
			s.activePilot.reset();
		} else if (plan != null) {
			s.sparBot.start(plan.sparStyle, plan.seed != 0L ? plan.seed : s.rng.nextLong(), plan.botLag);
		}
		agentTickerSetAt = s.server.getTickCount();
		s.botLastPos = null;
		s.setIdleNow(false);
		scenesPutBack++;
	}

	public static void setSpeed(float speed) {
		ArenaServer s = active;
		if (s == null) {
			return;
		}
		ServerTickRateManager tm = s.server.tickRateManager();
		if (s.savedTickRate < 0f) {
			s.savedTickRate = tm.tickrate();
		}
		tm.setTickRate(Math.max(1f, speed) * NORMAL_TICK_RATE);
	}

	public static void setIdle(boolean idle) {
		if (active != null) {
			active.setIdleNow(idle);
		}
	}

	private void setIdleNow(boolean value) {
		idle = value;
		if (value && bot != null) {
			if (activePilot != null) {
				activePilot.idle(bot);
			} else {
				sparBot.idle(bot);
			}
		}
	}

	private void placeBot(double ax, double az, float agentYaw, float minD, float maxD, float maxBear) {
		double x = ax, z = az;
		boolean found = false;
		for (int i = 0; i < PLACEMENT_ATTEMPTS && !found; i++) {
			double d = minD + rng.nextDouble() * Math.max(0, maxD - minD);
			double bearing = rng.nextDouble() * maxBear * (rng.nextBoolean() ? 1 : -1);
			double rad = Math.toRadians(agentYaw + bearing);
			x = ax - Math.sin(rad) * d;
			z = az + Math.cos(rad) * d;
			found = arena.inside(x, z, 1.5);
		}
		if (!found) {
			x = arena.clampX(x);
			z = arena.clampZ(z);
		}
		float yaw = ViewGeometry.yawTo(ax - x, az - z);
		bot.teleportTo(arena.level, x, arena.floorY, z, Set.of(), yaw, 0f, true);
		botLastPos = null;
		bot.setDeltaMovement(Vec3.ZERO);
		bot.setYHeadRot(yaw);
		hopId++;
	}

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
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (agent == null) {
			shutdown(null);
			return;
		}
		if (bot == null || bot.isRemoved()) {
			ServerPlayer cand = server.getPlayerList().getPlayerByName(botName);
			if (cand instanceof EntityPlayerMPFake) {
				cand.setGameMode(GameType.SURVIVAL);
				cand.getInventory().clearContent();
				equip(cand);
				heal(cand);
				bot = cand;
				ticksWithoutBot = 0;
				placeBot(arena.centerX, arena.centerZ, 0f, 4f, 4f, 0f);
			} else {
				if (++ticksWithoutBot > BOT_TIMEOUT_TICKS) {
					shutdown("sparring bot never appeared");
				} else if (ticksWithoutBot % BOT_RETRY_TICKS == 0) {
					spawnFake(botName, 4);
				}
				return;
			}
		}
		Vec3 botPos = bot.position();
		if (botLastPos == null) {
			botFall.reset();
		}
		bot.setKnownMovement(botLastPos == null ? Vec3.ZERO : botPos.subtract(botLastPos));
		botLastPos = botPos;
		botFall.tick(bot);

		upkeep(agent);
		upkeep(bot);
		if (!idle) {
			if (activePilot != null) {
				activePilot.tick(bot, agent, arena.floorY);
			} else {
				sparBot.tick(bot, agent, arena.floorY);
			}
		}
		publish();
	}

	private void upkeep(ServerPlayer p) {
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		if (p.getY() < arena.floorY - 3) {
			p.teleportTo(arena.level, arena.centerX, arena.floorY, arena.centerZ, Set.of(), p.getYRot(), 0f, true);
		}
	}

	private void equip(ServerPlayer p) {
		Identifier swordId = Identifier.tryParse(cfg.kitSword == null ? "" : cfg.kitSword);
		Item sword = swordId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(swordId);
		if (sword == Items.AIR) {
			sword = Items.DIAMOND_SWORD;
		}
		ItemStack wantedSword = kitItem(sword);
		if (!ItemStack.matches(p.getInventory().getItem(0), wantedSword)) {
			p.getInventory().setItem(0, wantedSword);
		}
		p.getInventory().setSelectedSlot(0);
		Item[] armor = switch (cfg.kitArmor == null ? "iron" : cfg.kitArmor) {
			case "diamond" -> new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			case "netherite" -> new Item[] {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
			case "iron" -> new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
			default -> null;
		};
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < slots.length; i++) {
			ItemStack wanted = armor == null ? ItemStack.EMPTY : kitItem(armor[i]);
			if (!ItemStack.matches(p.getItemBySlot(slots[i]), wanted)) {
				p.setItemSlot(slots[i], wanted);
			}
		}
	}

	private static ItemStack kitItem(Item item) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		return s;
	}

	private static void heal(ServerPlayer p) {
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.invulnerableTime = 0;
		p.hurtTime = 0;
		p.resetFallDistance();
	}

	private void publish() {
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (bot != null && agent != null) {
			scenes.note(server.getTickCount(), agent, bot);
		}
		truth = new Truth(true, bot != null, bot == null ? null : bot.getUUID(), episodeId, hopId,
				arena.centerX, arena.floorY, arena.centerZ, arena.radius,
				bot == null ? 0f : bot.getHealth(), bot == null ? 0f : bot.getAttackStrengthScale(0f),
				bot != null && bot.isSprinting(), bot != null && bot.onGround(),
				bot != null && !bot.onGround() && bot.getY() < bot.yo,
				agent != null && agent.isSprinting(), server.getTickCount(), -1, 0, null);
	}

	public static double[] sceneAt(long tick) {
		ArenaServer s = active;
		double[] scene = s == null ? null : s.scenes.at(tick);
		if (scene != null) {
			for (int o = 0; o <= SceneRecorder.BODY; o += SceneRecorder.BODY) {
				scene[o] -= s.arena.centerX;
				scene[o + 1] -= s.arena.floorY;
				scene[o + 2] -= s.arena.centerZ;
			}
		}
		return scene;
	}

	private static double[] placed(double[] scene, Arena arena, boolean randomTurn) {
		double[] s = scene.clone();
		int b = SceneRecorder.BODY;
		if (randomTurn) {
			double deg = Math.random() * 360.0, a = Math.toRadians(deg), c = Math.cos(a), sn = Math.sin(a);
			for (int o = 0; o <= b; o += b) {
				double x = s[o], z = s[o + 2], vx = s[o + 3], vz = s[o + 5];
				s[o] = x * c - z * sn;
				s[o + 2] = x * sn + z * c;
				s[o + 3] = vx * c - vz * sn;
				s[o + 5] = vx * sn + vz * c;
				s[o + 6] = s[o + 6] + deg;
			}
		}
		for (int o = 0; o <= b; o += b) {
			s[o] = arena.clampX(s[o] + arena.centerX);
			s[o + 1] = s[o + 1] + arena.floorY;
			s[o + 2] = arena.clampZ(s[o + 2] + arena.centerZ);
		}
		return s;
	}

	public static void onAttack(Player attacker, float charge, boolean sprinting, boolean crit, boolean rising, boolean sweep) {
		ArenaServer s = active;
		if (s == null) {
			return;
		}
		if (attacker.getUUID().equals(s.agentId)) {
			CombatLog.agentSwung(charge, sprinting, crit, rising, sweep);
		} else if (attacker == s.bot) {
			CombatLog.botSwung(crit, charge > 0.9f && sprinting);
		}
	}

	public static void onDamage(LivingEntity victim, DamageSource source, float dealt) {
		ArenaServer s = active;
		if (s == null || s.bot == null || source.getEntity() == null) {
			return;
		}
		Entity attacker = source.getEntity();
		boolean botWasHit = victim == s.bot && attacker.getUUID().equals(s.agentId);
		boolean botDidHit = victim.getUUID().equals(s.agentId) && attacker == s.bot;
		if (botWasHit) {
			CombatLog.agentDamagedBot(dealt);
			if (s.activePilot != null) s.activePilot.hitTaken();
			else s.sparBot.hitTaken();
		} else if (botDidHit) {
			CombatLog.botDamagedAgent(dealt);
			if (s.activePilot != null) s.activePilot.hitDealt();
			else s.sparBot.hitDealt();
		}
	}

	public static boolean allowDeath(LivingEntity entity) {
		ArenaServer s = active;
		if (s == null) {
			return true;
		}
		boolean isBot = entity == s.bot;
		boolean isAgent = entity.getUUID().equals(s.agentId);
		if (!isBot && !isAgent) {
			return true;
		}
		entity.setHealth(entity.getMaxHealth());
		CombatLog.knockout(isBot);
		if (s.activePilot != null) {
			ServerPlayer agent = s.server.getPlayerList().getPlayer(s.agentId);
			if (agent != null && s.bot != null) {
				s.activePilot.roundOver(s.bot, agent, s.arena.floorY);
			}
		}
		return false;
	}
}
