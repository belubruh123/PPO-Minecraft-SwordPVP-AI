package dev.swordai.core.school;

import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.PvpPlayer;
import dev.swordai.core.rl.ActionSpace;

/**
 * Run 123 (agent 57, 16 Nov ~22:45), its own combo held by spacing (COMBO_SPACING). The user, 16 Nov 22:40: "I want it to have
 * ultimate consistency to hold combo over 10 hits easily ... prioritize and use max method".
 *
 * <p>Why (every tick of the user's real rounds, mostly run35): its sprint hit landed from under 2.0 blocks was followed by THEIR hit
 * 72% of the time (110 hits), from 2.0-2.6 54% (103), from 2.6+ only 15% (26); its crits 62-76% at any distance. When theirs came next,
 * 172 of 272 came within 5 ticks of its hit (hit through: they were still in reach) and 91 of 272 jump-reset its hit. Its combos: 2+
 * hits 44, 3+ 18, 5+ 5, 10+ 1, mean 3.0, and 33-35 of about 43 ended with their hit. So its combos break because it hits from too
 * close. The document (docs/PVP-TECHNIQUES.md): T3 a stun is "a sprint hit that knocks the opponent far enough away that they can't
 * hit you back", combos are a chain of stuns; T7 "sprint-hit the opponent before they are close enough to hit you back ... running at
 * full sprint toward them"; T4 "too close, and they hit you through"; part 0's S-tap "a short press of S to control spacing (stay just
 * out of their reach while staying in yours)" and W-tap "restart the sprint so the next hit is a sprint hit again"; C7 against a jump
 * reset "delay your sprint hit"; K2 "don't knockback-hit someone who is falling, whether during a combo ... wait until they are on the
 * ground or jumping up"; and "with good spacing you can still stun and combo a player who jump-resets ... S-tapping helps".
 *
 * <p>From its hit that landed ({@link #hitDealt}) until their hit lands ({@link #hitTaken}), they are out of the fight range once the
 * knockback has settled, or {@link #HOLD_MOST} ticks went by, it holds W/S, the sprint, the jump and the swing (A/D and the camera stay
 * the network's and the rules'):
 * <ol>
 * <li>the tick after its hit, the W-tap: W and the sprint let go ({@link #W_TAP});</li>
 * <li>while its sword comes back: S where they are nearer than {@link #EDGE_LO} (not on the ticks its view still shows them where they
 * were hit, {@link #stale}), else the approach that brings it to {@link #EDGE_TARGET} as the sword is back: in at a sprint
 * ({@link #CHASE}) when even a full sprint would not be early, standing ({@link #WAIT}) when standing is not early, S when they come in
 * faster than its sword comes back; in the last {@link #SPRINT_LEAD} ticks W and the sprint again ({@link #RUN_UP}), so that the hit is
 * a sprint hit (the game ends the sprint with the hit and starts it again from W and the sprint key a tick later);</li>
 * <li>the swing ({@link #SWING}) the tick the sword is at the ready level, the game's pick on them (the whiff guard), not held by the
 * session's guards, them not falling by the time the hit lands (K2), itself sprinting, and them at the edge, {@link #SWING_FROM} to
 * {@link Duel#REACH}; nearer only where waiting is worse: they can hit it now and come in (their sword ready, it in their reach), they go
 * away, or they rise early in a jump (T8, C7: they jumped before the hit);</li>
 * <li>out of reach with the sword ready: in at a full sprint, the swing the tick they are in reach (T7); falling at it: S (K2: kept out
 * of reach until they land); never a jump.</li>
 * </ol>
 * Distances are the session's: eye to their box ({@link Duel#reach}), as the game measures reach and the pick reaches 3.0. Deterministic.
 */
public final class ComboSpacing {
	/** Run 123's switch for the sessions (FightSession, SchoolSession): false = no controller, no target, as before. */
	public static final boolean ON = true;

