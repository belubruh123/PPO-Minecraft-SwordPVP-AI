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
import java.nio.file.Files;
import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** The {@code /swordai} command. Client-side, so it needs no cheats and no operator rights. */
final class Commands {
	/** The most times normal speed training on the screen may be asked to run at. */
	private static final float MAX_SPEED = 8f;
	/** The arena's floor reaches this many blocks from its middle in each direction: the least and the most that can be asked for. */
	private static final int MIN_ARENA = Config.MIN_ARENA_RADIUS;
	private static final int MAX_ARENA = 32;

	private Commands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("swordai")
				.executes(c -> help(c.getSource()))
				.then(literal("school")
						.executes(c -> school(c.getSource(), SchoolSession.Mode.TRAIN, false, false))
						.then(literal("check").executes(c -> school(c.getSource(), SchoolSession.Mode.CHECK, false, false))
								.then(literal("teacher").executes(c -> school(c.getSource(), SchoolSession.Mode.CHECK, true, false)))
								.then(literal("overlay").executes(c -> school(c.getSource(), SchoolSession.Mode.CHECK, true, true)))))
				.then(literal("speed")
						.executes(c -> speed(c.getSource(), 0f))
						.then(argument("times", FloatArgumentType.floatArg(1f, MAX_SPEED))
								.executes(c -> speed(c.getSource(), FloatArgumentType.getFloat(c, "times")))))
				.then(literal("fight")
						.executes(c -> fight(c.getSource(), null))
						.then(argument("player", StringArgumentType.word())
								.executes(c -> fight(c.getSource(), StringArgumentType.getString(c, "player")))))
				.then(literal("fov")
						.executes(c -> fov(c.getSource(), 0))
						.then(argument("degrees", IntegerArgumentType.integer(30, 110))
								.executes(c -> fov(c.getSource(), IntegerArgumentType.getInteger(c, "degrees")))))
				.then(literal("name")
						.executes(c -> name(c.getSource(), null))
						.then(argument("nick", StringArgumentType.word())
								.executes(c -> name(c.getSource(), StringArgumentType.getString(c, "nick")))))
				.then(literal("skin")
						.executes(c -> skin(c.getSource(), null))
						.then(literal("on").executes(c -> skin(c.getSource(), true)))
						.then(literal("off").executes(c -> skin(c.getSource(), false))))
				.then(literal("arena")
						.executes(c -> arena(c.getSource(), 0))
						.then(argument("radius", IntegerArgumentType.integer(MIN_ARENA, MAX_ARENA))
								.executes(c -> arena(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
				.then(literal("menu").executes(c -> menu(c.getSource(), ""))
						.then(literal("export").executes(c -> menu(c.getSource(), "export")))
						.then(literal("import").executes(c -> menu(c.getSource(), "import"))))
				.then(literal("models").executes(c -> models(c.getSource())))
				.then(literal("export")
						.executes(c -> exportModel(c.getSource(), null))
						.then(argument("name", StringArgumentType.word())
								.executes(c -> exportModel(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("import")
						.executes(c -> models(c.getSource()))
						.then(argument("name", StringArgumentType.word())
								.suggests((c, b) -> {
									ModelPack.list().forEach(b::suggest);
									return b.buildFuture();
								})
								.executes(c -> importModel(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("stop").executes(c -> stop(c.getSource())))
				.then(literal("status").executes(c -> status(c.getSource())))
				.then(literal("debug").executes(c -> debug(c.getSource()))));
	}

	private static int help(FabricClientCommandSource source) {
		say(source, "/swordai menu [export|import] - all of this as buttons (also on Right Shift; change the key in Controls)");
		say(source, "/swordai export [name] | import <name> | models - the trained model as one .zip in swordai/models, to keep or take to another game");
		say(source, "/swordai school - train; /swordai school check - the head-to-head against the installed network");
		say(source, "/swordai speed [times] - how many times normal speed school training on the screen runs the game at");
		say(source, "/swordai name [nick] - the opponent's name; it gets that Minecraft account's skin where its looks are free to choose");
		say(source, "/swordai skin on|off - while the AI plays on your screen, show the opponent's own skin and name instead of the blue and green figure");
		say(source, "/swordai arena [radius] - how far the arena's floor reaches from its middle, in blocks");
		say(source, "/swordai stop - save and give the game back");
		say(source, "/swordai fight [player] - the trained AI plays your character against another player (any world or server)");
		say(source, "/swordai fov [degrees] - the field of view of the picture the AI gets (your screen is held at 90)");
		say(source, "/swordai status | debug");
		return 1;
	}

	/**
	 * Says, or sets, how many times normal speed training on the screen runs the game at.
	 *
	 * @param times 0 only reports
	 */
	private static int speed(FabricClientCommandSource source, float times) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (times == 0f) {
				say(source, String.format(java.util.Locale.ROOT, "School training on the screen runs the game at x%.1f. /swordai speed <times> changes it (1 to %.0f)."
						, cfg.trainSpeed, MAX_SPEED));
				return 1;
			}
			if (SwordAiClient.running()) {
				source.sendError(Component.literal("Use /swordai stop first, then change it."));
				return 0;
			}
			cfg.trainSpeed = times;
			SwordFiles.saveConfig(cfg);
			say(source, times <= 1f ? "Training on the screen runs at normal speed again."
					: String.format(java.util.Locale.ROOT, "From the next /swordai school the game runs at x%.1f while it trains. The window is made small for it "
							+ "(the AI's picture needs no more) and put back when it stops; if the computer does not keep up, the speed comes down by itself.", times));
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.literal("Could not change it: " + e));
			return 0;
		}
	}

	/** The technique school: training against the technique bots, or the paired check against the installed model. */
	private static int school(FabricClientCommandSource source, SchoolSession.Mode mode, boolean teacherPlays, boolean overlay) {
		Minecraft mc = source.getClient();
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			source.sendError(Component.literal("Open a singleplayer world first: SwordAI trains inside it."));
			return 0;
		}
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("SwordAI is already running. Use /swordai stop first."));
			return 0;
		}
		try {
			SchoolSession session = SwordAiClient.startSchool(mc, mode, teacherPlays, overlay);
			say(source, session.startLine());
			say(source, "Esc pauses, /swordai stop saves and restores everything.");
			return 1;
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not start the school", e);
			SwordFiles.writeError("could not start the school: " + e, e);
			source.sendError(Component.literal("SwordAI could not start: " + e));
			return 0;
		}
	}

	/** The trained network takes over the user's character and fights another player. */
	private static int fight(FabricClientCommandSource source, String opponentName) {
		Minecraft mc = source.getClient();
		if (mc.player == null || mc.level == null) {
			source.sendError(Component.literal("Join a world first."));
			return 0;
		}
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("SwordAI is already running. Use /swordai stop first."));
			return 0;
		}
		try {
			FightSession session = SwordAiClient.startFight(mc, opponentName);
			say(source, "The AI now plays your character against " + (opponentName == null ? "the other player" : opponentName)
					+ ". " + session.skillLine());
			say(source, "Your mouse and keys are ignored while it fights (T and / still open the chat). If nobody is there to fight, you get the controls back until someone is.");
			say(source, "It plays best with a sword on the hotbar and on flat ground. Esc pauses, /swordai stop ends it and restores your settings.");
			return 1;
		} catch (IOException | RuntimeException e) {
			return couldNotStart(source, e);
		}
	}

