package dev.swordai.server;

import dev.swordai.core.league.League;

/** Everything the server needs to start one arena fight or restored key scene. */
public final class EpisodePlan {
	public enum KitMode { SWORD_ONLY, SMP_PHASE1 }

	/** When non-null, this pilot drives the fake opponent on ReplicaBody (self, snapshot, exploiter, human clone). */
	public SparPilot pilot;
	/** Used when {@link #pilot} is null: full-fight sparring archetype. */
	public League.SparStyle sparStyle = League.SparStyle.MIXER;
	/** Multi-kit randomization: SWORD_ONLY (random slot 0..8) vs SMP_PHASE1 (Sword + Axe + Shield + Gaps in random slots). */
	public KitMode kitMode = KitMode.SWORD_ONLY;
	/** When true, shuffle kit items across random hotbar slots 0..8 so the Pointer Network is strictly order-invariant. */
	public boolean randomizeSlots = true;
	/** When true, enable 1-block ascend/descend flat stone platforms in the arena (SMP/UHC terrain curriculum). */
	public boolean terraced = false;
	/** Optional exact arena state captured by {@link SceneRecorder} for key-moment starts or MoveSearch. */
	public double[] scene;
	public float minDistance = 2.6f;
	public float maxDistance = 5.5f;
	public float maxBearingDeg = 35f;
	public int agentLag;
	public int botLag;
	public int botPing;
	public int agentKnockLag;
	public long seed;
	public boolean seedYaw;
	public float startYaw = Float.NaN;
	public volatile float usedYaw = Float.NaN;

	public static EpisodePlan ofPilot(SparPilot pilot, long seed, boolean seedYaw) {
		EpisodePlan p = new EpisodePlan();
		p.pilot = pilot;
		p.seed = seed;
		p.seedYaw = seedYaw;
		return p;
	}

	public static EpisodePlan ofSpar(League.SparStyle style, long seed, boolean seedYaw) {
		EpisodePlan p = new EpisodePlan();
		p.sparStyle = style;
		p.seed = seed;
		p.seedYaw = seedYaw;
		return p;
	}
}
