package dev.swordai.core.school;

import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Keys;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;

import java.util.Random;

/**
 * Run 97 (agent 39), the combo-hold teacher. The user, 14 Nov 14:50: "it needs to learn to hold combo make sure its opponent is designed
 * to teach it hold combo with hops lunge and upper cut and some regular sprint hit and random strafe with A D tapping and S tapping and
 * regular W tap". In its own combo (its last hit at most {@link #COMBO_GAP} ticks ago, none taken since) it holds the next hit with a hold
 * drawn at random for each follow-up, each timed as {@code docs/PVP-TECHNIQUES.md} has it:
 * <ul>
 * <li>{@link #GROUND_W}, {@link #GROUND_S}: part 0's sprint hit with a W-tap ("let go of W ... to restart the sprint") or an S-tap ("a
 * short press of S to control spacing (stay just out of their reach while staying in yours)"), then in from the edge of reach with the
 * sword back (T7: "before they are close enough to hit you back"; T3: combos are chained stuns);</li>
 * <li>{@link #STRAFE}: the same with A/D tapped side to side (T6a: "strafe side to side as you fight, so they miss");</li>
 * <li>{@link #HOP}: T11b, "let go of W and jump right after ... after the jump, hold W ... time your sprint hit as you fall from each jump,
 * right before you release W", taken off from outside their reach ("you stay outside their reach while holding them in a combo");</li>
 * <li>{@link #UPPERCUT}: T10, "jump, and sprint-hit the opponent at the peak of your jump ... it reaches someone who would otherwise be too
 * far away ... often combined with S right after the sprint hit": taken off only where the jump brings it to the edge of reach at the
 * peak, so that it does not rise into their reach;</li>
 * <li>{@link #LUNGE}: T11a, "sprint-jump forward and sprint-hit them as you fall ... extra hits on players who hold S out of a trade (C3)";
 * whenever they back out after its hit, the next hold is the lunge (T11c, "alternate hops and lunges whenever they hold S").</li>
 * </ul>
 * The hold is drawn again at every hit, so its hits do not come to one set rhythm (T9a: "most players combo to a set rhythm, so they are
 * not hard to jump-reset out of"); agent 39's ComboSim found an added wait of 2-3 ticks every other hit cost more combos (a stun ends
 * 12-20 ticks after the hit) than the jump resets it saved, and so did keeping out of their reach with the sword far from back. With
 * the sword not back it does not stand on top of them (part 0's S-tap). Distances are read as the network reads them, its view of them
 * {@link #seenLag} ticks late, moved on by their speed. Its keys are a copy target only (SchoolSession: in its own combo against the comboable bots, by the
 * outcome); it presses nothing for the network. Deterministic for a seed and the same ticks.
 */
