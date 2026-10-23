package dev.swordai.client;

import dev.swordai.server.ArenaDriver;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.ReplicaBody;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Training without the screen. The agent's body is a fake player, run as if it had a game client of
 * its own ({@link ReplicaBody}): the same body the network is given when a person spars against it.
 * Its picture is drawn rather than read off the screen, so nothing has to be rendered for it and
 * the world can run as fast as the computer manages. What is trained, and how, is {@link Trainer}'s
 * and exactly the same as on the screen.
 *
 * <p>Everything about the training runs on the server thread, called from the arena's tick. The
 * client thread only asks: for the status, and for a stop, which it hands over. The user's own
 * character is not touched and stays theirs to play.
 */
public final class HeadlessSession extends ReplicaTrainer implements ArenaDriver {
	/** Slower than this share of the speed asked for means the computer cannot run the world that fast. */
	private static final float KEEPING_UP = 0.93f;
	private static final float HEADROOM = 0.95f;
	/** Keeping up with the speed asked for means reaching at least this share of it. */
	private static final float AT_SPEED = 0.97f;
	/** Each try at going faster asks for this many times the speed it has. */
	private static final float TRY_FASTER = 1.5f;
	/** Once a limit has been found, it is tried again after this many updates: what the computer manages changes. */
	private static final int RETRY_AFTER_UPDATES = 25;
	/** A change of speed is only worth a line in the chat when it is at least this large. */
	private static final float WORTH_SAYING = 0.15f;
	/** The pace is only judged over at least this many ticks of fighting. */
	private static final int PACE_MIN_TICKS = 400;
	/** Left alone, it says where it has got to about this often. */
	private static final long PROGRESS_EVERY_NANOS = 10L * 60L * 1_000_000_000L;

	/** An arena that could not be set up at all is noticed from the client's side; once is enough. */
	private boolean failing;
	/** A copy of the network as the screen had trained it was kept when this began. */
	private boolean keptCopy;
	private long lastProgressNanos = System.nanoTime();

	// How fast the fights really go, measured over the ticks spent fighting.
	private long lastRunNanos;
	private long runNanos;
	private long runTicks;
	/** The first update's ticks are slow for reasons that pass (the game is still warming up), so they are not judged. */
	private boolean warmedUp;
	/** The speed at which it last failed to keep up, or 0 while no limit is known. */
	private float knownLimit;
	private int updatesSinceLimit;
	private float lastSaidSpeed;

	/**
	 * Loads config, checkpoint and curriculum state and asks the server for the arena and the two
	 * fake players.
	 *
	 * @throws IOException when the saved state cannot be read
	 */
	public HeadlessSession(Minecraft mc) throws IOException {
		super(mc, false);
		if (resumed && !history.isEmpty() && !history.get(history.size() - 1).headless) {
			// What the screen has trained so far is kept as it is, in case this kind of training turns
			// out worse: /swordai undo brings it back.
			try {
				SwordFiles.keepBeforeHeadless();
				keptCopy = true;
			} catch (IOException e) {
				say("Could not keep a copy of the network from before this (" + e + "); /swordai undo will not have it.");
			}
		}
		OptionsGuard.applyQuiet(mc);
		IntegratedServer server = mc.getSingleplayerServer();
		UUID me = mc.player.getUUID();
		server.execute(() -> ArenaServer.startHeadless(server, me, cfg, this));
	}

	// ---- the client's side: it only asks ----------------------------------------------------------------

	/** Start of every client tick: only looks whether the arena could be set up at all. */
	@Override
	public void tick() {
		if (phase == Phase.STOPPED || failing) {
			return;
		}
		String error = ArenaServer.truth().error();
		if (error != null) {
			failing = true;
			onTrainingThread(() -> fail(error, null));
		}
	}

	@Override
	public String describe() {
		return super.describe() + ", without the screen";
	}

	@Override
	public void setStage(int index) {
		onTrainingThread(() -> super.setStage(index));
	}

	@Override
	public List<String> statusLines() {
		List<String> out = super.statusLines();
		out.add("Training without the screen: the AI plays the fake player " + ArenaServer.playerName(cfg.agentName, ArenaServer.AGENT_NAME)
				+ " against " + ArenaServer.playerName(cfg.botName, ArenaServer.BOT_NAME)
				+ " in the arena above you, and your own character is yours. /swordai stop saves and ends it.");
		return out;
	}

	/** Asks for a clean stop. The training is ended on its own thread; this returns at once. */
	@Override
	public void stop(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		say("Stopping: saving what it has learned...");
		onTrainingThread(() -> stopNow(reason));
	}

	// ---- the server's side: the training itself ---------------------------------------------------------

