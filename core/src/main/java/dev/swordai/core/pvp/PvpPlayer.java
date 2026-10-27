package dev.swordai.core.pvp;

import java.util.Random;

/**
 * A sword fighter that plays the techniques of {@code docs/PVP-TECHNIQUES.md}, each the way that file
 * spells it out, with the chances, timing and reactions of its {@link Style}. One class serves three
 * uses:
 * <ul>
 * <li>the <b>specialist bots</b> of the technique school: a style with one technique likely and the
 * ordinary fight otherwise;</li>
 * <li>the <b>real player</b> bot: a style drawn wide that reads the other fighter's habits and turns to
 * their counters (part 7 of the file), {@link Style#adapts};</li>
 * <li>the <b>teachers</b>: the same machines run for the learner's own body, whose keys pull on the
 * network's choices while it learns a technique (never pressing anything for it).</li>
 * </ul>
 *
 * <p>Every technique starts on an edge (a hit, a swing, a take-off, the other coming into reach) and
 * is rolled for once there, with the style's chance; then it runs as a short plan, phase by phase,
 * until it is done or the fight has moved on. Between plans the fighter does the ordinary fight of
 * part 0: sprint at the other, sprint-hit with the sword back, W-tap after its hits, S-tap to keep its
 * spacing while the sword comes back.
 *
 * <p>Deterministic for a seed and the same ticks, so a fight can be set up again exactly.
 */
public final class PvpPlayer {
	/** Plans. Each carries the technique it is labelled with. */
	enum Plan {
		NONE(Technique.BASE),
		/** Part 0: a crit made from a jump of its own, opening an exchange (the jump-in critter's whole game). */
		OPEN_CRIT(Technique.BASE),
		B1(Technique.B1),
		/** B1 against a jump-in ({@link Style#punishJumpIns}): the swing held and the sprint let go until their hit lifts it. */
		B1_WAIT(Technique.B1),
		/**
		 * The punish crit drill ({@link Style#punishFirst}): from the start, the swing held and the sprint let go while they come
		 * in, so that the sword is back when their hit lifts it; then B1. In the drill scenes a quarter of its lifts came with the
		 * sword under half back, swung within five ticks before their hit, and half its answers were swings on the way up.
		 */
		B1_HOLD(Technique.B1),
		T1(Technique.T1), T1A(Technique.T1A), T1B(Technique.T1B), T1C(Technique.T1C), T2(Technique.T2),
		T4(Technique.T4), T5(Technique.T5), T6(Technique.T6), T7(Technique.T7), T8(Technique.T8), T9C(Technique.T9C),
		T10(Technique.T10), T11A(Technique.T11A), T11B(Technique.T11B), T11C(Technique.T11C),
		C1(Technique.C1), C2(Technique.C2), C3(Technique.C3), C4(Technique.C4), C6(Technique.C6), C7(Technique.C7), K3(Technique.K3);

		final Technique label;

		Plan(Technique label) {
			this.label = label;
		}
	}

	/** What a plan's step says: go on next tick; this tick's keys and then done; or nothing to do (the ordinary fight takes the tick). */
	private static final int RUN = 0;
	private static final int DONE = 1;
	private static final int ABORT = 2;

	/** In a combo of its own while its last hit is at most this old. */
	static final int COMBO_TICKS = 25;
	/** Ticks a plan may last before it is given up. */
	static final int PLAN_MOST = 80;
	/** A crit jump from this far (eye to hitbox) or more is a running jump; the bait crit (T1a) is one from here, landing close. */
	static final float RUNNING_JUMP_FROM = 2.8f;

	private final Random rng = new Random();
	private Style style = new Style();
	private final float[] pNow = new float[Technique.COUNT];
	private final Habits habits = new Habits();

	private int tick;
	private int sinceMyHit = 1000;
	private int sinceTheirHit = 1000;
	private int sinceMySwing = 1000;
	private int sinceTheirSwing = 1000;
	private int sinceLanded = 1000;
	/** The tick their hit threw it up from the ground; its crit before it lands again is a punish crit (B1). */
	private int thrownUpAt = -1000;
	private boolean wasOnGround = true;
	private boolean theyWereOnGround = true;
	private int sinceTheyTookOff = 1000;
	private int backingTicks;
	private int inReachTicks;
	/** Edge flags: each approach, each ready window, rolled for once. */
	private boolean approachRolled;
	private boolean outRolled;
	private boolean backRolled;
	private boolean straightRolled;
	private boolean delayRolled;
	/** This fight it keeps to the golden rule (K2): no knockback hit on someone falling. */
	private boolean k2;
	/** What it was told the other will do (a teacher knows); below zero: go by what has been seen. */
	private float tellDouble = -1f;
	private float tellReset = -1f;
	private float tellDeflect = -1f;

	private Plan plan = Plan.NONE;
	private int planT;
	private int phase;
	private int planDir;
	private boolean planAlt;
	private Plan pending = Plan.NONE;
	private int pendingAt;
	/** Timed jump waiting for its tick (a jump reset): the tick, or -1. */
	private int jumpAt = -1;
	private Technique jumpFor = Technique.BASE;

	// The ordinary fight.
	private int wtapLeft;
	private int stapLeft;
	private int strafeDir = 1;
	private int strafeLeft;
	private boolean strafing;
	/** Waiting for their swing to hit right after it (hit selecting): ticks left, or 0; and whether this window was rolled for. */
	private int selectLeft;
	private boolean selectRolled;

	public void start(Style s, long seed) {
		style = s;
		rng.setSeed(seed);
		System.arraycopy(s.p, 0, pNow, 0, pNow.length);
		habits.reset();
		tick = 0;
		sinceMyHit = 1000;
		sinceTheirHit = 1000;
		sinceMySwing = 1000;
		sinceTheirSwing = 1000;
		sinceLanded = 1000;
		thrownUpAt = -1000;
		wasOnGround = true;
		theyWereOnGround = true;
		punishWatching = false;
		punishChances = 0;
		punishTries = 0;
		doubleWatching = false;
		doubleChances = 0;
		doubleTries = 0;
		theirCritTick = -1000;
		donePlan = Plan.NONE;
		doneAt = -1000;
		sinceTheyTookOff = 1000;
		backingTicks = 0;
		inReachTicks = 0;
		approachRolled = false;
		outRolled = false;
		backRolled = false;
		straightRolled = false;
		delayRolled = false;
		k2 = rng.nextFloat() < s.p(Technique.K2);
		tellDouble = -1f;
		tellReset = -1f;
		tellDeflect = -1f;
		plan = Plan.NONE;
		planT = 0;
		phase = 0;
		pending = Plan.NONE;
		jumpAt = -1;
		jumpFor = Technique.BASE;
		wtapLeft = 0;
		stapLeft = 0;
		strafeLeft = 0;
		strafing = false;
		selectLeft = 0;
		selectRolled = false;
	}

