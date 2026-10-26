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

	private static void write(ServerPlayer p, double[] s, int o) {
		Vec3 v = p.getDeltaMovement();
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
		// Sends the motion to the client (the agent's own client moves it).
		p.hurtMarked = true;
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