	/** What it did on a tick (counters, the logs): not holding, the W-tap, S, standing, in at a sprint, the run-up, the swing, held (K2 or a guard). */
	public static final int OFF = 0;
	public static final int W_TAP = 1;
	public static final int S_TAP = 2;
	public static final int WAIT = 3;
	public static final int CHASE = 4;
	public static final int RUN_UP = 5;
	public static final int SWING = 6;
	public static final int HELD = 7;
	public static final int DECISIONS = 8;
	public static final String[] NAMES = {"off", "W-tap", "S", "stand", "chase", "run-up", "swing", "held (K2/guard)"};

	/** The heads it holds: W/S, the sprint, the jump (never pressed) and the swing. */
	public static final int HEADS = ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT, ActionSpace.JUMP, ActionSpace.ATTACK);
	/**
	 * Its bit in a round file's rules (RoundRecorder, 'H'): above the 23 Oct rules' bits (Fighter#rules, 0-13), so that the two never
	 * meet.
	 */
	public static final int RULE_BIT = 1 << 20;

	/** The swing from here to the reach: the evidence's band where their hit came next 15% (2.0-2.6: 54%, under 2.0: 72%). */
	public static final double SWING_FROM = 2.6;
	/** S below this (the task's "back to the edge of reach, about 2.7-3.0"). */
	public static final double EDGE_LO = 2.7;
	/** Where the approach aims to be as its sword is back: the middle of the edge band 2.7-3.0. */
	public static final double EDGE_TARGET = 2.85;
	/**
	 * A sprint's speed on the ground, blocks a tick: the game's sprint push 0.13 a tick (LivingEntity: 0.1 walking, x1.3 sprinting) over
	 * ground friction 0.546, 0.13 / (1 - 0.546) = 0.286 (Config's "a run closes on that one by 0.28"; tools/combosim's physics).
	 */
	public static final double SPRINT_SPEED = 0.28;
	/** A walk's (and S's) speed, 0.1 / (1 - 0.546) = 0.22: the most their walking away is counted (a flight from its hit slows and lands). */
	public static final double WALK_SPEED = 0.22;
	/**
	 * The first tick of a sprint from a stand moves this far (the game's push, 0.13): a run-up's swing on the next tick is made from up to
	 * this much nearer than SWING_FROM, so that W pressed again at the edge does not end in an S and a lost sprint.
	 */
	public static final double FIRST_STEP = 0.13;
	/** The last ticks before its sword is back with W and the sprint down, so that it is sprinting when the sword is back. */
	public static final int SPRINT_LEAD = 2;
	/**
	 * Two variants tried in tools/combosim (300 fights each against the stunned comboable bot, the combo breaker, and the breaker with its
	 * "too close" swing back at 2.6 as in the real rounds instead of the code's 1.5): letting them come in with the sword ready (WAIT, the
	 * run-up SPRINT_LEAD ticks before they are in reach), and in at a sprint while the view still shows them where they were hit (ComboHolder's
	 * ground hold). Both off: 10+ runs 72 / 8 / 1 against 61 / 0 / 0 with both on, 5+ 107 / 61 / 38 against 110 / 37 / 14 (the chase walked
	 * into a jump reset and a bot walking back in).
	 */
	public static final boolean WAIT_FOR_COMING = false;
	public static final boolean STALE_CHASE = false;
	/** The stale ticks after its hit beyond the seen lag: the tick its hit reaches them and their knockback's first tick (see #stale). */
	public static final int STALE_EXTRA = 2;
	/** Run-up ticks in a row after which the swing is made sprinting or not (the sprint comes the tick after W; two ticks without it: none coming). */
	public static final int RUN_UP_MOST = 2;
	/** The W-tap's ticks right after its hit (ComboHolder's W-tap: 1). */
	public static final int W_TAP_TICKS = 1;
	/** Out of the fight range: farther than this (SchoolSession.JUMP_IN_REOPEN; the user, 14 Nov: "run away or back out ... 5-6 block"). */
	public static final double FIGHT_RANGE = 5.0;
	/** ... once its hit is this old, the knockback settled (SchoolSession.REOPEN_QUIET, run 121: a flight past 5 blocks is not a back-out). */
	public static final int RANGE_QUIET = 20;
	/** No hit of its own for this long: its combo is over (run 121's re-engaging window ends at 40 ticks after the last hit). */
	public static final int HOLD_MOST = 40;
	/** Coming down at it: falling and closing at least this fast, blocks a tick (ComboHolder.FALL_AT_SPEED). */
	public static final double FALL_AT_SPEED = 0.1;
	/** Kept this far beyond its reach while one falls at it (ComboHolder: REACH + 0.3). */
	public static final double FALL_MARGIN = 0.3;
	/** Going away (or coming in): faster than this, blocks a tick (ComboHolder.AWAY_SPEED). */
	public static final double AWAY_SPEED = 0.05;

	private boolean on;
	private int sinceHit = 1000;
	private int decision = OFF;
	private int lastDecision = OFF;
	private int runUpTicks;
	private int forward;
	private boolean sprint;
	private boolean attack;
	/** Ticks by decision, the combos it held (started) and how they ended, since the last {@link #takeCounts}. */
	private final long[] ticks = new long[DECISIONS];
	private long started;
	private long endedByHit;
	private long endedByRange;
	private long endedByTime;

	/** A new fight or a new opponent: nothing held. */
	public void reset() {
		on = false;
		sinceHit = 1000;
		decision = OFF;
		lastDecision = OFF;
		forward = 0;
		sprint = false;
		attack = false;
	}

	/** Its hit landed (the session's count: its click with them under the crosshair in reach). Its combo goes on, or starts. */
	public void hitDealt() {
		if (!on) {
			started++;
		}
		on = true;
		sinceHit = 0;
	}

	/** Their hit landed (a trade too): its combo is over. */
	public void hitTaken() {
		if (on) {
			endedByHit++;
		}
		on = false;
		decision = OFF;
	}

	/** Whether it held the keys on the last {@link #tick}. */
	public boolean active() {
		return decision != OFF;
	}

	/** What it did on the last tick ({@link #OFF} ... {@link #HELD}). */
	public int decision() {
		return decision;
	}

	/** Ticks since its last hit (1 on the first tick after it). */
	public int sinceHit() {
		return sinceHit;
	}

	/**
	 * One tick, before the keys are pressed.
	 *
	 * @param d         the fight as the session sees it (its view of them lagged, as the network's)
	 * @param ready     the charge a swing waits for (the rules' line: Config.ruleSwingCharge, online ruleSwingChargeOnline; at least
	 *                  {@link Body#CHARGED})
	 * @param pickOn    the game's pick is on them, in reach (the whiff guard)
	 * @param swingHeld the session's guards hold the swing this tick (the jump-in guard)
	 * @param k2Lag     ticks from what is seen to the hit landing (seen lag and the click), for K2 ({@link PvpPlayer#fallingByTheHit})
	 * @param seenLag   ticks its view of them is behind (their knockback shows that much after its hit)
	 * @return whether it holds the keys this tick; then {@link #press} and {@link #target} give them
	 */
	public boolean tick(Duel d, float ready, boolean pickOn, boolean swingHeld, int k2Lag, int seenLag) {
		lastDecision = decision;
		decision = OFF;
		forward = 0;
		sprint = false;
		attack = false;
		if (!on) {
			return false;
		}
		sinceHit = Math.min(sinceHit + 1, 1000);
		if (sinceHit > HOLD_MOST) {
			on = false;
			endedByTime++;
			return false;
		}
		if (sinceHit >= RANGE_QUIET && d.reach > FIGHT_RANGE && d.them.onGround) {
			on = false;
			endedByRange++;
			return false;
		}
		decide(d, Math.max(ready, Body.CHARGED), pickOn, swingHeld, k2Lag, seenLag);
		ticks[decision]++;
		return true;
	}

	/**
	 * Its view of them has not caught up with its hit yet: they still show where they were hit (the seen lag, the tick the knockback
	 * reaches them on the server, and its first tick of motion; ComboHolder.STALE_TICKS 3 at a lag of 1).
	 */
	private boolean stale(int seenLag) {
		return sinceHit <= Math.max(0, seenLag) + STALE_EXTRA;
	}

	private void decide(Duel d, float ready, boolean pickOn, boolean swingHeld, int k2Lag, int seenLag) {
		Body me = d.me;
		Body them = d.them;
		// As seen: the distance the game's pick and the evidence's bands go by.
		double r = d.reach;
		// Where they are now (ComboHolder#reachNow): seen seenLag ticks late, moved on by their speed toward it; never by their going away
		// (a flight from its hit turns into a walk back in that the late view does not show yet). The spacing goes by this.
		double rc = Math.max(0.0, r - Math.max(0, seenLag) * Math.max(0.0, d.theyClose));
		if (sinceHit <= W_TAP_TICKS) {
			// The W-tap: "let go of W (or re-press it) between hits to restart the sprint".
			set(W_TAP, 0, false);
			return;
		}
		boolean stale = stale(seenLag);
		// K2, as the session's guard reads it: in the air and falling by the time the hit gets there.
		boolean falling = PvpPlayer.fallingByTheHit(them, k2Lag);
		boolean fallAtIt = them.falling() && d.theyClose > FALL_AT_SPEED;
		boolean goingAway = d.theyClose < -AWAY_SPEED;
		boolean comingIn = d.theyClose > AWAY_SPEED;
		boolean theyCanHit = them.charge >= Body.CHARGED && d.inTheirReach();
		if (me.charge >= ready) {
			boolean committed = lastDecision == RUN_UP;
			boolean spaced = r >= SWING_FROM - (committed ? FIRST_STEP : 0.0) && r <= Duel.REACH;
			// Nearer than the edge only where waiting is worse: their hit is there now (T4: the first hit), they go away (it would lose
			// them), or they rise early in their jump (T8; C7: they jumped before the hit, "in the air when your hit lands").
			boolean nearNow = r < SWING_FROM && (theyCanHit && comingIn || goingAway || them.rising() && !falling);
			boolean free = pickOn && !swingHeld && !falling;
			if (free && (spaced || nearNow)) {
				if (me.sprinting || runUpTicks >= RUN_UP_MOST) {
					// (A run-up that brought no sprint, as with too little food to sprint, does not hold the swing back for ever.)
					set(SWING, 1, true);
					attack = true;
				} else {
					// Not sprinting (an S, a stand): W and the sprint again, the swing next tick (T7: "running at full sprint").
					set(RUN_UP, 1, true);
				}
				return;
			}
			if (fallAtIt && rc < Duel.REACH + FALL_MARGIN) {
				// K2: one coming down at it is kept out of reach until it lands.
				set(HELD, -1, false);
				return;
			}
			if (r > Duel.REACH || !pickOn && r >= SWING_FROM) {
				// Out of reach (or not under the crosshair yet at the edge): in at a full sprint, the swing the tick they are in reach (T7).
				// With them coming in, they are let come and the run-up made SPRINT_LEAD ticks before they are in reach: both running in
				// at full sprint, the late view shows them a lag's run farther than they are, and the hit lands from too close.
				if (WAIT_FOR_COMING && comingIn && rc > Duel.REACH && (rc - Duel.REACH) / d.theyClose > SPRINT_LEAD) {
					set(WAIT, 0, false);
				} else {
					set(CHASE, 1, true);
				}
				return;
			}
			if (falling || swingHeld) {
				// K2 or a guard holds the swing: the edge kept, W again as they come down so that the hit is a sprint hit.
				if (rc < EDGE_LO && !stale && !goingAway) {
					set(HELD, -1, false);
				} else {
					set(HELD, r >= EDGE_TARGET ? 1 : 0, r >= EDGE_TARGET);
				}
				return;
			}
			// In reach, nearer than the edge: S back to it (S-tap), unless the view still shows them where they were hit or they go away.
			if (stale || goingAway) {
				set(WAIT, 0, false);
			} else {
				set(S_TAP, -1, false);
			}
			return;
		}
		// Its sword is coming back: at the edge as it is back.
		int t = (int) Math.ceil((ready - me.charge) * Body.SWORD_TICKS - 1e-4);
		if (!stale && !goingAway && (rc < EDGE_LO || fallAtIt && rc < Duel.REACH + FALL_MARGIN)) {
			// The S-tap: "stay just out of their reach while staying in yours"; T6: S so their swing misses.
			set(S_TAP, -1, false);
			return;
		}
		if (stale && STALE_CHASE) {
			// The view still shows them where they were hit; their flight is coming (ComboHolder: in at a sprint on these ticks). A jump
			// reset (they stay near) shows a tick or two later, and the S-tap above takes over.
			set(CHASE, 1, true);
			return;
		}
		double their = Math.max(-WALK_SPEED, Math.min(SPRINT_SPEED, d.theyClose));
		if (rc - t * (SPRINT_SPEED + their) >= EDGE_TARGET) {
			// Even a full sprint from now is not early: in after them (they flew, or back out).
			set(CHASE, 1, true);
			return;
		}
		if (t <= SPRINT_LEAD && rc >= EDGE_LO) {
			// The run-up: sprinting again as the sword is back (the W-tap's "press again").
			set(RUN_UP, 1, true);
			return;
		}
		if (stale || goingAway || rc - t * Math.max(0.0, their) >= EDGE_TARGET) {
			// Standing is not early: let them come (or land).
			set(WAIT, 0, false);
			return;
		}
		// They come in faster than its sword comes back: S to keep the edge.
		set(S_TAP, -1, false);
	}

	private void set(int what, int fwd, boolean run) {
		runUpTicks = what == RUN_UP ? runUpTicks + 1 : 0;
		decision = what;
		forward = fwd;
		sprint = run && fwd > 0;
	}

	/** Its keys into {@code action} (W/S, the sprint, no jump, the swing); the other heads are left as they are. */
	public void press(int[] action) {
		action[ActionSpace.FORWARD] = forward > 0 ? 1 : forward < 0 ? 2 : 0;
		action[ActionSpace.SPRINT] = sprint ? 1 : 0;
		action[ActionSpace.JUMP] = 0;
		action[ActionSpace.ATTACK] = attack ? 1 : 0;
	}

	/** Its keys as sure copy targets on {@link #HEADS} (Teacher's form, one choice 1, the rest 0); returns {@link #HEADS}. */
	public int target(float[] target) {
		sure(target, ActionSpace.FORWARD, forward > 0 ? 1 : forward < 0 ? 2 : 0);
		sure(target, ActionSpace.SPRINT, sprint ? 1 : 0);
		sure(target, ActionSpace.JUMP, 0);
		sure(target, ActionSpace.ATTACK, attack ? 1 : 0);
		return HEADS;
	}

	private static void sure(float[] target, int head, int choice) {
		int off = ActionSpace.OFFSETS[head];
		for (int k = 0; k < ActionSpace.SIZES[head]; k++) {
			target[off + k] = k == choice ? 1f : 0f;
		}
	}

	/** W (+1), S (-1) or neither on the last tick. */
	public int forward() {
		return forward;
	}

	public boolean sprint() {
		return sprint;
	}

	public boolean attack() {
		return attack;
	}

	/**
	 * "ticks: W-tap 12, S 40, ... ; combos held 9 (ended by their hit 5, out of range 2, time 1)", and the counts start again. Ticks are
	 * the ticks it held the keys.
	 */
	public String takeCounts() {
		String s = counts();
		java.util.Arrays.fill(ticks, 0);
		started = endedByHit = endedByRange = endedByTime = 0;
		return s;
	}

	/** {@link #takeCounts}'s text, the counts kept (the user's fight summary, read again and again). */
	public String counts() {
		StringBuilder b = new StringBuilder("ticks:");
		for (int i = 1; i < DECISIONS; i++) {
			b.append(' ').append(NAMES[i]).append(' ').append(ticks[i]).append(i + 1 < DECISIONS ? "," : "");
		}
		b.append("; combos held ").append(started).append(" (ended by their hit ").append(endedByHit).append(", out of range ").append(endedByRange)
				.append(", time ").append(endedByTime).append(')');
		return b.toString();
	}

	/** Ticks it held the keys since the last {@link #takeCounts} (all decisions but OFF). */
	public long heldTicks() {
		long n = 0;
		for (int i = 1; i < DECISIONS; i++) {
			n += ticks[i];
		}
		return n;
	}
}
