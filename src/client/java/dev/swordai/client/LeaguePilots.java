package dev.swordai.client;

import dev.swordai.core.env.*;
import dev.swordai.core.league.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** Fake-opponent pilots on {@link ReplicaBody}: NetPilot, ExploiterPilot (Main Exploiter & Human-KL Challenger), HumanPilot. */
final class LeaguePilots {
	private LeaguePilots() {}

	static void recordIdm(InverseDynamics.LaggedRecorder rec, ServerPlayer bot, ServerPlayer opp, int[] action) {
		if (rec.idm == null) return;
		float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(bot.getEyePosition()));
		rec.record(bot.getX(), bot.getY(), bot.getZ(), bot.yBodyRot, bot.getYHeadRot(), bot.getXRot(),
				bot.onGround(), bot.isSprinting(), bot.swinging, bot.swingTime, opp.getX(), opp.getY(), opp.getZ(), dist, action);
	}

	static final class NetPilot implements SparPilot {
		private final SwordNet net;
		private final SwordNet.Workspace ws;
		private final ReplicaBody body = new ReplicaBody(0.5f);
		private final SightTracker sight = new SightTracker();
		private final ObsMemory memory = new ObsMemory();
		private final SessionClock clock = new SessionClock();
		private final AimMotor motor;
		private final Obs obs = new Obs();
		private final Random rng;
		private final float temp;
		private final int[] action = new int[ActionSpace.HEADS], lastAction = new int[ActionSpace.HEADS];
		private boolean hasLast;
		private final InverseDynamics.LaggedRecorder idmRec = new InverseDynamics.LaggedRecorder();

		NetPilot(SwordNet net, float aimResponse, float temp, long seed, InverseDynamics idm) {
			this.net = net; this.ws = net.newWorkspace(); this.motor = new AimMotor(aimResponse);
			this.temp = temp; this.rng = new Random(seed); this.idmRec.idm = idm;
		}
		@Override public void reset() { body.reset(); sight.reset(); memory.reset(); clock.reset(); motor.reset(); hasLast = false; idmRec.reset(); }
		@Override public void setLag(int t) { body.setLag(t); }
		@Override public void hitDealt() { clock.hitDealt(); body.state().updateExchange(true, false, false); }
		@Override public void hitTaken() { clock.hitTaken(); body.state().updateExchange(false, false, true); idmRec.tookHit = true; }
		@Override public void idle(ServerPlayer bot) { body.idle(bot); }
		@Override public String swingSummary() { return body.swingSummary(); }

		@Override
		public void tick(ServerPlayer bot, ServerPlayer opp, double floorY) {
			body.sense(bot, opp, floorY); clock.tick();
			BodyState st = body.state();
			st.ticksSinceHitDealt = clock.ticksSinceHitDealt; st.ticksSinceHitTaken = clock.ticksSinceHitTaken;
			st.hitsTakenInRow = clock.takenStreak.shown();
			System.arraycopy(body.slots(), 0, obs.slots, 0, ObsSpec.SLOTS_SIZE);
			System.arraycopy(body.terrain(), 0, obs.terrain, 0, ObsSpec.TERRAIN_SIZE);
			ObsBuilder.writeActor(obs, body.frame(), sight, st, motor, hasLast ? lastAction : null, ActionSpace.PHASE1_HEADS);
			memory.pushSeen(obs.vec);
			net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, obs.slots, obs.terrain, memory.hist, memory.seen, memory.hidden, memory.hidden);
			if (temp <= 0f) net.samplePlay(ws, ws.step, ActionSpace.PHASE1_HEADS, rng, action);
			else net.sample(ws, ws.step, ActionSpace.PHASE1_HEADS, false, temp, rng, action);
			memory.pushAction(action);
			recordIdm(idmRec, bot, opp, action);
			motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
			body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
			System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS); hasLast = true;
		}
	}

	static final class ExploiterPilot implements SparPilot {
		final LeagueLearner learner;
		private final SwordNet priorNet;
		private final ReplicaBody body = new ReplicaBody(0.5f);
		private final SightTracker sight = new SightTracker();
		private final SessionClock clock = new SessionClock();
		private final StepFacts facts = new StepFacts();
		private final AimMotor motor;
		private final Obs obs = new Obs(), prevObs = new Obs();
		private final LeagueLearner.Decision dec = new LeagueLearner.Decision(), prevDec = new LeagueLearner.Decision();
		private final int[] lastAction = new int[ActionSpace.HEADS];
		private final float[] view = new float[3], styleState = new float[HumanImitation.STATE_DIM];
		private final float[] prevStyleProbs = new float[ActionSpace.TOTAL_LOGITS];
		private final InverseDynamics.LaggedRecorder idmRec = new InverseDynamics.LaggedRecorder();
		private volatile HumanImitation.HumanPolicy stylePrior;
		private volatile float styleKl;
		private boolean hasPrev, hasPrevStyle;
		private float lastMyHp = 20f, lastOppHp = 20f, prevPhi, prevDist = 4f;
		private int ownCombo, oppCombo;
		int rounds, gen, styleIdx;

		ExploiterPilot(LeagueLearner learner, SwordNet priorNet, float aimResponse, InverseDynamics idm) {
			this.learner = learner; this.priorNet = priorNet; this.motor = new AimMotor(aimResponse); this.idmRec.idm = idm;
		}
		void setStylePrior(HumanImitation.HumanPolicy p, float kl) { this.stylePrior = p; this.styleKl = kl; }
		String styleName() { HumanImitation.HumanPolicy p = stylePrior; return p != null ? p.name : "none"; }
		void resetTo(SwordNet seed, boolean enableGru) {
			learner.resetForExploiter(seed, enableGru);
			rounds = 0; gen++;
		}
		@Override public void reset() {
			body.reset(); sight.reset(); clock.reset(); motor.reset(); learner.resetEpisode(); idmRec.reset();
			hasPrev = hasPrevStyle = false; lastMyHp = lastOppHp = 20f; prevPhi = 0f; prevDist = 4f; ownCombo = oppCombo = 0;
		}
		@Override public void setLag(int t) { body.setLag(t); }
		@Override public void hitDealt() { clock.hitDealt(); ownCombo++; oppCombo = 0; body.state().updateExchange(true, false, false); }
		@Override public void hitTaken() { clock.hitTaken(); oppCombo++; ownCombo = 0; body.state().updateExchange(false, false, true); idmRec.tookHit = true; }
		@Override public void idle(ServerPlayer bot) { body.idle(bot); }
		@Override public String swingSummary() { return body.swingSummary(); }
		@Override public void tick(ServerPlayer bot, ServerPlayer opp, double floorY) { step(bot, opp, floorY, false, false, false); }
		@Override public void roundOver(ServerPlayer bot, ServerPlayer opp, double floorY) {
			boolean koD = opp.getHealth() <= 1f || opp.isDeadOrDying();
			step(bot, opp, floorY, true, koD, !koD);
		}

		private void step(ServerPlayer bot, ServerPlayer opp, double floorY, boolean done, boolean koD, boolean koT) {
			body.sense(bot, opp, floorY); body.fillGeometry(facts, bot, opp); clock.tick();
			BodyState st = body.state();
			st.ticksSinceHitDealt = clock.ticksSinceHitDealt; st.ticksSinceHitTaken = clock.ticksSinceHitTaken;
			st.hitsTakenInRow = clock.takenStreak.shown();
			System.arraycopy(body.slots(), 0, obs.slots, 0, ObsSpec.SLOTS_SIZE);
			System.arraycopy(body.terrain(), 0, obs.terrain, 0, ObsSpec.TERRAIN_SIZE);
			ObsBuilder.writeActor(obs, body.frame(), sight, st, motor, hasPrev ? lastAction : null, ActionSpace.PHASE1_HEADS);
			Vec3 rel = body.shownEyesFrom(bot, opp);
			ViewGeometry.toYawFrame(rel.x, rel.y, rel.z, bot.getYRot(), view);
			boolean hasShield = InventoryObs.hasShield(bot);
			boolean hasAxe = InventoryObs.findHotbarSlot(bot, ObsSpec.SF_AXE) >= 0;
			float myHp = bot.getHealth() + bot.getAbsorptionAmount(), oppHp = opp.getHealth() + opp.getAbsorptionAmount();
			ObsBuilder.writePrivileged(obs.priv, facts, st, view[0], view[1], view[2], oppHp,
					opp.getAttackStrengthScale(0f), opp.isSprinting(), opp.onGround(), facts.onBody && facts.reachDist <= 3f, clock, 0,
					opp.isBlocking(), InventoryObs.shieldCooldown(opp), bot.isBlocking(), InventoryObs.shieldCooldown(bot),
					hasShield, hasAxe);

			float dd = Math.max(0f, lastOppHp - oppHp), dt = Math.max(0f, lastMyHp - myHp);
			lastMyHp = myHp; lastOppHp = oppHp;
			float phi = FixedReward.potential(myHp / 20f, oppHp / 20f, ownCombo, oppCombo, st.charge, opp.getAttackStrengthScale(0f), facts.reachDist, done);
			if (hasPrev) {
				learner.record(prevObs, ActionSpace.PHASE1_HEADS, prevDec, FixedReward.step(dd, dt, koD, koT, prevPhi, phi), done, null, 0f);
				if (hasPrevStyle && styleKl > 0f) learner.recordStyle(prevStyleProbs, styleKl);
				if (learner.rollout.isFull()) learner.update(done ? 0f : learner.bootstrapValue(obs), priorNet, priorNet != null ? 0.01f : 0f, 0f);
			}
			prevPhi = phi;
			if (done) { hasPrev = hasPrevStyle = false; return; }
			learner.decide(obs, ActionSpace.PHASE1_HEADS, false, 1f, dec);
			HumanImitation.HumanPolicy sp = stylePrior;
			if (sp != null && styleKl > 0f) {
				Vec3 eye = bot.getEyePosition();
				float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(eye));
				float yawErr = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(opp.getX() - eye.x, opp.getZ() - eye.z) - bot.getYRot());
				HumanImitation.HumanPolicy.packLiveState(dist, bot.getAttackStrengthScale(0f), st.opponentCharge(),
						bot.onGround(), opp.onGround(), bot.isSprinting(), st.velForward, st.velRight, st.velUp,
						-st.oppVelForward, st.oppVelUp, myHp / 20f, oppHp / 20f,
						clock.ticksSinceHitDealt, clock.ticksSinceHitTaken, ownCombo, oppCombo, yawErr, bot.getXRot(), prevDist, styleState);
				prevDist = dist;
				sp.probs(styleState, prevStyleProbs);
				hasPrevStyle = true;
			} else {
				hasPrevStyle = false;
			}
			prevObs.copyFrom(obs); System.arraycopy(dec.action, 0, prevDec.action, 0, ActionSpace.HEADS);
			prevDec.logp = dec.logp; prevDec.value = dec.value;
			System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
			recordIdm(idmRec, bot, opp, dec.action);
			motor.step(ActionSpace.yawDegrees(dec.action), ActionSpace.pitchDegrees(dec.action));
			body.act(bot, opp, dec.action, motor.yawRate(), motor.pitchRate());
		}
	}

	static final class HumanPilot implements SparPilot {
		private final HumanImitation.HumanPolicy policy;
		private final ReplicaBody body = new ReplicaBody(0.5f);
		private final AimMotor motor = new AimMotor(0.6f);
		private final Random rng;
		private final float[] state = new float[HumanImitation.STATE_DIM];
		private final int[] action = new int[ActionSpace.HEADS];
		private int sinceD = 100, sinceT = 100, ownC, oppC;
		private float prevDist = 4f;

		HumanPilot(HumanImitation.HumanPolicy p, long seed) { policy = p; rng = new Random(seed); }
		@Override public void reset() { body.reset(); motor.reset(); sinceD = sinceT = 100; ownC = oppC = 0; prevDist = 4f; }
		@Override public void setLag(int t) { body.setLag(t); }
		@Override public void hitDealt() { sinceD = 0; ownC++; oppC = 0; }
		@Override public void hitTaken() { sinceT = 0; oppC++; ownC = 0; }
		@Override public void idle(ServerPlayer bot) { body.idle(bot); }
		@Override public String swingSummary() { return body.swingSummary(); }

		@Override
		public void tick(ServerPlayer bot, ServerPlayer opp, double floorY) {
			body.sense(bot, opp, floorY);
			sinceD = Math.min(1000, sinceD + 1); sinceT = Math.min(1000, sinceT + 1);
			Vec3 eye = bot.getEyePosition();
			float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(eye));
			float yawErr = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(opp.getX() - eye.x, opp.getZ() - eye.z) - bot.getYRot());
			float pitchErr = ViewGeometry.pitchTo(opp.getX() - eye.x, opp.getEyeY() - 0.25 - eye.y, opp.getZ() - eye.z) - bot.getXRot();
			BodyState st = body.state();
			float chg = bot.getAttackStrengthScale(0f);
			HumanImitation.HumanPolicy.packLiveState(dist, chg, st.opponentCharge(),
					bot.onGround(), opp.onGround(), bot.isSprinting(), st.velForward, st.velRight, st.velUp,
					-st.oppVelForward, st.oppVelUp, (bot.getHealth() + bot.getAbsorptionAmount()) / 20f,
					(opp.getHealth() + opp.getAbsorptionAmount()) / 20f,
					sinceD, sinceT, ownC, oppC, yawErr, bot.getXRot(), prevDist, state);
			prevDist = dist;
			policy.act(state, 0.85f, rng, action);
			// Ensure human Sword clone switches to its sword slot if spawned in a randomized hotbar slot.
			int swSlot = InventoryObs.findHotbarSlot(bot, ObsSpec.SF_SWORD);
			action[ActionSpace.HOTBAR_SLOT] = (swSlot >= 0 && bot.getInventory().getSelectedSlot() != swSlot) ? (swSlot + 1) : 0;
			if (!bot.onGround() && (bot.isSprinting() || body.serverSprinting()) && action[ActionSpace.SPRINT] == 0 && action[ActionSpace.FORWARD] == 1) {
				action[ActionSpace.FORWARD] = 0;
			}
			if (Math.abs(yawErr) > 3f) action[ActionSpace.YAW] = InverseDynamics.nearestBin(yawErr + (rng.nextFloat() - 0.5f) * 2.5f, ActionSpace.YAW_BINS);
			if (Math.abs(pitchErr) > 3f) action[ActionSpace.PITCH] = InverseDynamics.nearestBin(pitchErr + (rng.nextFloat() - 0.5f) * 2f, ActionSpace.PITCH_BINS);
			if (action[ActionSpace.ATTACK] == 1 && (chg < 0.85f || !body.aimedAt(bot, opp))) action[ActionSpace.ATTACK] = 0;
			motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
			body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
		}
	}
}