public final class ComboHolder {
	public static final int GROUND_W = 0;
	public static final int GROUND_S = 1;
	public static final int STRAFE = 2;
	public static final int HOP = 3;
	public static final int UPPERCUT = 4;
	public static final int LUNGE = 5;
	public static final int METHODS = 6;
	public static final String[] NAMES = {"ground W-tap", "ground S-tap", "strafe taps (T6a)", "hop (T11b)", "uppercut (T10)", "lunge (T11a)"};
	/** The label of each hold, for the logs. */
	public static final Technique[] LABELS = {Technique.BASE, Technique.BASE, Technique.T6A, Technique.T11B, Technique.T10, Technique.T11A};
	/** How often each hold is drawn for a follow-up (the lunge also comes whenever they back out, see {@link #awayNow}). */
	// Run 97b (14 Nov ~21:55, the user: "I prefer uppercuts ... dont use hop when combo"): the hop share (0.16; 0 hits landed from hops in 262 updates)
	// goes to the uppercut (82% of its uppercut holds were followed by its own next hit first).
	// Run 104c (the user, 15 Nov 17:55, lists T11b hops among the skills to learn): hops 0 -> 0.1 (W-tap, S-tap 0.2 -> 0.15).
	public static final float[] WEIGHTS = {0.15f, 0.15f, 0.2f, 0.1f, 0.3f, 0.1f};
	/** In its own combo while its last hit is at most this old (SchoolSession.COMBO_GAP). */
	public static final int COMBO_GAP = 20;
	/** The sword comes back about this much a tick (Body.SWORD_TICKS 12.5). */
	static final float PER_TICK = 1f / Body.SWORD_TICKS;
	/** A strafe tap holds one side this many ticks (drawn). */
	static final int STRAFE_LEAST = 2;
	static final int STRAFE_MOST = 5;
	/** The swing: a charged sword (the game's line for a sprint hit's knockback is 0.9). */
	static final float SWING = 0.92f;
	/** Nearer than this with the sword not back, it steps back (S). */
	static final double TOO_NEAR = 1.6;
	/** Ticks after its hit is seen in which the view still shows them before the hit's knockback (their ping and the motion's first tick). */
	static final int STALE_TICKS = 3;
	/** Coming down at it: falling and closing at least this fast (a jump reset's sprint-jump comes in; a lifted one drifts away). */
	static final double FALL_AT_SPEED = 0.1;
	/**
	 * The hop's take-off (T11b step 1): the sword between these, so that it is back about the top of the jump (5-7 ticks on) and the hit
	 * comes on the way down (step 5); they are seen at least HOP_NEAREST away and not coming in faster than HOP_CLOSING, so that the rise
	 * is over before they are back in reach (T8: "if the opponent jumps at you and you hit them as they are rising ... a free stun").
	 */
	static final float HOP_LO = 0.40f;
	static final float HOP_HI = 0.56f;
	static final double HOP_NEAREST = 2.6;
	static final double HOP_CLOSING = 0.1;
	/**
	 * The uppercut's take-off (T10): the sword between these, so that it is back at the peak (about UP_TICKS on); from a stand after the
	 * S that follows its hit (no run-up: the sprint-jump's carry, about 1.9 blocks to the peak, brings it to the edge of reach there), with
	 * them seen between UP_NEAREST and UP_FARTHEST and not coming in (their flight from its hit), so that it rises out of their reach.
	 */
	static final int UP_TICKS = 6;
	static final float UP_LO = SWING - UP_TICKS * PER_TICK;
	static final float UP_HI = UP_LO + 0.12f;
	static final double UP_NEAREST = 2.4;
	static final double UP_FARTHEST = 4.4;
	static final double UP_CLOSING = 0.05;
	/** The lunge: the hit about this many ticks after the take-off (falling); the sprint-jump carries it about LUNGE_CARRY in that time. */
	static final int LUNGE_TICKS = 9;
	static final double LUNGE_CARRY = 2.7;
	/**
	 * They back out (C3): seen on the ground going away this fast (blocks a tick) beyond BACK_GROUND, from AWAY_FROM_TICK after its hit.
	 * Not by the distance in the air: seen 1-4 ticks late, a flight from its hit (or a trade's two flights apart) still shows them going
	 * away beyond 4-5 blocks after they have landed and turned back in (agent 39's ComboSim traces: lunges into a stunned bot walking in).
	 */
	static final double AWAY_SPEED = 0.05;
	static final int AWAY_FROM_TICK = 5;
	static final double BACK_GROUND = 3.3;

	private final Random rng = new Random();
	private final Random pick = new Random();
	private int seenLag;
	private int sinceMyHit = 1000;
	private int sinceTheirHit = 1000;
	private int method = GROUND_W;
	private int tapLen;
	private int strafeDir = 1;
	private int strafeLeft;
	private boolean strafeAny;
	private int ownStrafe;
	private int ownHeld;
	private boolean tookOff;
	private final Keys keys = new Keys();

	public void start(long seed) {
		rng.setSeed(seed ^ 0xC0B0L);
		pick.setSeed(seed ^ 0x401DL);
		sinceMyHit = 1000;
		sinceTheirHit = 1000;
		method = GROUND_W;
		tapLen = 0;
		strafeLeft = 0;
		strafeAny = false;
		ownStrafe = 0;
		ownHeld = 0;
		tookOff = false;
		keys.clear();
	}