	public Style style() {
		return style;
	}

	public Habits habits() {
		return habits;
	}

	/** The technique in charge now. */
	public Technique technique() {
		return plan.label;
	}

	/** The chance it gives a technique now (the style's, or more where it has adapted). */
	public float chance(Technique t) {
		return pNow[t.ordinal()];
	}

	/**
	 * For a teacher: what it knows the other fighter will do, which a person can only guess. Each is a
	 * chance, 0 to 1, or below zero to go by what has been seen ({@link Habits}).
	 */
	public void tell(float doubleCrit, float jumpReset, float deflect) {
		tellDouble = doubleCrit;
		tellReset = jumpReset;
		tellDeflect = deflect;
	}

	private float expect(int habit, float told) {
		return told >= 0f ? told : Math.max(style.believe[habit], habits.rate(habit) * habits.evidence(habit));
	}

	/**
	 * A punish crit is the crit "on the way down" after their hit (B1): for one that goes by the file, only when it can come,
	 * the sword nearly back already and them close, not thrown out of reach by a sprint hit. 24 Oct, in the game: of the
	 * teacher's falls after a hit in reach, 15% had the sword back; waiting for punish crits that could not come, the crit
	 * family cost it 20 damage a fight against the installed model (half a gate, charge 0.5, changed nothing).
	 * A sprint hit no longer rules it out (24 Oct 16:10, in the game, once their sprint hits were read right): of the lifts
	 * after their sprint hit about a third came down in reach, most with the sword back, and all but one still sprinting, with
	 * no punish crit taught there; after their plain hits, where it was taught, one in ten came down in reach.
	 */
	private boolean punishable(Duel d, Events e) {
		return !style.onlyWhenItWorks || d.me.charge >= PUNISH_CHARGE && d.reach <= 2.6;
	}

	/**
	 * A jump for a crit waits until the sword is a third back, for one that goes by the file: the sword takes about as long to
	 * come back after a hit as a jump takes to come down, so a jump the very tick after the hit comes down before it (24 Oct,
	 * in the game: of the teacher's in-reach falls in the crit family, 16% had the sword back, and no weak swings are made).
	 */
	private boolean waitsForTheSword(Body me) {
		return style.onlyWhenItWorks && me.charge < JUMP_FOR_CRIT_CHARGE;
	}

	/**
	 * 0.3 until 24 Oct 20:15: a fall starts about six ticks after the jump, and from a third back the sword was about four
	 * fifths back there, so of the crit family's falls in reach about half had it charged (34 in reach, 19 charged, 7 crits
	 * in an update). From 0.4 it is back as the fall begins.
	 */
	static final float JUMP_FOR_CRIT_CHARGE = 0.4f;
	/**
	 * The sword half back when thrown up is back by the way down (a fall is about six ticks, the sword about 0.08 a tick).
	 * At 0.8 a punish crit came about once in four minutes of fighting, too seldom to be learned (24 Oct).
	 */
	static final float PUNISH_CHARGE = 0.5f;
	/** The punish crit drill holds the swing for their hit (B1_HOLD) from this near, for at most this long. */
	static final double PUNISH_HOLD_REACH = 5.0;
	static final int PUNISH_HOLD_TICKS = 40;

	/** The double crit "only works if the opponent tries to punish-crit you" (T1): for one that goes by the file, only then. */
	private boolean punishes() {
		return !style.onlyWhenItWorks || expect(Habits.PUNISH_CRIT, -1f) > 0.3f || rate(punishTries, punishChances) > 0.3f;
	}

	/** Whether they go for double crits: seen landing them, or trying (a jump again soon after their crit). */
	private boolean doubleCrits() {
		return expect(Habits.DOUBLE_CRIT, tellDouble) > 0.3f || tellDouble < 0f && rate(doubleTries, doubleChances) > 0.3f;
	}

	/**
	 * Tries, as the file has it: the double crit "only works if the opponent tries to punish-crit you", and C1 is for "when
	 * you expect a double crit". Landed ones are rare for everyone (the sword is back on few falls), so judged by landed ones
	 * the opponent was never one that punish-crits: no double crit (T1) and no bait (T1a) in fifteen updates, nor C1 (24 Oct).
	 * A punish-crit try: lifted by my hit, they swing (or crit me) before they are down. A double-crit try: after their crit
	 * on me, they jump again within a jump and a half.
	 */
	private void tries(Duel d, Events e) {
		Body them = d.them;
		if (e.iHit != Events.NONE && theyWereOnGround) {
			punishWatching = true;
			punishChances++;
		} else if (punishWatching) {
			if (sinceMyHit <= 18 && (e.theySwung && !them.onGround || e.theyHit == Events.CRIT)) {
				punishTries++;
				punishWatching = false;
			} else if (sinceMyHit > 18 || them.onGround && sinceMyHit > 3 || e.theyHit != Events.NONE) {
				punishWatching = false;
			}
		}
		if (e.theyHit == Events.CRIT) {
			doubleWatching = true;
			doubleChances++;
			theirCritTick = tick;
		} else if (doubleWatching) {
			if (sinceTheyTookOff == 0 && tick - theirCritTick <= 24) {
				doubleTries++;
				doubleWatching = false;
			} else if (tick - theirCritTick > 24) {
				doubleWatching = false;
			}
		}
	}

	private static float rate(int tries, int chances) {
		return chances == 0 ? 0f : tries / (chances + 1f);
	}

	private boolean punishWatching;
	private int punishChances;
	private int punishTries;
	private boolean doubleWatching;
	private int doubleChances;
	private int doubleTries;
	private long theirCritTick = -1000;

