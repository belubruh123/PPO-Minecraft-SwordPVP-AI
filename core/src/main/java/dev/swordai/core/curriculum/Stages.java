package dev.swordai.core.curriculum;

import dev.swordai.core.env.TakenStreak;
import dev.swordai.core.rl.ActionSpace;

import java.util.ArrayList;
import java.util.List;

/**
 * The default cumulative curriculum. Each stage keeps every head the previous one had and adds
 * more, so nothing learned earlier is discarded.
 */
public final class Stages {
	private Stages() {
	}

	public static List<StageSpec> defaults() {
		List<StageSpec> s = new ArrayList<>();
		s.add(horizontalAim());
		s.add(horizontalTracking());
		s.add(verticalAim());
		s.add(footwork());
		s.add(hitTiming());
		s.add(sprintHitWTap());
		s.add(comboEscape());
		s.add(crits());
		s.add(duel());
		s.add(selfPlay());
		return s;
	}

	private static final int AIM = ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
	// Jumping stays locked until the crit stage: jumping into hits is a technique of its own, and a
	// fighter still learning to chain plain sprint hits only gets worse by hopping.
	private static final int MOVE = AIM | ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT);
	private static final int FIGHT = MOVE | ActionSpace.mask(ActionSpace.ATTACK);
	/** Three full fights of the sprint-hit stage per learning update. */
	private static final int FIGHT_ROLLOUT = 1800;
	private static final float FIGHT_ENTROPY = 0.3f;
	/**
	 * Per swing made before the weapon is back to 60% charge. It is the only charge on swinging, so it
	 * is set higher than it was while a miss cost something too.
	 */
	/**
	 * What a swing made before the weapon is 60% back costs. Heavy on purpose: more than half of what
	 * a full hit is worth, so that a tap that lands is still a loss. (0.15 up to 0.12.1.)
	 */
	private static final float SPAM = 0.6f;
	/**
	 * In the combo stages the aim is scored from this charge on: about three ticks before a sword is
	 * ready, which is the time there is to bring the crosshair back.
	 */
	private static final float COMBO_AIM_FROM = 0.75f;
	/** Stage 1 is where coming to rest on a standing opponent is learned, so it is part of passing it. */
	private static final float STAGE1_STILL = 0.50f;
	/**
	 * How often the opponent is put far away, and how far: across a duel arena rather than a few steps
	 * off. Up to 0.13.3 every fight began within ten blocks and every aiming target was within eight,
	 * so an opponent twenty blocks away, which is where a fight on a server begins, was a few pixels
	 * the fighter had never seen: it lost the opponent, turned on the spot and looked at the floor.
	 */
	private static final float FAR_AIM_SHARE = 0.25f;
	private static final float FAR_FIGHT_SHARE = 0.2f;
	private static final float FAR_MIN = 10f;
	private static final float FAR_MAX = 26f;
	/** The most the opponent's connection takes one way in an ordinary fight, in ticks; see {@link StageSpec#pathLagTicks}. */
	private static final int PATH_LAG = 2;
	/** From the stage after combos are learned: every other fight is against an opponent on a slow connection; see {@link StageSpec#slowShare}. */
	private static final float SLOW_SHARE = 0.5f;
	/** Against a copy of itself, one fight in five is on equal terms and counted; see {@link StageSpec#fairShare}. */
	private static final float FAIR_SHARE = 0.2f;
	/** How often a scripted opponent is late with a swing, from the stage after combos are learned; see {@link BotProfile#swingSlip}. */
	private static final float SWING_SLIP = 0.35f;
	/** From the crit stage on S stays pushed for in the ticks after a hit of a combo, to this share of them; see {@link StageSpec#backNudgeKeep}. */
	private static final float BACK_KEEP = 0.45f;

	/** A share of the times this opponent is placed, it is placed far off. */
	private static void farStarts(StageSpec st, float share) {
		st.bot.farChance = share;
		st.bot.farMinDistance = FAR_MIN;
		st.bot.farMaxDistance = FAR_MAX;
	}

	private static StageSpec horizontalAim() {
		StageSpec st = new StageSpec();
		st.name = "Horizontal aim";
		st.goal = "turn left/right onto a standing target";
		st.headMask = ActionSpace.mask(ActionSpace.YAW);
		st.bot.movement = BotProfile.STAND;
		// Inside the 51-degree half field of view with room to spare: looking around for a target
		// that is off screen is the next stage's lesson, not this one's.
		st.bot.maxBearingDeg = 40f;
		st.bot.hopIntervalTicks = 80;
		st.reward.aimProgress = 0.02f;
		st.reward.onTarget = 0.08f;
		st.reward.still = 0.08f;
		st.reward.hold = 0.04f;
		st.reward.aimError = 0.05f;
		st.reward.smooth = 0.03f;
		st.reward.visible = 0.03f;
		st.reward.search = 0.04f;
		st.lostResetTicks = 100;
		st.promote.minSteps = 15_000;
		st.promote.minOnTarget = 0.75f;
		st.promote.minStill = STAGE1_STILL;
		st.promote.maxAimError = 3f;
		return st;
	}

	private static StageSpec horizontalTracking() {
		StageSpec st = new StageSpec();
		st.name = "Horizontal tracking";
		st.goal = "find the target anywhere around you and follow it as it strafes";
		st.headMask = ActionSpace.mask(ActionSpace.YAW);
		st.bot.movement = BotProfile.STRAFE;
		st.bot.maxBearingDeg = 175f;
		st.bot.hopIntervalTicks = 120;
		farStarts(st, FAR_AIM_SHARE);
		st.reward.aimProgress = 0.02f;
		st.reward.onTarget = 0.08f;
		st.reward.still = 0.08f;
		st.reward.hold = 0.04f;
		st.reward.aimError = 0.05f;
		st.reward.smooth = 0.03f;
		st.reward.visible = 0.03f;
		st.reward.search = 0.04f;
		st.lostResetTicks = 120;
		st.promote.minSteps = 20_000;
		st.promote.minOnTarget = 0.55f;
		st.promote.maxAimError = 8f;
		return st;
	}

	private static StageSpec verticalAim() {
		StageSpec st = new StageSpec();
		st.name = "Vertical aim";
		st.goal = "add up/down aim and keep the crosshair on the opponent";
		st.headMask = AIM;
		st.agentMaxPillar = 3;
		st.startPitchRangeDeg = 35f;
		st.bot.movement = BotProfile.STRAFE;
		st.bot.minDistance = 1.8f;
		st.bot.maxBearingDeg = 120f;
		st.bot.maxPillar = 4;
		st.bot.jumpChance = 0.02f;
		st.bot.hopIntervalTicks = 100;
		farStarts(st, FAR_AIM_SHARE);
		st.reward.aimProgress = 0.02f;
		st.reward.onTarget = 0.08f;
		st.reward.still = 0.08f;
		st.reward.hold = 0.04f;
		st.reward.aimError = 0.05f;
		st.reward.smooth = 0.03f;
		st.reward.visible = 0.03f;
		st.reward.search = 0.04f;
		st.reward.blindPitch = 0.04f;
		st.lostResetTicks = 100;
		st.promote.minSteps = 30_000;
		st.promote.minOnTarget = 0.55f;
		st.promote.maxAimError = 8f;
		return st;
	}

	private static StageSpec footwork() {
		StageSpec st = new StageSpec();
		st.name = "Footwork";
		st.goal = "move with W/A/S/D and sprint while keeping the target in the crosshair and in range";
		st.headMask = MOVE;
		st.episodeTicks = 500;
		st.startPitchRangeDeg = 20f;
		st.bot.movement = BotProfile.WANDER;
		st.bot.minDistance = 4f;
		st.bot.maxDistance = 10f;
		st.bot.maxBearingDeg = 120f;
		st.bot.jumpChance = 0.01f;
		farStarts(st, FAR_FIGHT_SHARE);
		st.reward.aimProgress = 0.01f;
		st.reward.onTarget = 0.07f;
		st.reward.still = 0.04f;
		st.reward.aimError = 0.04f;
		st.reward.smooth = 0.02f;
		st.reward.range = 0.06f;
		st.promote.minSteps = 30_000;
		st.promote.minOnTarget = 0.50f;
		st.promote.minInRange = 0.45f;
		st.reward.visible = 0.03f;
		st.lostResetTicks = 160;
		st.reward.hold = 0.04f;
		st.reward.search = 0.04f;
		st.reward.blindPitch = 0.04f;
		return st;
	}

	private static StageSpec hitTiming() {
		StageSpec st = new StageSpec();
		st.name = "Hit timing";
		st.goal = "swing when a hit is there to be had, on a full cooldown, and never spam";
		st.headMask = FIGHT;
		st.episodeTicks = 500;
		st.startPitchRangeDeg = 20f;
		st.bot.movement = BotProfile.WANDER;
		st.bot.minDistance = 3f;
		st.bot.maxDistance = 8f;
		st.bot.maxBearingDeg = 120f;
		st.bot.jumpChance = 0.01f;
		farStarts(st, FAR_FIGHT_SHARE);
		st.reward.aimProgress = 0.005f;
		st.reward.onTarget = 0.03f;
		st.reward.still = 0.01f;
		st.reward.aimError = 0.02f;
		st.reward.smooth = 0.01f;
		st.reward.range = 0.02f;
		st.reward.hit = 1.0f;
		// A swing that hits nothing is not charged: it already costs the weapon's charge, and a fighter
		// that fears missing holds back at the edge of its reach. Clicking again before the weapon has
		// recovered is what is charged, hit or miss.
		st.reward.spam = SPAM;
		st.promote.minSteps = 40_000;
		st.promote.maxSpamRate = 0.20f;
		st.promote.minFullCharge = 0.75f;
		st.promote.minHitsPerMinute = 15f;
		st.reward.visible = 0.02f;
		st.lostResetTicks = 160;
		st.reward.hold = 0.02f;
		st.reward.search = 0.03f;
		st.reward.blindPitch = 0.03f;
		return st;
	}

	private static StageSpec sprintHitWTap() {
		StageSpec st = new StageSpec();
		st.name = "Sprint-hit + W-tap";
		st.goal = "hit while sprinting, release W for a moment after each hit, and hold A or D to go round the opponent while the combo lands";
		st.headMask = FIGHT;
		st.rolloutSteps = FIGHT_ROLLOUT;
		st.entropyScale = FIGHT_ENTROPY;
		st.episodeTicks = 600;
		st.startPitchRangeDeg = 15f;
		st.bot.movement = BotProfile.FIGHT;
		st.bot.minDistance = 5f;
		st.bot.maxDistance = 9f;
		st.bot.maxBearingDeg = 90f;
		st.bot.attacks = true;
		st.bot.reactionTicks = 9;
		st.bot.aimNoiseDeg = 7f;
		st.bot.turnRateDeg = 22f;
		// In a good third of the fights it does not hold its range but keeps pushing in, like a player
		// who wants to be in your face. Backing off does not shake it; only a sprint hit makes room.
		st.bot.rushChance = 0.35f;
		// A combo has to work on an opponent as a real one takes a hit: knocked back a tick or two late
		// (its own game hears of the hit after the server has dealt it, and until then it is still in
		// reach and still coming), and in every other fight not thrown the full distance. Up to 0.11.2
		// this bot flew off at once and all the way, every time; the fighter chained 7.8 hits on it and
		// 1.3 to 4 on people.
		st.hitLagTicks = 2;
		st.knockbackMin = 0.6f;
		// And now also seen late, and late by one connection's worth for all of it: see
		// StageSpec.pathLagTicks. The ordinary connections only: the combo is still being learned, and
		// the opponent whose knockback comes a third of a second late is the next stage's.
		st.pathLagTicks = PATH_LAG;
		farStarts(st, FAR_FIGHT_SHARE);
		st.lessonChangedIn = 30;
		// Hits now dwarf everything else in the reward, so the aim keeps the same pay it had in the
		// stage before; without it the camera slowly stops caring where it points.
		st.reward.aimProgress = 0.005f;
		st.reward.onTarget = 0.04f;
		st.reward.hold = 0.02f;
		st.reward.aimError = 0.03f;
		st.reward.smooth = 0.01f;
		st.reward.range = 0.03f;
		st.reward.rangeMin = 2.4f;
		st.reward.rangeMax = 3.0f;
		st.reward.rangeFalloff = 1.5f;
		st.reward.hit = 1.0f;
		st.reward.sprintHit = 0.5f;
		st.reward.combo = 0.2f;
		st.reward.spam = SPAM;
		// A hit made slowly, with no sprint behind it, keeps only a fifth of its worth: it is the weak
		// hit a combo is never made of.
		st.reward.sweep = 0.8f;
		// Being comboed is no reason to stop swinging: a hit back ends the opponent's combo, whatever
		// kind of hit it is.
		st.reward.comboBreak = 0.6f;
		// And it is to try: every hit it takes is to be answered with a swing, landed or not. Waiting
		// for a sure hit while being comboed is waiting for the next hit.
		st.reward.answer = 0.15f;
		// And when the opponent is too far to swing at, as it is right after its hit has knocked the
		// fighter back, the way out is to let the knockback carry it clear and come back with a hit of
		// its own. From the second hit in a row, fighting range stops being paid and being just out of
		// reach pays a little instead; most of what backing out is worth is held back until it has come
		// back and landed that hit.
		st.reward.comboEscape = 0.8f;
		st.reward.comboSpace = 0.015f;
		// It wins most exchanges against the scripted bot, so it is seldom the one being comboed and
		// gets little practice at the two things above. Half of the fights are therefore short drills
		// against a copy of itself that starts on top of it, some hits into a combo already.
		st.drillShare = 0.5f;
		// Not from the start of the stage, though: first it has to be landing combos of its own, here,
		// against the scripted bot. Only then is its copy a fighter worth getting away from.
		st.drillFromSprintHits = 0.5f;
		st.drillFromCombo = 2f;
		// Being hit costs about what a full sprint hit in a combo earns (1.0 up to 0.12.1, when a hit
		// given was worth more than twice a hit taken and trading hits paid). Not more than that: a
		// fighter that loses more by being hit than it can win by hitting learns to stay away.
		st.reward.hitTaken = 2.0f;
		// The second hit in a row and every one after it costs half as much again.
		st.reward.comboedHit = 1.0f;
		// And staying away costs too: after five seconds with no hit either way, every tick does.
		st.reward.stall = 0.04f;
		st.reward.stallAfterTicks = 100;
		st.reward.tap = 0.08f;
		st.reward.reengage = 0.03f;
		// With the opponent already in its face no step forward seems needed, but without W and sprint
		// the hit has no knockback. Having the sprint in place as the sword comes back is paid for.
		st.reward.primed = 0.04f;
		st.promote.minSteps = 60_000;
		st.promote.minSprintHits = 0.60f;
		st.promote.minCloseSprint = 0.50f;
		st.promote.minCombo = 2.5f;
		st.promote.minTapRate = 0.50f;
		st.promote.minHitRatio = 1.5f;
		// Strafing belongs to the combo: A or D held, going round the opponent while the hits land (see
		// MixIdea). It is handed out and paid for here, where the combo is learned, and asked for to pass,
		// though not for ever: a fighter that still does not circle after a long time moves on.
		st.ideas = true;
		st.reward.mixStrafe = 0.15f;
		st.promote.minMixStrafe = 0.5f;
		st.promote.styleOptionalAfterSteps = 150_000;
		st.reward.visible = 0.02f;
		st.lostResetTicks = 200;
		st.reward.search = 0.03f;
		st.reward.blindPitch = 0.03f;
		return st;
	}

	private static StageSpec comboEscape() {
		// The combo fight of the stage before, seen from the other end: most of it is spent inside the
		// opponent's combo, and what is taught is the way out of one.
		StageSpec st = sprintHitWTap();
		st.name = "Combo escape";
		st.goal = "get out of the opponent's combo: from its second hit in a row hold S until clear of its reach, then come back with a charged hit";
		// It got here by landing sprint-hit combos, so its copy lands them too: the drills run from the start.
		st.drillFromSprintHits = 0f;
		st.drillFromCombo = 0f;
		// What it has to get out of is the scripted opponent's combo, below. The drills against its own
		// copy stay, fewer of them: a copy can be traded hits with, which is the other way out and not
		// the one being taught. Each still starts with the copy's combo already going.
		st.drillShare = 0.3f;
		st.drillMinHits = 2;
		// The lesson itself: in the ticks after a hit of the combo, S. A hit's worth of it stays
		// below what the hit cost.
		st.reward.comboBack = 0.07f;
		// And getting all the way out, then back in with a hit, is worth more than hitting back from inside.
		st.reward.comboEscape = 1.0f;
		// The mark it is held to: out by the opponent's second hit. A third costs once more, on top.
		st.reward.comboThird = 1.5f;
		// The scripted opponent stops being easy, and it combos: it reacts sooner, aims better, turns
		// faster, sprints into its hits, and follows a hit up on rhythm with no reaction time at all.
		// And that follow-up reaches a little farther than a sword does, the way the hits of a player
		// with a combo going land on someone who cannot reach back. Without that the fighter simply
		// traded hit for hit with it and never once backed out (0.7.0: S held in 2% of the ticks that
		// decide it, over 120,000 steps). Now walking back into it after a hit is the next hit taken
		// for nothing; backing away until its follow-up has run out is what beats it.
		st.bot.reactionTicks = 6;
		st.bot.aimNoiseDeg = 5f;
		st.bot.turnRateDeg = 30f;
		st.bot.sprint = true;
		st.bot.followUpTicks = TakenStreak.COMBO_TICKS;
		// Up to 0.9.1 the follow-up reached 0.4 blocks farther than a sword and was only ever thrown when
		// it would land. So backing out got the fighter nothing: the bot never missed, and on the way
		// back in it was hit first, every time. Now the follow-up has a sword's reach and comes on
		// rhythm: at a fighter that has backed out it is thrown anyway and misses, and that is the
		// opening the fighter comes back in through, with its own sword charged and the bot's not.
		st.bot.followUpReach = 0f;
		st.bot.followUpWhiff = 1.5f;
		// Hit, it sometimes backs off instead of walking straight back in, as a person does.
		st.bot.dodgeChance = 0.3f;
		// And it begins some of its attacks by jumping in for a critical hit, as people do: the fighter
		// meets that here, a stage before it learns to do it itself, and being hit by one costs more.
		st.bot.critChance = 0.3f;
		st.reward.critTaken = 0.3f;
		// Where the mod can, the fighters see each other a few ticks late, as on a server, and the
		// opponent is knocked back by a hit a little late, as a player with a connection of its own is.
		st.lagTicks = 3;
		st.seenLagTicks = 4;
		st.lagStutter = 0.18f;
		st.slowKnockbackMaxTicks = 12;
		st.hitLagTicks = 2;
		st.knockbackMin = 0.6f;
		st.pathLagTicks = PATH_LAG;
		st.slowShare = SLOW_SHARE;
		// One swing in three of its own comes a tick to three late: a person's rhythm is not a
		// machine's, and those are the ticks in which swinging back at an opponent that is comboing the
		// fighter lands. See BotProfile.swingSlip. Taking that swing is not left to the fighter: where
		// the game has the opponent under its crosshair, the rule makes it (AnswerRule).
		// (For a few runs of 0.14.0 an answer was paid twice as well and from 3.6 blocks, landed or
		// not, and a miss in the air cost nothing within that distance. Against a copy of itself, which
		// keeps its distance, it then threw its sword away in the air after every hit it took: thirty
		// swings a minute there, nineteen in twenty at nothing, and half the hits it had been landing.)
		st.bot.swingSlip = SWING_SLIP;
		// It can knock back now. From here on a hit that neither knocks back nor is critical earns
		// nothing: not for itself and not for the combo.
		st.reward.plainHit = 0f;
		// In 0.8.0 the pay for S was there and S was still not pressed: after six stages of holding W it
		// was a key the fighter all but never tried, so it never found out what the pay was for. So for
		// the first part of the stage the policy is pushed to hold S in the ticks right after a hit of a
		// combo, as hard as it takes for it to do so on most of them. Then the push is let down to
		// nothing, before the stage can be passed. It is a push in those ticks only; see BackNudge.
		// S pays for as long as the opponent is within three blocks; farther off than that it costs.
		st.reward.comboBackFar = 0.07f;
		st.backNudgeRate = 0.6f;
		st.backNudgeHoldSteps = 30_000;
		st.backNudgeSteps = 60_000;
		st.lessonChangedIn = 28;
		// The strafe plan (out to a side and back, or round the opponent) is handed out and paid for from
		// here on, as a habit: it no longer decides whether the stage is passed, and it is left alone
		// while the fighter is getting out of a combo.
		st.ideas = true;
		st.reward.mixStrafe = 0.10f;
		st.reward.mixTiming = 0f;
		// Between swings the crosshair is free: the strafe carries it off the opponent and back, and
		// only once the sword is nearly ready does being on the opponent count again.
		st.reward.aimFromCharge = COMBO_AIM_FROM;
		st.reward.calm = 0.03f;
		st.promote = new Promotion();
		st.promote.minSteps = 70_000;
		// Passed when the habit is there (S held in half of the ticks that decide it) and it works (after
		// most hits of a combo, no further hit follows).
		st.promote.minBackRate = 0.50f;
		st.promote.minOutRate = 0.55f;
		// A habit, not a gate for ever: if it has not formed well after the least the stage takes, the
		// fighter moves on with it still being paid for.
		st.promote.styleOptionalAfterSteps = 160_000;
		// Its own hits still have to be sprint hits. How many it lands for every one it takes is not
		// asked here: this opponent is not a fair one, and getting away from it is the point.
		st.promote.minSprintHits = 0.40f;
		return st;
	}

	private static StageSpec crits() {
		// The same fight with the jump key, and one thing to learn with it: the critical hit, and when
		// there is one to be had.
		StageSpec st = comboEscape();
		st.name = "Crits";
		st.goal = "land critical hits, and know which hit is the one to make: knock back the opponent that can be knocked back, jump and crit the one that stays in its face, "
				+ "and answer a hit with a critical hit made from its knockback (a punish crit)";
		st.headMask = ActionSpace.ALL_HEADS;
		// An opponent that leaves room to try something new: a little slower than the last one, it does
		// not follow its hits up but backs off after them, and now and then it jumps in for a critical
		// hit of its own.
		st.bot.reactionTicks = 7;
		st.bot.aimNoiseDeg = 5.5f;
		st.bot.turnRateDeg = 28f;
		st.bot.followUpTicks = 0;
		st.bot.followUpReach = 0f;
		st.bot.wtap = true;
		// (One swing in four up to 0.9.0. A person who crits does it far more than that, and this is the
		// first opponent that does it at all.)
		st.bot.critChance = 0.4f;
		// A critical hit taken costs as much as a punish crit earns (the hit, the critical hit and the
		// punish crit: 4.5), so that being hit and hitting back comes to nothing, as it does in health.
		// (0.3 up to the last day of 0.14.0. With the punish crit a rule, and so nearly a sure thing,
		// each such exchange was worth 2.2, and it learned to go and stand in the reach of the opponent
		// in the corner: 42 hits a minute taken from that one became 70 within the hour.)
		st.reward.critTaken = 2.5f;
		// After most of its critical hits it stays on top of the fighter instead of backing off. Up to
		// 0.12.3 it backed off after every hit, the critical ones too, so by the time the fighter was
		// coming down from the knockback there was nobody in reach: a punish crit could not be made
		// against it at all, however well the fighter timed one.
		st.bot.critPress = 0.6f;
		// Getting out of combos is kept up here, and at what the stage before paid for it. (Up to the
		// first runs of 0.14.0: fewer drills that began up to four hits in, S paid 0.03 a tick and not
		// pushed for. Put on the screen against a copy of itself that had a combo going, the fighter
		// held S on none of the ticks that decide it and was out by the second hit one time in three.
		// What the stage before had taught was gone: of this stage's opponents one does not combo and
		// one cannot be backed away from, so nothing here had kept it up.)
		// The drills begin one or two hits into the copy's combo: whether a third follows is then the
		// fighter's doing, and that is what is counted (Tally.outByTwoRate).
		st.drillShare = 0.35f;
		st.drillMinHits = 1;
		st.drillHits = 2;
		// S in a combo is no longer paid from here on (0.07 a tick up to the first evening against
		// people). Backing off is what a player who follows with a running jump wants of it: that is
		// the opponent that beat it, and what it does about a combo now is jump with the hit and swing
		// (JumpReset). A third hit still costs, and so does every hit of a combo.
		st.reward.comboBack = 0f;
		st.reward.comboEscape = 1.0f;
		st.reward.comboThird = 1.5f;
		// A critical hit is worth as much again as a sprint hit and more. Through armour it does nearly
		// twice the damage, and it has to be worth giving up the knockback for: a sprint hit sends the
		// opponent away, a critical hit leaves it standing there to hit back.
		st.reward.crit = 2.5f;
		// Made on the way down from the opponent's own hit, without a jump, it is worth more still: it
		// needs no jump, cannot be seen coming, and turns the opponent's hit into an opening. (0.5 up to 0.12.3.)
		st.reward.punishCrit = 1.0f;
		// Nothing is paid for taking the two kinds of hit in turn (RewardWeights.critFollow). The first
		// 0.14.0 run was: knock the opponent away, jump after it, crit, was worth 7.5 a pair. It learned
		// to, slowly, and in the fights it took two hits for every two it landed where it had been
		// landing three for one. Which hit is the right one depends on the opponent, below.
		st.reward.critFollow = 0f;
		st.reward.sprintFollow = 0f;
		// Four fights in ten are against an opponent that cannot be knocked away; see StageSpec.traderShare.
		// Those are the fights a critical hit is for: it cannot keep that opponent off, so what decides
		// them is what each hit does. What it found there is the punish crit, hit for hit: knocked up by
		// the opponent's swing, it keeps its own for the way down.
		st.traderShare = 0.06f;
		// And against one that keeps running out of the fight and one that goes round the fighter
		// making critical hits; see StageSpec.runnerShare. Those are the fights a jump is for.
		st.runnerShare = 0.1f;
		st.circlerShare = 0.1f;
		// One that stands in a corner and makes critical hits from there, one that takes next to no
		// knockback and makes critical hits from on top of the fighter, and one that does something
		// odd all fight long; see StageSpec.cornerShare. What is left, not quite a fifth, are against
		// the opponent a sprint hit knocks away, which still swings at a fighter that jumps into its reach.
		st.cornerShare = 0.05f;
		st.noKnockShare = 0.05f;
		st.oddShare = 0.05f;
		// And the two it lost to on the first evening against people; see StageSpec.jumperShare.
		st.jumperShare = 0.11f;
		st.punisherShare = 0.1f;
		st.backerShare = 0.05f;
		// And the one that plays on its rhythm; see StageSpec.timerShare.
		st.timerShare = 0.12f;
		// And the one that comes in for a critical hit and backs straight out with S; see StageSpec.spacerShare.
		st.spacerShare = 0.08f;
		// And the one that swings on rhythm at the edge of its reach, which spacing beats; see StageSpec.whifferShare.
		st.whifferShare = 0.05f;
		// And copies of the players it has fought; see StageSpec.peopleShare.
		st.peopleShare = 0.04f;
		// Half the fights against the one that goes round it, and against the one that takes no
		// knockback, begin with it beside the fighter or behind it.
		st.besideShare = 0.5f;
		// And every other scripted opponent is one drawn afresh; see StageSpec.randomShare.
		st.randomShare = 0.5f;
		// A jump at an opponent that a run is not catching is free to try, and a critical hit that
		// comes of it earns as much again as half a critical hit; see RewardWeights.chaseCrit.
		st.reward.chaseCrit = 1.5f;
		// What a critical hit is made from, paid for tick by tick as the sword comes back: in the air and
		// coming down, with the sprint off. Twice what having the sprint in place pays.
		st.reward.critReady = 0.08f;
		// A jump is judged by what comes of it. One that ends without a critical hit costs about what a
		// swing made too early costs; one that ends in a critical hit has earned seven times that. Up to
		// 0.12.3 it was the other way round: the jump itself was paid, anywhere within four and a half
		// blocks, and pushed for after every swing, whether or not a hit could follow. A fighter taught
		// that way jumps after every swing and swings at the air, which is what it then did.
		st.reward.critJump = 0f;
		// (0.5 in 0.13.0, a seventh of what a critical hit earns. At that price a jump was worth trying
		// wherever one time in eight came off, and it went on jumping at an opponent four blocks away.)
		// Now a jump has to come off better than one time in three to be worth making. Unless it is made
		// with the opponent in its face: that one is never charged (see RewardWeights.wastedJump). In
		// 0.13.2 it was, whenever the opponent had stepped out of reach by the time it came down, which
		// a moving opponent does most of the time; fifteen updates into the stage's own fights it had
		// stopped jumping altogether.
		// (0.13.1 to 0.13.3: 2.0 for every jump made from farther off than right in the opponent's face
		// that did not end in a critical hit. A jump was then only worth making from where a fight is
		// hardly ever fought: its opponents stand at the edge of its reach or are flying off from its
		// last hit. Within fifteen updates of leaving the drills it had stopped jumping, and for the
		// hundred and thirty updates after that not one of its hits was critical.)
		// No line is drawn any more. A jump that comes to nothing costs a little, wherever it was made,
		// so that hopping about is not free; what tells it where a jump is worth making is what the
		// critical hit at the end of it pays, and what being hit in the air costs.
		st.reward.wastedJump = 0.4f;
		st.reward.jumpFreeWithin = 0f;
		// Held for longer than a jump can make up for, the key still costs: from across the arena.
		st.reward.jumpWithin = 7f;
		// And the swing that goes with such a jump, made in the air at nothing, costs as well. Not on the
		// ground: there a miss still costs only the sword's charge.
		st.reward.airWhiff = 1.0f;
		// (0.13.0 to 0.13.3 paid the sprint hits of a combo what a critical hit is paid, each time they
		// added up to a critical hit's worth of damage, so that leaving the jump alone was worth
		// something. It was worth so much that jumping was never the better call.)
		st.reward.comboAsCrit = 0f;
		// A jump at random makes a fighter worse, and in 0.7.0 that is all it learned of jumping: within
		// 20,000 steps it had stopped altogether. So the jump key starts over here, on the drills below,
		// where a jump at the right moment is found within the first fights.
		st.reopenHeads = ActionSpace.mask(ActionSpace.JUMP);
		// Nothing pushes for the jump any more, anywhere. (0.8.0 to 0.12.3: for the first 40,000 to
		// 80,000 steps of the stage's own fights, three to five ticks after every swing, as hard as it
		// took. 0.13.0: in the first drill. Either way it made jumping a habit before the fighter had
		// seen a single opponent it should not jump at, and that habit did not come out again.)
		st.jumpNudgeRate = 0f;
		st.jumpNudgeHoldSteps = 0;
		st.jumpNudgeSteps = 0;
		// The push for S, as in the stage before and for the same reason: a key it has stopped pressing
		// is not found to help, however well it is paid. Over before the stage can be passed.
		st.backNudgeRate = 0.6f;
		st.backNudgeHoldSteps = 25_000;
		st.backNudgeSteps = 50_000;
		// And from then on it is kept, at a lower mark: let go of altogether, the move was gone again
		// within a few thousand steps. See StageSpec.backNudgeKeep.
		st.backNudgeKeep = BACK_KEEP;
		// Three fights in ten are drills: see the lessons in Curriculum.
		st.critDrillShare = 0.3f;
		// And they come first and alone, one lesson after the other; nothing else is fought until the
		// last is passed. In them its feet and its camera are held for it (see Trainer): all it has is
		// the jump and the swing, and all there is to find is when. (In 0.12.0 a run went through this
		// whole stage without one critical hit and left it on its time limit.)
		// 1. The crit in its face, with all its keys, on an opponent that never swings, keeps pushing
		// into it and is hardly moved by a hit: a sprint hit gets nothing there that a critical hit does
		// not, so jump as the sword recharges and swing on the way down. Half of its hits there critical.
		// (0.13.1 to 0.13.3: held in place, jumping at an opponent put right in its face and not at one
		// farther off. The first 0.14.0 run: going after an opponent it had knocked away, which took a
		// million steps to learn a third of and was the worse way to fight that opponent.)
		st.critDrillPass = 0.5f;
		// Afterwards it goes on among the fights, and is not asked to stay passed. The second 0.14.0 run:
		// passed in seventy seconds (57% of its hits critical), and fifty updates into the fights it had
		// stopped jumping, in this drill as well. Not for want of a reward: jumping, it landed 45 hits a
		// minute on the opponent a sprint hit knocks away and took 43; not jumping, 70 and 24. Against
		// that opponent the jump is the wrong call, and one network makes the call for all of them.
		// What it kept, and made more of the more the opponent stayed in its face, is the punish crit.
		st.critDrillKeep = 0.33f;
		st.critDrillFirstKept = false;
		st.critDrillFarJumpMax = 0f;
		// 2. The punish crit, on one that jumps in for critical hits and stays close: six in ten of the
		// chances it leaves taken, over a hundred and fifty of them (see Curriculum.PUNISH_LESSON_CHANCES).
		// After the lessons both drills go on among the fights, and have to stay passed.
		st.critDrillPunishPass = 0.6f;
		// (39: the drill and the opponents. 41: every other fight against an opponent on a slow connection.
		// 43: getting out of a combo by its second hit is taught here again.)
		st.lessonChangedIn = 43;
		// A critical hit that comes a tick too late is a slow hit on the ground. That must not cost
		// what it does in a stage where such a hit is only ever a mistake, or trying is not worth it.
		// (0.2 up to the first runs of 0.14.0, when it was jumping. It hardly jumps in a fight now, so
		// a sweep is no longer mostly a critical hit that came late: it is a hit made without the sprint
		// behind it, and those are to be few.)
		st.reward.sweep = 0.4f;
		// From across the arena a jump costs while it is held as well, as before.
		st.reward.farJump = 0.03f;
		st.promote = new Promotion();
		// (60,000 up to the first runs of 0.14.0. With the runner and the circler among its opponents
		// there is more to find here than four minutes hold: when a jump is the way in, and when it is
		// the way to get hit.)
		st.promote.minSteps = 400_000;
		// What it is passed on, after the lessons: it still lands critical hits in a real fight, and it
		// still takes the punish crits it is left in the drill, which goes on (see
		// Curriculum.critDrillsKept). Not a large share of critical hits: against an opponent that can be comboed, a
		// combo is often the better way to the same damage, and it is paid as such.
		// (One in twenty up to 0.13.3, when a combo was paid as well as a critical hit.) Over all its
		// fights: against the opponent that can be knocked away, one hit in fifteen is and none need
		// be; against the one that cannot, one in five to one in three, nearly all of them punish crits.
		st.promote.minCritShare = 0.08f;
		// (0.4 in 0.13.2: four jumps in ten had to end in a critical hit.)
		st.promote.minJumpCritRate = 0f;
		// No way out by waiting: this stage is for critical hits, and it is not left without them.
		st.promote.styleOptionalAfterSteps = 0;
		// It only has to hold its own while it changes how it hits.
		st.promote.minHitRatio = 0.6f;
		return st;
	}

	private static StageSpec duel() {
		StageSpec st = new StageSpec();
		st.name = "Duel";
		st.goal = "beat the fighter it was when the duel began, a saved copy of itself, four times out of five";
		st.headMask = ActionSpace.ALL_HEADS;
		st.rolloutSteps = FIGHT_ROLLOUT;
		st.entropyScale = FIGHT_ENTROPY;
		st.episodeTicks = 1200;
		st.startPitchRangeDeg = 10f;
		st.koEndsEpisode = true;
		// No scripted bot is a match for it any more. The opponent is the network itself as it was when
		// the duel began: everything it knows, played back at it, and it has to find what beats that.
		st.bot.movement = BotProfile.SELF;
		st.bot.fixedCopy = true;
		st.lagTicks = 3;
		st.seenLagTicks = 4;
		st.lagStutter = 0.18f;
		st.slowKnockbackMaxTicks = 12;
		st.hitLagTicks = 2;
		st.knockbackMin = 0.6f;
		st.pathLagTicks = PATH_LAG;
		st.slowShare = SLOW_SHARE;
		st.fairShare = FAIR_SHARE;
		st.bot.minDistance = 6f;
		st.bot.maxDistance = 10f;
		st.bot.maxBearingDeg = 60f;
		farStarts(st, FAR_FIGHT_SHARE);
		st.bot.attacks = true;
		st.bot.botImmortal = false;
		st.bot.agentImmortal = false;
		st.reward.onTarget = 0.02f;
		st.reward.aimError = 0.02f;
		st.reward.smooth = 0.005f;
		st.reward.hit = 0.3f;
		// Its damage is still paid, like any damage; the hit itself is not.
		st.reward.plainHit = 0f;
		st.reward.sprintHit = 0.2f;
		st.reward.combo = 0.1f;
		st.reward.spam = 0.4f;
		// Less than before the crit stage, since a critical hit that comes a tick late is one of these.
		// (0.2 up to the first runs of 0.14.0. It leaves the jump alone now, and what is left of these
		// are hits made without the sprint behind them, which is a W-tap left out.)
		st.reward.sweep = 0.3f;
		st.reward.comboBreak = 0.3f;
		st.reward.answer = 0.05f;
		st.reward.comboEscape = 0.4f;
		st.reward.comboSpace = 0.005f;
		// Not paid from the crit stage on; see there.
		st.reward.comboBack = 0f;
		// The push for S is kept on from the crit stage; see StageSpec.backNudgeKeep.
		st.backNudgeKeep = BACK_KEEP;
		// Out by the second hit, as in the stages before; see RewardWeights.comboThird.
		st.reward.comboThird = 0.6f;
		st.reward.damage = 0.15f;
		st.reward.ko = 5f;
		// Two copies of one fighter can agree not to fight. Five seconds without a hit from either
		// side and standing off starts to cost more than aiming pays; a fight that runs out of time
		// costs both of them more than half of what a knockout is worth.
		st.reward.stall = 0.04f;
		st.reward.stallAfterTicks = 100;
		// Up to 0.12.1 a hit taken cost nothing here but the damage it did, about a third of what a hit
		// given earned.
		st.reward.hitTaken = 0.8f;
		// And a critical hit taken a little more, for the same reason as in the crit stage: with the
		// hit, a punish crit earns 1.1 here.
		st.reward.critTaken = 0.3f;
		st.reward.comboedHit = 0.4f;
		st.reward.draw = 3f;
		// Against itself it is comboed about as often as it combos, so fewer drills are needed; what
		// they still add is the start from several hits behind.
		st.drillShare = 0.25f;
		st.reward.tap = 0.02f;
		st.reward.primed = 0.015f;
		st.ideas = true;
		st.reward.mixStrafe = 0.05f;
		st.reward.aimFromCharge = COMBO_AIM_FROM;
		st.reward.calm = 0.01f;
		// What a critical hit is really worth is in the damage, which counts here. These keep the habit.
		st.reward.crit = 0.6f;
		st.reward.punishCrit = 0.2f;
		st.reward.critReady = 0.02f;
		st.reward.farJump = 0.02f;
		st.reward.jumpWithin = 7f;
		// As in the stage before, scaled like everything else here: a jump that comes to nothing costs a
		// little wherever it was made.
		st.reward.wastedJump = 0.1f;
		st.reward.jumpFreeWithin = 0f;
		st.reward.airWhiff = 0.3f;
		st.reward.chaseCrit = 0.4f;
		st.reward.comboAsCrit = 0f;
		st.reward.uppercut = 0.3f;
		// The duel is over once it beats that copy four times out of five: it is then clearly better
		// than the fighter that began it.
		st.promote.minSteps = 60_000;
		// One fight in five is on equal terms and counted (see StageSpec.fairShare), and a fight is a
		// few hundred steps: over 30,000 steps, as up to the first runs of 0.14.0, there were often
		// fewer than the twenty it takes to say anything, and the stage could not be passed however
		// the fights went.
		st.promote.windowSteps = 60_000;
		st.promote.minFights = 20;
		st.promote.minWinRate = 0.80f;
		st.reward.search = 0.02f;
		st.reward.blindPitch = 0.02f;
		return st;
	}

	private static StageSpec selfPlay() {
		// The same fight for the same rewards. The copy no longer stays as it was: the agent takes its
		// place whenever it clearly beats it, so the opponent keeps getting better with it.
		StageSpec st = duel();
		st.name = "Self-play";
		st.goal = "fight a copy of itself; whenever it clearly beats the copy, it becomes the copy the next one has to beat";
		st.bot.fixedCopy = false;
		st.promote = new Promotion();
		st.promote.endless = true;
		return st;
	}
}
