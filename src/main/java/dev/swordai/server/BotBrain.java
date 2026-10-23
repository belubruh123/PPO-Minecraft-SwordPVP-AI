package dev.swordai.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import dev.swordai.core.curriculum.BotProfile;
import dev.swordai.core.env.Delay;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * The scripted mind of the sparring bot. The body is a Carpet fake player, so everything it does
 * goes through real player physics and the real attack code; this class only decides where to
 * look, which way to walk and when to swing.
 */
public final class BotBrain {
	/**
	 * For the training log, from the one that plays on the agent's rhythm ({@link BotProfile#timer}):
	 * jumps timed for the agent's sword, jumps that met the agent arriving, critical hits that came
	 * of each, and how many of either jump the agent hit it out of. Read and cleared by the trainer,
	 * which in a world of one's own runs in the same game.
	 */
	public static final java.util.concurrent.atomic.AtomicIntegerArray TIMER_COUNTS = new java.util.concurrent.atomic.AtomicIntegerArray(6);
	public static final int TIMER_GAP_JUMPS = 0;
	public static final int TIMER_ARRIVE_JUMPS = 1;
	public static final int TIMER_GAP_CRITS = 2;
	public static final int TIMER_ARRIVE_CRITS = 3;
	public static final int TIMER_HIT_IN_JUMP = 4;
	public static final int TIMER_PUNISH_CRITS = 5;
	/** How long the bot backs off after landing a hit when it is allowed to W-tap. */
	private static final int BACKOFF_TICKS = 5;
	/**
	 * For a crit it comes in to this distance, eye to hitbox: far closer than for a sprint hit. A
	 * critical hit knocks back little, and it is made from on top of the opponent.
	 */
	private static final double CRIT_RANGE = 1.5;
	/**
	 * It only jumps for a crit from this near. A jump takes seven ticks to its way down, and the agent
	 * does not wait for it; from the edge of its reach (which is where it jumped from up to 0.13.0) the
	 * agent was mostly gone by then, and the bot came down having hit nothing.
	 */
	private static final double CRIT_JUMP_REACH = 2.2;
	/** In its crit jump it keeps walking in on the agent until it is this near. */
	private static final double CRIT_CLOSE = 1.0;
	/** After a critical hit it means to stay on top of, it runs in from beyond this distance and walks from within it. */
	private static final double PRESS_RUN_FROM = 2.5;
	/** It jumps for a crit from this charge on, so the sword is ready by the time it comes down. */
	private static final float CRIT_JUMP_CHARGE = 0.4f;
	/** A crit that has not come off after this many ticks is given up for an ordinary hit. */
	private static final int CRIT_PATIENCE_TICKS = 50;
	/**
	 * How long it keeps coming at the agent after a critical hit it means to stay on top of: its own
	 * way down from the jump, and then the time the agent is in the air from the hit.
	 */
	private static final int PRESS_TICKS = 16;
	/** Going round the agent it keeps this far from it, eye to hitbox: inside the reach it jumps for a crit from. */
	private static final double ORBIT_RANGE = 1.8;
	/** From farther off than this it comes in at a run before it starts going round. */
	private static final double ORBIT_RUN_FROM = 4.0;
	/** A run away from the agent lasts this long: a second and a quarter to three seconds. */
	private static final int FLEE_MIN_TICKS = 25;
	private static final int FLEE_MORE_TICKS = 36;
	/** Keeping away for good, it turns and runs once the agent is nearer than this, and looks back at it from farther off than a little more. */
	private static final double KEEP_AWAY_RUN = 7.0;
	private static final double KEEP_AWAY_LOOK = 9.0;
	/** Caught by a hit, it fights for this long before it is off again: two to five seconds. */
	private static final int CAUGHT_MIN_TICKS = 40;
	private static final int CAUGHT_MORE_TICKS = 60;
	/** Holding its ground it jumps for its critical hits from this far off, so that it is coming down as the agent arrives. */
	private static final double HOLD_JUMP_REACH = 3.4;
	/** And walks back to where it was put once it is farther from there than this. */
	private static final double POST_SLACK = 0.7;
	private static final float SPIN_DEG = 36f;
	/**
	 * A running jump for a critical hit ({@link BotProfile#jumpIn}): by the tick it is well on its way
	 * down, the ninth, it has covered this much ground, and it jumps from where that and what the
	 * agent will have moved by then leave the agent this near.
	 */
	private static final int RUN_JUMP_TICKS = 9;
	private static final double RUN_JUMP_COVERS = 2.8;
	private static final double RUN_JUMP_ENDS_WITHIN = 2.85;
	private static final double RUN_JUMP_FARTHEST = 5.6;
	/**
	 * The jump is for an agent that is going away from it at this much a tick or more (walking
	 * backwards is 0.2) and is out of its reach or all but. An agent that stands or comes in gets the
	 * sprint hit, on rhythm, from the edge of its reach.
	 */
	private static final double RUN_JUMP_AT = 0.08;
	private static final double RUN_JUMP_BEYOND = 2.7;
	/** Hit, it means its next swing for its way down for this long; see {@link BotProfile#punish}. */
	private static final int PUNISH_TICKS = 34;
	/** Not thrown up by then (and that long again for a knockback that is late), it never will be. */
	private static final int PUNISH_LIFT_TICKS = 4;
	/** The one that clicks as fast as it can swings with its sword this far back: every three or four ticks. */
	private static final float SPAM_CHARGE = 0.25f;
	/**
	 * The one that plays on the agent's rhythm ({@link BotProfile#timer}) holds this far off, eye to
	 * hitbox, while the agent's sword is on its way back: a step outside the agent's reach. With the
	 * agent's sword back it keeps farther off, so that the agent has to run in to it.
	 */
	private static final double TIMER_RANGE = 3.4;
	private static final double TIMER_FAR = 5.4;
	/** The agent's reach, eye to hitbox. */
	private static final double TIMER_AGENT_REACH = 3.0;
	/**
	 * An agent running in at it with its sword back it meets with a jump taken this many ticks before
	 * the agent will be in reach: with what the agent sees of it two or three ticks old, it is on its
	 * way down by the time the agent's swing comes, and the agent's sprint hit and its critical hit
	 * come at once.
	 */
	private static final boolean TIMER_ARRIVE_JUMPS_ON = false;
	private static final int TIMER_ARRIVE_MIN = 4;
	private static final int TIMER_ARRIVE_MAX = 6;
	/** The tick after its last swing on which the agent swings again: with its sword all the way back, as it always has. */
	private static final int TIMER_AGENT_READY = 12;
	/** From its take-off to its swing on the way down, coming into the agent's reach. */
	private static final int TIMER_LEAD_TICKS = 8;
	/** What a jump from a walk carries it in by then; one from a run carries it {@link #RUN_JUMP_COVERS}. */
	private static final double TIMER_WALK_COVERS = 0.8;
	/** It jumps where that leaves the agent this near, eye to hitbox, and not so near that it would come down past it. */
	private static final double TIMER_ENDS_WITHIN = 2.7;
	private static final double TIMER_ENDS_FROM = 0.4;
	/** An agent coming in at this much a tick or more, its sword back, is met with a jump. */
	private static final double TIMER_ARRIVING = 0.1;
	/**
	 * Crit, S, crit, S ({@link BotProfile#spacer}): while its sword comes back it holds this far off,
	 * eye to hitbox, a step outside the agent's reach; the jump in is taken from {@link #HOLD_JUMP_REACH}.
	 */
	private static final double SPACER_RANGE = 3.3;
	/** It goes in once its sword will be back this many ticks on, when its jump comes down. */
	private static final int SPACER_LEAD_TICKS = 6;
	/** After its hit it walks backwards for this long at most, or until it is out of the agent's reach. */
	private static final int SPACER_OUT_TICKS = 12;

