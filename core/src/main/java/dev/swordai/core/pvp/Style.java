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
	/**
	 * Human aim (7 Nov, the user: "make it know how opponent get use to aim you strafe a side and then change your direction ...
	 * your bot has to be like human"): it gets used to the way the agent moves across its view (aimAdapt of the difference a tick:
	 * 0.15 = slowly, 0.4 = fast) and aims aimLead ticks ahead along it, as a person leads a strafing target. Steady strafing is
	 * then tracked; a sudden change of direction is aimed the old way for a few ticks and can be missed. Before, the bots turned
	 * straight at where they saw it (up to 35 degrees a tick), so no strafe change ever made them miss.
	 */
	public float aimAdapt = 0.3f;
	public float aimLead = 2f;
	/**
	 * A person's timing of the jump reset (bot audit, 7 Nov): exact at the usual rate only in a combo's set rhythm (the document's
	 * T9a), else mostly late. The opponent bots have it (Specialists#draw); the teacher shows the technique exactly.
	 */
	public boolean humanTiming;
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
	/**
	 * Run 89 (13 Nov): after its punish crit, the share of times it makes them miss (T6: S as they swing, then the sprint hit) instead
	 * of the jump and crit again (T1c). 0 for every bot (their behaviour and the benchmark stay as they were); the school's teacher.
	 */
	public float t6AfterPunish = 0f;
	/** From how far it jumps in for such a crit: eye to hitbox at take-off. */
	public float critFrom = 2.2f;
	/**
	 * Its opening jump-in crit lands 2.5 to 3 blocks from them, so that it is already falling when it comes into their reach and
	 * is not knocked away (the user, 2 Nov): taken off from {@link PvpPlayer#jumpInFrom}, whenever they are that far, instead of
	 * from {@link #critFrom}.
	 */
	public boolean jumpInLands;
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
	 * Run 70 (Fable): in THEIR combo, their jump-in from beyond reach is answered with its own jump along with it and the crit on
	 * the way down (K3, PvpPlayer#jumpAlongMoment) instead of the wait for the punish crit. The teacher's; the bots keep theirs.
	 */
	public boolean jumpAlong = false;
	/** Run 79: T1c's jump on its first ground tick after the punish crit (PvpPlayer#T1C_AT_LANDING). The teacher's; the bots keep theirs. */
	public boolean t1cAtLanding = false;
	/** Run 83: hops (T11b) and the uppercut (T10) after its own sprint hit only once that hit stunned (PvpPlayer#TEACH_STUN_GATE). The teacher's; the bots keep theirs. */
	public boolean hopsAfterStun = false;
	/**
	 * Every hit that throws it up, of any kind, is answered by the punish crit (B1) when one can come, before the jump reset
	 * or any other answer: the drill of the user's method (27 Oct), where the decision model's output is forced to the punish
	 * crit on the punish crit's own cases so that the network learns to land it whenever it is told to. In the full fights the
	 * jump reset still comes first and the decision model learns when to punish instead.
	 */
	public boolean punishFirst = false;
	/** Run 59: the chance it swings back at once (a trade) when their crit lands on it, sword ready and them in reach. */
	public float answerCrit = 0f;
	/**
	 * Run 60: how much closer than its reach it comes before it swings (0 = the moment it is in reach). Real players hit from
	 * closer than the bots did (agent 2, 10 Nov: the distance it saw at their hits, real median 1.87 against the bots' 2.7-2.9).
	 */
	public float closeIn = 0f;
	/** Run 60: the chance it flies out of the trade (C3: jump and hold S) after a sprint hit it takes, as real players did. */
	public float flyOut = 0f;
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
		s.aimAdapt = aimAdapt;
		s.aimLead = aimLead;
		s.humanTiming = humanTiming;
		s.swingCharge = swingCharge;
		s.spacing = spacing;
		s.wtap = wtap;
		s.stap = stap;
		s.strafe = strafe;
		s.critOpen = critOpen;
		s.critFrom = critFrom;
		s.jumpInLands = jumpInLands;
		s.swingEarly = swingEarly;
		s.rhythm = rhythm;
		s.noJumps = noJumps;
		s.adapts = adapts;
		s.onlyWhenItWorks = onlyWhenItWorks;
		s.resetsInCombosOnly = resetsInCombosOnly;
		s.punishJumpIns = punishJumpIns;
		s.jumpAlong = jumpAlong;
		s.t1cAtLanding = t1cAtLanding;
		s.hopsAfterStun = hopsAfterStun;
		s.punishFirst = punishFirst;
		s.answerCrit = answerCrit;
		s.closeIn = closeIn;
		s.flyOut = flyOut;
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
		// Run 32 (2 Nov): two ticks more than the 0-3 of before, for the client's smoothing of a moving player that a person sees
		// on top of both connections. In the user's duels the first hit on the AI's jump-ins from 4-6 blocks came 7-10 ticks
		// after the take-off and it hit back in 95% (tools/jumpins.py); the school's bots met them rising, and in the checks the
		// school network was hit rising five times as often as the installed model. The user: "your bot timed too perfect".
		lag = 2 + (int) Math.round(3 * Math.pow(rng.nextDouble(), 1.5));
		ping = (int) Math.round(4 * Math.pow(rng.nextDouble(), 1.5));
		aimNoise = 1.5f + 4f * rng.nextFloat();
		turnRate = 25f + 25f * rng.nextFloat();
		aimAdapt = 0.15f + 0.25f * rng.nextFloat();
		aimLead = 1f + 2f * rng.nextFloat();
		swingCharge = 0.9f + 0.1f * rng.nextFloat();
		spacing = 2.8f + 0.6f * rng.nextFloat();
		wtap = rng.nextInt(4);
		stap = rng.nextFloat() < 0.3f ? 1 + rng.nextInt(3) : 0;
		strafe = 0.5f * rng.nextFloat();
		// Run 59 (10 Nov 08:30): real players answer its punish crit (the 3-hour rule's research, agent 2, 332 real punish crits: they
		// hit back within 4 ticks in 40%, within 12 in 63%; it hit first in 26%); the bots did in 24-32% (it hit first in 50-59%).
		// Drawn last, so the draws above are as before.
		answerCrit = 0.5f + 0.3f * rng.nextFloat(); // run 59b: 0.3-0.6 before
		// Run 60 (10 Nov): agent 2's other two gaps between the bots and the user's 200 real rounds. (a) The distance it saw at
		// their hits: real median 1.87 (90th percentile 2.87), the bots 2.7-2.9 (about 4.0); a quarter of the bots' hits came
		// from beyond its own longest, and it was hit with its sword ready in 56-68% of their hits against 28% real. (b) Eight
		// ticks after its clean sprint hit, the opponent was 2+ blocks farther in 36% real, 8-12% for the bots (they walked back
		// in). Drawn last again.
		// Run 59b: halved (0.3-1.0 before). The bench after run 60 had their whiffs at 10% (real 29%, 51% before run 60).
		closeIn = 0.1f + 0.4f * rng.nextFloat();
		// Run 59b: off again (agent 4: the arena showed its knockback 3-4 ticks late, so the fly-out fired late and added a tail real
		// players do not have). The draw is kept so the other draws stay as they were.
		flyOut = 0f * rng.nextFloat();
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
