package dev.swordai.core.school;

import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.BackNudge;

import java.util.Locale;

/**
 * Run 124 (COUNTER_SPACING, agent 58, 16 Nov ~22:50): in THEIR combo, and as they close in before a trade, it counters by spacing,
 * not by flying out.
 *
 * <p>The user (16 Nov 22:40-22:45): "a better model on basically no way to combo it because it learns all the counter"; "escaping
 * combo still gets them two free hits anyway so it is bad as well". Every tick of the user's real rounds (mostly run35):
 * <ul>
 *   <li>after their hit at under 2.0 blocks its hit came next 61% (theirs 20%); at 2.0-2.6 47% (23%); at 2.6+ only 31% (48%): the
 *       edge-of-reach sprint hit is the stun (T3). Its own sprint hit from 2.6+ was followed by theirs 15%, from under 2.0 72%;</li>
 *   <li>when their next hit came (108): it had held S in 71 (the escape did not save it), they were within 3.0 in 103, and its own
 *       sword was READY in 59: in reach with a ready sword and still hit first.</li>
 * </ul>
 * So, the document's answers (docs/PVP-TECHNIQUES.md): with its sword ready it is the one hitting at the edge, a sprint hit the moment
 * they enter its reach (T7 "sprint-hit the opponent before they are close enough to hit you back ... running at full sprint toward
 * them"; T4 when they swing first or come too close); with its sword not ready it stays just outside their reach with S taps (part 0
 * "S-tap: a short press of S ... stay just out of their reach") and strafes (T6a) so their swing misses (T6), and runs in to arrive
 * at the edge as its sword comes back. No long S (C3's "common mistake", and the evidence), no jump (the rules' reactive jump reset,
 * right in 95% of real rounds, keeps the jump), nothing at one in the air (their jump-in: K2, the late-hit guard, T8 and the punish
 * crit B1 stay with the rules), and only charged swings.
 *
 * <p>Pure logic, no game: the session fills {@link Input} from what it sees and carries out the {@link Plan}'s keys, as a rule over the
 * network (the user's duels, the checks' side A, training where the rules play) and as the teacher's target in training
 * ({@link #target}). It is self-contained: run 123's spacing in its OWN combo (ComboSpacing) acts where this one does not (this one is
 * off in its own combo), so the two can stand side by side; joined, they would share {@link #SPRINT_TICK}, the edge band and the S-tap
 * clock.
 */
