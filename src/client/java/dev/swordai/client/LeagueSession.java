package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.league.FixedReward;
import dev.swordai.core.league.HumanImitation;
import dev.swordai.core.league.InverseDynamics;
import dev.swordai.core.league.League;
import dev.swordai.core.league.LeagueLearner;
import dev.swordai.core.league.LeaguePpo;
import dev.swordai.core.league.MoveSearch;
import dev.swordai.core.league.ObsMemory;
import dev.swordai.core.league.SwordNet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.Obs;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.CombatLog;
import dev.swordai.server.EpisodePlan;
import dev.swordai.server.SceneRecorder;
import dev.swordai.server.SparPilot;
import dev.swordai.server.Truth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Unified, rule-free league training and paired 200-round evaluation session:
 * <ul>
 *   <li>Stage 2: Fixed reward + Ng potential shaping + League (self, PFSP snapshots, Exploiter, small SparBot share) + Step D obs.</li>
 *   <li>Stage 3: + GRU recurrent memory ({@code gruEnabled = true}, BPTT).</li>
 *   <li>Stage 4: + VPT {@link InverseDynamics} + BC Real-Human opponents ({@link HumanImitation}, main non-self opponents) + outcome-filtered winning human demos.</li>
 *   <li>Stage 5: + AlphaZero-style {@link MoveSearch} on the 4 key moments + 25% key-moment start states.</li>
 * </ul>
 * Check mode ({@code /swordai school check}) runs paired fights (A = current checkpoint, B = previous stage baseline)
 * emitting exact {@code school pair} lines for {@code tools/schoolpairs.py} and saving gzipped rounds for {@code tools/bench.py}.
 */
final class LeagueSession implements AgentSession {
	enum Mode { TRAIN, CHECK }

	private static final int ROLLOUT_SIZE = 4096;
	private static final int MAX_EPISODE_TICKS = 600;
	private static final int SETTLE_TICKS = 4;
	private static final int SNAPSHOT_EVERY_UPDATES = 8;
	private static final int SEARCH_EVERY_EPISODES = 25;
	private static final float PRIOR_KL_WEIGHT = 0.02f;

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
	private final Obs obs = new Obs();
	private final Obs prevObs = new Obs();
	private final LeagueLearner.Decision dec = new LeagueLearner.Decision();
	private final LeagueLearner.Decision prevDec = new LeagueLearner.Decision();
	private final int[] lastAction = new int[ActionSpace.HEADS];
	private final Random rng = new Random();
	private final RoundRecorder recorder = new RoundRecorder();

	// Training state (mode == TRAIN).
	private final LeagueLearner learner;
	private final SwordNet priorNet;
	private final SwordNet selfNet;
	private final League league = new League();
	private final InverseDynamics idm = new InverseDynamics(7L);
	private final HumanImitation humans = new HumanImitation();
	private final MoveSearch moveSearch = new MoveSearch();
	private final LeaguePilots.ExploiterPilot exploiter;
	private League.Match currentMatch;
	private MoveSearch.SearchJob activeSearch;
	private int searchTrialTick;
	private float searchDmgDealt, searchDmgTaken;
	private int searchMaxOwnCombo, searchMaxOppCombo;

	// Check state (mode == CHECK): A = candidate, B = previous-stage baseline.
	private final SwordNet netA;
	private final SwordNet netB;
	private final SwordNet.Workspace wsA;
	private final SwordNet.Workspace wsB;
	private final ObsMemory memCheck = new ObsMemory();
	private final List<CheckOpp> checkPool;
	private int checkPairIdx;
	private boolean checkSideB;
	private String checkOppName = "";
	private final Path benchDirA;
	private final Path benchDirB;

	private record CheckOpp(String name, League.SparStyle spar, HumanImitation.HumanPolicy human, boolean isBaseline) {
	}

	// Episode lifecycle.
	private boolean stopped;
	private boolean arenaRequested;
	private boolean resetPending;
	private int waitingEpisodeId;
	private boolean waitingScenePutBack;
	private int expectedPutBack;
	private int settleLeft;
	private int epTicks;
	private int episodesDone;
	private boolean hasPrev;
	private float prevPhi;
	private float epDealt, epTaken;
	private int ownCombo, oppCombo, critChain;
	private int ownSwingsSeen = OwnSwing.count();
	private boolean actedThisTick;
	private float turnYaw, turnPitch;
	private long totalTicks;
	private final long startedMs = System.currentTimeMillis();

