package dev.swordai.core.pvp;

import java.util.Arrays;
import java.util.Random;

/**
 * How one player fights, drawn for a fight: how likely it is to use each technique when its moment
 * comes, how quick and how exact it is, how far it sees behind (its connection), and how it does the
 * ordinary fight of part 0 (W-tap, S-tap, its spacing, its strafing). A specialist bot is a style with
 * one technique high; the "real player" is a style drawn wide that also adapts as the fight goes
 * ({@link #adapts}).
 */
public final class Style {
	/** A name for the logs: the specialist's, or "mixer". */
	public String name = "mixer";
	/** Which of the specialists of the technique school it is (see {@link Specialists}), or -1 for none. */
	public int bot = -1;
	/** The chance of each technique when its moment comes, by {@link Technique#ordinal()}. */
	public final float[] p = new float[Technique.COUNT];

	/** Ticks between seeing something and acting on it (a person: 3 to 6). */
	public int reaction = 4;
	/** Share of timed presses (a jump reset, a jump on landing) that land on the right tick; the rest are a tick or two off. */
	public float timing = 0.7f;
	/** How late it sees the other fighter, ticks (its connection one way). */
	public int lag = 1;
	/** How late a hit knocks it back, ticks (its own connection; the server's knockback reaches it late). */
	public int ping = 1;
	/** Aim: noise in degrees, and how fast it can turn, degrees a tick. */
	public float aimNoise = 3f;
	public float turnRate = 35f;
	/** The least charge it swings at, 0.9 to 1. */
	public float swingCharge = 0.92f;

	// ---- the ordinary fight (part 0) ----------------------------------------------------------
	/** Eye to hitbox it likes to keep while its sword comes back (S-tap spacing). */
	public float spacing = 3.1f;
	/** Ticks W is let go of after each of its hits (W-tap); 0 for none. */
	public int wtap = 2;
	/** Ticks of S after its hits instead (an S-tap); 0 for none. */
	public int stap = 0;
	/** How much it strafes near the other fighter, 0 to 1. */
	public float strafe = 0.3f;
	/** Share of its openings made as a jumped crit rather than a sprint hit (part 0: a crit is a hit made falling). */
	public float critOpen = 0f;
	/** From how far it jumps in for such a crit: eye to hitbox at take-off. */
	public float critFrom = 2.2f;
	/** It swings this far beyond its reach: 0 for none; the swinger that misses ({@link Specialists}) has some. */
	public float swingEarly = 0f;
	/** In a combo of its own it hits the moment the other fighter lands (a rhythm), rather than the moment its sword is back. */
	public boolean rhythm = false;
	/** It never jumps except for its techniques (the straight sprinter). */
	public boolean noJumps = false;
	/**
	 * It reads the other fighter's habits as the fight goes and turns to their counters (part 7 of the
	 * techniques file), as a person does; see {@link PvpPlayer}.
	 */
	public boolean adapts = false;
	/**
	 * Its techniques only where the file says they work: the double crit and its bait (T1, T1a) against one who
	 * punish-crits ("only works if the opponent tries to punish-crit you", T1), the hit as they enter reach (T9c) against
	 * one who comes straight in (part 7). The school's teacher: otherwise the first technique checked at a moment takes it every time, and the
	 * others there are never shown (24 Oct: T1a took every approach from T7).
	 */
	public boolean onlyWhenItWorks = false;
	/**
	 * Jump resets only while being comboed (their second hit in a row and on), never on a first hit: the user, 24 Oct,
	 * so that people can still beat it "in a normal way" (and on 13 Oct: jump resets used too much are "really not fair").
	 * So no T9b (against a deflection, after its own attack) nor T9c (against a three-block hit), and T9 only in a combo.
	 */
	public boolean resetsInCombosOnly = false;
	/**
	 * A jump-in is answered by the punish crit (B1, "let it land, punish crit"): the swing held and the sprint let go while
	 * they come in, their hit taken, the crit on the way down; not the hit on them while they rise (T8). The teacher's
	 * (26 Oct 16:10). The user: a rising or falling jumper "will still land a crit because knock back wont get them
	 * instantly out of reach so it must be a punish crit"; in the jump-in scenes it lost, it had swung 3.5 ticks before their
	 * crit and its sword was 0.26 back; in those it won, 15 ticks and 0.69.
	 */
	public boolean punishJumpIns = false;
	/**
	 * Hit selecting (part 0, and the deflection T4): the share of the times it has the other in reach with
	 * the other's sword back that it waits for the other's swing, instead of swinging first.
	 */
	public float hitSelect = 0f;
	/**
	 * What it takes the other to do before it has seen anything, by {@link Habits} index; below zero for
	 * no belief. A counter specialist expects the thing it counters (the crit-breaker expects double crits).
	 */
	public final float[] believe = new float[Habits.COUNT];

