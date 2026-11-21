package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.env.*;
import dev.swordai.core.league.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Pure neural-network play session (/swordai fight): zero play-time rules, zero guards, zero overrides. */
final class FightSession implements AgentSession {
	private static final float LOST_ROUND_HP = 4f;

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
	private final int[] action = new int[ActionSpace.HEADS], lastAction = new int[ActionSpace.HEADS];
	private final String wantedName;

	private UUID opponentId;
	private Player opponent;
	private String opponentName = "?";
	private double groundY;
	private float lastHealth = -1f;
	private int lastOwnHurt, lastOppHurt, ownSwingsSeen = OwnSwing.count(), kills, deaths;
	private boolean hasLastAction, stopped, handedBack, actedThisTick, clicked, clickResolved, deathCounted, killCounted, oppSpecCounted;
	private float turnYaw, turnPitch;
	private long ticks;

	FightSession(Minecraft mc, String opponentName) throws IOException {
		this.mc = mc; this.wantedName = opponentName; this.cfg = SwordFiles.loadConfig();
		Path ckpt = Files.exists(SwordFiles.schoolCheckpoint()) ? SwordFiles.schoolCheckpoint() : SwordFiles.checkpoint();
		if (!Files.exists(ckpt)) throw new IOException("no trained network found at " + ckpt);
		this.net = LeagueLearner.loadActorOnly(ckpt); this.ws = net.newWorkspace();
		this.motor = new AimMotor(cfg.aimResponse);
		OptionsGuard.apply(mc, cfg.fov);
		int visFov = Math.min(cfg.visionFov, cfg.fov);
		ViewGeometry.setFov(visFov); grabber.setCrop(ViewGeometry.cropFor(visFov, cfg.fov)); grabber.setEnabled(true);
		KeyedSkin.setTarget(null); KeyedSkin.setShowReal(cfg.showOpponentSkin); KeyedSkin.setActive(false);
	}

	String skillLine() { return "Pure neural policy (zero play-time rules)."; }
	@Override public boolean stopped() { return stopped; }
	@Override public boolean inControl() { return !stopped && !handedBack; }
	@Override public void worldDrawn() { if (!stopped && !handedBack) grabber.worldDrawn(mc); }
	@Override public float viewYawLag(float pt) { return -(1f - pt) * turnYaw; }
	@Override public float viewPitchLag(float pt) { return -(1f - pt) * turnPitch; }
	@Override public boolean requestDebugDump() { return false; }
	@Override public String describe() { return "fighting " + opponentName; }
	@Override public List<String> statusLines() { return List.of(String.format(Locale.ROOT, "Fighting %s: %d ticks, %d-%d", opponentName, ticks, kills, deaths)); }

