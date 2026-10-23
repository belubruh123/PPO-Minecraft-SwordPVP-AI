package dev.swordai.core.rl;

/** Diagnostics from one PPO update. */
public final class UpdateStats {
	public float policyLoss;
	public float valueLoss;
	public float approxKl;
	public float clipFraction;
	/** KL(anchor || network) on the update's states, averaged over its epochs; NaN without an anchor ({@code Config.anchorWeight}). */
	public float anchorKl = Float.NaN;
	/** Fraction of return variance the critic explained before this update (1 = perfect). */
	public float explainedVariance;
	public final float[] entropy = new float[ActionSpace.HEADS];
	/** Actor learning rate after this update's adaptation. */
	public float actorLr;
	/** False when only the critic was trained (warm-up after a stage change). */
	public boolean actorTrained = true;
	public float actorGradNorm;
	public float criticGradNorm;
	public int epochsRun;
	public int samples;
	public long millis;
}
