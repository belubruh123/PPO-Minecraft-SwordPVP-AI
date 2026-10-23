package dev.swordai.core.config;

import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Stages;

import java.util.List;

/**
 * Everything tunable without a rebuild. The mod writes this to {@code swordai/config.json}; when
 * {@link #configVersion} in the file is older than {@link #CURRENT_VERSION} the file is backed up
 * and regenerated, so new defaults shipped in a jar are never shadowed by a stale file.
 */
public final class Config {
	public static final int CURRENT_VERSION = 63;
	/**
	 * From this version on the two settings below have other defaults, for reasons a file written
	 * before it knows nothing of: such a file's values for them are not carried over.
	 */
	public static final int PEOPLE_AND_ARENA_FROM = 38;
	/** The arena's floor has to reach at least this far from its middle for the fights that start far apart to fit. */
	public static final int MIN_ARENA_RADIUS = 20;

	public int configVersion = CURRENT_VERSION;

	// ---- PPO ------------------------------------------------------------------------------------
	// The game only yields 20 decisions a second, so every sample is reused hard: small rollouts,
	// small minibatches, many epochs. Compute is cheap here; game time is not.
	/** Steps collected before an update. The update then waits for the running episode to finish. */
	public int rolloutSteps = 512;
	/** Extra room in the buffer so the longest episode can finish before the update. */
	public int rolloutSlack = 1300;
	public int epochs = 8;
	public int minibatch = 64;
	public float gamma = 0.98f;
	public float lambda = 0.95f;
	public float clip = 0.2f;
	/** Starting actor learning rate; with {@link #adaptiveLr} it then follows the measured policy change. */
	public float actorLr = 3e-4f;
	public float criticLr = 1e-3f;
	public float maxGradNorm = 0.5f;
	/**
	 * Keeps each update inside a trust region: the actor learning rate is lowered when an epoch moves
	 * the policy more than twice {@link #klTarget} and raised when it moves less than half of it.
	 */
	public boolean adaptiveLr = true;
	public float klTarget = 0.015f;
	public float minActorLr = 3e-5f;
	public float maxActorLr = 5e-4f;
	/** Stop a PPO update early once the policy has moved this far from the one that collected the data. */
	public float klStop = 0.04f;
	/** After a stage change the critic gets this many updates to itself before the actor moves again. */
	public int criticWarmupUpdates = 2;
	/** Entropy bonus per head: yaw, pitch, forward, strafe, sprint, jump, attack. */
	public float[] entropyCoef = {0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f};
	public boolean normalizeRewards = true;

	// ---- network --------------------------------------------------------------------------------
	public boolean usePeriphery = true;
	public boolean useFovea = true;
	/** The value network may read ground truth during training. The acting network never does. */
	public boolean privilegedCritic = true;

