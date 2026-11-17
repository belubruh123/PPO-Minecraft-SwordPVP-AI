package dev.swordai.core.school;

import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.BackNudge;

import java.util.Locale;
import java.util.Random;

/**
 * Run 125 (agent 59, 17 Nov ~21:00), the combo-hold drill (COMBO_DRILL). The user, 17 Nov 20:50: "Why didn't you purposely let the bot
 * be stunned and then just purposely let the AI have a chance to combo it and teach it to hold the combo perfectly".
 *
 * <p>Why (the user's real rounds, mostly run35): of its 44 chains of 2+ hits 18 reached 3, 5 reached 5, 1 reached 10 (mean about 3),
 * and 35 ended with the opponent's hit. After its sprint hit from under 2.0 blocks (eye to box) the human hit back next 72%, from
 * 2.0-2.6 54%, from 2.6+ 15%; their hit came within 5 ticks in 172 of 272 (hit through while still in reach), and they jump-reset its
 * hit in 91 of 272. The training bots let close follow-ups through (they punished only hits under about 1.5), so close-range combo
 * habits looked fine in training. Run 123's scripted controller (ComboSpacing) held ready swings while it waited for the edge and lost
 * 14-17 HP a fight: no rule presses keys here, the network plays the drill itself.
 *
 * <p>The drill: a training fight that starts the tick after its own sprint hit landed (the document's T3 stun, "a sprint hit that
 * knocks the opponent far enough away that they can't hit you back"): the bot knocked back with its hurt time running, the AI's sword
 * just swung, both on the ground facing each other ({@link #startState}, put back as a scene is). The bot ({@link #victim}) is a person
 * being comboed (Human's limits, reactions spread per reaction): it flies out with S and a jump (C3), jump-resets the hit (T9/C5,
 * {@link #START_RESET_SHARE} of the stuns and its T9 chance after), strafes, walks back in, and swings back as soon as the AI is in its
 * reach with its sword ready, so a follow-up from too close is hit back and one from the edge mostly is not (calibrated in
 * ComboDrillTest against the real rounds). The chain ({@link Chain}): each further hit pays more ({@link #hitReward}), the bot's hit
 * landing costs {@link #BREAK_COST} and ends the drill, as does the bot out of the fight range for {@link #OUT_TICKS} ticks
 * ({@link #DROP_COST}). The light teacher ({@link #target}, advantage-weighted like the combo-hold teacher's pull): W-tap after its hit,
 * S while the sword comes back if nearer than {@link #BACK_WITHIN}, W and the sprint to chase or to come in for the next sprint hit, no
 * jump; the swing stays the ordinary fight's swing lesson (sword ready, the game's pick on them: never held).
 *
 * <p>Deterministic for a seed.
 */
public final class ComboDrill {
	/** Run 125's switch: false = no drills (SchoolSession), everything as before. */
	public static final boolean ON = true;
	/** The share of training fights that are drills (SchoolSession draws it before the scenes and the full fights). */
	public static final float SHARE = 0.25f;

