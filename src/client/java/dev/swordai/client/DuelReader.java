package dev.swordai.client;

import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Habits;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * The fight as the player's own game shows it, for the technique school: both bodies, what happened
 * since the last tick (a hit each way and of what kind, a swing each way), and the memory of what this
 * opponent has been doing ({@link Habits}), which the network gets as an input. Only what a person's
 * game shows them goes in: where the opponent is drawn, its arm, whether it flinches, whether it is
 * sprinting, and how the player's own hits were made. The same reader serves training and the user's
 * fights, so the memory means one thing in both.
 *
 * <p>Client thread, once per decision.
 */
final class DuelReader {
	final Duel duel = new Duel();
	final Events events = new Events();
	final Habits habits = new Habits();
	/**
	 * The same detector watching the player's own character, as an opponent would: what it did by what the game
	 * shows (a jump on the tick it was hit, a crit after being hit, a second crit after its own...). It goes by the
	 * game alone, never by a teacher, so it says whether a technique was really done.
	 */
	final Habits own = new Habits();
	private final Duel mirror = new Duel();
	private final Events mirrorEvents = new Events();
	private final float[] mem = new float[Habits.SIZE];

	private boolean meWasOnGround = true;
	private boolean themWasOnGround = true;
	/** Their sprint on the tick before: the server ends a sprint the tick its sprint hit lands, so the hit shows them walking. */
	private boolean themWereSprinting;
	/** Their hits seen since the last {@link #takeTheirHits}: sprint (of them, seen sprinting only the tick before), plain, crit. */
	private final int[] theirHits = new int[4];
	private int lastMyHurt;
	private int lastTheirHurt;
	private boolean theyWereSwinging;
	private int theirLastSwingTime;
	private int theirSinceSwing = 1000;
	/** How far their sword was back when their last swing began: the charge of the hit that swing makes. */
	private float theirSwingCharge = 1f;
	private int mySinceSwing = 1000;
	/** How its own last swing was made, for the kind of a hit seen to land. */
	private int mySwingKind = Events.PLAIN;
	private boolean started;

	void reset() {
		habits.reset();
		own.reset();
		events.clear();
		meWasOnGround = true;
		themWasOnGround = true;
		themWereSprinting = false;
		theyWereSwinging = false;
		theirSinceSwing = 1000;
		theirSwingCharge = 1f;
		mySinceSwing = 1000;
		mySwingKind = Events.PLAIN;
		started = false;
	}

	/**
	 * One tick.
	 *
	 * @param swungLast   its own click on the last tick
	 * @param chargeAtClick, sprintAtClick, critAtClick how that swing was made
	 */
	void tick(LocalPlayer me, Player them, boolean swungLast, float chargeAtClick, boolean sprintAtClick, boolean critAtClick) {
		fill(duel.me, me, meWasOnGround);
		fill(duel.them, them, themWasOnGround);
		if (swungLast) {
			mySinceSwing = 0;
			mySwingKind = Events.kindOf(chargeAtClick, sprintAtClick, critAtClick);
		} else {
			mySinceSwing = Math.min(mySinceSwing + 1, 1000);
		}
		duel.me.sinceSwing = mySinceSwing;
		boolean began = them.swinging && (!theyWereSwinging || them.swingTime < theirLastSwingTime);
		theyWereSwinging = them.swinging;
		theirLastSwingTime = them.swingTime;
		if (began) {
			theirSwingCharge = Math.min(1f, (theirSinceSwing + 1) / Body.SWORD_TICKS);
		}
		theirSinceSwing = began ? 0 : Math.min(theirSinceSwing + 1, 1000);
		duel.them.sinceSwing = theirSinceSwing;
		duel.them.charge = Math.min(1f, theirSinceSwing / Body.SWORD_TICKS);
		duel.measure();

		events.clear();
		events.iSwung = swungLast;
		events.theySwung = began;
		int myHurt = me.hurtTime;
		int theirHurt = them.hurtTime;
		if (started) {
			if (theirHurt > lastTheirHurt) {
				events.iHit = mySwingKind;
			}
			if (myHurt > lastMyHurt) {
				// Their hit, by what its game shows of them: sprinting, or coming down with the sword back.
				// A sprint hit ends their sprint on the server the tick it lands, so the game shows it with them walking: by
				// their sprint now alone, a deflection (T4) was never a sprint hit, and the reverse deflection (T5) and the late
				// jump + hold S (C3), which answer a sprint hit, had about no moments all day (24 Oct, T5: 17).
				boolean sprinted = them.isSprinting() || themWereSprinting;
				boolean crit = !duel.them.onGround && duel.them.vy < 0 && !sprinted;
				// The charge of the swing that hit, not the sword now: the swing is seen on the tick of its hit or the one before,
				// and the sword now is back at nothing, so every hit of theirs was a weak one (24 Oct 15:40: 0 sprint hits of 191).
				float charge = theirSinceSwing <= 3 ? theirSwingCharge : duel.them.charge;
				events.theyHit = Events.kindOf(charge, sprinted, crit);
				theirHits[events.theyHit]++;
				theirHits[0] += events.theyHit == Events.SPRINT && !them.isSprinting() ? 1 : 0;
			}
		}
		lastMyHurt = myHurt;
		lastTheirHurt = theirHurt;
		started = true;
		habits.observe(duel, events);
		mirror.mirrorOf(duel);
		mirrorEvents.mirrorOf(events);
		own.observe(mirror, mirrorEvents);
		meWasOnGround = me.onGround();
		themWasOnGround = them.onGround();
		themWereSprinting = them.isSprinting();
	}

	/** "41 sprint (37 by the tick before), 12 plain, 3 crit", and starts the count again. */
	String takeTheirHits() {
		String line = String.format(java.util.Locale.ROOT, "%d sprint (%d by the tick before), %d plain, %d crit",
				theirHits[Events.SPRINT], theirHits[0], theirHits[Events.PLAIN], theirHits[Events.CRIT]);
		java.util.Arrays.fill(theirHits, 0);
		return line;
	}

	/** The memory, as the network reads it. */
	float[] memory() {
		habits.write(mem, 0);
		return mem;
	}

	private static void fill(Body b, Player p, boolean wasOnGround) {
		b.x = p.getX();
		b.y = p.getY();
		b.z = p.getZ();
		double lastVy = b.vy;
		b.vx = p.getX() - p.xo;
		b.vy = p.getY() - p.yo;
		b.vz = p.getZ() - p.zo;
		b.yaw = p.getYRot();
		b.pitch = p.getXRot();
		b.onGround = p.onGround();
		b.sprinting = p.isSprinting();
		b.charge = p.getAttackStrengthScale(0f);
		b.sinceHurt = p.hurtTime > 0 ? 10 - p.hurtTime : 1000;
		// Another player is drawn gliding between the positions its game is sent, so its take-off shows smaller than its own.
		boolean tookOff = Body.tookOff(lastVy, b.vy) && p.hurtTime == 0;
		b.sinceJump = tookOff ? 0 : Math.min(b.sinceJump + 1, 1000);
		b.airTicks = p.onGround() ? 0 : b.airTicks + 1;
		b.health = p.getHealth();
	}
}
