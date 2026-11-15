package dev.swordai.client;

import com.mojang.blaze3d.platform.Window;
import dev.swordai.SwordAi;
import dev.swordai.core.rl.ObsSpec;
import net.minecraft.client.Minecraft;

/**
 * Makes the game window small while training runs faster than real time, and puts it back
 * afterwards. The agent's picture is made from 270 rows of the frame however large the window is
 * (see {@code MaskExtractor}); everything beyond that is drawn for nobody. A small window draws
 * several times as many frames, which is what a shorter tick needs.
 *
 * <p>Client thread only. Not written to disk: after a crash the game opens at its usual size.
 */
final class WindowGuard {
	/** Rows of a frame the picture is made from. */
	private static final int PICTURE_ROWS = 270;
	/** Never smaller than this on screen, whatever the display's pixel density. */
	private static final int MIN_SCREEN_HEIGHT = 180;

	private static boolean shrunk;
	private static boolean wasFullscreen;
	private static int oldWidth;
	private static int oldHeight;

	private WindowGuard() {
	}

	/**
	 * @param crop the middle part of each frame the picture uses, as a fraction of its height
	 * @param rows the height to draw at, in rows; 0 or less means the least the picture needs
	 */
	static void shrink(Minecraft mc, float crop, int rows) {
		if (shrunk) {
			return;
		}
		try {
			Window window = mc.getWindow();
			wasFullscreen = window.isFullscreen();
			if (wasFullscreen) {
				mc.options.fullscreen().set(false);
			}
			oldWidth = window.getScreenWidth();
			oldHeight = window.getScreenHeight();
			// Frame pixels per screen unit: 2 on a high-density display, 1 elsewhere.
			float density = oldWidth <= 0 ? 1f : Math.max(1f, window.getWidth() / (float) oldWidth);
			int needed = (int) Math.ceil(PICTURE_ROWS / Math.max(0.05f, Math.min(1f, crop)));
			int frameRows = Math.max(needed, rows);
			int height = Math.max(MIN_SCREEN_HEIGHT, (int) Math.ceil(frameRows / density));
			int width = (int) Math.ceil(height * ObsSpec.ASPECT);
			if (height >= oldHeight && !wasFullscreen) {
				// Already as small as that.
				return;
			}
			window.setWindowed(width, height);
			shrunk = true;
			smallWidth = width;
			smallHeight = height;
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("could not make the game window small; training goes on at its present size", e);
		}
	}

	/** The size {@link #shrink} set, in screen units. */
	private static int smallWidth;
	private static int smallHeight;
	private static long lastLookNanos;
	/** Times the window was found grown back (maximized, full screen or over 1.5x the size set) and made small again. */
	private static int reshrinks;

	static int reshrinks() {
		return reshrinks;
	}

	/**
	 * Run 105 (agent 46): keeps the window small while training. At 16:40:56 on 15 Nov the display was reconnected (the sound
	 * device switched to the TV) and Windows maximized the shrunk window: 1280x649 screen units at 300% scaling, a frame about
	 * 3840x1950 instead of about 960x540, drawn and read back every frame on the shared graphics chip. From the next update on the
	 * network's camera disagreed with the installed model's far more (installed ce 0.45-0.49 -> 0.57-0.74), "turned to find them"
	 * rose 300-430 -> 680-1170 an update and the off-body camera in their combo 6 of 41 -> 46 of 99, with no code change. Looked at
	 * every 5 seconds; nothing is done while the window stays as set.
	 */
	static void keepSmall(Minecraft mc) {
		if (!shrunk || smallHeight <= 0) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastLookNanos < 5_000_000_000L) {
			return;
		}
		lastLookNanos = now;
		try {
			Window window = mc.getWindow();
			long handle = window.handle();
			boolean maximized = handle != 0L
					&& org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_MAXIMIZED) == org.lwjgl.glfw.GLFW.GLFW_TRUE;
			boolean grown = window.getScreenHeight() > smallHeight * 3 / 2 || window.getScreenWidth() > smallWidth * 3 / 2;
			if (!maximized && !grown && !window.isFullscreen()) {
				return;
			}
			int fromW = window.getScreenWidth();
			int fromH = window.getScreenHeight();
			if (window.isFullscreen()) {
				mc.options.fullscreen().set(false);
			}
			if (maximized) {
				org.lwjgl.glfw.GLFW.glfwRestoreWindow(handle);
			}
			window.setWindowed(smallWidth, smallHeight);
			reshrinks++;
			SwordAi.LOGGER.warn("run 105: the game window had grown back to {}x{} (maximized {}); made small again at {}x{} ({} times)",
					fromW, fromH, maximized, smallWidth, smallHeight, reshrinks);
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("could not make the game window small again", e);
		}
	}

	static void restore(Minecraft mc) {
		if (!shrunk) {
			return;
		}
		shrunk = false;
		try {
			mc.getWindow().setWindowed(oldWidth, oldHeight);
			if (wasFullscreen) {
				mc.options.fullscreen().set(true);
			}
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("could not put the game window back to its size", e);
		}
	}
}
