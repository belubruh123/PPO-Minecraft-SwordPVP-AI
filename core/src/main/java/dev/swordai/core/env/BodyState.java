package dev.swordai.core.env;

import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.vision.ViewGeometry;

/**
 * What a fighter knows about its own body at one moment, however that body is driven: the player's
 * own character on the client, or a fake player on the server. Filling this in is the only part
 * that differs between the two; the layout the network reads is written here, once.
 */
public final class BodyState {
	/** Blocks per tick are small numbers; this brings walking and sprinting speeds to around one. */
	public static final float VELOCITY_SCALE = 5f;
	/** "Just happened" fades to zero over this many ticks. */
	public static final float EVENT_FADE_TICKS = 20f;
	private static final float HEIGHT_SCALE = 3f;

	/** Where the camera points now, degrees. */
	public float yaw;
	public float pitch;
	/** Blocks above the floor being fought on. */
	public float height;
	public boolean onGround;
	/** Own movement over the last tick in the horizontal view frame, blocks. */
	public float velRight;
	public float velUp;
	public float velForward;
	public float charge;
	/** 0..1, as the fighter is shown it; set through {@link #setHealth}. */
	public float health;
	/**
	 * Set for a fighter that plays with what it learned in a stage where it could not lose health.
	 * There it was patched up every tick, so the health it was ever shown is full, less what a hit on
	 * that very tick took. With this set it is shown its health the same way in a real fight, where
	 * nobody patches it up: a health that stays low is something it has never seen and cannot read.
	 */
	public boolean patchedUp;
	private float realHealth = Float.NaN;
	public boolean sprinting;
	public int ticksSinceHitDealt = 1000;
	public int ticksSinceHitTaken = 1000;
	/** Ticks since the opponent's arm was last seen to swing; see {@code ObsSpec.SC_OPP_CHARGE}. */
	public int oppTicksSinceSwing = 1000;
	private boolean oppWasSwinging;
	private int oppLastSwingTime;
	/** Hits taken in a row without landing one, as {@link TakenStreak#shown} gives it: 0..1. */
	public float hitsTakenInRow;
	public boolean stale;
	/** The opponent's movement over the last tick in the same frame; only shown while it is in view. */
	public float oppVelRight;
	public float oppVelUp;
	public float oppVelForward;
	/** Step D: directly observable opponent ground state and exchange/combo/crit-chain counters. */
	public boolean oppOnGround = true;
	public int hitsDealtRow;
	public int exchangeOwnHits;
	public int exchangeOppHits;
	public int critChain;
	private int ticksSinceAnyHit = 1000;

	private final float[] view = new float[3];

	/**
	 * What this opponent has been doing lately (see {@code ObsSpec.SC_STYLE_AWAY} and the two after it):
	 * running averages over about three seconds, started afresh with every fight.
	 */
	static final float STYLE_RATE = 1f / 60f;
	/** Between these distances, and hardly moving, it is standing at the edge of the reach. */
	static final float EDGE_FROM = 3f;
	static final float EDGE_TO = 4.6f;
	static final float EDGE_STILL = 0.3f;
	private float styleAway;
	private float styleEdge;
	private float styleDist = 0.5f;

	/** @param fraction the health the body really has, 0..1 */
	public void setHealth(float fraction) {
		if (patchedUp) {
			float lost = Float.isNaN(realHealth) ? 0f : Math.max(0f, realHealth - fraction);
			health = Math.max(0f, 1f - lost);
		} else {
			health = fraction;
		}
		realHealth = fraction;
	}

	/** Sets own movement from a world-space displacement, using {@link #yaw}. */
	public void setMotion(double dx, double dy, double dz) {
		ViewGeometry.toYawFrame(dx, dy, dz, yaw, view);
		velRight = view[0];
		velUp = view[1];
		velForward = view[2];
	}

	public void setOpponentMotion(double dx, double dy, double dz) {
		ViewGeometry.toYawFrame(dx, dy, dz, yaw, view);
		oppVelRight = view[0];
		oppVelUp = view[1];
		oppVelForward = view[2];
	}

	/** A sword is back this many ticks after a swing. */
	public static final float SWORD_TICKS = 12.5f;

	/**
	 * One tick of watching the opponent's arm: {@code swinging} and {@code swingTime} as the game has
	 * them for that player. A swing has begun when the arm starts swinging, or starts over.
	 */
	public void noteOpponentSwing(boolean swinging, int swingTime) {
		boolean began = swinging && (!oppWasSwinging || swingTime < oppLastSwingTime);
		oppTicksSinceSwing = began ? 0 : Math.min(oppTicksSinceSwing + 1, 1000);
		oppWasSwinging = swinging;
		oppLastSwingTime = swingTime;
	}

	/** A new fight: nothing is known of the opponent's sword or exchange state. */
	public void resetOpponentSwing() {
		oppTicksSinceSwing = 1000;
		oppWasSwinging = false;
		oppLastSwingTime = 0;
		oppOnGround = true;
		hitsDealtRow = 0;
		exchangeOwnHits = 0;
		exchangeOppHits = 0;
		critChain = 0;
		ticksSinceAnyHit = 1000;
		styleAway = 0f;
		styleEdge = 0f;
		styleDist = 0.5f;
	}

