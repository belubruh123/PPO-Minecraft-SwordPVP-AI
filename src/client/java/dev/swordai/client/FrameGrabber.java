package dev.swordai.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.swordai.core.vision.MaskExtractor;
import dev.swordai.core.vision.MaskFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;

/**
 * Reads the rendered frame back from the GPU through the same device calls vanilla's screenshot
 * code uses, so it works on whatever graphics backend the game runs on. Read-back is asynchronous:
 * a request made after one frame is delivered a frame or so later, and at most one is in flight.
 * Everything here runs on the render thread.
 */
public final class FrameGrabber {
	/** No point reading back faster than this at normal speed; the agent decides twenty times a second. */
	private static final long MIN_INTERVAL_NANOS = 8_000_000L;

	private final MaskExtractor extractor = new MaskExtractor();
	private GpuBuffer gpuBuffer;
	private int gpuWidth;
	private int gpuHeight;
	private boolean inFlight;
	private boolean enabled;
	private float crop = 1f;
	private long minIntervalNanos = MIN_INTERVAL_NANOS;
	private long lastRequestNanos;

	private ByteBuffer latest;
	private int latestWidth;
	private int latestHeight;
	private long sequence;
	private long latestRequestNanos;
	private float latestYaw;
	private float latestPitch;
	private double latestX;
	private double latestY;
	private double latestZ;
	private float latestPartial;

	private long deliveries;
	private double latencyMillisSum;
	private String failure;

	/**
	 * While the opponent's own skin is shown (see {@link KeyedSkin#setShowReal}): a copy of the agent's
	 * picture, made the moment it is complete. The game then draws the real skin over the original, and
	 * a read-back that a graphics driver carries out later than it was asked for would hand the agent
	 * that one. The copy is nobody else's, so it cannot change under the read-back.
	 */
	private GpuTexture copy;
	private int copyWidth;
	private int copyHeight;
	private long copiedGrabs;
	private long directGrabs;
	private String copyFailure;

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Uses only the middle of each frame, this fraction of its height: the picture of a narrower field
	 * of view than the one on screen. See {@code ViewGeometry.cropFor}.
	 */
	public void setCrop(float crop) {
		this.crop = crop;
	}

	/** The game runs at this many times its normal speed: a tick is that much shorter, so frames are read back that much more often. */
	public void setSpeed(float speed) {
		minIntervalNanos = (long) (MIN_INTERVAL_NANOS / Math.max(1f, speed));
	}

	/** Non-null once reading the frame back has failed; the session reports it and stops. */
	public String failure() {
		return failure;
	}

