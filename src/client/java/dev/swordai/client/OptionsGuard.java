package dev.swordai.client;

import dev.swordai.SwordAi;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.server.level.ParticleStatus;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Puts the game options into the state the agent's vision and controls assume, and puts them back
 * afterwards. The previous values are also written to disk, so a crash or a killed game cannot
 * leave the player stuck with the training settings: they are restored on the next launch.
 */
final class OptionsGuard {
	private static final int TRAINING_FPS_LIMIT = 120;
	/** The game's own value for "no limit". */
	private static final int UNLIMITED_FPS = 260;
	/** Training without the screen: the game only has to draw often enough to be looked at. */
	private static final int QUIET_FPS_LIMIT = 30;
	/**
	 * Training without the screen: how far the world is loaded and kept running around the player, in
	 * chunks. The server runs everything in that radius on every tick, and nothing in it but the arena
	 * matters, so the smaller it is the more ticks a second the fights get. Five is the least the game
	 * allows for the second of the two.
	 */
	private static final int QUIET_DISTANCE = 5;

	private static Properties saved;
	/** The field of view being held while a session runs. */
	private static int heldFov;
	/** Whether the field of view is being held at all (not while training without the screen). */
	private static boolean holding;

	private OptionsGuard() {
	}

	/**
	 * @param fov the vertical field of view to hold the game at
	 */
	static void apply(Minecraft mc, int fov) {
		apply(mc, fov, false);
	}

	/**
	 * Training without the screen: nothing the agent sees depends on the options, so only two are
	 * touched. The game must not pause when the player looks at another window, and it need not
	 * spend the computer's strength on drawing.
	 */
	static void applyQuiet(Minecraft mc) {
		remember(mc);
		Options o = mc.options;
		o.pauseOnLostFocus = false;
		if (o.framerateLimit().get() > QUIET_FPS_LIMIT) {
			o.framerateLimit().set(QUIET_FPS_LIMIT);
		}
		if (o.renderDistance().get() > QUIET_DISTANCE) {
			o.renderDistance().set(QUIET_DISTANCE);
		}
		if (o.simulationDistance().get() > QUIET_DISTANCE) {
			o.simulationDistance().set(QUIET_DISTANCE);
		}
	}

	/**
	 * @param fov  the vertical field of view to hold the game at
	 * @param fast the game is going to run faster than real time, so it draws as fast as it can
	 */
	static void apply(Minecraft mc, int fov, boolean fast) {
		heldFov = fov;
		remember(mc);
		Options o = mc.options;
		holding = true;

		// Training has to go on while the player uses another window.
		o.pauseOnLostFocus = false;
		// The masks and the learned sense of range both assume one fixed field of view.
		o.fov().set(fov);
		o.fovEffectScale().set(0.0);
		o.damageTiltStrength().set(0.0);
		o.bobView().set(false);
		o.toggleSprint().set(false);
		o.toggleCrouch().set(false);
		o.autoJump().set(false);
		// Uncapped-ish frames keep every decision on a fresh image; vsync would add a frame of delay.
		o.enableVsync().set(false);
		o.framerateLimit().set(fast ? UNLIMITED_FPS : TRAINING_FPS_LIMIT);
		// The default setting throttles the game to a crawl when nobody touches the keyboard. (The
		// throttle for a minimised window is not an option; FramerateLimitTrackerMixin lifts that one.)
		o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		o.particles().set(ParticleStatus.MINIMAL);
		o.cloudStatus().set(CloudStatus.OFF);
		o.setCameraType(CameraType.FIRST_PERSON);
		// Fog mixes its own colour into everything, and in rain it does so from arm's length on. The
		// colour key only stands a little of that, and the arena it was learned in has clear weather.
		setFog(false);
		// The HUD needs no setting: the agent's picture is taken before the hand and the HUD are drawn.
	}

	/**
	 * The game's own switch for fog (the one its debug key flips). It is not an option and is not
	 * saved anywhere: a game that is closed while it is off starts with fog again.
	 */
	private static void setFog(boolean on) {
		if (FogRenderer.toggleFog() != on) {
			FogRenderer.toggleFog();
		}
	}