	// ---- the start state: the tick after its sprint hit -------------------------------------------------------------------------
	/** The scene layout of SceneRecorder (server): 14 numbers per fighter, the agent's at 0 and the bot's at {@link #BODY}. */
	public static final int BODY = 14;
	public static final int X = 0, Y = 1, Z = 2, VX = 3, VY = 4, VZ = 5, YAW = 6, PITCH = 7, TICKER = 8, HEALTH = 9, HURT = 10, INVUL = 11,
			FALL = 12, SPRINT = 13;
	/** Its stun's distance (eye to their box at the swing): a sprint hit from the edge of reach, the document's T3/T7 stun. */
	public static final double STUN_LEAST = 2.3;
	public static final double STUN_MOST = 3.0;
	/** Half a hitbox: the middles are this much farther apart than eye to box, on the line between them. */
	static final double HALF_WIDTH = 0.3;
	/** The bot's walk in at the hit (toward the AI, blocks a tick), drawn up to this. */
	static final double BOT_IN_MOST = 0.13;
	/** The game's knockback: the hurt's 0.4, then the sprint hit's 0.5 (each halves the motion before it), lifted 0.4 on the ground. */
	static final double KB_HURT = 0.4;
	static final double KB_SPRINT = 0.5;
	static final double KB_UP = 0.4;
	/** A jump reset of the stun (T9/C5, 91 of 272 real): the jump's 0.42 up and the sprint jump's 0.2 toward the AI. */
	public static final float START_RESET_SHARE = 0.33f;
	static final double JUMP_UP = 0.42;
	static final double JUMP_BOOST = 0.2;
	/** The AI's move on after its sprint hit (the hit keeps 0.6 of it): its sprint's 0.29 a tick times 0.6, about. */
	static final double AGENT_V_LEAST = 0.10;
	static final double AGENT_V_MOST = 0.18;
	/** The AI's sword this many ticks into coming back (the swing on the hit's tick; the drill begins a tick or two later). */
	public static final int AGENT_TICKER_LEAST = 1;
	public static final int AGENT_TICKER_MOST = 2;
	/** The bot's sword is back at the start in this share (real: 172 of 272 hit back within 5 ticks); else this many ticks into it. */
	public static final float BOT_READY_SHARE = 0.75f;
	static final int BOT_TICKER_LEAST = 4;
	static final int BOT_TICKER_MOST = 9;
	static final int BOT_READY_TICKER = 1000;
	/** The bot's hurt and grace ticks the tick after a hit (the game sets 10 and 20 on it). */
	static final int HURT_AFTER = 9;
	static final int INVUL_AFTER = 19;

	// ---- the chain and its end ----------------------------------------------------------------------------------------------------
	/** The fight range: eye to their box. Beyond it for OUT_TICKS ticks in a row, the chain is dropped. */
	public static final double RANGE = 5.0;
	public static final int OUT_TICKS = 20;
	/** No hit either way for this long (in range): the chain is dropped too (twice SchoolSession.COMBO_GAP). */
	public static final int GAP_TICKS = 40;
	/** The longest drill, ticks (a 10+ chain is about 150): then it ends as time up (bootstrapped). */
	public static final int MOST_TICKS = 400;
	/** How a drill ended. */
	public static final int RUNNING = 0, BROKEN = 1, OUT = 2, STALL = 3, TIME = 4, KO = 5;
	public static final String[] END_NAMES = {"running", "their hit", "out of range", "no hit", "time", "knockout"};

	// ---- the reward (on top of the fight's own: damage, COMBO_HIT 0.4, COMBO_HIT3 0.4, THEIR_ROW_COST) ----------------------------
	/** Each further hit of the chain: HIT_STEP x (length - 1), at most HIT_MOST: the 2nd 0.2, the 3rd 0.4, the 4th 0.6, the 5th on 0.8. */
	public static final float HIT_STEP = 0.2f;
	public static final float HIT_MOST = 0.8f;
	/** The bot's hit landing in the chain (a trade too): about five hits' damage; the drill ends there. */
	public static final float BREAK_COST = 1.0f;
	/** The chain dropped (out of range, or no hit): it let them go. */
	public static final float DROP_COST = 0.3f;

	// ---- the light teacher (SchoolSession: step.holdPull, advantage-weighted on W/S, the sprint and the jump) ----------------------
	public static final float PULL = 0.3f;
	/** Ticks with W let go from the tick its own hit shows (part 0's W-tap: "let go of W ... between hits to restart the sprint"). */
	public static final int WTAP_TICKS = 2;
	/** S while the sword comes back if they are nearer than this (from 2.6+ the real players hit back 15%, from under 2.0 72%). */
	public static final double BACK_WITHIN = 2.6;
	/** Beyond this (eye to box) they are out of reach: chase at a sprint. */
	public static final double CHASE_FROM = 3.0;
	/** The sword this far back: W and the sprint again, so the hit as it comes back is a sprint hit (a sprint starts a tick after W). */
	public static final float RUN_UP_CHARGE = 0.84f;

