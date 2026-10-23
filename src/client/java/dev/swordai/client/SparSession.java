package dev.swordai.client;

import dev.swordai.SwordAi;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.BotPilot;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The user fights the trained network themselves. The network plays the fake player on the
 * integrated server (see {@link BotPilot}); this side only starts it, keeps the score and stops it.
 * The player keeps their own mouse, keys, settings and view throughout.
 */
final class SparSession implements AgentSession {
	private final Minecraft mc;
	private final LoadedFighter loaded;
	private boolean stopped;
	private String lastScore = "";
	private String lastDetail = "";
	private String lastError;

	/**
	 * @throws IOException when there is no trained network to play with
	 */
	SparSession(Minecraft mc) throws IOException {
		this.mc = mc;
		this.loaded = LoadedFighter.load();
		SwordFiles.clearLastError();
		IntegratedServer server = mc.getSingleplayerServer();
		UUID me = mc.player.getUUID();
		BotPilot pilot = new BotPilot(loaded.fighter, loaded.frameAgeTicks, loaded.patchedUp);
		// From here on the fighter belongs to the server thread.
		server.execute(() -> ArenaServer.startSpar(server, me, loaded.cfg, pilot));
	}

	String skillLine() {
		return loaded.skillLine();
	}

	@Override
	public boolean stopped() {
		return stopped;
	}

	@Override
	public boolean inControl() {
		return false;
	}

	@Override
	public void tick() {
		if (stopped) {
			return;
		}
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
			stop("left the world");
			return;
		}
		Truth truth = ArenaServer.truth();
		if (truth.error() != null) {
			lastError = truth.error();
			SwordFiles.writeError(lastError, null);
			say("Stopped because of a problem: " + lastError);
			stop("error");
			return;
		}
		String score = ArenaServer.sparStatus();
		if (!score.isEmpty()) {
			lastScore = score;
		}
		String detail = ArenaServer.sparDetail();
		if (!detail.isEmpty()) {
			lastDetail = detail;
		}
	}

	@Override
	public void endTick() {
	}

	@Override
	public void worldDrawn() {
	}

	@Override
	public boolean beforePlayerMoves() {
		return false;
	}

	@Override
	public float viewYawLag(float partialTick) {
		return 0f;
	}

	@Override
	public float viewPitchLag(float partialTick) {
		return 0f;
	}

	@Override
	public boolean requestDebugDump() {
		return false;
	}

	@Override
	public String describe() {
		return "SPAR" + (lastScore.isEmpty() ? "" : ", " + lastScore);
	}

	String lastError() {
		return lastError;
	}

	@Override
	public void stop(String reason) {
		if (stopped) {
			return;
		}
		stopped = true;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			// The arena goes first, on its own thread; only then is the network it was using let go.
			server.execute(() -> {
				ArenaServer.stop(server);
				loaded.close();
			});
		} else {
			loaded.close();
		}
		String result = lastScore.isEmpty() ? "" : " Final score: " + lastScore + ".";
		if (!lastScore.isEmpty()) {
			SwordFiles.writeLastFight("spar, " + lastScore + "; " + loaded.skillLine() + (lastDetail.isEmpty() ? "" : " " + lastDetail));
		}
		say("Stopped (" + reason + ")." + result);
		if (!lastDetail.isEmpty()) {
			say(lastDetail);
		}
	}

	private void say(String message) {
		SwordAi.LOGGER.info(message);
		if (mc.gui != null) {
			mc.gui.getChat().addMessage(Component.literal("[SwordAI] " + message));
		}
	}

	@Override
	public List<String> statusLines() {
		List<String> out = new ArrayList<>();
		out.add("Sparring against the trained network. " + (lastScore.isEmpty() ? "The arena is being set up." : "Score: " + lastScore + "."));
		out.add(loaded.skillLine());
		if (!lastDetail.isEmpty()) {
			out.add(lastDetail);
		}
		return out;
	}
}