	/**
	 * The strafe pressed on the last tick by the one it teaches (-1 A, 1 D, 0 none), before {@link #tick}: the strafe hold taps from that
	 * side to the other after STRAFE_LEAST to STRAFE_MOST ticks a side (T6a: "strafe side to side as you fight, so they miss").
	 */
	public void ownStrafe(int axis) {
		int a = Integer.signum(axis);
		if (a != 0 && a == ownStrafe) {
			ownHeld++;
		} else {
			if (a != 0 && ownStrafe != 0) {
				// The tap to the other side came: the length of this one.
				strafeLeft = STRAFE_LEAST + rng.nextInt(STRAFE_MOST - STRAFE_LEAST + 1);
			}
			ownHeld = a != 0 ? 1 : 0;
		}
		ownStrafe = a;
	}

	/** Ticks the network's view of the other is behind the game this fight (SchoolSession's seen lag). */
	public void seenLag(int ticks) {
		seenLag = Math.max(0, ticks);
	}

	/** Its own combo is alive: its last hit at most COMBO_GAP ticks ago and no hit taken since (as of the last tick). */
	public boolean active() {
		return sinceMyHit <= COMBO_GAP && sinceMyHit < sinceTheirHit;
	}

	/** The hold of this follow-up ({@link #GROUND_W} ... {@link #LUNGE}). */
	public int method() {
		return method;
	}

	/** Its keys on the last tick (cleared outside its own combo). */
	public Keys keys() {
		return keys;
	}

	/**
	 * One tick: the fight as the network sees it and what happened since the last tick. Returns whether its own combo is alive, with its
	 * keys in {@link #keys()}.
	 */
	public boolean tick(Duel d, Events e) {
		keys.clear();
		sinceMyHit = e.iHit != Events.NONE ? 0 : Math.min(sinceMyHit + 1, 1000);
		sinceTheirHit = e.theyHit != Events.NONE ? 0 : Math.min(sinceTheirHit + 1, 1000);
		if (e.iHit != Events.NONE && e.theyHit == Events.NONE) {
			nextHold();
		}
		boolean on = active();
		if (on) {
			if (method != LUNGE && !tookOff && awayNow(d)) {
				// They back out (T11a "extra hits on players who hold S out of a trade"; T11c).
				method = LUNGE;
			}
			hold(d, keys);
			keys.technique = LABELS[method];
		}
		return on;
	}