	private boolean roll(Technique t) {
		float p = pNow[t.ordinal()];
		return p > 0f && rng.nextFloat() < p;
	}

	/**
	 * One tick: the fight as this fighter sees it (already as late as its connection makes it), and what
	 * happened since the last tick. Writes what it does now into {@code k}.
	 */
	public void tick(Duel d, Events e, Keys k) {
		k.clear();
		tick++;
		Body me = d.me;
		clocks(d, e);
		habits.observe(d, e);
		if (style.adapts && tick % 20 == 0) {
			adapt();
		}
		// What a hit calls for comes first, on the tick it is felt; a hit taken last, as it decides the next ticks.
		if (e.iHit != Events.NONE) {
			hitLanded(d, e);
		}
		if (e.theyHit != Events.NONE) {
			hitTaken(d, e);
		}
		if (plan == Plan.NONE && pending != Plan.NONE && tick >= pendingAt) {
			begin(pending);
			pending = Plan.NONE;
		}
		if (plan == Plan.NONE) {
			edges(d, e);
		}
		boolean done = false;
		if (plan != Plan.NONE) {
			planT++;
			k.technique = plan.label;
			int how = planT > PLAN_MOST ? ABORT : step(d, k);
			if (how == ABORT) {
				end();
				k.clear();
			} else if (how == DONE) {
				donePlan = plan;
				doneAt = tick;
				end();
				done = true;
			}
		}
		if (plan == Plan.NONE && !done) {
			ordinary(d, k);
		}
		// The golden rule (K2) for every knockback hit, a technique's too: "don't knockback-hit someone who is falling,
		// whether during a combo or while they are critting you." A falling one sticks to the floor (K1) and crits back.
		if (k.attack && k2 && fallingByTheHit(d.them) && (me.sprinting || k.sprint) && k.technique != Technique.K2) {
			k.attack = false;
			k.technique = Technique.K2;
			k.taught = Keys.KEY_ATTACK;
		}
		// A timed jump (a jump reset) waits for its tick whatever else is going on.
		if (jumpAt >= 0 && tick >= jumpAt) {
			if (me.onGround) {
				k.jump = true;
				k.forward = 1;
				k.sprint = true;
				k.technique = jumpFor;
				k.taught |= Keys.KEY_JUMP | Keys.KEY_MOVE | Keys.KEY_SPRINT;
			}
			jumpAt = -1;
		}
		wasOnGround = me.onGround;
		theyWereOnGround = d.them.onGround;
		theirLastVy = d.them.vy;
	}

	private double theirLastVy;
	/** The last plan done with its swing, and when: its hit lands a tick or more later (see hitLanded). */
	private Plan donePlan = Plan.NONE;
	private long doneAt = -1000;
	private static final int HIT_AFTER_SWING = 4;

	private void clocks(Duel d, Events e) {
		Body me = d.me;
		Body them = d.them;
		sinceMyHit = e.iHit != Events.NONE ? 0 : Math.min(sinceMyHit + 1, 1000);
		sinceTheirHit = e.theyHit != Events.NONE ? 0 : Math.min(sinceTheirHit + 1, 1000);
		sinceMySwing = e.iSwung ? 0 : Math.min(sinceMySwing + 1, 1000);
		sinceTheirSwing = e.theySwung ? 0 : Math.min(sinceTheirSwing + 1, 1000);
		sinceLanded = !wasOnGround && me.onGround ? 0 : Math.min(sinceLanded + 1, 1000);
		// Their take-off: from not going up to going up, not lifted by a hit of mine. Seen from a client another player glides
		// between the positions it is sent, so the take-off shows smaller than the jump and not on the tick its ground flag
		// changes: by the ground flag and 0.3 a tick, T8 never started in the game (24 Oct).
		if (Body.tookOff(theirLastVy, them.vy) && sinceMyHit > 3) {
			sinceTheyTookOff = 0;
		} else {
			sinceTheyTookOff = Math.min(sinceTheyTookOff + 1, 1000);
		}
		tries(d, e);
		// Going away from me: holding S, or flying out of a trade (with S held, they keep going once the knockback is spent).
		backingTicks = d.theyClose < -0.12 ? backingTicks + 1 : 0;
		inReachTicks = d.inReach() ? inReachTicks + 1 : 0;
		if (d.reach <= Duel.REACH) {
			outRolled = false;
		}
		if (d.reach > Duel.REACH + 1.5) {
			approachRolled = false;
			straightRolled = false;
		}
		if (backingTicks == 0) {
			backRolled = false;
		}
		if (!me.swordReady() || d.reach > Duel.REACH + 0.5) {
			delayRolled = false;
		}
		if (!d.inReach() || e.theySwung) {
			selectRolled = false;
			selectLeft = 0;
		}
	}

	/** The real player turns to the counters of what the other does most (part 7 of the techniques file). */
	private void adapt() {
		System.arraycopy(style.p, 0, pNow, 0, pNow.length);
		boost(Habits.PUNISH_CRIT, Technique.T1, Technique.T2);
		boost(Habits.DOUBLE_CRIT, Technique.C1);
		boost(Habits.STUN, Technique.T9, Technique.C3);
		boost(Habits.SPRINT_HITS, Technique.T9);
		boost(Habits.DEFLECT, Technique.T5, Technique.T9B);
		boost(Habits.STRAIGHT_IN, Technique.T9C);
		boost(Habits.JUMP_IN, Technique.T8, Technique.K2);
		boost(Habits.JUMP_RESET, Technique.C6, Technique.C7);
		boost(Habits.BACK_OUT, Technique.T11A, Technique.T11C);
		boost(Habits.MISS, Technique.T6);
		boost(Habits.HOP, Technique.C3);
		if (pNow[Technique.K2.ordinal()] > style.p(Technique.K2) && !k2) {
			k2 = rng.nextFloat() < pNow[Technique.K2.ordinal()];
		}
	}

	private void boost(int habit, Technique... counters) {
		float m = 1f + 4f * Math.max(0f, habits.rate(habit) - 0.2f) * habits.evidence(habit);
		if (m <= 1.01f) {
			return;
		}
		for (Technique t : counters) {
			int i = t.ordinal();
			// A boost, never a cut: a technique it does every time stays every time.
			pNow[i] = Math.max(pNow[i], Math.min(0.95f, Math.max(pNow[i], 0.1f) * m));
		}
	}

