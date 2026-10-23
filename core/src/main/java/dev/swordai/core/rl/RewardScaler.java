package dev.swordai.core.rl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Divides rewards by a running estimate of the standard deviation of the discounted return, so the
 * critic sees similar magnitudes in every curriculum stage.
 */
public final class RewardScaler {
	private static final float CLIP = 10f;

	private final float gamma;
	private double running;
	private double mean;
	private double var = 1.0;
	private double count = 1e-4;

	public RewardScaler(float gamma) {
		this.gamma = gamma;
	}

	public float scale(float reward, boolean episodeEnded) {
		running = running * gamma + reward;
		double delta = running - mean;
		double total = count + 1.0;
		mean += delta / total;
		var = (var * count + delta * (running - mean)) / total;
		count = total;
		if (episodeEnded) {
			running = 0;
		}
		float scaled = (float) (reward / Math.sqrt(var + 1e-8));
		return Math.max(-CLIP, Math.min(CLIP, scaled));
	}

	/** A reward scaled as things stand, without counting it: for steps that are not the network's own (DemoFile). */
	public float scaleOnly(float reward) {
		float scaled = (float) (reward / Math.sqrt(var + 1e-8));
		return Math.max(-CLIP, Math.min(CLIP, scaled));
	}

	public void write(DataOutputStream out) throws IOException {
		out.writeDouble(mean);
		out.writeDouble(var);
		out.writeDouble(count);
	}

	public void read(DataInputStream in) throws IOException {
		mean = in.readDouble();
		var = in.readDouble();
		count = in.readDouble();
		running = 0;
	}
}