	/**
	 * Called once per rendered frame, at the point where the main render target holds the finished
	 * world and nothing drawn on top of it (hand, HUD, open screens).
	 */
	public void worldDrawn(Minecraft mc) {
		if (!enabled || inFlight || failure != null) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastRequestNanos < minIntervalNanos) {
			return;
		}
		try {
			RenderTarget target = mc.getMainRenderTarget();
			GpuTexture texture = target.getColorTexture();
			int width = target.width;
			int height = target.height;
			if (texture == null || width <= 0 || height <= 0) {
				return;
			}
			int pixelSize = texture.getFormat().pixelSize();
			if (pixelSize != 4) {
				failure = "unexpected frame format " + texture.getFormat() + " (" + pixelSize + " bytes per pixel)";
				return;
			}
			if (gpuBuffer == null || gpuWidth != width || gpuHeight != height) {
				closeGpuBuffer();
				gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "SwordAI frame read-back",
						GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) width * height * pixelSize);
				gpuWidth = width;
				gpuHeight = height;
			}
			final GpuBuffer destination = gpuBuffer;
			// The angles this frame was drawn with. While the agent turns, the picture glides towards
			// the player's real rotation between ticks, so the two are not the same.
			Camera camera = mc.gameRenderer.getMainCamera();
			final float yaw = camera.yRot();
			final float pitch = camera.xRot();
			final Vec3 eye = camera.position();
			final float partial = camera.getPartialTickTime();
			GpuTexture source = KeyedSkin.showReal() ? ownCopy(texture, width, height) : texture;
			if (source == texture) {
				directGrabs++;
			} else {
				copiedGrabs++;
			}
			inFlight = true;
			lastRequestNanos = now;
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(source, destination, 0L,
					() -> deliver(destination, width, height, now, yaw, pitch, eye, partial), 0);
		} catch (RuntimeException e) {
			inFlight = false;
			failure = "frame read-back failed: " + e;
		}
	}

	private void deliver(GpuBuffer source, int width, int height, long requestedNanos, float yaw, float pitch, Vec3 eye, float partial) {
		inFlight = false;
		if (source.isClosed()) {
			return;
		}
		try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(source, true, false)) {
			int bytes = width * height * 4;
			ByteBuffer data = view.data().duplicate();
			if (data.capacity() < bytes) {
				failure = "frame read-back returned " + data.capacity() + " bytes, expected " + bytes;
				return;
			}
			if (latest == null || latest.capacity() != bytes) {
				latest = ByteBuffer.allocateDirect(bytes);
			}
			data.position(0).limit(bytes);
			latest.clear();
			latest.put(data);
			latest.flip();
			latestWidth = width;
			latestHeight = height;
			latestRequestNanos = requestedNanos;
			latestYaw = yaw;
			latestPitch = pitch;
			latestX = eye.x;
			latestY = eye.y;
			latestZ = eye.z;
			latestPartial = partial;
			sequence++;
			deliveries++;
			latencyMillisSum += (System.nanoTime() - requestedNanos) / 1_000_000.0;
		} catch (RuntimeException e) {
			failure = "frame read-back failed: " + e;
		}
	}

	/** Counts up with every delivered frame. */
	public long sequence() {
		return sequence;
	}

	/** Extracts the masks from the newest delivered frame. Returns false when there is none yet. */
	public boolean extract(MaskFrame out) {
		if (latest == null) {
			return false;
		}
		// OpenGL read-back order: row 0 is the bottom of the image.
		extractor.extract(latest, latestWidth, latestHeight, true, crop, out);
		out.sequence = sequence;
		out.requestedNanos = latestRequestNanos;
		out.viewYaw = latestYaw;
		out.viewPitch = latestPitch;
		out.viewX = latestX;
		out.viewY = latestY;
		out.viewZ = latestZ;
		out.partialTick = latestPartial;
		return true;
	}

	/** Mean time from request to delivery since the last call, in milliseconds. */
	public float takeMeanLatencyMillis() {
		float mean = deliveries == 0 ? 0f : (float) (latencyMillisSum / deliveries);
		deliveries = 0;
		latencyMillisSum = 0;
		return mean;
	}

	/** The newest raw frame (R,G,B,A bytes, bottom row first), or null. For the debug dump only. */
	public ByteBuffer rawFrame() {
		return latest;
	}

	public int rawWidth() {
		return latestWidth;
	}

	public int rawHeight() {
		return latestHeight;
	}

	/**
	 * Copies the picture as it is right now into a texture of this class's own and returns that; if
	 * that cannot be done here, the picture itself.
	 */
	private GpuTexture ownCopy(GpuTexture texture, int width, int height) {
		if (copyFailure != null) {
			return texture;
		}
		try {
			if (copy == null || copy.isClosed() || copyWidth != width || copyHeight != height || copy.getFormat() != texture.getFormat()) {
				closeCopy();
				copy = RenderSystem.getDevice().createTexture(() -> "SwordAI frame copy",
						GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, texture.getFormat(), width, height, 1, 1);
				copyWidth = width;
				copyHeight = height;
			}
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(texture, copy, 0, 0, 0, 0, 0, width, height);
			return copy;
		} catch (RuntimeException e) {
			copyFailure = e.toString();
			return texture;
		}
	}

	/** For the diagnosis when showing the skin goes wrong: how the pictures were taken while it was on. */
	public String grabCounts() {
		return copiedGrabs + " pictures taken from a copy, " + directGrabs + " straight"
				+ (copyFailure == null ? "" : " (the copy could not be made: " + copyFailure + ")");
	}

	public void close() {
		enabled = false;
		closeGpuBuffer();
		closeCopy();
		latest = null;
	}

	private void closeCopy() {
		if (copy != null && !copy.isClosed()) {
			copy.close();
		}
		copy = null;
	}

	private void closeGpuBuffer() {
		if (gpuBuffer != null && !gpuBuffer.isClosed()) {
			gpuBuffer.close();
		}
		gpuBuffer = null;
	}
}
