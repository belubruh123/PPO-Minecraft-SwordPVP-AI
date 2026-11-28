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
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.gamerules.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Integrated-server arena hosting multi-kit league training, paired checks, scene replays, and MoveSearch. */
public final class ArenaServer {
	public static final String BOT_NAME = "SwordBot";
	private static final ItemStack SHIELD_PROBE = new ItemStack(Items.SHIELD);
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
	private final Map<GameRule<Boolean>, Boolean> savedRules = new LinkedHashMap<>();

	private ServerPlayer bot;
	private Vec3 botLastPos, savedPos;
	private int ticksWithoutBot, episodeId, hopId;
	private SparPilot activePilot;
	private EpisodePlan.KitMode activeKit = EpisodePlan.KitMode.SWORD_ONLY;
	private double[] pendingScene;
	private EpisodePlan pendingPlan;
	private boolean idle = true;
	private float savedTickRate = -1f, savedYaw, savedPitch;
	private GameType savedMode;
	private ItemStack[] savedInventory;

	private ArenaServer(MinecraftServer server, UUID agentId, Config cfg, Arena arena, String owner) {
		this.server = server; this.agentId = agentId; this.cfg = cfg; this.arena = arena;
		String n = playerName(cfg.botName, BOT_NAME);
		this.botName = n.equalsIgnoreCase(owner) ? BOT_NAME : n;
	}

	public static String playerName(String name, String fallback) {
		return name != null && name.matches("[A-Za-z0-9_]{3,16}") ? name : fallback;
	}
	public static Truth truth() { return truth; }
	public static void setInverseDynamics(InverseDynamics idm) { if (active != null) active.sparBot.setInverseDynamics(idm); }

	public static void start(MinecraftServer server, UUID agentId, Config cfg) {
		if (active != null) active.shutdown(null);
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (agent == null) { truth = Truth.failed("player not found"); return; }
		ServerLevel level = agent.level();
		if (level.dimension() != Level.OVERWORLD) { truth = Truth.failed("arena needs Overworld"); return; }
		int floorY = Math.min(200, level.getMaxY() - 40);
		Arena arena = arenaFor(server, level, agent, floorY, Math.max(Config.MIN_ARENA_RADIUS, cfg.arenaRadius));
		ArenaServer s = new ArenaServer(server, agentId, cfg, arena, agent.getScoreboardName());
		try {
			s.setUp(agent); active = s; CombatLog.clear(); s.publish();
		} catch (RuntimeException e) {
			SwordAi.LOGGER.error("arena setup failed", e);
			s.shutdown("arena setup failed: " + e);
		}
	}

	private static Arena arenaFor(MinecraftServer server, ServerLevel level, ServerPlayer owner, int floorY, int radius) {
		Arena old = standing; standing = null;
		if (old != null && old.level.getServer() == server) {
			if (old.level == level && old.radius == radius && old.floorY == floorY && old.near(owner.getX(), owner.getZ())) return old;
			try { old.remove(); } catch (RuntimeException ignored) {}
		}
		return new Arena(level, (int) Math.floor(owner.getX()), floorY, (int) Math.floor(owner.getZ()), radius);
	}

	public static void stop(MinecraftServer server) { if (active != null) active.shutdown(null); }
	public static void onServerStopping(MinecraftServer server) {
		stop(server); Arena left = standing; standing = null;
		if (left != null && left.level.getServer() == server) { try { left.remove(); } catch (RuntimeException ignored) {} }
	}
	public static void onPlayerLeft(ServerPlayer player, MinecraftServer server) {
		if (active != null && player.getUUID().equals(active.agentId)) active.shutdown(null);
	}

	private void setUp(ServerPlayer agent) {
		ServerLevel level = arena.level;
		Inventory inv = agent.getInventory();
		savedPos = agent.position(); savedYaw = agent.getYRot(); savedPitch = agent.getXRot();
		savedMode = agent.gameMode.getGameModeForPlayer();
		savedInventory = new ItemStack[inv.getContainerSize()];
		for (int i = 0; i < savedInventory.length; i++) savedInventory[i] = inv.getItem(i).copy();
		arena.build();
		GameRules r = level.getGameRules();
		setRule(r, GameRules.ADVANCE_TIME, false); setRule(r, GameRules.ADVANCE_WEATHER, false);
		setRule(r, GameRules.SPAWN_MOBS, false); setRule(r, GameRules.NATURAL_HEALTH_REGENERATION, false);
		setRule(r, GameRules.KEEP_INVENTORY, true); setRule(r, GameRules.IMMEDIATE_RESPAWN, true);
		setRule(r, GameRules.FALL_DAMAGE, false); setRule(r, GameRules.PVP, true); setRule(r, GameRules.SHOW_DEATH_MESSAGES, false);
		level.setDayTime(6000L); level.setWeatherParameters(6_000_000, 0, false, false);
		agent.setGameMode(GameType.SURVIVAL); agent.removeAllEffects(); inv.clearContent();
		equip(agent, EpisodePlan.KitMode.SWORD_ONLY, false, rng);
		agent.teleportTo(level, arena.centerX, arena.floorY, arena.centerZ, Set.of(), 0f, 0f, true); heal(agent);
		spawnFake(botName, 4);
	}

