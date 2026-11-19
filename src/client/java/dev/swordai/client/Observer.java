package dev.swordai.client;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.SightTracker;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.Sightline;
import dev.swordai.server.Truth;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the actor observation (pixels + own/observable body scalars) and training-only privileged
 * critic inputs for one client tick.
 */
final class Observer {
	final MaskFrame frame = new MaskFrame();
	private final SightTracker sight = new SightTracker();
	private final BodyState body = new BodyState();
	private final float[] view = new float[3];
	private long lastSequence = -1;
	private boolean stale;

	void resetEpisode() {
		sight.reset();
		body.resetOpponentSwing();
	}

	BodyState body() {
		return body;
	}

	boolean capture(FrameGrabber grabber) {
		if (!grabber.extract(frame)) {
			return false;
		}
		stale = frame.sequence == lastSequence;
		lastSequence = frame.sequence;
		return true;
	}

	boolean stale() {
		return stale;
	}

	boolean late() {
		return sight.frameIsLate();
	}

	void fillGeometry(StepFacts facts, LocalPlayer me, Player bot) {
		Sightline.fill(facts, me.getEyePosition(), me.getYRot(), me.getXRot(), me.getLookAngle(),
				bot.getBoundingBox(), bot.getX(), bot.getEyeY(), bot.getZ());
		facts.enemyVisible = frame.enemyVisible();
		facts.falling = !me.onGround() && me.getY() < me.yo;
	}

	void fillBody(BodyState b, LocalPlayer me, Player opponent, double floorY) {
		b.yaw = me.getYRot();
		b.pitch = me.getXRot();
		b.height = (float) (me.getY() - floorY);
		b.onGround = me.onGround();
		b.oppOnGround = opponent.onGround();
		b.setMotion(me.getX() - me.xo, me.getY() - me.yo, me.getZ() - me.zo);
		b.setOpponentMotion(opponent.getX() - opponent.xo, opponent.getY() - opponent.yo, opponent.getZ() - opponent.zo);
		b.charge = me.getAttackStrengthScale(0f);
		b.noteOpponentSwing(opponent.swinging, opponent.swingTime);
		b.setHealth(me.getHealth() / Math.max(1f, me.getMaxHealth()));
		b.sprinting = me.isSprinting();
		b.stale = stale;
	}

	void fillActorOnly(Obs obs, LocalPlayer me, Player opponent, double floorY, SessionClock clock,
			AimMotor motor, int[] lastAction, int headMask) {
		fillBody(body, me, opponent, floorY);
		body.ticksSinceHitDealt = clock.ticksSinceHitDealt;
		body.ticksSinceHitTaken = clock.ticksSinceHitTaken;
		body.hitsTakenInRow = clock.takenStreak.shown();
		ObsBuilder.writeActor(obs, frame, sight, body, motor, lastAction, headMask);
	}

	void fillObs(Obs obs, LocalPlayer me, Player bot, Truth truth, StepFacts facts, int[] lastAction,
			int headMask, SessionClock clock, AimMotor motor) {
		fillActorOnly(obs, me, bot, truth.floorY(), clock, motor, lastAction, headMask);
		Vec3 eye = me.getEyePosition();
		ViewGeometry.toYawFrame(bot.getX() - eye.x, bot.getEyeY() - eye.y, bot.getZ() - eye.z, me.getYRot(), view);
		boolean inReach = facts.onBody && facts.reachDist <= me.entityInteractionRange();
		ObsBuilder.writePrivileged(obs.priv, facts, body, view[0], view[1], view[2],
				truth.botHealth(), truth.botCharge(), truth.botSprinting(), truth.botOnGround(), inReach, clock, 0);
	}
}