	@Override
	public void arenaTick(ServerPlayer agent, ServerPlayer bot, double floorY) {
		if (phase == Phase.STOPPED) {
			return;
		}
		this.agent = agent;
		this.bot = bot;
		this.floorY = floorY;
		try {
			tickUnsafe();
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

	private void tickUnsafe() {
		Truth truth = ArenaServer.truth();
		boolean bodies = agent != null && bot != null;
		long now = System.nanoTime();
		boolean wasRunning = phase == Phase.RUN;
		switch (phase) {
			case WAIT_ARENA -> {
				if (bodies && truth.active() && truth.botReady()) {
					beginEpisode(truth);
				} else if (++waitTicks > ARENA_TIMEOUT_TICKS) {
					fail("the arena and its two fake players did not come up within 30 seconds", null);
				}
			}
			case WAIT_RESET -> {
				holdStill();
				if (truth.episodeId() >= requestedEpisodeId) {
					phase = Phase.SETTLE;
					settleTicks = SETTLE_TICKS;
				} else if (++waitTicks > RESET_TIMEOUT_TICKS) {
					fail("the server did not start the next episode", null);
				}
			}
			case SETTLE -> {
				holdStill();
				boolean landed = headStart() && openerLanded();
				if (--settleTicks <= 0) {
					if (headStart() && !landed) {
						waitTicks = 0;
						phase = Phase.OPENER;
					} else {
						startRun(truth);
					}
				}
			}
			case OPENER -> {
				// A combo drill: the agent stays held until the copy has landed the hits it was given.
				holdStill();
				if (openerLanded() || ++waitTicks > OPENER_TIMEOUT_TICKS) {
					startRun(truth);
				}
			}
			case RUN -> {
				if (!bodies) {
					// One of the two fake players is gone; the arena brings it back, and then a new fight begins.
					suspendEpisode();
					waitTicks = 0;
					phase = Phase.WAIT_ARENA;
					return;
				}
				if (lastRunNanos != 0L) {
					runNanos += now - lastRunNanos;
					runTicks++;
				}
				replica.sense(agent, bot, floorY);
				decisions++;
				step(truth);
			}
			case UPDATING -> {
				holdStill();
				if (pendingUpdate.isDone()) {
					finishUpdate();
					if (phase == Phase.UPDATING) {
						beginEpisode(truth);
					}
				}
			}
			default -> {
				// STOPPED does nothing; the other phases belong to training on the screen.
			}
		}
		// Only two fighting ticks in a row say how long a tick takes.
		lastRunNanos = wasRunning && phase == Phase.RUN ? now : 0L;
	}

	/** Keys let go, but still watching: what a player with their hands off the keys is. */
	private void holdStill() {
		if (agent != null && bot != null) {
			replica.hold(agent, bot);
		}
	}

	private void stopNow(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		Phase was = phase;
		phase = Phase.STOPPED;
		try {
			saveProgress(was);
		} finally {
			try {
				closeLearning();
				if (mc.isSameThread()) {
					OptionsGuard.restore(mc);
				} else {
					mc.execute(() -> OptionsGuard.restore(mc));
				}
				releaseArena();
			} finally {
				closed = true;
			}
		}
		say("Stopped (" + reason + ").");
	}

	// ---- the rest of what the trainer asks for; the body itself is the base class's ---------------

	@Override
	float wantedSpeed() {
		return cfg.headlessSpeed;
	}

	@Override
	boolean headless() {
		return true;
	}

	/** True when a copy of the network from before this training was kept at its start. */
	boolean keptCopy() {
		return keptCopy;
	}

	/** While the network learns nothing is fought, so the world need not race through empty ticks. */
	@Override
	void onUpdating(boolean started) {
		float s = started ? 1f : speed;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null && phase != Phase.STOPPED) {
			server.execute(() -> ArenaServer.setSpeed(s));
		}
	}

	/**
	 * Nothing depends on real time here: if the computer cannot run the world as fast as asked, the
	 * fights simply take longer, and are the same fights. Asking for more than it manages only makes
	 * the server complain, so the speed asked for comes down to the speed reached.
	 */
	@Override
	void afterUpdate() {
		long now = System.nanoTime();
		if (now - lastProgressNanos >= PROGRESS_EVERY_NANOS) {
			// Left to itself, it says now and then where it has got to.
			lastProgressNanos = now;
			String blocker = curriculum.promotionBlocker();
			say(String.format(Locale.ROOT, "Progress: stage %d/%d (%s), %,d steps in total, %.0f steps a second (x%.1f of real time). Next stage: %s.",
					curriculum.stageIndex() + 1, curriculum.stageCount(), curriculum.current().name, learner.totalSteps,
					stepsPerSecond, stepsPerSecond / 20f, blocker == null ? "ready, at the next update" : blocker));
		}
		if (runTicks < PACE_MIN_TICKS || runNanos <= 0L) {
			return;
		}
		float reached = (float) (runTicks / (runNanos / 1e9)) / 20f;
		runTicks = 0;
		runNanos = 0L;
		if (!warmedUp) {
			warmedUp = true;
			return;
		}
		float cap = Math.max(1f, cfg.headlessSpeed);
		float next = speed;
		if (speed > 1f && reached < speed * KEEPING_UP) {
			// A little under what it reached, so that it is not forever a step behind.
			knownLimit = reached;
			updatesSinceLimit = 0;
			next = Math.max(1f, reached * HEADROOM);
		} else if (speed < cap && reached >= speed * AT_SPEED) {
			// It keeps up. If no limit is known, or the known one is old, it may be able to go faster:
			// what limits it changes with the stage, with what else the computer is doing, and with
			// how long the game has been running.
			if (knownLimit <= 0f || ++updatesSinceLimit >= RETRY_AFTER_UPDATES) {
				knownLimit = 0f;
				updatesSinceLimit = 0;
				next = Math.min(cap, speed * TRY_FASTER);
			}
		}
		if (next == speed) {
			return;
		}
		boolean settling = next < speed;
		applySpeed(next);
		// Only a speed it has settled on is news, and only when it differs from the last one said.
		if (settling && (lastSaidSpeed <= 0f || Math.abs(next - lastSaidSpeed) > lastSaidSpeed * WORTH_SAYING)) {
			lastSaidSpeed = next;
			say(String.format(Locale.ROOT, "This computer runs the fights at about x%.1f of real time. It tries for more again from time to time.", next));
		}
	}

	@Override
	String pictureLine() {
		return String.format(Locale.ROOT, "picture: drawn for the fake player, %.0f ms old as a frame on the screen is (%s)", frameAgeTicks * 50f,
				SwordFiles.readEyeCheck() == null ? "not measured on this computer yet, so the usual half tick" : "as measured while it trained on the screen");
	}
}