	private void setRule(GameRules r, GameRule<Boolean> rule, boolean v) { savedRules.put(rule, r.get(rule)); r.set(rule, v, server); }

	private void spawnFake(String name, int dx) {
		ServerPlayer ex = server.getPlayerList().getPlayerByName(name);
		if (ex instanceof EntityPlayerMPFake || EntityPlayerMPFake.isSpawningPlayer(name)) return;
		if (ex != null) throw new IllegalStateException("real player named " + name + " is online");
		if (!EntityPlayerMPFake.createFake(name, server, new Vec3(arena.centerX + dx, arena.floorY, arena.centerZ), 90.0, 0.0, Level.OVERWORLD, GameType.SURVIVAL, false)) {
			throw new IllegalStateException("Carpet refused to spawn " + name);
		}
	}

	private void shutdown(String error) {
		active = null; KnockbackLag.off(); truth = error == null ? Truth.INACTIVE : Truth.failed(error);
		try {
			if (savedTickRate > 0f) { server.tickRateManager().setTickRate(savedTickRate); savedTickRate = -1f; }
			if (bot instanceof EntityPlayerMPFake fake && !fake.isRemoved()) fake.kill(Component.literal("SwordAI stopped"));
			bot = null; standing = arena;
			ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
			if (agent != null && savedInventory != null) {
				Inventory inv = agent.getInventory(); inv.clearContent();
				for (int i = 0; i < savedInventory.length && i < inv.getContainerSize(); i++) inv.setItem(i, savedInventory[i]);
				savedInventory = null;
				if (savedMode != null) agent.setGameMode(savedMode);
				heal(agent);
				if (savedPos != null) agent.teleportTo(agent.level(), savedPos.x, savedPos.y, savedPos.z, Set.of(), savedYaw, savedPitch, true);
			}
			GameRules r = arena.level.getGameRules();
			for (var e : savedRules.entrySet()) r.set(e.getKey(), e.getValue(), server);
			savedRules.clear();
		} catch (RuntimeException e) { SwordAi.LOGGER.error("could not restore world", e); }
	}

	public static void reset(EpisodePlan plan) {
		ArenaServer s = active; if (s == null || s.bot == null) return;
		ServerPlayer agent = s.server.getPlayerList().getPlayer(s.agentId); if (agent == null) return;
		if (plan.seed != 0L) s.rng.setSeed(plan.seed);
		s.activePilot = plan.pilot;
		s.activeKit = plan.kitMode != null ? plan.kitMode : EpisodePlan.KitMode.SWORD_ONLY;
		s.arena.setTerraced(plan.terraced && plan.scene == null);
		if (s.activePilot != null) { s.activePilot.reset(); s.activePilot.setLag(plan.botLag); }
		else {
			s.sparBot.start(plan.sparStyle, plan.seed != 0L ? plan.seed : s.rng.nextLong(), plan.botLag);
			if (plan.forceBotShieldTicks > 0) s.sparBot.forceShield(plan.forceBotShieldTicks);
		}
		KnockbackLag.set(s.bot, plan.botPing, 1f);
		KnockbackLag.setAgent(agent, plan.agentKnockLag);
		s.setIdleNow(false);

		double span = s.arena.radius * 0.35;
		double ax = s.arena.centerX + (s.rng.nextDouble() * 2 - 1) * span;
		double az = s.arena.centerZ + (s.rng.nextDouble() * 2 - 1) * span;
		double ay = s.arena.surfaceY(ax, az);
		float yaw = Float.isNaN(plan.startYaw) ? ViewGeometry.wrapDegrees(agent.getYRot()) : plan.startYaw;
		if (Float.isNaN(plan.startYaw) && plan.seedYaw && plan.seed != 0L) yaw = new Random(plan.seed ^ 0x5EED_9A3DL).nextFloat() * 360f - 180f;
		plan.usedYaw = yaw;
		agent.teleportTo(s.arena.level, ax, ay, az, Set.of(), yaw, 0f, true);
		heal(agent); heal(s.bot);
		s.equip(agent, s.activeKit, plan.randomizeSlots, s.rng);
		s.equip(s.bot, s.activeKit, plan.randomizeSlots, s.rng);
		if (plan.forceBotShieldTicks > 0 && !InventoryObs.isShield(s.bot.getOffhandItem())) {
			int sh = InventoryObs.findHotbarSlot(s.bot, dev.swordai.core.rl.ObsSpec.SF_SHIELD);
			if (sh >= 0) { s.bot.setItemInHand(InteractionHand.OFF_HAND, s.bot.getInventory().getItem(sh).copy()); s.bot.getInventory().setItem(sh, ItemStack.EMPTY); }
		}
		s.placeBot(ax, az, yaw, plan.minDistance, plan.maxDistance, plan.maxBearingDeg);

		if (plan.scene != null) {
			double[] sc = placed(plan.scene, s.arena, plan.seed == 0L);
			SceneRecorder.apply(s.arena.level, agent, sc, 0);
			SceneRecorder.apply(s.arena.level, s.bot, sc, SceneRecorder.BODY);
			if (plan.forceBotShieldTicks > 0 && InventoryObs.isShield(s.bot.getOffhandItem())) s.bot.startUsingItem(InteractionHand.OFF_HAND);
			s.pendingScene = sc; s.pendingPlan = plan; s.setIdleNow(true); s.botLastPos = null;
		} else { s.pendingScene = null; s.pendingPlan = null; }
		CombatLog.clear(); s.episodeId++; s.publish();
	}

