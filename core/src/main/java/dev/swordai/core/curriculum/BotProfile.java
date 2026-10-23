package dev.swordai.core.curriculum;

/**
 * How the sparring bot is placed and how it behaves in one stage. Plain public fields so the whole
 * thing round-trips through the JSON config.
 */
public final class BotProfile {
	public static final String STAND = "stand";
	public static final String STRAFE = "strafe";
	public static final String WANDER = "wander";
	public static final String APPROACH = "approach";
	public static final String FIGHT = "fight";
	/** Not scripted at all: the bot's body is played by a saved copy of the agent's own network. */
	public static final String SELF = "self";
	/** Where the copy starts in a combo drill: a step away, and mostly but not always in view. */
	public static final float DRILL_MIN_DISTANCE = 2.5f;
	public static final float DRILL_MAX_DISTANCE = 3.5f;
	public static final float DRILL_BEARING_DEG = 45f;

	// ---- placement, relative to the agent -------------------------------------------------------
	public float minDistance = 2.5f;
	public float maxDistance = 8f;
	/** Largest bearing from the agent's current view direction, in degrees either side. */
	public float maxBearingDeg = 50f;
	/** And the smallest: above zero the bot is never put straight ahead, but off to a side or behind. */
	public float minBearingDeg = 0f;
	/**
	 * Above zero: this share of the times the bot is put somewhere, it is put farther off instead,
	 * between {@link #farMinDistance} and {@link #farMaxDistance}. For an opponent that is sometimes
	 * within a sword's reach and sometimes plainly not, with nothing in between.
	 */
	public float farChance = 0f;
	public float farMinDistance = 0f;
	public float farMaxDistance = 0f;
	/** Tallest pillar the bot may be put on; gives the agent something to look up at. */
	public int maxPillar = 0;
	/** Above zero: the bot jumps to a fresh spot this often, so every hop is a new aiming problem. */
	public int hopIntervalTicks = 0;

	// ---- movement -------------------------------------------------------------------------------
	public String movement = STAND;
	public boolean sprint = false;
	/** Chance per tick of a jump while on the ground. */
	public float jumpChance = 0f;
	/** Eye-to-hitbox range the bot tries to hold in approach/fight modes. */
	public float preferredRange = 2.7f;
	/**
	 * Share of fights in which the bot does not hold that range at all but keeps pushing in: it runs
	 * straight at the agent the whole time and never backs off, the way many players do. Against that,
	 * only hits with a sprint behind them make room.
	 */
	public float rushChance = 0f;
	/**
	 * With {@link #SELF}: the copy stays the fighter the agent was when the stage began, however often
	 * the agent beats it. Without this the agent takes the copy's place whenever it is clearly ahead.
	 */
	public boolean fixedCopy = false;

