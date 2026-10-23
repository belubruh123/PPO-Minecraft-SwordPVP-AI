package dev.swordai.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.Curriculum;
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
				.then(literal("train")
						.executes(c -> start(c.getSource(), TrainingSession.Mode.TRAIN, false))
						.then(literal("fresh").executes(c -> start(c.getSource(), TrainingSession.Mode.TRAIN, true)))
						.then(literal("headless").executes(c -> headless(c.getSource()))))
				.then(literal("school")
						.executes(c -> school(c.getSource(), SchoolSession.Mode.TRAIN, false, false))
						.then(literal("check").executes(c -> school(c.getSource(), SchoolSession.Mode.CHECK, false, false))
								.then(literal("teacher").executes(c -> school(c.getSource(), SchoolSession.Mode.CHECK, true, false)))
								.then(literal("overlay").executes(c -> school(c.getSource(), SchoolSession.Mode.CHECK, true, true)))))
				.then(literal("speed")
						.executes(c -> speed(c.getSource(), 0f))
						.then(argument("times", FloatArgumentType.floatArg(1f, MAX_SPEED))
								.executes(c -> speed(c.getSource(), FloatArgumentType.getFloat(c, "times")))))
				.then(literal("play").executes(c -> start(c.getSource(), TrainingSession.Mode.PLAY, false)))
				.then(literal("selftest").executes(c -> start(c.getSource(), TrainingSession.Mode.SELFTEST, false)))
				.then(literal("fight")
						.executes(c -> fight(c.getSource(), null))
						.then(argument("player", StringArgumentType.word())
								.executes(c -> fight(c.getSource(), StringArgumentType.getString(c, "player")))))
				.then(literal("spar").executes(c -> spar(c.getSource())))
				.then(literal("demo")
						.executes(c -> demo(c.getSource(), "spacer"))
						.then(literal("stop").executes(c -> demoStop(c.getSource())))
						.then(literal("record").executes(c -> demoRecord(c.getSource(), true)))
						.then(literal("pause").executes(c -> demoRecord(c.getSource(), false)))
						.then(argument("kind", StringArgumentType.word())
								.suggests((c, b) -> {
									DemoPlan.KINDS.forEach(b::suggest);
									return b.buildFuture();
								})
								.executes(c -> demo(c.getSource(), StringArgumentType.getString(c, "kind")))))
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
				.then(literal("learn")
						.executes(c -> learn(c.getSource(), null))
						.then(literal("on").executes(c -> learn(c.getSource(), true)))
						.then(literal("off").executes(c -> learn(c.getSource(), false))))
				.then(literal("arena")
						.executes(c -> arena(c.getSource(), 0))
						.then(argument("radius", IntegerArgumentType.integer(MIN_ARENA, MAX_ARENA))
								.executes(c -> arena(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
				.then(literal("undo").executes(c -> undo(c.getSource())))
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
				.then(literal("report").executes(c -> report(c.getSource())))
				.then(literal("debug").executes(c -> debug(c.getSource())))
				.then(literal("retry")
						.executes(c -> retry(c.getSource(), 0))
						.then(argument("number", IntegerArgumentType.integer(2))
								.executes(c -> retry(c.getSource(), IntegerArgumentType.getInteger(c, "number")))))
				.then(literal("stage")
						.then(argument("number", IntegerArgumentType.integer(1))
								.executes(c -> stage(c.getSource(), IntegerArgumentType.getInteger(c, "number"))))));
	}

	private static int help(FabricClientCommandSource source) {
		say(source, "/swordai menu [export|import] - all of this as buttons (also on Right Shift; change the key in Controls)");
		say(source, "/swordai export [name] | import <name> | models - the trained model as one .zip in swordai/models, to keep or take to another game");
		say(source, "/swordai train - start or resume training (train fresh starts over)");
		say(source, "/swordai train headless - the same training without the screen: faster, and you keep your keys");
		say(source, "/swordai speed [times] - how many times normal speed /swordai train runs the game at");
		say(source, "/swordai name [nick] - the opponent's name; it gets that Minecraft account's skin in spar and in training without the screen");
		say(source, "/swordai skin on|off - while the AI plays on your screen, show the opponent's own skin and name instead of the blue and green figure");
		say(source, "/swordai learn on|off - whether /swordai fight and /swordai spar also teach the AI (off unless you turn it on)");
		say(source, "/swordai arena [radius] - how far the arena's floor reaches from its middle, in blocks");
		say(source, "/swordai undo - go back to the network as it was before training without the screen, or learning from people, last took over");
		say(source, "/swordai stop - save and give the game back");
		say(source, "/swordai report - copy a summary to the clipboard to paste back");
		say(source, "/swordai retry [n] - learn stage n (default: the current one) again from where it began");
		say(source, "/swordai fight [player] - the trained AI plays your character against another player (any world or server)");
		say(source, "/swordai spar - you fight the trained AI yourself (singleplayer)");
		say(source, "/swordai demo [spacer|mix|jumper|timer|...] - you fight the training's opponents and the AI learns from how you do it; /swordai demo stop to end");
		say(source, "/swordai fov [degrees] - the field of view of the picture the AI gets (your screen is held at 90)");
		say(source, "/swordai status | play | selftest | debug | stage <n>");
		return 1;
	}

	private static int start(FabricClientCommandSource source, TrainingSession.Mode mode, boolean fresh) {
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
			if (fresh) {
				Path backup = SwordFiles.archiveRun();
				say(source, backup == null ? "Nothing saved yet, starting fresh." : "Previous run moved to " + backup.getFileName() + ".");
			}
			boolean hadCheckpoint = Files.exists(SwordFiles.checkpoint());
			if (mode == TrainingSession.Mode.PLAY && !hadCheckpoint) {
				say(source, "No trained checkpoint yet, so the agent will act at random. Run /swordai train first.");
			}
			TrainingSession session = SwordAiClient.start(mc, mode);
			switch (mode) {
				case TRAIN -> say(source, session.resumed()
						? "Resuming training at stage " + (session.curriculum().stageIndex() + 1) + ": " + session.curriculum().current().name + "."
						: "Starting training at stage 1: " + session.curriculum().current().name + ".");
				case PLAY -> say(source, "Playing with the current policy (no learning).");
				case SELFTEST -> say(source, "Running the vision self-test.");
			}
			say(source, "Building the arena and spawning the sparring bot. Your mouse and keys are ignored while the agent plays;");
			say(source, "T and / still open the chat, and you can use other windows. /swordai status shows the progress.");
			say(source, "Esc pauses, /swordai stop saves and restores everything.");
			return 1;
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not start", e);
			SwordFiles.writeError("could not start: " + e, e);
			source.sendError(Component.literal("SwordAI could not start: " + e));
			return 0;
		}
	}

	/**
	 * Training without the screen: the agent is a fake player with a drawn picture, so the world can
	 * run as fast as the computer manages. The same checkpoint, the same curriculum.
	 */
	private static int headless(FabricClientCommandSource source) {
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
			HeadlessSession session = SwordAiClient.startHeadless(mc);
			say(source, (session.resumed() ? "Resuming" : "Starting") + " training without the screen at stage "
					+ (session.curriculum().stageIndex() + 1) + ": " + session.curriculum().current().name + ".");
			say(source, "The AI plays a fake player of its own in an arena above you, with a drawn picture instead of the screen, as fast as this computer manages.");
			say(source, "Your character, keys and mouse stay yours, and the window can be minimised. It says where it has got to every ten minutes and at every new stage; /swordai status and /swordai report work as always.");
			say(source, "To go back to training on the screen: /swordai stop, then /swordai train. It carries on from the same network either way."
					+ (session.keptCopy() ? " The network as the screen had trained it was kept: /swordai undo brings that back if this turns out worse." : ""));
			return 1;
		} catch (IOException | RuntimeException e) {
			return couldNotStart(source, e);
		}
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
				say(source, String.format(java.util.Locale.ROOT, "/swordai train runs the game at x%.1f. /swordai speed <times> changes it (1 to %.0f). "
						+ "/swordai train headless does not use this: it goes as fast as the computer manages.", cfg.trainSpeed, MAX_SPEED));
				return 1;
			}
			if (SwordAiClient.running()) {
				source.sendError(Component.literal("Use /swordai stop first, then change it."));
				return 0;
			}
			cfg.trainSpeed = times;
			SwordFiles.saveConfig(cfg);
			say(source, times <= 1f ? "Training on the screen runs at normal speed again."
					: String.format(java.util.Locale.ROOT, "From the next /swordai train the game runs at x%.1f while it trains. The window is made small for it "
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
			if (SwordFiles.loadConfig().learnFromPeople) {
				FightLearnSession session = SwordAiClient.startFightLearning(mc, opponentName);
				say(source, "The AI now plays your character against " + (opponentName == null ? "the other player" : opponentName)
						+ ", and learns from it (/swordai learn off turns that off). " + session.skillLine());
				if (session.safe()) {
					say(source, "It learns safely: the proven network and a learning copy take turns, a fight each, and the copy takes over only once it has dealt more damage and taken less over 8 fights each; "
							+ "if it does worse it is thrown away. The proven network is never changed otherwise. " + session.safeLine());
				} else {
					say(source, "It learns from each minute or two of fighting and fights on while it does; /swordai stop also learns from what is left."
							+ (session.keptCopy() ? " The network as it was before this was kept: /swordai undo brings it back." : ""));
				}
				say(source, "Your mouse and keys are ignored while it fights (T and / still open the chat). If nobody is there to fight, you get the controls back until someone is.");
				return 1;
			}
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

	/** The user fights the trained network, which plays the fake player in the arena. */
	/**
	 * {@code /swordai demo [kind]}: the user fights the training's opponents and is recorded for the network
	 * to learn from (see DemoPlan). Training on the screen that is running is put aside and goes on
	 * by itself afterwards.
	 */
	private static int demo(FabricClientCommandSource source, String kind) {
		Minecraft mc = source.getClient();
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			source.sendError(Component.literal("Open a singleplayer world first: the fights are set up inside it."));
			return 0;
		}
		if (!DemoPlan.KINDS.contains(kind)) {
			source.sendError(Component.literal("Not an opponent to record against: " + kind + ". One of " + String.join(", ", DemoPlan.KINDS) + "."));
			return 0;
		}
		if (SwordAiClient.personPlays()) {
			source.sendError(Component.literal("You are being recorded already. /swordai demo stop ends it."));
			return 0;
		}
		boolean resume = false;
		if (SwordAiClient.running()) {
			if (!(SwordAiClient.session() instanceof TrainingSession t) || t.mode != TrainingSession.Mode.TRAIN) {
				source.sendError(Component.literal("SwordAI is running something else. Use /swordai stop first."));
				return 0;
			}
			resume = true;
			SwordAiClient.stop("you are going to be recorded");
		}
		try {
			SwordAiClient.startDemo(mc, new DemoPlan(kind, resume));
		} catch (IOException | RuntimeException e) {
			return couldNotStart(source, e);
		}
		String key = SwordAiClient.recordKeyName();
		say(source, "You fight " + (kind.equals("mix") ? "the training's mix of opponents" : "the " + kind)
				+ " yourself: your mouse and keys are yours, at normal speed. Each fight lasts until a knockout or 30 seconds.");
		say(source, "Warm up first: nothing is recorded yet. Press " + key + " (or /swordai demo record) when you are ready, and recording begins with the next fight;"
				+ " " + key + " again (or /swordai demo pause) pauses it and drops the fight under way.");
		say(source, "The AI sees what you see and learns only from the moves of yours that went better than it expects of itself."
				+ " /swordai demo stop when you are done" + (resume ? "; training goes on by itself then." : "."));
		return 1;
	}

	private static int demoRecord(FabricClientCommandSource source, boolean on) {
		if (!SwordAiClient.personPlays() || !(SwordAiClient.session() instanceof Trainer t)) {
			source.sendError(Component.literal("You are not fighting for the AI to watch. /swordai demo starts it."));
			return 0;
		}
		t.demoRecord(on);
		return 1;
	}

	private static int demoStop(FabricClientCommandSource source) {
		if (!SwordAiClient.personPlays()) {
			source.sendError(Component.literal("You are not being recorded. /swordai demo starts it."));
			return 0;
		}
		SwordAiClient.stop("you are done");
		return 1;
	}

	private static int spar(FabricClientCommandSource source) {
		Minecraft mc = source.getClient();
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			source.sendError(Component.literal("Open a singleplayer world first: the arena and the AI's body are made inside it."));
			return 0;
		}
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("SwordAI is already running. Use /swordai stop first."));
			return 0;
		}
		try {
			Config cfg = SwordFiles.loadConfig();
			if (cfg.learnFromPeople) {
				SparLearnSession session = SwordAiClient.startSparLearning(mc);
				say(source, "Setting up the arena. You fight " + ArenaServer.playerName(cfg.botName, ArenaServer.BOT_NAME)
						+ ", played by the AI, which learns from it (/swordai learn off turns that off); both get the same kit. " + session.skillLine());
				say(source, "It learns from each minute or two of fighting and fights on while it does; /swordai stop also learns from what is left."
						+ (session.keptCopy() ? " The network as it was before this was kept: /swordai undo brings it back." : ""));
				say(source, "A knockout ends the round and the next one starts after a short countdown. /swordai stop ends it and puts you and your inventory back.");
				return 1;
			}
			SparSession session = SwordAiClient.startSpar(mc);
			say(source, "Building the arena. You fight " + ArenaServer.playerName(cfg.botName, ArenaServer.BOT_NAME)
					+ ", played by the trained AI; both get the same kit. " + session.skillLine());
			say(source, "A knockout ends the round and the next one starts after a short countdown. The score is said in chat after each round.");
			say(source, "/swordai stop ends it and puts you and your inventory back.");
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
					+ ". It applies from the next train, play, fight or spar.");
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
	 * Throws away what was learned from a stage onwards and trains it again, starting from the network
	 * as it was when the stage before it was passed.
	 *
	 * @param number the stage, counted from 1; 0 means the current one
	 */
	private static int retry(FabricClientCommandSource source, int number) {
		Minecraft mc = source.getClient();
		if (mc.getSingleplayerServer() == null || mc.player == null) {
			source.sendError(Component.literal("Open a singleplayer world first: SwordAI trains inside it."));
			return 0;
		}
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("SwordAI is running. Use /swordai stop first, then /swordai retry."));
			return 0;
		}
		try {
			Config cfg = SwordFiles.loadConfig();
			Curriculum curriculum = new Curriculum(cfg.stages);
			if (Files.exists(SwordFiles.curriculum())) {
				curriculum.load(SwordFiles.curriculum());
			}
			int reached = curriculum.stageIndex() + 1;
			int target = number == 0 ? reached : number;
			if (target > reached) {
				source.sendError(Component.literal("Training has only reached stage " + reached + "."));
				return 0;
			}
			if (target <= 1) {
				source.sendError(Component.literal("Stage 1 starts from nothing: use /swordai train fresh."));
				return 0;
			}
			if (!Files.exists(SwordFiles.stageCheckpoint(target - 1))) {
				source.sendError(Component.literal("No checkpoint was kept from the end of stage " + (target - 1) + ", so stage " + target + " cannot be started again."));
				return 0;
			}
			Path backup = SwordFiles.rewindToStage(target - 1);
			curriculum.setStage(target - 1);
			curriculum.save(SwordFiles.curriculum());
			say(source, "Back to the start of stage " + target + ": " + curriculum.current().name + ". What it replaced is kept in " + backup.getFileName() + ".");
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not go back to the stage", e);
			source.sendError(Component.literal("Could not go back to that stage: " + e));
			return 0;
		}
		return start(source, TrainingSession.Mode.TRAIN, false);
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
			say(source, "The opponent is called " + nick + " from the next start. In /swordai spar and /swordai train headless it also has the skin of the Minecraft account "
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
			KeyedSkin.setTwoPass(on);
			if (on) {
				say(source, "You now see the opponent with its own skin, armour and name tag. The AI still gets its blue and green picture: it is drawn first, out of sight, and then the world is drawn again for you.");
				say(source, "That is two pictures per frame, so expect about half the frames a second, and the AI's picture is a little older for it. For long training runs /swordai skin off is the better setting.");
				say(source, "It is watched: should the AI stop seeing the opponent with this on, it is switched off again within a couple of seconds, and says so.");
			} else {
				say(source, "Back to one picture per frame: you see the blue and green figure the AI sees.");
			}
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.literal("Could not change it: " + e));
			return 0;
		}
	}

	/**
	 * Whether {@code /swordai fight} and {@code /swordai spar} also teach the network.
	 *
	 * @param on null only reports
	 */
	private static int learn(FabricClientCommandSource source, Boolean on) {
		try {
			Config cfg = SwordFiles.loadConfig();
			if (on == null) {
				say(source, cfg.learnFromPeople
						? "The AI learns from /swordai fight and /swordai spar. /swordai learn off makes them plain fights again."
						: "The AI does not learn from /swordai fight or /swordai spar: they only show what it can do. /swordai learn on makes it learn from them too.");
				return 1;
			}
			if (SwordAiClient.running()) {
				source.sendError(Component.literal("Use /swordai stop first, then change it."));
				return 0;
			}
			cfg.learnFromPeople = on;
			SwordFiles.saveConfig(cfg);
			if (on) {
				say(source, "From the next /swordai fight or /swordai spar the AI learns from the fight as well, with the rewards of the stage it is in. It fights on while it learns, so nothing stops.");
				if (cfg.safeLearn) {
					say(source, "In /swordai fight it learns safely: the trained network and a learning copy take turns a fight each; the copy only replaces it after doing better (damage dealt minus taken) over 8 fights each, and is thrown away if it does worse.");
				}
				say(source, "Fights against people never count towards passing a stage, and in a fight on a server the hits are judged from what your game can see, which is less exact than in training.");
				say(source, "The network from before it first learns from people is kept: /swordai undo brings it back. /swordai learn off turns this off again.");
			} else {
				say(source, "/swordai fight and /swordai spar are plain fights again: nothing is learned from them.");
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

	/**
	 * Goes back to the network as it was kept before training without the screen, or learning from
	 * people, last took over: whichever of the two began last. That copy is then used up, so a second
	 * undo goes back to the one before it.
	 */
	private static int undo(FabricClientCommandSource source) {
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("SwordAI is running. Use /swordai stop first, then /swordai undo."));
			return 0;
		}
		Path keep = SwordFiles.newestKept();
		if (keep == null) {
			source.sendError(Component.literal("There is nothing to go back to: no copy is kept from before training without the screen or from before learning from people."));
			return 0;
		}
		boolean people = keep.equals(SwordFiles.beforePeople());
		try {
			Path backup = SwordFiles.restoreKept(keep);
			Config cfg = SwordFiles.loadConfig();
			Curriculum curriculum = new Curriculum(cfg.stages);
			if (Files.exists(SwordFiles.curriculum())) {
				curriculum.load(SwordFiles.curriculum());
			}
			say(source, "Back to the network as it was before " + (people ? "it learned from fights against people" : "training without the screen") + ": stage "
					+ (curriculum.stageIndex() + 1) + " (" + curriculum.current().name + "). What it had become since is kept in " + backup.getFileName()
					+ ". /swordai train carries on from here." + (SwordFiles.newestKept() != null ? " /swordai undo once more goes back another step." : ""));
			return 1;
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not go back", e);
			source.sendError(Component.literal("Could not go back: " + e));
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
			say(source, "Not running. /swordai train starts or resumes; /swordai report shows the history.");
			return 1;
		}
		for (String line : session.statusLines()) {
			say(source, line);
		}
		return 1;
	}

	private static int report(FabricClientCommandSource source) {
		Minecraft mc = source.getClient();
		Trainer session = SwordAiClient.training();
		String other = session == null && SwordAiClient.running() ? SwordAiClient.session().describe() : null;
		try {
			String text = Report.build(mc, session, other, null);
			Report.write(text);
			mc.keyboardHandler.setClipboard(text);
			say(source, "Report copied to the clipboard and saved to swordai/report.txt (" + text.lines().count() + " lines). Paste it back to get the training tuned.");
			return 1;
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.error("SwordAI could not build the report", e);
			source.sendError(Component.literal("Could not build the report: " + e));
			return 0;
		}
	}

	private static int debug(FabricClientCommandSource source) {
		if (!SwordAiClient.running()) {
			source.sendError(Component.literal("Start the agent first (/swordai train, play, fight or selftest); the images are saved while it runs."));
			return 0;
		}
		if (!SwordAiClient.session().requestDebugDump()) {
			source.sendError(Component.literal("There is nothing to save here: the AI's view is not drawn on your screen."));
			return 0;
		}
		say(source, "The next frame the agent uses will be saved to swordai/debug (frame, mask and stats).");
		return 1;
	}

	private static int stage(FabricClientCommandSource source, int number) {
		try {
			if (SwordAiClient.running()) {
				Trainer session = SwordAiClient.training();
				if (session == null || !session.stageFights()) {
					source.sendError(Component.literal("Use /swordai stop first; the stage can only be changed for training."));
					return 0;
				}
				if (number > session.curriculum().stageCount()) {
					source.sendError(Component.literal("There are only " + session.curriculum().stageCount() + " stages."));
					return 0;
				}
				session.setStage(number - 1);
				say(source, "Now on stage " + number + ": " + session.curriculum().current().name + " (takes effect next episode).");
				return 1;
			}
			Config cfg = SwordFiles.loadConfig();
			Curriculum curriculum = new Curriculum(cfg.stages);
			if (number > curriculum.stageCount()) {
				source.sendError(Component.literal("There are only " + curriculum.stageCount() + " stages."));
				return 0;
			}
			if (Files.exists(SwordFiles.curriculum())) {
				curriculum.load(SwordFiles.curriculum());
			}
			curriculum.setStage(number - 1);
			curriculum.save(SwordFiles.curriculum());
			say(source, "The next /swordai train starts on stage " + number + ": " + curriculum.current().name + ".");
			return 1;
		} catch (IOException e) {
			source.sendError(Component.literal("Could not change the stage: " + e));
			return 0;
		}
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
