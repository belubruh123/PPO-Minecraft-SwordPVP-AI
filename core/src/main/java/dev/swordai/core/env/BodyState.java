package dev.swordai.core.env;

import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.vision.ViewGeometry;

/** Client-observable body and opponent state packed into {@link ObsSpec} scalars. */
public final class BodyState {
	public static final float VELOCITY_SCALE = 5f;
	public static final float EVENT_FADE_TICKS = 20f;
	public static final float SWORD_TICKS = 12.5f;
	private static final float HEIGHT_SCALE = 3f;
	private static final float STYLE_RATE = 1f / 60f;

	public float yaw, pitch, height, velRight, velUp, velForward, charge, health, hitsTakenInRow, oppHurt;
	public boolean onGround, sprinting, stale, oppOnGround = true, onTarget;
	public int ticksSinceHitDealt = 1000, ticksSinceHitTaken = 1000, oppTicksSinceSwing = 1000;
	public float oppVelRight, oppVelUp, oppVelForward;
	public int hitsDealtRow, exchangeOwnHits, exchangeOppHits, critChain;
	private boolean oppWasSwinging;
	private int oppLastSwingTime, ticksSinceAnyHit = 1000;
	private float styleAway, styleEdge, styleDist = 0.5f;
	private final float[] view = new float[3];

	public void setHealth(float fraction) {
		health = fraction;
	}

	public void setMotion(double dx, double dy, double dz) {
		ViewGeometry.toYawFrame(dx, dy, dz, yaw, view);
		velRight = view[0]; velUp = view[1]; velForward = view[2];
	}

	public void setOpponentMotion(double dx, double dy, double dz) {
		ViewGeometry.toYawFrame(dx, dy, dz, yaw, view);
		oppVelRight = view[0]; oppVelUp = view[1]; oppVelForward = view[2];
	}

	public void noteOpponentSwing(boolean swinging, int swingTime) {
		boolean began = swinging && (!oppWasSwinging || swingTime < oppLastSwingTime);
		oppTicksSinceSwing = began ? 0 : Math.min(oppTicksSinceSwing + 1, 1000);
		oppWasSwinging = swinging;
		oppLastSwingTime = swingTime;
	}

	public void resetOpponentSwing() {
		oppTicksSinceSwing = 1000;
		oppWasSwinging = false;
		oppLastSwingTime = 0;
		oppOnGround = true;
		oppHurt = 0f;
		onTarget = false;
		hitsDealtRow = exchangeOwnHits = exchangeOppHits = critChain = 0;
		ticksSinceAnyHit = 1000;
		styleAway = styleEdge = 0f;
		styleDist = 0.5f;
	}

	public void updateExchange(boolean dealtNow, boolean dealtCrit, boolean takenNow) {
		if (dealtNow || takenNow) {
			if (ticksSinceAnyHit >= 20) exchangeOwnHits = exchangeOppHits = critChain = 0;
			ticksSinceAnyHit = 0;
		} else if (ticksSinceAnyHit < 1000 && ++ticksSinceAnyHit == 20) {
			exchangeOwnHits = exchangeOppHits = critChain = 0;
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

	public float opponentCharge() {
		return Math.min(1f, oppTicksSinceSwing / SWORD_TICKS);
	}

	public static float apparentDistance(float[] v) {
		if (v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f) return Float.MAX_VALUE;
		float h = v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] - v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM];
		return h <= 1e-4f ? Float.MAX_VALUE : ViewGeometry.apparentSize(1.8f, 1f) / h;
	}

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
		ObsBuilder.writeScalar(v, ObsSpec.SC_SINCE_HIT_DEALT, Math.max(0f, 1f - ticksSinceHitDealt / EVENT_FADE_TICKS));
		ObsBuilder.writeScalar(v, ObsSpec.SC_SINCE_HIT_TAKEN, Math.max(0f, 1f - ticksSinceHitTaken / EVENT_FADE_TICKS));
		ObsBuilder.writeScalar(v, ObsSpec.SC_HITS_TAKEN_ROW, hitsTakenInRow);
		ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_CHARGE, opponentCharge());
		ObsBuilder.writeScalar(v, ObsSpec.SC_FRAME_STALE, stale ? 1f : 0f);
		ObsBuilder.writeScalar(v, ObsSpec.SC_OPP_HURT, oppHurt);
		ObsBuilder.writeScalar(v, ObsSpec.SC_ON_TARGET, onTarget ? 1f : 0f);
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
			float dist = Math.min(8f, apparentDistance(v));
			float away = oppVelForward * VELOCITY_SCALE;
			boolean edge = dist >= 3f && dist <= 4.6f && Math.abs(away) < 0.3f;
			styleAway += STYLE_RATE * (away - styleAway);
			styleEdge += STYLE_RATE * ((edge ? 1f : 0f) - styleEdge);
			styleDist += STYLE_RATE * (dist / 8f - styleDist);
		}
		ObsBuilder.writeScalar(v, ObsSpec.SC_STYLE_AWAY, styleAway);
		ObsBuilder.writeScalar(v, ObsSpec.SC_STYLE_EDGE, styleEdge);
		ObsBuilder.writeScalar(v, ObsSpec.SC_STYLE_DIST, styleDist);
	}
}
