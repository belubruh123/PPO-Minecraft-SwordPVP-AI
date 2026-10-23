package dev.swordai.server;

import dev.swordai.core.rl.Fighter;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets the trained network play a fake player, so that a person (or the network itself) can fight
 * it. The network learned on a real game client, so the fake player is run as if it had one: see
 * {@link ReplicaBody}. This class only puts the network's decision in the middle of that tick.
 *
 * <p>Created on the client thread, then used by the server thread only.
 */
public final class BotPilot implements SparPilot {
	private final Fighter fighter;
	private final ReplicaBody body;

	/**
	 * @param frameAgeTicks how old the frame is that a client decides on, in ticks (measured while
	 *                      the network plays the user's own character; about half a tick)
	 */
	public BotPilot(Fighter fighter, float frameAgeTicks) {
		this(fighter, frameAgeTicks, false);
	}

	/**
	 * @param patchedUp the network learned in a stage where it could not lose health, and is shown its
	 *                  health the way it was there; see {@code BodyState.patchedUp}
	 */
	public BotPilot(Fighter fighter, float frameAgeTicks, boolean patchedUp) {
		this.fighter = fighter;
		this.body = new ReplicaBody(frameAgeTicks);
		this.body.state().patchedUp = patchedUp;
	}

	/** How many ticks late it sees its opponent; see {@link ReplicaBody#setLag}. */
	public void setLag(int ticks) {
		body.setLag(ticks);
	}

	/** Start of a round. */
	@Override
	public void reset() {
		fighter.reset();
		body.reset();
	}

	@Override
	public void hitDealt() {
		fighter.hitDealt();
	}

	@Override
	public void hitTaken() {
		fighter.hitTaken();
	}

	/** Between rounds: stands still and waits. */
	@Override
	public void idle(ServerPlayer bot) {
		body.idle(bot);
	}

	/** Swings made, swings that reached the opponent, and swings that ended on a block. */
	@Override
	public String swingSummary() {
		return body.swingSummary();
	}

	void clearSummary() {
		body.clearSummary();
	}

	@Override
	public void tick(ServerPlayer bot, ServerPlayer opponent, double floorY) {
		body.sense(bot, opponent, floorY);
		// What its crosshair is on, for the rule about answering a hit; see AnswerRule.
		fighter.crosshairOnOpponent(body.aimedAt(bot, opponent));
		int[] action = fighter.decide(body.frame(), body.state());
		body.act(bot, opponent, action, fighter.yawRate(), fighter.pitchRate(), fighter.answerForced());
		if (body.swingLeftOut()) {
			fighter.answerCancelled();
		}
	}
}
