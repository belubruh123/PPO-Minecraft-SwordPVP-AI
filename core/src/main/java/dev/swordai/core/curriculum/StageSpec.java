package dev.swordai.core.curriculum;

import dev.swordai.core.rl.ActionSpace;

/** One step of the cumulative curriculum. */
public final class StageSpec {
	public String name = "";
	/** One line shown in status and reports. */
	public String goal = "";
	/** Bit set of the action heads the agent controls; the rest stay at their defaults. */
	public int headMask = ActionSpace.ALL_HEADS;
	public int episodeTicks = 600;
	/** The agent may start an episode standing on a pillar up to this tall (to practise looking down). */
	public int agentMaxPillar = 0;
	/** Random starting pitch, degrees either side of level. Only meaningful once pitch is unlocked. */
	public float startPitchRangeDeg = 0f;
	/**
	 * Above zero: an episode is cut short and restarted once the opponent has been off screen this
	 * many ticks in a row, so game time is not wasted staring at nothing.
	 */
	public int lostResetTicks = 0;
	/** A knockout ends the episode; otherwise it always runs to the time limit. */
	public boolean koEndsEpisode = false;
	/**
	 * Steps collected before each learning update while this stage is the current one; zero uses the
	 * configured default. Fights are judged by a few hits a second, so one fight says little: the
	 * fighting stages learn from several at a time.
	 */
	public int rolloutSteps = 0;
	/**
	 * Scales the bonus for keeping choices undecided while this stage is the current one. Where the
	 * reward comes in rare, large events, the full bonus slowly wins and the play turns random.
	 */
	public float entropyScale = 1f;
	/** The agent is handed a random plan for its strafing and hit timing; see {@code MixIdea}. */
	public boolean ideas = false;
	/**
	 * Share of this stage's fights that are combo drills. A fighter that wins most of its exchanges is
	 * rarely on the wrong end of a combo and so never learns to get out of one. In a drill the opponent
	 * is a saved copy of the agent itself, which combos as well as the agent does; it starts right in
	 * front of the agent and is given its first hits before the agent gets its keys back. The agent
	 * then starts where it otherwise seldom is: some hits into the opponent's combo.
	 */
	public float drillShare = 0f;
	/**
	 * Share of this stage's fights that are crit drills instead: twenty seconds with an opponent that
	 * stands still close by and does not hit back. Jumping, coming down with the sprint off and
	 * swinging on the way down is hard enough to find without an opponent that moves and hits; here
	 * there is nothing else to do. Nothing from these counts towards passing the stage.
	 */
	public float critDrillShare = 0f;
	/**
	 * Above zero: the crit drills come first and alone, one lesson after another (see
	 * {@code Curriculum.DRILL_RANGE} and the one after it). Every fight of the stage is a drill of
	 * the lesson it is at until that lesson is passed; only after the last do the stage's own fights
	 * begin (and the drills drop to {@link #critDrillShare}). Until then the stage's own count of
	 * steps does not move, and the stage cannot be passed.
	 *
	 * <p>This is what the first lesson asks: the share of the hits it lands on the standing opponent
	 * that have to be critical.
	 */
	public float critDrillPass = 0f;
	/**
	 * Once the lessons are passed and the first drill only goes on among the fights: the share of its
	 * hits there that still have to be critical for it to count as kept. Lower than what the lesson
	 * asks: in the fights it learns when a jump is not worth making, and brings some of that back to
	 * the drill. Zero: the same as the lesson.
	 */
	public float critDrillKeep = 0f;
	/**
	 * Whether the first drill has to stay passed for the stage to be passed. False: it is a lesson,
	 * passed once, and goes on among the fights so that the jump stays something it can do; whether
	 * it then jumps is left to what its fights pay. (0.14.0: against an opponent a sprint hit knocks
	 * away a jump loses more hits than its critical hit is worth, and it stops jumping there within
	 * fifty updates, in the drill as well. The stage could then never be passed.)
	 */
	public boolean critDrillFirstKept = true;
	/**
	 * Above zero, the first lesson also asks this: of the ticks it stands with the opponent out of
	 * reach, the largest share on which it may still jump. (What the lesson was about in 0.13.1 to
	 * 0.13.3, when a jump was only wanted from right in the opponent's face.)
	 */
	public float critDrillFarJumpMax = 0f;
	/**
	 * The second lesson asks this: of the hits that knock it up and leave the opponent in its reach as
	 * it comes down, the share it answers with a critical hit made from that fall (a punish crit).
	 */
	public float critDrillPunishPass = 0f;
	/**
	 * The drills only run while the agent's own recent fights in this stage show it can combo: at least
	 * this share of its hits are sprint hits and its combos are at least this long on average. Until
	 * then its copy cannot combo either, so there is no combo to get out of, and being hit by an equal
	 * a quarter of the time would only teach a fighter that has never been hit to keep away. Zero: no
	 * such condition (it has already shown it in an earlier stage).
	 */
	public float drillFromSprintHits = 0f;
	public float drillFromCombo = 0f;
	/** A drill is over after this many ticks: long enough to get out of the combo and answer it. */
	public int drillTicks = 200;
	/** The copy is given between {@link #drillMinHits} and this many hits before the agent may move. */
	public int drillHits = 4;
	public int drillMinHits = 1;

