package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.league.LeagueLearner;
import dev.swordai.core.league.ObsMemory;
import dev.swordai.core.league.SwordNet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Pure neural-network play session ({@code /swordai fight}): zero play-time rules, zero guards,
 * zero key overrides. Reads pixels + observable body state, runs the recurrent {@link SwordNet},
 * presses the network's keys, and records every round via {@link RoundRecorder}.
 */
final class FightSession implements AgentSession {
	private final Minecraft mc;
	private final Config cfg;
	private final SwordNet net;
	private final SwordNet.Workspace ws;
	private final ObsMemory memory = new ObsMemory();
	private final AimMotor motor;
	private final Obs obs = new Obs();
	private final SessionClock clock = new SessionClock();
	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	private final StepFacts facts = new StepFacts();
	private final BlindGuard blind = new BlindGuard();
	private final RoundRecorder rounds = new RoundRecorder();
	private final Random rng = new Random();
	private final int[] action = new int[ActionSpace.HEADS];
	private final int[] lastAction = new int[ActionSpace.HEADS];
	private boolean hasLastAction;

	private final String wantedName;
	private UUID opponentId;
	private Player opponent;
	private String opponentName = "?";
	private double groundY;
	private int lastOwnHurt;
	private int lastOppHurt;
	private int ownSwingsSeen = OwnSwing.count();

	private boolean stopped;
	private boolean handedBack;
	private boolean actedThisTick;
	private boolean clicked;
	private float turnYaw;
	private float turnPitch;
	private long ticks;
	private int kills;
	private int deaths;
	private boolean deathCounted;
	private boolean killCounted;

	FightSession(Minecraft mc, String opponentName) throws IOException {
		this.mc = mc;
		this.wantedName = opponentName;
		this.cfg = SwordFiles.loadConfig();
		Path ckpt = Files.exists(SwordFiles.schoolCheckpoint()) ? SwordFiles.schoolCheckpoint() : SwordFiles.checkpoint();
		if (!Files.exists(ckpt)) {
			throw new IOException("no trained network found at " + ckpt);
		}
		this.net = LeagueLearner.loadActorOnly(ckpt);
		this.ws = net.newWorkspace();
		this.motor = new AimMotor(cfg.aimResponse);
		OptionsGuard.apply(mc, cfg.fov);
		int visFov = Math.min(cfg.visionFov, cfg.fov);
		ViewGeometry.setFov(visFov);
		grabber.setCrop(ViewGeometry.cropFor(cfg.fov, visFov));
		grabber.setEnabled(true);
		KeyedSkin.setShowReal(cfg.showOpponentSkin);
		KeyedSkin.setActive(true);
	}

	String skillLine() {
		return "Pure neural policy (zero play-time rules).";
	}

	@Override
	public boolean stopped() {
		return stopped;
	}

	@Override
	public boolean inControl() {
		return !stopped && !handedBack;
	}