	public static void putBackScene() {
		ArenaServer s = active;
		if (s == null || s.bot == null || s.pendingScene == null) { scenesPutBack++; return; }
		ServerPlayer agent = s.server.getPlayerList().getPlayer(s.agentId); if (agent == null) return;
		double[] sc = s.pendingScene; EpisodePlan plan = s.pendingPlan;
		s.pendingScene = null; s.pendingPlan = null;
		SceneRecorder.apply(s.arena.level, agent, sc, 0);
		SceneRecorder.apply(s.arena.level, s.bot, sc, SceneRecorder.BODY);
		if (s.activePilot != null) s.activePilot.reset();
		else if (plan != null) {
			s.sparBot.start(plan.sparStyle, plan.seed != 0L ? plan.seed : s.rng.nextLong(), plan.botLag);
			if (plan.forceBotShieldTicks > 0) {
				s.sparBot.forceShield(plan.forceBotShieldTicks);
				if (InventoryObs.isShield(s.bot.getOffhandItem())) s.bot.startUsingItem(InteractionHand.OFF_HAND);
			}
		}
		agentTickerSetAt = s.server.getTickCount(); s.botLastPos = null; s.setIdleNow(false); scenesPutBack++;
	}

	public static void setSpeed(float speed) {
		ArenaServer s = active; if (s == null) return;
		ServerTickRateManager tm = s.server.tickRateManager();
		if (s.savedTickRate < 0f) s.savedTickRate = tm.tickrate();
		tm.setTickRate(Math.max(1f, speed) * 20f);
	}

	public static void setIdle(boolean idle) { if (active != null) active.setIdleNow(idle); }

	private void setIdleNow(boolean v) {
		idle = v;
		if (v && bot != null) { if (activePilot != null) activePilot.idle(bot); else sparBot.idle(bot); }
	}

	private void placeBot(double ax, double az, float agentYaw, float minD, float maxD, float maxBear) {
		double x = ax, z = az; boolean found = false;
		for (int i = 0; i < 40 && !found; i++) {
			double d = minD + rng.nextDouble() * Math.max(0, maxD - minD);
			double rad = Math.toRadians(agentYaw + rng.nextDouble() * maxBear * (rng.nextBoolean() ? 1 : -1));
			x = ax - Math.sin(rad) * d; z = az + Math.cos(rad) * d;
			found = arena.inside(x, z, 1.5);
		}
		if (!found) { x = arena.clampX(x); z = arena.clampZ(z); }
		double by = arena.surfaceY(x, z);
		float yaw = ViewGeometry.yawTo(ax - x, az - z);
		bot.teleportTo(arena.level, x, by, z, Set.of(), yaw, 0f, true);
		botLastPos = null; bot.setDeltaMovement(Vec3.ZERO); bot.setYHeadRot(yaw); hopId++;
	}

