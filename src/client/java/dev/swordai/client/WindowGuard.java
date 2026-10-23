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
		} catch (RuntimeException e) {
			SwordAi.LOGGER.warn("could not make the game window small; training goes on at its present size", e);
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