	@Override
	public void tick() {
		if (stopped) return;
		actedThisTick = false; turnYaw = turnPitch = 0f;
		LocalPlayer me = mc.player;
		if (me == null || mc.level == null) { stop("left the world"); return; }
		OptionsGuard.hold(mc);
		if (mc.screen != null && !(mc.screen instanceof ChatScreen)) { handBack(); return; }
		if (!me.isAlive() || me.isSpectator()) {
			if (!deathCounted) { deaths++; deathCounted = true; finishRound("lost"); }
			handBack(); return;
		}
		deathCounted = false;
		boolean oppSpec = opponentId != null && spectating(opponentId);
		if (oppSpec && !oppSpecCounted) { oppSpecCounted = true; kills++; finishRound("won"); }
		if (!oppSpec) oppSpecCounted = false;
		float hpNow = me.getHealth();
		if (lastHealth >= 0f && lastHealth <= LOST_ROUND_HP && hpNow >= me.getMaxHealth() - 0.5f) {
			deaths++; finishRound("lost"); resetEpisodeState();
		}
		lastHealth = hpNow;

		Player opp = Opponents.find(mc, me, wantedName, opponentId);
		if (opp == null || !opp.isAlive()) {
			if (opp != null && !opp.isAlive() && !killCounted) { kills++; killCounted = true; finishRound("won"); }
			handBack(); return;
		}
		killCounted = false;
		if (opp != opponent || !opp.getUUID().equals(opponentId)) {
			finishRound("open"); opponent = opp; opponentId = opp.getUUID(); opponentName = opp.getScoreboardName();
			KeyedSkin.setTarget(opponentId); KeyedSkin.setShowReal(cfg.showOpponentSkin); KeyedSkin.setActive(true);
			resetEpisodeState(); lastOwnHurt = me.hurtTime; lastOppHurt = opp.hurtTime;
			rounds.start(opponentName);
		}
		if (handedBack) {
			handedBack = false; resetEpisodeState(); groundY = me.getY();
			lastOwnHurt = me.hurtTime; lastOppHurt = opponent.hurtTime;
		}
		Opponents.keepView(mc, me);
		if (me.onGround()) groundY = me.getY();
		if (!observer.capture(grabber)) return;

		boolean tNow = me.hurtTime > lastOwnHurt && me.hurtTime >= 8;
		boolean dNow = opponent.hurtTime > lastOppHurt && opponent.hurtTime >= 8;
		lastOwnHurt = me.hurtTime; lastOppHurt = opponent.hurtTime;
		if (tNow) { clock.hitTaken(); observer.body().updateExchange(false, false, true); }
		clock.tick();
		observer.fillActorOnly(obs, me, opponent, groundY, clock, motor, hasLastAction ? lastAction : null, ActionSpace.ALL_HEADS);

		memory.pushSeen(obs.vec);
		net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, memory.hist, memory.seen, memory.hidden, memory.hidden);
		net.samplePlay(ws, ws.step, rng, action);
		memory.pushAction(action);

		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		turnYaw = motor.yawRate(); turnPitch = motor.pitchRate();
		AgentController.apply(mc, action, turnYaw, turnPitch);
		if (!rounds.open()) rounds.start(opponentName);
		rounds.tick(me, opponent, observer.body(), action, dNow, tNow);
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS); hasLastAction = true;
		clicked = action[ActionSpace.ATTACK] == 1; clickResolved = false; actedThisTick = true; ticks++;
		observer.fillGeometry(facts, me, opponent);
		if (KeyedSkin.showReal() && blind.note(facts)) KeyedSkin.setShowReal(false);
	}

	@Override
	public void endTick() {
		if (stopped || !clicked || clickResolved) return;
		clickResolved = true;
		int made = OwnSwing.count();
		String kind = made != ownSwingsSeen ? OwnSwing.lastKind() : "miss";
		if (made != ownSwingsSeen) ownSwingsSeen = made;
		rounds.swing(kind);
		// Tell the network immediately on connect (matching 0 ms training), not 2-4 ticks later when hurtTime returns from the server.
		if (opponent != null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == opponent) {
			clock.hitDealt();
			observer.body().updateExchange(true, "crit".equals(kind), false);
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || !inControl()) return false;
		actedThisTick = false;
		AgentController.hold(mc, action);
		return clicked;
	}

	@Override
	public void stop(String reason) {
		if (stopped) return;
		stopped = true; finishRound("open"); AgentController.release(mc);
		KeyedSkin.setTarget(null); KeyedSkin.setActive(false); KeyedSkin.setShowReal(false);
		OptionsGuard.restore(mc); grabber.close();
		SwordAi.LOGGER.info("Stopped ({}): fight vs {} ({} ticks, {}-{})", reason, opponentName, ticks, kills, deaths);
		if (mc.player != null) mc.player.displayClientMessage(Component.literal("[SwordAI] Stopped (" + reason + ")."), false);
	}

	private void resetEpisodeState() { memory.reset(); motor.reset(); clock.reset(); observer.resetEpisode(); hasLastAction = false; }
	private void handBack() { clicked = false; if (!handedBack) { handedBack = true; AgentController.release(mc); } }
	private boolean spectating(UUID id) {
		return mc.getConnection() != null && mc.getConnection().getPlayerInfo(id) != null
				&& mc.getConnection().getPlayerInfo(id).getGameMode() == GameType.SPECTATOR;
	}

	private void finishRound(String res) {
		if (!rounds.open()) return;
		int ping = (mc.getConnection() != null && opponentId != null && mc.getConnection().getPlayerInfo(opponentId) != null)
				? mc.getConnection().getPlayerInfo(opponentId).getLatency() : 0;
		String line = rounds.finish(res, ping);
		if (line != null) SwordAi.LOGGER.info("round: {}", line);
	}
}