	// ---- combat ---------------------------------------------------------------------------------
	public boolean attacks = false;
	/** Ticks between the agent entering reach and the bot being allowed to swing. */
	public int reactionTicks = 8;
	/** Standard deviation of the bot's aim error, in degrees. */
	public float aimNoiseDeg = 6f;
	/** How fast the bot can turn, degrees per tick. */
	public float turnRateDeg = 25f;
	/** The bot only swings once its own cooldown has reached this fraction. */
	public float attackChargeMin = 0.92f;
	/** After landing a hit the bot backs off for a few ticks, like a human W-tapping. */
	public boolean wtap = false;
	/**
	 * Above zero: the bot keeps a combo going. For this many ticks after landing a hit it swings again
	 * the moment the agent is in its reach and its weapon is back, with no reaction time in between,
	 * the way a player follows up a hit on a rhythm rather than on sight. An agent that walks back
	 * into it is hit again; one that backs away until this has run out meets the slow bot again.
	 */
	public int followUpTicks = 0;
	/**
	 * Extra reach, in blocks, of those follow-up swings. A player with a combo going lands hits the
	 * other cannot answer: the one being hit is in the air or on its way out, and on a server it is
	 * hit where it was a moment ago. With this the bot's follow-up cannot be traded with either; what
	 * is left is getting out of it.
	 */
	public float followUpReach = 0f;
	/**
	 * Above zero: a follow-up swing is thrown on rhythm, whether or not it will land. As soon as the
	 * weapon is back and the agent is no farther than this many blocks beyond its reach, it swings.
	 * At an agent that has backed out, that swing misses: the combo is over, the weapon is recharging
	 * and the bot is open to a charged hit. That opening is what backing out of a combo is for;
	 * without it the bot only ever swings when it will land, and nothing the agent does gets it one.
	 */
	public float followUpWhiff = 0f;
	/**
	 * The chance that a swing of its own comes late: it has the agent in reach and under its crosshair,
	 * its weapon is back, and it takes one to {@link #swingSlipTicks} ticks more to swing. A person does
	 * that every few swings, also in the middle of a combo, and for those ticks stands within the
	 * agent's reach with nothing on the way. A swing back made then lands first and ends the combo.
	 * Without it the scripted opponent's follow-up came on the first tick it could, every time, and a
	 * swing back was never worth making. Not for a swing made on the way down from a jump: that one has
	 * five ticks to be made in.
	 */
	public float swingSlip = 0f;
	public int swingSlipTicks = 3;
	/**
	 * The chance, each time the agent hits it, that it backs straight off for a moment instead of
	 * walking back in: what a person does who wants out of a combo.
	 */
	public float dodgeChance = 0f;
	/** For how many ticks it then backs off: a quarter of a second, or with twelve, the way out of a combo that S is. */
	public int dodgeTicks = 5;
	/**
	 * Share of its swings, when the agent is close, that the bot makes as a jumped critical hit instead:
	 * it stops sprinting, jumps and swings on the way down.
	 */
	public float critChance = 0f;
	/**
	 * Share of its critical hits after which it keeps coming, running straight at the agent for half a
	 * second instead of holding its range or backing off: what a player does who wants to stay on top
	 * of the one they have just hit. A critical hit throws the agent back only a little, so with this
	 * the bot is still in the agent's reach as the agent comes down, and there is a punish crit to be
	 * made. Without it there never was one: the bot was gone before the agent had landed.
	 */
	public float critPress = 0f;
	/**
	 * The chance, each time it lands a hit or takes one, that it runs: for a second or two it makes
	 * for somewhere away from the agent. What a player does who keeps running out of a fight. Most
	 * times it backs off with its eyes on the agent and its sword ready: a run closes on that only
	 * slowly, a running jump twice as fast, and it hits a fighter that comes in on it carelessly. The
	 * rest ({@link #fleeTurned}) it turns its back and sprints: it cannot swing then, and a fighter
	 * that only sprints after it never gets any nearer, since both run as fast. Either way the hit
	 * made coming down from a running jump, with the sprint let go, is a critical hit, which does not
	 * send the runner on its way as a sprint hit would.
	 */
	public float fleeChance = 0f;
	public float fleeTurned = 0.35f;
	/**
	 * It fights by going round the agent: it keeps to about two blocks, walks sideways round it,
	 * changing direction now and then, and makes its hits as jumped critical hits from there. A
	 * critical hit throws the agent up and hardly back, so it comes down with this opponent still
	 * in reach, but no longer where its crosshair was.
	 */
	public boolean orbit = false;
	/**
	 * A run does not end by itself: it keeps away from the agent until the agent has hit it, turning
	 * its back whenever the agent comes near and looking back at it from a distance. What a player
	 * does who will not be caught. Only a fighter that covers ground faster than a run gets to it.
	 */
	public boolean fleeUntilHit = false;
	/**
	 * It does not walk: it stands where it was put (in a corner, with the wall behind it), turns to
	 * face the agent and hits it whenever it comes into reach, jumping for its critical hits on the
	 * spot. A hit does not send it anywhere, so what decides the fight is what each hit does.
	 */
	public boolean holdGround = false;
	/**
	 * It combos with sprint hits from the edge of its reach, and an agent that backs off out of that
	 * reach it comes after with a running jump, taken from as far off as the way down will bring the
	 * agent into reach (four blocks from one that is walking backwards): it sprints up to the top of
	 * the jump, lets go of the sprint and makes a critical hit on the way down. Which of the two its
	 * next hit is, is decided tick by tick by what the agent is doing. What a player does to one that
	 * backs out of combos with S: backing off only makes room for the next jump.
	 */
	public boolean jumpIn = false;
	/**
	 * Hit by the agent, it keeps its swing for its own way down and makes it a critical hit: the
	 * punish crit. Where it takes the full knockback the agent is out of its reach by then; where it
	 * takes part of it (as on a server that hands out less), the agent is right there with its sword
	 * on the way back.
	 */
	public boolean punish = false;
	/**
	 * It plays on the agent's rhythm, as a player does who has found out that the agent swings the
	 * moment its sword is all the way back and never before. It holds its distance a step outside
	 * the agent's reach and now and then steps into it and out again. Once the agent has swung, it
	 * takes a jump timed so that its critical hit comes down a tick or two before the agent's sword
	 * is back, when the agent has nothing to answer it with; an agent that comes in at it with its
	 * sword back it meets with a jump that has it on its way down as the agent arrives. Hit, it
	 * answers on its way down ({@link #punish}). The friend of the user's who still beat it on
	 * 18 October did this.
	 */
	public boolean timer = false;
	/**
	 * The player at the edge of the reach, in two ways, as {@link #critChance} has it swing by swing.
	 * For a critical hit: it keeps a step outside the agent's reach while its sword comes back, walks
	 * in and jumps from the edge of that reach once it is back, makes its critical hit on the way
	 * down, and backs straight out with S. Otherwise it stands where it is and hits the agent the
	 * moment it comes into reach. Never to the side, nothing late, nothing off. The user, 18 October:
	 * "crit and then PRESS S like go out of distance and Crit and go out of distance", and "PERFECT
	 * 3 block not moving", "just pure perfect combo"; the player is "mix of both".
	 */
	public boolean spacer = false;
	/**
	 * Played by the outside bot (Carpet PvP's own fighter) at this level, not by the scripted brain;
	 * null for the brain. See {@code Config.outsideShare}.
	 */
	public String outside;
	/**
	 * The share of the hits it takes on the ground that it jumps with, as a player does to be thrown
	 * less far and stay in the fight (a jump reset). Most players who fight back do it some of the
	 * time.
	 */
	public float jumpResetChance = 0f;
	/** Something odd it does all fight long; one of the {@code ANTIC_} names, or empty. */
	public String antic = "";
	/** It jumps all the time. */
	public static final String ANTIC_HOP = "hop";
	/** It keeps turning on the spot as it walks in, and swings whenever the agent passes under its crosshair. */
	public static final String ANTIC_SPIN = "spin";
	/** It stands still for seconds on end, then runs at the agent, then stands again. */
	public static final String ANTIC_STATUE = "statue";
	/** It changes the way it is going every few ticks. */
	public static final String ANTIC_ZIGZAG = "zigzag";
	/** It clicks as fast as it can, long before its sword is back. */
	public static final String ANTIC_SPAM = "spam";
	/** It keeps ducking and standing up. */
	public static final String ANTIC_DUCK = "duck";
	public static final String[] ANTICS = {ANTIC_HOP, ANTIC_SPIN, ANTIC_STATUE, ANTIC_ZIGZAG, ANTIC_SPAM, ANTIC_DUCK};