	/**
	 * Heads that are put back to how a head starts whenever this stage begins: see
	 * {@code Learner.reopenHead}. For a control this stage teaches the use of, which earlier play may
	 * have taught one thing only: to leave it alone.
	 */
	public int reopenHeads = 0;
	/**
	 * Above zero: while this stage is young the policy is pushed towards jumping right after a swing
	 * (see {@code JumpNudge}), as strongly as it takes for it to press jump on this share of those
	 * ticks. Held there for {@link #jumpNudgeHoldSteps} steps of the stage, then let down to nothing by
	 * {@link #jumpNudgeSteps}; from then on it jumps of its own accord or not at all.
	 */
	public float jumpNudgeRate = 0f;
	public int jumpNudgeHoldSteps = 0;
	public int jumpNudgeSteps = 0;
	/**
	 * The same for S in the ticks right after a hit of a combo it is taking (see {@code BackNudge}):
	 * the share of those ticks on which the stage wants S held while it pushes for it, how long it
	 * holds that, and by when the push is gone.
	 */
	public float backNudgeRate = 0f;
	/**
	 * Above zero: the push towards S in the ticks after a hit of a combo does not end with
	 * {@link #backNudgeSteps} (or, in a stage without such a stretch, is there from its start). It is
	 * kept at whatever it takes for S to be held on this share of those ticks, in training and in
	 * every fight it plays.
	 *
	 * <p>Not a lesson but a habit it is given, like the rule about the sword's charge. Measured
	 * (0.14.0, against a copy of itself that had a combo going on it): with the push held it was out
	 * by the opponent's second hit in two combos of three and the drill paid 0.085 a step; as the push
	 * was let down it was out of one in two and then one in three, the drill paid 0.04, and by the
	 * end S was held on none of those ticks. The move works and the network does not keep it: one
	 * escape model answers every opponent, and against the one that runs straight back in, backing
	 * off gains nothing. Left to itself it drops the move everywhere.
	 */
	public float backNudgeKeep = 0f;
	public int backNudgeHoldSteps = 0;
	public int backNudgeSteps = 0;
	/**
	 * The config version in which what this stage teaches last changed so much that a fighter which
	 * passed it before has not learned it. A run saved under older rules that is in a later scripted
	 * stage goes back to this one.
	 */
	public int lessonChangedIn = 0;
	/**
	 * Largest lag, in ticks, in what the opponent (the scripted bot or a copy) sees of the agent:
	 * each fight draws its own, anywhere from none to this many ticks. A fighter that has only ever
	 * fought with no delay at all is lost on a server.
	 */
	public int lagTicks = 0;
	/**
	 * Largest lag, in ticks, in what the agent sees of its opponent, drawn per fight in the same way.
	 * On a server this is the longer of the two: a hit knocks the opponent up only once the hit has
	 * gone to the server, on to the opponent's game, and its answer has come all the way back.
	 */
	public int seenLagTicks = 0;
	/**
	 * In half of the fights the opponent also reaches the agent's eyes in fits and starts, as over a
	 * connection that stutters: on each tick, with a chance of up to this much (drawn per fight),
	 * nothing arrives for {@link #LAG_STALL_MIN_TICKS} to {@link #LAG_STALL_MAX_TICKS} ticks and then
	 * all of it at once. 0: never.
	 */
	public float lagStutter = 0f;
	/**
	 * Largest lag, in ticks, of the opponent's own connection, drawn per fight: a hit knocks it back
	 * that many ticks late, and until then it goes on as it was, still in reach and able to swing. A
	 * combo learned on an opponent that flies off the moment it is hit does not work on one that does not.
	 */
	public int hitLagTicks = 0;
	/**
	 * Between 0 and 1: in every other fight the opponent is knocked back less far than a hit would
	 * throw it, by a share drawn per fight between this and all of it. A real opponent takes less
	 * than the full knockback much of the time: it is running into the hit, or it jumps as it lands.
	 * A combo that only works on an opponent thrown the full distance every time does not work on
	 * that one. 0: always the full knockback.
	 */
	public float knockbackMin = 0f;
	/**
	 * Above zero: each fight draws one connection for the whole way between the two fighters, and that
	 * is used for all three of {@link #lagTicks}, {@link #seenLagTicks} and {@link #hitLagTicks}, whose
	 * own values are then not used. This is the most the opponent's connection takes one way, in ticks,
	 * in an ordinary fight: none to this many, short ones more often (see {@code Trainer.drawPathLag}).
	 *
	 * <p>On a server the three are one thing. A hit moves the opponent only once it has gone to the
	 * opponent's own game and that game's answer has come back: its connection there and back, so twice
	 * the way. Until then the server has it walking on as it was, in reach and able to swing. And what
	 * either fighter sees of the other is old by the opponent's way plus the fighter's own (none or one
	 * tick, drawn per fight). Drawn separately and small (up to 0.13.3: seen 0 to 4 late, hit 0 to 2
	 * late), the knockback was never more than two ticks late, so the fighter had hardly met an
	 * opponent that stays where it is while the sword recharges. Against one that did, it swung again
	 * at once.
	 */
	public int pathLagTicks = 0;
	/**
	 * The share of this stage's fights that are against an opponent on a slow connection: a hit knocks
	 * it back between {@link #slowKnockbackMinTicks} and {@link #slowKnockbackMaxTicks} ticks late (a
	 * quarter to half a second), and each sees the other half of that late. Needs {@link #pathLagTicks}.
	 *
	 * <p>The rest are the ordinary ones, where the knockback is none to four ticks late. A fighter has to
	 * be able to fight both and cannot be told which it has in front of it: it finds out from its first
	 * hit, by whether the opponent goes when it is hit or a third of a second later. (0.14.0, first
	 * runs: one draw for all fights, none to six ticks, short ones more often; one fight in six had
	 * the knockback five ticks late or more, and none more than six. Against a player whose knockback
	 * took 0.3 to 0.5 seconds it was lost.)
	 */
	public float slowShare = 0f;
	/**
	 * Against a copy of itself: the share of the fights that are set up on equal terms, and they are
	 * the ones that count as won or lost. Nothing is late for either of them, the copy is knocked back
	 * as far as the fighter is, what the fighter sees of it does not stutter, and they start facing
	 * each other. In every other fight of such a stage something is against the fighter and nothing
	 * against the copy: the copy's knockback comes late or carries it less far, which keeps it in
	 * reach; what the fighter sees of it comes in fits and starts; the copy starts with its eyes on
	 * the fighter and the fighter has to find it. Those are what it is to learn to deal with, and
	 * who wins them says what they are worth, not which of the two is the better. (Counted without
	 * this, a fighter put against a copy of itself made that very minute lost 16 of 19.)
	 */
	public float fairShare = 0f;
	public int slowKnockbackMinTicks = 5;
	public int slowKnockbackMaxTicks = 10;
	/**
	 * Share of this stage's own fights that are against a trader: the stage's opponent, but one that
	 * cannot be kept away. It keeps pushing in, it is ready to swing {@link #traderReactionTicks}
	 * ticks after it has the fighter in reach, and a hit throws it back only a part of the way (drawn
	 * per fight between {@link #traderKnockbackMin} and {@link #traderKnockbackMax}), so it is back
	 * before the fighter's sword is.
	 *
	 * <p>This is the opponent a critical hit is for. Against one that a sprint hit sends flying, a
	 * combo of sprint hits is simply the better fight: it is hit and cannot hit back. Measured (0.14.0,
	 * the first fights after the crit drills): jumping for a third of its hits, the fighter landed 49
	 * hits a minute and took 45; with the jump left alone, 69 and 22. It dropped the jump, and it was
	 * right to. But an opponent that takes the hit and stays (one that runs into the hit, jumps as it
	 * lands, or is a quarter of a second away on a server) trades hit for hit whatever is done, and
	 * then the only question is whose hits do more. So: knock back the opponent that can be knocked
	 * back, and crit the one that cannot.
	 */
	public float traderShare = 0f;
	/**
	 * The share of the stage's own fights that are against a runner: the stage's opponent, but one
	 * that keeps running out of the fight ({@code BotProfile.fleeChance}). And the share against a
	 * circler: one that goes round the fighter and makes its hits as jumped critical hits
	 * ({@code BotProfile.orbit}). What they are for: against every opponent up to 0.14.0 a jump was
	 * the wrong call, since each of them either swung at whatever came into its reach or could not be
	 * kept off at all, and so the fighter stopped jumping. A player who runs is caught by a running
	 * jump and not by a run, and the hit to make on one is a critical hit; one who circles and crits
	 * leaves a punish crit to make after every hit, to a fighter that can keep its crosshair on it.
	 */
	public float runnerShare = 0f;
	public float circlerShare = 0f;
	/** How often a runner runs, each time it lands a hit or takes one; see {@code BotProfile.fleeChance}. */
	public float runnerFlee = 0.8f;
	/** The share of the fights against a runner in which it keeps away until it is hit; see {@code BotProfile.fleeUntilHit}. */
	public float runnerKeepsAway = 0.5f;
	/**
	 * Shares of the stage's fights against more opponents of its own kind each:
	 * <ul>
	 * <li>one that stands in a corner and makes critical hits from there ({@code BotProfile.holdGround}),
	 * with the fight begun next to that corner;</li>
	 * <li>one that takes next to no knockback and makes critical hits, one after the other, from on top
	 * of the fighter: what a cheat looks like, and a player who has learned to hit without knocking back;</li>
	 * <li>one that does something odd all fight long ({@code BotProfile.ANTICS}).</li>
	 * </ul>
	 */
	public float cornerShare = 0f;
	public float noKnockShare = 0f;
	public float oddShare = 0f;
	/**
	 * And against the two that beat it on the first evening against people: one that comes after it
	 * with a running jump and a critical hit whenever it backs off ({@code BotProfile.jumpIn}), and
	 * one that takes part of the knockback and answers each of its hits with a critical hit made on
	 * the way down ({@code BotProfile.punish}).
	 */
	public float jumperShare = 0f;
	public float punisherShare = 0f;
	/**
	 * And against one that does what this fighter did until then: hit, it walks backwards for twelve
	 * ticks with its eyes on the fighter and its sword ready. That is the opponent the running jump
	 * and the critical hit on the way down are for ({@code JumpIn.chaseInCombo}): where the fighter
	 * practises on someone else what was done to it.
	 */
	public float backerShare = 0f;
	/**
	 * And against one that plays on its rhythm: it holds a step outside its reach and times its
	 * critical hits to come down just before the fighter's sword is back ({@code BotProfile.timer}).
	 * What the user's friend still beat it with on 18 October.
	 */
	public float timerShare = 0f;
	/**
	 * And against the player at the edge of the reach ({@code BotProfile.spacer}): one that comes in
	 * with a jump for a critical hit and backs out with S straight after, one that stands three blocks
	 * off and hits whatever comes into its reach, perfectly, and one that does both. A third of the
	 * fights each.
	 */
	public float spacerShare = 0f;
	/**
	 * And against the player spacing beats: it walks in and swings the moment its sword is back, on
	 * rhythm, whether or not the fighter is quite in reach. Held off at the edge of the reach it misses,
	 * and its cooldown is the opening. Where the fighter learns to space others, not only to get out
	 * of being spaced. The user, 18 October: "I WANT IT TO LEARN TO ALSO kill some ppl with this method".
	 */
	public float whifferShare = 0f;
	/**
	 * And against scripted copies of real players it has fought in {@code /swordai fight}: their
	 * habits, counted from those fights, played out by the script ({@code PersonStyle}). The players it
	 * lost to, or nearly, come up most. Where there are none yet, these fights are against the stage's
	 * own opponent.
	 */
	public float peopleShare = 0f;
	/** Each scripted opponent of this stage jumps with this share of the hits it takes; see {@code BotProfile.jumpResetChance}. */
	public float opponentJumpReset = 0f;
	/**
	 * The share of the fights against one that goes round the fighter, or takes no knockback, that
	 * begin with it close by and off to a side or behind: out of the picture, and making its first
	 * critical hit from there.
	 */
	public float besideShare = 0f;
	/**
	 * The share of the fights against a scripted opponent in which that opponent is not the stage's
	 * as written but one drawn afresh: how quick it is, how well it aims, how near it likes to be,
	 * how often it jumps, backs off or comes in for a critical hit.
	 */
	public float randomShare = 0f;
	/** The share of a circler's swings that are jumped critical hits. */
	public float circlerCrit = 0.85f;
	public float traderKnockbackMin = 0.2f;
	public float traderKnockbackMax = 0.5f;
	public int traderReactionTicks = 3;
	public static final int LAG_STALL_MIN_TICKS = 2;
	public static final int LAG_STALL_MAX_TICKS = 6;

	public BotProfile bot = new BotProfile();
	public RewardWeights reward = new RewardWeights();
	public Promotion promote = new Promotion();

	public boolean unlocked(int head) {
		return ActionSpace.unlocked(headMask, head);
	}
}
