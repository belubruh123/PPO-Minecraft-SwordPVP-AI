package dev.swordai.core.vision;

import dev.swordai.core.rl.ObsSpec;

/** What one captured frame boils down to: two enemy masks and a few numbers measured on them. */
public final class MaskFrame {
	public final byte[] peri = new byte[ObsSpec.PERI_SIZE];
	public final byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
	public final float[] stats = new float[ObsSpec.STATS];
	/** Sampled pixels classified as head / body. */
	public int headSamples;
	public int bodySamples;
	/** Size of the frame this was extracted from. */
	public int width;
	public int height;
	/** Set by the capturer: counts up once per captured frame. */
	public long sequence;
	/** Set by the capturer: {@link System#nanoTime()} when the frame was requested. */
	public long requestedNanos;
	/** Set by the capturer: where the camera was pointing when this frame was rendered. */
	public float viewYaw;
	public float viewPitch;
	/** Set by the capturer: where the camera was, and how far through its tick the game was. */
	public double viewX;
	public double viewY;
	public double viewZ;
	public float partialTick;

	public boolean enemyVisible() {
		return stats[ObsSpec.ST_ANY_VISIBLE] > 0f;
	}

	public void copyFrom(MaskFrame o) {
		System.arraycopy(o.peri, 0, peri, 0, peri.length);
		System.arraycopy(o.fovea, 0, fovea, 0, fovea.length);
		System.arraycopy(o.stats, 0, stats, 0, stats.length);
		headSamples = o.headSamples;
		bodySamples = o.bodySamples;
		width = o.width;
		height = o.height;
		sequence = o.sequence;
		requestedNanos = o.requestedNanos;
		viewYaw = o.viewYaw;
		viewPitch = o.viewPitch;
		viewX = o.viewX;
		viewY = o.viewY;
		viewZ = o.viewZ;
		partialTick = o.partialTick;
	}
}
