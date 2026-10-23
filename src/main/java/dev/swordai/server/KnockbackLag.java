package dev.swordai.server;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;

/**
 * The opponent's own connection to the server. A real player is knocked back by its own game, and
 * its game hears of a hit a moment after the server has dealt it; until the answer is back, the
 * server has it going on as it was. So a player hit on a server stays where a sword reaches it, and
 * can swing back, for a few ticks that a fake player in a singleplayer world does not have: there
 * the hit moves it on the spot. A fighter that has learned its combos on the second kind finds the
 * first kind still in its face.
 *
 * <p>While a fight is on, a hit's knockback on the one opponent is kept here for as many ticks as
 * the fight says and then applied unchanged. Server thread only.
 *
 * <p>And the fighter's own, where the fight says so ({@link #setAgent}): on a server its game too hears of a hit a moment
 * late, and until then it is where it was, in reach of the one that hit it. The user, 23 October: "it kb you away but
 * because you are charged and it won't suddenly put you out of reach, so you can land a crit on them".
 */
public final class KnockbackLag {
	private record Pending(LivingEntity entity, double strength, double x, double z, long due) {
	}

	private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
	/** Null: nobody's knockback is late. */
	private static volatile LivingEntity who;
	private static int ticks;
	private static float scale = 1f;
	private static long now;
	private static boolean applying;
	private static long held;
	/** The fighter whose own knockback is late, or null; and by how many ticks. */
	private static volatile LivingEntity agent;
	private static int agentTicks;

	private KnockbackLag() {
	}

	/**
	 * Whose knockback is late from now on, by how many ticks, and how much of it the fighter takes
	 * (1 is all of it: a real opponent often takes less, running into the hit or jumping as it
	 * lands). What was still due is applied at once.
	 */
	static void set(LivingEntity fighter, int lagTicks, float share) {
		flush();
		ticks = Math.max(0, lagTicks);
		scale = Math.max(0f, Math.min(1f, share));
		who = ticks > 0 || scale < 1f ? fighter : null;
	}

	/** Whether the fighter's own knockback is late from now on, and by how many ticks (0: not). */
	static void setAgent(LivingEntity fighter, int lagTicks) {
		agentTicks = Math.max(0, lagTicks);
		agent = agentTicks > 0 ? fighter : null;
	}

	/** Once per server tick while the arena runs. */
	static void tick() {
		now++;
		while (!PENDING.isEmpty() && PENDING.peekFirst().due <= now) {
			apply(PENDING.removeFirst());
		}
	}

	/** Nobody's knockback is late any more. */
	static void off() {
		flush();
		who = null;
		scale = 1f;
		agent = null;
		agentTicks = 0;
	}

	/** How many knockbacks have been held back since the game started. */
	public static long held() {
		return held;
	}

	/** Asked just before a knockback is applied. True: it has been kept and comes later; nothing is to be done now. */
	public static boolean hold(LivingEntity entity, double strength, double x, double z) {
		if (applying) {
			return false;
		}
		if (entity == agent && entity != who) {
			PENDING.addLast(new Pending(entity, strength, x, z, now + agentTicks));
			held++;
			return true;
		}
		if (entity != who) {
			return false;
		}
		if (ticks == 0) {
			// Not late, only weaker.
			apply(new Pending(entity, strength * scale, x, z, now));
			return true;
		}
		PENDING.addLast(new Pending(entity, strength * scale, x, z, now + ticks));
		held++;
		return true;
	}

	private static void flush() {
		while (!PENDING.isEmpty()) {
			apply(PENDING.removeFirst());
		}
	}

	private static void apply(Pending p) {
		if (p.entity.isRemoved()) {
			return;
		}
		applying = true;
		try {
			p.entity.knockback(p.strength, p.x, p.z);
			if (p.entity == agent) {
				// A player moves itself: the knockback reaches its game only with the motion the server sends it, and the
				// one sent on the tick of the hit went out without it.
				p.entity.hurtMarked = true;
			}
		} finally {
			applying = false;
		}
	}
}