	// ---- starting plans ---------------------------------------------------------------------------

	private void begin(Plan p) {
		plan = p;
		planT = 0;
		phase = 0;
		planDir = rng.nextBoolean() ? 1 : -1;
		planAlt = false;
	}

	private void end() {
		plan = Plan.NONE;
		planT = 0;
		phase = 0;
	}

	/**
	 * Ticks the view of the other is behind the game (the school's seen lag): for the golden rule (K2) and the hit on a riser
	 * (T8), one seen rising too slowly to still be rising when the hit gets there is falling already. The user, 24 Oct 23:05:
	 * "remember the server part have lag between client so it might be landing already while client still doesn't". 0: as seen.
	 */
	private int seenLag;

	public void seenLag(int ticks) {
		seenLag = Math.max(0, ticks);
	}

	/** Falling in the game by the time a hit of mine reaches them: falling as seen, or rising too slowly to outlast the lag. */
	boolean fallingByTheHit(Body b) {
		return fallingByTheHit(b, seenLag);
	}

	/** {@link #fallingByTheHit(Body)} for a hit that gets there {@code lagTicks} after what is seen. */
	public static boolean fallingByTheHit(Body b, int lagTicks) {
		return !b.onGround && b.vy - RISE_LOST_A_TICK * Math.max(0, lagTicks) < 0.0;
	}

	/** What a jump's rise loses in a tick (gravity 0.08, near enough with the drag). */
	static final double RISE_LOST_A_TICK = 0.08;

	/** Starts {@code p} once a reaction time has gone by. */
	private void later(Plan p) {
		if (pending == Plan.NONE) {
			pending = p;
			pendingAt = tick + Math.max(0, style.reaction - 1 + rng.nextInt(2));
		}
	}

	/** On the tick a hit of theirs is felt. */
	private void hitTaken(Duel d, Events e) {
		boolean lifted = wasOnGround;
		if (lifted) {
			thrownUpAt = tick;
		}
		pending = Plan.NONE;
		if ((plan == Plan.T1 || plan == Plan.T1B || plan == Plan.T1C) && e.theyHit == Events.SPRINT && roll(Technique.C2)) {
			// C2: the double crit was sprint-hit: S at once, out of the trade still up on damage.
			begin(Plan.C2);
			return;
		}
		if (plan == Plan.B1 || plan == Plan.C2 || plan == Plan.C3 || plan == Plan.C4) {
			return;
		}
		end();
		if (style.punishFirst && lifted && punishable(d, e)) {
			// A sprint hit is jump-reset as it lands, so that it comes down in reach: the jump reset "gets crits", "a free crit
			// as you fall" (the file, part 3 and T9c). Without it nearly every sprint hit's lift came down out of reach in the
			// sim (763 of 769), 69% in the game. Then the crit on the way down.
			if (e.theyHit == Events.SPRINT) {
				timedJump(Technique.T9);
			}
			begin(Plan.B1);
			return;
		}
		if (e.theyHit == Events.CRIT && doubleCrits() && roll(Technique.C1)) {
			// C1: a double crit is coming; no punish crit, a sprint hit when it comes up again.
			begin(Plan.C1);
			return;
		}
		if (lifted && e.theyHit != Events.CRIT && habits.theirCombo() >= 2 && sinceLanded <= 1 && roll(Technique.T9A)) {
			// T9a: hit as it landed, in the rhythm of their combo: the jump with it.
			timedJump(Technique.T9A);
			return;
		}
		if (!style.resetsInCombosOnly && lifted && e.theyHit == Events.SPRINT && sinceMySwing <= 3 && expect(Habits.DEFLECT, tellDeflect) > 0.3f
				&& roll(Technique.T9B)) {
			timedJump(Technique.T9B);
			return;
		}
		if (e.theyHit == Events.SPRINT && sinceMySwing >= 1 && sinceMySwing <= 12 && d.theirReach < Duel.REACH + 0.5 && roll(Technique.T5)) {
			// Their deflection, and it did not send me out of reach: deflect it back.
			later(Plan.T5);
			return;
		}
		// The jump reset answers a sprint hit, a stun attempt (part 7: "Sprint hit (stun attempt) -> Jump reset (T9)"); for one
		// that goes by the file only that, and a plain hit or a crit that throws it up is the punish crit's (B1). Checked first
		// and certain, T9 took nearly every hit from B1 (24 Oct: one punish crit moment in six updates).
		if (lifted && e.theyHit != Events.CRIT && (!style.onlyWhenItWorks || e.theyHit == Events.SPRINT)
				&& (!style.resetsInCombosOnly || habits.theirCombo() >= 2) && roll(Technique.T9)) {
			timedJump(Technique.T9);
			return;
		}
		if (lifted && punishable(d, e) && roll(Technique.B1)) {
			begin(Plan.B1);
			return;
		}
		// The late C3 (the common mistake of the file): S only once stunned. "This can work for getting out of combos, but you
		// take more damage than you need to": for one that goes by the file, only in their combo (their second hit in a row).
		// After every sprint hit, once those were read right, it backed out of a third of its trades and held S three times as
		// much as the installed model, which traded on (24 Oct 17:44 check: 212 dealt a minute to its 314, taken alike).
		if (e.theyHit == Events.SPRINT && (!style.onlyWhenItWorks || habits.theirCombo() >= 2) && roll(Technique.C3)) {
			begin(Plan.C3);
		}
	}

	/** The jump reset: on the tick the hit is felt, or a tick or two late for a press that is not exact. */
	private void timedJump(Technique why) {
		int off = rng.nextFloat() < style.timing ? 0 : 1 + rng.nextInt(2);
		jumpAt = tick + off;
		jumpFor = why;
	}

