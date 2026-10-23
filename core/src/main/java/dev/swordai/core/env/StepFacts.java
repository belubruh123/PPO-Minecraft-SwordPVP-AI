package dev.swordai.core.env;

/**
 * Ground truth about one agent step, gathered by the mod. It feeds the reward and the metrics and
 * is never part of what the acting network sees.
 */
public final class StepFacts {
	/** The opponent's sword counts as back from here on (see {@code ObsSpec.SC_OPP_CHARGE}). */
	public static final float OPP_READY = 0.9f;

	// ---- aim, measured against the opponent as rendered on the client ---------------------------
	/** Signed error to the opponent's head, degrees. */
	public float yawErrDeg;
	public float pitchErrDeg;
	/** How far the crosshair is outside the opponent's hitbox, degrees; zero anywhere on it. */
	public float missDeg;
	/** The same counting left/right only (used while pitch is still locked). */
	public float yawMissDeg;
	/** The crosshair's yaw is within the head's width (used while pitch is still locked). */
	public boolean yawOnHead;
	public boolean onHead;
	/** The crosshair is on the hitbox, head included. This is what a swing needs. */
	public boolean onBody;
	public boolean enemyVisible;
	/** Distance from the agent's eye to the opponent's hitbox, blocks. Vanilla reach is 3.0. */
	public float reachDist;
	/** The opponent was just repositioned, so "progress since last tick" is meaningless. */
	public boolean discontinuity;

	// ---- what the agent did on the step being scored --------------------------------------------
	public boolean clicked;
	/** The click had the opponent under the crosshair, in reach (an attack was sent). */
	public boolean clickOnTarget;
	/** Attack charge at the moment of the click, 0..1. */
	public float chargeAtClick;
	/** Current attack charge, 0..1. */
	public float charge;
	/** +1 W, -1 S, 0 neither. */
	public int forwardAxis;
	/** -1 A, +1 D, 0 neither. */
	public int strafeAxis;
	public boolean sprintKey;
	/** Whether the server has the agent sprinting now: a charged hit made in this state knocks back. */
	public boolean serverSprinting;
	/** The agent is in the air and coming down: a charged hit made in this state, not sprinting, is a critical hit. */
	public boolean falling;
	/** The agent stands on the ground now, after the step. */
	public boolean onGround;
	/** When the step was chosen the agent was off the ground, whoever put it there. */
	public boolean inAir;
	/**
	 * Above zero only while the agent is held for the first crit drill: the distance, eye to hitbox,
	 * within which that drill wants the jump made. A jump taken off with the opponent farther away
	 * than this is charged as a jump for nothing, whatever the distance on the way down. Zero in a
	 * fight: there a jump is judged by what really comes of it.
	 */
	public float critZone;
	/**
	 * A swing on this step would have been a critical hit that landed: when the step was chosen the
	 * agent was coming down with the weapon back and the sprint off, and the opponent was under its
	 * crosshair and in reach.
	 */
	public boolean critOpen;
	/**
	 * When the step was chosen the agent was in the air by the opponent's doing: knocked up by a hit,
	 * with no jump of its own since. A critical hit made from there is a punish crit.
	 */
	public boolean knockedUp;
	/**
	 * The agent is held in place for a drill and has no way of not being hit: hits taken on this step
	 * are counted, but nothing is charged for them.
	 */
	public boolean cannotAvoid;
	/** A fight to the knockout ran out of time on this step. */
	public boolean timedOut;
	/** How far the camera actually turned, degrees; positive turns right. */
	public float yawTurnDeg;
	/** Positive looks down. */
	public float pitchTurnDeg;
	/** The agent's own pitch after the move, degrees; positive looks down. */
	public float agentPitchDeg;
	/** The jump key was held on this step. */
	public boolean jumpKey;
	/** And the agent was standing on the ground when it chose that: it jumped. */
	public boolean tookOff;
	/** The step was chosen in the situation a jump is pushed in (see {@code JumpNudge}): just swung, on the ground, opponent on screen. */
	public boolean jumpWindow;
	/** Distance from the eye to the opponent's hitbox when this step was chosen, blocks. */
	public float reachAtAction;
	/** The agent had a plan (see {@code MixIdea}) when it chose this step. */
	public boolean ideaOn;
	/** The plan's strafe direction: -1 left, +1 right, 0 free. */
	public int ideaStrafe;
	/** That direction is one to keep going round the opponent in, rather than to go out to and come back from. */
	public boolean ideaCircle;
	/** Ticks the plan still wanted the swing held back. */
	public int ideaHold;
	/** How far back the opponent's sword was when this step was chosen, 0..1, as read off its arm. */
	public float oppCharge = 1f;
	/**
	 * On this step it walked into reach (W held, the opponent in view) while the opponent's sword was
	 * on its way back, for the first time since that swing: spacing. See {@code RewardWeights.spaceIn}.
	 */
	public boolean walkedInOnCooldown;
	/**
	 * On this step it walked into reach (W held, the opponent in view) while the opponent's sword was
	 * ready: what the user found it loses by. See {@code RewardWeights.spaceOut}.
	 */
	public boolean walkedInOnReady;
	/**
	 * The opponent began a jump in at it on this step: 0 not, 1 with the fighter's sword down, 2 with it
	 * ready to meet the jump. See {@code RewardWeights.jumpInReady}.
	 */
	public int oppJumpIn;