	private final Random rng = new Random();
	private final float[] frame = new float[3];

	private BotProfile profile = new BotProfile();
	private boolean idle;
	private boolean anchored;
	private int strafeDir = 1;
	private int strafeTicks;
	private double wayX;
	private double wayZ;
	private int wayTicks;
	private float noiseYaw;
	private float noisePitch;
	private float noiseYawGoal;
	private float noisePitchGoal;
	private int noiseTicks;
	private int inReachTicks;
	private int backoffTicks;
	/** Ticks left of running straight at the agent after a critical hit; see {@link BotProfile#critPress}. */
	private int pressTicks;
	/** Ticks left of backing straight out after its hit; see {@link BotProfile#spacer}. */
	private int spacerOut;
	/** Ticks left in which its next swing follows up the hit it has just landed, with no reaction time. */
	private int followUpTicks;
	/** How many ticks late its next swing comes; see {@link BotProfile#swingSlip}. */
	private int slipTicks;
	/** Where it sees the agent: a few ticks late when the fight has lag; see {@link #setLag}. */
	private final Delay<Vec3> seenEye = new Delay<>(ReplicaBody.MAX_LAG_TICKS);
	private final Delay<AABB> seenBox = new Delay<>(ReplicaBody.MAX_LAG_TICKS);
	/** The next swing is meant to be a jumped critical hit. */
	private boolean critPlan;
	private int critPlanTicks;
	/** It has jumped for that critical hit and has not landed since: ticks since the jump, or -1. */
	private int critJump = -1;
	/** This fight it keeps pushing into the agent instead of holding its range. */
	private boolean rushing;
	/** Ticks left of running away from the agent; see {@link BotProfile#fleeChance}. */
	private int fleeTicks;
	/** In this run its back is turned: it looks where it is going, sprints, and cannot swing. */
	private boolean fleeBackTurned;
	/** Caught by a hit while keeping away: ticks left of fighting before it runs again; see {@link BotProfile#fleeUntilHit}. */
	private int caughtTicks;
	/** Where it was last put: the post it holds; see {@link BotProfile#holdGround}. */
	private double postX;
	private double postZ;
	/** Ticks left of what its antic is doing now, and which of its two things that is; see {@link BotProfile#antic}. */
	private int anticTicks;
	private boolean anticOn;
	private int anticDir;
	/** Where it saw the agent a tick ago, and how fast the agent is going away from it by that; see {@link BotProfile#jumpIn}. */
	private double seenX = Double.NaN;
	private double seenZ;
	private double agentAway;
	/** Ticks left of meaning its swing for its own way down from the agent's hit; see {@link BotProfile#punish}. */
	private int punishTicks;
	private boolean punishAir;
	private int lagTicks;
	/** It has just been hit and means to jump with it on its next tick; see {@link BotProfile#jumpResetChance}. */
	private boolean pendingReset;
	/** The tick after the agent's last swing on which its next critical hit is to come down; see {@link BotProfile#timer}. */
	private int timerLand = TIMER_AGENT_READY - 1;
	/** How long its sword had been on the way back as it saw it a tick ago. */
	private int agentTicksSeen;
	/** Which of its timed jumps it is in, as a {@code TIMER_} count: the gap, the arrival; or -1. */
	private int timerJump = -1;

