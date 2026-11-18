package dev.swordai.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.swordai.client.mixin.KeyMappingAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SwordAiClient implements ClientModInitializer {
	/** The current or most recent session; a stopped one is kept so its result can still be reported. */
	private static AgentSession session;
	/** Above zero while training runs faster than real time: how long a client tick is to last, in milliseconds. */
	private static volatile float tickMillis;
	/** Opens the SwordAI menu; see {@link SwordAiScreen}. Right Shift unless changed in Controls. */
	private static KeyMapping menuKey;
	/** Starts {@code /swordai fight} against the nearest player, or stops whatever is running. No key until one is given in Controls. */
	private static KeyMapping fightKey;

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(OptionsGuard::recoverAfterCrash);
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.swordai.menu", InputConstants.Type.KEYSYM,
				org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
		fightKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.swordai.fight", InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop("game closing"));
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			AgentSession s = session;
			if (s != null) {
				s.tick();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.getSingleplayerServer() != null) {
				// The agent's real motion for the scene recorder (the server only gets its positions).
				net.minecraft.world.phys.Vec3 v = mc.player.getDeltaMovement();
				dev.swordai.server.SceneRecorder.noteClientMotion(mc.player.getX(), mc.player.getY(), mc.player.getZ(), v.x, v.y, v.z);
			}
			AgentSession s = session;
			if (s != null) {
				s.endTick();
			}
			readInbox(mc);
			while (menuKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.setScreen(new SwordAiScreen());
				}
			}
			while (fightKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.player.connection.sendCommand(running() ? "swordai stop" : "swordai fight");
				}
			}
		});
		// The connection can close from another thread; the session only ever runs on the client thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> stop("left the world")));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> Commands.register(dispatcher));
	}

	/** How often the inbox is looked at, in client ticks. */
	private static final int INBOX_EVERY_TICKS = 20;
	private static int inboxTicks;

	/**
	 * Runs the {@code /swordai} commands left in {@code swordai/inbox.txt} as if they had been typed,
	 * and removes the file. That is how it is started, asked for its status and stopped with nobody
	 * at the keyboard: a script writes the file, and what the commands say goes to the game's log.
	 * Only its own commands are taken from there, and only while a world is open.
	 */
	private static void readInbox(Minecraft mc) {
		if (++inboxTicks < INBOX_EVERY_TICKS) {
			return;
		}
		inboxTicks = 0;
		if (mc.player == null || mc.level == null || mc.player.connection == null) {
			return;
		}
		Path inbox = SwordFiles.inbox();
		if (!Files.exists(inbox)) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
			Files.delete(inbox);
		} catch (IOException e) {
			// Still being written, most likely: it is looked at again in a second.
			return;
		}
		List<String> held = new java.util.ArrayList<>();
		for (String line : lines) {
			String command = line.strip();
			if (command.startsWith("/")) {
				command = command.substring(1);
			}
			if (personPlays() && !command.equals("swordai status")) {
				// The user is being recorded: what a script asks for waits until they are done.
				held.add(line);
				continue;
			}
			if (command.equals("swordai") || command.startsWith("swordai ")) {
				dev.swordai.SwordAi.LOGGER.info("inbox: /{}", command);
				mc.player.connection.sendCommand(command);
			} else if (!command.isEmpty() && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				// The development client only: any command, so a script can set up a test (a Carpet fake player to fight).
				dev.swordai.SwordAi.LOGGER.info("inbox (development): /{}", command);
				mc.player.connection.sendCommand(command);
			}
		}
		if (!held.isEmpty()) {
			String what = String.join(" | ", held);
			if (!what.equals(lastHeld)) {
				dev.swordai.SwordAi.LOGGER.info("inbox: held while you are recorded: {}", what);
				lastHeld = what;
			}
			try {
				// In front of anything written meanwhile.
				if (Files.exists(inbox)) {
					held.addAll(Files.readAllLines(inbox, StandardCharsets.UTF_8));
				}
				Files.write(inbox, held, StandardCharsets.UTF_8);
			} catch (IOException e) {
				dev.swordai.SwordAi.LOGGER.warn("could not put the held inbox commands back", e);
			}
		}
	}

	/** The inbox commands last held back during a recording, so that holding them is logged once. */
	private static String lastHeld = "";

	static AgentSession session() {
		return session;
	}

	/** The menu's key and the fight key, for the menu to say which they are. */
	static KeyMapping menuKey() {
		return menuKey;
	}

	static KeyMapping fightKey() {
		return fightKey;
	}

	static void setTickMillis(float millis) {
		tickMillis = millis;
	}

	/** How long a client tick is to last while training runs faster than real time and is not paused; 0 otherwise. */
	public static float tickMillis() {
		float millis = tickMillis;
		return millis > 0f && agentInControl() ? millis : 0f;
	}

	static boolean running() {
		return session != null && !session.stopped();
	}

	/** The technique school on the screen; see {@link SchoolSession}. */
	static SchoolSession startSchool(Minecraft mc, SchoolSession.Mode mode) throws IOException {
		return startSchool(mc, mode, false, false);
	}

	static SchoolSession startSchool(Minecraft mc, SchoolSession.Mode mode, boolean teacherPlays, boolean overlay) throws IOException {
		SchoolSession started = new SchoolSession(mc, mode, teacherPlays, overlay);
		session = started;
		return started;
	}

	/** The network plays the user's character against another player; see {@link FightSession}. */
	static FightSession startFight(Minecraft mc, String opponentName) throws IOException {
		FightSession started = new FightSession(mc, opponentName);
		session = started;
		return started;
	}

	static void stop(String reason) {
		if (running()) {
			session.stop(reason);
		}
	}

	// ---- used by the mixins; all of these run on the client thread --------------------------------

	/** True while the agent, not the player, is the one playing: not stopped and not paused. */
	public static boolean agentInControl() {
		AgentSession s = session;
		return s != null && s.inControl();
	}

	/** The user plays and is recorded ({@code /swordai demo}); see {@link DemoPlan}. */
	static boolean personPlays() {
		AgentSession s = session;
		return s != null && !s.stopped() && s.personPlays();
	}

	/** A press kept from the game by {@link #ignoresPlayerKey}: while the user is recorded, a swing held for the session. */
	public static void heldClick(InputConstants.Key key) {
		AgentSession s = session;
		if (s != null && s.takesClicks() && isBoundTo(Minecraft.getInstance().options.keyAttack, key)) {
			s.heldClick();
		}
	}

	/** Whether a press of this physical key or mouse button is kept away from the game. */
	public static boolean ignoresPlayerKey(InputConstants.Key key) {
		AgentSession s = session;
		if (s != null && s.takesClicks()) {
			// The user plays and is recorded: only their swing is held back, to be made at the moment in the
			// tick where the network's would be, after the picture it is recorded with was taken.
			return isBoundTo(Minecraft.getInstance().options.keyAttack, key);
		}
		if (!agentInControl()) {
			return false;
		}
		Options o = Minecraft.getInstance().options;
		// Its own two keys go through as well: the one that starts a fight has to be able to stop it
		// (up to 0.15.0 it could start one and not stop it), and the menu has a stop button.
		return !isBoundTo(o.keyChat, key) && !isBoundTo(o.keyCommand, key)
				&& (fightKey == null || !isBoundTo(fightKey, key)) && (menuKey == null || !isBoundTo(menuKey, key));
	}

	private static boolean isBoundTo(KeyMapping mapping, InputConstants.Key key) {
		return key.equals(((KeyMappingAccessor) mapping).swordai$getKey());
	}

	/** Once per rendered frame, when the world is drawn and the hand and HUD are not yet. */
	public static void worldDrawn() {
		// With the real skin shown the picture was taken as soon as the level was drawn; see levelDrawn.
		if (takenAtLevel) {
			takenAtLevel = false;
			return;
		}
		AgentSession s = session;
		if (s != null && !s.stopped()) {
			s.worldDrawn();
		}
	}

	/** This frame's picture was taken when the level was drawn, before the real skin went over the key. */
	private static boolean takenAtLevel;

	/**
	 * The level is drawn, its depth still in place, and nothing else yet. With the opponent's own skin
	 * shown, this is where the agent's picture is taken, with the key; then the real skin is drawn over
	 * the keyed figure for the player (see {@link ShownSkin}).
	 */
	public static void levelDrawn() {
		takenAtLevel = KeyedSkin.showReal();
		if (!takenAtLevel) {
			return;
		}
		AgentSession s = session;
		if (s != null && !s.stopped()) {
			s.worldDrawn();
		}
		try {
			ShownSkin.drawOver(Minecraft.getInstance());
		} catch (RuntimeException e) {
			keyedPassFailed(e);
		}
	}

	/**
	 * Drawing the opponent's own skin over the key went wrong. It is not tried again: the player sees
	 * the keyed figure, which is what the agent needs.
	 */
	public static void keyedPassFailed(RuntimeException error) {
		KeyedSkin.setShowReal(false);
		dev.swordai.SwordAi.LOGGER.error("SwordAI could not draw the opponent's own skin over the colour key; showing the colour key again", error);
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) {
			mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
					"[SwordAI] Showing the opponent's own skin did not work here (" + error + "), so it is the blue and green figure again. /swordai report has the details in the log."));
		}
	}

	/**
	 * With the opponent's own skin shown, the agent's picture has no opponent in it: the picture it
	 * is handed is not the keyed one. Showing the skin is switched off, here and in the config, so
	 * that it does not come back with the next start; the agent then sees again at once.
	 *
	 * @param grabs what the frame grabber has to say about the pictures it took meanwhile
	 */
	static void skinBlindsTheAgent(String grabs) {
		String diagnosis = KeyedSkin.drawCounts() + "; " + grabs;
		KeyedSkin.setShowReal(false);
		try {
			dev.swordai.core.config.Config cfg = SwordFiles.loadConfig();
			cfg.showOpponentSkin = false;
			SwordFiles.saveConfig(cfg);
		} catch (IOException | RuntimeException e) {
			dev.swordai.SwordAi.LOGGER.warn("could not save that the skin is not to be shown", e);
		}
		String message = "With the opponent's own skin shown, the AI could not see the opponent at all: it was fighting blind. Showing the skin is switched off (the blue and green figure is back) and it sees again. "
				+ "Please paste /swordai report so that this can be fixed; diagnosis: " + diagnosis + ".";
		dev.swordai.SwordAi.LOGGER.warn(message);
		SwordFiles.writeError("showing the opponent's skin blinded the AI; " + diagnosis, null);
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) {
			mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("[SwordAI] " + message));
		}
	}

	/** Once per client tick, after vanilla's key handling. Returns whether the agent swings now. */
	public static boolean beforePlayerMoves() {
		AgentSession s = session;
		return s != null && s.beforePlayerMoves();
	}

	/** Degrees to add to the drawn view so that this tick's turn glides in instead of snapping. */
	public static float viewYawLag(float partialTick) {
		AgentSession s = session;
		return s == null ? 0f : s.viewYawLag(partialTick);
	}

	public static float viewPitchLag(float partialTick) {
		AgentSession s = session;
		return s == null ? 0f : s.viewPitchLag(partialTick);
	}
}