	{
		java.util.Arrays.fill(believe, -1f);
	}

	/** Kept out of training: a style only the tests fight against. */
	public boolean heldOut = false;

	public float p(Technique t) {
		return p[t.ordinal()];
	}

	public Style set(Technique t, float chance) {
		p[t.ordinal()] = chance;
		return this;
	}

	public Style copy() {
		Style s = new Style();
		s.name = name;
		s.bot = bot;
		System.arraycopy(p, 0, s.p, 0, p.length);
		s.reaction = reaction;
		s.timing = timing;
		s.lag = lag;
		s.ping = ping;
		s.aimNoise = aimNoise;
		s.turnRate = turnRate;
		s.swingCharge = swingCharge;
		s.spacing = spacing;
		s.wtap = wtap;
		s.stap = stap;
		s.strafe = strafe;
		s.critOpen = critOpen;
		s.critFrom = critFrom;
		s.swingEarly = swingEarly;
		s.rhythm = rhythm;
		s.noJumps = noJumps;
		s.adapts = adapts;
		s.onlyWhenItWorks = onlyWhenItWorks;
		s.resetsInCombosOnly = resetsInCombosOnly;
		s.punishJumpIns = punishJumpIns;
		s.hitSelect = hitSelect;
		System.arraycopy(believe, 0, s.believe, 0, believe.length);
		s.heldOut = heldOut;
		return s;
	}

	/**
	 * A person's body: reaction, timing, connection and aim drawn wide, as the plan asks (reaction 3 to
	 * 6 ticks, ping drawn), and the ordinary fight's habits too. The techniques are left as they are.
	 */
	public Style drawHuman(Random rng) {
		reaction = 3 + rng.nextInt(4);
		timing = 0.5f + 0.45f * rng.nextFloat();
		lag = (int) Math.round(3 * Math.pow(rng.nextDouble(), 1.5));
		ping = (int) Math.round(4 * Math.pow(rng.nextDouble(), 1.5));
		aimNoise = 1.5f + 4f * rng.nextFloat();
		turnRate = 25f + 25f * rng.nextFloat();
		swingCharge = 0.9f + 0.1f * rng.nextFloat();
		spacing = 2.8f + 0.6f * rng.nextFloat();
		wtap = rng.nextInt(4);
		stap = rng.nextFloat() < 0.3f ? 1 + rng.nextInt(3) : 0;
		strafe = 0.5f * rng.nextFloat();
		return this;
	}

	/** Every technique's chance, for the logs: only those above zero. */
	public String describe() {
		StringBuilder b = new StringBuilder(name);
		b.append(" [");
		boolean first = true;
		for (Technique t : Technique.ALL) {
			if (t != Technique.BASE && p[t.ordinal()] > 0f) {
				b.append(first ? "" : " ").append(t.id).append(String.format(java.util.Locale.ROOT, "=%.2f", p[t.ordinal()]));
				first = false;
			}
		}
		b.append(String.format(java.util.Locale.ROOT, "] react %d timing %.2f lag %d ping %d", reaction, timing, lag, ping));
		return b.toString();
	}

	@Override
	public String toString() {
		return describe() + " " + Arrays.toString(p);
	}
}