	public static void onServerTick(MinecraftServer server) {
		ArenaServer s = active;
		if (s != null && s.server == server) {
			try { s.tick(); } catch (RuntimeException e) { SwordAi.LOGGER.error("arena tick failed", e); s.shutdown("arena error: " + e); }
		}
	}

	private void tick() {
		KnockbackLag.tick();
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (agent == null) { shutdown(null); return; }
		if (bot == null || bot.isRemoved()) {
			ServerPlayer c = server.getPlayerList().getPlayerByName(botName);
			if (c instanceof EntityPlayerMPFake) {
				c.setGameMode(GameType.SURVIVAL); c.getInventory().clearContent();
				equip(c, activeKit, false, rng); heal(c);
				bot = c; ticksWithoutBot = 0; placeBot(arena.centerX, arena.centerZ, 0f, 4f, 4f, 0f);
			} else {
				if (++ticksWithoutBot > 400) shutdown("sparring bot never appeared");
				else if (ticksWithoutBot % 100 == 0) spawnFake(botName, 4);
				return;
			}
		}
		Vec3 bp = bot.position();
		if (botLastPos == null) botFall.reset();
		bot.setKnownMovement(botLastPos == null ? Vec3.ZERO : bp.subtract(botLastPos));
		botLastPos = bp; botFall.tick(bot);
		upkeep(agent); upkeep(bot);
		if (!idle) { if (activePilot != null) activePilot.tick(bot, agent, arena.floorY); else sparBot.tick(bot, agent, arena.floorY); }
		publish();
	}

	private void upkeep(ServerPlayer p) {
		p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(5f);
		if (p.getY() < arena.floorY - 4) p.teleportTo(arena.level, arena.centerX, arena.surfaceY(arena.centerX, arena.centerZ), arena.centerZ, Set.of(), p.getYRot(), 0f, true);
	}

	/**
	 * Equips either pure Sword-only or SMP Phase 1 (Sword + Axe + Shield + 3 Golden Apples),
	 * shuffling hotbar slots 0..8 when {@code randomSlots} is true so the AlphaStar Pointer Head
	 * learns true order-invariant item selection across any layout.
	 */
	private void equip(ServerPlayer p, EpisodePlan.KitMode kit, boolean randomSlots, Random r) {
		Inventory inv = p.getInventory();
		inv.clearContent();
		p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

		Identifier sid = Identifier.tryParse(cfg.kitSword == null ? "" : cfg.kitSword);
		Item sw = sid == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(sid);
		ItemStack sword = kitItem(sw == Items.AIR ? Items.DIAMOND_SWORD : sw);

		int[] slots = {0, 1, 2, 3, 4, 5, 6, 7, 8};
		if (randomSlots) {
			for (int i = slots.length - 1; i > 0; i--) {
				int j = r.nextInt(i + 1), t = slots[i]; slots[i] = slots[j]; slots[j] = t;
			}
		}
		int swordSlot = slots[0];
		inv.setItem(swordSlot, sword);

		if (kit == EpisodePlan.KitMode.SMP_PHASE1) {
			inv.setItem(slots[1], kitItem(Items.DIAMOND_AXE));
			inv.setItem(slots[2], new ItemStack(Items.GOLDEN_APPLE, 3));
			ItemStack shield = kitItem(Items.SHIELD);
			if (!randomSlots || r.nextFloat() < 0.85f) {
				p.setItemInHand(InteractionHand.OFF_HAND, shield);
			} else {
				inv.setItem(slots[3], shield);
			}
			// Start on sword 60% of the time, or another slot 40% so pointer-switching gets immediate experience.
			inv.setSelectedSlot((!randomSlots || r.nextFloat() < 0.60f) ? swordSlot : slots[1 + r.nextInt(3)]);
		} else {
			inv.setSelectedSlot(swordSlot);
		}

		Item[] armor = switch (cfg.kitArmor == null ? "diamond" : cfg.kitArmor) {
			case "diamond" -> new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			case "netherite" -> new Item[] {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
			case "iron" -> new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
			default -> null;
		};
		EquipmentSlot[] eq = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < eq.length; i++) {
			ItemStack want = armor == null ? ItemStack.EMPTY : kitItem(armor[i]);
			if (!ItemStack.matches(p.getItemBySlot(eq[i]), want)) p.setItemSlot(eq[i], want);
		}
	}