	/** On the tick a hit of mine lands. */
	private void hitLanded(Duel d, Events e) {
		// The plan the hit belongs to: one that swung and was done a few ticks ago counts, as its hit lands after the swing.
		Plan was = plan != Plan.NONE ? plan : tick - doneAt <= HIT_AFTER_SWING ? donePlan : Plan.NONE;
		if (e.iHit == Events.CRIT) {
			if (was == Plan.T1 || was == Plan.T1B || was == Plan.T1C) {
				// The second crit landed: the double crit is done.
				end();
				wtapLeft = style.wtap;
				return;
			}
			end();
			// A punish crit: its own B1, or any crit before landing from their hit's lift (the school's punish crit teacher
			// lets go of the sprint there without the B1 plan, and T1c had about no moments, 24 Oct 20:10).
			boolean punishCrit = was == Plan.B1 || tick - thrownUpAt <= 18 && sinceLanded > tick - thrownUpAt && !d.me.onGround;
			if (was == Plan.T1A) {
				begin(Plan.T1);
			} else if (punishCrit && roll(Technique.T1C)) {
				begin(Plan.T1C);
			} else if (punishes() && roll(Technique.T1)) {
				// Any crit of mine against one that tries to punish-crit: "after your first crit, immediately jump and crit
				// the other player again" (only from these plans before, so it hardly ever came).
				begin(roll(Technique.T1B) ? Plan.T1B : Plan.T1);
			}
			return;
		}
		if (e.iHit == Events.SPRINT) {
			if (was == Plan.T11B || was == Plan.T11C) {
				return;
			}
			end();
			boolean afterStun = was == Plan.T4 || was == Plan.T5 || was == Plan.T6 || habits.myCombo() >= 2;
			if (afterStun && expect(Habits.JUMP_RESET, tellReset) > 0.3f && roll(Technique.C6)) {
				begin(Plan.C6);
				return;
			}
			// T2 "when": the sprint hit "did not take enough knockback to be put in a combo", as one hit in the air does
			// (K1). For one that goes by the file only then; else the hit is a combo's start (24 Oct, in the game: a standing
			// crit after every sprint hit cost about 13 damage a fight against the installed model).
			if ((!style.onlyWhenItWorks || !d.them.onGround) && roll(Technique.T2)) {
				begin(Plan.T2);
				return;
			}
			// T10 "best time: right after you stun them. For example, deflection (T4) into uppercut." For one that goes by the
			// file, first then; hops (T11b) hold the other moments ("hops hold a combo"). With both certain, hops always came
			// first and the uppercut never (24 Oct: no T10 moment in the whole night).
			boolean stunHit = was == Plan.T4 || was == Plan.T5 || was == Plan.T6;
			if (style.onlyWhenItWorks && stunHit && roll(Technique.T10)) {
				later(Plan.T10);
				return;
			}
			if (roll(Technique.T11B)) {
				begin(Plan.T11B);
				return;
			}
			if (roll(Technique.T10)) {
				later(Plan.T10);
			}
		} else {
			end();
		}
		wtapLeft = style.wtap;
		stapLeft = style.stap;
	}

	/** Edges looked at while no plan runs. */
	private void edges(Duel d, Events e) {
		Body me = d.me;
		Body them = d.them;
		if (pending != Plan.NONE) {
			return;
		}
		if (style.punishFirst && d.reach < PUNISH_HOLD_REACH) {
			begin(Plan.B1_HOLD);
			return;
		}
		// Their swing began, the sword is back and they are near: the deflection (T4).
		if (e.theySwung && me.swordReady() && d.reach < 4.2 && roll(Technique.T4)) {
			later(Plan.T4);
			return;
		}
		// My own swing: T9b, the jump right after it, against someone who deflects.
		if (!style.resetsInCombosOnly && e.iSwung && me.onGround && expect(Habits.DEFLECT, tellDeflect) > 0.25f && roll(Technique.T9B)) {
			jumpAt = tick + 1 + (rng.nextFloat() < style.timing ? 0 : rng.nextInt(2));
			jumpFor = Technique.T9B;
			return;
		}
		// They jump at me: for the teacher, let it land and punish crit (B1); else hit them while they rise (T8).
		if (style.punishJumpIns && sinceTheyTookOff == 0 && d.theyClose > 0.05 && d.flat < 6.5 && roll(Technique.B1)) {
			begin(Plan.B1_WAIT);
			return;
		}
		if (!style.punishJumpIns && sinceTheyTookOff == 0 && d.theyClose > 0.05 && d.flat < 6.5 && roll(Technique.T8)) {
			begin(Plan.T8);
			return;
		}
		// They come in: out before the stun (C3, C4), make them miss (T6), or jump so they meet me falling (K3).
		boolean coming = d.theyClose > 0.08 && d.theirReach > Duel.REACH && d.theirReach < Duel.REACH + 1.0 && them.onGround;
		if (coming && !approachRolled) {
			approachRolled = true;
			// C4: against one that sprint-jumps after a held S, "attack them right before you hold S", which kills the chase.
			// Only C3's own start (the sword not back) led to it, so with the sword back it never came (one moment in fifteen
			// updates, 24 Oct): its own start, the sword back, before the C3.
			if (me.swordReady() && them.swordReady() && them.sprinting && expect(Habits.JUMP_IN, -1f) > 0.3f && roll(Technique.C4)) {
				begin(Plan.C4);
				return;
			}
			if (!me.swordReady() && them.swordReady() && them.sprinting && roll(Technique.C3)) {
				begin(Plan.C3);
				return;
			}
			// A sprint hit coming (the extra knockback): jump so that it lands on me falling, less knockback for me (K3);
			// a plain one, make it miss (T6). Each first where it fits, the other if it is not rolled.
			boolean k3 = me.onGround && me.charge > 0.5f;
			if (them.sprinting && k3 && roll(Technique.K3)) {
				begin(Plan.K3);
				return;
			}
			if (roll(Technique.T6)) {
				begin(Plan.T6);
				planAlt = rng.nextFloat() < Math.max(style.p(Technique.T6A), pNow[Technique.T6A.ordinal()]);
				return;
			}
			if (!them.sprinting && k3 && roll(Technique.K3)) {
				begin(Plan.K3);
				return;
			}
		}
		// They run straight in, not jumping: T9c, the hit as they enter reach with the jump.
		boolean straight = d.theyClose > 0.18 && them.onGround && sinceTheyTookOff > 15 && d.theySideways < 0.08
				&& d.reach > Duel.REACH + 0.3 && d.reach < Duel.REACH + 2.5;
		if (straight && !straightRolled && me.swordReady()) {
			straightRolled = true;
			if (!style.resetsInCombosOnly && (!style.onlyWhenItWorks || expect(Habits.STRAIGHT_IN, -1f) > 0.3f) && roll(Technique.T9C)) {
				begin(Plan.T9C);
				return;
			}
		}
		// Out of reach with the sword back: the bait crit (T1a), the three-block hit (T7), or an opening crit.
		if (d.reach > Duel.REACH + 0.4 && d.reach < 8.0 && me.swordReady() && !outRolled) {
			outRolled = true;
			if (punishes() && roll(Technique.T1A)) {
				begin(Plan.T1A);
				return;
			}
			if (d.theyClose < 0.2 && roll(Technique.T7)) {
				begin(Plan.T7);
				return;
			}
			if (!style.noJumps && style.critOpen > 0f && rng.nextFloat() < style.critOpen) {
				begin(Plan.OPEN_CRIT);
				return;
			}
		}
		// They back out with S: an uppercut from just outside reach (T10: "enough to catch up if they are not too far"),
		// a lunge after them when farther (T11a: "chases players holding S"). With the lunge checked first and certain the
		// uppercut never came here, only after a stun of its own (24 Oct: about ten moments an update, and falling); the
		// user: the uppercut matters more than hops and lunges.
		if (backingTicks >= 3 && !backRolled && me.onGround && d.reach > Duel.REACH - 0.3 && d.reach < Duel.REACH + 3.0) {
			backRolled = true;
			if (d.reach < Duel.REACH + 1.0 && roll(Technique.T10)) {
				begin(Plan.T10);
				return;
			}
			if (roll(Technique.T11A)) {
				begin(Plan.T11A);
				return;
			}
		}
		// In reach, sword back, against one that jump-resets: the delayed sprint hit (C7).
		if (d.inReach() && me.swordReady() && them.onGround && !delayRolled) {
			delayRolled = true;
			if (expect(Habits.JUMP_RESET, tellReset) > 0.25f && roll(Technique.C7)) {
				begin(Plan.C7);
			}
		}
	}

