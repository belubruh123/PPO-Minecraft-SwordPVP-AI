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
	/**
	 * Run 105 (agent 46): after their crit, against one expected to double crit, the share of times C1 ("when you expect a double crit,
	 * don't go for the punish crit. Sprint-hit them") goes before the punish crit (B1). Before, B1 went first whenever the crit could
	 * be punished, so C1 came only when it could not (8 moments all morning, 0 since). 0 for every bot; the school's teacher.
	 */
	public float c1BeforeB1 = 0f;
	/**
	 * Run 105 (agent 46): of its double crits (T1), the share thrown around them (T1b); below 0 by its own chance of T1b. The teacher
	 * has every technique at 1, so with T1b in its family every double crit was a T1b and plain T1 came only after a bait (T1a): 7
	 * moments in a 40-moment window.
	 */
	public float t1bShare = -1f;
	/**
	 * Run 107 (agent 47; see PvpPlayer#punishesForDouble): for the double crit after its own crit (T1), a punish-crit chance is counted
	 * only where a punish crit could come, they coming down (falling) within reach after the lift of its hit, or trying one. False for
	 * every bot (their behaviour and the benchmark stay as they were); the school's teacher (Teacher#CRIT_READS).
	 */
	public boolean punishChanceInReach = false;
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
	 * Run 92 (13 Nov ~24:00): no hops (T11b) or uppercut (T10) after its own sprint hit, nor the backing edge's T10/T11a in its own
	 * combo; the ordinary fight's gap while the sword comes back, then W and the sprint hit from the edge (PvpPlayer#TEACH_SPACE_AFTER_HIT;
	 * real rounds: after its own hit it took off within 5 ticks 41%, then was hit within 3 ticks 44%, after S 11%). The teacher's;
	 * the bots (the hopper, the lunger) keep theirs.
	 */
	public boolean spaceAfterHit = false;
	/**
	 * Every hit that throws it up, of any kind, is answered by the punish crit (B1) when one can come, before the jump reset
	 * or any other answer: the drill of the user's method (27 Oct), where the decision model's output is forced to the punish
	 * crit on the punish crit's own cases so that the network learns to land it whenever it is told to. In the full fights the
	 * jump reset still comes first and the decision model learns when to punish instead.
	 */
	public boolean punishFirst = false;
	/**
	 * Run 122 (agent 56; the user, 16 Nov 22:25: "all the time it pcrit the opponent instead double crit or sprint hit back ... you need
	 * massive consistency"; the document's B1: "Their hit knocks you up; you crit them on the way down"): every hit of theirs that throws
	 * it up with a punish crit possible (its sword PvpPlayer#PUNISH_CHARGE back, them within PvpPlayer#PUNISH_ALWAYS_REACH) is answered
	 * by the punish crit (B1), after the timed jump the hit would have had (T9a in their rhythm, T9b against a deflector, T9 on a sprint
	 * hit) and before C1, T5, the plain jump reset and C3. Before, on a sprint hit's lift the jump reset (T9) took the moment with no
	 * punish crit after it, T5 and C1 (half the times, run 105) went first where they applied, so the decider learned B1 only after
	 * plain hits and crits. Not punishable: as before. false for every bot (their behaviour and the benchmark stay as they were); the
	 * school's teacher (Teacher#PUNISH_ALWAYS).
	 */
	public boolean punishAlways = false;
	/**
	 * Run 122: no crit chain right after its own hit (T1, T1b, T1c, T2, C6; SchoolStages#CRIT_CHAINS_TAUGHT): their chances stay 0 when it
	 * adapts, and the bait (T1a) is not followed by the double crit. false for every bot; the school's teacher.
	 */
	public boolean noCritChains = false;
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
	 * Run 93 (14 Nov ~02:00): ticks of S (walking back, no sprint, no swing) after every hit it takes, then the ordinary fight's
	 * spacing and its swing again; 0 for every bot but the comboable one ({@link Specialists#comboable}). Agent 32's finding: no bot lets
	 * itself be comboed (it stays in reach and hits back), so a held sprint-hit combo of 3+ is almost never possible in training.
	 */
	public int backAfterHit = 0;
	/**
	 * Run 93b (agent 37, 14 Nov): ticks after every hit it takes (from the tick it feels it) in which it fights as if its sword were
	 * not back: no swing, and the ordinary fight's spacing for a sword coming back (part 0's S-tap: S only inside spacing - 0.3,
	 * standing to spacing + 0.2, walking in from farther), then its swing again; 0 for every bot but the comboable one. The stun of
	 * the document's T3 ("knocks the opponent far enough away that they can't hit you back"), without the C3 fly-out that
	 * {@link #backAfterHit} was: 12-20 ticks of S after each hit carried it about 4.5 blocks out in its knockback (S held in the air)
	 * and kept it walking back, so the next hit came only as it walked back in with its sword ready, 15-25 ticks later (past the
	 * school's COMBO_GAP of 20) and head-on, where the side that sees later loses the first hit.
	 */
	public int stunAfterHit = 0;
	/**
	 * Run 97 (agent 39; the user, 14 Nov 14:50: "upper cut or any jump combo style always had it's combo be broken by opponent so make
	 * sure it is stable to hold combo like that"): the combo breaker ({@link Specialists#comboBreaker}), a stunned comboable bot that
	 * gets out of a badly held combo the document's ways. After each hit it takes, rolled once per hit with these chances, and only
	 * once {@link #breakStun} ticks have gone by (a short stun first):
	 * <ul>
	 * <li>{@link #breakRising}: T8, "if the opponent jumps at you and you hit them as they are rising from their jump, that is usually a
	 * free stun": the swing at one rising into its reach from a jump of its own (a hop, uppercut or lunge taken off too near);</li>
	 * <li>{@link #breakRhythm}: T9a, "most players combo to a set rhythm, so they are not hard to jump-reset out of": a hit that comes as
	 * many ticks after the last as that one did after the one before is jump-reset, and the crit comes on the way down (T9: "it gets
	 * crits");</li>
	 * <li>{@link #breakBackOut}: C3, "jump and hold S, so that you fly out of the trade" (the lunge's moment, T11a);</li>
	 * <li>{@link #breakEmpty}: the swing back at one in its reach with its sword far from back (not going away), or too close (part 0's
	 * S-tap, "stay just out of their reach"; T4, "too close, and they hit you through").</li>
	 * </ul>
	 * Otherwise it is the stunned comboable bot ({@link #stunAfterHit}). All 0 for every other bot and the teacher.
	 */
	public float breakRising = 0f;
	public float breakRhythm = 0f;
	public float breakBackOut = 0f;
	public float breakEmpty = 0f;
	public int breakStun = 0;
	/**
	 * Run 100 (agent 42; the user, 15 Nov 08:35: "those rounds are advance crit spammers ... they jump and crit after your punish crit
	 * and then also does crit combo ... make a replica version like that"): the crit spammer of the user's 7 lost real rounds
	 * ({@link Specialists#critSpammer}). With it on: (1) a crit jump of its own (T1, T1a, T1c, the opening crit) goes on through a crit it
	 * takes in the air and comes down critting (K1: an air hit is little knockback and pushes it the way it already goes), so a punish
	 * crit into its double crit (T1) is answered by its second crit, as in 15 of the 17 lost-round punish crits made after their jump
	 * again; (2) its double crit (T1, T1b) jumps on landing like T1c ({@link PvpPlayer#T1C_AT_LANDING}), a short human wait of 0 to
	 * {@link PvpPlayer#SPAM_WAIT_MOST} ground ticks first; (3) after its second crit lands it may jump and crit again (T1c's chance): the
	 * crit combo. false for every other bot and the teacher.
	 */
	public boolean critSpam = false;
	/** Run 100: each opening crit's take-off distance drawn uniform within critFrom +- this (0: always critFrom). */
	public float critFromSpread = 0f;
	/** Run 100: of its answers to a crit that lifted it, the share made as a punish crit back (below 0: PvpPlayer's ANSWER_BY_CRIT). */
	public float answerByCrit = -1f;
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

	/**
	 * Agent 49 ({@link Human}): a person's limits on, drawn by {@link Human#humanize}: the distance misjudged per window (distBias +-
	 * distSd blocks, plus = taken for farther), early clicks (this share of its windows, at a charge of 0.7-0.9) and late ones (ticks
	 * after the sword is back, exponential with this mean), a reaction drawn per reaction, and with humanAdapts the adaptation (the
	 * counters of what the other repeats, part 7; and less of its own technique that keeps failing). All off for every style but a
	 * humanized bot's.
	 */
	public boolean human = false;
	public float distBias = 0f;
	public float distSd = 0f;
	public float earlyClick = 0f;
	public float lateClick = 0f;
	public boolean humanAdapts = false;

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
		s.c1BeforeB1 = c1BeforeB1;
		s.t1bShare = t1bShare;
		s.punishChanceInReach = punishChanceInReach;
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
		s.spaceAfterHit = spaceAfterHit;
		s.punishFirst = punishFirst;
		s.punishAlways = punishAlways;
		s.noCritChains = noCritChains;
		s.answerCrit = answerCrit;
		s.closeIn = closeIn;
		s.flyOut = flyOut;
		s.hitSelect = hitSelect;
		s.backAfterHit = backAfterHit;
		s.stunAfterHit = stunAfterHit;
		s.breakRising = breakRising;
		s.breakRhythm = breakRhythm;
		s.breakBackOut = breakBackOut;
		s.breakEmpty = breakEmpty;
		s.breakStun = breakStun;
		s.critSpam = critSpam;
		s.critFromSpread = critFromSpread;
		s.answerByCrit = answerByCrit;
		System.arraycopy(believe, 0, s.believe, 0, believe.length);
		s.heldOut = heldOut;
		s.human = human;
		s.distBias = distBias;
		s.distSd = distSd;
		s.earlyClick = earlyClick;
		s.lateClick = lateClick;
		s.humanAdapts = humanAdapts;
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
		if (human) {
			b.append(String.format(java.util.Locale.ROOT, " human dist %+.2f+-%.2f early %.2f late %.1f%s", distBias, distSd, earlyClick, lateClick,
					humanAdapts ? " adapts" : ""));
		}
		return b.toString();
	}

	@Override
	public String toString() {
		return describe() + " " + Arrays.toString(p);
	}
}
