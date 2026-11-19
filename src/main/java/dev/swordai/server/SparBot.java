package dev.swordai.server;

import dev.swordai.core.league.InverseDynamics;
import dev.swordai.core.league.League;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Full-fight sparring opponent with four non-single-technique styles ({@link League.SparStyle}:
 * crit spammer, jump-in critter, combo player, mixer), driven through {@link ReplicaBody} so it
 * obeys exact client physics. Also emits ground-truth {@code (state_{t-1}, state_t, state_{t+1}) -> keys}
 * transitions to {@link InverseDynamics} (Follow-up #2) so the VPT key guesser trains on real
 * Minecraft physics.
 */
public final class SparBot {
	private final ReplicaBody body = new ReplicaBody(0.5f);
	private final AimMotor motor = new AimMotor(0.6f);
	private final int[] action = new int[ActionSpace.HEADS];
	private final int[] prevAction = new int[ActionSpace.HEADS];
	private final float[] tickPrev2 = new float[InverseDynamics.TICK_DIM];
	private final float[] tickPrev1 = new float[InverseDynamics.TICK_DIM];
	private final float[] tickCur = new float[InverseDynamics.TICK_DIM];
	private final float[] idmWindow = new float[InverseDynamics.IN_DIM];
	private int ticksRecorded;
	private boolean tookHitPrev;
	private boolean tookHitCur;

	private League.SparStyle style = League.SparStyle.MIXER;
	private Random rng = new Random(1L);
	private int strafeDir = 1;
	private int strafeTimer;
	private int sinceHitDealt = 100;
	private int sinceHitTaken = 100;
	private int ownCombo;
	private int critChain;
	private InverseDynamics idm;

	public void setInverseDynamics(InverseDynamics idm) {
		this.idm = idm;
	}

	public void start(League.SparStyle style, long seed, int lagTicks) {
		this.style = style == null ? League.SparStyle.MIXER : style;
		this.rng = new Random(seed == 0L ? System.nanoTime() : seed);
		this.body.reset();
		this.body.setLag(lagTicks);
		this.motor.reset();
		this.strafeDir = rng.nextBoolean() ? 1 : 2;
		this.strafeTimer = 10 + rng.nextInt(20);
		this.sinceHitDealt = 100;
		this.sinceHitTaken = 100;
		this.ownCombo = 0;
		this.critChain = 0;
		this.ticksRecorded = 0;
		this.tookHitPrev = false;
		this.tookHitCur = false;
	}

	public void idle(ServerPlayer bot) {
		body.idle(bot);
	}

	public void hitDealt() {
		sinceHitDealt = 0;
		ownCombo++;
	}

	public void hitTaken() {
		sinceHitTaken = 0;
		ownCombo = 0;
		tookHitCur = true;
	}

	public void tick(ServerPlayer bot, ServerPlayer opp, double floorY) {
		body.sense(bot, opp, floorY);
		sinceHitDealt = Math.min(1000, sinceHitDealt + 1);
		sinceHitTaken = Math.min(1000, sinceHitTaken + 1);

		Vec3 eye = bot.getEyePosition();
		double dx = opp.getX() - eye.x;
		double dy = opp.getEyeY() - 0.25 - eye.y;
		double dz = opp.getZ() - eye.z;
		float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(eye));
		float yawErr = ViewGeometry.wrapDegrees(ViewGeometry.yawTo(dx, dz) - bot.getYRot());
		float pitchErr = ViewGeometry.pitchTo(dx, dy, dz) - bot.getXRot();
		// Slight human-like aim jitter so the bot is realistic.
		yawErr += (rng.nextFloat() - 0.5f) * 3f;
		pitchErr += (rng.nextFloat() - 0.5f) * 2f;

		action[ActionSpace.YAW] = InverseDynamics.nearestBin(yawErr, ActionSpace.YAW_BINS);
		action[ActionSpace.PITCH] = InverseDynamics.nearestBin(pitchErr, ActionSpace.PITCH_BINS);

		float charge = bot.getAttackStrengthScale(0f);
		boolean onGround = bot.onGround();
		boolean falling = !onGround && bot.getDeltaMovement().y < -0.05;
		boolean aimed = body.aimedAt(bot, opp);
		boolean ready = charge >= 0.92f;
		boolean oppAir = !opp.onGround();

		if (--strafeTimer <= 0) {
			strafeDir = rng.nextInt(3);
			strafeTimer = 8 + rng.nextInt(20);
		}

		// Full-fight decision tuned to the active League.SparStyle archetype.
		int fwd = 1;
		int strafe = dist < 4.5f ? strafeDir : 0;
		int sprint = 1;
		int jump = 0;
		int attack = 0;

		League.SparStyle activeStyle = style == League.SparStyle.MIXER
				? League.SparStyle.values()[(sinceHitDealt + sinceHitTaken + strafeTimer) % 3]
				: style;

		switch (activeStyle) {
			case CRIT_SPAMMER -> {
				// Close-range crit pressure, double crits, and punish crits when lifted.
				if (dist < 2.6f && onGround && charge >= 0.65f) {
					jump = 1;
					sprint = 0;
					critChain++;
				} else if (dist < 1.8f) {
					fwd = 0;
					sprint = 0;
				}
				if (!onGround) {
					sprint = 0;
					attack = (falling && ready && aimed) ? 1 : 0;
				} else {
					attack = (ready && aimed && (dist > 2.5f || charge >= 0.98f)) ? 1 : 0;
				}
			}
			case JUMP_IN_CRITTER -> {
				// Jump-ins from 3.2-4.8 blocks, then sprint spacing or crit follow-up.
				if (onGround && dist >= 3.1f && dist <= 4.7f && charge >= 0.72f) {
					jump = 1;
					fwd = 1;
					sprint = 1;
				} else if (!onGround) {
					sprint = 0;
					fwd = dist > 2.2f ? 1 : 0;
					attack = (falling && ready && aimed) ? 1 : 0;
				} else {
					if (sinceHitDealt <= 3 && dist < 2.8f) {
						fwd = 2; // brief back-step on cooldown
						sprint = 0;
					}
					attack = (ready && aimed) ? 1 : 0;
				}
			}
			case COMBO_PLAYER, MIXER -> {
				// W-tap combo spacing around 2.5-3.0 blocks + jump reset on hit + punish crit if lifted close.
				if (sinceHitTaken == 1 && onGround && dist < 3.4f && rng.nextFloat() < 0.75f) {
					jump = 1; // jump reset
				} else if (ownCombo >= 1 && dist < 2.5f && charge < 0.85f) {
					fwd = dist < 2.1f ? 2 : 0; // W-tap / S-tap to hold combo spacing
					sprint = 0;
				} else if (sinceHitDealt <= 2) {
					fwd = 0; // W-tap reset for next sprint hit
					sprint = 0;
				} else if (oppAir && dist > 2.8f) {
					fwd = 1;
					sprint = 1;
				}
				if (!onGround && falling && ready && aimed) {
					sprint = 0;
					attack = 1;
				} else if (onGround && ready && aimed) {
					attack = 1;
				}
			}
		}

		action[ActionSpace.FORWARD] = fwd;
		action[ActionSpace.STRAFE] = strafe;
		action[ActionSpace.SPRINT] = sprint;
		action[ActionSpace.JUMP] = jump;
		action[ActionSpace.ATTACK] = attack;

		// Feed ground-truth (t-2, t-1, t) -> action_{t-1} transition into InverseDynamics.
		recordIdmSample(bot, opp, dist, charge);

		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		body.act(bot, opp, action, motor.yawRate(), motor.pitchRate());
		System.arraycopy(action, 0, prevAction, 0, ActionSpace.HEADS);
		tookHitPrev = tookHitCur;
		tookHitCur = false;
	}

	private void recordIdmSample(ServerPlayer bot, ServerPlayer opp, float dist, float charge) {
		if (idm == null) {
			return;
		}
		double ux = bot.getX() - opp.getX();
		double uz = bot.getZ() - opp.getZ();
		double flat = Math.sqrt(ux * ux + uz * uz);
		ux = flat > 1e-6 ? ux / flat : 0;
		uz = flat > 1e-6 ? uz / flat : 0;
		double vx = bot.getX() - bot.xo;
		double vz = bot.getZ() - bot.zo;
		float toward = (float) -(vx * ux + vz * uz);
		float sideSigned = (float) (vx * uz - vz * ux);
		float up = (float) (bot.getY() - bot.yo);
		float toOppYaw = ViewGeometry.yawTo(opp.getX() - bot.getX(), opp.getZ() - bot.getZ());
		float facing = ViewGeometry.wrapDegrees(bot.getYHeadRot() - toOppYaw);

		System.arraycopy(tickPrev1, 0, tickPrev2, 0, InverseDynamics.TICK_DIM);
		System.arraycopy(tickCur, 0, tickPrev1, 0, InverseDynamics.TICK_DIM);
		InverseDynamics.packTick(dist, toward, sideSigned, up, bot.onGround(), bot.isSprinting(),
				charge, bot.swinging, facing, bot.getXRot(), tickCur, 0);
		if (++ticksRecorded >= 3) {
			InverseDynamics.packWindow(tickPrev2, tickPrev1, tickCur, tookHitPrev, idmWindow);
			idm.addSample(idmWindow, prevAction);
		}
	}
}
