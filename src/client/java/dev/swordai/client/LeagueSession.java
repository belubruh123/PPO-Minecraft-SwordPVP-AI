package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.env.*;
import dev.swordai.core.league.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Staged rule-free league training (/swordai school) and 200-pair evaluation (/swordai school check):
 * Stage 2 = fixed reward + league; Stage 3 = + GRU; Stage 4 = + VPT IDM & human clones/demos; Stage 5 = + MoveSearch.
 */
final class LeagueSession implements AgentSession {
	enum Mode { TRAIN, CHECK }

	private static final int ROLLOUT = 4096, MAX_EP_TICKS = 600, SETTLE = 4, SNAP_EVERY = 8, SEARCH_EVERY = 25;
	private static final float PRIOR_KL = 0.02f;

	private final Minecraft mc;
	private final MinecraftServer server;
	private final Mode mode;
	private final int stage;
	private final Config cfg;
	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	private final SessionClock clock = new SessionClock();
	private final StepFacts facts = new StepFacts();
	private final AimMotor motor;
	private final Obs obs = new Obs(), prevObs = new Obs();
	private final LeagueLearner.Decision dec = new LeagueLearner.Decision(), prevDec = new LeagueLearner.Decision();
	private final int[] lastAction = new int[ActionSpace.HEADS];
	private final Random rng = new Random();
	private final RoundRecorder recorder = new RoundRecorder();

	private final LeagueLearner learner;
	private final SwordNet priorNet, selfNet, netA, netB;
	private final SwordNet.Workspace wsA, wsB;
	private final League league = new League();
	private final InverseDynamics idm = new InverseDynamics(7L);
	private final HumanImitation humans = new HumanImitation();
	private final MoveSearch moveSearch = new MoveSearch();
	private final LeaguePilots.ExploiterPilot exploiter;
	private final ObsMemory memCheck = new ObsMemory();
	private final List<CheckOpp> checkPool;
	private final Path benchDirA, benchDirB;

	private League.Match currentMatch;
	private MoveSearch.SearchJob activeSearch;
	private int searchTick, searchMaxOwn, searchMaxOpp, checkPair, waitingEp, expectedPutBack, settleLeft, epTicks, epDone, activeLag;
	private int ownCombo, oppCombo, critChain, ownSwingsSeen = OwnSwing.count();
	private int statClose, statOnBody, statClicks, statLowChg, statWhiffs, statSwings;
	private final int[] chkClose = new int[2], chkOnBody = new int[2], chkClicks = new int[2], chkLowChg = new int[2], chkWhiffs = new int[2];
	private float searchDealt, searchTaken, prevPhi, epDealt, epTaken, turnYaw, turnPitch;
	private boolean checkSideB, stopped, arenaRequested, resetPending, waitingPutBack, hasPrev, actedThisTick, clicked, clickResolved;
	private String checkOppName = "";
	private long totalTicks;
	private final long startedMs = System.currentTimeMillis();

	private record CheckOpp(String name, League.SparStyle spar, HumanImitation.HumanPolicy human, SwordNet net) {}