	/** Its chances from here on are drawn from this seed: a fight set up again exactly; see EpisodePlan.seed. */
	void reseed(long seed) {
		rng.setSeed(seed);
		// And none of the last fight carried over.
		strafeDir = 1;
		noiseYaw = 0f;
		noisePitch = 0f;
		noiseYawGoal = 0f;
		noisePitchGoal = 0f;
		noiseTicks = 0;
	}

	void setProfile(BotProfile profile) {
		this.profile = profile;
		strafeTicks = 0;
		wayTicks = 0;
		inReachTicks = 0;
		backoffTicks = 0;
		pressTicks = 0;
		spacerOut = 0;
		critJump = -1;
		followUpTicks = 0;
		seenEye.clear();
		seenBox.clear();
		rushing = profile.rushChance > 0f && rng.nextFloat() < profile.rushChance;
		fleeTicks = 0;
		caughtTicks = 0;
		anticTicks = 0;
		anticOn = false;
		seenX = Double.NaN;
		agentAway = 0;
		punishTicks = 0;
		punishAir = false;
		agentTicksSeen = 0;
		timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
		if (profile.fleeUntilHit || profile.fleeChance > 0f && rng.nextFloat() < 0.5f * profile.fleeChance) {
			// Some fights it runs from the start; the one that keeps away always does.
			startFlee();
		}
		planNextSwing();
	}

	/** Where it has been put: the spot it goes back to if it is one that holds its ground. */
	void setPost(double x, double z) {
		postX = x;
		postZ = z;
	}

	private boolean antic(String name) {
		return name.equals(profile.antic);
	}

	private void startFlee() {
		fleeTicks = profile.fleeUntilHit ? Integer.MAX_VALUE / 2 : FLEE_MIN_TICKS + rng.nextInt(FLEE_MORE_TICKS);
		fleeBackTurned = profile.fleeUntilHit || rng.nextFloat() < profile.fleeTurned;
		wayTicks = 0;
		backoffTicks = 0;
		pressTicks = 0;
		spacerOut = 0;
		followUpTicks = 0;
	}

	private void planNextSwing() {
		// A swing that follows up a hit comes on rhythm; a jump for a crit is how a new attack begins.
		// The one that goes round the agent makes every hit it can a jumped one.
		critPlan = (followUpTicks <= 0 || profile.orbit || profile.jumpIn) && profile.critChance > 0f && rng.nextFloat() < profile.critChance;
		critPlanTicks = 0;
		slipTicks = profile.swingSlip > 0f && rng.nextFloat() < profile.swingSlip ? 1 + rng.nextInt(Math.max(1, profile.swingSlipTicks)) : 0;
	}

	/**
	 * How many ticks late it sees the agent, as a player on a server would. It aims at, walks up to and
	 * swings at the agent where that was; a swing that connects there lands on the agent where it is
	 * now, which to the agent is being hit from where it had already left.
	 */
	void setLag(int ticks) {
		lagTicks = Math.max(0, ticks);
		seenEye.setTicks(ticks);
		seenBox.setTicks(ticks);
	}

	/** An anchored bot keeps its feet where they are (it was put on a pillar) but still looks and may jump. */
	void setAnchored(boolean anchored) {
		this.anchored = anchored;
	}

	/** While idle the bot only stands and looks at the agent (during PPO updates, pauses and the self-test). */
	void setIdle(boolean idle) {
		this.idle = idle;
	}

