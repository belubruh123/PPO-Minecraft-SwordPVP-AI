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
	}

	/** How many knockbacks have been held back since the game started. */
	public static long held() {
		return held;
	}

	/** Asked just before a knockback is applied. True: it has been kept and comes later; nothing is to be done now. */
	public static boolean hold(LivingEntity entity, double strength, double x, double z) {
		if (entity != who || applying) {
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
		} finally {
			applying = false;
		}
	}
}