	@Override
	public void tick() {
		if (stopped) return;
		actedThisTick = false;
		clicked = false;
		turnYaw = 0f;
		turnPitch = 0f;

		LocalPlayer me = mc.player;
		if (me == null || mc.level == null) {
			stop("left the world");
			return;
		}
		if (mc.screen != null && !(mc.screen instanceof ChatScreen)) {
			handBack();
			return;
		}
		if (!me.isAlive()) {
			if (!deathCounted) {
				deaths++;
				deathCounted = true;
				finishRound("lost");
			}
			handBack();
			return;
		}
		deathCounted = false;

		opponent = Opponents.find(mc, me, wantedName, opponentId);
		if (opponent == null || !opponent.isAlive()) {
			if (opponent != null && !opponent.isAlive() && !killCounted) {
				kills++;
				killCounted = true;
				finishRound("won");
			}
			handBack();
			return;
		}
		killCounted = false;
		if (!opponent.getUUID().equals(opponentId)) {
			finishRound("open");
			opponentId = opponent.getUUID();
			opponentName = opponent.getScoreboardName();
			resetEpisode();
			rounds.start(opponentName);
		}
		handedBack = false;
		Opponents.keepView(mc, me);
		if (me.onGround()) {
			groundY = me.getY();
		}
		if (!observer.capture(grabber)) {
			return;
		}

		boolean takenNow = me.hurtTime > lastOwnHurt && me.hurtTime >= 8;
		boolean dealtNow = opponent.hurtTime > lastOppHurt && opponent.hurtTime >= 8;
		lastOwnHurt = me.hurtTime;
		lastOppHurt = opponent.hurtTime;
		if (dealtNow) clock.hitDealt();
		if (takenNow) clock.hitTaken();
		clock.tick();

		boolean dealtCrit = false;
		if (OwnSwing.count() != ownSwingsSeen) {
			ownSwingsSeen = OwnSwing.count();
			dealtCrit = "crit".equals(OwnSwing.lastKind());
			rounds.swing(OwnSwing.lastKind());
		}
		observer.body().updateExchange(dealtNow, dealtCrit, takenNow);
		observer.fillActorOnly(obs, me, opponent, groundY, clock, motor, hasLastAction ? lastAction : null, ActionSpace.ALL_HEADS);

		memory.pushSeen(obs.vec);
		net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, memory.hist, memory.seen, memory.hidden, memory.hidden);
		net.sample(ws, ws.step, ActionSpace.ALL_HEADS, false, cfg.playTemperature, rng, action);
		memory.pushAction(action);

		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		turnYaw = motor.yawRate();
		turnPitch = motor.pitchRate();
		AgentController.apply(mc, action, turnYaw, turnPitch);
		rounds.tick(me, opponent, observer.body(), action, dealtNow, takenNow);
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
		actedThisTick = true;
		ticks++;
		observer.fillGeometry(facts, me, opponent);
		if (blind.note(facts)) {
			KeyedSkin.setShowReal(false);
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || stopped) return false;
		AgentController.hold(mc, action);
		clicked = action[ActionSpace.ATTACK] == 1;
		return clicked;
	}

	@Override
	public void endTick() {
	}

	@Override
	public void worldDrawn() {
		if (!stopped && !handedBack) {
			grabber.worldDrawn(mc);
		}
	}

	@Override
	public float viewYawLag(float partialTick) {
		return -(1f - partialTick) * turnYaw;
	}

	@Override
	public float viewPitchLag(float partialTick) {
		return -(1f - partialTick) * turnPitch;
	}

	@Override
	public void stop(String reason) {
		if (stopped) return;
		stopped = true;
		finishRound("open");
		AgentController.release(mc);
		KeyedSkin.setActive(false);
		KeyedSkin.setShowReal(false);
		OptionsGuard.restore(mc);
		grabber.close();
		SwordAi.LOGGER.info("Stopped ({}): fight vs {} ({} ticks, {} kills / {} deaths)", reason, opponentName, ticks, kills, deaths);
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[SwordAI] Stopped (" + reason + ")."), false);
		}
	}

	@Override
	public List<String> statusLines() {
		return List.of(String.format(Locale.ROOT, "Fighting %s (pure neural policy): %d ticks, %d-%d",
				opponentName, ticks, kills, deaths));
	}

	@Override
	public boolean requestDebugDump() {
		return false;
	}

	@Override
	public String describe() {
		return "fighting " + opponentName;
	}

	private void resetEpisode() {
		memory.reset();
		motor.reset();
		clock.reset();
		observer.resetEpisode();
		hasLastAction = false;
	}

	private void handBack() {
		if (!handedBack) {
			handedBack = true;
			AgentController.release(mc);
		}
	}

	private void finishRound(String result) {
		if (rounds.open()) {
			int ping = 0;
			if (mc.getConnection() != null && opponentId != null) {
				var info = mc.getConnection().getPlayerInfo(opponentId);
				if (info != null) ping = info.getLatency();
			}
			String line = rounds.finish(result, ping);
			if (line != null) SwordAi.LOGGER.info("round: {}", line);
		}
	}
}