	void tick(ServerPlayer bot, ServerPlayer agent, Arena arena) {
		EntityPlayerActionPack pack = ((ServerPlayerInterface) bot).getActionPack();
		Vec3 botEye = bot.getEyePosition();
		Vec3 agentEye = seenEye.pass(agent.getEyePosition());
		double dx = agentEye.x - botEye.x;
		double dz = agentEye.z - botEye.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		// How fast the agent is going away from it, as it sees it.
		agentAway = Double.isNaN(seenX) || flat < 1e-6 ? 0 : ((agentEye.x - seenX) * dx + (agentEye.z - seenZ) * dz) / flat;
		seenX = agentEye.x;
		seenZ = agentEye.z;

		// Running away: towards somewhere in the arena that is away from the agent.
		if (profile.fleeUntilHit && fleeTicks <= 0 && !idle && --caughtTicks <= 0) {
			// It has fought for long enough: off again.
			startFlee();
		}
		boolean fleeing = !idle && !anchored && fleeTicks > 0 && BotProfile.FIGHT.equals(profile.movement);
		if (fleeing && profile.fleeUntilHit) {
			// With the agent near it has its back to it and runs; from far off it turns round to look.
			if (flat < KEEP_AWAY_RUN) {
				fleeBackTurned = true;
			} else if (flat > KEEP_AWAY_LOOK) {
				fleeBackTurned = false;
			}
		}
		double fleeX = 0;
		double fleeZ = 0;
		if (fleeing) {
			double wdx = wayX - bot.getX();
			double wdz = wayZ - bot.getZ();
			if (--wayTicks <= 0 || wdx * wdx + wdz * wdz < 2.25) {
				pickRefuge(bot, agent, arena);
				wdx = wayX - bot.getX();
				wdz = wayZ - bot.getZ();
			}
			double far = Math.sqrt(wdx * wdx + wdz * wdz);
			fleeX = far > 1e-6 ? wdx / far : 0;
			fleeZ = far > 1e-6 ? wdz / far : 0;
			if (--fleeTicks == 0) {
				// It turns and fights again.
				planNextSwing();
			}
		}
		if (fleeing && fleeBackTurned) {
			// Its eyes are on where it is going.
			aim(bot, pack, fleeX, 0, fleeZ);
		} else if (!idle && antic(BotProfile.ANTIC_SPIN)) {
			// Round and round, whatever is in front of it.
			pack.look(bot.getYRot() + SPIN_DEG, 0f);
			bot.setYHeadRot(bot.getYRot());
		} else {
			aim(bot, pack, dx, agentEye.y - 0.25 - botEye.y, dz);
		}

		if (idle) {
			pack.setForward(0f);
			pack.setStrafing(0f);
			pack.setSprinting(false);
			return;
		}

		AABB agentBox = seenBox.pass(agent.getBoundingBox());
		double reachDist = Math.sqrt(agentBox.distanceToSqr(botEye));
		// How long the agent's sword has been on its way back since its last swing.
		float agentScale = agent.getAttackStrengthScale(0f);
		int agentTicks = agentScale >= 1f ? Integer.MAX_VALUE / 2 : Math.round(agentScale * agent.getCurrentItemAttackStrengthDelay());
		if (profile.timer) {
			if (agentTicks < agentTicksSeen) {
				// The agent has swung: where its next critical hit comes down is drawn afresh, a tick or two
				// before the agent's sword is back.
				timerLand = TIMER_AGENT_READY - 1 - rng.nextInt(2);
			}
		}
		agentTicksSeen = agentTicks;
		if (profile.jumpIn && critJump < 0 && punishTicks <= 0 && !fleeing) {
			// Which hit its next one is, is decided by what the agent is doing right now; for the one that only jumps in, it
			// is always the jump.
			critPlan = profile.jumpInAlways ? reachDist > CRIT_CLOSE : agentAway >= RUN_JUMP_AT && reachDist > RUN_JUMP_BEYOND;
		}

		// Desired walking direction in world space.
		double mx = 0;
		double mz = 0;
		boolean sprint = false;
		double ux = flat > 1e-6 ? dx / flat : 0;
		double uz = flat > 1e-6 ? dz / flat : 0;
		// Unit vector to the bot's left when it faces the agent.
		double lx = uz;
		double lz = -ux;

		if (--strafeTicks <= 0) {
			strafeDir = rng.nextBoolean() ? 1 : -1;
			// Going round the agent it keeps a direction for longer: a second and a half to four and a half.
			strafeTicks = profile.orbit ? 30 + rng.nextInt(60) : 10 + rng.nextInt(30);
		}

		switch (anchored || profile.movement == null ? BotProfile.STAND : profile.movement) {
			case BotProfile.STRAFE -> {
				mx = lx * strafeDir;
				mz = lz * strafeDir;
				if (flat < profile.minDistance) {
					mx -= ux;
					mz -= uz;
				} else if (flat > profile.maxDistance) {
					mx += ux;
					mz += uz;
				}
			}
			case BotProfile.WANDER -> {
				double wdx = wayX - bot.getX();
				double wdz = wayZ - bot.getZ();
				if (--wayTicks <= 0 || wdx * wdx + wdz * wdz < 1.0) {
					pickWaypoint(agent, arena);
					wdx = wayX - bot.getX();
					wdz = wayZ - bot.getZ();
				}
				mx = wdx;
				mz = wdz;
				sprint = profile.sprint;
			}
			case BotProfile.APPROACH, BotProfile.FIGHT -> {
				// For a crit it walks in close and does not sprint: a sprinting swing is never a crit.
				double range = critPlan ? Math.min(profile.preferredRange, CRIT_RANGE) : profile.preferredRange;
				if (profile.holdGround) {
					// It stands where it was put, and walks back there if a hit has moved it.
					backoffTicks = 0;
					double px = postX - bot.getX();
					double pz = postZ - bot.getZ();
					if (px * px + pz * pz > POST_SLACK * POST_SLACK && critJump < 0) {
						mx = px;
						mz = pz;
					}
				} else if (antic(BotProfile.ANTIC_STATUE) || antic(BotProfile.ANTIC_ZIGZAG)) {
					backoffTicks = 0;
					if (--anticTicks <= 0) {
						anticOn = !anticOn;
						boolean statue = antic(BotProfile.ANTIC_STATUE);
						// A statue stands for two to five seconds and runs for one and a half to three and a half;
						// the other one goes a new way every few ticks.
						anticTicks = statue ? (anticOn ? 30 + rng.nextInt(40) : 40 + rng.nextInt(60)) : 3 + rng.nextInt(6);
						anticDir = rng.nextInt(5);
					}
					if (antic(BotProfile.ANTIC_STATUE)) {
						if (anticOn) {
							mx = ux;
							mz = uz;
							sprint = profile.sprint;
						}
					} else {
						// Towards the agent, to either side of that, or straight sideways.
						double fwd = anticDir <= 2 ? 1.0 : 0.0;
						double sideways = anticDir == 0 ? 0.0 : anticDir % 2 == 1 ? 1.0 : -1.0;
						if (reachDist < range - 0.6) {
							fwd = -1.0;
						}
						mx = ux * fwd + lx * sideways;
						mz = uz * fwd + lz * sideways;
						sprint = profile.sprint && fwd > 0;
					}
				} else if (fleeing) {
					backoffTicks = 0;
					if (fleeBackTurned) {
						mx = fleeX;
						mz = fleeZ;
						sprint = profile.sprint;
					} else if (profile.fleeUntilHit) {
						// Far enough off: it stands and watches the agent come.
					} else {
						// Backing off with its eyes on the agent: at a walk, which is all backwards is.
						mx = -ux;
						mz = -uz;
					}
				} else if (profile.orbit) {
					// Round the agent, at a walk: sideways, and in or out to keep its distance. In its jump
					// for a critical hit it goes on round, so it comes down beside where it took off.
					backoffTicks = 0;
					if (reachDist > ORBIT_RUN_FROM) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && critJump < 0;
					} else {
						double inward = Math.max(-1.0, Math.min(1.0, (reachDist - ORBIT_RANGE) * 1.5));
						mx = lx * strafeDir + ux * inward;
						mz = lz * strafeDir + uz * inward;
					}
				} else if (critJump >= 0) {
					// In its jump for a critical hit: it keeps walking in on the agent, so that it is on top
					// of it when it comes down, and never at a sprint (a sprinting swing is no critical hit).
					backoffTicks = 0;
					if (reachDist > CRIT_CLOSE) {
						mx = ux;
						mz = uz;
						// A running jump is run up to its top; the sprint is off for the way down.
						sprint = (profile.jumpIn || profile.timer) && punishTicks <= 0 && profile.sprint && bot.getDeltaMovement().y > 0;
					}
				} else if (profile.spacer && punishTicks <= 0 && !critPlan && spacerOut <= 0) {
					// Standing at the edge of the reach: it does not move, and hits whatever comes into its
					// reach (in tryAttack). Only an agent far off is walked up to.
					backoffTicks = 0;
					if (reachDist > SPACER_RANGE + 2.0) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && reachDist > SPACER_RANGE + 3.0;
					}
				} else if (profile.spacer && punishTicks <= 0) {
					// Crit, S, crit, S. After its hit, straight back out of the agent's reach; while its sword
					// comes back, a step outside that reach; with it about to be back, in (the jump from the
					// edge of the reach is below). Never to the side, never at a sprint near the agent.
					backoffTicks = 0;
					boolean ready = bot.getAttackStrengthScale(0.5f + SPACER_LEAD_TICKS) >= profile.attackChargeMin;
					if (spacerOut > 0 && reachDist < SPACER_RANGE + 0.2) {
						spacerOut--;
						mx = -ux;
						mz = -uz;
					} else {
						spacerOut = 0;
						if (ready || reachDist > SPACER_RANGE + 0.15) {
							mx = ux;
							mz = uz;
							sprint = profile.sprint && reachDist > SPACER_RANGE + 1.5;
						} else if (reachDist < SPACER_RANGE - 0.1) {
							mx = -ux;
							mz = -uz;
						}
					}
				} else if (pressTicks > 0) {
					// It has just landed a critical hit and stays on top of the agent, whatever it means to
					// do next: at a run while the agent is still a way off, then at a walk, so that its next
					// jump is not a running one that carries it past. The agent comes down from that hit
					// with the bot still in its reach.
					backoffTicks = 0;
					if (reachDist > CRIT_CLOSE) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && reachDist > PRESS_RUN_FROM;
					}
				} else if (profile.timer && punishTicks <= 0) {
					// A step outside the agent's reach while its sword is on the way back; farther with it back.
					backoffTicks = 0;
					double want = agentTicks < TIMER_AGENT_READY || !TIMER_ARRIVE_JUMPS_ON ? TIMER_RANGE : TIMER_FAR;
					if (reachDist > want + 0.15) {
						mx = ux;
						mz = uz;
						sprint = profile.sprint && reachDist > want + 1.5;
					} else if (reachDist < want - 0.15) {
						mx = -ux;
						mz = -uz;
					}
				} else if (rushing && BotProfile.FIGHT.equals(profile.movement)) {
					// Straight at the agent, always: it wants to be in its face and never backs off.
					backoffTicks = 0;
					mx = ux;
					mz = uz;
					sprint = profile.sprint && (!critPlan || profile.jumpIn);
				} else if (backoffTicks > 0) {
					backoffTicks--;
					mx = -ux;
					mz = -uz;
				} else if (reachDist > range + 0.15) {
					mx = ux;
					mz = uz;
					sprint = profile.sprint && (!critPlan || profile.jumpIn);
				} else if (reachDist < range - 0.6) {
					mx = -ux;
					mz = -uz;
				}
				// Its sidestepping, except while it is going straight in for a critical hit or staying on top of one.
				if (reachDist < 4.5 && critJump < 0 && pressTicks <= 0 && !fleeing && !profile.orbit && !profile.holdGround && !profile.spacer
						&& !antic(BotProfile.ANTIC_STATUE) && !antic(BotProfile.ANTIC_ZIGZAG)) {
					mx += 0.6 * lx * strafeDir;
					mz += 0.6 * lz * strafeDir;
				}
			}
			default -> {
				// stand
			}
		}

		// Never push into the wall: drop the component that points out of the arena.
		double margin = arena.radius - 1.2;
		if (Math.abs(bot.getX() - arena.centerX) > margin && Math.signum(mx) == Math.signum(bot.getX() - arena.centerX)) {
			mx = 0;
		}
		if (Math.abs(bot.getZ() - arena.centerZ) > margin && Math.signum(mz) == Math.signum(bot.getZ() - arena.centerZ)) {
			mz = 0;
		}

		double len = Math.sqrt(mx * mx + mz * mz);
		if (len > 1e-6) {
			ViewGeometry.toYawFrame(mx / len, 0, mz / len, bot.getYRot(), frame);
			float forward = frame[2];
			pack.setForward(forward);
			// Carpet's strafing input is positive to the left.
			pack.setStrafing(-frame[0]);
			pack.setSprinting(sprint && forward > 0.7f);
		} else {
			pack.setForward(0f);
			pack.setStrafing(0f);
			pack.setSprinting(false);
		}

		if (pendingReset) {
			// The jump made with a hit: W and the sprint held, on the tick after it.
			pendingReset = false;
			// (For one that answers its hits on the way down, the jump is that way down.)
			if (bot.onGround() && (critJump < 0 || punishTicks > 0)) {
				pack.setForward(1f);
				pack.setSprinting(true);
				pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
			}
		}
		float jumpChance = antic(BotProfile.ANTIC_HOP) ? Math.max(0.5f, profile.jumpChance) : profile.jumpChance;
		if (jumpChance > 0f && bot.onGround() && critJump < 0 && rng.nextFloat() < jumpChance) {
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
		}
		if (antic(BotProfile.ANTIC_DUCK)) {
			// Down for a quarter of a second, up for a quarter of a second.
			pack.setSneaking((bot.tickCount / 5) % 2 == 0);
		} else if (bot.isShiftKeyDown()) {
			pack.setSneaking(false);
		}

		if (pressTicks > 0) {
			pressTicks--;
		}
		if (punishTicks > 0) {
			// Thrown up by the agent's hit: the way down is where its swing is meant for. Once it is
			// down again, or was never thrown up at all, that is over.
			punishAir |= !bot.onGround();
			punishTicks--;
			boolean never = !punishAir && punishTicks < PUNISH_TICKS - (2 * lagTicks + PUNISH_LIFT_TICKS);
			if (punishTicks <= 0 || never || punishAir && bot.onGround()) {
				punishTicks = 0;
				critJump = -1;
				planNextSwing();
			}
		} else if (critJump >= 0 && ++critJump > 2 && bot.onGround()) {
			// Down again. If the swing was not made, the plan stands and it jumps again.
			critJump = -1;
			timerJump = -1;
		}
		if (critPlan && punishTicks <= 0 && ++critPlanTicks > CRIT_PATIENCE_TICKS) {
			critPlan = false;
		}
		// A running jump is taken from where its way down will bring the agent into reach.
		boolean runJump = profile.jumpIn
				&& reachDist <= Math.min(RUN_JUMP_FARTHEST, RUN_JUMP_ENDS_WITHIN + RUN_JUMP_COVERS - RUN_JUMP_TICKS * Math.max(0, agentAway));
		// The one that only jumps in, from the distance drawn for the fight: a running jump from far, a hop from a walk near.
		boolean fixedJump = profile.jumpInAlways && profile.jumpInFrom > 0f;
		if (fixedJump) {
			runJump = reachDist <= profile.jumpInFrom && profile.jumpInFrom >= BotProfile.JUMP_IN_RUN_FROM;
		}
		if (profile.timer && critPlan && critJump < 0 && punishTicks <= 0 && !fleeing && profile.attacks && bot.onGround()
				&& bot.getAttackStrengthScale(0.5f + TIMER_LEAD_TICKS) >= profile.attackChargeMin) {
			// Where the jump would leave the agent by the time it is coming down into its reach.
			double ends = reachDist - (bot.isSprinting() ? RUN_JUMP_COVERS : TIMER_WALK_COVERS) + TIMER_LEAD_TICKS * agentAway;
			boolean lands = ends <= TIMER_ENDS_WITHIN && ends >= TIMER_ENDS_FROM;
			// With its sword back, the agent running in is met so that the jump is on its way down as the
			// agent comes into reach.
			double ownIn = bot.getDeltaMovement().x * ux + bot.getDeltaMovement().z * uz;
			double closing = -agentAway + Math.max(0.1, ownIn);
			double toReach = (reachDist - TIMER_AGENT_REACH) / Math.max(0.05, closing);
			boolean arrives = agentAway <= -TIMER_ARRIVING && toReach >= TIMER_ARRIVE_MIN && toReach <= TIMER_ARRIVE_MAX;
			// Down a tick or two before the agent's sword is back; or, with it back, as the agent arrives.
			// (The jump at an agent arriving is left out: the agent ran at it and hit it out of the air on
			// nine jumps in ten, 121 of 132.)
			boolean onTime = agentTicks < TIMER_AGENT_READY ? lands && Math.abs(agentTicks - (timerLand - TIMER_LEAD_TICKS)) <= 1 : arrives && TIMER_ARRIVE_JUMPS_ON;
			if (onTime) {
				pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
				critJump = 0;
				timerJump = agentTicks < TIMER_AGENT_READY ? TIMER_GAP_JUMPS : TIMER_ARRIVE_JUMPS;
				TIMER_COUNTS.incrementAndGet(timerJump);
			}
		}
		if (!profile.timer && critPlan && critJump < 0 && !fleeing && profile.attacks && bot.onGround()
				&& (runJump || reachDist <= (fixedJump ? profile.jumpInFrom : profile.holdGround || profile.spacer ? HOLD_JUMP_REACH : CRIT_JUMP_REACH))
				&& bot.getAttackStrengthScale(0.5f) >= CRIT_JUMP_CHARGE
				&& (!profile.spacer || spacerOut <= 0 && bot.getAttackStrengthScale(0.5f + SPACER_LEAD_TICKS) >= profile.attackChargeMin)) {
			if (!runJump) {
				// From a walk, never from a run: a running jump would carry it past the agent, and the sprint
				// has to be off by the time it swings.
				pack.setSprinting(false);
				bot.setSprinting(false);
			}
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
			critJump = 0;
		}

		if (followUpTicks > 0 && --followUpTicks == 0) {
			// Its combo has run out: what it does next is planned afresh.
			planNextSwing();
		}
		// With its back turned it has nothing under its crosshair and does not swing.
		if (profile.attacks && !(fleeing && fleeBackTurned)) {
			tryAttack(bot, agent, botEye, agentBox);
		}
	}

	/** One of its swings has hurt the agent: for a while the next one follows on rhythm, not on sight. */
	void landedHit() {
		followUpTicks = profile.followUpTicks;
		if (followUpTicks > 0 && !profile.orbit && !profile.jumpIn) {
			critPlan = false;
		}
		if (!profile.fleeUntilHit && profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			// Hit and run.
			startFlee();
		}
	}

	/** The agent has hit it: now and then it backs off instead of walking straight back in. */
	void tookHit() {
		pendingReset = profile.jumpResetChance > 0f && rng.nextFloat() < profile.jumpResetChance;
		if (timerJump >= 0) {
			TIMER_COUNTS.incrementAndGet(TIMER_HIT_IN_JUMP);
			timerJump = -1;
		}
		if (profile.punish && profile.attacks && fleeTicks <= 0) {
			// Its next swing is for its own way down from this hit, and a critical one. As with a jump
			// of its own: it walks in on the agent meanwhile, and the swing waits for nothing.
			punishTicks = PUNISH_TICKS;
			punishAir = false;
			critPlan = true;
			critPlanTicks = 0;
			critJump = 0;
			backoffTicks = 0;
			return;
		}
		if (profile.dodgeChance > 0f && rng.nextFloat() < profile.dodgeChance) {
			backoffTicks = Math.max(1, profile.dodgeTicks);
		}
		if (profile.fleeUntilHit) {
			if (fleeTicks > 0) {
				// Caught: it turns and fights, for a while.
				fleeTicks = 0;
				caughtTicks = CAUGHT_MIN_TICKS + rng.nextInt(CAUGHT_MORE_TICKS);
				planNextSwing();
			}
		} else if (profile.fleeChance > 0f && fleeTicks <= 0 && rng.nextFloat() < profile.fleeChance) {
			startFlee();
		}
	}

	/** Turns towards the agent at a limited rate, with slowly drifting aim error. */
	private void aim(ServerPlayer bot, EntityPlayerActionPack pack, double dx, double dy, double dz) {
		if (--noiseTicks <= 0) {
			noiseTicks = 6;
			noiseYawGoal = (float) rng.nextGaussian() * profile.aimNoiseDeg;
			noisePitchGoal = (float) rng.nextGaussian() * profile.aimNoiseDeg * 0.5f;
		}
		noiseYaw += (noiseYawGoal - noiseYaw) * 0.35f;
		noisePitch += (noisePitchGoal - noisePitch) * 0.35f;
		float noiseScale = idle ? 0f : 1f;

		float wantYaw = ViewGeometry.yawTo(dx, dz) + noiseYaw * noiseScale;
		float wantPitch = ViewGeometry.pitchTo(dx, dy, dz) + noisePitch * noiseScale;
		float rate = idle ? 180f : profile.turnRateDeg;
		float yaw = bot.getYRot() + clamp(ViewGeometry.wrapDegrees(wantYaw - bot.getYRot()), -rate, rate);
		float pitch = bot.getXRot() + clamp(wantPitch - bot.getXRot(), -rate, rate);
		pack.look(yaw, pitch);
		bot.setYHeadRot(bot.getYRot());
	}

	/** Swings exactly when a vanilla client could: target under the crosshair, within reach, weapon charged. */
	private void tryAttack(ServerPlayer bot, ServerPlayer agent, Vec3 botEye, AABB agentBox) {
		// A follow-up swing may reach a little farther than a sword does; see BotProfile.followUpReach.
		double reach = bot.entityInteractionRange() + (followUpTicks > 0 ? profile.followUpReach : 0f);
		AABB target = agentBox.inflate(agent.getPickRadius());
		Vec3 end = botEye.add(bot.getLookAngle().scale(reach));
		boolean canHit = target.contains(botEye) || target.clip(botEye, end).isPresent();
		inReachTicks = canHit ? inReachTicks + 1 : 0;
		if (!canHit && followUpTicks > 0 && profile.followUpWhiff > 0f
				&& bot.getAttackStrengthScale(0.5f) >= profile.attackChargeMin) {
			// The next swing of a combo comes on rhythm. If the agent has backed out of reach by then,
			// but not out of sight of a hit, it is thrown anyway and misses: the combo is over, the
			// weapon is recharging and the agent has its opening.
			double nx = Math.max(target.minX, Math.min(botEye.x, target.maxX));
			double ny = Math.max(target.minY, Math.min(botEye.y, target.maxY));
			double nz = Math.max(target.minZ, Math.min(botEye.z, target.maxZ));
			double far = reach + profile.followUpWhiff;
			if (botEye.distanceToSqr(nx, ny, nz) <= far * far) {
				bot.swing(InteractionHand.MAIN_HAND);
				followUpTicks = 0;
				inReachTicks = 0;
				planNextSwing();
				return;
			}
		}
		// Following up a hit it has just landed takes no reaction time: the swing was coming anyway. Nor
		// does the swing of a jump made for a critical hit: it was decided on when it jumped. (Up to
		// 0.13.0 it did. The crosshair had to have been on the agent for the whole reaction time without
		// a break, and the way down lasts five ticks; against an agent that moves it came down again
		// and again without swinging.)
		boolean jumped = critPlan && critJump >= 0;
		boolean planned = followUpTicks > 0 || jumped;
		// And now and then it is late with it, as a person is; see BotProfile.swingSlip.
		// One that turns on the spot swings the moment the agent passes under its crosshair, and one
		// that clicks as fast as it can does not wait for anything.
		boolean atOnce = antic(BotProfile.ANTIC_SPIN) || antic(BotProfile.ANTIC_SPAM);
		int needed = atOnce ? 0 : (planned ? 0 : profile.reactionTicks) + (jumped ? 0 : slipTicks);
		if (!canHit || inReachTicks < needed) {
			return;
		}
		if (bot.getAttackStrengthScale(0.5f) < (antic(BotProfile.ANTIC_SPAM) ? SPAM_CHARGE : profile.attackChargeMin)) {
			return;
		}
		if (antic(BotProfile.ANTIC_STATUE) && !anticOn) {
			// Standing like a statue it does not swing either.
			return;
		}
		// The one that plays on the agent's rhythm keeps its swing for the way down only in a jump of
		// its own or from the agent's hit: on its feet it hits whatever comes into its reach, first.
		boolean critSwing = critPlan && (!profile.timer || critJump >= 0 || punishTicks > 0);
		if (critSwing) {
			// Only on the way down from its jump, and not sprinting: that is what makes the hit critical.
			if (bot.onGround() || bot.fallDistance <= 0) {
				return;
			}
			bot.setSprinting(false);
		}
		boolean crit = critSwing;
		if (profile.timer && crit) {
			TIMER_COUNTS.incrementAndGet(punishTicks > 0 ? TIMER_PUNISH_CRITS : timerJump == TIMER_ARRIVE_JUMPS ? TIMER_ARRIVE_CRITS : TIMER_GAP_CRITS);
		}
		timerJump = -1;
		bot.attack(agent);
		// Swinging is what resets the cooldown on the server, exactly as for a real player's packet.
		bot.swing(InteractionHand.MAIN_HAND);
		critJump = -1;
		punishTicks = 0;
		if (profile.spacer) {
			// Out again after a critical hit; one that stands stays standing.
			spacerOut = crit ? SPACER_OUT_TICKS : 0;
		} else if (crit && !profile.orbit && profile.critPress > 0f && rng.nextFloat() < profile.critPress) {
			pressTicks = PRESS_TICKS;
		} else if (profile.wtap) {
			backoffTicks = BACKOFF_TICKS;
		}
		planNextSwing();
	}

	private void pickWaypoint(ServerPlayer agent, Arena arena) {
		double span = arena.radius - 2.0;
		for (int attempt = 0; attempt < 12; attempt++) {
			double x = arena.centerX + (rng.nextDouble() * 2 - 1) * span;
			double z = arena.centerZ + (rng.nextDouble() * 2 - 1) * span;
			double d = Math.hypot(x - agent.getX(), z - agent.getZ());
			wayX = x;
			wayZ = z;
			if (d >= profile.minDistance && d <= profile.maxDistance) {
				break;
			}
		}
		wayTicks = 30 + rng.nextInt(50);
	}

	/**
	 * Where it runs to: of a dozen spots in the arena, the one farthest from the agent that does not
	 * take it past the agent to get there.
	 */
	private void pickRefuge(ServerPlayer bot, ServerPlayer agent, Arena arena) {
		double span = arena.radius - 2.5;
		double awayX = bot.getX() - agent.getX();
		double awayZ = bot.getZ() - agent.getZ();
		double best = -1;
		for (int attempt = 0; attempt < 12; attempt++) {
			double x = arena.centerX + (rng.nextDouble() * 2 - 1) * span;
			double z = arena.centerZ + (rng.nextDouble() * 2 - 1) * span;
			double toX = x - bot.getX();
			double toZ = z - bot.getZ();
			// Not through the agent: the way there has to lead away from it, or at least not towards it.
			boolean leadsAway = toX * awayX + toZ * awayZ >= 0;
			double score = Math.hypot(x - agent.getX(), z - agent.getZ()) + (leadsAway ? 100 : 0);
			if (score > best) {
				best = score;
				wayX = x;
				wayZ = z;
			}
		}
		wayTicks = 30 + rng.nextInt(30);
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
