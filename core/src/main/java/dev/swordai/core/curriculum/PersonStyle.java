package dev.swordai.core.curriculum;

/**
 * How one real opponent fights, as counted in {@code /swordai fight} from what the user's own game
 * could see, summed over every fight against that player; and the scripted opponent made from it,
 * which training then fights ({@code StageSpec.peopleShare}). Not a recording of the fights: a
 * recording does not answer what the fighter does differently, so what is kept is the habits, and
 * the script plays them out against whatever the fighter does.
 *
 * <p>Saved as {@code swordai/people/<name>.json}: a few hundred bytes a player.
 */
public final class PersonStyle {
	public String name = "";
	public int fights;
	public int losses;
	public int hitsDealt;
	public int hitsTaken;
	/** Ticks counted in all, and of those with both on the ground and no hit for a while: where the distance it keeps is read. */
	public long ticks;
	public long quietTicks;
	public double quietDistanceSum;
	/** Of the hits it made, how many came on its way down: critical hits. */
	public int critsTaken;
	/** Of the fighter's hits, how many it answered within three to thirteen ticks, and how many of those on its way down. */
	public int answered;
	public int punishCrits;
	/** Times the fighter went back from it (S or turning away), and how many of those it followed with a hit from the air. */
	public int backOffs;
	public int chasedCrits;
	/** Hits of the fighter's after which it was seen going away, and how fast: the knockback it takes. */
	public int knockSamples;
	public double knockSpeedSum;
	/** Of the fighter's hits on it while it stood, how many it jumped with. */
	public int groundHitsTaken;
	public int jumpsWithHit;
	/** Ticks in which it was moving across the fighter's view rather than to or from it. */
	public long strafeTicks;
	/** Its connection, where the server told: milliseconds, summed over the fights that had it. */
	public int pingFights;
	public long pingSum;

	/** The distance it keeps, eye to hitbox, or the scripted opponent's own when too little is known. */
	public float holdDistance() {
		return quietTicks >= 40 ? (float) (quietDistanceSum / quietTicks) : 2.9f;
	}

	public float critShare() {
		return hitsTaken > 0 ? (float) critsTaken / hitsTaken : 0f;
	}

	public float punishShare() {
		return hitsDealt > 0 ? (float) punishCrits / hitsDealt : 0f;
	}

	public float chaseShare() {
		return backOffs > 0 ? (float) chasedCrits / backOffs : 0f;
	}

	/** The share of a full knockback it takes, read off how fast it goes away after a sprint hit (0.55 a tick at its full). */
	public float knockback() {
		return knockSamples >= 5 ? clamp((float) (knockSpeedSum / knockSamples) / 0.55f, 0.1f, 1f) : 1f;
	}

	public float jumpResetShare() {
		return groundHitsTaken >= 5 ? (float) jumpsWithHit / groundHitsTaken : 0f;
	}

	public float strafeShare() {
		return ticks > 0 ? (float) strafeTicks / ticks : 0f;
	}

	/** Ticks of lag each way, from its ping; one if the server never said. */
	public int lagTicks() {
		return pingFights > 0 ? Math.max(0, Math.min(5, Math.round(pingSum / (float) pingFights / 2f / 50f))) : 1;
	}

	/** Whether enough of it has been seen to make a script of. */
	public boolean enough() {
		return hitsDealt + hitsTaken >= 30;
	}

	/**
	 * Makes {@code b} the scripted opponent with these habits, a sharp one: what it is in the habit
	 * of doing it does as often as it was seen to, and nothing it was never seen to.
	 */
	public void applyTo(BotProfile b, java.util.Random rng) {
		b.attacks = true;
		b.sprint = true;
		b.reactionTicks = rng.nextInt(2);
		b.aimNoiseDeg = 0.5f + rng.nextFloat();
		b.turnRateDeg = 60f;
		b.preferredRange = clamp(holdDistance(), 2.4f, 4.5f);
		b.critChance = clamp(critShare(), 0f, 1f);
		b.punish = punishShare() >= 0.1f;
		// One that both answers the fighter's hits on its way down and keeps its distance outside the
		// fighter's reach plays on the fighter's rhythm.
		b.timer = b.punish && holdDistance() >= 3.1f;
		b.jumpIn = chaseShare() >= 0.2f;
		b.jumpResetChance = clamp(jumpResetShare(), 0f, 1f);
		b.rushChance = holdDistance() < 2.6f ? 0.5f : 0f;
		b.wtap = !b.timer && holdDistance() >= 2.9f;
		b.dodgeChance = 0f;
		b.fleeChance = 0f;
		b.followUpTicks = b.timer ? 0 : dev.swordai.core.env.TakenStreak.COMBO_TICKS;
		b.followUpReach = 0f;
		b.swingSlip = 0f;
		b.attackChargeMin = 0.92f;
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