	/** Advances exchange timeout and records hits for Step D combo/exchange/crit-chain scalars. */
	public void updateExchange(boolean dealtNow, boolean dealtCrit, boolean takenNow) {
		if (dealtNow || takenNow) {
			if (ticksSinceAnyHit >= 20) {
				exchangeOwnHits = 0;
				exchangeOppHits = 0;
				critChain = 0;
			}
			ticksSinceAnyHit = 0;
		} else if (ticksSinceAnyHit < 1000) {
			ticksSinceAnyHit++;
			if (ticksSinceAnyHit == 20) {
				exchangeOwnHits = 0;
				exchangeOppHits = 0;
				critChain = 0;
			}
		}
		if (dealtNow) {
			hitsDealtRow++;
			exchangeOwnHits++;
			critChain = dealtCrit ? critChain + 1 : 0;
		}
		if (takenNow) {
			hitsDealtRow = 0;
			exchangeOppHits++;
		}
	}

	/** Apparent distance to the opponent estimated solely from their on-screen silhouette height. */
	public static float apparentDistance(float[] v) {
		if (v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f) {
			return Float.MAX_VALUE;
		}
		float h = v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] - v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM];
		return h <= 1e-4f ? Float.MAX_VALUE : ViewGeometry.apparentSize(1.8f, 1f) / h;
	}

	/** The opponent's sword, 0 to 1, as {@code ObsSpec.SC_OPP_CHARGE} has it. */
	public float opponentCharge() {
		return Math.min(1f, oppTicksSinceSwing / SWORD_TICKS);
	}

	/**
	 * Writes the body scalars into an observation vector.
	 *
	 * @param enemyVisible whether any of the opponent is on screen; its relative velocity is something
	 *                     read off the screen, so it is only given while it is
	 */
	public void write(float[] v, boolean enemyVisible) {
		ObsBuilder.writeScalar(v, ObsSpec.SC_HEIGHT, height / HEIGHT_SCALE);
		ObsBuilder.writeScalar(v, ObsSpec.SC_ON_GROUND, onGround ? 1f : 0f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_VEL_FORWARD, velForward * VELOCITY_SCALE);
		ObsBuilder.writeScalar(v, ObsSpec.SC_VEL_RIGHT, velRight * VELOCITY_SCALE);
		ObsBuilder.writeScalar(v, ObsSpec.SC_VEL_UP, velUp * VELOCITY_SCALE);
		ObsBuilder.writeScalar(v, ObsSpec.SC_PITCH, pitch / 90f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_ATTACK_CHARGE, charge);
		ObsBuilder.writeScalar(v, ObsSpec.SC_HEALTH, health);
		ObsBuilder.writeScalar(v, ObsSpec.SC_SPRINTING, sprinting ? 1f : 0f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_SINCE_HIT_DEALT, fade(ticksSinceHitDealt));
		ObsBuilder.writeScalar(v, ObsSpec.SC_SINCE_HIT_TAKEN, fade(ticksSinceHitTaken));
		ObsBuilder.writeScalar(v, ObsSpec.SC_HITS_TAKEN_ROW, hitsTakenInRow);
		ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_CHARGE, opponentCharge());
		ObsBuilder.writeScalar(v, ObsSpec.SC_FRAME_STALE, stale ? 1f : 0f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_OWN_FALLING, (!onGround && velUp < -0.02f) ? 1f : 0f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_HITS_DEALT_ROW, Math.min(5, hitsDealtRow) / 5f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_EXCHANGE_OWN_HITS, Math.min(5, exchangeOwnHits) / 5f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_EXCHANGE_OPP_HITS, Math.min(5, exchangeOppHits) / 5f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_CRIT_CHAIN, Math.min(4, critChain) / 4f);
		if (enemyVisible) {
			ObsBuilder.writeScalar(v, ObsSpec.SC_REL_VEL_FORWARD, (oppVelForward - velForward) * VELOCITY_SCALE);
			ObsBuilder.writeScalar(v, ObsSpec.SC_REL_VEL_RIGHT, (oppVelRight - velRight) * VELOCITY_SCALE);
			ObsBuilder.writeScalar(v, ObsSpec.SC_REL_VEL_UP, (oppVelUp - velUp) * VELOCITY_SCALE);
			ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_ON_GROUND, oppOnGround ? 1f : 0f);
			ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_VEL_UP, oppVelUp * VELOCITY_SCALE);
			ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_FALLING, (!oppOnGround && oppVelUp < -0.02f) ? 1f : 0f);
			// The opponent's ways, from what is seen of it: once a decision, so once a tick.
			float distance = Math.min(8f, apparentDistance(v));
			float away = oppVelForward * VELOCITY_SCALE;
			boolean edge = distance >= EDGE_FROM && distance <= EDGE_TO && Math.abs(away) < EDGE_STILL;
			styleAway += STYLE_RATE * (away - styleAway);
			styleEdge += STYLE_RATE * ((edge ? 1f : 0f) - styleEdge);
			styleDist += STYLE_RATE * (distance / 8f - styleDist);
		}
		ObsBuilder.writeScalar(v, ObsSpec.SC_STYLE_AWAY, styleAway);
		ObsBuilder.writeScalar(v, ObsSpec.SC_STYLE_EDGE, styleEdge);
		ObsBuilder.writeScalar(v, ObsSpec.SC_STYLE_DIST, styleDist);
	}

	private static float fade(int ticksAgo) {
		return Math.max(0f, 1f - ticksAgo / EVENT_FADE_TICKS);
	}
}