	// ---- combat events confirmed by the server since the previous step --------------------------
	public int hitsDealt;
	/** Attack charge of the landed hit as the server saw it. */
	public float hitCharge;
	public boolean sprintHit;
	/** The landed hit was a critical hit: made while falling, charged, and not sprinting as far as the server knew. */
	public boolean critDealt;
	/**
	 * That critical hit was a punish crit: the fall it was made from came from the opponent's own hit
	 * knocking the agent into the air, not from a jump.
	 */
	public boolean punishCrit;
	/**
	 * The landed hit was an uppercut: a sprint-knockback hit made on the way up from the agent's own
	 * sprint-jump at the opponent.
	 */
	public boolean uppercut;
	/** The jump this step took off on was made by a rule: which one ({@code JumpIn.CHASE} and so on), or 0. */
	public int ruleJump;
	/** On the step that is being scored, W and S were the rule's that steps back after a hit of its own; see {@code StepBack}. */
	public boolean stepBack;
	/**
	 * The landed hit was a sweep: charged, on the ground, without sprint knockback and moving slower
	 * than a sprint. Full damage, but it does not send the opponent anywhere.
	 */
	public boolean sweep;
	public float damageDealt;
	public int hitsTaken;
	/** How many of the hits taken were critical hits. */
	public int critsTaken;
	public float damageTaken;
	public boolean koDealt;
	public boolean koTaken;

	public void clear() {
		yawErrDeg = 0f;
		pitchErrDeg = 0f;
		missDeg = 0f;
		yawMissDeg = 0f;
		yawOnHead = false;
		onHead = false;
		onBody = false;
		enemyVisible = false;
		reachDist = 0f;
		discontinuity = false;
		clicked = false;
		clickOnTarget = false;
		chargeAtClick = 0f;
		charge = 0f;
		forwardAxis = 0;
		strafeAxis = 0;
		sprintKey = false;
		yawTurnDeg = 0f;
		pitchTurnDeg = 0f;
		agentPitchDeg = 0f;
		jumpKey = false;
		tookOff = false;
		jumpWindow = false;
		reachAtAction = 0f;
		serverSprinting = false;
		falling = false;
		onGround = false;
		inAir = false;
		critZone = 0f;
		critOpen = false;
		knockedUp = false;
		cannotAvoid = false;
		timedOut = false;
		ideaOn = false;
		ideaStrafe = 0;
		ideaCircle = false;
		ideaHold = 0;
		oppCharge = 1f;
		walkedInOnCooldown = false;
		walkedInOnReady = false;
		oppJumpIn = 0;
		hitsDealt = 0;
		hitCharge = 0f;
		sprintHit = false;
		critDealt = false;
		punishCrit = false;
		uppercut = false;
		ruleJump = 0;
		stepBack = false;
		sweep = false;
		damageDealt = 0f;
		hitsTaken = 0;
		critsTaken = 0;
		damageTaken = 0f;
		koDealt = false;
		koTaken = false;
	}
}