	/**
	 * Says, or sets, the field of view of the picture the network gets. The screen stays at the
	 * configured field of view either way; a narrower picture is the middle of the frame.
	 *
	 * @param degrees 0 only reports
	 */
	private static int fov(FabricClientCommandSource source, int degrees) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (degrees == 0) {
				say(source, "While the AI plays your screen is held at FOV " + cfg.fov + ", and the AI's picture is FOV "
						+ Math.min(cfg.visionFov, cfg.fov) + ". /swordai fov <degrees> changes what the AI gets.");
				return 1;
			}
			if (SwordAiClient.running()) {
				source.sendError(Component.literal("Use /swordai stop first, then change it."));
				return 0;
			}
			if (degrees > cfg.fov) {
				source.sendError(Component.literal("The AI's picture cannot be wider than the screen's FOV " + cfg.fov + "."));
				return 0;
			}
			cfg.visionFov = degrees;
			SwordFiles.saveConfig(cfg);
			say(source, "The AI's picture is now FOV " + degrees + "; your screen stays at " + cfg.fov
					+ ". It applies from the next start.");
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.literal("Could not change it: " + e));
			return 0;
		}
	}

	private static int couldNotStart(FabricClientCommandSource source, Exception e) {
		if (e instanceof IOException && e.getMessage() != null) {
			// An expected reason (nothing trained yet, a checkpoint that cannot be read): say it plainly.
			source.sendError(Component.literal("SwordAI could not start: " + e.getMessage()));
			return 0;
		}
		SwordAi.LOGGER.error("SwordAI could not start", e);
		SwordFiles.writeError("could not start: " + e, e);
		source.sendError(Component.literal("SwordAI could not start: " + e));
		return 0;
	}

	/**
	 * Says, or sets, the name of the fake player the AI fights. A fake player has the skin of the
	 * Minecraft account of its name, so this is also how its looks are chosen, wherever its looks are
	 * free to choose: where the AI reads the screen it has to see the blue and green figure.
	 *
	 * @param nick null only reports
	 */
	private static int name(FabricClientCommandSource source, String nick) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (nick == null) {
				say(source, "The opponent is called " + ArenaServer.playerName(cfg.botName, ArenaServer.BOT_NAME) + ". /swordai name <nick> changes it.");
				return 1;
			}
			if (SwordAiClient.running()) {
				source.sendError(Component.literal("Use /swordai stop first, then change it."));
				return 0;
			}
			if (!nick.equals(ArenaServer.playerName(nick, null))) {
				source.sendError(Component.literal("A player's name is 3 to 16 letters, digits or underscores."));
				return 0;
			}
			Minecraft mc = source.getClient();
			if (mc.player != null && nick.equalsIgnoreCase(mc.player.getScoreboardName())) {
				source.sendError(Component.literal("That is your own name; two players of one name cannot be in a world."));
				return 0;
			}
			cfg.botName = nick;
			SwordFiles.saveConfig(cfg);
			say(source, "The opponent is called " + nick + " from the next start. Where its looks are free to choose it also has the skin of the Minecraft account "
					+ nick + ", if there is one and the game can look it up (otherwise the default skin).");
			say(source, cfg.showOpponentSkin
					? "You have /swordai skin on, so you see that skin and name while the AI plays on your screen too."
					: "While the AI plays on your screen the opponent is the blue and green figure the AI needs to see. /swordai skin on shows you its own skin and name there as well.");
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.literal("Could not change it: " + e));
			return 0;
		}
	}

	/**
	 * Says, or sets, whether the player is shown the opponent's own skin while the AI plays on the
	 * screen. The AI's picture stays the keyed one either way.
	 *
	 * @param on null only reports
	 */
	private static int skin(FabricClientCommandSource source, Boolean on) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (on == null) {
				say(source, cfg.showOpponentSkin
						? "While the AI plays on your screen you see the opponent's own skin and name. /swordai skin off goes back to the blue and green figure."
						: "While the AI plays on your screen you see the blue and green figure the AI sees. /swordai skin on shows you the opponent's own skin and name instead.");
				return 1;
			}
			cfg.showOpponentSkin = on;
			SwordFiles.saveConfig(cfg);
			// It only changes what is drawn for the player, so it can be switched while the AI plays.
			KeyedSkin.setShowReal(on);
			if (on) {
				say(source, "You now see the opponent with its own skin, armour and name tag. The AI still gets its blue and green picture: it is taken first, and then only the opponent is drawn again over it for you, so it costs next to no frames.");
				say(source, "It is watched: should the AI stop seeing the opponent with this on, it is switched off again within a couple of seconds, and says so.");
			} else {
				say(source, "You now see the blue and green figure the AI sees.");
			}
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.literal("Could not change it: " + e));
			return 0;
		}
	}

	/**
	 * Says, or sets, how large the arena is.
	 *
	 * @param radius 0 only reports
	 */
	private static int arena(FabricClientCommandSource source, int radius) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (radius == 0) {
				int r = Math.max(MIN_ARENA, cfg.arenaRadius);
				say(source, "The arena's floor reaches " + r + " blocks from its middle (" + (2 * r + 1) + " by " + (2 * r + 1) + "). /swordai arena <radius> changes it ("
						+ MIN_ARENA + " to " + MAX_ARENA + "). It is built once and stays up between runs until you leave the world.");
				return 1;
			}
			if (SwordAiClient.running()) {
				source.sendError(Component.literal("Use /swordai stop first, then change it."));
				return 0;
			}
			cfg.arenaRadius = radius;
			SwordFiles.saveConfig(cfg);
			say(source, "From the next start the arena's floor reaches " + radius + " blocks from its middle (" + (2 * radius + 1) + " by " + (2 * radius + 1)
					+ "). Most fights start a few steps apart as before, and one in five from up to 26 blocks apart; the rest is room to be knocked, chased and run into.");
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.literal("Could not change it: " + e));
			return 0;
		}
	}

	private static int stop(FabricClientCommandSource source) {
		if (!SwordAiClient.running()) {
			source.sendError(Component.literal("SwordAI is not running."));
			return 0;
		}
		SwordAiClient.stop("by command");
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		AgentSession session = SwordAiClient.session();
		if (!SwordAiClient.running()) {
			say(source, "Not running. /swordai school trains; /swordai fight plays.");
			return 1;
		}
		for (String line : session.statusLines()) {
			say(source, line);
		}
		return 1;
	}

	private static int debug(FabricClientCommandSource source) {
		if (!SwordAiClient.running()) {
			source.sendError(Component.literal("Start the agent first (/swordai school or fight); the images are saved while it runs."));
			return 0;
		}
		if (!SwordAiClient.session().requestDebugDump()) {
			source.sendError(Component.literal("There is nothing to save here: the AI's view is not drawn on your screen."));
			return 0;
		}
		say(source, "The next frame the agent uses will be saved to swordai/debug (frame, mask and stats).");
		return 1;
	}

	private static int menu(FabricClientCommandSource source, String page) {
		Minecraft mc = source.getClient();
		// After the chat screen the command was typed in has closed.
		mc.execute(() -> mc.setScreen(new SwordAiScreen(page)));
		return 1;
	}

	private static int models(FabricClientCommandSource source) {
		java.util.List<String> packs = ModelPack.list();
		if (packs.isEmpty()) {
			say(source, "No models in " + ModelPack.dir() + " yet. /swordai export saves the current one there; a .zip from another game goes there too.");
		} else {
			say(source, "Models in " + ModelPack.dir() + ", newest first: " + String.join(", ", packs) + ". /swordai import <name> makes one the current model.");
		}
		return 1;
	}

	private static int exportModel(FabricClientCommandSource source, String name) {
		try {
			Path out = ModelPack.export(name == null ? ModelPack.defaultName() : name);
			say(source, String.format(java.util.Locale.ROOT, "Saved the current model to %s (%.1f MB). To use it in another game, put that file in its "
					+ "swordai/models folder and /swordai import %s there.", out, Files.size(out) / 1048576.0, ModelPack.clean(out.getFileName().toString())));
			return 1;
		} catch (IOException e) {
			source.sendError(Component.literal("Could not export the model: " + e.getMessage()));
			return 0;
		}
	}

	private static int importModel(FabricClientCommandSource source, String name) {
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("Stop it first (/swordai stop): the model cannot be swapped while it is running."));
			return 0;
		}
		try {
			int files = ModelPack.importPack(name);
			say(source, "Imported " + ModelPack.clean(name) + " (" + files + " files): it is the current model now. What it replaced was saved as "
					+ ModelPack.BEFORE_IMPORT + ": /swordai import " + ModelPack.BEFORE_IMPORT + " takes it back.");
			return 1;
		} catch (IOException e) {
			source.sendError(Component.literal("Could not import " + name + ": " + e.getMessage()));
			return 0;
		}
	}

	private static void say(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal("[SwordAI] " + message));
	}
}