	// ---- the plans ---------------------------------------------------------------------------------

	private boolean canSwing(Duel d) {
		return d.inReach() && d.me.charge >= style.swingCharge;
	}

	private int step(Duel d, Keys k) {
		Body me = d.me;
		Body them = d.them;
		switch (plan) {
			case OPEN_CRIT -> {
				return jumpedCrit(d, k, style.critFrom, false);
			}
			case T1A -> {
				return jumpedCrit(d, k, RUNNING_JUMP_FROM + 0.1f, false);
			}
			case B1_WAIT -> {
				// They come in: no swing (the sword stays back for the punish crit) and the sprint let go (a crit needs not
				// sprinting; letting go of W ends it); in reach it stands. Their hit ends it (hitTaken), and B1 takes over.
				k.taught = Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_MOVE;
				k.forward = !me.sprinting && d.reach > Duel.REACH ? 1 : 0;
				if (them.onGround && planT > 2) {
					return ABORT;
				}
				return planT < 30 ? RUN : ABORT;
			}
			case B1_HOLD -> {
				// Waiting for their hit with the sword back: no swing, no sprint; in to the edge of reach at a walk. Their hit ends it
				// (hitTaken), and B1 takes over.
				k.taught = Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_MOVE;
				k.forward = !me.sprinting && d.reach > Duel.REACH ? 1 : 0;
				return planT < PUNISH_HOLD_TICKS ? RUN : ABORT;
			}
			case B1 -> {
				// Hit standing, thrown up: no swing back now, the sprint let go, the swing kept for the way down. A crit needs
				// not sprinting, and letting go of W is what ends a sprint (part 8): no W while it still sprints.
				k.taught = Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_MOVE;
				k.forward = d.reach > 1.8 && !me.sprinting ? 1 : 0;
				if (me.falling() && canSwing(d) && !me.sprinting) {
					k.attack = true;
					return RUN;
				}
				// Sent out of reach (a stun): there is nothing to punish; the ordinary fight takes over. For one that goes by the
				// file, as soon as it comes down out of reach: the crit "on the way down" is not there.
				// On the way up a sprint hit's lift goes farther out than a plain one's, and they come after it.
				double out = style.onlyWhenItWorks && me.falling() ? Duel.REACH + 0.3 : Duel.REACH + 2.5;
				return me.onGround && planT > 2 || d.reach > out ? ABORT : RUN;
			}
			case T1, T1B, T1C -> {
				return jumpedCrit(d, k, 2.5f, plan == Plan.T1B);
			}
			case T2, C6 -> {
				// Sprint hit made: straight up, and the crit on the way down.
				k.taught = Keys.KEY_JUMP | Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK;
				if (phase == 0) {
					if (!me.onGround) {
						return ABORT;
					}
					if (waitsForTheSword(me) && planT < 8) {
						return RUN;
					}
					k.jump = true;
					phase = 1;
					return RUN;
				}
				if (me.falling() && canSwing(d)) {
					k.attack = true;
					return DONE;
				}
				return me.onGround && planT > 2 ? ABORT : RUN;
			}
			case T4, T5 -> {
				// Sprint-hit them right after their swing (or their deflection), from the edge of reach, not too close.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK;
				if (plan == Plan.T5 && planT == 1 && d.theirReach > Duel.REACH + 0.8) {
					// Their deflection stunned me after all: no reverse deflection from out here.
					return ABORT;
				}
				if (d.reach < style.spacing - 0.8) {
					k.forward = -1;
				} else {
					k.forward = 1;
					k.sprint = true;
				}
				if (canSwing(d) && me.sprinting) {
					k.attack = true;
					return DONE;
				}
				return planT < (plan == Plan.T5 ? 16 : 10) ? RUN : ABORT;
			}
			case T6 -> {
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_STRAFE;
				if (phase == 0) {
					// Stop, or S, as they go to swing (or strafe, T6a): the swing comes short.
					k.forward = planDir > 0 ? 0 : -1;
					if (planAlt) {
						k.strafe = planDir;
						k.technique = Technique.T6A;
					}
					if (sinceTheirSwing <= 2 && sinceTheirHit > 3) {
						phase = 1;
						return RUN;
					}
					return sinceTheirHit <= 1 || planT > 14 ? ABORT : RUN;
				}
				// They missed: in, and the sprint hit; from not too close.
				if (sinceTheirHit == 0) {
					return ABORT;
				}
				k.forward = d.reach < 2.2 ? -1 : 1;
				k.sprint = k.forward > 0;
				if (canSwing(d) && d.reach >= 2.2 && me.sprinting) {
					k.attack = true;
					return DONE;
				}
				return planT < 30 ? RUN : ABORT;
			}
			case T7 -> {
				// Full sprint, straight at them, the hit from the edge before they can hit back.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_STRAFE;
				k.forward = 1;
				k.sprint = true;
				if (canSwing(d) && me.sprinting) {
					k.attack = true;
					return DONE;
				}
				return d.reach < 9.0 && planT < 50 ? RUN : ABORT;
			}
			case T8 -> {
				// At them while they rise; once they fall it is the golden rule's (K2): leave it.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK;
				if (them.onGround && planT > 2 || fallingByTheHit(them)) {
					return ABORT;
				}
				k.forward = 1;
				k.sprint = true;
				if (canSwing(d) && them.rising() && !fallingByTheHit(them)) {
					k.attack = true;
					return DONE;
				}
				return planT < 12 ? RUN : ABORT;
			}
			case T9C -> {
				// Sprint at them, the hit as they come into reach and the jump with it; the crit falling.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_JUMP;
				if (phase == 0) {
					k.forward = 1;
					k.sprint = true;
					if (canSwing(d) && me.onGround) {
						k.attack = true;
						k.jump = true;
						phase = 1;
					}
					return planT < 30 ? RUN : ABORT;
				}
				k.forward = d.reach > 2.0 ? 1 : 0;
				if (me.falling() && canSwing(d)) {
					k.attack = true;
					return RUN;
				}
				return me.onGround && planT > 3 ? ABORT : RUN;
			}
			case T10 -> {
				// Jump with the sprint on, the sprint hit at the top; then S.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_JUMP;
				if (phase == 0) {
					// Still landing from the hit before: keep the sprint going (W and the sprint key), the jump comes on landing.
					// It used to press nothing while waiting: "let go of W" for up to six ticks, which nothing in the file asks
					// and every other moment contradicts.
					k.forward = 1;
					k.sprint = true;
					if (!me.onGround) {
						return planT < 6 ? RUN : ABORT;
					}
					k.jump = true;
					phase = 1;
					return RUN;
				}
				if (phase == 1) {
					k.forward = 1;
					k.sprint = true;
					boolean top = !me.onGround && Math.abs(me.vy) < 0.12;
					if ((top || me.falling()) && canSwing(d)) {
						k.attack = true;
						phase = 2;
						planT = 0;
						return RUN;
					}
					return me.onGround && planT > 3 ? ABORT : RUN;
				}
				k.forward = -1;
				return planT < 5 ? RUN : DONE;
			}
			case T11A -> {
				// Sprint-jump forward, the sprint hit falling.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_JUMP;
				k.forward = 1;
				k.sprint = true;
				if (phase == 0) {
					if (me.onGround) {
						k.jump = true;
						phase = 1;
						return RUN;
					}
					return planT < 6 ? RUN : ABORT;
				}
				if (me.falling() && canSwing(d)) {
					k.attack = true;
					return DONE;
				}
				return me.onGround && planT > 3 ? ABORT : RUN;
			}
			case T11B, T11C -> {
				return hops(d, k);
			}
			case C1 -> {
				// No punish crit against a double critter: the sprint hit (in the air too) as it comes up again.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK;
				k.forward = 1;
				k.sprint = true;
				if (canSwing(d) && me.sprinting && !them.falling()) {
					k.attack = true;
					return DONE;
				}
				return planT < 24 ? RUN : ABORT;
			}
			case C2 -> {
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT;
				k.forward = -1;
				return planT < 12 ? RUN : DONE;
			}
			case C3, C4 -> {
				// Jump and hold S; with C4 the swing first, if there is one to make.
				k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_JUMP | Keys.KEY_ATTACK;
				if (phase == 0) {
					if (plan == Plan.C4 && !canSwing(d)) {
						// Started on its own: the attack as they come into reach, then the S.
						return planT < 6 ? RUN : ABORT;
					}
					phase = 1;
					if (canSwing(d) && (plan == Plan.C4 || roll(Technique.C4))) {
						plan = Plan.C4;
						k.technique = Technique.C4;
						k.attack = true;
						return RUN;
					}
				}
				k.forward = -1;
				if (me.onGround && planT <= 3) {
					k.jump = true;
				}
				return planT < 14 ? RUN : DONE;
			}
			case C7 -> {
				// The delayed sprint hit: in reach, sword back, no hit until they have jumped. W stays held: letting go of it ends
				// the sprint (part 8), and the hit that "sends them flying" is a sprint hit. Up close it used to let go of W
				// with the sprint key on, so its hit was a plain one (24 Oct: C7 26%, off on W/S a third of its ticks).
				k.taught = Keys.KEY_ATTACK | Keys.KEY_MOVE | Keys.KEY_SPRINT;
				k.forward = 1;
				k.sprint = true;
				if (them.rising() && canSwing(d)) {
					k.attack = true;
					return DONE;
				}
				if (planT > 6) {
					// They did not jump: the hit now.
					k.attack = canSwing(d);
					return DONE;
				}
				return d.reach < Duel.REACH + 1.0 ? RUN : ABORT;
			}
			case K3 -> {
				// A jump now, so that they meet me on my way down; the crit as they arrive.
				k.taught = Keys.KEY_JUMP | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_MOVE;
				if (phase == 0) {
					if (!me.onGround) {
						return ABORT;
					}
					k.jump = true;
					phase = 1;
					return RUN;
				}
				if (me.falling() && canSwing(d)) {
					k.attack = true;
					return RUN;
				}
				return me.onGround && planT > 2 ? ABORT : RUN;
			}
			default -> {
				return ABORT;
			}
		}
	}

