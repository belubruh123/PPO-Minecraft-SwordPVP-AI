package dev.swordai.client;

import dev.swordai.OwnSwing;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.env.*;
import dev.swordai.core.league.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.mixin.LivingEntityAccessor;
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
 * Staged rule-free league training (/swordai school) and dual-kit paired evaluation (/swordai school check):
 * Stage 2-5 = Pure Sword (distilled -> GRU -> VPT IDM + Human-KL -> MoveSearch);
 * Stage 6 = SMP Phase 1 Generalist (AlphaStar Pointer + 4-Expert MoE + 256-GRU + 5x5 Terrain + Sword/Axe/Shield/Gaps + randomized hotbar + terraced arena).
 */
final class LeagueSession implements AgentSession {
	enum Mode { TRAIN, CHECK }

	private static final int ROLLOUT = 4096, SWORD_MAX_EP_TICKS = 600, SMP_MAX_EP_TICKS = 1100, SETTLE = 4, SEARCH_EVERY = 25;
	private static final int SNAP_EVERY = 8, STAGE5_SNAP_EVERY = 25, DUEL_SYNC_EVERY = 200, MILESTONE_EVERY = 300, PRIOR_REFRESH_EVERY = 400, MAX_MILESTONES = 24;
	private static final float PRIOR_KL = 0.02f, STAGE4_PRIOR_KL = 0.015f, HUMAN_STYLE_KL = 0.12f;

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
	private final SwordNet priorNet, selfNet, netA, netB, swordMasterAnchor;
	private final SwordNet.Workspace wsA, wsB;
	private final League league = new League();
	private final InverseDynamics idm = new InverseDynamics(7L);
	private final HumanImitation humans = new HumanImitation();
	private final MoveSearch moveSearch = new MoveSearch();
	private final LeaguePilots.ExploiterPilot exploiter, humanChallenger;
	private final ObsMemory memCheck = new ObsMemory();
	private final List<CheckOpp> checkPool;
	private final Path benchDirA, benchDirB;

	private League.Match currentMatch;
	private EpisodePlan.KitMode currentKit = EpisodePlan.KitMode.SWORD_ONLY;
	private MoveSearch.SearchJob activeSearch;
	private int searchTick, checkPair, waitingEp, expectedPutBack, settleLeft, epTicks, epDone, activeLag, badUpdates, lowRamHits;
	private int ownCombo, oppCombo, critChain, ownSwingsSeen = OwnSwing.count();
	private int statClose, statOnBody, statClicks, statLowChg, statWhiffs, statSwings;
	private final int[] chkClose = new int[2], chkOnBody = new int[2], chkClicks = new int[2], chkLowChg = new int[2], chkWhiffs = new int[2];
	private float searchStartMyHp, searchStartBotHp, prevPhi, epDealt, epTaken, turnYaw, turnPitch;
	private float emaAim = 0.88f, emaLow = 0.05f, emaWhf = 0.05f;
	private boolean checkSideB, stopped, arenaRequested, resetPending, waitingPutBack, hasPrev, actedThisTick, clicked, usingItem, clickResolved, humansBuiltWithIdm;
	private boolean oppWasBlockingOnSwing;
	private String checkOppName = "";
	private long totalTicks;
	private final long startedMs = System.currentTimeMillis();

	private record CheckOpp(String name, League.SparStyle spar, HumanImitation.HumanPolicy human, SwordNet net,
			EpisodePlan.KitMode kit, boolean terraced) {}