	/**
	 * Its keys of the last tick as copy targets in Teacher's form (one key sure, or two keys alike where either will do, Teacher#aim) on
	 * the movement heads: W where it holds W, S where the S-tap is the hold, else "not W" (stop or S); A/D on the strafe hold (either side
	 * from no strafe); the sprint key while W; the jump (its take-offs, no jump on every other tick). Returns the head bits written, 0
	 * outside its own combo. The swing is left to the teachers that have it.
	 */
	public int target(float[] target) {
		if (!active()) {
			return 0;
		}
		int heads;
		if (keys.forward > 0) {
			heads = put(target, ActionSpace.FORWARD, 1);
		} else if (keys.forward < 0 && method == GROUND_S && sinceMyHit <= tapLen) {
			heads = put(target, ActionSpace.FORWARD, 2);
		} else {
			heads = putEither(target, ActionSpace.FORWARD, 0, 2);
		}
		if (method == STRAFE) {
			heads |= strafeAny ? putEither(target, ActionSpace.STRAFE, 1, 2) : put(target, ActionSpace.STRAFE, keys.strafe < 0 ? 1 : 2);
		}
		if (keys.forward > 0) {
			heads |= put(target, ActionSpace.SPRINT, keys.sprint ? 1 : 0);
		}
		heads |= put(target, ActionSpace.JUMP, keys.jump ? 1 : 0);
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
	 * Eye to their box now, read on the near side: what it sees, moved on by their speed toward it over the ticks its view is late (never
	 * by their going away: a flight from its hit turns into a walk back in, which the late view does not show yet).
	 */
	private double reachNow(Duel d) {
		return Math.max(0.0, d.reach - seenLag * Math.max(0.0, d.theyClose));
	}

	/** A hit of its own landed: the next follow-up's hold, drawn. */
	private void nextHold() {
		int last = method;
		float u = pick.nextFloat();
		int m = 0;
		float acc = 0f;
		for (; m < METHODS - 1; m++) {
			acc += WEIGHTS[m];
			if (u < acc) {
				break;
			}
		}
		method = m;
		// After an uppercut the S comes right after the hit (T10: "often combined with S right after the sprint hit").
		if (last == UPPERCUT && (method == GROUND_W || method == STRAFE)) {
			method = GROUND_S;
		}
		tapLen = 1 + (method == GROUND_S ? rng.nextInt(2) : 0);
		strafeDir = rng.nextBoolean() ? 1 : -1;
		strafeLeft = STRAFE_LEAST + rng.nextInt(STRAFE_MOST - STRAFE_LEAST + 1);
		tookOff = false;
	}

	/**
	 * They back out (C3, S held): seen on the ground going away out of reach after its hit. Not their flight from its hit's knockback (in
	 * the air), nor a stunned one's S-tap at the edge of its spacing (within reach).
	 */
	private boolean awayNow(Duel d) {
		return sinceMyHit >= AWAY_FROM_TICK && d.theyClose < -AWAY_SPEED && d.them.onGround && d.reach > BACK_GROUND;
	}

	private void hold(Duel d, Keys k) {
		Body me = d.me;
		k.taught = Keys.KEY_MOVE | Keys.KEY_SPRINT | Keys.KEY_JUMP | Keys.KEY_ATTACK | (method == STRAFE ? Keys.KEY_STRAFE : 0);
		if (method == STRAFE) {
			// A/D tapped: its own side for the tap's ticks, then the other; from no strafe, either side (a coin toss for the keys).
			strafeAny = ownStrafe == 0;
			k.strafe = strafeAny ? strafeDir : ownHeld >= strafeLeft ? -ownStrafe : ownStrafe;
		}
		if (tookOff) {
			if (!me.onGround) {
				air(d, k);
				return;
			}
			// Landed from its hop, uppercut or lunge without the hit: in from the edge on the ground.
			method = GROUND_W;
			tookOff = false;
			tapLen = 0;
		}
		double r = reachNow(d);
		switch (method) {
			case HOP -> {
				boolean safe = r >= HOP_NEAREST && d.theyClose <= HOP_CLOSING;
				if (me.charge >= HOP_LO && me.charge <= HOP_HI && me.onGround && safe) {
					// Step 1: "while sprinting forward, let go of W and jump right after" (straight up: W off, so no sprint-jump).
					k.jump = true;
					k.forward = 0;
					tookOff = true;
					return;
				}
				if (me.charge > HOP_HI || me.charge >= HOP_LO && !safe) {
					// The moment went by, or they are in reach or coming in (it would rise into their reach): on the ground.
					method = GROUND_W;
					tapLen = 0;
					ground(d, k);
					return;
				}
				// Before the take-off: the W-tap after its hit, then in at a sprint, never on top of them.
				k.forward = sinceMyHit <= tapLen ? 0 : r < TOO_NEAR ? -1 : 1;
				k.sprint = k.forward > 0;
			}
			case UPPERCUT -> {
				boolean safe = r >= UP_NEAREST && r <= UP_FARTHEST && d.theyClose <= UP_CLOSING;
				if (me.charge >= UP_LO && me.charge <= UP_HI && me.onGround && safe) {
					// The sprint-jump at them, the sword due back at the peak.
					k.jump = true;
					k.forward = 1;
					k.sprint = true;
					tookOff = true;
					return;
				}
				if (me.charge > UP_HI || me.charge >= UP_LO && !safe) {
					method = GROUND_W;
					tapLen = 0;
					ground(d, k);
					return;
				}
				// Before the take-off: S right after its hit, then a stand (no run-up), S if they come too near.
				k.forward = sinceMyHit <= tapLen + 1 || r < TOO_NEAR ? -1 : 0;
			}
			case LUNGE -> {
				if (!awayNow(d)) {
					// Not (or no longer) going away: the lunge waits on the ground hold (it is for "players who hold S out of a trade").
					ground(d, k);
					return;
				}
				// Where the lunge's falling hit would be, with them going on as they move (away: theyClose below zero).
				double atHit = d.reach - LUNGE_CARRY - LUNGE_TICKS * d.theyClose;
				float lungeCharge = SWING - LUNGE_TICKS * PER_TICK;
				if (me.charge >= lungeCharge && me.onGround && me.sprinting && atHit <= Duel.REACH - 0.2) {
					// Sprint-jump forward after them; the sprint hit as it falls.
					k.jump = true;
					k.forward = 1;
					k.sprint = true;
					tookOff = true;
					return;
				}
				k.forward = 1;
				k.sprint = true;
				k.attack = me.charge >= SWING && d.reach <= Duel.REACH && me.sprinting;
			}
			default -> ground(d, k);
		}
	}

	/** In the air from its own take-off: W and the sprint (the hop's step 2), and the sprint hit at the peak (T10) or falling (T11a/b). */
	private void air(Duel d, Keys k) {
		Body me = d.me;
		k.forward = 1;
		k.sprint = true;
		// K2 here too: not at one coming down at it.
		boolean ready = me.charge >= SWING && d.reach <= Duel.REACH && me.sprinting && !(d.them.falling() && d.theyClose > FALL_AT_SPEED);
		if (method == UPPERCUT) {
			// At the peak; past it, falling, the hit is still a sprint cut (T11).
			k.attack = ready && me.vy < 0.12;
			return;
		}
		if (me.rising()) {
			return;
		}
		k.attack = ready;
		if (method == HOP && !k.attack && me.vy < -0.3 && !(me.charge >= SWING - 2f * PER_TICK && reachNow(d) <= Duel.REACH + 0.4)) {
			// Step 3: W let go just before landing (no hit coming on this fall).
			k.forward = 0;
			k.sprint = false;
		}
	}

	/**
	 * The ground hold: the W-tap or S-tap after the hit, then in at a sprint, but not nearer than TOO_NEAR before the sword is back (the
	 * S-tap's "stay just out of their reach while staying in yours"), nor at one coming down at it (K2); the sprint hit once the sword is
	 * back and they are in reach.
	 */
	private void ground(Duel d, Keys k) {
		Body me = d.me;
		if (sinceMyHit <= tapLen) {
			// Right after the hit: the W-tap (W let go) or the S-tap.
			k.forward = method == GROUND_S ? -1 : 0;
			return;
		}
		double r = reachNow(d);
		boolean ready = me.charge >= SWING;
		// Its hit is seen as late as they are: for STALE_TICKS after it the view still shows them where they were hit, before the
		// knockback, so the spacing waits (backing off from where they no longer are cost it the follow-up's tempo in the simulation).
		boolean stale = sinceMyHit <= STALE_TICKS;
		// K2: "don't knockback-hit someone who is falling ... wait until they are on the ground or jumping up". One coming down AT it (a
		// jump reset's crit, T9: "it gets crits") is kept out of reach until it lands; not one coming down from its hit's lift and going
		// away, whom the late view shows falling for a few ticks after it has landed.
		boolean theyFallAtIt = d.them.falling() && d.theyClose > FALL_AT_SPEED;
		if (!stale && !ready && r < TOO_NEAR || theyFallAtIt && r < Duel.REACH + 0.3) {
			k.forward = -1;
		} else {
			k.forward = 1;
			k.sprint = true;
		}
		k.attack = ready && d.reach <= Duel.REACH && me.sprinting && !theyFallAtIt;
	}
}
