package dev.swordai.client;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.rl.ObsBuilder;
import dev.swordai.core.rl.SightTracker;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ReplicaBody;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;

/**
 * Training in which the agent's body is a fake player, run as if it had a game client of its own
 * ({@link ReplicaBody}), with a picture that is drawn for it rather than read off the screen. This
 * class is that body, as {@link Trainer} asks for it. Who it fights, and what starts and ends a
 * fight, is the subclass's.
 *
 * <p>Everything about the training runs on the server thread. The client thread only asks: for the
 * status, and for a stop, which it hands over. Nobody's keys are taken.
 */
abstract class ReplicaTrainer extends Trainer {
	final ReplicaBody replica;
	final SightTracker sight = new SightTracker();
	private final float[] view = new float[3];
	final float frameAgeTicks;

	/** The agent's body and its opponent's, as of the tick being played. */
	ServerPlayer agent;
	ServerPlayer bot;
	double floorY;
	/** Set once stopping has finished: everything is saved and let go. */
	volatile boolean closed;

	/**
	 * @param trainedOnly see {@link Trainer#Trainer(Minecraft, Mode, boolean)}
	 * @throws IOException when the saved state cannot be read
	 */
	ReplicaTrainer(Minecraft mc, boolean trainedOnly) throws IOException {
		super(mc, Mode.TRAIN, trainedOnly);
		// The picture is as old as a frame is on the screen: as measured there, or about half a tick.
		this.frameAgeTicks = SwordFiles.readFrameAgeMillis() / 50f;
		this.replica = new ReplicaBody(frameAgeTicks);
		this.replica.reset();
	}

	// ---- the client's side: it only asks ----------------------------------------------------------------

	/**
	 * Stopping takes a moment on another thread (an update may have to finish, the checkpoint is
	 * written). Until that is done this still counts as running, so that nothing else can pick up
	 * the checkpoint half-written.
	 */
	@Override
	public boolean stopped() {
		return closed;
	}

	/** Nobody's keys are taken: the network plays a body of its own. */
	@Override
	public boolean inControl() {
		return false;
	}

	@Override
	public void endTick() {
	}

	@Override
	public void worldDrawn() {
	}

	@Override
	public boolean beforePlayerMoves() {
		return false;
	}

	@Override
	public float viewYawLag(float partialTick) {
		return 0f;
	}

	@Override
	public float viewPitchLag(float partialTick) {
		return 0f;
	}

	@Override
	public boolean requestDebugDump() {
		return false;
	}

	/** Runs something on the thread the training lives on, or here if that thread is gone. */
	void onTrainingThread(Runnable work) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || server.isSameThread() || !server.isRunning() || !server.getRunningThread().isAlive()) {
			work.run();
		} else {
			server.execute(work);
		}
	}

	// ---- the body, as the trainer asks for it -------------------------------------------------------

	@Override
	void releaseKeys() {
		if (agent != null && !agent.isRemoved()) {
			replica.letGo(agent);
		}
	}

	@Override
	void prepareBody() {
	}

	@Override
	void setAgentLag(int ticks, float stallChance, int stallTicks) {
		replica.setLag(ticks, stallChance, stallTicks);
	}

	@Override
	void resetSight() {
		sight.reset();
		replica.state().resetOpponentSwing();
	}

	@Override
	float bodyCharge() {
		return replica.state().charge;
	}

	@Override
	boolean bodyOnGround() {
		return agent.onGround();
	}

	@Override
	float bodyPitch() {
		return agent.getXRot();
	}

	@Override
	boolean serverSprinting(Truth truth) {
		return replica.serverSprinting();
	}

	@Override
	void fillGeometry(StepFacts facts) {
		replica.fillGeometry(facts, agent, bot);
	}

	@Override
	void fillObs(Obs obs, Truth truth, int[] lastActionOrNull) {
		BodyState body = replica.state();
		body.noteOpponentSwing(bot.swinging, bot.swingTime);
		body.ticksSinceHitDealt = clock.ticksSinceHitDealt;
		body.ticksSinceHitTaken = clock.ticksSinceHitTaken;
		body.hitsTakenInRow = clock.takenStreak.shown();
		ObsBuilder.writeActor(obs, replica.frame(), sight, body, motor, idea, lastActionOrNull, stage.headMask);

		// Everything below is for the critic and never reaches the acting network. The opponent is
		// read as it stands on this tick, after its own move: what the arena publishes about it too.
		Vec3 rel = replica.shownEyesFrom(agent, bot);
		ViewGeometry.toYawFrame(rel.x, rel.y, rel.z, agent.getYRot(), view);
		boolean inReach = facts.onBody && facts.reachDist <= agent.entityInteractionRange();
		ObsBuilder.writePrivileged(obs.priv, facts, body, view[0], view[1], view[2], opponentHealth(), bot.getAttackStrengthScale(0f),
				bot.isSprinting(), bot.onGround(), inReach, clock, episodeStageIndex);
	}

	/** The opponent's health as the critic is told it, in half hearts. */
	float opponentHealth() {
		return bot.getHealth();
	}

	@Override
	boolean frameLate() {
		return sight.frameIsLate();
	}

	@Override
	boolean clickWasOnTarget() {
		return replica.clickOnTarget();
	}

	@Override
	boolean crosshairOnOpponent() {
		return agent != null && bot != null && replica.aimedAt(agent, bot);
	}

	@Override
	void applyAction(int[] action, float yawRate, float pitchRate) {
		replica.act(agent, bot, action, yawRate, pitchRate, answerAtAction);
		if (replica.swingLeftOut()) {
			answerCancelled();
		}
		lastYawTurn = replica.yawTurned();
		lastPitchTurn = replica.pitchTurned();
	}

	@Override
	float frameAgeTicks() {
		return frameAgeTicks;
	}

	@Override
	float captureMillis() {
		return 0f;
	}
}