	private static ItemStack kitItem(Item item) { ItemStack s = new ItemStack(item); s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE); return s; }

	private static void heal(ServerPlayer p) {
		p.stopUsingItem();
		p.removeAllEffects();
		p.setAbsorptionAmount(0f);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.invulnerableTime = 0;
		p.hurtTime = 0;
		p.resetFallDistance();
		p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(SHIELD_PROBE));
	}

	private void publish() {
		ServerPlayer agent = server.getPlayerList().getPlayer(agentId);
		if (bot != null && agent != null) scenes.note(server.getTickCount(), agent, bot);
		boolean botShUp = bot != null && bot.isBlocking();
		float botShCd = bot != null ? InventoryObs.shieldCooldown(bot) : 0f;
		boolean agShUp = agent != null && agent.isBlocking();
		float agShCd = agent != null ? InventoryObs.shieldCooldown(agent) : 0f;
		boolean hasSh = activeKit == EpisodePlan.KitMode.SMP_PHASE1;
		float botEffHp = bot == null ? 0f : (bot.getHealth() + bot.getAbsorptionAmount());
		truth = new Truth(true, bot != null, bot == null ? null : bot.getUUID(), episodeId, hopId,
				arena.centerX, arena.floorY, arena.centerZ, arena.radius,
				botEffHp, bot == null ? 0f : bot.getAttackStrengthScale(0f),
				bot != null && bot.isSprinting(), bot != null && bot.onGround(),
				bot != null && !bot.onGround() && bot.getY() < bot.yo, agent != null && agent.isSprinting(),
				botShUp, botShCd, agShUp, agShCd, hasSh, hasSh,
				server.getTickCount(), -1, 0, null);
	}

	public static double[] sceneAt(long tick) {
		ArenaServer s = active; double[] sc = s == null ? null : s.scenes.at(tick);
		if (sc != null) for (int o = 0; o <= SceneRecorder.BODY; o += SceneRecorder.BODY) { sc[o] -= s.arena.centerX; sc[o + 1] -= s.arena.floorY; sc[o + 2] -= s.arena.centerZ; }
		return sc;
	}

	private static double[] placed(double[] scene, Arena arena, boolean randomTurn) {
		double[] s = scene.clone(); int b = SceneRecorder.BODY;
		if (randomTurn) {
			double deg = Math.random() * 360.0, a = Math.toRadians(deg), c = Math.cos(a), sn = Math.sin(a);
			for (int o = 0; o <= b; o += b) {
				double x = s[o], z = s[o + 2], vx = s[o + 3], vz = s[o + 5];
				s[o] = x * c - z * sn; s[o + 2] = x * sn + z * c; s[o + 3] = vx * c - vz * sn; s[o + 5] = vx * sn + vz * c; s[o + 6] += deg;
			}
		}
		for (int o = 0; o <= b; o += b) { s[o] = arena.clampX(s[o] + arena.centerX); s[o + 1] += arena.floorY; s[o + 2] = arena.clampZ(s[o + 2] + arena.centerZ); }
		return s;
	}

	public static void onAttack(Player a, float chg, boolean spr, boolean crit, boolean rise, boolean swp) {
		ArenaServer s = active; if (s == null) return;
		if (a.getUUID().equals(s.agentId)) CombatLog.agentSwung(chg, spr, crit, rise, swp);
		else if (a == s.bot) CombatLog.botSwung(crit, chg > 0.9f && spr);
	}

	public static void onDamage(LivingEntity victim, DamageSource src, float dealt) {
		ArenaServer s = active; if (s == null || s.bot == null || src.getEntity() == null) return;
		Entity a = src.getEntity();
		if (victim == s.bot && a.getUUID().equals(s.agentId)) {
			CombatLog.agentDamagedBot(dealt);
			if (s.activePilot != null) s.activePilot.hitTaken(); else s.sparBot.hitTaken();
		} else if (victim.getUUID().equals(s.agentId) && a == s.bot) {
			CombatLog.botDamagedAgent(dealt);
			if (s.activePilot != null) s.activePilot.hitDealt(); else s.sparBot.hitDealt();
		}
	}

	public static boolean allowDeath(LivingEntity e) {
		ArenaServer s = active; if (s == null) return true;
		boolean isBot = e == s.bot, isAgent = e.getUUID().equals(s.agentId);
		if (!isBot && !isAgent) return true;
		e.setHealth(e.getMaxHealth()); CombatLog.knockout(isBot);
		if (s.activePilot != null && s.bot != null) {
			ServerPlayer ag = s.server.getPlayerList().getPlayer(s.agentId);
			if (ag != null) s.activePilot.roundOver(s.bot, ag, s.arena.floorY);
		}
		return false;
	}
}