public final class CounterSpacing {
	/** Switch: false and nothing in this class acts anywhere (the sessions check it). */
	public static final boolean ON = true;
	/**
	 * In training where the rules play (not a fight without the rules, not run 84's combo-exit fights), its keys are pressed as a rule
	 * and taught under it (shadow); in the other training fights the network presses them and is taught them. False: training is label
	 * only everywhere (checks and the user's duels still press them).
	 */
	public static final boolean RULE_IN_TRAINING = true;
	/** Its lesson's own pull in training (SchoolRollout#spacingPull): full, as the swing lesson's SWING_PULL since run 117, not by the outcome. */
	public static final float PULL = 1.0f;
	/** Their combo, as the school counts it: a hit taken within this many ticks, none dealt since (SchoolSession.COMBO_GAP, SchoolFighter.OWN_COMBO_TICKS; the evidence's 20 ticks). */
	public static final int COMBO_TICKS = 20;
	/** A sword's reach, eye to hitbox (Duel.REACH). */
	public static final double REACH = Duel.REACH;
	/** A sprinting player's ground speed, blocks a tick (Minecraft's 5.6 blocks a second over 20 ticks): one tick of their run-in. */
	public static final double SPRINT_TICK = 0.28;
	/** "Just outside their reach": their reach plus one tick of their sprint (REACH + SPRINT_TICK = 3.28). */
	public static final double OUT_EDGE = REACH + SPRINT_TICK;
	/** The far end of the "just out of reach" band where it stands (EdgeHold.TO, the code's 3.0-4.2 edge band); beyond it walks in. */
	public static final double BAND_TO = 4.2;
	/** The evidence's stun edge: hits from 2.6+ blocks stun both ways (their next 48% / its next 15%), the closer ones get hit through. */
	public static final double EDGE_FROM = 2.6;
	/** Their combo is followed within this distance; past it the engagement is over (the user, 14 Nov: they "back out 5-6 block"). */
	public static final double COMBO_RANGE = 6.0;
	/** Closing in before a trade: they come at it this fast (blocks a tick) within REACH + CLOSE_BAND of their reach (PvpPlayer's "they come in": 0.08, REACH + 1.0). */
	public static final double CLOSE_SPEED = 0.08;
	public static final double CLOSE_BAND = 1.0;
	/** Facing them: W and S move along the line to them only within this many degrees (the camera lesson's 25-degree test in SchoolSession). */
	public static final double FACE_DEG = 25.0;
	/** The longest S tap, ticks: under hit and out's 4-tick S (StepBack#planShort), the shortest "out" S of the code; C3 holds 14, rule b 12. */
	public static final int S_TAP_MOST = 3;
	/** Ticks off S before the next tap. */
	public static final int S_TAP_REST = 2;
	/** The tally's long S: this many ticks of S in a row (hit and out's 4 and up). */
	public static final int LONG_S = 4;
	/** A or D held this many ticks before the other (ComboStrafe.TICKS, half a second); the first way is away from their sideways run. */
	public static final int STRAFE_TICKS = 10;
	/** Their sideways speed (blocks a tick) that picks the strafe's first way (ComboStrafe.ACROSS). */
	public static final double ACROSS = 0.04;
	/**
	 * The installed model's rules that come first ({@code Fighter#rules()} bits): the swing kept for the way down (k, 1), the run at a
	 * rising jumper (m, 2), the jump reset (r, 3), a jump made for it (J, 6), the dodge (10), the crit trade's hold (12), the punish crit
	 * (13). On a tick one of them has keys this controller stands aside. The answer swing (a, 0), the back-out S (b, 4), the step back
	 * (t, 5), the camera (7, 8), straight in (s, 9) and the edge hold (11) it overrides on its heads.
	 */
	public static final int RULES_FIRST = (1 << 1) | (1 << 2) | (1 << 3) | (1 << 6) | (1 << 10) | (1 << 12) | (1 << 13);
	/** Its bit in the round files' rules column (RoundRecorder, beside Fighter#rules()'s 0-13): shown as 'C'. */
	public static final int RULE_BIT = 1 << 24;
	/** The heads it may set: W/S, A/D, the sprint key, the swing. Never the jump, never the camera. */
	public static final int HEADS = ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.ATTACK);

	/** Where it is. */
	public static final int NO_MOMENT = 0, THEIR_COMBO = 1, CLOSING = 2;
	/** What it decided (counters): stood aside, turning (not facing them), the run-in, the swing, an S tap, the tap's rest, standing at the edge, walking in. */
	public static final int NONE = 0, YIELD = 1, TURN = 2, RUN_IN = 3, SWING = 4, TAP_BACK = 5, REST = 6, HOLD = 7, WALK_IN = 8;
	public static final String[] WHY_NAMES = {"none", "stood aside", "turning", "run-in", "swing", "S tap", "tap rest", "at the edge", "walk in"};

	/** What the session tells it each tick. */
	public static final class Input {
		/** Both fighters as it sees them, measured this tick (DuelReader's): reach, their reach, their run at it, the line to them, its yaw. */
		public Duel duel;
		/** Its sword (0..1) and the least charge for a swing here (the rule swing charge: 0.95 in the school, 1.0 on a server). */
		public float charge;
		public float readyCharge = 0.95f;
		/** The game's pick: the crosshair on them within reach. */
		public boolean pick;
		/** Ticks since its last hit taken and dealt (0: this tick). */
		public int sinceTaken = 1000;
		public int sinceDealt = 1000;
		/** Their jump-in in the air (the jump-in guard's flag), or them falling by the time a sprint hit lands (K2). */
		public boolean jumpIn;
		public boolean themFalling;
		/** No swing allowed this tick (K2, the jump-in guard). */
		public boolean swingHeld;
		/** Something comes first this tick (a rule of RULES_FIRST, a kept technique told or taught): it stands aside. */
		public boolean yield;
	}

	/** One tick's decision; valid until the next {@link #plan}. */
	public static final class Plan {
		public int moment;
		public int why;
		/** The heads it sets (0: none) and their keys (ActionSpace choices). */
		public int heads;
		public int forward;
		public int strafe;
		public int sprint;
		public int attack;

		public boolean active() {
			return heads != 0;
		}

		public void clear() {
			moment = NO_MOMENT;
			why = NONE;
			heads = 0;
			forward = 0;
			strafe = 0;
			sprint = 0;
			attack = 0;
		}

		/** Its keys into an action, on its heads only. */
		public void writeTo(int[] action) {
			if ((heads & (1 << ActionSpace.FORWARD)) != 0) {
				action[ActionSpace.FORWARD] = forward;
			}
			if ((heads & (1 << ActionSpace.STRAFE)) != 0) {
				action[ActionSpace.STRAFE] = strafe;
			}
			if ((heads & (1 << ActionSpace.SPRINT)) != 0) {
				action[ActionSpace.SPRINT] = sprint;
			}
			if ((heads & (1 << ActionSpace.ATTACK)) != 0) {
				action[ActionSpace.ATTACK] = attack;
			}
		}

		/** Its keys as an action array (the other heads at their defaults), for SchoolFighter#outsideRule. */
		public int[] keys(int[] out) {
			System.arraycopy(ActionSpace.DEFAULTS, 0, out, 0, ActionSpace.HEADS);
			writeTo(out);
			return out;
		}
	}

	private final Plan plan = new Plan();
	/** S ticks in a row up to the last tick, and ticks without S in a row (from the keys really pressed, see {@link #pressed}). */
	private int sRun;
	private int noS = 1000;
	/** The way it strafes (1 A, 2 D, 0 none) and for how many ticks in a row it has. */
	private int strafeWay;
	private int strafeTicks;

	/** A new fight: no taps, no strafe going. */
	public void reset() {
		plan.clear();
		sRun = 0;
		noS = 1000;
		strafeWay = 0;
		strafeTicks = 0;
	}

	/** The last decision. */
	public Plan plan() {
		return plan;
	}

	/** Which moment this is, if any (their combo, closing in): before the gates. */
	public static int moment(Input in) {
		Duel d = in.duel;
		boolean theirCombo = in.sinceTaken >= 1 && in.sinceTaken <= COMBO_TICKS && in.sinceDealt > in.sinceTaken;
		if (theirCombo) {
			return d.theirReach <= COMBO_RANGE ? THEIR_COMBO : NO_MOMENT;
		}
		// The tick of their hit is the jump reset's (rule r) and the late-jump guard's: never this controller's.
		if (in.sinceTaken == 0) {
			return NO_MOMENT;
		}
		boolean ownCombo = in.sinceDealt <= COMBO_TICKS && in.sinceTaken > in.sinceDealt;
		if (!ownCombo && d.theyClose >= CLOSE_SPEED && d.theirReach <= REACH + CLOSE_BAND) {
			return CLOSING;
		}
		return NO_MOMENT;
	}

	/** Whether it faces them: W and S then run along the line to them. */
	static boolean facing(Duel d) {
		double dot = d.me.lookX() * d.ux + d.me.lookZ() * d.uz;
		return dot >= Math.cos(Math.toRadians(FACE_DEG));
	}

	/** One decision. */
	public Plan plan(Input in) {
		plan.clear();
		if (!ON || in.duel == null) {
			return plan;
		}
		Duel d = in.duel;
		plan.moment = moment(in);
		if (plan.moment == NO_MOMENT) {
			return plan;
		}
		// On the ground both, them not falling, no jump-in up, nothing first: else the rules' and the network's (K2, T8, B1, the jump reset).
		if (in.yield || !d.me.onGround || !d.them.onGround || d.them.falling() || in.themFalling || in.jumpIn) {
			plan.why = YIELD;
			return plan;
		}
		if (!facing(d)) {
			plan.why = TURN;
			return plan;
		}
		boolean ready = in.charge >= in.readyCharge;
		if (ready || arrivesReady(in)) {
			// Its sword ready (or back by the time it gets to the edge): face them and sprint in, the sprint hit the moment they enter reach.
			plan.heads = ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT);
			plan.forward = 1;
			plan.strafe = 0;
			plan.sprint = 1;
			plan.why = RUN_IN;
			if (ready && in.pick && !in.swingHeld) {
				plan.heads |= 1 << ActionSpace.ATTACK;
				plan.attack = 1;
				plan.why = SWING;
			}
			return plan;
		}
		// Its sword not ready: just outside their reach, S taps (never a long S), strafing so their swing misses (T6, T6a).
		plan.heads = ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT);
		plan.sprint = 0;
		if (d.theirReach < OUT_EDGE) {
			boolean tap = sRun > 0 ? sRun < S_TAP_MOST : noS >= S_TAP_REST;
			plan.forward = tap ? BackNudge.BACK : 0;
			plan.why = tap ? TAP_BACK : REST;
			plan.strafe = strafeNow(d);
		} else if (d.theirReach <= BAND_TO) {
			plan.forward = 0;
			plan.why = HOLD;
			plan.strafe = strafeNow(d);
		} else {
			// Out past the band: walk (no sprint) back to it, straight (StraightIn's "out of reach it runs straight").
			plan.forward = 1;
			plan.strafe = 0;
			plan.why = WALK_IN;
		}
		return plan;
	}

	/**
	 * Its sword is not back yet but will be by the time a sprint brings it to the edge of reach: the run-in starts now, so that it arrives
	 * as the sword comes back (ticks to the edge at its sprint plus their run at it, against ticks to the charge, Body.SWORD_TICKS a sword).
	 */
	static boolean arrivesReady(Input in) {
		Duel d = in.duel;
		if (d.reach <= REACH) {
			return false;
		}
		double toReady = (in.readyCharge - in.charge) * Body.SWORD_TICKS;
		double closing = SPRINT_TICK + Math.max(0.0, d.theyClose);
		double toEdge = (d.reach - REACH) / closing;
		return toEdge >= toReady;
	}

	/** The strafe for this tick: the way it is going, the other after STRAFE_TICKS, the first one away from their sideways run. */
	private int strafeNow(Duel d) {
		if (strafeWay != 0 && strafeTicks < STRAFE_TICKS) {
			return strafeWay;
		}
		if (strafeWay != 0) {
			return strafeWay == 1 ? 2 : 1;
		}
		// Their speed across its line, to its right positive (right of a player facing (ux, uz) is (-uz, ux)): strafe the other way.
		double across = -d.them.vx * d.uz + d.them.vz * d.ux;
		return across > ACROSS ? 1 : across < -ACROSS ? 2 : (noS + sRun) % 2 == 0 ? 1 : 2;
	}

	/** The keys really pressed this tick (by whoever), for the S taps and the strafe's clock. */
	public void pressed(int forward, int strafe) {
		if (forward == BackNudge.BACK) {
			sRun++;
			noS = 0;
		} else {
			sRun = 0;
			noS = Math.min(noS + 1, 1000);
		}
		if (strafe != 0 && strafe == strafeWay) {
			strafeTicks++;
		} else if (strafe != 0) {
			strafeWay = strafe;
			strafeTicks = 1;
		} else {
			strafeWay = 0;
			strafeTicks = 0;
		}
	}

	/**
	 * The plan as the teacher's target on its heads (Teacher's sure targets; the strafe either way, the teacher's coin toss; the sprint
	 * only with W, as the Teacher has it; the swing only where it swings). Returns the heads written.
	 */
	public static int target(Plan p, float[] target) {
		if (!p.active()) {
			return 0;
		}
		int heads = 0;
		if ((p.heads & (1 << ActionSpace.FORWARD)) != 0) {
			heads |= put(target, ActionSpace.FORWARD, p.forward);
		}
		if ((p.heads & (1 << ActionSpace.STRAFE)) != 0) {
			heads |= p.strafe != 0 ? putEither(target, ActionSpace.STRAFE, 1, 2) : put(target, ActionSpace.STRAFE, 0);
		}
		if ((p.heads & (1 << ActionSpace.SPRINT)) != 0 && p.forward == 1) {
			heads |= put(target, ActionSpace.SPRINT, p.sprint);
		}
		if ((p.heads & (1 << ActionSpace.ATTACK)) != 0 && p.attack == 1) {
			heads |= put(target, ActionSpace.ATTACK, 1);
		}
		return heads;
	}

	private static int put(float[] target, int head, int choice) {
		int off = ActionSpace.OFFSETS[head];
		int n = ActionSpace.SIZES[head];
		float rest = (1f - Teacher.SURE) / (n - 1);
		for (int k = 0; k < n; k++) {
			target[off + k] = k == choice ? Teacher.SURE : rest;
		}
		return 1 << head;
	}

	private static int putEither(float[] target, int head, int a, int b) {
		int off = ActionSpace.OFFSETS[head];
		int n = ActionSpace.SIZES[head];
		float rest = (1f - Teacher.SURE) / (n - 2);
		for (int k = 0; k < n; k++) {
			target[off + k] = k == a || k == b ? Teacher.SURE / 2f : rest;
		}
		return 1 << head;
	}

	/**
	 * The techniques whose keys it leaves alone, told by the decider or taught by the teacher: the jump and crit techniques (the punish
	 * crit, the double and standing crits, the jump resets, K3, the jump techniques), K2's hold, C7's delayed hit and T8's hit on a riser.
	 * Their keys are the definition (B1 "you crit them on the way down", K2, C7 "delay your sprint hit", K3 "forced to hit you as you are
	 * falling"). The escapes C2/C3/C4 (the long S) and the stun starters T4-T7, C1 it takes over where it acts.
	 */
	public static boolean keeps(Technique t) {
		if (t == null) {
			return false;
		}
		return switch (t) {
			case B1, K2, K3, T1, T1A, T1B, T1C, T2, C6, C7, T8, T9, T9A, T9B, T9C, T10, T11A, T11B, T11C -> true;
			default -> false;
		};
	}

	/** The escapes it replaces where it acts: the long S out of their combo (C2, C3, C4; the user: "escaping combo still gets them two free hits"). */
	public static boolean replaces(Technique t) {
		return t == Technique.C2 || t == Technique.C3 || t == Technique.C4;
	}

	/**
	 * Run 124's counters, one side's: their runs of hits on it, what came after their hit by its distance, who hit first when they came into
	 * its reach with its sword ready, and the long S in their combo. Fed a tick at a time with the hits of the tick and what it pressed.
	 */
	public static final class Tally {
		/** Distance bands at their hit: under 2.0, 2.0-2.6 (EDGE_FROM), 2.6+ (the evidence's bands). */
		public static final double[] BANDS = {2.0, EDGE_FROM};
		/** Their runs of hits on it (each within COMBO_TICKS of the last, no hit of its own between): 2+, 3+, 5+, and the longest. */
		public int runs2;
		public int runs3;
		public int runs5;
		public int longest;
		/** After their hit, by band: [0] their next hit first, [1] its hit first, [2] neither within COMBO_TICKS. */
		public final int[][] after = new int[3][3];
		/** They came into its reach (from past OUT_EDGE) with its sword ready, outside its own combo: its hit first, theirs, both on one tick, neither. */
		public final int[] entries = new int[4];
		/** Their-combo windows (their hit until its hit or COMBO_TICKS), of them with a long S (LONG_S in a row), and its S ticks in them. */
		public int windows;
		public int longS;
		public int sTicks;

		private int row;
		private int sinceTaken = 1000;
		private int sinceDealt = 1000;
		private int afterBand = -1;
		private int afterAge;
		private int entryAge = -1;
		private boolean armed = true;
		private double lastReach = 99.0;
		private boolean window;
		private boolean windowLong;
		private int sRun;

		/**
		 * One tick.
		 *
		 * @param dealt       its hit landed this tick
		 * @param taken       their hit landed this tick
		 * @param reach       its eye to their box
		 * @param charge      its sword
		 * @param readyCharge its sword counts as ready from here
		 * @param back        it pressed S this tick
		 */
		public void tick(boolean dealt, boolean taken, double reach, float charge, float readyCharge, boolean back) {
			sinceTaken = Math.min(sinceTaken + 1, 1000);
			sinceDealt = Math.min(sinceDealt + 1, 1000);
			boolean ownComboBefore = sinceDealt <= COMBO_TICKS && sinceTaken > sinceDealt;
			// Their run of hits.
			if (row > 0 && (dealt || sinceTaken > COMBO_TICKS)) {
				closeRun();
			}
			if (taken) {
				row++;
				longest = Math.max(longest, row);
			}
			// What came after their last hit.
			if (afterBand >= 0) {
				afterAge++;
				if (taken) {
					after[afterBand][0]++;
					afterBand = -1;
				} else if (dealt) {
					after[afterBand][1]++;
					afterBand = -1;
				} else if (afterAge >= COMBO_TICKS) {
					after[afterBand][2]++;
					afterBand = -1;
				}
			}
			if (taken) {
				afterBand = reach < BANDS[0] ? 0 : reach < BANDS[1] ? 1 : 2;
				afterAge = 0;
			}
			// Into its reach with its sword ready: who hits first.
			if (entryAge >= 0) {
				entryAge++;
			}
			if (reach > OUT_EDGE) {
				armed = true;
			}
			if (entryAge < 0 && armed && lastReach > REACH && reach <= REACH && charge >= readyCharge && !ownComboBefore) {
				entryAge = 0;
				armed = false;
			}
			if (entryAge >= 0) {
				if (dealt || taken) {
					entries[dealt && taken ? 2 : dealt ? 0 : 1]++;
					entryAge = -1;
				} else if (entryAge >= COMBO_TICKS) {
					entries[3]++;
					entryAge = -1;
				}
			}
			lastReach = reach;
			// The long S in their combo.
			if (window && (dealt || sinceTaken > COMBO_TICKS)) {
				closeWindow();
			}
			if (taken && !window && !dealt) {
				window = true;
				windowLong = false;
				sRun = 0;
			}
			if (window && !taken) {
				sRun = back ? sRun + 1 : 0;
				sTicks += back ? 1 : 0;
				windowLong |= sRun >= LONG_S;
			}
			if (taken) {
				sinceTaken = 0;
			}
			if (dealt) {
				sinceDealt = 0;
			}
		}

		private void closeRun() {
			runs2 += row >= 2 ? 1 : 0;
			runs3 += row >= 3 ? 1 : 0;
			runs5 += row >= 5 ? 1 : 0;
			row = 0;
		}

		private void closeWindow() {
			windows++;
			longS += windowLong ? 1 : 0;
			window = false;
			windowLong = false;
			sRun = 0;
		}

		/** The fight is over: an open run and window are counted, the open outcomes dropped. */
		public void endFight() {
			if (row > 0) {
				closeRun();
			}
			if (window) {
				closeWindow();
			}
			sinceTaken = 1000;
			sinceDealt = 1000;
			afterBand = -1;
			entryAge = -1;
			armed = true;
			lastReach = 99.0;
		}

		/** The counts back to zero (the state of a fight going on is kept). */
		public void clear() {
			runs2 = runs3 = runs5 = longest = 0;
			for (int[] a : after) {
				java.util.Arrays.fill(a, 0);
			}
			java.util.Arrays.fill(entries, 0);
			windows = longS = sTicks = 0;
		}

		/** One line of the counts. */
		public String line() {
			return String.format(Locale.ROOT, "their runs of hits on it 2+ %d, 3+ %d, 5+ %d, longest %d; after their hit, their next / its next / neither within %d"
							+ " by distance: under 2.0 %s, 2.0-2.6 %s, 2.6+ %s; they came into its reach with its sword ready %d: its hit first %d, theirs %d,"
							+ " both %d, neither %d; their-combo windows %d, a long S (%d+ ticks) in %d, S ticks %d",
					runs2, runs3, runs5, longest, COMBO_TICKS, triple(after[0]), triple(after[1]), triple(after[2]), entries[0] + entries[1] + entries[2] + entries[3],
					entries[0], entries[1], entries[2], entries[3], windows, LONG_S, longS, sTicks);
		}

		private static String triple(int[] a) {
			int n = a[0] + a[1] + a[2];
			return String.format(Locale.ROOT, "%d/%d/%d (%s)", a[0], a[1], a[2], n == 0 ? "-" : String.format(Locale.ROOT, "%.0f%% theirs", 100.0 * a[0] / n));
		}
	}
}
