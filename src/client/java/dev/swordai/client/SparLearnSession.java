package dev.swordai.client;

import dev.swordai.core.report.UpdateRecord;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.CombatLog;
import dev.swordai.server.SparPilot;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Sparring that the network also learns from. To the player it is {@code /swordai spar}: the same
 * arena, rounds and score, against the same fake player. But that fake player is the agent of a
 * {@link Trainer} here, so every round is gathered, scored with the rewards of the stage it is in,
 * and learned from.
 *
 * <p>A person is not the stage's opponent: these fights never count towards passing a stage. And a
 * person does not wait: while the network learns from a batch, a copy of it fights on (see
 * {@link Trainer#playsOnWhileLearning}), so a round is never held up.
 *
 * <p>The training runs on the server thread, as the arena's pilot. The client thread only asks.
 */
final class SparLearnSession extends ReplicaTrainer implements SparPilot {
	private final long stepsAtStart;
	private final long updatesAtStart;
	/** A copy of the network from before it learned from people was kept when this began. */
	private boolean keptCopy;
	/** An arena that could not be set up at all is noticed from the client's side; once is enough. */
	private boolean failing;
	/** The round being fought has been decided; the step being scored is its last. */
	private boolean roundEnding;
	private volatile String lastScore = "";
	private volatile String lastDetail = "";

	/**
	 * @throws IOException when there is no trained network to spar with, or it cannot be read
	 */
	SparLearnSession(Minecraft mc) throws IOException {
		super(mc, true);
		this.stepsAtStart = learner.totalSteps;
		this.updatesAtStart = learner.updates;
		this.keptCopy = SwordFiles.keepBeforePeople(history, this::say);
		// Until the duel it has never seen its health stay down; it is shown it as its stage shows it.
		this.replica.state().patchedUp = curriculum.current().bot.agentImmortal;
		IntegratedServer server = mc.getSingleplayerServer();
		UUID me = mc.player.getUUID();
		// From here on the trainer belongs to the server thread.
		server.execute(() -> ArenaServer.startSpar(server, me, cfg, this));
	}

	/** What it plays with and learns towards, for the message shown when sparring starts. */
	String skillLine() {
		return String.format(Locale.ROOT, "It plays with what it knows at stage %d/%d (%s) and learns from this with that stage's rewards; "
						+ "fights against you never count towards passing a stage.",
				curriculum.stageIndex() + 1, curriculum.stageCount(), curriculum.current().name);
	}

	boolean keptCopy() {
		return keptCopy;
	}

	// ---- the client's side: it only asks ----------------------------------------------------------------

	@Override
	public void tick() {
		if (phase == Phase.STOPPED || failing) {
			return;
		}
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
			stop("left the world");
			return;
		}
		String error = ArenaServer.truth().error();
		if (error != null) {
			failing = true;
			onTrainingThread(() -> fail(error, null));
			return;
		}
		String score = ArenaServer.sparStatus();
		if (!score.isEmpty()) {
			lastScore = score;
		}
		String detail = ArenaServer.sparDetail();
		if (!detail.isEmpty()) {
			lastDetail = detail;
		}
	}

	@Override
	public String describe() {
		return "SPAR, learning from it" + (lastScore.isEmpty() ? "" : ", " + lastScore);
	}

	@Override
	String paceLine() {
		return null;
	}

	@Override
	public List<String> statusLines() {
		List<String> out = new ArrayList<>();
		out.add("Sparring against the AI, which learns from it. " + (lastScore.isEmpty() ? "The arena is being set up." : "Score: " + lastScore + "."));
		out.add(skillLine());
		out.add(learnedLine());
		if (!lastDetail.isEmpty()) {
			out.add(lastDetail);
		}
		return out;
	}

	private String learnedLine() {
		return String.format(Locale.ROOT, "learned from %,d steps of this sparring in %d updates (one about every %d s of fighting; it fights on while it learns)%s",
				learner.totalSteps - stepsAtStart - learner.rolloutSize(), learner.updates - updatesAtStart, wantedRollout() / 20,
				pendingUpdate != null ? "; learning right now" : "");
	}

	/** Asks for a clean stop. The training is ended on its own thread; this returns at once. */
	@Override
	public void stop(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		say("Stopping: learning from the rest of the sparring and saving...");
		onTrainingThread(() -> stopNow(reason));
	}

	// ---- the server's side: the arena's pilot -----------------------------------------------------------

	/** Start of a round: the stand-off is over. */
	@Override
	public void reset() {
		if (phase == Phase.STOPPED) {
			return;
		}
		replica.reset();
		roundEnding = false;
		CombatLog.clear();
		beginAgainstPerson();
	}

	// The trainer reads who hit whom from the combat log, where the arena keeps it for a pilot that learns.
	@Override
	public void hitDealt() {
	}

	@Override
	public void hitTaken() {
	}

	@Override
	public boolean learns() {
		return true;
	}

	@Override
	public String swingSummary() {
		return replica.swingSummary();
	}

	@Override
	public void idle(ServerPlayer body) {
		if (phase == Phase.STOPPED) {
			return;
		}
		agent = body;
		replica.idle(body);
		try {
			updateFinished();
		} catch (RuntimeException | Error e) {
			fail("internal error: " + e, e);
		}
	}

	@Override
	public void tick(ServerPlayer body, ServerPlayer opponent, double floorY) {
		if (phase == Phase.STOPPED) {
			return;
		}
		agent = body;
		bot = opponent;
		this.floorY = floorY;
		try {
			if (!updateFinished()) {
				return;
			}
			if (phase != Phase.RUN) {
				replica.hold(body, opponent);
				return;
			}
			replica.sense(body, opponent, floorY);
			decisions++;
			step(ArenaServer.truth());
		} catch (RuntimeException | Error e) {
			fail("internal error: " + e, e);
		}
	}

	/** The round was decided on the tick before: the last move is scored against how it ended. */
	@Override
	public void roundOver(ServerPlayer body, ServerPlayer opponent, double floorY) {
		if (phase != Phase.RUN) {
			return;
		}
		agent = body;
		bot = opponent;
		this.floorY = floorY;
		try {
			roundEnding = true;
			Truth truth = ArenaServer.truth();
			if (hasLastAction && pendingUpdate == null) {
				replica.sense(body, opponent, floorY);
				cutNow = true;
				step(truth);
			}
			cutNow = false;
			if (phase == Phase.RUN) {
				// Nothing was being gathered: a copy was playing while the network learned, or it had only just finished.
				releaseKeys();
				if (pendingUpdate == null) {
					learner.dropPending();
				}
				beginEpisode(truth);
			}
		} catch (RuntimeException | Error e) {
			fail("internal error: " + e, e);
		}
	}

	@Override
	public void arenaClosed(String error) {
		if (phase == Phase.STOPPED) {
			return;
		}
		if (error != null) {
			lastError = error;
			SwordFiles.writeError(error, null);
			say("Stopped because of a problem: " + error);
			say("Run /swordai report and paste the result.");
		}
		stopNow(error != null ? "error" : "the arena was closed");
	}

	private void stopNow(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		Phase was = phase;
		try {
			if (was == Phase.RUN) {
				suspendEpisode();
			}
			learnFromTheRest();
		} catch (RuntimeException e) {
			dev.swordai.SwordAi.LOGGER.warn("could not learn from the rest of the sparring", e);
		}
		phase = Phase.STOPPED;
		try {
			saveProgress(was);
		} finally {
			try {
				closeLearning();
				releaseArena();
			} finally {
				closed = true;
			}
		}
		String score = lastScore;
		String detail = lastDetail;
		if (!score.isEmpty()) {
			SwordFiles.writeLastFight("spar that it learned from, " + score + "; " + learnedLine() + (detail.isEmpty() ? "" : " " + detail));
		}
		say("Stopped (" + reason + ")." + (score.isEmpty() ? "" : " Final score: " + score + ".") + " It " + learnedLine() + ".");
		if (!detail.isEmpty()) {
			say(detail);
		}
	}

	// ---- what the trainer asks about the opponent -------------------------------------------------------

	/** Between rounds: nothing is played until the arena starts the next one. */
	@Override
	void beginEpisode(Truth truth) {
		hasLastAction = false;
		clicked = false;
		phase = Phase.WAIT_RESET;
	}

	/** In a stage whose opponent cannot lose health the critic has only ever been told it is full. */
	@Override
	float opponentHealth() {
		return stage.bot.botImmortal ? bot.getMaxHealth() : bot.getHealth();
	}

	/** A round lasts until someone is knocked out, however long that takes. */
	@Override
	int episodeLimit() {
		return Integer.MAX_VALUE;
	}

	@Override
	boolean stageFights() {
		return false;
	}

	@Override
	int opponentKind() {
		return UpdateRecord.OPPONENT_SPAR;
	}

	@Override
	boolean fightGoesOn() {
		return phase == Phase.RUN && !roundEnding;
	}

	@Override
	boolean playsOnWhileLearning() {
		return true;
	}

	@Override
	float wantedSpeed() {
		return 1f;
	}

	@Override
	String pictureLine() {
		return String.format(Locale.ROOT, "picture: drawn for the fake player, %.0f ms old as a frame on the screen is", frameAgeTicks * 50f);
	}
}