	LeagueSession(Minecraft mc, Mode mode) throws IOException {
		this.mc = mc; this.server = mc.getSingleplayerServer(); this.mode = mode;
		this.stage = SwordFiles.readLeagueStage(); this.cfg = SwordFiles.loadConfig(); this.motor = new AimMotor(cfg.aimResponse);
		Path ckpt = SwordFiles.schoolCheckpoint(), base = SwordFiles.baselineCheckpoint(), prior = SwordFiles.priorCheckpoint();
		Path swMasterPath = SwordFiles.anchorsDir().resolve("sword-master.bin");
		this.swordMasterAnchor = Files.exists(swMasterPath) ? LeagueLearner.loadActorOnly(swMasterPath) : null;
		if (swordMasterAnchor != null) swordMasterAnchor.gruEnabled = swordMasterAnchor.gruWeightNorm() > 0f;

		if (mode == Mode.TRAIN) {
			learner = new LeagueLearner(ROLLOUT, System.nanoTime());
			if (Files.exists(ckpt)) learner.load(ckpt); else if (Files.exists(base)) learner.load(base);
			learner.setGruEnabled(stage >= 3);
			Path livePrior = SwordFiles.livePriorCheckpoint();
			boolean useLivePrior = stage >= 5 && Files.exists(livePrior)
					&& (!Files.exists(base) || Files.getLastModifiedTime(livePrior).toMillis() > Files.getLastModifiedTime(base).toMillis());
			Path priorPath = useLivePrior ? livePrior : (stage >= 4 && Files.exists(base)) ? base : prior;
			priorNet = Files.exists(priorPath) ? LeagueLearner.loadActorOnly(priorPath) : null;
			if (priorNet != null) priorNet.gruEnabled = priorNet.gruWeightNorm() > 0f;
			selfNet = new SwordNet(2L); selfNet.gruEnabled = learner.actor.gruEnabled;
			LeagueLearner.copyWeights(learner.actor, selfNet);
			selfNet.params.dropOptimizerState();
			int loadedPool = 0;
			try { loadedPool = league.loadPool(SwordFiles.leaguePoolDir()); } catch (IOException ignored) {}
			if (loadedPool == 0) addAndPersistSnapshot("start", learner.actor);

			InverseDynamics logIdm = stage >= 4 ? idm : null;
			LeagueLearner expL = new LeagueLearner(2048, System.nanoTime() ^ 0x55AA55AAL);
			if (Files.exists(SwordFiles.exploiterCheckpoint())) { try { expL.load(SwordFiles.exploiterCheckpoint()); } catch (IOException ignored) {} }
			else LeagueLearner.copyWeights(learner.actor, expL.actor);
			expL.setGruEnabled(stage >= 3);
			exploiter = new LeaguePilots.ExploiterPilot(expL, priorNet, cfg.aimResponse, logIdm);

			if (stage >= 4) {
				try { idm.load(SwordFiles.idmCheckpoint()); } catch (IOException ignored) {}
				int synced = SwordFiles.syncExternalDuels();
				int nReal = humans.loadDirectory(SwordFiles.realFightsDir());
				if (nReal > 0) {
					humans.buildFromRealRounds(idm, 42L);
					league.setHumanOpponents(humans.opponents());
					humansBuiltWithIdm = idm.trainedSteps() >= 2048;
				}
				LeagueLearner humL = new LeagueLearner(2048, System.nanoTime() ^ 0x33CC33CCL);
				if (Files.exists(SwordFiles.humanChallengerCheckpoint())) { try { humL.load(SwordFiles.humanChallengerCheckpoint()); } catch (IOException ignored) {} }
				else LeagueLearner.copyWeights(learner.actor, humL.actor);
				humL.setGruEnabled(stage >= 3);
				humanChallenger = new LeaguePilots.ExploiterPilot(humL, priorNet, cfg.aimResponse, logIdm);
				if (!humans.opponents().isEmpty()) humanChallenger.setStylePrior(humans.opponents().get(0), HUMAN_STYLE_KL);
				SwordAi.LOGGER.info("League Stage {}: {} real rounds (+{} synced) -> {} clusters, pool={} snaps, prior={} (smpPrior={}), swordMaster={}",
						stage, nReal, synced, humans.opponents().size(), league.snapshotCount(), priorPath.getFileName(),
						priorNet != null && LeagueLearner.hasSmpWeights(priorNet), swordMasterAnchor != null);
			} else {
				humanChallenger = null;
			}
			netA = netB = null; wsA = wsB = null; checkPool = List.of(); benchDirA = benchDirB = null;
		} else {
			if (!Files.exists(ckpt)) throw new IOException("no candidate checkpoint at " + ckpt);
			if (!Files.exists(base)) throw new IOException("no baseline checkpoint at " + base);
			netA = LeagueLearner.loadActorOnly(ckpt); netB = LeagueLearner.loadActorOnly(base);
			wsA = netA.newWorkspace(); wsB = netB.newWorkspace();
			learner = null; priorNet = selfNet = null; exploiter = humanChallenger = null;
			try { idm.load(SwordFiles.idmCheckpoint()); } catch (IOException ignored) {}
			humans.loadDirectory(SwordFiles.realFightsDir()); humans.buildFromRealRounds(idm, 42L);
			List<CheckOpp> pool = new ArrayList<>();
			if (stage >= 6) {
				// Dual-kit honest check: 50% Pure Sword (random slot, verifying zero regression vs sword-master) + 50% SMP Phase 1 (random slots + terraces)
				for (int i = 0; i < 2; i++) pool.add(new CheckOpp("sword-baseline", null, null, netB, EpisodePlan.KitMode.SWORD_ONLY, false));
				if (swordMasterAnchor != null) {
					for (int i = 0; i < 2; i++) pool.add(new CheckOpp("sword-master", null, null, swordMasterAnchor, EpisodePlan.KitMode.SWORD_ONLY, false));
				}
				var hps = humans.opponents();
				for (int i = 0; i < 2 && !hps.isEmpty(); i++) {
					var h = hps.get(i % hps.size());
					String c = h.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-").replaceAll("^-|-$", "");
					pool.add(new CheckOpp("sword-human-" + (c.isEmpty() ? "real" : c), null, h, null, EpisodePlan.KitMode.SWORD_ONLY, false));
				}
				pool.add(new CheckOpp("sword-crit-spammer", League.SparStyle.CRIT_SPAMMER, null, null, EpisodePlan.KitMode.SWORD_ONLY, false));
				pool.add(new CheckOpp("sword-jump-critter", League.SparStyle.JUMP_IN_CRITTER, null, null, EpisodePlan.KitMode.SWORD_ONLY, false));

				// SMP Phase 1 kit slots (half flat, half 1-block terraced arena, randomized hotbar order!)
				for (int i = 0; i < 4; i++) pool.add(new CheckOpp("smp-baseline", null, null, netB, EpisodePlan.KitMode.SMP_PHASE1, i % 2 == 1));
				pool.add(new CheckOpp("smp-crit-spammer", League.SparStyle.CRIT_SPAMMER, null, null, EpisodePlan.KitMode.SMP_PHASE1, false));
				pool.add(new CheckOpp("smp-jump-critter", League.SparStyle.JUMP_IN_CRITTER, null, null, EpisodePlan.KitMode.SMP_PHASE1, true));
				pool.add(new CheckOpp("smp-combo-player", League.SparStyle.COMBO_PLAYER, null, null, EpisodePlan.KitMode.SMP_PHASE1, false));
				pool.add(new CheckOpp("smp-mixer", League.SparStyle.MIXER, null, null, EpisodePlan.KitMode.SMP_PHASE1, true));
			} else {
				for (int i = 0; i < 4; i++) pool.add(new CheckOpp("baseline", null, null, netB, EpisodePlan.KitMode.SWORD_ONLY, false));
				Path ancDir = SwordFiles.anchorsDir();
				if (Files.isDirectory(ancDir)) {
					try (var s = Files.list(ancDir)) {
						for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".bin")).sorted().toList()) {
							try {
								String tag = p.getFileName().toString().replaceFirst("\\.bin$", "").toLowerCase(Locale.ROOT)
										.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
								pool.add(new CheckOpp("anchor-" + (tag.isEmpty() ? "fixed" : tag), null, null, LeagueLearner.loadActorOnly(p), EpisodePlan.KitMode.SWORD_ONLY, false));
							} catch (IOException ignored) {}
						}
					}
				}
				var hps = humans.opponents();
				for (int i = 0; i < 4 && !hps.isEmpty(); i++) {
					var h = hps.get(i % hps.size());
					String c = h.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-").replaceAll("^-|-$", "");
					pool.add(new CheckOpp("human-" + (c.isEmpty() ? "real" : c), null, h, null, EpisodePlan.KitMode.SWORD_ONLY, false));
				}
				try { league.loadPool(SwordFiles.leaguePoolDir()); } catch (IOException ignored) {}
				for (League.Snapshot hs : league.hardestHumanSnapshots(2)) {
					String tag = hs.id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
					pool.add(new CheckOpp("rl-" + (tag.isEmpty() ? "hum" : tag), null, null, hs.net, EpisodePlan.KitMode.SWORD_ONLY, false));
				}
				pool.add(new CheckOpp("crit-spammer", League.SparStyle.CRIT_SPAMMER, null, null, EpisodePlan.KitMode.SWORD_ONLY, false));
				pool.add(new CheckOpp("jump-in-critter", League.SparStyle.JUMP_IN_CRITTER, null, null, EpisodePlan.KitMode.SWORD_ONLY, false));
				pool.add(new CheckOpp("combo-player", League.SparStyle.COMBO_PLAYER, null, null, EpisodePlan.KitMode.SWORD_ONLY, false));
				pool.add(new CheckOpp("mixer", League.SparStyle.MIXER, null, null, EpisodePlan.KitMode.SWORD_ONLY, false));
			}
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
				? String.format(Locale.ROOT, "League training started (Stage %d, GRU256=%s, MoE=4, humans=%d, steps=%d).", stage, stage >= 3, humans.opponents().size(), learner.totalSteps)
				: String.format(Locale.ROOT, "Paired check started (A=%s vs B=%s, %d pool slots, stage %d).", SwordFiles.schoolCheckpoint().getFileName(), SwordFiles.baselineCheckpoint().getFileName(), checkPool.size(), stage);
	}

	@Override public boolean stopped() { return stopped; }
	@Override public boolean inControl() { return !stopped && mc.screen == null; }
	@Override public boolean wantsUseItem() { return usingItem; }
	@Override public boolean requestDebugDump() { return false; }
	@Override public String describe() { return mode == Mode.TRAIN ? ("league stage " + stage) : ("paired check pair " + checkPair); }
	@Override public void endTick() {
		if (stopped || !clicked || clickResolved) return;
		clickResolved = true;
		int made = OwnSwing.count();
		boolean hit = made != ownSwingsSeen;
		String kind = hit ? OwnSwing.lastKind() : "miss";
		if (hit) {
			ownSwingsSeen = made; statSwings++;
			if (oppWasBlockingOnSwing) observer.body().noteOpponentShieldBroken();
		} else {
			statWhiffs++; if (mode == Mode.CHECK) chkWhiffs[checkSideB ? 1 : 0]++;
		}
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
		if (waitingPutBack) {
			if (ArenaServer.scenesPutBack < expectedPutBack) return;
			waitingPutBack = false;
			MoveSearch.Moment m = activeSearch != null ? activeSearch.moment
					: (currentMatch != null ? currentMatch.startMoment : null);
			if (m != null) {
				((LivingEntityAccessor) me).swordai$setAttackStrengthTicker(SceneRecorder.agentTicker(m.arenaState));
				me.setSprinting(m.arenaState[13] > 0.5);
				if (learner != null) learner.restoreMemory(m.hist, m.seen, m.hPrev);
			}
			return;
		}
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
		observer.fillObs(obs, me, bot, truth, facts, hasPrev ? lastAction : null, ActionSpace.PHASE1_HEADS, clock, motor);

		if (mode == Mode.CHECK) tickCheck(me, bot, truth, dNow, tNow, koD, koT);
		else if (activeSearch != null) tickSearch(me, truth, koD, koT);
		else tickTrain(me, truth, dNow, tNow, dCrit, koD, koT);
		totalTicks++;
	}

	private int maxEpTicks() {
		return currentKit == EpisodePlan.KitMode.SMP_PHASE1 ? SMP_MAX_EP_TICKS : SWORD_MAX_EP_TICKS;
	}

	private void tickTrain(LocalPlayer me, Truth truth, boolean dNow, boolean tNow, boolean dCrit, boolean koD, boolean koT) {
		boolean done = koD || koT || epTicks >= maxEpTicks();
		float meEffHp = me.getHealth() + me.getAbsorptionAmount();
		float phi = FixedReward.potential(meEffHp / 20f, truth.botHealth() / 20f, ownCombo, oppCombo,
				me.getAttackStrengthScale(0f), truth.botCharge(), facts.reachDist, done);
		if (hasPrev) {
			learner.record(prevObs, ActionSpace.PHASE1_HEADS, prevDec, FixedReward.step(facts.damageDealt, facts.damageTaken, koD, koT, prevPhi, phi), done, null, 0f);
			if (learner.rollout.isFull()) runUpdate(done ? 0f : learner.bootstrapValue(obs));
		}
		prevPhi = phi;
		if (stage >= 5 && !done && epTicks > 8 && facts.reachDist < 6.0f && me.getHealth() > 3f && truth.botHealth() > 4f) {
			MoveSearch.Kind k = detectMoment(me, dCrit, dNow, tNow, truth);
			if (k != null) moveSearch.recordMoment(k, currentKit == EpisodePlan.KitMode.SMP_PHASE1,
					ArenaServer.sceneAt(truth.tick()), obs, learner.memory.hist, learner.memory.seen, learner.memory.hidden);
		}
		if (done) {
			if (currentMatch != null) {
				league.recordOutcome(currentMatch, koD || (!koT && epDealt > epTaken));
				if (currentMatch.kind == League.OpponentKind.EXPLOITER && exploiter != null) {
					exploiter.rounds++;
					float expWr = league.exploiterWinRate();
					if ((exploiter.rounds >= 60 && expWr >= 0.65f) || (exploiter.rounds >= 240 && expWr < 0.45f)) {
						boolean froze = expWr >= 0.55f;
						if (froze) addAndPersistSnapshot("exp" + exploiter.gen, exploiter.learner.actor);
						SwordNet snap = rng.nextBoolean() ? league.randomSnapshot(rng) : null;
						SwordNet seed = snap != null ? snap : learner.actor;
						SwordAi.LOGGER.info(String.format(Locale.ROOT,
								"AlphaStar exploiter gen %d %s (expWr=%.0f%% over %d rounds) -> reset to %s",
								exploiter.gen, froze ? "FROZEN into PFSP" : "unstuck", expWr * 100f, exploiter.rounds,
								snap != null ? "pfsp-snap" : "main"));
						exploiter.resetTo(seed, stage >= 3);
						league.resetExploiterStats();
					}
				} else if (currentMatch.kind == League.OpponentKind.HUMAN_CLONE && humanChallenger != null) {
					humanChallenger.rounds++;
					float hWr = league.humanChallengerWinRate();
					if ((humanChallenger.rounds >= 60 && hWr >= 0.58f) || humanChallenger.rounds >= 180) {
						boolean froze = hWr >= 0.45f;
						String stName = humanChallenger.styleName();
						if (froze) addAndPersistSnapshot("hum-" + stName + "-g" + humanChallenger.gen, humanChallenger.learner.actor);
						var hps = humans.opponents();
						if (!hps.isEmpty()) {
							humanChallenger.styleIdx = (humanChallenger.styleIdx + 1) % hps.size();
							humanChallenger.setStylePrior(hps.get(humanChallenger.styleIdx), HUMAN_STYLE_KL);
						}
						SwordAi.LOGGER.info(String.format(Locale.ROOT,
								"Human-KL challenger gen %d (%s) %s (hWr=%.0f%% over %d rounds) -> reset to main on %s",
								humanChallenger.gen, stName, froze ? "FROZEN into PFSP" : "rotated", hWr * 100f,
								humanChallenger.rounds, humanChallenger.styleName()));
						humanChallenger.resetTo(learner.actor, stage >= 3);
						league.resetHumanChallengerStats();
					}
				}
			}
			if (stage >= 5 && ++epDone % SEARCH_EVERY == 0 && moveSearch.totalMoments() >= 4) {
				int sLag = rng.nextFloat() < 0.80f ? (1 + rng.nextInt(2)) : 0;
				SwordNet sOpp = rng.nextBoolean() ? league.randomSnapshot(rng) : null;
				activeSearch = moveSearch.startSearch(rng, sLag, sOpp != null ? sOpp : selfNet);
			}
			requestNextEpisode(truth); return;
		}
		learner.decide(obs, ActionSpace.PHASE1_HEADS, false, 1f, dec);
		prevObs.copyFrom(obs); System.arraycopy(dec.action, 0, prevDec.action, 0, ActionSpace.HEADS);
		prevDec.logp = dec.logp; prevDec.value = dec.value;
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
		applyAction(me, truth, dec.action); epTicks++;
	}

	private void tickSearch(LocalPlayer me, Truth truth, boolean koD, boolean koT) {
		float myHpNow = me.getHealth() + me.getAbsorptionAmount();
		float botHpNow = truth.botHealth();
		if (searchTick == 0) { searchStartMyHp = myHpNow; searchStartBotHp = botHpNow; }
		if (koD || koT || searchTick >= activeSearch.rolloutTicks()) {
			float netHpGain = (myHpNow - searchStartMyHp) - (botHpNow - searchStartBotHp);
			activeSearch.finishTrial(netHpGain, koD, koT);
			if (activeSearch.finished()) { moveSearch.commitSearch(activeSearch); activeSearch = null; }
			requestNextEpisode(truth); return;
		}
		learner.decide(obs, ActionSpace.PHASE1_HEADS, false, 1f, dec);
		MoveSearch.Candidate cand = activeSearch.currentCandidate();
		if (searchTick < cand.macroTicks) {
			boolean canHit = me.getAttackStrengthScale(0f) >= 0.90f && facts.onBody && facts.reachDist <= 3.05f;
			boolean critWindow = !me.onGround() && me.fallDistance > 0.0 && !me.isSprinting();
			int swSlot = InventoryObs.findHotbarSlot(me, ObsSpec.SF_SWORD);
			int axSlot = InventoryObs.findHotbarSlot(me, ObsSpec.SF_AXE);
			int gpSlot = InventoryObs.findHotbarSlot(me, ObsSpec.SF_GAP);
			cand.apply(dec.action, searchTick, canHit, critWindow, swSlot, axSlot, gpSlot,
					InventoryObs.hasShieldReady(me), truth.botShieldUp());
		}
		if (searchTick == 0) activeSearch.noteFirstStep(obs, learner.lastHistIn(), learner.lastSeenIn(), learner.lastHiddenIn(), dec.action);
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
		applyAction(me, truth, dec.action); searchTick++; epTicks++;
	}

	private void tickCheck(LocalPlayer me, Player bot, Truth truth, boolean dNow, boolean tNow, boolean koD, boolean koT) {
		if (koD || koT || epTicks >= maxEpTicks()) {
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
		net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, obs.slots, obs.terrain, memCheck.hist, memCheck.seen, memCheck.hidden, memCheck.hidden);
		net.samplePlay(ws, ws.step, ActionSpace.PHASE1_HEADS, rng, dec.action);
		memCheck.pushAction(dec.action);
		System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
		applyAction(me, truth, dec.action); recorder.tick(me, bot, observer.body(), dec.action, dNow, tNow); epTicks++;
	}

	private void applyAction(LocalPlayer me, Truth truth, int[] a) {
		motor.step(ActionSpace.yawDegrees(a), ActionSpace.pitchDegrees(a));
		turnYaw = motor.yawRate(); turnPitch = motor.pitchRate();
		AgentController.apply(mc, a, turnYaw, turnPitch);
		actedThisTick = true;
		clicked = a[ActionSpace.ATTACK] == 1;
		usingItem = a[ActionSpace.USE_ITEM] == 1 && !clicked;
		oppWasBlockingOnSwing = clicked && truth.botShieldUp() && InventoryObs.isAxe(me.getMainHandItem());
		clickResolved = false;
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
		float aimRate = (float) statOnBody / Math.max(1, statClose);
		float lowRate = (float) statLowChg / Math.max(1, statClicks);
		float whfRate = (float) statWhiffs / Math.max(1, statClicks);
		if (statClose >= 100) emaAim += 0.10f * (aimRate - emaAim);
		if (statClicks >= 10) { emaLow += 0.10f * (lowRate - emaLow); emaWhf += 0.10f * (whfRate - emaWhf); }

		float baseKl = stage >= 4 ? STAGE4_PRIOR_KL : PRIOR_KL;
		// OpenAI Five surgery warmup: when critic is newly initialized/reset (adamSteps < 384 = ~6 updates), keep prior KL high so noisy initial values never perturb the actor.
		float pKl = (learner.updates < 10 || learner.critic.params.adamSteps < 384) ? 0.50f : baseKl;
		LeaguePpo.Stats s = learner.update(bootstrap, priorNet, pKl, 0f);

		boolean nonFinite = !Float.isFinite(s.policyLoss) || !Float.isFinite(s.valueLoss) || learner.hasNonFiniteWeights();
		boolean severeDrop = statClose >= 200 && statClicks >= 20 && (aimRate < 0.55f || whfRate > 0.30f || lowRate > 0.35f);
		badUpdates = severeDrop ? badUpdates + 1 : 0;
		if (nonFinite || badUpdates >= 10) {
			recoverFromCollapse(nonFinite ? "non-finite loss/weights"
					: String.format(Locale.ROOT, "sustained collapse aim=%.0f%% low=%.0f%% whf=%.0f%%", aimRate * 100f, lowRate * 100f, whfRate * 100f));
			statClose = statOnBody = statClicks = statLowChg = statWhiffs = statSwings = 0;
			server.execute(() -> ArenaServer.setIdle(false));
			return;
		}

		LeagueLearner.copyWeights(learner.actor, selfNet);
		float searchCe = 0f;
		if (stage >= 4 && learner.updates % DUEL_SYNC_EVERY == 0 && SwordFiles.syncExternalDuels() > 0) {
			int nReal = humans.loadDirectory(SwordFiles.realFightsDir());
			if (nReal > 0) {
				humans.buildFromRealRounds(idm, 42L + learner.updates);
				league.setHumanOpponents(humans.opponents());
				if (humanChallenger != null && !humans.opponents().isEmpty()) {
					humanChallenger.setStylePrior(humans.opponents().get(humanChallenger.styleIdx % humans.opponents().size()), HUMAN_STYLE_KL);
				}
				SwordAi.LOGGER.info("Auto-ingested new real duels: {} rounds -> {} clusters", nReal, humans.opponents().size());
			}
		}
		if (stage >= 4 && idm.sampleCount() >= 256) {
			idm.train(16, 64, 1e-3f, rng);
			boolean firstIdmBuild = !humansBuiltWithIdm && idm.trainedSteps() >= 2048;
			if (humans.roundCount() > 0 && (firstIdmBuild || learner.updates % 50 == 0)) {
				humans.buildFromRealRounds(idm, 42L + learner.updates);
				league.setHumanOpponents(humans.opponents());
				humansBuiltWithIdm = idm.trainedSteps() >= 2048;
				if (humanChallenger != null && !humans.opponents().isEmpty()) {
					int idx = humanChallenger.styleIdx % humans.opponents().size();
					humanChallenger.setStylePrior(humans.opponents().get(idx), HUMAN_STYLE_KL);
				}
			}
		}
		if (stage >= 5 && moveSearch.targetCount() >= 8) {
			searchCe = moveSearch.trainActor(learner.actor, learner.ppo, 1, learner.ppo.lr * 0.25f, rng);
		}
		int snapEvery = stage >= 5 ? STAGE5_SNAP_EVERY : SNAP_EVERY;
		if (learner.updates % snapEvery == 0) addAndPersistSnapshot("u" + learner.updates, learner.actor);
		if (learner.updates % MILESTONE_EVERY == 0 && emaAim >= 0.80f && emaLow <= 0.12f && emaWhf <= 0.09f) {
			saveMilestone(learner.updates);
		}
		if (stage >= 5 && priorNet != null && learner.updates % PRIOR_REFRESH_EVERY == 0
				&& emaAim >= 0.84f && emaLow <= 0.10f && emaWhf <= 0.07f && league.overallWinRate() >= 0.50f) {
			LeagueLearner.copyWeights(learner.actor, priorNet);
			priorNet.gruEnabled = learner.actor.gruEnabled;
			try { LeagueLearner.saveActorOnly(priorNet, SwordFiles.livePriorCheckpoint()); } catch (IOException ignored) {}
			SwordAi.LOGGER.info(String.format(Locale.ROOT,
					"PriorNet promoted at u=%d (ema aim=%.0f%% low=%.1f%% whf=%.1f%% wr=%.0f%%)",
					learner.updates, emaAim * 100f, emaLow * 100f, emaWhf * 100f, league.overallWinRate() * 100f));
		}
		saveAll();
		long freeRamMb = freeSystemRamMb();
		if (freeRamMb >= 0 && freeRamMb < 550) {
			if (++lowRamHits >= 2) {
				SwordAi.LOGGER.warn("[RAM-GUARD] OS free physical RAM dropped to {} MB — saving and exiting dev client to protect PC", freeRamMb);
				stop("low system RAM (" + freeRamMb + " MB)");
				mc.stop();
				return;
			}
		} else {
			lowRamHits = 0;
		}
		double secs = Math.max(1.0, (System.currentTimeMillis() - startedMs) / 1000.0);
		SwordAi.LOGGER.info(String.format(Locale.ROOT,
				"league update %d (stage %d), %d steps (%.0f/s): pol %.4f val %.4f kl %.4f ent %.3f pKl %.3f ev %.2f lr %.1e aim %.0f%% low %.1f%% whf %.1f%% gru|w| %.2f moe [%d/%d/%d/%d%%] search %d/%d(ce %.2f) | %s",
				learner.updates, stage, learner.totalSteps, totalTicks / secs, s.policyLoss, s.valueLoss, s.approxKl, s.entropy, s.priorKl,
				s.explainedVar, s.lr, aimRate * 100f, lowRate * 100f, whfRate * 100f, learner.actor.gruWeightNorm(),
				Math.round(s.moeUsage[0] * 100f), Math.round(s.moeUsage[1] * 100f), Math.round(s.moeUsage[2] * 100f), Math.round(s.moeUsage[3] * 100f),
				moveSearch.targetsFound(), moveSearch.searchesDone(), searchCe, league.summary()));
		statClose = statOnBody = statClicks = statLowChg = statWhiffs = statSwings = 0;
		server.execute(() -> ArenaServer.setIdle(false));
	}

	private void requestNextEpisode(Truth truth) {
		observer.resetEpisode(); clock.reset(); motor.reset(); if (learner != null) learner.resetEpisode(); memCheck.reset();
		hasPrev = false; usingItem = false; prevPhi = epDealt = epTaken = 0f; epTicks = ownCombo = oppCombo = critChain = 0;
		EpisodePlan plan;
		if (mode == Mode.CHECK) {
			CheckOpp opp = checkPool.get(checkPair % checkPool.size()); checkOppName = opp.name();
			long seed = 0x517CC1B727220A95L ^ (checkPair * 0x9E3779B97F4A7C15L);
			plan = opp.net() != null ? EpisodePlan.ofPilot(new LeaguePilots.NetPilot(opp.net(), cfg.aimResponse, 0f, seed + 1L, null), seed, true)
					: opp.human() != null ? EpisodePlan.ofPilot(new LeaguePilots.HumanPilot(opp.human(), seed + 1L), seed, true)
					: EpisodePlan.ofSpar(opp.spar(), seed, true);
			plan.kitMode = opp.kit();
			plan.randomizeSlots = stage >= 6;
			plan.terraced = opp.terraced();
			currentKit = plan.kitMode;
			int lagBit = (checkPool.size() % 2 == 1) ? (checkPair % 2) : (((checkPair / checkPool.size()) + checkPair) % 2);
			activeLag = (lagBit == 1) ? 2 : 0;
			plan.agentLag = activeLag; plan.botLag = activeLag; plan.botPing = activeLag; plan.agentKnockLag = activeLag > 0 ? 1 : 0;
			recorder.start(checkOppName);
		} else if (activeSearch != null) {
			searchTick = 0;
			activeLag = activeSearch.lag;
			SwordNet sOpp = activeSearch.opponent != null ? activeSearch.opponent : selfNet;
			plan = EpisodePlan.ofPilot(new LeaguePilots.NetPilot(sOpp, cfg.aimResponse, 1f, rng.nextLong(), null), 0xA1F0L + activeSearch.moment.kind.ordinal(), false);
			plan.kitMode = activeSearch.moment.smpKit ? EpisodePlan.KitMode.SMP_PHASE1 : EpisodePlan.KitMode.SWORD_ONLY;
			plan.randomizeSlots = stage >= 6;
			plan.terraced = false;
			currentKit = plan.kitMode;
			plan.agentLag = activeLag; plan.botLag = activeLag; plan.botPing = activeLag;
			plan.agentKnockLag = activeLag > 0 ? 1 : 0;
			plan.scene = activeSearch.moment.arenaState;
		} else {
			currentMatch = league.sampleMatch(rng, stage >= 5 ? moveSearch : null, true);
			InverseDynamics logIdm = stage >= 4 ? idm : null;

			// Multi-kit curriculum in Stage 6: Human-KL challengers stay pure Sword (from banked human Sword fights);
			// all other matches mix 75% SMP Phase 1 (Sword+Axe+Shield+Gaps, 50% terraced) + 25% pure Sword (random slots, anchor sword-master!).
			boolean smpKit = stage >= 6 && currentMatch.kind != League.OpponentKind.HUMAN_CLONE && rng.nextFloat() < 0.75f;
			if (stage >= 5 && currentMatch.startMoment != null) smpKit = currentMatch.startMoment.smpKit;
			currentKit = smpKit ? EpisodePlan.KitMode.SMP_PHASE1 : EpisodePlan.KitMode.SWORD_ONLY;

			SwordNet snapNet = currentMatch.snapshot != null ? currentMatch.snapshot.net : null;
			// On pure Sword snapshot rounds in Stage 6, 35% face frozen anchors/sword-master.bin so Sword mastery never regresses!
			if (!smpKit && stage >= 6 && swordMasterAnchor != null && currentMatch.kind == League.OpponentKind.PAST_SNAPSHOT && rng.nextFloat() < 0.35f) {
				snapNet = swordMasterAnchor;
			}
			plan = switch (currentMatch.kind) {
				case LATEST_SELF -> EpisodePlan.ofPilot(new LeaguePilots.NetPilot(selfNet, cfg.aimResponse, 1f, rng.nextLong(), smpKit ? null : logIdm), 0L, false);
				case PAST_SNAPSHOT -> EpisodePlan.ofPilot(new LeaguePilots.NetPilot(snapNet != null ? snapNet : selfNet, cfg.aimResponse, 1f, rng.nextLong(), smpKit ? null : logIdm), 0L, false);
				case EXPLOITER -> EpisodePlan.ofPilot(exploiter, 0L, false);
				case HUMAN_CLONE -> EpisodePlan.ofPilot(humanChallenger != null ? humanChallenger : new LeaguePilots.HumanPilot(currentMatch.human, rng.nextLong()), 0L, false);
				case SPAR_BOT -> EpisodePlan.ofSpar(currentMatch.sparStyle, 0L, false);
			};
			plan.kitMode = currentKit;
			plan.randomizeSlots = stage >= 6;
			plan.terraced = stage >= 6 && (smpKit ? rng.nextFloat() < 0.50f : rng.nextFloat() < 0.20f);

			activeLag = rng.nextFloat() < 0.80f ? (1 + rng.nextInt(3)) : 0;
			plan.agentLag = activeLag; plan.botLag = activeLag; plan.botPing = activeLag;
			plan.agentKnockLag = activeLag > 0 ? Math.max(1, activeLag - 1) : 0;
			if (stage >= 5 && currentMatch.startMoment != null && !plan.terraced) {
				plan.scene = currentMatch.startMoment.arenaState;
			} else if (rng.nextFloat() < 0.20f) {
				// 20% 360-degree spawns including ultra-close (0.2-1.4 block) pass-through drills so lost-track vanishes.
				plan.maxBearingDeg = 180f;
				boolean passThrough = rng.nextBoolean();
				plan.minDistance = passThrough ? 0.2f : 2.2f;
				plan.maxDistance = passThrough ? 1.4f : 5.5f;
				observer.simulateLostSight(rng.nextInt(3) - 1);
			}
		}
		SeenLag.set(activeLag, 0f, 0);
		waitingEp = truth.episodeId(); resetPending = true;
		server.execute(() -> ArenaServer.reset(plan));
	}

	private MoveSearch.Kind detectMoment(LocalPlayer me, boolean dCrit, boolean dNow, boolean tNow, Truth truth) {
		if (stage >= 6 && currentKit == EpisodePlan.KitMode.SMP_PHASE1) {
			if (truth.botShieldUp() && facts.reachDist < 4.8f && rng.nextFloat() < 0.25f) return MoveSearch.Kind.OPP_SHIELD_UP;
			float meEffHp = me.getHealth() + me.getAbsorptionAmount();
			if (meEffHp <= 11f && InventoryObs.findHotbarSlot(me, ObsSpec.SF_GAP) >= 0 && !me.isUsingItem() && rng.nextFloat() < 0.20f) {
				return MoveSearch.Kind.LOW_HP_GAP;
			}
		}
		if (dCrit && critChain >= 1) return MoveSearch.Kind.AFTER_PUNISH_CRIT;
		if (truth.botFalling() && facts.reachDist < 4.5f) return MoveSearch.Kind.COUNTER_CRIT;
		if (dNow && ownCombo >= 1 && ownCombo <= 3) return MoveSearch.Kind.HOLD_COMBO;
		if (!dNow && !tNow && facts.reachDist >= 2.2f && facts.reachDist <= 3.6f && mc.player.getAttackStrengthScale(0f) >= 0.95f
				&& clock.ticksSinceHitDealt > 20 && clock.ticksSinceHitTaken > 20 && rng.nextFloat() < 0.08f) return MoveSearch.Kind.COMBO_START;
		return null;
	}

	private void addAndPersistSnapshot(String id, SwordNet net) {
		int idx = league.addSnapshot(id, net);
		try { league.saveSnapshotSlot(SwordFiles.leaguePoolDir(), idx); }
		catch (IOException e) { SwordAi.LOGGER.warn("could not save PFSP slot " + idx, e); }
	}

	private void saveMilestone(long upd) {
		Path dir = SwordFiles.milestonesDir();
		try {
			Files.createDirectories(dir);
			Path dst = dir.resolve(String.format(Locale.ROOT, "u%07d.bin", upd));
			learner.save(dst);
			try (var stream = Files.list(dir)) {
				List<Path> all = stream.filter(p -> p.getFileName().toString().endsWith(".bin")).sorted().toList();
				for (int i = 0; i < all.size() - MAX_MILESTONES; i++) Files.deleteIfExists(all.get(i));
			}
			SwordAi.LOGGER.info("Saved healthy milestone {} (ema aim={}%, low={}%, whf={}%)",
					dst.getFileName(), Math.round(emaAim * 100f), String.format(Locale.ROOT, "%.1f", emaLow * 100f), String.format(Locale.ROOT, "%.1f", emaWhf * 100f));
		} catch (IOException e) { SwordAi.LOGGER.warn("could not save milestone", e); }
	}

	private void recoverFromCollapse(String reason) {
		Path fallback = SwordFiles.baselineCheckpoint();
		Path dir = SwordFiles.milestonesDir();
		if (Files.isDirectory(dir)) {
			try (var stream = Files.list(dir)) {
				List<Path> all = stream.filter(p -> p.getFileName().toString().endsWith(".bin")).sorted().toList();
				if (!all.isEmpty()) fallback = all.get(all.size() - 1);
			} catch (IOException ignored) {}
		}
		try {
			if (Files.exists(fallback)) {
				learner.load(fallback);
				learner.setGruEnabled(stage >= 3);
				learner.ppo.lr = 1.0e-4f;
				LeagueLearner.copyWeights(learner.actor, selfNet);
				badUpdates = 0; emaAim = 0.88f; emaLow = 0.05f; emaWhf = 0.05f;
				saveAll();
				SwordAi.LOGGER.warn("[AUTO-RECOVERY] {} -> rolled back to {}", reason, fallback.getFileName());
			}
		} catch (IOException e) { SwordAi.LOGGER.error("[AUTO-RECOVERY] rollback failed", e); }
	}

	private static long freeSystemRamMb() {
		var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
		if (bean instanceof com.sun.management.OperatingSystemMXBean os) return os.getFreeMemorySize() / (1024L * 1024L);
		return -1L;
	}

	private void saveAll() {
		try {
			learner.save(SwordFiles.schoolCheckpoint());
			if (exploiter != null) exploiter.learner.save(SwordFiles.exploiterCheckpoint());
			if (humanChallenger != null) humanChallenger.learner.save(SwordFiles.humanChallengerCheckpoint());
			if (stage >= 4 && idm.trainedSteps() > 0) idm.save(SwordFiles.idmCheckpoint());
			if (league.snapshotCount() > 0) league.savePoolMeta(SwordFiles.leaguePoolDir());
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
				: List.of(String.format(Locale.ROOT, "Stage %d (%s): %d updates, %d steps | %s", stage, currentKit, learner.updates, learner.totalSteps, league.summary()));
	}
}