	LeagueSession(Minecraft mc, Mode mode) throws IOException {
		this.mc = mc;
		this.server = mc.getSingleplayerServer();
		this.mode = mode;
		this.stage = SwordFiles.readLeagueStage();
		this.cfg = SwordFiles.loadConfig();
		this.motor = new AimMotor(cfg.aimResponse);

		Path ckpt = SwordFiles.schoolCheckpoint();
		Path base = SwordFiles.baselineCheckpoint();
		Path prior = SwordFiles.priorCheckpoint();

		if (mode == Mode.TRAIN) {
			this.learner = new LeagueLearner(ROLLOUT_SIZE, System.nanoTime());
			if (Files.exists(ckpt)) {
				learner.load(ckpt);
			} else if (Files.exists(base)) {
				learner.load(base);
			}
			learner.actor.gruEnabled = (stage >= 3);
			this.priorNet = Files.exists(prior) ? LeagueLearner.loadActorOnly(prior) : null;
			if (this.priorNet != null) {
				this.priorNet.gruEnabled = (stage >= 3);
			}
			this.selfNet = new SwordNet(2L);
			this.selfNet.gruEnabled = learner.actor.gruEnabled;
			LeagueLearner.copyWeights(learner.actor, selfNet);
			league.addSnapshot("start", learner.actor);

			LeagueLearner expLearner = new LeagueLearner(2048, System.nanoTime() ^ 0x55AA55AAL);
			if (Files.exists(SwordFiles.exploiterCheckpoint())) {
				try {
					expLearner.load(SwordFiles.exploiterCheckpoint());
				} catch (IOException ignored) {
				}
			} else {
				LeagueLearner.copyWeights(learner.actor, expLearner.actor);
			}
			expLearner.actor.gruEnabled = learner.actor.gruEnabled;
			this.exploiter = new LeaguePilots.ExploiterPilot(expLearner, cfg.aimResponse);

			if (stage >= 4) {
				try {
					idm.load(SwordFiles.idmCheckpoint());
				} catch (IOException ignored) {
				}
				int nReal = humans.loadDirectory(SwordFiles.realFightsDir());
				if (nReal > 0) {
					humans.buildFromRealRounds(idm, 42L);
					league.setHumanOpponents(humans.opponents());
				}
				SwordAi.LOGGER.info("League Stage {}: loaded {} real rounds -> {} human clones, {} winning demos",
						stage, nReal, humans.opponents().size(), humans.winningDemos().size);
			}
			this.netA = this.netB = null;
			this.wsA = this.wsB = null;
			this.checkPool = List.of();
			this.benchDirA = this.benchDirB = null;
		} else {
			if (!Files.exists(ckpt)) throw new IOException("no candidate checkpoint at " + ckpt);
			if (!Files.exists(base)) throw new IOException("no baseline checkpoint at " + base);
			this.netA = LeagueLearner.loadActorOnly(ckpt);
			this.netB = LeagueLearner.loadActorOnly(base);
			this.wsA = netA.newWorkspace();
			this.wsB = netB.newWorkspace();
			this.learner = null;
			this.priorNet = this.selfNet = null;
			this.exploiter = null;

			// Deterministic check pool: 40% direct vs baseline, 40% real-human clones, 20% SparStyle.
			try {
				idm.load(SwordFiles.idmCheckpoint());
			} catch (IOException ignored) {
			}
			humans.loadDirectory(SwordFiles.realFightsDir());
			humans.buildFromRealRounds(idm, 42L);
			java.util.ArrayList<CheckOpp> pool = new java.util.ArrayList<>();
			for (int i = 0; i < 4; i++) pool.add(new CheckOpp("baseline", null, null, true));
			List<HumanImitation.HumanPolicy> hps = humans.opponents();
			for (int i = 0; i < 4 && !hps.isEmpty(); i++) {
				HumanImitation.HumanPolicy h = hps.get(i % hps.size());
				String clean = h.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-").replaceAll("^-|-$", "");
				if (clean.isEmpty()) clean = "real";
				pool.add(new CheckOpp("human-" + clean, null, h, false));
			}
			pool.add(new CheckOpp("crit-spammer", League.SparStyle.CRIT_SPAMMER, null, false));
			pool.add(new CheckOpp("jump-in-critter", League.SparStyle.JUMP_IN_CRITTER, null, false));
			pool.add(new CheckOpp("combo-player", League.SparStyle.COMBO_PLAYER, null, false));
			pool.add(new CheckOpp("mixer", League.SparStyle.MIXER, null, false));
			this.checkPool = pool;
			this.benchDirA = SwordFiles.benchDir().resolve("A");
			this.benchDirB = SwordFiles.benchDir().resolve("B");
		}

		boolean fast = cfg.trainSpeed > 1f;
		int visFov = Math.min(cfg.visionFov, cfg.fov);
		float crop = ViewGeometry.cropFor(cfg.fov, visFov);
		OptionsGuard.apply(mc, cfg.fov, fast);
		if (fast) {
			WindowGuard.shrink(mc, crop, cfg.speedWindowRows);
			SwordAiClient.setTickMillis(50f / cfg.trainSpeed);
		}
		ViewGeometry.setFov(visFov);
		grabber.setCrop(crop);
		grabber.setSpeed(cfg.trainSpeed);
		grabber.setEnabled(true);
		KeyedSkin.setShowReal(false);
		KeyedSkin.setActive(true);

		UUID meId = mc.player.getUUID();
		server.execute(() -> {
			ArenaServer.start(server, meId, cfg);
			if (mode == Mode.TRAIN && stage >= 4) {
				ArenaServer.setInverseDynamics(idm);
			}
			if (fast) ArenaServer.setSpeed(cfg.trainSpeed);
		});
		arenaRequested = true;
	}

