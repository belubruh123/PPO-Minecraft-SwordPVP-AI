package dev.swordai.client;

import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks, in the real game, that the agent's eyes work before any training time is spent: the bot
 * is stood at known spots and the masks must show it where and as large as geometry predicts, and
 * show nothing at all when it is behind the camera.
 */
final class SelfTest {
	/** {distance in blocks, bearing in degrees}; the last one puts the bot out of sight. */
	private static final float[][] PLACEMENTS = {{3f, 0f}, {6f, 0f}, {5f, 180f}};
	private static final int SETTLE_TICKS = 25;
	private static final int TIMEOUT_TICKS = 240;
	// The opponent as the game draws it: 15/16 of two blocks tall with its hat layer, a quarter block
	// deep, its eyes level with the agent's.
	private static final float MODEL_TOP = 1.906f;
	private static final float EYE_HEIGHT = 1.62f;
	private static final float HEAD_FRONT = 0.264f;
	private static final float BODY_FRONT = 0.132f;
	/** Farther off than this, the picture does not have the field of view the maths assumes. */
	private static final float SIZE_TOLERANCE = 0.08f;

	private final List<String> lines = new ArrayList<>();
	private int step = -1;
	private int ticks;
	private int requestedEpisode;
	private boolean placed;
	private long sequenceAtPlacement;
	private boolean fatal;
	private boolean warned;

	/** Advances the test by one tick. Returns true once it has finished. */
	boolean tick(Minecraft mc, Truth truth, FrameGrabber grabber, Observer observer) {
		if (step < 0) {
			step = 0;
			request(mc, truth);
			return false;
		}
		ticks++;
		if (!placed) {
			if (truth.episodeId() >= requestedEpisode) {
				placed = true;
				ticks = 0;
				sequenceAtPlacement = grabber.sequence();
			} else if (ticks > TIMEOUT_TICKS) {
				fail("the server never placed the bot for the self-test");
				return true;
			}
			return false;
		}
		if (ticks < SETTLE_TICKS) {
			return false;
		}
		// Only judge frames that were rendered well after the bot arrived.
		if (grabber.sequence() - sequenceAtPlacement < 4 || !observer.capture(grabber)) {
			if (ticks > TIMEOUT_TICKS) {
				fail("no frames could be read back from the game (" + (grabber.sequence() - sequenceAtPlacement) + " since placement)");
				return true;
			}
			return false;
		}
		evaluate(mc, observer.frame);
		step++;
		if (fatal || step >= PLACEMENTS.length) {
			if (mc.getFps() < 40) {
				warned = true;
				lines.add("low frame rate (" + mc.getFps() + " fps): the agent will often decide on an old image");
			}
			return true;
		}
		request(mc, truth);
		return false;
	}

	private void request(Minecraft mc, Truth truth) {
		float distance = PLACEMENTS[step][0];
		float bearing = PLACEMENTS[step][1];
		float yaw = mc.player.getYRot();
		requestedEpisode = truth.episodeId() + 1;
		placed = false;
		ticks = 0;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.place(distance, bearing, yaw));
		}
	}

	private void evaluate(Minecraft mc, MaskFrame f) {
		float distance = PLACEMENTS[step][0];
		boolean behind = PLACEMENTS[step][1] != 0f;
		int pixels = f.headSamples + f.bodySamples;
		if (behind) {
			if (pixels > 50) {
				fail(String.format(Locale.ROOT, "with the bot behind the camera %d pixels still look like an opponent "
						+ "(is a shader pack or coloured resource pack active?)", pixels));
			} else if (pixels > 0) {
				warned = true;
				lines.add(pixels + " stray key-coloured pixels with the bot out of view");
			} else {
				lines.add("bot behind the camera: 0 px (good)");
			}
			return;
		}
		if (!f.enemyVisible() || f.headSamples == 0 || f.bodySamples == 0) {
			fail(String.format(Locale.ROOT, "at %.0f blocks the bot was not recognised (head %d px, body %d px, frame %dx%d); "
					+ "run /swordai debug and send the two PNGs", distance, f.headSamples, f.bodySamples, f.width, f.height));
			return;
		}
		float[] s = f.stats;
		float height = s[ObsSpec.ST_TOP] - s[ObsSpec.ST_BOTTOM];
		// Top of the head and soles of the feet, each at the depth of the surface facing the camera.
		float expected = ViewGeometry.apparentSize(MODEL_TOP - EYE_HEIGHT, distance - HEAD_FRONT)
				+ ViewGeometry.apparentSize(EYE_HEIGHT, distance - BODY_FRONT);
		lines.add(String.format(Locale.ROOT, "at %.0f blocks: head at (%.2f, %.2f), height %.2f (expected %.2f), head %d px, body %d px",
				distance, s[ObsSpec.ST_HEAD_X], s[ObsSpec.ST_HEAD_Y], height, expected, f.headSamples, f.bodySamples));
		boolean offCentre = Math.abs(s[ObsSpec.ST_HEAD_X]) > 0.08f || Math.abs(s[ObsSpec.ST_HEAD_Y]) > 0.2f;
		boolean wrongSize = Math.abs(height / expected - 1f) > SIZE_TOLERANCE;
		if (offCentre || wrongSize) {
			warned = true;
			lines.add("  ^ " + (offCentre ? "not centred on the crosshair" : "") + (offCentre && wrongSize ? ", " : "")
					+ (wrongSize ? String.format(Locale.ROOT, "size is off: the picture looks like FOV %.0f, not %.0f (is another mod changing the field of view?)",
							impliedFov(height, expected), ViewGeometry.fov()) : ""));
		}
	}

	/** The field of view at which the opponent would look as large as it was measured. */
	private static float impliedFov(float measured, float expected) {
		double tan = ViewGeometry.tanHalfFov() * expected / Math.max(1e-3f, measured);
		return (float) Math.toDegrees(2.0 * Math.atan(tan));
	}

	private void fail(String message) {
		fatal = true;
		lines.add(message);
	}

	/** True when the vision pipeline is unusable and training must not start. */
	boolean fatal() {
		return fatal;
	}

	String verdict() {
		return fatal ? "FAIL" : warned ? "WARN" : "PASS";
	}

	List<String> lines() {
		return lines;
	}

	/** One paragraph for the report. */
	String summary() {
		return verdict() + " - " + String.join("; ", lines);
	}
}