	// ---- the bot being comboed (a person; Human adds the limits at TechniqueBot#start) ----------------------------------------------
	/** Its jump reset of each hit (T9/C5): with Human timing a third or so lands on the tick (the rest are late, wasted). */
	static final float T9_LEAST = 0.35f;
	static final float T9_SPREAD = 0.3f;
	/** Its fly out (C3, jump and hold S) after a sprint hit, a reaction later (rolled when no jump reset was). */
	static final float FLY_OUT_LEAST = 0.1f;
	static final float FLY_OUT_SPREAD = 0.2f;
	/** How much nearer than the reach it waits to swing (Style#closeIn; drawHuman's 0.1-0.5): a person hit by surprise swings back at once. */
	static final float CLOSE_IN_MOST = 0.15f;
	/** Its swing back through a hit while still in reach (Style#answerHit), per hit taken. */
	static final float ANSWER_LEAST = 0.4f;
	static final float ANSWER_SPREAD = 0.3f;
	static final float STRAFE_LEAST = 0.15f;
	static final float STRAFE_SPREAD = 0.35f;

	private ComboDrill() {
	}

	/**
	 * The drill's start (SceneRecorder's layout, relative to the arena's middle on the floor; ArenaServer#placed turns it about the middle
	 * at random): the tick after the AI's sprint hit from {@link #STUN_LEAST}-{@link #STUN_MOST} blocks. The agent at -z facing +z, moving on
	 * at 0.6 of a sprint, not sprinting (the hit ended it), its sword {@link #AGENT_TICKER_LEAST}-{@link #AGENT_TICKER_MOST} ticks into
	 * coming back; the bot at the hit's spot facing it, the knockback in its motion (or a jump reset's, {@link #START_RESET_SHARE}), hurt
	 * and grace ticks running, its sword back or nearly.
	 */
	public static double[] startState(Random rng) {
		double hit = STUN_LEAST + (STUN_MOST - STUN_LEAST) * rng.nextDouble();
		double flat = hit + HALF_WIDTH;
		boolean reset = rng.nextFloat() < START_RESET_SHARE;
		double in = BOT_IN_MOST * rng.nextDouble();
		double agentV = AGENT_V_LEAST + (AGENT_V_MOST - AGENT_V_LEAST) * rng.nextDouble();
		int agentTicker = AGENT_TICKER_LEAST + rng.nextInt(AGENT_TICKER_MOST - AGENT_TICKER_LEAST + 1);
		int botTicker = rng.nextFloat() < BOT_READY_SHARE ? BOT_READY_TICKER : BOT_TICKER_LEAST + rng.nextInt(BOT_TICKER_MOST - BOT_TICKER_LEAST + 1);
		float agentPitch = 8f * rng.nextFloat();
		double[] s = new double[2 * BODY];
		// The agent.
		s[Z] = -flat / 2;
		s[VZ] = agentV;
		s[YAW] = 0;
		s[PITCH] = agentPitch;
		s[TICKER] = agentTicker;
		s[HEALTH] = 20;
		// The bot: away along +z. Its motion before the hit was -in (walking in); the hurt halves it and adds 0.4 away, the sprint hit
		// halves that and adds 0.5.
		double away = (-in / 2 + KB_HURT) / 2 + KB_SPRINT;
		double up = KB_UP;
		if (reset) {
			up = JUMP_UP;
			away -= JUMP_BOOST;
		}
		int b = BODY;
		s[b + Z] = flat / 2;
		s[b + VZ] = away;
		s[b + VY] = up;
		s[b + YAW] = 180;
		s[b + TICKER] = botTicker;
		s[b + HEALTH] = 20;
		s[b + HURT] = HURT_AFTER;
		s[b + INVUL] = INVUL_AFTER;
		return s;
	}

	/** The stun's distance in a start state (eye to box on the line, as drawn). */
	public static double stunReach(double[] s) {
		return Math.abs(s[BODY + Z] - s[Z]) - HALF_WIDTH;
	}

	/** Whether a start state's stun was jump-reset (its lift is a jump's). */
	public static boolean stunReset(double[] s) {
		return s[BODY + VY] > KB_UP + 1e-9;
	}