	/**
	 * A crit from a jump of its own: in at a sprint to {@code from} (eye to hitbox), the jump, and the swing
	 * on the way down with the sprint off. From {@link #RUNNING_JUMP_FROM} on the jump is a running one
	 * (it carries in about three blocks); from nearer the sprint is let go of a step before and the jump is
	 * made from a walk. The plan ends when the crit lands ({@link #hitLanded}) or on landing without one.
	 */
	private int jumpedCrit(Duel d, Keys k, float from, boolean around) {
		Body me = d.me;
		boolean running = from >= RUNNING_JUMP_FROM;
		k.taught = Keys.KEY_JUMP | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_MOVE | (around ? Keys.KEY_STRAFE : 0);
		// T1b: around them the whole double crit, "go around them or behind them as you double crit"; only in the air it was a
		// strafe key turned on at the jump, taught as no strafe until then, and the network missed it (24 Oct 20:34: 25%,
		// off on A/D a fifth of its ticks).
		if (around) {
			k.strafe = planDir;
		}
		if (phase == 0) {
			if (!me.onGround) {
				// Still coming down from the last one: wait for the ground, walking in.
				k.forward = d.reach > 1.2 ? 1 : 0;
				return planT < 20 ? RUN : ABORT;
			}
			if (d.reach > from + 0.1) {
				k.forward = 1;
				k.sprint = running || d.reach > from + 1.0;
				return planT < 40 ? RUN : ABORT;
			}
			if (waitsForTheSword(me)) {
				k.forward = d.reach > 1.2 ? 1 : 0;
				return planT < 40 ? RUN : ABORT;
			}
			k.jump = true;
			k.forward = d.reach > 1.0 ? 1 : 0;
			k.sprint = running;
			phase = 1;
			return RUN;
		}
		// The running jump keeps its sprint going up; coming down, W is let go while it still sprints, as a crit needs not
		// sprinting (part 8), and the swing waits for that.
		// W on the way up, let go on the way down and not pressed again (as B1: off while still sprinting and on again once
		// not, it flipped within a tick or two; T1 65%, T1b 16% at 20:50).
		k.forward = d.reach > 1.0 && me.rising() ? 1 : 0;
		k.sprint = running && me.rising();
		if (around) {
			k.strafe = planDir;
		}
		if (me.falling() && canSwing(d) && !me.sprinting) {
			k.attack = true;
			k.sprint = false;
			return RUN;
		}
		return me.onGround && planT > 3 ? ABORT : RUN;
	}

