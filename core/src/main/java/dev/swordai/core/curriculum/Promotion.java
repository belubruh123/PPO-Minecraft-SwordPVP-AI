package dev.swordai.core.curriculum;

/**
 * When a stage counts as learned. Every threshold that is set must hold over the recent window;
 * a value of zero (or below) means "not checked".
 */
public final class Promotion {
	/** Steps that must be spent in the stage before promotion is considered at all. */
	public int minSteps = 20_000;
	/** Size of the recent window the metrics are averaged over. */
	public int windowSteps = 12_000;

	/** Fraction of ticks with the crosshair on the head. */
	public float minOnHead = 0f;
	/** Fraction of ticks with the crosshair on the opponent at all. */
	public float minOnTarget = 0f;
	/** Fraction of the time on the opponent that the crosshair rests there instead of sliding about. */
	public float minStill = 0f;
	/** Mean distance of the crosshair from the opponent's hitbox, degrees. */
	public float maxAimError = 0f;
	/** Fraction of ticks inside the stage's range band. */
	public float minInRange = 0f;
	/** Fraction of clicks that had the opponent under the crosshair. */
	public float minClickAccuracy = 0f;
	/** Largest share of swings that may be made before the weapon has recovered to 60% charge. */
	public float maxSpamRate = 0f;
	/** Fraction of landed hits made at 90% charge or more. */
	public float minFullCharge = 0f;
	public float minHitsPerMinute = 0f;
	/** Fraction of landed hits that were sprint-knockback hits. */
	public float minSprintHits = 0f;
	public float minCombo = 0f;
	/** Fraction of landed hits followed by a correct W-tap. */
	public float minTapRate = 0f;
	/** Hits dealt divided by hits taken. */
	public float minHitRatio = 0f;
	/** Fraction of the hits landed from very close that were sprint-knockback hits. */
	public float minCloseSprint = 0f;
	/** Punish crits landed per critical hit taken. */
	public float minPunishRate = 0f;
	/** Fraction of landed hits that were critical hits. */
	public float minCritShare = 0f;
	/** Fraction of its jumps that ended in a critical hit, once it has made enough of them to tell. */
	public float minJumpCritRate = 0f;
	/** Fraction of the ticks just after a hit of the opponent's combo that S was held. */
	public float minBackRate = 0f;
	/** Fraction of the hits of the opponent's combos after which no further hit followed. */
	public float minOutRate = 0f;
	/** Fraction of the time with a strafe plan that the agent strafed that way. */
	public float minMixStrafe = 0f;
	/** Fraction of swings that waited as long as the plan said. */
	public float minMixTiming = 0f;
	/** Share of decided fights won, over the recent window. */
	public float minWinRate = 0f;
	/** Decided fights the window must hold before the win rate counts. */
	public int minFights = 20;
	/** How sharp the sparring bot must have become (see {@code BotProfile.scalesWithSkill}). */
	public float minBotSkill = 0f;
	/**
	 * A habit that is slow to form must not hold up everything after it. Once this many steps have
	 * been played in the stage, the thresholds that are about how it fights rather than whether it
	 * can ({@link #minMixStrafe}, {@link #minMixTiming}, {@link #minPunishRate}, {@link #minCritShare},
	 * {@link #minJumpCritRate}, {@link #minBackRate}, {@link #minOutRate}) no longer have to be met for the stage to be passed;
	 * all the others still do. Whatever those were teaching stays on and paid for in every later stage.
	 * Zero: they always have to be met.
	 */
	public int styleOptionalAfterSteps = 0;
	/** Final stage only: never promote. */
	public boolean endless = false;
}