	/**
	 * The bot of the drill: a person being comboed. Its body and ordinary fight drawn as a person's (Style#drawHuman), no technique but
	 * the stun's counters of the document: the jump reset (T9/C5) and the fly out (C3: jump and hold S, {@link Style#flyOut}); its strafe,
	 * its walk back in to the edge of reach and its swing back the ordinary fight's (PvpPlayer#ordinary: in reach, sword back, a
	 * reaction). No opening crit, no hit selecting. It stands in the straight sprinter's slot (the network's opponent input), kept out of
	 * that bot's tallies (SchoolSession). Human#humanize adds a person's limits and the reaction spread at TechniqueBot#start.
	 */
	public static Style victim(Random rng) {
		Style s = new Style().drawHuman(rng);
		s.bot = Specialists.STRAIGHT_SPRINTER;
		s.name = "combo-drill";
		java.util.Arrays.fill(s.p, 0f);
		s.comboDrill = true;
		s.adapts = false;
		s.humanTiming = true;
		s.critOpen = 0f;
		s.jumpInLands = false;
		s.hitSelect = 0f;
		s.swingEarly = 0f;
		s.rhythm = false;
		s.set(Technique.T9, T9_LEAST + T9_SPREAD * rng.nextFloat());
		s.flyOut = FLY_OUT_LEAST + FLY_OUT_SPREAD * rng.nextFloat();
		s.strafe = STRAFE_LEAST + STRAFE_SPREAD * rng.nextFloat();
		s.closeIn = CLOSE_IN_MOST * rng.nextFloat();
		s.answerHit = ANSWER_LEAST + ANSWER_SPREAD * rng.nextFloat();
		return s;
	}

	/** The drill's pay for a hit that makes the chain {@code length} long (2 for the first after the stun): more for each further one. */
	public static float hitReward(int length) {
		return length < 2 ? 0f : Math.min(HIT_MOST, HIT_STEP * (length - 1));
	}

	/**
	 * One drill's chain, tick by tick from the start: the stun is hit 1. {@link #tick} returns the drill's reward for the tick and
	 * {@link #end} says when it is over. A hit of theirs (a trade too) breaks it; out of the fight range {@link #OUT_TICKS} ticks in a row,
	 * or no hit either way for {@link #GAP_TICKS}, drops it; {@link #MOST_TICKS} is time up.
	 */
	public static final class Chain {
		private int length = 1;
		private int ticks;
		private int sinceHit;
		private int outTicks;
		private int end = RUNNING;

		/** One tick: its hit landed, theirs landed, eye to their box now. The drill's reward for it (0 once over). */
		public float tick(boolean dealt, boolean taken, double reach) {
			if (end != RUNNING) {
				return 0f;
			}
			ticks++;
			sinceHit++;
			if (taken) {
				end = BROKEN;
				return -BREAK_COST;
			}
			float r = 0f;
			if (dealt) {
				length++;
				sinceHit = 0;
				r += hitReward(length);
			}
			outTicks = reach > RANGE ? outTicks + 1 : 0;
			if (outTicks >= OUT_TICKS) {
				end = OUT;
				r -= DROP_COST;
			} else if (sinceHit >= GAP_TICKS) {
				end = STALL;
				r -= DROP_COST;
			} else if (ticks >= MOST_TICKS) {
				end = TIME;
			}
			return r;
		}

		/** The fight ended by a knockout (either way) while the chain ran. */
		public void knockout() {
			if (end == RUNNING) {
				end = KO;
			}
		}

		public int length() {
			return length;
		}

		public int end() {
			return end;
		}

		public boolean over() {
			return end != RUNNING;
		}

		/** Over by the chain (their hit, dropped): no value after it. Time up is not (the learner bootstraps it). */
		public boolean terminal() {
			return end == BROKEN || end == OUT || end == STALL;
		}
	}

