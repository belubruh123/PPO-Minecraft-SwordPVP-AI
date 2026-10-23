package dev.swordai.server;

import dev.swordai.core.env.StepFacts;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a fighter's crosshair is in relation to its opponent: ground truth, used only to score it.
 * The same sums whichever body the fighter has; the caller supplies the opponent as that body's
 * client shows it.
 */
public final class Sightline {
	private static final float HEAD_HALF_WIDTH = 0.25f;
	private static final float HEAD_HEIGHT = 0.5f;
	private static final double RAY_LENGTH = 64.0;
	/** Off the hitbox is never scored as closer than this, however the angles round. */
	private static final float MIN_MISS_DEG = 0.2f;

	private Sightline() {
	}

	/**
	 * Fills the geometric part of {@code facts}.
	 *
	 * @param eye       the fighter's eyes
	 * @param look      the direction it is looking in, as a unit vector
	 * @param box       the opponent's hitbox
	 * @param botEyeY   the height of the opponent's eyes
	 */
	public static void fill(StepFacts facts, Vec3 eye, float yaw, float pitch, Vec3 look, AABB box, double botX, double botEyeY, double botZ) {
		double hx = botX - eye.x;
		double hy = botEyeY - eye.y;
		double hz = botZ - eye.z;

		facts.yawErrDeg = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(hx, hz) - yaw);
		facts.pitchErrDeg = ViewGeometry.pitchTo(hx, hy, hz) - pitch;
		double flat = Math.sqrt(hx * hx + hz * hz);
		facts.yawOnHead = Math.abs(facts.yawErrDeg) <= Math.toDegrees(Math.atan2(HEAD_HALF_WIDTH, flat));

		Vec3 far = eye.add(look.scale(RAY_LENGTH));
		AABB head = new AABB(botX - HEAD_HALF_WIDTH, box.maxY - HEAD_HEIGHT, botZ - HEAD_HALF_WIDTH,
				botX + HEAD_HALF_WIDTH, box.maxY, botZ + HEAD_HALF_WIDTH);
		facts.onHead = head.contains(eye) || head.clip(eye, far).isPresent();
		facts.onBody = box.contains(eye) || box.clip(eye, far).isPresent();
		// How far the crosshair is outside the hitbox, as angles: sideways past its half width, and
		// above its top or below its feet. (Positive pitch looks down, so the top has the smaller pitch.)
		float halfWidth = (float) Math.toDegrees(Math.atan2(box.getXsize() * 0.5, flat));
		facts.yawMissDeg = Math.max(0f, Math.abs(facts.yawErrDeg) - halfWidth);
		float top = ViewGeometry.pitchTo(hx, box.maxY - eye.y, hz);
		float bottom = ViewGeometry.pitchTo(hx, box.minY - eye.y, hz);
		float pitchMiss = Math.max(0f, Math.max(top - pitch, pitch - bottom));
		facts.missDeg = facts.onBody ? 0f : Math.max(MIN_MISS_DEG, (float) Math.hypot(facts.yawMissDeg, pitchMiss));
		facts.reachDist = (float) Math.sqrt(box.distanceToSqr(eye));
	}
}