	String startLine() {
		return mode == Mode.TRAIN
				? String.format(Locale.ROOT, "League training started (Stage %d, GRU=%s, humans=%d, steps=%d).",
						stage, stage >= 3, humans.opponents().size(), learner.totalSteps)
				: String.format(Locale.ROOT, "Paired 200-round check started (A=%s vs B=%s, %d pool slots).",
						SwordFiles.schoolCheckpoint().getFileName(), SwordFiles.baselineCheckpoint().getFileName(), checkPool.size());
	}

	@Override
	public boolean stopped() {
		return stopped;
	}

	@Override
	public boolean inControl() {
		return !stopped && mc.screen == null;
	}

	@Override
	public void tick() {
		if (stopped) return;
		actedThisTick = false;
		turnYaw = 0f;
		turnPitch = 0f;

		LocalPlayer me = mc.player;
		if (me == null || mc.level == null) {
			stop("left the world");
			return;
		}
		if (mc.screen != null) {
			AgentController.release(mc);
			return;
		}
		Truth truth = ArenaServer.truth();
		if (truth.error() != null) {
			stop("arena error: " + truth.error());
			return;
		}
		if (!truth.active() || !truth.botReady() || truth.botUuid() == null) {
			return;
		}
		Player bot = mc.level.getPlayerByUUID(truth.botUuid());
		if (bot == null) {
			return;
		}

		if (resetPending) {
			if (truth.episodeId() == waitingEpisodeId) {
				return;
			}
			resetPending = false;
			settleLeft = SETTLE_TICKS;
		}
		if (waitingScenePutBack) {
			if (ArenaServer.scenesPutBack < expectedPutBack) {
				return;
			}
			waitingScenePutBack = false;
			return;
		}
		if (settleLeft > 0) {
			AgentController.release(mc);
			if (--settleLeft == 0) {
				if ((activeSearch != null) || (currentMatch != null && currentMatch.startMoment != null)) {
					expectedPutBack = ArenaServer.scenesPutBack + 1;
					waitingScenePutBack = true;
					server.execute(ArenaServer::putBackScene);
				}
			}
			return;
		}
		if (arenaRequested) {
			arenaRequested = false;
			requestNextEpisode(truth);
			return;
		}
		Opponents.keepView(mc, me);
		if (!observer.capture(grabber)) {
			return;
		}

		CombatLog.drain(facts);
		boolean koDealt = facts.koDealt;
		boolean koTaken = facts.koTaken;
		boolean dealtNow = facts.damageDealt > 0f;
		boolean takenNow = facts.damageTaken > 0f;
		boolean dealtCrit = facts.critDealt;
		epDealt += facts.damageDealt;
		epTaken += facts.damageTaken;
		if (dealtNow) {
			clock.hitDealt();
			ownCombo++;
			oppCombo = 0;
			critChain = dealtCrit ? critChain + 1 : 0;
		}
		if (takenNow) {
			clock.hitTaken();
			oppCombo++;
			ownCombo = 0;
			critChain = 0;
		}
		clock.tick();
		observer.body().updateExchange(dealtNow, dealtCrit, takenNow);
		observer.fillGeometry(facts, me, bot);
		observer.fillObs(obs, me, bot, truth, facts, hasPrev ? lastAction : null, ActionSpace.ALL_HEADS, clock, motor);

		if (mode == Mode.CHECK) {
			tickCheck(me, bot, truth, dealtNow, takenNow, koDealt, koTaken);
		} else if (activeSearch != null) {
			tickSearch(me, bot, truth, koDealt, koTaken);
		} else {
			tickTrain(me, bot, truth, dealtNow, takenNow, dealtCrit, koDealt, koTaken);
		}
		totalTicks++;
	}

