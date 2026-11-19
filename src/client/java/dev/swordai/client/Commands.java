package dev.swordai.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.server.ArenaServer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side {@code /swordai} commands. */
final class Commands {
	private Commands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("swordai")
				.executes(c -> help(c.getSource()))
				.then(literal("school")
						.executes(c -> school(c.getSource(), LeagueSession.Mode.TRAIN))
						.then(literal("check").executes(c -> school(c.getSource(), LeagueSession.Mode.CHECK))))
				.then(literal("fight")
						.executes(c -> fight(c.getSource(), null))
						.then(argument("player", StringArgumentType.word())
								.executes(c -> fight(c.getSource(), StringArgumentType.getString(c, "player")))))
				.then(literal("speed")
						.executes(c -> speed(c.getSource(), 0f))
						.then(argument("times", FloatArgumentType.floatArg(1f, 8f))
								.executes(c -> speed(c.getSource(), FloatArgumentType.getFloat(c, "times")))))
				.then(literal("fov")
						.executes(c -> fov(c.getSource(), 0))
						.then(argument("degrees", IntegerArgumentType.integer(30, 110))
								.executes(c -> fov(c.getSource(), IntegerArgumentType.getInteger(c, "degrees")))))
				.then(literal("skin")
						.then(literal("on").executes(c -> skin(c.getSource(), true)))
						.then(literal("off").executes(c -> skin(c.getSource(), false))))
				.then(literal("arena")
						.then(argument("radius", IntegerArgumentType.integer(Config.MIN_ARENA_RADIUS, 32))
								.executes(c -> arena(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
				.then(literal("name")
						.then(argument("nick", StringArgumentType.word())
								.executes(c -> name(c.getSource(), StringArgumentType.getString(c, "nick")))))
				.then(literal("export")
						.executes(c -> exportModel(c.getSource(), null))
						.then(argument("name", StringArgumentType.word())
								.executes(c -> exportModel(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("import")
						.then(argument("name", StringArgumentType.word())
								.executes(c -> importModel(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("menu").executes(c -> menu(c.getSource())))
				.then(literal("stop").executes(c -> stop(c.getSource())))
				.then(literal("status").executes(c -> status(c.getSource()))));
	}

	private static int help(FabricClientCommandSource s) {
		say(s, "/swordai school | /swordai school check | /swordai fight [player] | /swordai stop | /swordai status");
		say(s, "/swordai speed <1..8> | /swordai fov <30..110> | /swordai skin on|off | /swordai export|import [name]");
		return 1;
	}

	private static int school(FabricClientCommandSource s, LeagueSession.Mode mode) {
		Minecraft mc = s.getClient();
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			s.sendError(Component.literal("Open a singleplayer world first."));
			return 0;
		}
		if (SwordAiClient.running()) {
			s.sendError(Component.literal("Already running. Use /swordai stop first."));
			return 0;
		}
		try {
			LeagueSession sess = SwordAiClient.startSchool(mc, mode);
			say(s, sess.startLine());
			return 1;
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("could not start league session", e);
			SwordFiles.writeError("could not start: " + e, e);
			s.sendError(Component.literal("Could not start: " + e));
			return 0;
		}
	}

	private static int fight(FabricClientCommandSource s, String opp) {
		Minecraft mc = s.getClient();
		if (mc.player == null || mc.level == null) {
			s.sendError(Component.literal("Join a world first."));
			return 0;
		}
		if (SwordAiClient.running()) {
			s.sendError(Component.literal("Already running. Use /swordai stop first."));
			return 0;
		}
		try {
			FightSession sess = SwordAiClient.startFight(mc, opp);
			say(s, "Fighting " + (opp == null ? "nearest player" : opp) + ". " + sess.skillLine());
			return 1;
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("could not start fight", e);
			s.sendError(Component.literal("Could not start: " + e.getMessage()));
			return 0;
		}
	}

	private static int speed(FabricClientCommandSource s, float times) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (times == 0f) {
				say(s, String.format(Locale.ROOT, "Training speed: x%.1f", cfg.trainSpeed));
				return 1;
			}
			cfg.trainSpeed = times;
			SwordFiles.saveConfig(cfg);
			say(s, String.format(Locale.ROOT, "Training speed set to x%.1f", times));
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Failed: " + e));
			return 0;
		}
	}

	private static int fov(FabricClientCommandSource s, int deg) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (deg == 0) {
				say(s, "Screen FOV " + cfg.fov + ", vision FOV " + cfg.visionFov);
				return 1;
			}
			cfg.visionFov = Math.min(deg, cfg.fov);
			SwordFiles.saveConfig(cfg);
			say(s, "Vision FOV set to " + cfg.visionFov);
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Failed: " + e));
			return 0;
		}
	}

	private static int skin(FabricClientCommandSource s, boolean on) {
		try {
			Config cfg = SwordFiles.loadConfig();
			cfg.showOpponentSkin = on;
			SwordFiles.saveConfig(cfg);
			say(s, "Opponent skin " + (on ? "on" : "off"));
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Failed: " + e));
			return 0;
		}
	}

	private static int arena(FabricClientCommandSource s, int r) {
		try {
			Config cfg = SwordFiles.loadConfig();
			cfg.arenaRadius = r;
			SwordFiles.saveConfig(cfg);
			say(s, "Arena radius set to " + r);
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Failed: " + e));
			return 0;
		}
	}

	private static int name(FabricClientCommandSource s, String nick) {
		try {
			Config cfg = SwordFiles.loadConfig();
			cfg.botName = ArenaServer.playerName(nick, ArenaServer.BOT_NAME);
			SwordFiles.saveConfig(cfg);
			say(s, "Opponent name set to " + cfg.botName);
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Failed: " + e));
			return 0;
		}
	}

	private static int exportModel(FabricClientCommandSource s, String name) {
		try {
			Path p = ModelPack.export(name);
			say(s, "Exported " + p.getFileName());
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Export failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int importModel(FabricClientCommandSource s, String name) {
		try {
			ModelPack.importPack(name);
			say(s, "Imported " + name);
			return 1;
		} catch (IOException e) {
			s.sendError(Component.literal("Import failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int menu(FabricClientCommandSource s) {
		Minecraft mc = s.getClient();
		mc.execute(() -> mc.setScreen(new SwordAiScreen()));
		return 1;
	}

	private static int stop(FabricClientCommandSource s) {
		if (!SwordAiClient.running()) {
			say(s, "Nothing is running.");
			return 0;
		}
		SwordAiClient.stop("user stopped");
		return 1;
	}

	private static int status(FabricClientCommandSource s) {
		AgentSession sess = SwordAiClient.session();
		if (sess == null || sess.stopped()) {
			say(s, "Idle (SwordAI " + SwordFiles.modVersion() + ")");
		} else {
			for (String line : sess.statusLines()) say(s, line);
		}
		return 1;
	}

	private static void say(FabricClientCommandSource s, String msg) {
		s.sendFeedback(Component.literal("[SwordAI] " + msg));
	}
}
