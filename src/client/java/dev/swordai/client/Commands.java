package dev.swordai.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.server.ArenaServer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/** Client-side {@code /swordai} commands. */
final class Commands {
	private Commands() {}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("swordai")
				.executes(c -> say(c.getSource(), "/swordai school [check] | fight [player] | stop | status | speed | fov | skin | export | import"))
				.then(literal("school")
						.executes(c -> school(c.getSource(), LeagueSession.Mode.TRAIN))
						.then(literal("check").executes(c -> school(c.getSource(), LeagueSession.Mode.CHECK))))
				.then(literal("fight")
						.executes(c -> fight(c.getSource(), null))
						.then(argument("player", StringArgumentType.word()).executes(c -> fight(c.getSource(), StringArgumentType.getString(c, "player")))))
				.then(literal("speed").executes(c -> cfgSpeed(c.getSource(), 0f))
						.then(argument("times", FloatArgumentType.floatArg(1f, 8f)).executes(c -> cfgSpeed(c.getSource(), FloatArgumentType.getFloat(c, "times")))))
				.then(literal("fov").executes(c -> cfgFov(c.getSource(), 0))
						.then(argument("degrees", IntegerArgumentType.integer(30, 110)).executes(c -> cfgFov(c.getSource(), IntegerArgumentType.getInteger(c, "degrees")))))
				.then(literal("skin")
						.then(literal("on").executes(c -> cfgSkin(c.getSource(), true)))
						.then(literal("off").executes(c -> cfgSkin(c.getSource(), false))))
				.then(literal("arena").then(argument("radius", IntegerArgumentType.integer(Config.MIN_ARENA_RADIUS, 32))
						.executes(c -> cfgArena(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
				.then(literal("name").then(argument("nick", StringArgumentType.word())
						.executes(c -> cfgName(c.getSource(), StringArgumentType.getString(c, "nick")))))
				.then(literal("export").executes(c -> exp(c.getSource(), null))
						.then(argument("name", StringArgumentType.word()).executes(c -> exp(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("import").then(argument("name", StringArgumentType.word()).executes(c -> imp(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("menu").executes(c -> { c.getSource().getClient().execute(() -> c.getSource().getClient().setScreen(new SwordAiScreen())); return 1; }))
				.then(literal("stop").executes(c -> { if (!SwordAiClient.running()) return say(c.getSource(), "Nothing running."); SwordAiClient.stop("user stopped"); return 1; }))
				.then(literal("status").executes(c -> status(c.getSource()))));
	}

	private static int school(FabricClientCommandSource s, LeagueSession.Mode mode) {
		Minecraft mc = s.getClient();
		if (mc.getSingleplayerServer() == null || mc.player == null) return err(s, "Open a singleplayer world first.");
		if (SwordAiClient.running()) return err(s, "Already running. Use /swordai stop first.");
		try { return say(s, SwordAiClient.startSchool(mc, mode).startLine()); }
		catch (IOException | RuntimeException e) { SwordAi.LOGGER.error("start failed", e); SwordFiles.writeError("start: " + e, e); return err(s, "Could not start: " + e); }
	}

	private static int fight(FabricClientCommandSource s, String opp) {
		Minecraft mc = s.getClient();
		if (mc.player == null || mc.level == null) return err(s, "Join a world first.");
		if (SwordAiClient.running()) return err(s, "Already running. Use /swordai stop first.");
		try { return say(s, "Fighting " + (opp == null ? "nearest" : opp) + ". " + SwordAiClient.startFight(mc, opp).skillLine()); }
		catch (IOException | RuntimeException e) { SwordAi.LOGGER.error("fight failed", e); return err(s, "Could not start: " + e.getMessage()); }
	}

	private static int cfgSpeed(FabricClientCommandSource s, float x) {
		try { Config c = SwordFiles.loadConfig(); if (x == 0f) return say(s, String.format(Locale.ROOT, "Speed x%.1f", c.trainSpeed)); c.trainSpeed = x; SwordFiles.saveConfig(c); return say(s, "Speed x" + x); }
		catch (IOException e) { return err(s, "Failed: " + e); }
	}
	private static int cfgFov(FabricClientCommandSource s, int d) {
		try { Config c = SwordFiles.loadConfig(); if (d == 0) return say(s, "FOV " + c.visionFov + "/" + c.fov); c.visionFov = Math.min(d, c.fov); SwordFiles.saveConfig(c); return say(s, "Vision FOV " + c.visionFov); }
		catch (IOException e) { return err(s, "Failed: " + e); }
	}
	private static int cfgSkin(FabricClientCommandSource s, boolean on) {
		try { Config c = SwordFiles.loadConfig(); c.showOpponentSkin = on; SwordFiles.saveConfig(c); return say(s, "Skin " + (on ? "on" : "off")); }
		catch (IOException e) { return err(s, "Failed: " + e); }
	}
	private static int cfgArena(FabricClientCommandSource s, int r) {
		try { Config c = SwordFiles.loadConfig(); c.arenaRadius = r; SwordFiles.saveConfig(c); return say(s, "Arena radius " + r); }
		catch (IOException e) { return err(s, "Failed: " + e); }
	}
	private static int cfgName(FabricClientCommandSource s, String n) {
		try { Config c = SwordFiles.loadConfig(); c.botName = ArenaServer.playerName(n, ArenaServer.BOT_NAME); SwordFiles.saveConfig(c); return say(s, "Bot name " + c.botName); }
		catch (IOException e) { return err(s, "Failed: " + e); }
	}
	private static int exp(FabricClientCommandSource s, String n) {
		try { return say(s, "Exported " + ModelPack.export(n).getFileName()); } catch (IOException e) { return err(s, e.getMessage()); }
	}
	private static int imp(FabricClientCommandSource s, String n) {
		try { ModelPack.importPack(n); return say(s, "Imported " + n); } catch (IOException e) { return err(s, e.getMessage()); }
	}
	private static int status(FabricClientCommandSource s) {
		AgentSession sess = SwordAiClient.session();
		if (sess == null || sess.stopped()) return say(s, "Idle (SwordAI " + SwordFiles.modVersion() + ")");
		for (String l : sess.statusLines()) say(s, l);
		return 1;
	}
	private static int say(FabricClientCommandSource s, String m) { s.sendFeedback(Component.literal("[SwordAI] " + m)); return 1; }
	private static int err(FabricClientCommandSource s, String m) { s.sendError(Component.literal(m)); return 0; }
}
