package dev.swordai.server;

import dev.swordai.mixin.LivingEntityAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * The last ticks of both fighters, so that a moment of a fight can be set up again exactly (scene replay: the user, 26 Oct
 * 08:50: "just give it the 60-120 frames that it actually needs to perform the action to train ... tp to exact location where
 * this scene will be taken"). Written on the server thread every tick of a fight; read by the client thread, which marks the
 * windows it lost and starts training episodes from them (SchoolSession#scenes).
 */
public final class SceneRecorder {
	/** How far back a scene can start. */
	public static final int TICKS = 240;
	/** Numbers kept for each fighter: position, motion, look, sword clock, health, hurt and grace ticks, fall, sprint. */
	static final int BODY = 14;

	private final long[] at = new long[TICKS];
	private final double[][] state = new double[TICKS][2 * BODY];
	private int count;
	private int next;

	synchronized void clear() {
		count = 0;
		next = 0;
	}

	synchronized void note(long tick, ServerPlayer agent, ServerPlayer bot) {
		at[next] = tick;
		write(agent, state[next], 0);
		write(bot, state[next], BODY);
		next = (next + 1) % TICKS;
		count = Math.min(count + 1, TICKS);
	}

	/** Both fighters as they were on server tick {@code tick}, or null when that tick is not kept (any more). */
	public synchronized double[] at(long tick) {
		for (int i = 0; i < count; i++) {
			int k = (next - 1 - i + TICKS) % TICKS;
			if (at[k] == tick) {
				return state[k].clone();
			}
		}
		return null;
	}

	/**
	 * The agent's own client after each of its ticks: where it was and how fast it was moving (7 Nov, the restore check). The
	 * server never learns a client player's motion: it only gets positions, and its own copy of the player's velocity is a
	 * no-input simulation (1.21.11: Player.canSimulateMovement), so recording {@code getDeltaMovement()} for the agent put every
	 * scene back with the wrong velocity. The client writes this (same game, the integrated server) and the recorder takes the
	 * snapshot whose position is the one the server has.
	 */
	private static final int SNAPS = 8;
	private static final double[][] clientSnap = new double[SNAPS][6];
	private static int clientSnapNext, clientSnapCount;
	/** Agent velocities recorded from its client, and from the server's movement packets when no snapshot matched. */
	public static volatile long fromClient, fromPackets;

	public static synchronized void noteClientMotion(double x, double y, double z, double vx, double vy, double vz) {
		double[] c = clientSnap[clientSnapNext];
		c[0] = x;
		c[1] = y;
		c[2] = z;
		c[3] = vx;
		c[4] = vy;
		c[5] = vz;
		clientSnapNext = (clientSnapNext + 1) % SNAPS;
		clientSnapCount = Math.min(clientSnapCount + 1, SNAPS);
	}

	/** The client's velocity when it was at (x, y, z), newest first, or null. */
	private static synchronized double[] clientMotionAt(double x, double y, double z) {
		for (int i = 0; i < clientSnapCount; i++) {
			double[] c = clientSnap[(clientSnapNext - 1 - i + SNAPS) % SNAPS];
			if (Math.abs(c[0] - x) < 1e-6 && Math.abs(c[1] - y) < 1e-6 && Math.abs(c[2] - z) < 1e-6) {
				return new double[] {c[3], c[4], c[5]};
			}
		}
		return null;
	}

	/** How fast a fighter is really moving: for a real client (the agent) its own velocity, else the server's. */
	static Vec3 motion(ServerPlayer p, boolean agent) {
		if (!agent) {
			return p.getDeltaMovement();
		}
		double[] c = clientMotionAt(p.getX(), p.getY(), p.getZ());
		if (c != null) {
			fromClient++;
			return new Vec3(c[0], c[1], c[2]);
		}
		// No snapshot at this position: the client's last move as the server got it, turned into the velocity it leaves
		// behind (friction 0.546 on the ground, 0.91 in the air; gravity 0.08, drag 0.98).
		fromPackets++;
		Vec3 m = p.getKnownMovement();
		double f = p.onGround() ? 0.546 : 0.91;
		return new Vec3(m.x * f, p.onGround() ? 0 : (m.y - 0.08) * 0.98, m.z * f);
	}

	private static void write(ServerPlayer p, double[] s, int o) {
		Vec3 v = motion(p, o == 0);
		s[o] = p.getX();
		s[o + 1] = p.getY();
		s[o + 2] = p.getZ();
		s[o + 3] = v.x;
		s[o + 4] = v.y;
		s[o + 5] = v.z;
		s[o + 6] = p.getYRot();
		s[o + 7] = p.getXRot();
		s[o + 8] = ((LivingEntityAccessor) p).swordai$getAttackStrengthTicker();
		s[o + 9] = p.getHealth();
		s[o + 10] = p.hurtTime;
		s[o + 11] = p.invulnerableTime;
		s[o + 12] = p.fallDistance;
		s[o + 13] = p.isSprinting() ? 1 : 0;
	}

	/** Puts a fighter where {@code s} (from {@link #at}) had it: {@code o} 0 for the agent, BODY for the bot. */
	static void apply(ServerLevel level, ServerPlayer p, double[] s, int o) {
		p.teleportTo(level, s[o], s[o + 1], s[o + 2], Set.of(), (float) s[o + 6], (float) s[o + 7], true);
		p.setYHeadRot((float) s[o + 6]);
		p.setDeltaMovement(s[o + 3], s[o + 4], s[o + 5]);
		if (o == 0) {
			// The agent's own client moves it: its motion goes out right behind the teleport (which sets it to zero), so both
			// arrive together; hurtMarked would send it a tick later, after the client had already moved once without it.
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
		} else {
			p.hurtMarked = true;
		}
		((LivingEntityAccessor) p).swordai$setAttackStrengthTicker((int) s[o + 8]);
		p.setHealth((float) Math.max(1.0, s[o + 9]));
		p.hurtTime = (int) s[o + 10];
		p.invulnerableTime = (int) s[o + 11];
		p.fallDistance = s[o + 12];
		p.setSprinting(s[o + 13] > 0.5);
	}

	/** The agent's sword clock in {@code s}: its client keeps its own and is set to this. */
	public static int agentTicker(double[] s) {
		return (int) s[8];
	}
}