	// ---- health ---------------------------------------------------------------------------------
	/** Health is refilled every tick: hits still land and knock back, but nobody is ever KO'd. */
	public boolean botImmortal = true;
	public boolean agentImmortal = true;

	/**
	 * When set, reaction time, aim noise and turn rate are interpolated between this profile (skill 0)
	 * and a sharp fighter (skill 1), and the curriculum raises the skill as the agent starts winning.
	 */
	public boolean scalesWithSkill = false;

	public BotProfile copy() {
		BotProfile b = new BotProfile();
		copyInto(b);
		return b;
	}

	/** Sets every field of {@code b} to this profile's. */
	public void copyInto(BotProfile b) {
		b.minDistance = minDistance;
		b.maxDistance = maxDistance;
		b.maxBearingDeg = maxBearingDeg;
		b.minBearingDeg = minBearingDeg;
		b.farChance = farChance;
		b.farMinDistance = farMinDistance;
		b.farMaxDistance = farMaxDistance;
		b.maxPillar = maxPillar;
		b.hopIntervalTicks = hopIntervalTicks;
		b.movement = movement;
		b.sprint = sprint;
		b.jumpChance = jumpChance;
		b.preferredRange = preferredRange;
		b.rushChance = rushChance;
		b.fixedCopy = fixedCopy;
		b.attacks = attacks;
		b.reactionTicks = reactionTicks;
		b.aimNoiseDeg = aimNoiseDeg;
		b.turnRateDeg = turnRateDeg;
		b.attackChargeMin = attackChargeMin;
		b.wtap = wtap;
		b.followUpTicks = followUpTicks;
		b.followUpReach = followUpReach;
		b.followUpWhiff = followUpWhiff;
		b.swingSlip = swingSlip;
		b.swingSlipTicks = swingSlipTicks;
		b.dodgeChance = dodgeChance;
		b.dodgeTicks = dodgeTicks;
		b.critChance = critChance;
		b.critPress = critPress;
		b.fleeChance = fleeChance;
		b.fleeTurned = fleeTurned;
		b.orbit = orbit;
		b.fleeUntilHit = fleeUntilHit;
		b.holdGround = holdGround;
		b.jumpIn = jumpIn;
		b.punish = punish;
		b.timer = timer;
		b.spacer = spacer;
		b.outside = outside;
		b.jumpResetChance = jumpResetChance;
		b.antic = antic;
		b.botImmortal = botImmortal;
		b.agentImmortal = agentImmortal;
		b.scalesWithSkill = scalesWithSkill;
	}

	/**
	 * This profile for a combo drill ({@link StageSpec#drillShare}): the same health rules, but the
	 * body is played by a copy of the agent and starts within a step of hitting it.
	 */
	public BotProfile drill() {
		BotProfile b = copy();
		b.movement = SELF;
		b.minDistance = DRILL_MIN_DISTANCE;
		b.maxDistance = DRILL_MAX_DISTANCE;
		b.maxBearingDeg = DRILL_BEARING_DEG;
		b.minBearingDeg = 0f;
		b.farChance = 0f;
		b.maxPillar = 0;
		b.hopIntervalTicks = 0;
		b.attacks = true;
		b.scalesWithSkill = false;
		return b;
	}

	/** The profile actually used at a given skill in [0, 1]. */
	public BotProfile atSkill(float skill) {
		if (!scalesWithSkill) {
			return this;
		}
		float s = Math.max(0f, Math.min(1f, skill));
		BotProfile b = copy();
		b.reactionTicks = Math.round(lerp(reactionTicks, 2f, s));
		b.aimNoiseDeg = lerp(aimNoiseDeg, 1.5f, s);
		b.turnRateDeg = lerp(turnRateDeg, 45f, s);
		b.sprint = sprint || s >= 0.3f;
		b.wtap = wtap || s >= 0.5f;
		return b;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