	/** Notes the options as they are now, once, in memory and on disk. */
	private static void remember(Minecraft mc) {
		Options o = mc.options;
		if (saved == null) {
			Properties p = new Properties();
			p.setProperty("pauseOnLostFocus", Boolean.toString(o.pauseOnLostFocus));
			p.setProperty("fov", Integer.toString(o.fov().get()));
			p.setProperty("fovEffectScale", Double.toString(o.fovEffectScale().get()));
			p.setProperty("damageTiltStrength", Double.toString(o.damageTiltStrength().get()));
			p.setProperty("bobView", Boolean.toString(o.bobView().get()));
			p.setProperty("toggleSprint", Boolean.toString(o.toggleSprint().get()));
			p.setProperty("toggleCrouch", Boolean.toString(o.toggleCrouch().get()));
			p.setProperty("autoJump", Boolean.toString(o.autoJump().get()));
			p.setProperty("enableVsync", Boolean.toString(o.enableVsync().get()));
			p.setProperty("framerateLimit", Integer.toString(o.framerateLimit().get()));
			p.setProperty("inactivityFpsLimit", o.inactivityFpsLimit().get().name());
			p.setProperty("particles", o.particles().get().name());
			p.setProperty("cloudStatus", o.cloudStatus().get().name());
			p.setProperty("cameraType", o.getCameraType().name());
			p.setProperty("renderDistance", Integer.toString(o.renderDistance().get()));
			p.setProperty("simulationDistance", Integer.toString(o.simulationDistance().get()));
			saved = p;
			writeBackup(p);
		}
	}

	/**
	 * Every tick of a session: puts the field of view back if it was changed in the options meanwhile.
	 * A network that has learned how large an opponent looks at each range is lost on any other.
	 */
	static void hold(Minecraft mc) {
		if (saved == null || !holding) {
			return;
		}
		Options o = mc.options;
		if (o.fov().get() != heldFov) {
			o.fov().set(heldFov);
		}
		if (o.fovEffectScale().get() != 0.0) {
			o.fovEffectScale().set(0.0);
		}
	}

	static void restore(Minecraft mc) {
		if (saved == null) {
			return;
		}
		restoreFrom(mc, saved);
		if (holding) {
			setFog(true);
		}
		saved = null;
		holding = false;
		try {
			Files.deleteIfExists(SwordFiles.optionsBackup());
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not delete the options backup", e);
		}
	}

	/** Called once at startup: undoes training settings left behind by a session that never got to stop. */
	static void recoverAfterCrash(Minecraft mc) {
		Path file = SwordFiles.optionsBackup();
		if (saved != null || !Files.exists(file)) {
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
			restoreFrom(mc, p);
			mc.options.save();
			Files.deleteIfExists(file);
			SwordAi.LOGGER.info("SwordAI restored the game options from an interrupted session");
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not restore options from " + file, e);
		}
	}

	private static void restoreFrom(Minecraft mc, Properties p) {
		Options o = mc.options;
		o.pauseOnLostFocus = Boolean.parseBoolean(p.getProperty("pauseOnLostFocus", "true"));
		o.fov().set(Integer.parseInt(p.getProperty("fov", "70")));
		o.fovEffectScale().set(Double.parseDouble(p.getProperty("fovEffectScale", "1.0")));
		o.damageTiltStrength().set(Double.parseDouble(p.getProperty("damageTiltStrength", "1.0")));
		o.bobView().set(Boolean.parseBoolean(p.getProperty("bobView", "true")));
		o.toggleSprint().set(Boolean.parseBoolean(p.getProperty("toggleSprint", "false")));
		o.toggleCrouch().set(Boolean.parseBoolean(p.getProperty("toggleCrouch", "false")));
		o.autoJump().set(Boolean.parseBoolean(p.getProperty("autoJump", "false")));
		o.enableVsync().set(Boolean.parseBoolean(p.getProperty("enableVsync", "true")));
		o.framerateLimit().set(Integer.parseInt(p.getProperty("framerateLimit", "120")));
		o.inactivityFpsLimit().set(InactivityFpsLimit.valueOf(p.getProperty("inactivityFpsLimit", InactivityFpsLimit.AFK.name())));
		o.particles().set(ParticleStatus.valueOf(p.getProperty("particles", ParticleStatus.ALL.name())));
		o.cloudStatus().set(CloudStatus.valueOf(p.getProperty("cloudStatus", CloudStatus.FANCY.name())));
		o.setCameraType(CameraType.valueOf(p.getProperty("cameraType", CameraType.FIRST_PERSON.name())));
		// Not in a backup written by an earlier version: then they were never changed.
		if (p.containsKey("renderDistance")) {
			o.renderDistance().set(Integer.parseInt(p.getProperty("renderDistance")));
		}
		if (p.containsKey("simulationDistance")) {
			o.simulationDistance().set(Integer.parseInt(p.getProperty("simulationDistance")));
		}
	}

	private static void writeBackup(Properties p) {
		try {
			Files.createDirectories(SwordFiles.dir());
			try (Writer w = Files.newBufferedWriter(SwordFiles.optionsBackup(), StandardCharsets.UTF_8)) {
				p.store(w, "Game options as they were before SwordAI changed them");
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not write the options backup", e);
		}
	}
}