	private void tickTrain(LocalPlayer me, Player bot, Truth truth,
			boolean dealtNow, boolean takenNow, boolean dealtCrit, boolean koDealt, boolean koTaken) {
		boolean timeout = epTicks >= MAX_EPISODE_TICKS;
		boolean done = koDealt || koTaken || timeout;
		float phi = FixedReward.potential(me.getHealth() / 20f, truth.botHealth() / 20f,
				ownCombo, oppCombo, me.getAttackStrengthScale(0f), truth.botCharge(), facts.reachDist, done);
		if (hasPrev) {
			float r = FixedReward.step(facts.damageDealt, facts.damageTaken, koDealt, koTaken, prevPhi, phi);
			learner.record(prevObs, prevDec, r, done, null, 0f);
			if (learner.rollout.isFull()) {
				runTrainingUpdate(done ? 0f : learner.bootstrapValue(obs));
			}
		}
		prevPhi = phi;

		// Stage 5: capture decisive key moments for key-state starts and AlphaZero MoveSearch.
		if (stage >= 5 && !done && epTicks > 8 && facts.reachDist < 5.2f) {
			MoveSearch.Kind kind = detectKeyMoment(dealtCrit, dealtNow, takenNow, truth);
			if (kind != null) {
				double[] snap = ArenaServer.sceneAt(truth.tick());
				moveSearch.recordMoment(kind, snap, obs, learner.lastHistIn(), learner.lastSeenIn(), learner.lastHiddenIn());
			}
		}

		if (done) {
			if (currentMatch != null) {
				boolean won = koDealt || (!koTaken && epDealt > epTaken);
				league.recordOutcome(currentMatch, won);
			}
			episodesDone++;
			if (stage >= 5 && episodesDone % SEARCH_EVERY_EPISODES == 0 && moveSearch.totalMoments() >= 4) {
				activeSearch = moveSearch.startSearch(rng);
			}
			requestNextEpisode(truth);
			return;
		}

		learner.decide(obs, false, 1f, dec);
		prevObs.copyFrom(obs);
		System.arraycopy(dec.action, 0, prevDec.action, 0, ActionSpace.HEADS);
		prevDec.logp = dec.logp;
		prevDec.value = dec.value;
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS);
		hasPrev = true;
		applyAction(dec.action);
		epTicks++;
	}

	private void tickSearch(LocalPlayer me, Player bot, Truth truth, boolean koDealt, boolean koTaken) {
		searchDmgDealt += facts.damageDealt;
		searchDmgTaken += facts.damageTaken;
		searchMaxOwnCombo = Math.max(searchMaxOwnCombo, ownCombo);
		searchMaxOppCombo = Math.max(searchMaxOppCombo, oppCombo);

		if (koDealt || koTaken || searchTrialTick >= MoveSearch.ROLLOUT_TICKS) {
			activeSearch.finishTrial(searchDmgDealt, searchDmgTaken, koDealt, koTaken, searchMaxOwnCombo, searchMaxOppCombo);
			if (activeSearch.finished()) {
				moveSearch.commitSearch(activeSearch);
				activeSearch = null;
			}
			requestNextEpisode(truth);
			return;
		}

		learner.decide(obs, false, 1f, dec);
		if (searchTrialTick < MoveSearch.MACRO_TICKS) {
			boolean ready = me.getAttackStrengthScale(0f) >= 0.92f;
			boolean falling = !me.onGround() && me.getY() < me.yo;
			activeSearch.currentCandidate().apply(dec.action, searchTrialTick, ready, falling);
		}
		if (searchTrialTick == 0) {
			activeSearch.noteFirstAction(dec.action);
		}
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS);
		hasPrev = true;
		applyAction(dec.action);
		searchTrialTick++;
		epTicks++;
	}

	private void tickCheck(LocalPlayer me, Player bot, Truth truth, boolean dealtNow, boolean takenNow,
			boolean koDealt, boolean koTaken) {
		boolean done = koDealt || koTaken || epTicks >= MAX_EPISODE_TICKS;
		if (OwnSwing.count() != ownSwingsSeen) {
			ownSwingsSeen = OwnSwing.count();
			recorder.swing(OwnSwing.lastKind());
		}
		if (done) {
			String side = checkSideB ? "B" : "A";
			String koStr = koDealt ? ", knockout" : koTaken ? ", knocked out" : "";
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school pair %d %s %s: dealt %.1f, taken %.1f, ticks %d%s",
					checkPairIdx, side, checkOppName, epDealt, epTaken, epTicks, koStr));
			String res = koDealt ? "won" : koTaken ? "lost" : "open";
			String fname = String.format(Locale.ROOT, "pair%03d-%s-%s.txt.gz", checkPairIdx, checkOppName, res);
			recorder.finishTo(checkSideB ? benchDirB : benchDirA, fname, res);

			if (checkSideB) {
				checkSideB = false;
				checkPairIdx++;
			} else {
				checkSideB = true;
			}
			requestNextEpisode(truth);
			return;
		}

		SwordNet net = checkSideB ? netB : netA;
		SwordNet.Workspace ws = checkSideB ? wsB : wsA;
		memCheck.pushSeen(obs.vec);
		net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, memCheck.hist, memCheck.seen, memCheck.hidden, memCheck.hidden);
		net.sample(ws, ws.step, ActionSpace.ALL_HEADS, false, cfg.playTemperature, rng, dec.action);
		memCheck.pushAction(dec.action);
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS);
		hasPrev = true;
		applyAction(dec.action);
		recorder.tick(me, bot, observer.body(), dec.action, dealtNow, takenNow);
		epTicks++;
	}

	private void applyAction(int[] action) {
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		turnYaw = motor.yawRate();
		turnPitch = motor.pitchRate();
		AgentController.apply(mc, action, turnYaw, turnPitch);
		actedThisTick = true;
	}

	private void runTrainingUpdate(float bootstrap) {
		server.execute(() -> ArenaServer.setIdle(true));
		AgentController.release(mc);
		LeaguePpo.Stats stats = learner.update(bootstrap, priorNet, PRIOR_KL_WEIGHT, 0f);
		LeagueLearner.copyWeights(learner.actor, selfNet);

		// Stage 4: train VPT InverseDynamics on arena transitions + fading outcome-filtered winning human demos.
		float humanDemoCe = 0f;
		if (stage >= 4) {
			if (idm.sampleCount() >= 256) {
				idm.train(12, 64, 1e-3f, rng);
				if (learner.updates % 10 == 0 && humans.roundCount() > 0) {
					humans.buildFromRealRounds(idm, 42L + learner.updates);
					league.setHumanOpponents(humans.opponents());
				}
			}
			HumanImitation.WinningDemos wd = humans.winningDemos();
			float demoW = league.demoWeight();
			if (wd.size >= 32 && demoW > 0.03f) {
				int batch = 32;
				byte[][] bp = new byte[batch][];
				byte[][] bf = new byte[batch][];
				float[][] bv = new float[batch][];
				float[][] bh = new float[batch][];
				float[][] bs = new float[batch][];
				byte[][] ba = new byte[batch][];
				for (int i = 0; i < batch; i++) {
					int k = rng.nextInt(wd.size);
					bp[i] = wd.peri[k]; bf[i] = wd.fovea[k]; bv[i] = wd.vec[k];
					bh[i] = wd.hist[k]; bs[i] = wd.seen[k]; ba[i] = wd.action[k];
				}
				humanDemoCe = learner.ppo.supervisedStep(learner.actor, bp, bf, bv, bh, bs, null, ba, batch, learner.ppo.lr * demoW);
			}
		}

		// Stage 5: train on AlphaZero MoveSearch game-verified outcome targets.
		float searchCe = 0f;
		if (stage >= 5 && moveSearch.targetCount() >= 8) {
			searchCe = moveSearch.trainActor(learner.actor, learner.ppo, 2, learner.ppo.lr * 0.5f, rng);
		}

		if (learner.updates % SNAPSHOT_EVERY_UPDATES == 0) {
			league.addSnapshot("u" + learner.updates, learner.actor);
		}
		saveAll();

		double secs = Math.max(1.0, (System.currentTimeMillis() - startedMs) / 1000.0);
		SwordAi.LOGGER.info(String.format(Locale.ROOT,
				"league update %d (stage %d), %d steps (%.0f/s): pol %.4f val %.4f kl %.4f ent %.3f priorKl %.3f ev %.2f lr %.1e gru|w| %.2f idmAcc %.0f%% demoCe %.2f search %d/%d(ce %.2f) | %s",
				learner.updates, stage, learner.totalSteps, totalTicks / secs,
				stats.policyLoss, stats.valueLoss, stats.approxKl, stats.entropy, stats.priorKl,
				stats.explainedVar, stats.lr, learner.actor.gruWeightNorm(),
				idm.lastAccuracy() * 100f, humanDemoCe, moveSearch.targetsFound(), moveSearch.searchesDone(), searchCe,
				league.summary()));
		server.execute(() -> ArenaServer.setIdle(false));
	}

	private void requestNextEpisode(Truth truth) {
		resetEpisodeState();
		EpisodePlan plan;
		if (mode == Mode.CHECK) {
			CheckOpp opp = checkPool.get(checkPairIdx % checkPool.size());
			checkOppName = opp.name();
			long seed = 0x517CC1B727220A95L ^ (checkPairIdx * 0x9E3779B97F4A7C15L);
			if (opp.isBaseline()) {
				SparPilot pilot = new LeaguePilots.NetPilot(netB, cfg.aimResponse, cfg.playTemperature, seed + 1L, null);
				plan = EpisodePlan.ofPilot(pilot, seed, true);
			} else if (opp.human() != null) {
				SparPilot pilot = new LeaguePilots.HumanPilot(opp.human(), seed + 1L);
				plan = EpisodePlan.ofPilot(pilot, seed, true);
			} else {
				plan = EpisodePlan.ofSpar(opp.spar(), seed, true);
			}
			recorder.start(checkOppName);
		} else if (activeSearch != null) {
			searchTrialTick = 0;
			searchDmgDealt = 0f;
			searchDmgTaken = 0f;
			searchMaxOwnCombo = 0;
			searchMaxOppCombo = 0;
			SparPilot opp = new LeaguePilots.NetPilot(selfNet, cfg.aimResponse, 1f, rng.nextLong(), null);
			plan = EpisodePlan.ofPilot(opp, 0xA1F0L + activeSearch.moment.kind.ordinal(), false);
			plan.scene = activeSearch.moment.arenaState;
		} else {
			currentMatch = league.sampleMatch(rng, stage >= 5 ? moveSearch : null, true);
			InverseDynamics logIdm = stage >= 4 ? idm : null;
			plan = switch (currentMatch.kind) {
				case LATEST_SELF -> EpisodePlan.ofPilot(
						new LeaguePilots.NetPilot(selfNet, cfg.aimResponse, 1f, rng.nextLong(), logIdm), 0L, false);
				case PAST_SNAPSHOT -> EpisodePlan.ofPilot(
						new LeaguePilots.NetPilot(currentMatch.snapshot.net, cfg.aimResponse, 1f, rng.nextLong(), logIdm), 0L, false);
				case EXPLOITER -> EpisodePlan.ofPilot(exploiter, 0L, false);
				case HUMAN_CLONE -> EpisodePlan.ofPilot(
						new LeaguePilots.HumanPilot(currentMatch.human, rng.nextLong()), 0L, false);
				case SPAR_BOT -> EpisodePlan.ofSpar(currentMatch.sparStyle, 0L, false);
			};
			if (stage >= 5 && currentMatch.startMoment != null) {
				plan.scene = currentMatch.startMoment.arenaState;
			}
		}
		waitingEpisodeId = truth.episodeId();
		resetPending = true;
		server.execute(() -> ArenaServer.reset(plan));
	}

	private MoveSearch.Kind detectKeyMoment(boolean dealtCrit, boolean dealtNow, boolean takenNow, Truth truth) {
		if (dealtCrit && critChain >= 1) return MoveSearch.Kind.AFTER_PUNISH_CRIT;
		if (truth.botFalling() && facts.reachDist < 4.5f) return MoveSearch.Kind.COUNTER_CRIT;
		if (dealtNow && ownCombo >= 1 && ownCombo <= 3) return MoveSearch.Kind.HOLD_COMBO;
		if (!dealtNow && !takenNow && facts.reachDist >= 2.2f && facts.reachDist <= 3.6f
				&& mc.player.getAttackStrengthScale(0f) >= 0.95f && clock.ticksSinceHitDealt > 20 && clock.ticksSinceHitTaken > 20) {
			return rng.nextFloat() < 0.08f ? MoveSearch.Kind.COMBO_START : null;
		}
		return null;
	}

	private void resetEpisodeState() {
		observer.resetEpisode();
		clock.reset();
		motor.reset();
		if (learner != null) learner.resetEpisode();
		memCheck.reset();
		hasPrev = false;
		prevPhi = 0f;
		epTicks = 0;
		epDealt = 0f;
		epTaken = 0f;
		ownCombo = 0;
		oppCombo = 0;
		critChain = 0;
	}

	private void saveAll() {
		try {
			learner.save(SwordFiles.schoolCheckpoint());
			if (exploiter != null) exploiter.learner.save(SwordFiles.exploiterCheckpoint());
			if (stage >= 4 && idm.trainedSteps() > 0) idm.save(SwordFiles.idmCheckpoint());
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not save league checkpoints", e);
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || stopped) return false;
		AgentController.hold(mc, dec.action);
		return dec.action[ActionSpace.ATTACK] == 1;
	}

	@Override
	public void endTick() {
	}

	@Override
	public void worldDrawn() {
		if (!stopped && mc.screen == null) {
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
		if (mode == Mode.TRAIN && learner != null) {
			saveAll();
		}
		AgentController.release(mc);
		SwordAiClient.setTickMillis(0f);
		KeyedSkin.setActive(false);
		KeyedSkin.setShowReal(false);
		WindowGuard.restore(mc);
		OptionsGuard.restore(mc);
		grabber.close();
		server.execute(() -> ArenaServer.stop(server));
		SwordAi.LOGGER.info("Stopped ({}): {} ({} ticks)", reason, describe(), totalTicks);
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[SwordAI] Stopped (" + reason + ")."), false);
		}
	}

	@Override
	public List<String> statusLines() {
		if (mode == Mode.CHECK) {
			return List.of("Check pair " + checkPairIdx + (checkSideB ? " B " : " A ") + checkOppName);
		}
		return List.of(String.format(Locale.ROOT, "League Stage %d: %d updates, %d steps | %s",
				stage, learner.updates, learner.totalSteps, league.summary()));
	}

	@Override
	public boolean requestDebugDump() {
		return false;
	}

	@Override
	public String describe() {
		return mode == Mode.TRAIN ? ("league stage " + stage) : ("paired check pair " + checkPairIdx);
	}
}