	/**
	 * The light teacher's keys for a drill tick, in Teacher's form (one key sure, or two alike where either does), on W/S, the sprint and
	 * the jump; A/D, the camera and the swing are left (the swing is the ordinary fight's swing lesson: sword ready and the pick on them,
	 * never held). Returns the heads written. {@code sinceOwnHit}: ticks since its hit as shown (0 the tick it lands); {@code charge}: its
	 * sword; {@code reach}: eye to their box.
	 * <ul>
	 * <li>the W-tap: W let go ({@link #WTAP_TICKS} ticks after its hit), not W (stand or S);</li>
	 * <li>the sword coming back (under {@link #RUN_UP_CHARGE}): S nearer than {@link #BACK_WITHIN}; W and the sprint beyond
	 * {@link #CHASE_FROM} (they fly out); between, not W (hold the gap at the edge);</li>
	 * <li>the sword back or nearly: W and the sprint (the next hit a sprint hit, T7 "running at full sprint toward them");</li>
	 * <li>never the jump.</li>
	 * </ul>
	 */
	public static int target(float[] target, int sinceOwnHit, float charge, double reach) {
		int heads;
		if (sinceOwnHit >= 0 && sinceOwnHit < WTAP_TICKS) {
			heads = putEither(target, ActionSpace.FORWARD, 0, BackNudge.BACK);
		} else if (charge < RUN_UP_CHARGE) {
			if (reach < BACK_WITHIN) {
				heads = put(target, ActionSpace.FORWARD, BackNudge.BACK);
				heads |= put(target, ActionSpace.SPRINT, 0);
			} else if (reach > CHASE_FROM) {
				heads = put(target, ActionSpace.FORWARD, 1);
				heads |= put(target, ActionSpace.SPRINT, 1);
			} else {
				heads = putEither(target, ActionSpace.FORWARD, 0, BackNudge.BACK);
			}
		} else {
			heads = put(target, ActionSpace.FORWARD, 1);
			heads |= put(target, ActionSpace.SPRINT, 1);
		}
		heads |= put(target, ActionSpace.JUMP, 0);
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
		float rest = n > 2 ? (1f - Teacher.SURE) / (n - 2) : 0f;
		for (int k = 0; k < n; k++) {
			target[off + k] = k == a || k == b ? Teacher.SURE / 2f : rest;
		}
		return 1 << head;
	}

	/** Distance bands of its hits (eye to box at the swing), as the real rounds: under 2.0, 2.0-2.6, 2.6+. */
	public static final double NEAR = 2.0;
	public static final double EDGE = 2.6;
	public static final String[] BAND_NAMES = {"<2.0", "2.0-2.6", "2.6+"};

	public static int band(double reach) {
		return reach < NEAR ? 0 : reach < EDGE ? 1 : 2;
	}

	/**
	 * The drills' counters for the training update line: drills played, chain lengths (mean, 3+, 5+, 10+, longest), how they ended, the
	 * bot's hit back after the AI's hits by its hit distance (and after the stun), and the AI's swing on its ready-in-reach ticks. Not
	 * thread-safe.
	 */
	public static final class Tally {
		private int drills, c3, c5, c10, longest;
		private long lengthSum;
		private final int[] ends = new int[END_NAMES.length];
		private final int[] hits = new int[3];
		private final int[] followed = new int[3];
		private final int[] hitBack = new int[3];
		private int stuns, stunHitBack, stunResets;
		private long readyTicks, readySwung;
		private long rewardTicks;
		private double rewardSum;
		/** The band of its last hit in the drill waiting for what comes next (-1 none; 3 the stun). */
		private int pending = -1;

		/** A drill began; its stun jump-reset or not ({@link #stunReset}). */
		public void start(boolean resetStun) {
			pending = 3;
			stuns++;
			stunResets += resetStun ? 1 : 0;
		}

		/** Its hit landed in the drill, from this far (eye to box at the swing); the hit before it was followed by its own. */
		public void hit(double reach) {
			if (pending >= 0 && pending < 3) {
				followed[pending]++;
			}
			int b = band(reach);
			hits[b]++;
			pending = b;
		}

		/** The bot's hit landed in the drill (it ends it). */
		public void hitBack() {
			if (pending == 3) {
				stunHitBack++;
			} else if (pending >= 0) {
				hitBack[pending]++;
				followed[pending]++;
			}
			pending = -1;
		}