	// ---- runtime --------------------------------------------------------------------------------
	/** Vertical field of view the game is held at while the agent plays the user's character. */
	public int fov = 90;
	/**
	 * Field of view of the picture the network gets; never wider than {@link #fov}. When it is narrower,
	 * the network is handed the middle of each frame, which is exactly the narrower view. It has to be
	 * the field of view the network was trained with: {@code /swordai fov} sets it, and it is kept when
	 * a new version rewrites this file.
	 */
	public int visionFov = 90;
	/**
	 * How many times normal speed {@code /swordai train} runs the game at (1 = real time). Above 1 the
	 * game window is made small, so that it can draw a fresh frame for every one of the shorter ticks,
	 * and the speed comes down by itself if the computer does not keep up. {@code /swordai speed} sets it.
	 */
	public float trainSpeed = 1f;
	/**
	 * Training faster than real time: the height of the picture the small window draws, in rows. 0 is
	 * the least the AI's picture needs (270 rows); set it higher for a window that is easier to read.
	 */
	public int speedWindowRows = 0;
	/**
	 * {@code /swordai train headless}: the most times normal speed the world may run at. It settles on
	 * what the computer manages, which is usually less.
	 */
	public float headlessSpeed = 20f;
	/**
	 * The name of the fake player the AI fights (and that you fight in {@code /swordai spar}). A fake
	 * player gets the skin of the Minecraft account of that name, if there is one and the game can
	 * look it up. {@code /swordai name} sets it.
	 */
	public String botName = "SwordBot";
	/**
	 * While the AI plays on the screen: show the opponent with its own skin, armour and name tag
	 * instead of the blue and green figure. The AI still gets the blue and green picture; it is drawn
	 * first, in a pass of its own, and then the world is drawn again for the player. That is two
	 * pictures per frame, so about half the frames a second. {@code /swordai skin} sets it.
	 */
	public boolean showOpponentSkin = false;
	/** Training without the screen: the name of the fake player that is the AI's own body. */
	public String agentName = "SwordAgent";
	/** Trainer threads; 0 picks a value from the CPU count. */
	public int trainerThreads = 0;
	/**
	 * How quickly the camera takes up the turning speed the agent asks for: the share of the gap closed
	 * each tick. 1 would be instant (and twitchy); lower is smoother and slower to react.
	 */
	public float aimResponse = 0.5f;
	/**
	 * How {@code /swordai play}, {@code fight} and {@code spar} pick from the network's choices. At 1
	 * they pick exactly as training does, so what plays is what was trained and watched. Below 1 the
	 * network commits to its favourite choice more often, which looks steadier but changes how it
	 * plays: things it does only now and then on purpose, like letting go of W for one tick to get
	 * its sprint hit back, become rare.
	 */
	public float playTemperature = 1f;
	/**
	 * {@code /swordai fight} and {@code /swordai spar} with a policy that has not passed the mix-up
	 * stage yet: when set, its strafe direction and hit timing are varied for it by rule. Off by
	 * default, so that what fights is exactly the network that was trained.
	 */
	public boolean fightMixAssist = false;
	/**
	 * {@code /swordai fight} and {@code /swordai spar} also teach the network: it learns from those
	 * fights against people with the rewards of the stage it is in. They never count towards passing
	 * a stage. Off unless {@code /swordai learn on} says otherwise.
	 *
	 * <p>(On by default from 0.11.0 to 0.13.3. What that did to a trained network was measured in
	 * 0.13.2: nine updates from sparring and from fights on a server, each from a minute of fighting
	 * or less and each moving the network three to seven times as far as a training update does, the
	 * last four on fights the critic could make nothing of. Early swings went from one in twenty to
	 * seven in ten and sprint hits from nine in ten to one in ten.)
	 */
	public boolean learnFromPeople = false;
	/**
	 * With {@link #learnFromPeople} on, how {@code /swordai fight} learns: safely. The proven network
	 * (the trained one) and a copy that learns take turns, a fight each; the copy learns from its own
	 * fights with small, cautious updates, and takes the proven one's place only when it has done
	 * better (damage dealt minus taken a minute) over enough fights of each, or is thrown away when it
	 * does worse; see {@code SafeLearn}. The proven network is never changed otherwise. Since 19 October.
	 * False learns as up to 0.15.1: the network itself, from every fight.
	 */
	public boolean safeLearn = true;
	/**
	 * Self-imitation learning (Oh et al., 2018; {@code SilBuffer}): after each update, the steps that
	 * went far better than the critic expected are learned from again, for as long as they still do.
	 * The weight of that on the actor; 0 is off. Since 20 October: the rare wins over an opponent it
	 * mostly loses to were learned from once and gone.
	 */
	public float silWeight = 0f;
	/** The same on the critic, which is raised towards those returns. */
	public float silValueWeight = 0.01f;
	/** Minibatches of self-imitation after each update. */
	public int silBatches = 8;
	/** Steps kept to be imitated, the most recent. */
	public int silCapacity = 4000;
	/** The most a step's lead over what was expected counts for. */
	public float silClip = 1f;
	/** A step is kept when its advantage stands this many standard deviations above its update's mean. */
	public float silKeepZ = 1.5f;
	/**
	 * Learning from the user's own fights, recorded with {@code /swordai demo} ({@code DemoFile}; the user,
	 * 22 October): after each update, {@link #demoBatches} minibatches of the recorded steps where the
	 * person did better than the critic expects of the network are imitated with this weight, the way
	 * {@link #silWeight} imitates its own best steps. 0 is off. The fights are read when training starts.
	 */
	public float demoWeight = 0f;
	public int demoBatches = 4;
	/** The same on the critic. 0: the critic stays the network's own and is not raised towards a person's returns. */
	public float demoValueWeight = 0f;
	/** At most this many recorded steps are read, the newest fights first; a step is about 5 KB in memory. */
	public int demoMaxSteps = 30000;
	/**
	 * A recorded step is learned from only if the reward of the next this-many ticks, its own included, adds up to more
	 * than nothing: the stretch after it went the person's way. 0: every step (the critic's comparison alone decides).
	 * The user, 23 October: the opponents are hard for them too, "some part are me losing and so it might learn the
	 * part that I lose".
	 */
	public int demoWindowTicks = 0;
	/**
	 * What that stretch's reward has to come to, more than this. 0: ahead at all (an even trade, a hit for a hit, is
	 * not: being hit costs about 4, a hit brings about 2). 3.5: a critical hit (4 to 5) or two clean hits, the user's
	 * crit combos (23 October: "only some part where I manage get into a crit combo").
	 */
	public float demoWindowMin = 0f;
	/**
	 * Kickstarting (Schmitt et al., 2018; Wołczyk et al., 2024, "Fine-tuning reinforcement learning
	 * models is secretly a forgetting mitigation problem"): the weight of KL(anchor || network) on the
	 * actor, on the states of every update, where the anchor is the network in {@code swordai/anchor.bin}
	 * (the one installed) and is never trained. Updates still move the network where the advantage
	 * says, but each step away from what the anchor does costs, so what it was good at is not walked
	 * away from. 0 is off; the drift from the anchor is logged whenever the file is there. Since 20
	 * October: every network trained on from the installed one measured worse than it within hours.
	 */
	public float anchorWeight = 0f;
	/**
	 * The anchor's weight on the steps of the kinds of fight listed in {@code anchorBoostTasks} (their
	 * places in {@code Trainer.episodeTask}), when above 0; the rest keep {@code anchorWeight}. Targeted
	 * distillation (Distral, Teh et al. 2017): on 21 October the installed network beat everything
	 * trained since against the spacer, fight by fight on the same 118 fights, while the newer networks
	 * did a little better against the rest; so where the spacer is fought the network is held to the
	 * installed one firmly, and elsewhere it keeps what it learned.
	 */
	public float anchorBoostWeight = 0f;
	/**
	 * At 0 or above: on a step whose action did better than the critic expected (advantage above 0), the
	 * anchor's weight is at most this. The anchor then holds the network back where it does worse than
	 * expected and not where it finds something better: a floor, not a ceiling. Below 0, off: the anchor
	 * pulls the same on every step. Since 21 October evening (suggested in a review the user passed on).
	 */
	public float anchorGainWeight = -1f;
	public int[] anchorBoostTasks = {12, 13};
	/**
	 * Paired evaluation (common random numbers): not 0, every fight is set up from this number and its
	 * place in the run (which stage, which opponent and how it is made, where both start, and the
	 * arena's and the bot's own random choices), so two networks measured with the same number meet
	 * the same fights in the same order and the difference between them is not the luck of the draw.
	 * For frozen measurements only; 0 (training) draws every fight afresh. Since 20 October: the same
	 * network measured +88 and -9 net against the spacer in two seven-minute runs.
	 */
	public int evalSeed = 0;
	/**
	 * With {@link #evalSeed}: every fight is fought twice in the same run, once by this network (A) and
	 * once by the network saved here (B: a run folder or a checkpoint file), from the same seed, the
	 * order turning from one pair to the next; see {@code PairedEval}. Both then share the run's luck
	 * (its connection, its timing, the machine), which two separate runs did not (20 to 23 October: the
	 * same network measured -15 and +92 on the same fights in two runs). "" for none. Gemini's review,
	 * 23 October (rank 6).
	 */
	public String pairedBase = "";
	/**
	 * The settings B fights with, as "key=value key=value" (plain fields only), A's own put back for its
	 * fights: the installed network is measured without the coach counters, say
	 * "keepWithin=0 jumpAtReadyOnlyFleeing=false". A B saved without a tactics model plays with none.
	 */
	public String pairedBaseSettings = "";
	/**
	 * The size of the networks, grown on 21 October 2026 (the user: "it might be too small"). Up to then
	 * the actor decided everything from one layer of 128 over 64 units of the vector input (6.7k of its
	 * 153k weights; the rest read the two pictures), and the critic was 132-128-128-1 and explained under
	 * half of the returns. Every part added starts out computing nothing, so a network saved before
	 * loads and plays exactly as it did, and grows from there:
	 * <ul>
	 * <li>{@code actorVecWide} more units reading the vector input straight into the trunk;</li>
	 * <li>{@code actorBlocks} residual blocks of {@code actorBlockWidth} between the trunk and the heads,
	 * as SimBa (Lee et al., ICLR 2025) scales RL networks: layer norm, a skip path, the block's output
	 * starting at zero (see {@code ResBlock});</li>
	 * <li>for the critic, which kind of fight it is ({@code criticTask}, never shown to the actor) and
	 * {@code criticBlocks} blocks of its own.</li>
	 * </ul>
	 * A network saved with more parts than these settings make does not load: set them back to what it
	 * was trained with.
	 */
	public int actorVecWide = 64;
	public int actorBlocks = 2;
	public int actorBlockWidth = 256;
	public boolean criticTask = true;
	public int criticBlocks = 1;
	public int criticBlockWidth = 256;
	/**
	 * Normalise the advantages of each kind of fight on their own in an update, instead of all together
	 * (the point of PopArt, Hessel et al. AAAI 2019); see {@code Ppo.setTaskNormalisation}.
	 */
	public boolean taskAdvantageNorm = false;
	/**
	 * In training, the share of the fights against a stage's scripted opponents that are fights it
	 * lost before, set up exactly as they were, until each has been won twice in a row; see
	 * {@code LostCases}. Since 19 October: a fight it still loses is very likely the one a person finds.
	 */
	public float replayLostShare = 0.5f;
	/**
	 * Lost fights are replayed by how much they have to teach (prioritised level replay, the mean |GAE| of
	 * their last fighting) instead of by how badly they were lost; see {@code LostCases.byPotential}.
	 */
	public boolean replayByPotential = false;
	/**
	 * Of the scripted fights that are not replays, the share made from a kept fight (lost, or lately
	 * beaten) changed a little: the edge of what it can do, explored around (ACCEL). 0 is off.
	 */
	public float mutateShare = 0f;
	/**
	 * The answer rule owes no swing to an opponent backing straight out after a single hit (the
	 * spacer's crit, S): the sword is kept for its next jump in, which the meet rule hits out of the
	 * air. A combo (the second hit in a row) is always answered. See {@code AnswerRule.opponentBacking}.
	 */
	public boolean answerSpareBacking = false;
	/**
	 * The least charge the weapon must have for the network to be given the attack key at all; see
	 * {@code SwingGate}. Zero leaves every swing to the network, as up to 0.13.3. Eleven ticks after
	 * the last swing since 18 October ({@code SwingGate.EARLY_CHARGE}); twelve before.
	 */
	public float swingMinCharge = dev.swordai.core.rl.SwingGate.EARLY_CHARGE;
	/**
	 * The least charge for a swing a rule makes for it (the answer it owes, the run at an opponent on
	 * its way up): the sword all the way back, twelve ticks after the last swing. Whether to swing a
	 * tick sooner is the network's to learn, not a rule's.
	 */
	public float ruleSwingCharge = dev.swordai.core.rl.SwingGate.MIN_CHARGE;
	/**
	 * The same on someone's server, where it is at least this: the weapon all the way back, which is
	 * one tick more (thirteen after the last swing). A server counts the time between two swings in its
	 * own ticks, from when they reach it. If two of them arrive a tick or two closer together than they
	 * were made, or the server is running slow, a swing made the moment the weapon was back on the
	 * fighter's own screen is one made too early as the server counts it: no knockback, no critical
	 * hit, less damage. The tick in hand is what keeps that from happening.
	 *
	 * <p>Twelve ticks (0.96) from 18 October, back to thirteen (1.0, as in 0.14.0) the same evening: at
	 * 105 ms the user saw weak hits again. Two ticks in hand, as 0.14.0 had, and no weak hit is worth
	 * the tick of rhythm.
	 */
	public float swingMinChargeOnline = 1f;
	/**
	 * The same for the swings a rule makes, on someone's server: thirteen ticks, as every swing was up
	 * to 18 October. The network's own may come a tick sooner ({@link #swingMinChargeOnline}, twelve
	 * ticks: on a connection that brings two swings a tick closer together the server counts eleven,
	 * 0.92, which is still more than the nine tenths it asks for).
	 */
	public float ruleSwingChargeOnline = 1f;
	/**
	 * On someone's server, how many ticks more it waits once the weapon is all the way back. None
	 * unless a server still makes weak hits of its swings: the result of a fight says how many of its
	 * swings were early as its own game counted them, and if that is none and the hits are weak all
	 * the same, the server is counting shorter, and each tick here is one more in hand. Each also
	 * costs a thirteenth of its hits.
	 */
	public int swingExtraTicksOnline = 0;
	/**
	 * Hit, and not yet having hit back, it swings as soon as its sword is back and the opponent is
	 * under its crosshair and near enough; see {@code AnswerRule}. A rule, in training and in every
	 * fight, so that it never takes a combo without swinging back where a swing was there to be made.
	 * False leaves that to the network, as up to the first runs of 0.14.0.
	 */
	public boolean answerRule = true;
	/**
	 * Not 0: the answer rule pushes instead of pressing. Where it would swing for the fighter, this is
	 * added to the network's swing logit, and where it would hold the swing back, taken off; the
	 * network's own choice is then what is done and what is learned from (Residual Policy Learning,
	 * Silver et al. 2018). Up to 21 October the rule pressed the key itself and took the attack head
	 * out of the step it decided, about 78 swings in 100: the network never learned when to swing right
	 * after being hit, the very moment spacing is decided in (a rule that overrides the agent without
	 * telling it is a "shield", Alshiekh et al. AAAI 2018, and such shields hold learning back). 0 keeps
	 * the rule pressing, as before.
	 */
	public float answerNudge = 0f;
	/**
	 * The tactics model ({@code rl.TacticNet}): a network of its own chooses every half second how to
	 * fight (in and combo, crit back, space at the edge of the reach and crit back, or back off after a
	 * hit; {@code rl.Tactic}), which turns {@code counterCritRule}, {@code edgeHoldRule} and
	 * {@code stepBackRule} on and off in their place, and is handed to the acting network as an input.
	 * It learns by PPO at its own pace from its own choices. The user's idea of 21 October: a decision
	 * model made only to choose the way to fight. A network trained with it must be played with it.
	 */
	public boolean tacticModel = false;
	/** The tactics model's learning rate (its own optimiser; the acting network's does not move it). */
	public float tacticLr = 3e-4f;
	/** Its entropy bonus: keeps it trying every way now and then. */
	public float tacticEntropy = 0.01f;
	/**
	 * How firmly the tactics model is held near the plan it starts with (a KL to it on every choice, as
	 * the acting network is held near the installed one), 0 for not at all. On 21 October, unheld, it
	 * went from crit back on 45% of its choices to 7% in two hours of training: crit back pays against
	 * the spacer and not against the jump-crit chaser and the whiffer, and without telling them apart it
	 * dropped it everywhere; the spacer went from +58 to -70 a minute.
	 */
	public float tacticPriorWeight = 0f;
	/**
	 * The tactics model also chooses again right after a hit either way, a swing of the opponent's and a
	 * jump of the opponent's (at the least {@code rl.Tactic.LEAST} ticks after its last choice), not only
	 * every half second; see {@code rl.Tactic.Clock}.
	 */
	public boolean tacticOnEvents = true;
	/**
	 * The opponent reader ({@code rl.OpponentReader}; only with {@code tacticModel}): a small network
	 * reads the last 3.2 seconds of the fight into sixteen numbers, taught to name the kind of opponent,
	 * and hands them to the tactics model and the acting network. A network trained with it must be
	 * played with it.
	 */
	public boolean opponentReader = false;
	/**
	 * The crit model (the jump key, and W/S, the sprint key and the swing in a jump) and the escape model
	 * (W/S just after a hit of a combo) read what the trunk has made of the fight (after the blocks, the
	 * tactic and the opponent reader) and the reader's numbers, not the vector input alone; see
	 * {@code rl.ActorNet}. Read-only: what they learn does not go back into the trunk. They start at
	 * zero, so a network plays exactly as before until it learns to use them. Gemini's audit, 23 October:
	 * the 32-unit crit model never knew which opponent it faced. A network trained with it must be played
	 * with it.
	 */
	public boolean critBridge = false;
	/**
	 * A memory of the last 3.2 seconds for the acting network: a causal temporal convolution over the
	 * opponent reader's window, into the trunk and the crit and escape models through weights that start
	 * at zero, learned from the policy's own gradient; see {@code rl.RhythmNet}. Only with the reader
	 * ({@link #opponentReader}), whose window it reads. Gemini's review, 23 October, and the user: build it.
	 * A network trained with it must be played with it.
	 */
	public boolean rhythmNet = false;
	/** Every fight against a scripted opponent written down tick by tick to {@code swordai/traces}, for reading how fights are lost. */
	public boolean traceFights = false;
	/** The reader's learning rate (its own optimiser). */
	public float readerLr = 1e-3f;
	/** Minibatches of 128 it learns from after each update, drawn from its memory of this many steps; see {@code rl.OpponentReader.Memory}. */
	public int readerBatches = 16;
	public int readerMemory = 20000;
	/**
	 * In the air, the swing that rule makes is kept for the way down and made as a critical hit, the
	 * punish crit; see {@code AnswerRule.inTheAir}. False makes it the moment the rule applies.
	 */
	public boolean punishRule = true;
	/**
	 * On the ground, the swing kept for the way down is kept for as long as the hit is fresh (a
	 * second), as up to 14 October; false keeps it on the tick of the hit only, and from the next
	 * tick on a swing that reaches is made. See {@code AnswerRule.onTheGround}.
	 */
	public boolean punishWaitOnGround = false;
	/**
	 * A running jump, and another as it lands, at an opponent five blocks off or more that is not
	 * itself coming in at a run: it closes the distance faster than a run, and the hit at the end of
	 * the last one is a critical hit if the way down brings the opponent into reach; see
	 * {@code JumpIn.approachHops}. Hit in the air of such a jump, it leaves them alone for a while.
	 */
	public boolean hopRule = true;
	/**
	 * A running jump is made for it at an opponent it is running after and not catching, a few steps
	 * beyond its reach, and the hit on the way down is made a critical hit; see {@code JumpIn}.
	 */
	public boolean chaseRule = true;
	/**
	 * That jump is made at an opponent the run is closing on by no more than this, in blocks a tick.
	 * 0.31: at any opponent that is not itself coming in, also one that holds its ground a step or
	 * two out of reach (a run closes on that one by 0.28). 0.15: only at one that is backing off or
	 * running. Hit in the air of such a jump, it does not make another for fifteen seconds either way.
	 */
	public float chaseClosingMost = 0.31f;
	/**
	 * A jump is made for it when the opponent is still in its face a few ticks after its hit, and the
	 * hit on the way down is a critical hit: against one that a hit does not send away; see {@code JumpIn}.
	 */
	public boolean faceRule = true;
	/**
	 * The share of the hits of a combo of its own, from the third on, that an uppercut follows: a
	 * running jump and the sprint hit made on the way up; see {@code JumpIn}. 0: never.
	 */
	public float uppercutChance = 0.4f;
	/**
	 * The camera is left alone while the opponent is far away and the crosshair is on it or all but
	 * on it; see {@code FarRest}.
	 */
	public boolean farRestRule = true;
	/**
	 * With the opponent off the screen for three ticks, the camera turns to the side it was last seen
	 * on and comes level until it is found; see {@code LostRule}. Since 18 October.
	 */
	public boolean lostRule = true;
	/**
	 * With the opponent in view and out of reach, the strafe keys are let go of, so that it runs
	 * straight in instead of going round in a circle; see {@code StraightIn}. Since 18 October.
	 */
	public boolean straightInRule = true;
	/**
	 * No swing at an opponent plainly out of reach (read off its height on screen), or not on the
	 * screen at all: such a swing hits nothing, starts the sword over and hands the opponent a free hit.
	 * See {@code SwingGate.outOfReach}. Since 18 October.
	 */
	public boolean whiffGuard = true;
	/**
	 * In training, the kinds of scripted opponent it has been losing rounds to come up more often, and
	 * the ones it nearly always beats less (prioritized fictitious self-play, as AlphaStar picked its
	 * opponents). Since 18 October; off unless set.
	 */
	public boolean hardOpponents = false;
	/**
	 * In training, this share of the scripted fights is against the outside bot: Carpet PvP's own fighter
	 * (a fork of Carpet whose fake players fight by themselves, https://modrinth.com/mod/carpet-pvp-practice),
	 * where the game runs on it instead of Carpet. An opponent that was not written here, at the levels in
	 * {@link #outsideLevels}. Zero (off) unless set; without Carpet PvP in the game it never comes up.
	 * Since 18 October.
	 */
	public float outsideShare = 0f;
	/** The levels the outside bot plays at, one drawn per fight: beginner, casual, average, skilled, expert. */
	public String outsideLevels = "average,skilled,expert";
	/**
	 * Every round of {@code /swordai fight} is written to {@code swordai/fights}, tick by tick, with what
	 * likely decided it; the newest 200 are kept. See {@code RoundRecorder}. Since 18 October.
	 */
	public boolean recordRounds = true;
	/**
	 * The share of the fights against copies of itself that are against the old model, where one is
	 * kept ({@code swordai/old-model.bin}, with the settings it played with in {@code old-model-config.json}).
	 * It plays as it did: its sword all the way back every time, the rules it had then. The user,
	 * 18 October: the old model "can be spaced", so a network that spaces it, finds the timing
	 * loophole and combos it has learned to choose its timing. Since 18 October.
	 */
	public float oldModelShare = 0.3f;
	/**
	 * Whether, being comboed, it still keeps its swing for the way down against an opponent it has
	 * made a punish crit on (the exception in {@code AnswerRule.when}). Off since 18 October: against
	 * the user's friend that was nearly every hit of a combo, a swing held in the air and made on the
	 * exact tick of the fall, time after time, and to the friend it looked like a trigger bot rather
	 * than a fighter. Being comboed it now swings back the moment it can; the swing kept for the way
	 * down is for the first hit of an exchange.
	 */
	public boolean punishInCombo = false;
	/**
	 * Crit back: with the opponent's sword on its way back (it has just swung, hit or miss) and its own
	 * sword nearly back, an opponent at the edge of the reach is jumped at and hit on the way down, a
	 * critical hit in the opponent's cooldown; see {@code JumpIn.COUNTER}. Since 19 October: players
	 * who space it crit it and sprint-hit it, and it never critted back. Off: in the frozen test of
	 * 19 October evening it cost more damage than it won with both networks tried (against the hard
	 * scripted opponents, damage dealt minus taken a minute -64 with it and -24 without; v19 -76 and -49).
	 */
	public boolean counterCritRule = false;
	/**
	 * An opponent jumping in for a crit is met (see {@code MeetJump}) also when it is the opponent alone
	 * that is coming, the fighter still being carried back by a hit: before 19 October only when the two
	 * were closing fast, which a hit taken a moment before ruled out on half the jumps.
	 */
	public boolean meetWhenComing = true;
	/**
	 * An opponent jumping in at it while its own sword is not back (it cannot meet that jump with a hit):
	 * it holds S for that jump, so the crit comes down out of reach, and comes in again once its sword is
	 * back; see {@code MeetJump.dodges}. Since 19 October: on half the jumps made at it its sword was not
	 * back, and those were the crits it took. Off: in training it lost the spacer more rounds than before
	 * (49% won without it, 29% with it), half an hour each on 19 October.
	 */
	public boolean dodgeJumpRule = false;
	/**
	 * The swing owed for a hit taken is kept for the fighter's way down (the punish crit, {@code AnswerRule.inTheAir})
	 * only with the opponent nearer than this, as {@code JumpIn.distance} reads it; farther off it is made at once,
	 * while the opponent is still under the crosshair. 0: always kept, as before 22 October. From the fight traces
	 * of 22 October: thrown up by a hit at 2.6 blocks or more, the crit came on 28% of those swings against the
	 * spacer and 10% against the one that stands and hits, and against those two the swing was never made at all
	 * on 42% and 30% of the openings where its sword was spent and the fighter's back (the knockback carried the
	 * fighter out of reach while it waited). Nearer than 2.2 the crit came on 68 to 97% of them, against every kind.
	 */
	public float keepWithin = 0f;
	/**
	 * The chase and the hop ({@code JumpIn.CHASE}, {@code JumpIn.HOP}) are only made at an opponent whose sword is
	 * not ready, or that is running away; never at one standing its ground with its sword ready. From the fight
	 * traces of 22 October: a running jump at a ready opponent that holds its ground is hit out of the air before it
	 * comes down (in the air the fighter's eye is higher and its reach to a grounded opponent shorter, and such an
	 * opponent hits whatever comes into its reach): the one that stands and hits did so on 98% of the chases and
	 * all the hops, the spacer on 74% of the chases, the timer on all of them.
	 */
	public boolean jumpAtReadyOnlyFleeing = false;
	/**
	 * The crit in ({@code JumpIn.EDGE}): at an opponent standing still on the ground, a running jump timed to come down into
	 * its reach late in the fall, and the critical hit as it does. Since 22 October, from the fight traces: against the
	 * one that stands and hits, every exchange was a hit for a hit (16.6 trades a fight, 0.3 crits of its own).
	 */
	public boolean edgeCritRule = false;
	/**
	 * The combo jump ({@code JumpIn.COMBO}): a few ticks after its own hit, a sprint-jump after the opponent that hit threw
	 * back and the next hit on the way down, W and the sprint key down all the way. Since 23 October, from the user's own
	 * recorded fights: they won against the spacer by staying on it (in reach 78% of the time to the fighter's 51%, never
	 * S, 16.5 jumps of their own a fight to its 2.3) and landed 38 hits a fight to its 30, not by critical hits (1-3%).
	 */
	public boolean comboJumpRule = false;
	/** How far back its sword must be for the combo jump to take off: the user's median 0.32; 0.15 in the first trial was too soon. */
	public float comboJumpChargeMin = 0.3f;
	/**
	 * The share of training episodes against the one that jumps in ({@code BotProfile.jumpInAlways}): a running jump at the
	 * fighter with its sword ready and the critical hit on the way down, nothing else, and short. The episode ends at the
	 * fighter's first hit after taking one (its punish), at a second hit taken, or after {@link #jumpInTicks}. The user, 23
	 * October: this is the one case their friends beat the installed network with, every time; train on it heavily.
	 */
	public float jumpInShare = 0f;
	public int jumpInTicks = 80;
	/**
	 * No swing at an opponent in the air with its sword ready, at the top of its jump or coming down, in reach and coming
	 * in: the hit would not stop its crit (see {@code rl.CritTrade}). The crit is let land, and the punish crit made.
	 */
	public boolean holdOnFallingJumper = false;
	/**
	 * The clash jump ({@code JumpIn.CLASH}): an opponent jumping in at it with its sword ready, near, is jumped with, and
	 * the crit made on the way down, crit for crit; the hold rule above is for where it is not in that jump. The user,
	 * 23 October: both players jump in, and close in their crit's knockback is late, so the fighter's own crit still lands.
	 */
	public boolean clashJumpRule = false;
	/** How many ticks late the hold rule takes a hit's knockback to reach the opponent on a server; see {@code CritTrade.knockAwayCertain}. */
	public int holdKnockbackDelay = 4;
	/**
	 * The punish crit made at once ({@code CritTrade.punish}): the sprint let go of on the tick of the hit, W held without
	 * it on the way up (drifting back in), the swing on the first tick of the way down. The user, 23 October: "their crit
	 * will knock you away so the punish crit must be also like them instant".
	 */
	public boolean instantPunish = false;
	/**
	 * The aim rule ({@code AimRule}) brings the camera level with the fighter's own eye on the opponent (where its body spans
	 * that height) instead of onto the middle of it: the shortest ray to it, so the most reach. Since 22 October.
	 */
	public boolean aimLevel = false;
	/**
	 * Spacing in: with the opponent just out of reach and its sword ready, it does not walk into that
	 * reach but stands at the edge and lets the opponent come, going in once that sword has been spent
	 * (or after a second of waiting); see {@code EdgeHold}. Since 19 October, on trial in training.
	 */
	public boolean edgeHoldRule = false;
	/**
	 * In self-play, the share of the scripted fights that goes to the stage with the opponents it
	 * still loses to (the crit stage and its kinds: spacer, timer, jumper, punisher...). The rest is
	 * shared by the earlier stages, whose opponents it beats four rounds in five. 0.5 up to 19 October.
	 */
	public float selfCritShare = 0.5f;
	/**
	 * In {@code /swordai fight}, how the opponent fights is counted and kept in
	 * {@code swordai/people/<name>.json} (a few hundred bytes a player), and training fights scripted
	 * opponents made from those it has lost to or nearly; see {@code PersonStyle} and
	 * {@code StageSpec.peopleShare}.
	 */
	public boolean watchStyles = true;
	/**
	 * In the ticks after it has been hit, until it has hit back, the camera is brought onto the
	 * opponent for it; see {@code AimRule}.
	 */
	public boolean aimRule = true;
	/**
	 * In the twelve ticks after each hit of a combo it is taking (from the second hit in a row), S is
	 * held for it: it backs out, and comes in again for the first hit. A rule, from the stage on that
	 * keeps the push for S for good ({@code StageSpec.backNudgeKeep}), in training and in every fight.
	 * It is what the push at its full strength came to anyway (S on 94 to 98 ticks in 100 of those),
	 * said outright, and nothing the network learns can wear it down. False leaves it to the push.
	 */
	public boolean backRule = true;
	/**
	 * The share of the hits of a combo it is taking (the second in a row and every later one) that it
	 * jumps with, W and the sprint key down, on the tick it learns of the hit: a jump reset, which
	 * halves how far the hit throws it, so that it comes down with the opponent in its reach and a
	 * swing to make; see {@code JumpReset}. On the rest, and on a hit that finds it in the air, S is
	 * held as {@link #backRule} has it. Never on the first hit. 0: never, which is how it was up to
	 * the first evening against people, when backing off with S was all it did about a combo and a
	 * player who came after it with a running jump and a critical hit had it for as long as he liked.
	 */
	public float jumpResetChance = 1f;
	/**
	 * From the second hit of a combo it is taking, A or D held for half a second after each such hit,
	 * away from the way the opponent is moving across its view: out of the line the combo comes down,
	 * and in again from the side; see {@code ComboStrafe}. With {@link #jumpResetChance} it is a jump out
	 * to the side. The user, 23 October: it "don't know how to run and run to left or right to make its
	 * combo hard to continue and go back in". Off until measured.
	 */
	public boolean comboStrafeRule = false;
	/**
	 * The share of the hits that find it with its sword still on the way back from a hit of its own
	 * (no more than thirteen ticks after it) that it jumps with as well: the opponent's answer to its
	 * hit, which it has no swing to answer; see {@code JumpReset}. 0: only ever in a combo.
	 *
	 * <p>Off. Measured with the network not learning: in a fast exchange nearly every hit it takes
	 * comes within those thirteen ticks, so it jumped with nine hits in ten (64 jumps a minute against
	 * the opponent that follows its hits with a running jump, 84 against the one that answers every
	 * hit), which is far more of this than was asked for; it then landed 69 hits to 73 taken against
	 * the first, where it is 55 to 68 without, and no better than before against the second, and
	 * its sweeps went from one hit in twelve to one in six.
	 */
	public float jumpResetCounterChance = 0f;
	/**
	 * Against an opponent that has followed a hit with a second one made from the air in the last
	 * twenty seconds (the running jump and the critical hit), the first hit is jumped with as well;
	 * see {@code JumpReset}. False: only ever from the second hit in a row, against anyone.
	 *
	 * <p>Off. Tried for ten minutes of training: every opponent that makes critical hits counts as
	 * one of those, so it jumped with nearly every hit it took (77 jumps a minute against the one
	 * this was meant for, where it had been 20), which is the fighter nobody can combo that this was
	 * not to become; and it gained nothing by it (62 hits landed to 78 taken, for 54 to 68), while
	 * the hits it then made from a standstill were sweeps, three in ten.
	 */
	public boolean jumpResetChasers = false;
	/**
	 * Against an opponent that answers its hits while its sword is on the way back (twice within
	 * five seconds), it walks backwards out of that opponent's reach after each hit of its own until its
	 * sword is nearly back; see {@code StepBack}. What it keeps clear of is the critical hit such an
	 * opponent makes on its way down, which there is no swing to answer with.
	 *
	 * <p>Off since the evening of 14 October. Against the scripted opponents it took fewer hits for
	 * as many landed; against people it was the S habit again in another place: after its own hit it
	 * walked backwards, an opponent who came straight on had the first hit, and what the user saw was
	 * a fighter that hardly combos and loses to weak players. In fifteen fights against six players it
	 * took 47 hits a minute where it had been taking 39, and landed 51 for 47.
	 */
	public boolean stepBackRule = false;
	/**
	 * The chase jump ({@link #chaseRule}) is also the next hit of a combo of its own, at an opponent
	 * that backs off after being hit; see {@code JumpIn.chaseInCombo}.
	 */
	public boolean chaseInCombo = true;
	/**
	 * An opponent that is coming at it through the air, still on its way up, is run at and hit the
	 * moment it is under the crosshair, if the sword is back; see {@code MeetJump}. A critical hit
	 * is only made on the way down, and this is the hit that gets there first.
	 */
	public boolean meetJumpRule = true;
	/**
	 * Against a copy of itself, who makes its move first is drawn on every tick. Up to the first runs
	 * of 0.14.0 the copy always moved first and the fighter after it, and that alone decided most
	 * fights between equals; see {@code EpisodePlan.mixedTurns}. False is that.
	 */
	public boolean selfPlayTurns = true;
	/**
	 * Training on the screen against a copy of itself: the copy sees the fighter this many ticks
	 * later than it otherwise would. The user's character sees the copy a tick late and its clicks
	 * reach the server a tick late, as a player's do; the copy plays on the server and has neither
	 * delay, and without this the fight is one against an opponent that is always two ticks ahead
	 * (0.4 to 0.6 hits landed for each taken, against a copy it is level with off the screen).
	 */
	public int screenCopyLagTicks = 2;
	/**
	 * A fight against a real opponent is learned from this many steps at a time (20 a second), far
	 * fewer than a training stage gathers: such fights are short and each of them is worth more.
	 */
	public int fightRolloutSteps = 600;
	/** {@code /swordai fight}: respawn by itself after dying, so a series of fights needs no hands. */
	public boolean fightAutoRespawn = true;
	/** {@code /swordai spar}: ticks of stand-off before each round. */
	public int sparCountdownTicks = 60;
	/** Probability that an episode replays an earlier stage, so old skills keep being practised. */
	public float rehearsalProbability = 0.12f;
	public String kitSword = "minecraft:diamond_sword";
	/** "diamond", "netherite", "iron" or "none". Worn for real (it changes damage) but never drawn on the opponent. */
	public String kitArmor = "diamond";
	/**
	 * Half-width of the square arena floor, in blocks. (12 up to 0.13.3, when every fight began within
	 * ten blocks. Some now begin up to 26 blocks apart, and that has to fit between the walls.)
	 */
	public int arenaRadius = MIN_ARENA_RADIUS;

	public List<StageSpec> stages = Stages.defaults();

	public int resolvedTrainerThreads() {
		if (trainerThreads > 0) {
			return trainerThreads;
		}
		int cores = Runtime.getRuntime().availableProcessors();
		return Math.max(1, Math.min(4, cores / 2 - 1));
	}
}
