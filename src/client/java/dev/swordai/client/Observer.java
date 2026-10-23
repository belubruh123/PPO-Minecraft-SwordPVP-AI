package dev.swordai.client;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.MixIdea;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.SightTracker;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.Sightline;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the two views of a moment in the game: the observation the agent acts on (pixels and its
 * own body) and the ground truth used only to score it. Keeping both in one place makes it easy to
 * check that nothing from the second leaks into the first.
 */
final class Observer {
	final MaskFrame frame = new MaskFrame();
	private final SightTracker sight = new SightTracker();
	private final BodyState body = new BodyState();
	private final float[] view = new float[3];
	private long lastSequence = -1;
	private boolean stale;

	/** Forgets the previous frame and where the opponent was last seen. */
	void resetEpisode() {
		sight.reset();
		body.resetOpponentSwing();
	}

	/**
	 * Pulls the newest frame's masks. Returns false when no frame has been delivered yet.
	 * Afterwards {@link #stale()} tells whether this frame had already been used for a decision.
	 */
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

	/** See {@link BodyState#patchedUp}; for the observations this class builds itself. */
	void showHealthPatchedUp(boolean value) {
		body.patchedUp = value;
	}

	/** True when the last observation was built from a frame taken before the camera's latest move. */
	boolean late() {
		return sight.frameIsLate();
	}

	// ---- ground truth (reward and metrics only) -------------------------------------------------

	/** Fills the geometric part of {@code facts} from the opponent as this client currently shows it. */
	void fillGeometry(StepFacts facts, LocalPlayer me, Player bot) {
		Sightline.fill(facts, me.getEyePosition(), me.getYRot(), me.getXRot(), me.getLookAngle(),
				bot.getBoundingBox(), bot.getX(), bot.getEyeY(), bot.getZ());
		facts.enemyVisible = frame.enemyVisible();
		// In the air and lower than a tick ago: the fall a critical hit is made from.
		facts.falling = !me.onGround() && me.getY() < me.yo;
	}

	// ---- observation ----------------------------------------------------------------------------

	/**
	 * Fills in what the player's own character knows about itself, and how the opponent moves on
	 * screen. The hit clocks are left to the caller.
	 *
	 * @param floorY the height of the ground being fought on
	 */
	void fillBody(BodyState body, LocalPlayer me, Player opponent, double floorY) {
		body.yaw = me.getYRot();
		body.pitch = me.getXRot();
		body.height = (float) (me.getY() - floorY);
		body.onGround = me.onGround();
		body.setMotion(me.getX() - me.xo, me.getY() - me.yo, me.getZ() - me.zo);
		body.setOpponentMotion(opponent.getX() - opponent.xo, opponent.getY() - opponent.yo, opponent.getZ() - opponent.zo);
		body.charge = me.getAttackStrengthScale(0f);
		body.noteOpponentSwing(opponent.swinging, opponent.swingTime);
		body.setHealth(me.getHealth() / Math.max(1f, me.getMaxHealth()));
		body.sprinting = me.isSprinting();
		body.stale = stale;
	}

	/**
	 * Writes the observation for the current tick.
	 *
	 * @param lastAction the action taken on the previous tick, or null at the start of an episode
	 * @param facts      geometry for the critic; already filled by {@link #fillGeometry}
	 * @param motor      the camera's hand model, whose built-up push the agent feels
	 * @param idea       the plan the agent has been handed, if the stage hands one out
	 */
	void fillObs(Obs obs, Minecraft mc, LocalPlayer me, Player bot, Truth truth, StepFacts facts, int[] lastAction,
			int headMask, int stageIndex, SessionClock clock, AimMotor motor, MixIdea idea) {
		fillObs(obs, me, bot, truth.floorY(), truth.botHealth(), truth.botCharge(), truth.botSprinting(), truth.botOnGround(),
				facts, lastAction, headMask, stageIndex, clock, motor, idea);
	}

	/**
	 * The same, where there is no arena to say how the opponent stands: the caller says what it knows.
	 *
	 * @param floorY    the height of the ground being fought on
	 * @param oppHealth the opponent's health in half hearts, and so on: for the critic only
	 */
	void fillObs(Obs obs, LocalPlayer me, Player bot, double floorY, float oppHealth, float oppCharge, boolean oppSprinting,
			boolean oppOnGround, StepFacts facts, int[] lastAction, int headMask, int stageIndex, SessionClock clock,
			AimMotor motor, MixIdea idea) {
		fillBody(body, me, bot, floorY);
		body.ticksSinceHitDealt = clock.ticksSinceHitDealt;
		body.ticksSinceHitTaken = clock.ticksSinceHitTaken;
		body.hitsTakenInRow = clock.takenStreak.shown();
		// Positions measured on the frame are shifted by however far the camera has turned since
		// it was taken, and the last sighting is remembered. Call order matters: once per decision.
		ObsBuilder.writeActor(obs, frame, sight, body, motor, idea, lastAction, headMask);

		// Everything below is for the critic and never reaches the acting network.
		Vec3 eye = me.getEyePosition();
		ViewGeometry.toYawFrame(bot.getX() - eye.x, bot.getEyeY() - eye.y, bot.getZ() - eye.z, me.getYRot(), view);
		boolean inReach = facts.onBody && facts.reachDist <= me.entityInteractionRange();
		ObsBuilder.writePrivileged(obs.priv, facts, body, view[0], view[1], view[2], oppHealth, oppCharge,
				oppSprinting, oppOnGround, inReach, clock, stageIndex);
	}
}