		/** A decision tick with its sword ready and them in reach under the pick; whether it swung. */
		public void ready(boolean swung) {
			readyTicks++;
			readySwung += swung ? 1 : 0;
		}

		/** The drill's own reward of a tick (for the line's mean). */
		public void reward(float r) {
			rewardTicks++;
			rewardSum += r;
		}

		/** The drill is over. */
		public void end(Chain c) {
			drills++;
			int n = c.length();
			lengthSum += n;
			c3 += n >= 3 ? 1 : 0;
			c5 += n >= 5 ? 1 : 0;
			c10 += n >= 10 ? 1 : 0;
			longest = Math.max(longest, n);
			ends[c.over() ? c.end() : TIME]++;
			pending = -1;
		}

		public int drills() {
			return drills;
		}

		public double meanLength() {
			return drills == 0 ? 0.0 : lengthSum / (double) drills;
		}

		public int chains(int least) {
			return least >= 10 ? c10 : least >= 5 ? c5 : c3;
		}

		public int longest() {
			return longest;
		}

		public int stuns() {
			return stuns;
		}

		/** The bot's hits back that came next after the stun (before any hit of the AI's). */
		public int stunHitBacks() {
			return stunHitBack;
		}

		public int hits(int band) {
			return hits[band];
		}

		public int hitBacks(int band) {
			return hitBack[band];
		}

		/** Its hits of a band followed by a hit either way in the drill (the real rounds' "hit next" counts these). */
		public int followed(int band) {
			return followed[band];
		}

		public double swingRate() {
			return readyTicks == 0 ? 0.0 : readySwung / (double) readyTicks;
		}

		/**
		 * "drills 40, chain (stun = 1) mean 2.4, 3+ 12, 5+ 3, 10+ 0, longest 6; ended: their hit 30, out of range 6, ...; the bot's hit back
		 * after the stun 9/40 (jump-reset stuns 13), after its hits by distance ...: <2.0 5/7 71% of 8, 2.0-2.6 ...; swung on ready-in-reach
		 * ticks 120/150 80%; drill reward a tick 0.012".
		 */
		public String line() {
			StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "drills %d, chain (stun = 1) mean %.2f, 3+ %d, 5+ %d, 10+ %d, longest %d; ended:",
					drills, meanLength(), c3, c5, c10, longest));
			for (int i = 1; i < END_NAMES.length; i++) {
				b.append(String.format(Locale.ROOT, " %s %d%s", END_NAMES[i], ends[i], i + 1 < END_NAMES.length ? "," : ";"));
			}
			b.append(String.format(Locale.ROOT, " the bot's hit back after the stun %d/%d (jump-reset stuns %d), after its hits by distance (hit back /"
					+ " followed by a hit, of its hits):", stunHitBack, stuns, stunResets));
			for (int i = 0; i < 3; i++) {
				b.append(String.format(Locale.ROOT, " %s %d/%d%s of %d%s", BAND_NAMES[i], hitBack[i], followed[i],
						followed[i] > 0 ? String.format(Locale.ROOT, " %.0f%%", 100.0 * hitBack[i] / followed[i]) : "", hits[i], i < 2 ? "," : ";"));
			}
			b.append(String.format(Locale.ROOT, " swung on ready-in-reach ticks %d/%d %.0f%%; drill reward a tick %.3f", readySwung, readyTicks,
					100.0 * swingRate(), rewardTicks == 0 ? 0.0 : rewardSum / rewardTicks));
			return b.toString();
		}

		/** Counts start again. */
		public void clear() {
			drills = c3 = c5 = c10 = longest = 0;
			lengthSum = 0;
			java.util.Arrays.fill(ends, 0);
			java.util.Arrays.fill(hits, 0);
			java.util.Arrays.fill(followed, 0);
			java.util.Arrays.fill(hitBack, 0);
			stuns = stunHitBack = stunResets = 0;
			readyTicks = readySwung = 0;
			rewardTicks = 0;
			rewardSum = 0;
		}
	}
}