	LeagueSession(Minecraft mc, Mode mode) throws IOException {
		this.mc = mc; this.server = mc.getSingleplayerServer(); this.mode = mode;
		this.stage = SwordFiles.readLeagueStage(); this.cfg = SwordFiles.loadConfig(); this.motor = new AimMotor(cfg.aimResponse);
		Path ckpt = SwordFiles.schoolCheckpoint(), base = SwordFiles.baselineCheckpoint(), prior = SwordFiles.priorCheckpoint();

		if (mode == Mode.TRAIN) {
			learner = new LeagueLearner(ROLLOUT, System.nanoTime());
			if (Files.exists(ckpt)) learner.load(ckpt); else if (Files.exists(base)) learner.load(base);
			learner.setGruEnabled(stage >= 3);
			priorNet = Files.exists(prior) ? LeagueLearner.loadActorOnly(prior) : null;
			if (priorNet != null) priorNet.gruEnabled = priorNet.gruWeightNorm() > 0f;
			selfNet = new SwordNet(2L); selfNet.gruEnabled = learner.actor.gruEnabled;
			LeagueLearner.copyWeights(learner.actor, selfNet);
			league.addSnapshot("start", learner.actor);

			LeagueLearner expL = new LeagueLearner(2048, System.nanoTime() ^ 0x55AA55AAL);
			if (Files.exists(SwordFiles.exploiterCheckpoint())) { try { expL.load(SwordFiles.exploiterCheckpoint()); } catch (IOException ignored) {} }
			else LeagueLearner.copyWeights(learner.actor, expL.actor);
			expL.setGruEnabled(stage >= 3);
			exploiter = new LeaguePilots.ExploiterPilot(expL, cfg.aimResponse);

			if (stage >= 4) {
				try { idm.load(SwordFiles.idmCheckpoint()); } catch (IOException ignored) {}
				int nReal = humans.loadDirectory(SwordFiles.realFightsDir());
				if (nReal > 0) { humans.buildFromRealRounds(idm, 42L); league.setHumanOpponents(humans.opponents()); }
				SwordAi.LOGGER.info("League Stage {}: {} real rounds -> {} human clones, {} winning demos",
						stage, nReal, humans.opponents().size(), humans.winningDemos().size);
			}
			netA = netB = null; wsA = wsB = null; checkPool = List.of(); benchDirA = benchDirB = null;
		} else {
			if (!Files.exists(ckpt)) throw new IOException("no candidate checkpoint at " + ckpt);
			if (!Files.exists(base)) throw new IOException("no baseline checkpoint at " + base);
			netA = LeagueLearner.loadActorOnly(ckpt); netB = LeagueLearner.loadActorOnly(base);
			wsA = netA.newWorkspace(); wsB = netB.newWorkspace();
			learner = null; priorNet = selfNet = null; exploiter = null;
			try { idm.load(SwordFiles.idmCheckpoint()); } catch (IOException ignored) {}
			humans.loadDirectory(SwordFiles.realFightsDir()); humans.buildFromRealRounds(idm, 42L);
			List<CheckOpp> pool = new ArrayList<>();
			for (int i = 0; i < 4; i++) pool.add(new CheckOpp("baseline", null, null, netB));
			Path ancDir = SwordFiles.anchorsDir();
			if (Files.isDirectory(ancDir)) {
				try (var s = Files.list(ancDir)) {
					for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".bin")).sorted().toList()) {
						try {
							String tag = p.getFileName().toString().replaceFirst("\\.bin$", "").toLowerCase(Locale.ROOT)
									.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
							pool.add(new CheckOpp("anchor-" + (tag.isEmpty() ? "fixed" : tag), null, null, LeagueLearner.loadActorOnly(p)));
						} catch (IOException ignored) {}
					}
				}
			}
			var hps = humans.opponents();
			for (int i = 0; i < 4 && !hps.isEmpty(); i++) {
				var h = hps.get(i % hps.size());
				String c = h.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-").replaceAll("^-|-$", "");
				pool.add(new CheckOpp("human-" + (c.isEmpty() ? "real" : c), null, h, null));
			}
			pool.add(new CheckOpp("crit-spammer", League.SparStyle.CRIT_SPAMMER, null, null));
			pool.add(new CheckOpp("jump-in-critter", League.SparStyle.JUMP_IN_CRITTER, null, null));
			pool.add(new CheckOpp("combo-player", League.SparStyle.COMBO_PLAYER, null, null));
			pool.add(new CheckOpp("mixer", League.SparStyle.MIXER, null, null));
			checkPool = pool; benchDirA = SwordFiles.benchDir().resolve("A"); benchDirB = SwordFiles.benchDir().resolve("B");
			Files.deleteIfExists(benchDirA.resolve("DONE"));
			Files.deleteIfExists(benchDirB.resolve("DONE"));
		}

		boolean fast = cfg.trainSpeed > 1f;
		int visFov = Math.min(cfg.visionFov, cfg.fov);
		float crop = ViewGeometry.cropFor(visFov, cfg.fov);
		OptionsGuard.apply(mc, cfg.fov, fast);
		if (fast) { WindowGuard.shrink(mc, crop, cfg.speedWindowRows); SwordAiClient.setTickMillis(50f / cfg.trainSpeed); }
		ViewGeometry.setFov(visFov); grabber.setCrop(crop); grabber.setSpeed(cfg.trainSpeed); grabber.setEnabled(true);
		KeyedSkin.setTarget(null); KeyedSkin.setShowReal(false); KeyedSkin.setActive(true);

		UUID meId = mc.player.getUUID();
		server.execute(() -> {
			ArenaServer.start(server, meId, cfg);
			if (mode == Mode.TRAIN && stage >= 4) ArenaServer.setInverseDynamics(idm);
			if (fast) ArenaServer.setSpeed(cfg.trainSpeed);
		});
		arenaRequested = true;
	}

	String startLine() {
		return mode == Mode.TRAIN
				? String.format(Locale.ROOT, "League training started (Stage %d, GRU=%s, humans=%d, steps=%d).", stage, stage >= 3, humans.opponents().size(), learner.totalSteps)
				: String.format(Locale.ROOT, "Paired check started (A=%s vs B=%s, %d pool slots).", SwordFiles.schoolCheckpoint().getFileName(), SwordFiles.baselineCheckpoint().getFileName(), checkPool.size());
	}

	@Override public boolean stopped() { return stopped; }
	@Override public boolean inControl() { return !stopped && mc.screen == null; }
	@Override public boolean requestDebugDump() { return false; }
	@Override public String describe() { return mode == Mode.TRAIN ? ("league stage " + stage) : ("paired check pair " + checkPair); }
	@Override public void endTick() {
		if (stopped || !clicked || clickResolved) return;
		clickResolved = true;
		int made = OwnSwing.count();
		boolean hit = made != ownSwingsSeen;
		String kind = hit ? OwnSwing.lastKind() : "miss";
		if (hit) { ownSwingsSeen = made; statSwings++; }
		else { statWhiffs++; if (mode == Mode.CHECK) chkWhiffs[checkSideB ? 1 : 0]++; }
		if (mode == Mode.CHECK) recorder.swing(kind);
	}
	@Override public void worldDrawn() { if (!stopped && mc.screen == null) grabber.worldDrawn(mc); }
	@Override public float viewYawLag(float pt) { return -(1f - pt) * turnYaw; }
	@Override public float viewPitchLag(float pt) { return -(1f - pt) * turnPitch; }

	@Override
	public void tick() {
		if (stopped) return;
		actedThisTick = false; turnYaw = turnPitch = 0f;
		LocalPlayer me = mc.player;
		if (me == null || mc.level == null) { stop("left the world"); return; }
		if (mc.screen != null) { AgentController.release(mc); return; }
		Truth truth = ArenaServer.truth();
		if (truth.error() != null) { stop("arena error: " + truth.error()); return; }
		if (!truth.active() || !truth.botReady() || truth.botUuid() == null) return;
		Player bot = mc.level.getPlayerByUUID(truth.botUuid()); if (bot == null) return;
		SeenLag.follow(bot.getUUID());
		boolean activeFight = !resetPending && !waitingPutBack && settleLeft == 0 && !arenaRequested;
		SeenLag.tick(activeFight);

		if (resetPending) { if (truth.episodeId() == waitingEp) return; resetPending = false; settleLeft = SETTLE; }
		if (waitingPutBack) { if (ArenaServer.scenesPutBack < expectedPutBack) return; waitingPutBack = false; return; }
		if (settleLeft > 0) {
			AgentController.release(mc);
			if (--settleLeft == 0 && (activeSearch != null || (currentMatch != null && currentMatch.startMoment != null))) {
				expectedPutBack = ArenaServer.scenesPutBack + 1; waitingPutBack = true; server.execute(ArenaServer::putBackScene);
			}
			return;
		}
		if (arenaRequested) { arenaRequested = false; requestNextEpisode(truth); return; }
		Opponents.keepView(mc, me);
		if (!observer.capture(grabber)) return;

		CombatLog.drain(facts);
		boolean koD = facts.koDealt, koT = facts.koTaken, dNow = facts.damageDealt > 0f, tNow = facts.damageTaken > 0f, dCrit = facts.critDealt;
		epDealt += facts.damageDealt; epTaken += facts.damageTaken;
		if (dNow) { clock.hitDealt(); ownCombo++; oppCombo = 0; critChain = dCrit ? critChain + 1 : 0; }
		if (tNow) { clock.hitTaken(); oppCombo++; ownCombo = 0; critChain = 0; }
		clock.tick();
		observer.body().updateExchange(dNow, dCrit, tNow);
		observer.fillGeometry(facts, me, bot);
		observer.fillObs(obs, me, bot, truth, facts, hasPrev ? lastAction : null, ActionSpace.ALL_HEADS, clock, motor);

		if (mode == Mode.CHECK) tickCheck(me, bot, truth, dNow, tNow, koD, koT);
		else if (activeSearch != null) tickSearch(me, truth, koD, koT);
		else tickTrain(me, truth, dNow, tNow, dCrit, koD, koT);
		totalTicks++;
	}

	private void tickTrain(LocalPlayer me, Truth truth, boolean dNow, boolean tNow, boolean dCrit, boolean koD, boolean koT) {
		boolean done = koD || koT || epTicks >= MAX_EP_TICKS;
		float phi = FixedReward.potential(me.getHealth() / 20f, truth.botHealth() / 20f, ownCombo, oppCombo,
				me.getAttackStrengthScale(0f), truth.botCharge(), facts.reachDist, done);
		if (hasPrev) {
			learner.record(prevObs, prevDec, FixedReward.step(facts.damageDealt, facts.damageTaken, koD, koT, prevPhi, phi), done, null, 0f);
			if (learner.rollout.isFull()) runUpdate(done ? 0f : learner.bootstrapValue(obs));
		}
		prevPhi = phi;
		if (stage >= 5 && !done && epTicks > 8 && facts.reachDist < 5.2f) {
			MoveSearch.Kind k = detectMoment(dCrit, dNow, tNow, truth);
			if (k != null) moveSearch.recordMoment(k, ArenaServer.sceneAt(truth.tick()), obs, learner.lastHistIn(), learner.lastSeenIn(), learner.lastHiddenIn());
		}
		if (done) {
			if (currentMatch != null) league.recordOutcome(currentMatch, koD || (!koT && epDealt > epTaken));
			if (stage >= 5 && ++epDone % SEARCH_EVERY == 0 && moveSearch.totalMoments() >= 4) activeSearch = moveSearch.startSearch(rng);
			requestNextEpisode(truth); return;
		}
		learner.decide(obs, false, 1f, dec);
		prevObs.copyFrom(obs); System.arraycopy(dec.action, 0, prevDec.action, 0, ActionSpace.HEADS);
		prevDec.logp = dec.logp; prevDec.value = dec.value;
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
		applyAction(dec.action); epTicks++;
	}

	private void tickSearch(LocalPlayer me, Truth truth, boolean koD, boolean koT) {
		searchDealt += facts.damageDealt; searchTaken += facts.damageTaken;
		searchMaxOwn = Math.max(searchMaxOwn, ownCombo); searchMaxOpp = Math.max(searchMaxOpp, oppCombo);
		if (koD || koT || searchTick >= MoveSearch.ROLLOUT_TICKS) {
			activeSearch.finishTrial(searchDealt, searchTaken, koD, koT, searchMaxOwn, searchMaxOpp);
			if (activeSearch.finished()) { moveSearch.commitSearch(activeSearch); activeSearch = null; }
			requestNextEpisode(truth); return;
		}
		learner.decide(obs, false, 1f, dec);
		if (searchTick < MoveSearch.MACRO_TICKS) {
			activeSearch.currentCandidate().apply(dec.action, searchTick, me.getAttackStrengthScale(0f) >= 0.92f, !me.onGround() && me.getY() < me.yo);
		}
		if (searchTick == 0) activeSearch.noteFirstAction(dec.action);
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
		applyAction(dec.action); searchTick++; epTicks++;
	}

	private void tickCheck(LocalPlayer me, Player bot, Truth truth, boolean dNow, boolean tNow, boolean koD, boolean koT) {
		if (koD || koT || epTicks >= MAX_EP_TICKS) {
			String side = checkSideB ? "B" : "A", ko = koD ? ", knockout" : koT ? ", knocked out" : "";
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school pair %d %s %s: dealt %.1f, taken %.1f, ticks %d%s",
					checkPair, side, checkOppName, epDealt, epTaken, epTicks, ko));
			String res = koD ? "won" : koT ? "lost" : "open";
			String benchTag = "baseline".equals(checkOppName) ? "h2h" : checkOppName;
			recorder.finishTo(checkSideB ? benchDirB : benchDirA, String.format(Locale.ROOT, "%04d-%s.txt.gz", checkPair, benchTag), res);
			if (checkSideB) {
				checkSideB = false; checkPair++;
				if (checkPair % 25 == 0) logCheckMechanics();
			} else checkSideB = true;
			requestNextEpisode(truth); return;
		}
		SwordNet net = checkSideB ? netB : netA; SwordNet.Workspace ws = checkSideB ? wsB : wsA;
		memCheck.pushSeen(obs.vec);
		net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, memCheck.hist, memCheck.seen, memCheck.hidden, memCheck.hidden);
		net.samplePlay(ws, ws.step, rng, dec.action);
		memCheck.pushAction(dec.action);
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
		applyAction(dec.action); recorder.tick(me, bot, observer.body(), dec.action, dNow, tNow); epTicks++;
	}

	private void applyAction(int[] a) {
		motor.step(ActionSpace.yawDegrees(a), ActionSpace.pitchDegrees(a));
		turnYaw = motor.yawRate(); turnPitch = motor.pitchRate();
		AgentController.apply(mc, a, turnYaw, turnPitch);
		actedThisTick = true;
		clicked = a[ActionSpace.ATTACK] == 1; clickResolved = false;
		int si = checkSideB ? 1 : 0;
		if (facts.reachDist < 4.5f) {
			statClose++; if (facts.onBody) statOnBody++;
			if (mode == Mode.CHECK) { chkClose[si]++; if (facts.onBody) chkOnBody[si]++; }
		}
		if (clicked) {
			statClicks++;
			boolean low = mc.player.getAttackStrengthScale(0f) < 0.85f;
			if (low) statLowChg++;
			if (mode == Mode.CHECK) { chkClicks[si]++; if (low) chkLowChg[si]++; }
		}
	}

	private void logCheckMechanics() {
		SwordAi.LOGGER.info(String.format(Locale.ROOT,
				"check mechanics @ pair %d | A: aim %.1f%% lowChg %.1f%% whiff %.1f%% (%d clicks) | B: aim %.1f%% lowChg %.1f%% whiff %.1f%% (%d clicks)",
				checkPair,
				100f * chkOnBody[0] / Math.max(1, chkClose[0]), 100f * chkLowChg[0] / Math.max(1, chkClicks[0]), 100f * chkWhiffs[0] / Math.max(1, chkClicks[0]), chkClicks[0],
				100f * chkOnBody[1] / Math.max(1, chkClose[1]), 100f * chkLowChg[1] / Math.max(1, chkClicks[1]), 100f * chkWhiffs[1] / Math.max(1, chkClicks[1]), chkClicks[1]));
	}

	private void runUpdate(float bootstrap) {
		server.execute(() -> ArenaServer.setIdle(true)); AgentController.release(mc);
		// Strong initial prior pull for the first 6 updates internalises legacy SwingGate/whiffGuard
		// into pure SwordNet weights (zero play-time rules), then relaxes to AlphaStar's gentle 0.02 KL pull.
		float pKl = learner.updates < 6 ? 0.50f : PRIOR_KL;
		LeaguePpo.Stats s = learner.update(bootstrap, priorNet, pKl, 0f);
		LeagueLearner.copyWeights(learner.actor, selfNet);
		float demoCe = 0f, searchCe = 0f;
		if (stage >= 4) {
			if (idm.sampleCount() >= 256) {
				idm.train(12, 64, 1e-3f, rng);
				if (learner.updates % 10 == 0 && humans.roundCount() > 0) {
					humans.buildFromRealRounds(idm, 42L + learner.updates);
					league.setHumanOpponents(humans.opponents());
				}
			}
			var wd = humans.winningDemos(); float dw = league.demoWeight();
			if (wd.size >= 32 && dw > 0.03f) {
				int bs = 32; byte[][] bp = new byte[bs][], bf = new byte[bs][], ba = new byte[bs][];
				float[][] bv = new float[bs][], bh = new float[bs][], bse = new float[bs][];
				for (int i = 0; i < bs; i++) {
					int k = rng.nextInt(wd.size);
					bp[i] = wd.peri[k]; bf[i] = wd.fovea[k]; bv[i] = wd.vec[k]; bh[i] = wd.hist[k]; bse[i] = wd.seen[k]; ba[i] = wd.action[k];
				}
				demoCe = learner.ppo.supervisedStep(learner.actor, bp, bf, bv, bh, bse, null, ba, bs, learner.ppo.lr * dw);
			}
		}
		if (stage >= 5 && moveSearch.targetCount() >= 8) searchCe = moveSearch.trainActor(learner.actor, learner.ppo, 2, learner.ppo.lr * 0.5f, rng);
		if (learner.updates % SNAP_EVERY == 0) league.addSnapshot("u" + learner.updates, learner.actor);
		saveAll();
		double secs = Math.max(1.0, (System.currentTimeMillis() - startedMs) / 1000.0);
		SwordAi.LOGGER.info(String.format(Locale.ROOT,
				"league update %d (stage %d), %d steps (%.0f/s): pol %.4f val %.4f kl %.4f ent %.3f pKl %.3f ev %.2f lr %.1e aim %.0f%% low %.1f%% whf %.1f%% gru|w| %.2f idm %.0f%% demoCe %.2f search %d/%d(ce %.2f) | %s",
				learner.updates, stage, learner.totalSteps, totalTicks / secs, s.policyLoss, s.valueLoss, s.approxKl, s.entropy, s.priorKl,
				s.explainedVar, s.lr, 100f * statOnBody / Math.max(1, statClose), 100f * statLowChg / Math.max(1, statClicks),
				100f * statWhiffs / Math.max(1, statClicks), learner.actor.gruWeightNorm(), idm.lastAccuracy() * 100f, demoCe,
				moveSearch.targetsFound(), moveSearch.searchesDone(), searchCe, league.summary()));
		statClose = statOnBody = statClicks = statLowChg = statWhiffs = statSwings = 0;
		server.execute(() -> ArenaServer.setIdle(false));
	}

	private void requestNextEpisode(Truth truth) {
		observer.resetEpisode(); clock.reset(); motor.reset(); if (learner != null) learner.resetEpisode(); memCheck.reset();
		hasPrev = false; prevPhi = epDealt = epTaken = 0f; epTicks = ownCombo = oppCombo = critChain = 0;
		EpisodePlan plan;
		if (mode == Mode.CHECK) {
			CheckOpp opp = checkPool.get(checkPair % checkPool.size()); checkOppName = opp.name();
			long seed = 0x517CC1B727220A95L ^ (checkPair * 0x9E3779B97F4A7C15L);
			plan = opp.net() != null ? EpisodePlan.ofPilot(new LeaguePilots.NetPilot(opp.net(), cfg.aimResponse, 0f, seed + 1L, null), seed, true)
					: opp.human() != null ? EpisodePlan.ofPilot(new LeaguePilots.HumanPilot(opp.human(), seed + 1L), seed, true)
					: EpisodePlan.ofSpar(opp.spar(), seed, true);
			// Alternating 0 ms and 100 ms server-lag pairs so the check measures real multiplayer lag robustness.
			activeLag = (checkPair % 2 == 1) ? 2 : 0;
			plan.agentLag = activeLag; plan.botLag = activeLag; plan.botPing = activeLag; plan.agentKnockLag = activeLag > 0 ? 1 : 0;
			recorder.start(checkOppName);
		} else if (activeSearch != null) {
			searchTick = searchMaxOwn = searchMaxOpp = 0; searchDealt = searchTaken = 0f; activeLag = 0;
			plan = EpisodePlan.ofPilot(new LeaguePilots.NetPilot(selfNet, cfg.aimResponse, 1f, rng.nextLong(), null), 0xA1F0L + activeSearch.moment.kind.ordinal(), false);
			plan.scene = activeSearch.moment.arenaState;
		} else {
			currentMatch = league.sampleMatch(rng, stage >= 5 ? moveSearch : null, true);
			InverseDynamics logIdm = stage >= 4 ? idm : null;
			plan = switch (currentMatch.kind) {
				case LATEST_SELF -> EpisodePlan.ofPilot(new LeaguePilots.NetPilot(selfNet, cfg.aimResponse, 1f, rng.nextLong(), logIdm), 0L, false);
				case PAST_SNAPSHOT -> EpisodePlan.ofPilot(new LeaguePilots.NetPilot(currentMatch.snapshot.net, cfg.aimResponse, 1f, rng.nextLong(), logIdm), 0L, false);
				case EXPLOITER -> EpisodePlan.ofPilot(exploiter, 0L, false);
				case HUMAN_CLONE -> EpisodePlan.ofPilot(new LeaguePilots.HumanPilot(currentMatch.human, rng.nextLong()), 0L, false);
				case SPAR_BOT -> EpisodePlan.ofSpar(currentMatch.sparStyle, 0L, false);
			};
			// Sim-to-real latency randomization (OpenAI Five / GT Sophy): 50% 0 ms, 50% 50-150 ms server lag.
			activeLag = rng.nextBoolean() ? (1 + rng.nextInt(3)) : 0;
			plan.agentLag = activeLag; plan.botLag = activeLag; plan.botPing = activeLag;
			plan.agentKnockLag = activeLag > 0 ? Math.max(1, activeLag - 1) : 0;
			if (stage >= 5 && currentMatch.startMoment != null) plan.scene = currentMatch.startMoment.arenaState;
		}
		SeenLag.set(activeLag, 0f, 0);
		waitingEp = truth.episodeId(); resetPending = true;
		server.execute(() -> ArenaServer.reset(plan));
	}

	private MoveSearch.Kind detectMoment(boolean dCrit, boolean dNow, boolean tNow, Truth truth) {
		if (dCrit && critChain >= 1) return MoveSearch.Kind.AFTER_PUNISH_CRIT;
		if (truth.botFalling() && facts.reachDist < 4.5f) return MoveSearch.Kind.COUNTER_CRIT;
		if (dNow && ownCombo >= 1 && ownCombo <= 3) return MoveSearch.Kind.HOLD_COMBO;
		if (!dNow && !tNow && facts.reachDist >= 2.2f && facts.reachDist <= 3.6f && mc.player.getAttackStrengthScale(0f) >= 0.95f
				&& clock.ticksSinceHitDealt > 20 && clock.ticksSinceHitTaken > 20 && rng.nextFloat() < 0.08f) return MoveSearch.Kind.COMBO_START;
		return null;
	}

	private void saveAll() {
		try {
			learner.save(SwordFiles.schoolCheckpoint());
			if (exploiter != null) exploiter.learner.save(SwordFiles.exploiterCheckpoint());
			if (stage >= 4 && idm.trainedSteps() > 0) idm.save(SwordFiles.idmCheckpoint());
		} catch (IOException e) { SwordAi.LOGGER.warn("could not save checkpoints", e); }
	}

	@Override public boolean beforePlayerMoves() { if (!actedThisTick || stopped) return false; actedThisTick = false; AgentController.hold(mc, dec.action); return clicked; }

	@Override
	public void stop(String reason) {
		if (stopped) return;
		stopped = true;
		if (mode == Mode.TRAIN && learner != null) saveAll();
		if (mode == Mode.CHECK && benchDirA != null) {
			logCheckMechanics();
			try { Files.createDirectories(benchDirA); Files.writeString(benchDirA.resolve("DONE"), "ok\n");
				Files.createDirectories(benchDirB); Files.writeString(benchDirB.resolve("DONE"), "ok\n"); } catch (IOException ignored) {}
		}
		AgentController.release(mc); SwordAiClient.setTickMillis(0f);
		SeenLag.off(); KeyedSkin.setTarget(null); KeyedSkin.setActive(false); KeyedSkin.setShowReal(false);
		WindowGuard.restore(mc); OptionsGuard.restore(mc); grabber.close();
		server.execute(() -> ArenaServer.stop(server));
		SwordAi.LOGGER.info("Stopped ({}): {} ({} ticks)", reason, describe(), totalTicks);
		if (mc.player != null) mc.player.displayClientMessage(Component.literal("[SwordAI] Stopped (" + reason + ")."), false);
	}

	@Override
	public List<String> statusLines() {
		return mode == Mode.CHECK ? List.of("Check pair " + checkPair + (checkSideB ? " B " : " A ") + checkOppName)
				: List.of(String.format(Locale.ROOT, "Stage %d: %d updates, %d steps | %s", stage, learner.updates, learner.totalSteps, league.summary()));
	}
}
