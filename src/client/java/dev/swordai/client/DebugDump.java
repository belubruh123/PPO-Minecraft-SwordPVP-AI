package dev.swordai.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.vision.MaskFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Saves exactly what the agent was shown: the raw frame, the masks extracted from it and the mask
 * statistics. When something looks wrong with the agent's vision these three files say why.
 */
final class DebugDump {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmmss-SSS");
	private static final int CELL = 8;
	private static final int GAP = 8;
	private static final int BACKGROUND = 0xFF202020;

	private DebugDump() {
	}

	/** Writes the files and returns the folder they are in. */
	static Path dump(FrameGrabber grabber, MaskFrame frame) throws IOException {
		Path dir = SwordFiles.debugDir();
		Files.createDirectories(dir);
		String stamp = LocalDateTime.now().format(STAMP);

		ByteBuffer raw = grabber.rawFrame();
		int w = grabber.rawWidth();
		int h = grabber.rawHeight();
		if (raw != null && w > 0 && h > 0) {
			try (NativeImage image = new NativeImage(w, h, false)) {
				for (int y = 0; y < h; y++) {
					// The read-back is bottom row first; PNG wants the top row first.
					int rowStart = (h - 1 - y) * w * 4;
					for (int x = 0; x < w; x++) {
						int i = rowStart + x * 4;
						int r = raw.get(i) & 0xFF;
						int g = raw.get(i + 1) & 0xFF;
						int b = raw.get(i + 2) & 0xFF;
						image.setPixelABGR(x, y, 0xFF000000 | b << 16 | g << 8 | r);
					}
				}
				image.writeToFile(dir.resolve("frame-" + stamp + ".png"));
			}
		}

		int periW = ObsSpec.PERI_W * CELL;
		int periH = ObsSpec.PERI_H * CELL;
		int foveaSide = ObsSpec.FOVEA * CELL;
		try (NativeImage image = new NativeImage(periW + GAP + foveaSide, Math.max(periH, foveaSide), false)) {
			fill(image, 0, 0, periW + GAP + foveaSide, Math.max(periH, foveaSide), BACKGROUND);
			paint(image, frame.peri, ObsSpec.PERI_W, ObsSpec.PERI_H, 0);
			paint(image, frame.fovea, ObsSpec.FOVEA, ObsSpec.FOVEA, periW + GAP);
			image.writeToFile(dir.resolve("mask-" + stamp + ".png"));
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "frame %dx%d, head samples %d, body samples %d%n", frame.width, frame.height, frame.headSamples, frame.bodySamples));
		String[] names = {"headVisible", "headX", "headY", "headXMid", "headYMid", "headXFine", "headYFine", "headArea",
				"anyVisible", "anyX", "anyY", "left", "right", "top", "bottom", "anyArea"};
		for (int i = 0; i < ObsSpec.STATS; i++) {
			sb.append(String.format(Locale.ROOT, "%-12s %.4f%n", names[i], frame.stats[i]));
		}
		Files.writeString(dir.resolve("stats-" + stamp + ".txt"), sb.toString(), StandardCharsets.UTF_8);
		return dir;
	}

	/** Solid rectangle; the colour is A,B,G,R from the high byte down, as {@code setPixelABGR} expects. */
	private static void fill(NativeImage image, int x0, int y0, int width, int height, int abgr) {
		for (int y = y0; y < y0 + height; y++) {
			for (int x = x0; x < x0 + width; x++) {
				image.setPixelABGR(x, y, abgr);
			}
		}
	}

	/** Draws one mask: head coverage in blue, body coverage in green, one square per cell. */
	private static void paint(NativeImage image, byte[] mask, int cols, int rows, int xOffset) {
		for (int cy = 0; cy < rows; cy++) {
			for (int cx = 0; cx < cols; cx++) {
				int i = (cy * cols + cx) * ObsSpec.CHANNELS;
				int head = mask[i + ObsSpec.CH_HEAD] & 0xFF;
				int body = mask[i + ObsSpec.CH_BODY] & 0xFF;
				if (head == 0 && body == 0) {
					continue;
				}
				fill(image, xOffset + cx * CELL, cy * CELL, CELL, CELL, 0xFF000000 | head << 16 | body << 8);
			}
		}
	}
}
