package dev.swordai.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.swordai.SwordAi;
import dev.swordai.client.mixin.KeyMappingAccessor;
import dev.swordai.server.SceneRecorder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SwordAiClient implements ClientModInitializer {
	private static AgentSession session;
	private static volatile float tickMillis;
	private static KeyMapping menuKey;
	private static KeyMapping fightKey;
	private static boolean takenAtLevel;
	private static final int INBOX_EVERY_TICKS = 20;
	private static int inboxTicks;

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(OptionsGuard::recoverAfterCrash);
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.swordai.menu", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
		fightKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.swordai.fight", InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop("game closing"));
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			AgentSession s = session;
			if (s != null) s.tick();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.getSingleplayerServer() != null) {
				Vec3 v = mc.player.getDeltaMovement();
				SceneRecorder.noteClientMotion(mc.player.getX(), mc.player.getY(), mc.player.getZ(), v.x, v.y, v.z);
			}
			AgentSession s = session;
			if (s != null) s.endTick();
			readInbox(mc);
			while (menuKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) mc.setScreen(new SwordAiScreen());
			}
			while (fightKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.player.connection.sendCommand(running() ? "swordai stop" : "swordai fight");
				}
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> stop("left the world")));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> Commands.register(dispatcher));
	}

	private static void readInbox(Minecraft mc) {
		if (++inboxTicks < INBOX_EVERY_TICKS) return;
		inboxTicks = 0;
		if (mc.player == null || mc.level == null || mc.player.connection == null) return;
		Path inbox = SwordFiles.inbox();
		if (!Files.exists(inbox)) return;
		List<String> lines;
		try {
			lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
			Files.delete(inbox);
		} catch (IOException e) {
			return;
		}
		for (String line : lines) {
			String cmd = line.strip();
			if (cmd.startsWith("/")) cmd = cmd.substring(1);
			if (cmd.equals("swordai") || cmd.startsWith("swordai ")) {
				SwordAi.LOGGER.info("inbox: /{}", cmd);
				mc.player.connection.sendCommand(cmd);
			} else if (!cmd.isEmpty() && FabricLoader.getInstance().isDevelopmentEnvironment()) {
				SwordAi.LOGGER.info("inbox (dev): /{}", cmd);
				mc.player.connection.sendCommand(cmd);
			}
		}
	}

	static AgentSession session() {
		return session;
	}

	static KeyMapping menuKey() {
		return menuKey;
	}

	static KeyMapping fightKey() {
		return fightKey;
	}

	static void setTickMillis(float millis) {
		tickMillis = millis;
	}

	public static float tickMillis() {
		float millis = tickMillis;
		return millis > 0f && agentInControl() ? millis : 0f;
	}

	static boolean running() {
		return session != null && !session.stopped();
	}

	static LeagueSession startSchool(Minecraft mc, LeagueSession.Mode mode) throws IOException {
		LeagueSession started = new LeagueSession(mc, mode);
		session = started;
		return started;
	}

	static FightSession startFight(Minecraft mc, String opponentName) throws IOException {
		FightSession started = new FightSession(mc, opponentName);
		session = started;
		return started;
	}

	static void stop(String reason) {
		if (running()) session.stop(reason);
	}

	public static boolean agentInControl() {
		AgentSession s = session;
		return s != null && s.inControl();
	}

	static boolean personPlays() {
		return false;
	}

	public static void heldClick(InputConstants.Key key) {
	}

	public static boolean ignoresPlayerKey(InputConstants.Key key) {
		if (!agentInControl()) return false;
		Options o = Minecraft.getInstance().options;
		return !isBoundTo(o.keyChat, key) && !isBoundTo(o.keyCommand, key)
				&& (fightKey == null || !isBoundTo(fightKey, key))
				&& (menuKey == null || !isBoundTo(menuKey, key));
	}

	private static boolean isBoundTo(KeyMapping mapping, InputConstants.Key key) {
		return key.equals(((KeyMappingAccessor) mapping).swordai$getKey());
	}

	public static void worldDrawn() {
		if (takenAtLevel) {
			takenAtLevel = false;
			return;
		}
		AgentSession s = session;
		if (s != null && !s.stopped()) s.worldDrawn();
	}

	public static void levelDrawn() {
		AgentSession s = session;
		if (s != null && !s.stopped() && KeyedSkin.showReal()) {
			s.worldDrawn();
			ShownSkin.drawOver(Minecraft.getInstance());
			takenAtLevel = true;
		}
	}

	public static boolean beforePlayerMoves() {
		AgentSession s = session;
		return s != null && !s.stopped() && s.beforePlayerMoves();
	}

	public static boolean agentWantsUseItem() {
		AgentSession s = session;
		return s != null && !s.stopped() && s.inControl() && s.wantsUseItem();
	}

	public static float viewYawLag(float partialTick) {
		AgentSession s = session;
		return s != null && s.inControl() ? s.viewYawLag(partialTick) : 0f;
	}

	public static float viewPitchLag(float partialTick) {
		AgentSession s = session;
		return s != null && s.inControl() ? s.viewPitchLag(partialTick) : 0f;
	}
}