	/**
	 * Hops (T11b), step by step as the file has them: W let go and the jump (straight up); W again in
	 * the air; the sprint hit falling, before W is let go; W let go and the next jump on landing. When
	 * they hold S the next jump is a lunge instead (T11c).
	 */
	private int hops(Duel d, Keys k) {
		Body me = d.me;
		k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_ATTACK | Keys.KEY_JUMP;
		if (sinceMyHit > COMBO_TICKS || d.reach > Duel.REACH + 2.0 || sinceTheirHit == 0) {
			return ABORT;
		}
		if (me.onGround) {
			if (d.theyClose < -0.12 && roll(Technique.T11C)) {
				plan = Plan.T11C;
				k.technique = Technique.T11C;
				k.forward = 1;
				k.sprint = true;
			}
			k.jump = true;
			return RUN;
		}
		if (me.rising()) {
			k.forward = 1;
			k.sprint = true;
			return RUN;
		}
		// Falling: the hit while W is still held; W let go just before landing.
		boolean hit = canSwing(d) && me.sprinting;
		boolean landing = me.vy < -0.3;
		k.forward = landing && !hit ? 0 : 1;
		k.sprint = k.forward > 0;
		k.attack = hit;
		return planT < 100 ? RUN : DONE;
	}

	// ---- the ordinary fight (part 0) ----------------------------------------------------------------

	private void ordinary(Duel d, Keys k) {
		Body me = d.me;
		Body them = d.them;
		k.technique = Technique.BASE;
		boolean ready = me.charge >= style.swingCharge;
		if (wtapLeft > 0) {
			// W-tap: W let go for a moment after a hit, to start the sprint again.
			wtapLeft--;
			k.forward = 0;
		} else if (stapLeft > 0) {
			stapLeft--;
			k.forward = -1;
		} else if (d.reach > Duel.REACH + 0.2) {
			k.forward = 1;
			k.sprint = true;
		} else if (!ready && d.reach < style.spacing - 0.3) {
			// S-tap: out of their reach again while the sword comes back.
			k.forward = -1;
		} else if (!ready) {
			k.forward = d.reach > style.spacing + 0.2 ? 1 : 0;
			k.sprint = k.forward > 0;
		} else {
			k.forward = 1;
			k.sprint = true;
		}
		if (d.flat < 5.0 && style.strafe > 0f) {
			if (--strafeLeft <= 0) {
				strafing = rng.nextFloat() < style.strafe;
				strafeDir = rng.nextBoolean() ? 1 : -1;
				strafeLeft = 8 + rng.nextInt(20);
			}
			if (strafing) {
				k.strafe = strafeDir;
			}
		}
		boolean inCombo = sinceMyHit <= COMBO_TICKS;
		boolean seenLongEnough = inCombo || inReachTicks >= Math.max(1, style.reaction / 2);
		if (style.rhythm && inCombo && ready && d.inReach()) {
			// A combo to a rhythm: the hit comes as they land.
			k.attack = them.onGround && !theyWereOnGround || them.onGround && sinceMyHit > 14;
		} else if (ready && seenLongEnough && d.reach <= Duel.REACH + style.swingEarly) {
			k.attack = true;
		}
		boolean comboing = sinceMyHit <= COMBO_TICKS && sinceMyHit < sinceTheirHit;
		if (k.attack && style.hitSelect > 0f && them.swordReady() && !comboing) {
			// Hit selecting: their swing first, the deflection right after it (T4).
			if (!selectRolled) {
				selectRolled = true;
				selectLeft = rng.nextFloat() < style.hitSelect ? 8 + rng.nextInt(8) : 0;
			}
			if (selectLeft > 0) {
				selectLeft--;
				k.attack = false;
				k.forward = d.reach < style.spacing - 0.3 ? -1 : 0;
				k.sprint = false;
				k.technique = Technique.T4;
				k.taught = Keys.KEY_ATTACK | Keys.KEY_MOVE;
			}
		}
		if (k.attack && k2 && them.falling()) {
			// The golden rule: no knockback hit on someone falling; on the ground or rising instead.
			k.attack = false;
			k.technique = Technique.K2;
			k.taught = Keys.KEY_ATTACK;
		}
	}
}
