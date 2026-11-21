package dev.swordai.client;

import dev.swordai.core.env.*;
import dev.swordai.core.league.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** Fake-opponent pilots on {@link ReplicaBody}: NetPilot (self/snapshot/baseline), ExploiterPilot, HumanPilot. */
final class LeaguePilots {
	private LeaguePilots() {}

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
		private final IdmRecorder idmRec = new IdmRecorder();

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
			ObsBuilder.writeActor(obs, body.frame(), sight, st, motor, hasLast ? lastAction : null, ActionSpace.ALL_HEADS);
			memory.pushSeen(obs.vec);
			net.forward(ws, ws.step, obs.peri, obs.fovea, obs.vec, memory.hist, memory.seen, memory.hidden, memory.hidden);
			if (temp <= 0f) net.samplePlay(ws, ws.step, rng, action);
			else net.sample(ws, ws.step, ActionSpace.ALL_HEADS, false, temp, rng, action);
			memory.pushAction(action);
			idmRec.record(bot, opp, action);
			motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
			body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
			System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS); hasLast = true;
		}
	}

	static final class ExploiterPilot implements SparPilot {
		final LeagueLearner learner;
		private final ReplicaBody body = new ReplicaBody(0.5f);
		private final SightTracker sight = new SightTracker();
		private final SessionClock clock = new SessionClock();
		private final StepFacts facts = new StepFacts();
		private final AimMotor motor;
		private final Obs obs = new Obs(), prevObs = new Obs();
		private final LeagueLearner.Decision dec = new LeagueLearner.Decision(), prevDec = new LeagueLearner.Decision();
		private final int[] lastAction = new int[ActionSpace.HEADS];
		private final float[] view = new float[3];
		private boolean hasPrev;
		private float lastMyHp = 20f, lastOppHp = 20f, prevPhi;
		private int ownCombo, oppCombo;

		ExploiterPilot(LeagueLearner learner, float aimResponse) { this.learner = learner; this.motor = new AimMotor(aimResponse); }
		@Override public void reset() {
			body.reset(); sight.reset(); clock.reset(); motor.reset(); learner.resetEpisode();
			hasPrev = false; lastMyHp = lastOppHp = 20f; prevPhi = 0f; ownCombo = oppCombo = 0;
		}
		@Override public void setLag(int t) { body.setLag(t); }
		@Override public void hitDealt() { clock.hitDealt(); ownCombo++; oppCombo = 0; body.state().updateExchange(true, false, false); }
		@Override public void hitTaken() { clock.hitTaken(); oppCombo++; ownCombo = 0; body.state().updateExchange(false, false, true); }
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
			ObsBuilder.writeActor(obs, body.frame(), sight, st, motor, hasPrev ? lastAction : null, ActionSpace.ALL_HEADS);
			Vec3 rel = body.shownEyesFrom(bot, opp);
			ViewGeometry.toYawFrame(rel.x, rel.y, rel.z, bot.getYRot(), view);
			ObsBuilder.writePrivileged(obs.priv, facts, st, view[0], view[1], view[2], opp.getHealth(),
					opp.getAttackStrengthScale(0f), opp.isSprinting(), opp.onGround(), facts.onBody && facts.reachDist <= 3f, clock, 0);

			float myHp = bot.getHealth(), oppHp = opp.getHealth();
			float dd = Math.max(0f, lastOppHp - oppHp), dt = Math.max(0f, lastMyHp - myHp);
			lastMyHp = myHp; lastOppHp = oppHp;
			float phi = FixedReward.potential(myHp / 20f, oppHp / 20f, ownCombo, oppCombo, st.charge, opp.getAttackStrengthScale(0f), facts.reachDist, done);
			if (hasPrev) {
				learner.record(prevObs, prevDec, FixedReward.step(dd, dt, koD, koT, prevPhi, phi), done, null, 0f);
				if (learner.rollout.isFull()) learner.update(done ? 0f : learner.bootstrapValue(obs), null, 0f, 0f);
			}
			prevPhi = phi;
			if (done) { hasPrev = false; return; }
			learner.decide(obs, false, 1f, dec);
			prevObs.copyFrom(obs); System.arraycopy(dec.action, 0, prevDec.action, 0, ActionSpace.HEADS);
			prevDec.logp = dec.logp; prevDec.value = dec.value;
			System.arraycopy(dec.action, 0, lastAction, 0, ActionSpace.HEADS); hasPrev = true;
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
			HumanImitation.HumanPolicy.packLiveState(dist, bot.getAttackStrengthScale(0f), st.opponentCharge(),
					bot.onGround(), opp.onGround(), bot.isSprinting(), st.velForward, st.velRight, st.velUp,
					-st.oppVelForward, st.oppVelUp, bot.getHealth() / 20f, opp.getHealth() / 20f,
					sinceD, sinceT, ownC, oppC, yawErr, bot.getXRot(), prevDist, state);
			prevDist = dist;
			policy.act(state, 0.9f, rng, action);
			if (Math.abs(yawErr) > 18f) action[ActionSpace.YAW] = InverseDynamics.nearestBin(yawErr * 0.7f, ActionSpace.YAW_BINS);
			if (Math.abs(pitchErr) > 14f) action[ActionSpace.PITCH] = InverseDynamics.nearestBin(pitchErr * 0.7f, ActionSpace.PITCH_BINS);
			motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
			body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
		}
	}

	private static final class IdmRecorder {
		InverseDynamics idm;
		private final float[] t0 = new float[InverseDynamics.TICK_DIM], t1 = new float[InverseDynamics.TICK_DIM];
		private final float[] t2 = new float[InverseDynamics.TICK_DIM], win = new float[InverseDynamics.IN_DIM];
		private final int[] prevAct = new int[ActionSpace.HEADS];
		private int count;
		boolean tookHit;
		private boolean prevTookHit;

		void reset() { count = 0; tookHit = prevTookHit = false; }
		void record(ServerPlayer bot, ServerPlayer opp, int[] action) {
			if (idm == null) return;
			double ux = bot.getX() - opp.getX(), uz = bot.getZ() - opp.getZ(), flat = Math.sqrt(ux * ux + uz * uz);
			ux = flat > 1e-6 ? ux / flat : 0; uz = flat > 1e-6 ? uz / flat : 0;
			double vx = bot.getX() - bot.xo, vz = bot.getZ() - bot.zo;
			float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(bot.getEyePosition()));
			float facing = ViewGeometry.wrapDegrees(bot.getYHeadRot() - ViewGeometry.yawTo(opp.getX() - bot.getX(), opp.getZ() - bot.getZ()));
			System.arraycopy(t1, 0, t0, 0, InverseDynamics.TICK_DIM); System.arraycopy(t2, 0, t1, 0, InverseDynamics.TICK_DIM);
			InverseDynamics.packTick(dist, (float) -(vx * ux + vz * uz), (float) (vx * uz - vz * ux), (float) (bot.getY() - bot.yo),
					bot.onGround(), bot.isSprinting(), bot.getAttackStrengthScale(0f), bot.swinging, facing, bot.getXRot(), t2, 0);
			if (++count >= 3) { InverseDynamics.packWindow(t0, t1, t2, prevTookHit, win); idm.addSample(win, prevAct); }
			System.arraycopy(action, 0, prevAct, 0, ActionSpace.HEADS);
			prevTookHit = tookHit; tookHit = false;
		}
	}
}
