package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.StageSpec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;

/**
 * Ties the networks, the rollout buffer and PPO together.
 *
 * <p>Threading contract: {@link #act}, {@link #complete} and {@link #value} are called from the
 * game thread; {@link #update} runs on a trainer thread. The caller must not act while an update
 * is in flight, except through {@link #actStandIn}, which plays a copy the update does not touch.
 */
public final class Learner implements AutoCloseable {
	private static final int MAGIC = 0x53574149; // "SWAI"
	private static final int FORMAT = 1;

	public final Config cfg;
	private final ActorNet actor;
	private final CriticNet critic;
	private final Ppo ppo;
	private final Rollout rollout;
	private final RewardScaler scaler;
	private final Random rng;
	/**
	 * The acting network's own draws, apart from {@link #rng}, which the update uses on its own thread while fights go on:
	 * see {@link #seedSampling}.
	 */
	private final Random sampleRng;

	private final ActorNet.Workspace actWs;
	private final CriticNet.Workspace valueWs;
	private final float[] probs = new float[ActionSpace.TOTAL_LOGITS];

	// A copy of the acting network for a fight that cannot wait while an update runs; made on demand.
	private ActorNet standIn;
	private ActorNet.Workspace standInWs;
	private final float[] standInProbs = new float[ActionSpace.TOTAL_LOGITS];
	private final Random standInRng;

	// The proven network, for learning from fights against people in safety ({@link SafeLearn}): it
	// plays its turns as it is and no update touches it. Made on demand.
	private ActorNet base;
	private ActorNet.Workspace baseWs;
	private final float[] baseProbs = new float[ActionSpace.TOTAL_LOGITS];
	private final Random baseRng;

	/** Steps that went far better than expected, imitated after each update; see {@link SilBuffer}. Made on demand. */
	private SilBuffer sil;
	private volatile String silLine = "off";
	/** The user's recorded fights, to be learned from; see {@link DemoFile} and {@code Config.demoWeight}. */
	SilBuffer demos;
	private volatile String demoLine = "off";

	/**
	 * The tactics model ({@link TacticNet}, {@code Config.tacticModel}); null without it. Its copies for
	 * the stand-in and the proven network are made with theirs.
	 */
	private final TacticNet tactics;
	private final TacticNet.Workspace tacticWs;
	private TacticNet standInTactics;
	private TacticNet.Workspace standInTacticWs;
	private TacticNet baseTactics;
	private TacticNet.Workspace baseTacticWs;
	private final Random tacticRng;
	/** A tactic was chosen for the next action to be recorded, with this log-probability. */
	private boolean tacticChosen;
	private float tacticLogp;
	private boolean pendingTacticStart;
	private float pendingTacticLogp;
	private volatile String tacticLine = "off";
	/** Whether the checkpoint loaded last had a tactics model saved in it; see {@link #playsTactics}. */
	private boolean tacticsSaved;
	/**
	 * The opponent reader ({@link OpponentReader}, {@code Config.opponentReader}); null without it. With
	 * copies for the stand-in and the proven network, like the tactics model.
	 */
	private final OpponentReader reader;
	private final OpponentReader.Workspace readerWs;
	private OpponentReader standInReader;
	private OpponentReader.Workspace standInReaderWs;
	private OpponentReader baseReader;
	private OpponentReader.Workspace baseReaderWs;
	private boolean readerSaved;
	private volatile String readerLine = "off";
	private volatile OpponentReader.Stats lastReaderStats;
	/** What the reader is taught from, across updates; made on demand. Not saved: it fills again in a few minutes. */
	private OpponentReader.Memory readerMemory;
	/** Marks the reader's part of a checkpoint, after the tactics model's. */
	private static final int READER_MARK = 0x4F505052; // "OPPR"

	/** The TD errors of the steps of the running episode, for {@link #takeEpisodePotential}; kept only with {@code Config.replayByPotential}. */
	private float[] episodeDeltas = new float[512];
	private int episodeDeltaCount;

	private final Obs pendingObs = new Obs();
	private final int[] pendingAction = new int[ActionSpace.HEADS];
	private int pendingMask;
	private float pendingLogp;
	private float pendingValue;
	private boolean hasPending;
	private float lastValue;

	public long totalSteps;
	public long updates;
	private boolean upgraded;
	private int loadedScalars = ObsSpec.SCALARS;
	/** See {@link JumpNudge}; zero is none. Read by the thread that plays, set by the same. */
	private volatile float jumpNudge;
	private float pendingNudge;
	/** See {@link BackNudge}. */
	private volatile float backNudge;
	private float pendingBackNudge;
	/** Added to the swing logit of the next action ({@link Policy#pushSwing}); set by the player of each step. */
	private float swingBias;
	private float pendingSwingBias;
	/** How much the network itself preferred swinging at the last action, before any push on the swing. */
	private float lastSwingGap = Float.NaN;

	public Learner(Config cfg, long seed, int threads) {
		this.cfg = cfg;
		this.actor = newActor(cfg, seed);
		this.critic = new CriticNet(seed + 1, cfg.privilegedCritic, cfg.criticTask, cfg.criticBlocks, cfg.criticBlockWidth);
		this.ppo = new Ppo(actor, critic, cfg, threads);
		this.rollout = new Rollout(longestRollout(cfg) + Math.max(0, cfg.rolloutSlack));
		this.scaler = new RewardScaler(cfg.gamma);
		this.rng = new Random(seed + 2);
		this.sampleRng = new Random(seed + 11);
		this.standInRng = new Random(seed + 3);
		this.baseRng = new Random(seed + 4);
		this.actWs = actor.newWorkspace();
		this.valueWs = critic.newWorkspace();
		this.reader = cfg.tacticModel && cfg.opponentReader ? new OpponentReader(seed + 7) : null;
		this.readerWs = reader == null ? null : reader.newWorkspace();
		this.tactics = cfg.tacticModel ? new TacticNet(seed + 5, reader != null) : null;
		this.tacticWs = tactics == null ? null : tactics.newWorkspace();
		this.tacticRng = new Random(seed + 6);
	}

	/** An acting network of the shape the config asks for. */
	static ActorNet newActor(Config cfg, long seed) {
		return new ActorNet(seed, cfg.usePeriphery, cfg.useFovea, cfg.actorVecWide, cfg.actorBlocks, cfg.actorBlockWidth,
				cfg.tacticModel ? Tactic.COUNT : 0, cfg.tacticModel && cfg.opponentReader ? OpponentReader.Z : 0, cfg.critBridge,
				cfg.rhythmNet && cfg.tacticModel && cfg.opponentReader);
	}

	/** The buffer has to hold the largest rollout any stage asks for. */
	private static int longestRollout(Config cfg) {
		int longest = cfg.rolloutSteps;
		if (cfg.stages != null) {
			for (StageSpec stage : cfg.stages) {
				longest = Math.max(longest, stage.rolloutSteps);
			}
		}
		return longest;
	}

	public int actorParameters() {
		return actor.params.totalSize();
	}

	public int criticParameters() {
		return critic.params.totalSize();
	}

	public float value(Obs obs) {
		return critic.forward(valueWs, obs.vec, obs.priv, obs.task);
	}

	/** How strongly the policy is pushed towards a jump right after a swing, from the next action on; see {@link JumpNudge}. */
	public void setJumpNudge(float strength) {
		jumpNudge = Math.max(0f, strength);
	}

	public float jumpNudge() {
		return jumpNudge;
	}

	/** How strongly the policy is pushed towards S right after a hit of a combo it is taking, from the next action on; see {@link BackNudge}. */
	public void setBackNudge(float strength) {
		backNudge = Math.max(0f, strength);
	}

	public float backNudge() {
		return backNudge;
	}

	/** A push on the swing for the next action only ({@code Config.answerNudge}); 0 for none. */
	public void setSwingBias(float bias) {
		swingBias = bias;
	}

	/** How much the network preferred swinging to not swinging at the last action, before {@link #setSwingBias}. */
	public float lastSwingGap() {
		return lastSwingGap;
	}

	/**
	 * Puts one head back to how a head is when it is first unlocked: the trunk has no say in it yet,
	 * and it rests on its default with the given strength. For a control whose earlier training
	 * taught it one thing only (never to be used) and which now has something else to learn.
	 * Not while an update is running.
	 */
	public void reopenHead(int head, float restBias) {
		actor.reopenHead(head, restBias);
	}

	/**
	 * Chooses an action. With {@code record} set, the step is held as pending until
	 * {@link #complete} supplies its reward.
	 */
	public void act(Obs obs, int headMask, boolean greedy, boolean record, int[] actionOut) {
		act(obs, headMask, greedy, record, 1f, actionOut);
	}

	/**
	 * @param temperature only applied when the step is not recorded; training always samples the
	 *                    policy exactly as it is, or the stored log-probabilities would be wrong
	 */
	/**
	 * The temperature recorded steps are sampled at. 1 always while it learns; a run that does not learn
	 * (both learning rates 0) may set another, to measure how the network fights at the temperature it is
	 * played at ({@code Config.playTemperature}).
	 */
	public float recordTemperature = 1f;

	public void act(Obs obs, int headMask, boolean greedy, boolean record, float temperature, int[] actionOut) {
		boolean chosen = tacticChosen;
		tacticChosen = false;
		actor.forward(actWs, obs.peri, obs.fovea, obs.vec, obs.tactic, reader == null ? null : obs.opp, reader == null ? null : obs.oppWindow);
		float nudge = jumpNudge;
		float back = backNudge;
		JumpNudge.apply(actWs.logits, obs.vec, nudge);
		BackNudge.apply(actWs.logits, obs.vec, back);
		float bias = swingBias;
		lastSwingGap = Policy.swingGap(actWs.logits);
		Policy.pushSwing(actWs.logits, bias);
		float logp = Policy.sample(actWs.logits, headMask, greedy, record ? recordTemperature : temperature, sampleRng, probs, actionOut);
		if (!record) {
			return;
		}
		pendingNudge = nudge;
		pendingBackNudge = back;
		pendingSwingBias = bias;
		pendingTacticStart = chosen;
		pendingTacticLogp = tacticLogp;
		pendingObs.copyFrom(obs);
		System.arraycopy(actionOut, 0, pendingAction, 0, ActionSpace.HEADS);
		pendingMask = headMask;
		pendingLogp = logp;
		pendingValue = value(obs);
		hasPending = true;
	}

	/**
	 * Copies the acting network as it is now. From here on {@link #actStandIn} plays that copy, and
	 * may do so while {@link #update} runs on another thread. Call from the game thread, before the
	 * update is started.
	 */
	public void keepStandIn() {
		if (standIn == null) {
			standIn = newActor(cfg, 0L);
			standInWs = standIn.newWorkspace();
		}
		standIn.params.copyWeightsFrom(actor.params);
		if (tactics != null) {
			if (standInTactics == null) {
				standInTactics = new TacticNet(0L);
				standInTacticWs = standInTactics.newWorkspace();
			}
			standInTactics.params.copyWeightsFrom(tactics.params);
		}
		if (reader != null) {
			if (standInReader == null) {
				standInReader = new OpponentReader(0L);
				standInReaderWs = standInReader.newWorkspace();
			}
			standInReader.params.copyWeightsFrom(reader.params);
		}
	}

	/**
	 * Chooses an action with the copy kept by {@link #keepStandIn}, the way {@link #act} would have
	 * chosen at that moment. Nothing is recorded, so nothing is learned from it.
	 */
	public void actStandIn(Obs obs, int headMask, boolean greedy, int[] actionOut) {
		if (standIn == null) {
			throw new IllegalStateException("no copy of the network was kept to play with");
		}
		standIn.forward(standInWs, obs.peri, obs.fovea, obs.vec, obs.tactic, reader == null ? null : obs.opp, reader == null ? null : obs.oppWindow);
		JumpNudge.apply(standInWs.logits, obs.vec, jumpNudge);
		BackNudge.apply(standInWs.logits, obs.vec, backNudge);
		lastSwingGap = Policy.swingGap(standInWs.logits);
		Policy.pushSwing(standInWs.logits, swingBias);
		Policy.sample(standInWs.logits, headMask, greedy, 1f, standInRng, standInProbs, actionOut);
	}

	/**
	 * Keeps the acting network as it is now as the proven one ({@link SafeLearn}): from here on
	 * {@link #actBase} plays it, whatever updates do to the acting network. Not while an update runs.
	 */
	public void keepBase() {
		if (base == null) {
			base = newActor(cfg, 0L);
			baseWs = base.newWorkspace();
		}
		base.params.copyWeightsFrom(actor.params);
		baseWithoutTactics = false;
		if (tactics != null) {
			if (baseTactics == null) {
				baseTactics = new TacticNet(0L);
				baseTacticWs = baseTactics.newWorkspace();
			}
			baseTactics.params.copyWeightsFrom(tactics.params);
		}
		if (reader != null) {
			if (baseReader == null) {
				baseReader = new OpponentReader(0L);
				baseReaderWs = baseReader.newWorkspace();
			}
			baseReader.params.copyWeightsFrom(reader.params);
		}
	}

	/** False for a network loaded by {@link #loadBase} that was saved without a tactics model: it fights with no tactic, as it was trained. */
	private boolean baseWithoutTactics;

	public boolean baseHasTactics() {
		return tactics != null && !baseWithoutTactics;
	}

	/**
	 * Loads a saved network as the one {@link #actBase} plays, for a paired evaluation ({@code Config.pairedBase}):
	 * its actor, and its tactics model and reader where the file has them. Read as {@link #load} reads, into a network
	 * of this run's shape; the parts it was saved without start out computing nothing. Not while an update runs.
	 */
	public void loadBase(Path file) throws IOException {
		ActorNet a = newActor(cfg, 0L);
		TacticNet t = null;
		OpponentReader r = null;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			int inserted = readLayout(in);
			int insertAt = ObsSpec.VEC_SCALARS + ObsSpec.SCALARS - inserted;
			in.readLong();
			in.readLong();
			a.params.read(in, insertAt, inserted);
			if (a.params.missing() > a.grown) {
				throw new IOException("the network to compare with is from before the network had all its parts");
			}
			// Its critic and reward scale are not wanted, only passed over.
			new CriticNet(0L, cfg.privilegedCritic, cfg.criticTask, cfg.criticBlocks, cfg.criticBlockWidth).params.read(in, insertAt, inserted);
			new RewardScaler(cfg.gamma).read(in);
			if (tactics != null) {
				try {
					t = new TacticNet(0L, reader != null);
					t.params.read(in);
				} catch (java.io.EOFException e) {
					t = null;
				}
				if (t != null && reader != null) {
					try {
						if (in.readInt() == READER_MARK) {
							r = new OpponentReader(0L);
							r.params.read(in);
						}
					} catch (java.io.EOFException e) {
						r = null;
					}
				}
			}
		}
		base = a;
		baseWs = a.newWorkspace();
		baseTactics = t;
		baseTacticWs = t == null ? null : t.newWorkspace();
		baseWithoutTactics = t == null;
		baseReader = r;
		baseReaderWs = r == null ? null : r.newWorkspace();
	}

	/**
	 * Paired evaluation (Gemini, 23 October): the acting network's and the compared network's draws for the fight about to
	 * begin start from the same seed, so that both sides of a pair take the same action wherever their chances are the
	 * same, and the pair differs only where the networks do (common random numbers). Before, the two drew from streams
	 * of their own and parted on the first close call: one 30 s spacer pair varied by ~107 net a minute.
	 */
	public void seedSampling(long seed) {
		sampleRng.setSeed(seed);
		// While an update runs a copy acts in the network's place, with draws of its own.
		standInRng.setSeed(seed);
		baseRng.setSeed(seed);
	}

	/** Chooses an action with the proven network kept by {@link #keepBase}. Nothing is recorded. */
	public void actBase(Obs obs, int headMask, boolean greedy, float temperature, int[] actionOut) {
		if (base == null) {
			throw new IllegalStateException("no proven network was kept to play with");
		}
		base.forward(baseWs, obs.peri, obs.fovea, obs.vec, obs.tactic, reader == null ? null : obs.opp, reader == null ? null : obs.oppWindow);
		JumpNudge.apply(baseWs.logits, obs.vec, jumpNudge);
		BackNudge.apply(baseWs.logits, obs.vec, backNudge);
		lastSwingGap = Policy.swingGap(baseWs.logits);
		Policy.pushSwing(baseWs.logits, swingBias);
		Policy.sample(baseWs.logits, headMask, greedy, temperature, baseRng, baseProbs, actionOut);
	}

	/**
	 * Throws away what the acting network has learned since {@link #keepBase}: it is the proven one
	 * again, and its optimiser starts afresh. Not while an update runs.
	 */
	public void restoreBase() {
		if (base == null) {
			throw new IllegalStateException("no proven network was kept to go back to");
		}
		actor.params.copyWeightsFrom(base.params);
		for (dev.swordai.core.nn.Param p : actor.params.params()) {
			java.util.Arrays.fill(p.m, 0f);
			java.util.Arrays.fill(p.v, 0f);
		}
		if (tactics != null && baseTactics != null) {
			tactics.params.copyWeightsFrom(baseTactics.params);
			for (dev.swordai.core.nn.Param p : tactics.params.params()) {
				java.util.Arrays.fill(p.m, 0f);
				java.util.Arrays.fill(p.v, 0f);
			}
		}
		if (reader != null && baseReader != null) {
			reader.params.copyWeightsFrom(baseReader.params);
			for (dev.swordai.core.nn.Param p : reader.params.params()) {
				java.util.Arrays.fill(p.m, 0f);
				java.util.Arrays.fill(p.v, 0f);
			}
		}
	}

	/** Whether there is an opponent reader ({@code Config.opponentReader}): for training, one starts as it is made. */
	public boolean hasReader() {
		return reader != null;
	}

	/** Whether the network loaded is to be played with its opponent reader: only one trained with it. */
	public boolean playsReader() {
		return reader != null && readerSaved;
	}

	/**
	 * Reads {@code obs.oppWindow} into {@code obs.opp} ({@link OpponentReader}); by the copy kept for the
	 * stand-in or for the proven network when one of those plays the step. Without a reader, nothing.
	 */
	public void readOpponent(Obs obs, boolean byStandIn, boolean byBase) {
		if (reader == null) {
			return;
		}
		if (byBase && baseReader != null) {
			baseReader.read(baseReaderWs, obs.oppWindow, obs.opp);
		} else if (byStandIn && standInReader != null) {
			standInReader.read(standInReaderWs, obs.oppWindow, obs.opp);
		} else {
			reader.read(readerWs, obs.oppWindow, obs.opp);
		}
	}

	/** What the opponent reader did at the last update, for the log, with the kinds' names by class; null before any. */
	public String readerLine(String[] names) {
		OpponentReader.Stats s = lastReaderStats;
		return s == null ? (reader == null ? "off" : "not learning yet") : s.line(names);
	}

	/** Whether there is a tactics model ({@code Config.tacticModel}): for training, one starts as it is made. */
	public boolean hasTactics() {
		return tactics != null;
	}

	/**
	 * Whether the network loaded is to be played with its tactics model: only one trained with it. An
	 * opponent loaded from a network of before (the 0.14.0 model) plays as it always did.
	 */
	public boolean playsTactics() {
		return tactics != null && tacticsSaved;
	}

	/**
	 * The tactics model chooses how to fight from now on ({@link TacticNet}); -1 without one. With
	 * {@code record} the choice is learned from, as part of the next action recorded by {@link #act}.
	 */
	public int chooseTactic(Obs obs, boolean record) {
		if (tactics == null) {
			return -1;
		}
		int t = tactics.choose(tacticWs, obs.vec, reader == null ? null : obs.opp, tacticRng);
		if (record) {
			tacticChosen = true;
			tacticLogp = tacticWs.logp;
		}
		return t;
	}

	/** As {@link #chooseTactic}, by the copy kept with {@link #keepStandIn}; never recorded. */
	public int chooseTacticStandIn(Obs obs) {
		if (tactics == null) {
			return -1;
		}
		return standInTactics == null ? chooseTactic(obs, false) : standInTactics.choose(standInTacticWs, obs.vec, reader == null ? null : obs.opp, tacticRng);
	}

	/** As {@link #chooseTactic}, by the proven network's ({@link #keepBase}); never recorded. */
	public int chooseTacticBase(Obs obs) {
		if (tactics == null || baseWithoutTactics) {
			return -1;
		}
		return baseTactics == null ? chooseTactic(obs, false) : baseTactics.choose(baseTacticWs, obs.vec, reader == null ? null : obs.opp, tacticRng);
	}

	/** The tactics model's choices on {@code obs}, for the log; null without one. */
	public float[] tacticChances(Obs obs) {
		if (tactics == null) {
			return null;
		}
		tactics.forward(tacticWs, obs.vec, reader == null ? null : obs.opp);
		return tacticWs.probs.clone();
	}

	/** What the tactics model learned at the last update, for the log. */
	public String tacticLine() {
		return tacticLine;
	}

	/** Lowers the actor's learning rate to at most this (it may still adapt below it). */
	public void capActorLr(float most) {
		ppo.capActorLr(most);
	}

	public boolean hasPending() {
		return hasPending;
	}

	/** Forgets the pending step without touching what is already stored. */
	public void dropPending() {
		hasPending = false;
	}

	/**
	 * The episode was cut off before the pending step could be scored (pause, stop, lost opponent).
	 * The last stored step becomes the end of its episode, bootstrapped from the value of the
	 * observation that followed it, so advantages never run across the gap.
	 */
	public void interrupt() {
		int last = rollout.size - 1;
		if (hasPending && last >= 0 && !rollout.done[last]) {
			rollout.reward[last] += cfg.gamma * pendingValue;
			rollout.done[last] = true;
		}
		hasPending = false;
		lastValue = 0f;
	}

	/**
	 * How much the episode just ended had to teach, as prioritised level replay (Jiang et al., ICML 2021)
	 * scores a level: the mean |GAE| over its recorded steps. NaN when none were recorded. Starts the
	 * next episode's count.
	 */
	public float takeEpisodePotential() {
		int n = episodeDeltaCount;
		episodeDeltaCount = 0;
		if (n == 0) {
			return Float.NaN;
		}
		double gae = 0;
		double sum = 0;
		for (int t = n - 1; t >= 0; t--) {
			gae = episodeDeltas[t] + cfg.gamma * cfg.lambda * gae;
			sum += Math.abs(gae);
		}
		return (float) (sum / n);
	}

	/** Forgets the TD errors kept so far; at the start of an episode. */
	public void clearEpisodePotential() {
		episodeDeltaCount = 0;
	}

	/** Steps that still fit in the buffer. */
	public int rolloutRoom() {
		return rollout.capacity - rollout.size;
	}

	/**
	 * Closes the pending step and stores it.
	 *
	 * @param terminal  the episode really ended (a knockout): no value beyond this step
	 * @param truncated the episode was cut off (time limit, target hop reset): bootstrap from {@code next}
	 * @param next      the observation that followed; may be null only when {@code terminal}
	 */
	public void complete(float reward, boolean terminal, boolean truncated, Obs next) {
		if (!hasPending) {
			return;
		}
		hasPending = false;
		boolean ended = terminal || truncated;
		float r = cfg.normalizeRewards ? scaler.scale(reward, ended) : reward;
		if (truncated && !terminal) {
			r += cfg.gamma * value(next);
		}
		rollout.add(pendingObs, pendingAction, pendingMask, pendingLogp, pendingNudge, pendingBackNudge, pendingSwingBias, pendingValue, r, ended);
		if (pendingTacticStart) {
			rollout.markTacticChoice(pendingTacticLogp);
		}
		totalSteps++;
		if (cfg.replayByPotential) {
			// r already holds the bootstrap of a cut episode; a knockout has nothing after it.
			float delta = r + (ended ? 0f : cfg.gamma * value(next)) - pendingValue;
			if (episodeDeltaCount == episodeDeltas.length) {
				episodeDeltas = java.util.Arrays.copyOf(episodeDeltas, 2 * episodeDeltas.length);
			}
			episodeDeltas[episodeDeltaCount++] = delta;
		}
		// An update can start right after any step from here on, so keep the bootstrap value current.
		if (rollout.size >= cfg.rolloutSteps) {
			lastValue = ended ? 0f : value(next);
		}
	}

	/** Enough steps for an update; the caller may still let the running episode finish first. */
	public boolean rolloutReady() {
		return rolloutReady(0);
	}

	/** @param steps how many steps the next update should be made from; zero or less means the configured default */
	public boolean rolloutReady(int steps) {
		return rollout.size >= (steps > 0 ? steps : cfg.rolloutSteps);
	}

	/** No room left: the caller must cut the episode here and update. */
	public boolean rolloutFull() {
		return rollout.full();
	}

	public int rolloutSize() {
		return rollout.size;
	}

	public float actorLr() {
		return ppo.actorLr();
	}

	/** What self-imitation did at the last update, for the log; see {@link SilBuffer}. */
	public String silLine() {
		return silLine;
	}

	/** What learning from the user's recorded fights did at the last update, for the log; see {@link DemoFile}. */
	public String demoLine() {
		return demoLine;
	}

	/**
	 * Reads the user's recorded fights to be learned from ({@code Config.demoWeight}), the newest first,
	 * up to {@code Config.demoMaxSteps} steps, with their returns as the critic sees them now. Returns a
	 * line for the log. A file that cannot be read is skipped.
	 */
	public String loadDemos(java.util.List<Path> files) {
		java.util.List<DemoFile.Fight> fights = new java.util.ArrayList<>();
		int total = 0;
		int skipped = 0;
		java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
		for (Path file : files) {
			DemoFile.Fight f;
			try {
				f = DemoFile.read(file);
			} catch (IOException | RuntimeException e) {
				skipped++;
				continue;
			}
			if (total + f.size > cfg.demoMaxSteps) {
				break;
			}
			fights.add(f);
			total += f.size;
			kinds.merge(f.kind, 1, Integer::sum);
		}
		if (total == 0) {
			demos = null;
			demoLine = "no recorded fights" + (skipped > 0 ? " (" + skipped + " unreadable)" : "");
			return demoLine;
		}
		// Only the steps after which things went the person's way, if so set (Config.demoWindowTicks).
		java.util.List<boolean[]> keep = new java.util.ArrayList<>();
		int kept = 0;
		for (DemoFile.Fight f : fights) {
			boolean[] k = DemoFile.wentTheirWay(f.reward, cfg.demoWindowTicks, cfg.demoWindowMin);
			keep.add(k);
			for (boolean b : k) {
				kept += b ? 1 : 0;
			}
		}
		if (kept == 0) {
			demos = null;
			demoLine = "no recorded steps after which the fight went the person's way";
			return demoLine;
		}
		demos = new SilBuffer(kept);
		// The reader's window is not in the file; it is made again from the fight's own ticks, as it was made while it was fought.
		OpponentReader.Window window = reader == null ? null : new OpponentReader.Window();
		float[] seen = new float[OpponentReader.INPUTS];
		for (int n = 0; n < fights.size(); n++) {
			DemoFile.Fight f = fights.get(n);
			boolean[] k = keep.get(n);
			if (window != null) {
				window.reset();
			}
			float[] reward = new float[f.size];
			float[] value = new float[f.size];
			for (int i = 0; i < f.size; i++) {
				reward[i] = cfg.normalizeRewards ? scaler.scaleOnly(f.reward[i]) : f.reward[i];
				// As self-imitation weighs them: the critic told the kind of fight (Ppo.selfImitate).
				value[i] = critic.forward(valueWs, f.vec[i], f.priv[i], f.task[i]);
			}
			float[] ret = DemoFile.returns(reward, f.terminal, value, cfg.gamma, cfg.lambda);
			for (int i = 0; i < f.size; i++) {
				if (window != null) {
					window.push(f.vec[i]);
					window.write(seen);
				}
				if (k[i]) {
					demos.put(f, i, ret[i], window == null ? null : seen);
				}
			}
		}
		demoLine = String.format(java.util.Locale.ROOT, "%d steps from %d recorded fights %s%s%s", total, fights.size(), kinds,
				cfg.demoWindowTicks > 0 ? String.format(java.util.Locale.ROOT, ", %d of them followed by %d ticks worth more than %.1f", kept, cfg.demoWindowTicks, cfg.demoWindowMin) : "",
				skipped > 0 ? ", " + skipped + " unreadable" : "");
		return demoLine;
	}

	/**
	 * Blocking PPO update over the collected rollout; clears it afterwards.
	 *
	 * @param trainActor false trains only the critic (used right after a stage change)
	 */
	public UpdateStats update(boolean trainActor) {
		return update(trainActor, 1f);
	}

	/** @param entropyScale see {@code StageSpec.entropyScale} */
	public UpdateStats update(boolean trainActor, float entropyScale) {
		return update(trainActor, entropyScale, Float.NEGATIVE_INFINITY);
	}

	/** @param actorNeeds see {@code Ppo.update}: below this share of the returns explained, only the critic learns */
	public UpdateStats update(boolean trainActor, float entropyScale, float actorNeeds) {
		rollout.computeAdvantages(lastValue, cfg.gamma, cfg.lambda);
		int silKept = 0;
		if (cfg.silWeight > 0f && trainActor) {
			if (sil == null || sil.capacity != Math.max(1, cfg.silCapacity)) {
				sil = new SilBuffer(cfg.silCapacity);
			}
			silKept = sil.addFrom(rollout, cfg.silKeepZ);
		}
		UpdateStats stats = ppo.update(rollout, rng, trainActor, entropyScale, actorNeeds);
		if (tactics != null && stats.actorTrained && ppo.actorLr() > 0f && cfg.tacticLr > 0f) {
			// The tactics model learns from its own choices, at its own pace; see TacticNet.
			TacticNet.Stats t = tactics.update(rollout, lastValue, cfg.gamma, cfg.lambda, cfg.tacticLr, cfg.clip, cfg.tacticEntropy,
					cfg.tacticPriorWeight, cfg.epochs, cfg.klStop, cfg.maxGradNorm, rng);
			tacticLine = t.toString();
		} else if (tactics != null) {
			tacticLine = "not learning this update";
		}
		if (reader != null && ppo.actorLr() > 0f && cfg.readerLr > 0f) {
			// Taught to name the kind of opponent of each step; see OpponentReader.
			if (readerMemory == null) {
				readerMemory = new OpponentReader.Memory(cfg.readerMemory);
			}
			lastReaderStats = reader.learn(rollout, readerMemory, cfg.readerLr, Math.max(1, cfg.readerBatches), 128, cfg.maxGradNorm, rng);
		}
		if (sil != null && cfg.silWeight > 0f && stats.actorTrained) {
			// What went far better than expected, learned from again; see SilBuffer.
			Ppo.SilStats s = ppo.selfImitate(sil, rng, cfg.silBatches, cfg.silWeight, cfg.silValueWeight, cfg.silClip);
			silLine = String.format(java.util.Locale.ROOT, "%d steps kept from this update, %d kept in all, %d still better than expected (by %.3f on average), learned from %d",
					silKept, s.kept, s.stillBetter, s.meanGap, s.samples);
		}
		if (demos != null && cfg.demoWeight > 0f && stats.actorTrained) {
			// The user's own fights, where they did better than expected of the network; see DemoFile.
			Ppo.SilStats s = ppo.selfImitate(demos, rng, cfg.demoBatches, cfg.demoWeight, cfg.demoValueWeight, cfg.silClip);
			demoLine = String.format(java.util.Locale.ROOT, "%d recorded steps, %d better than expected of the network (by %.3f on average), learned from %d",
					s.kept, s.stillBetter, s.meanGap, s.samples);
		}
		rollout.clear();
		hasPending = false;
		lastValue = 0f;
		updates++;
		return stats;
	}

	public UpdateStats update() {
		return update(true);
	}

	public void save(Path file) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(MAGIC);
			out.writeInt(FORMAT);
			out.writeInt(ObsSpec.VEC);
			out.writeInt(ObsSpec.PRIV);
			out.writeInt(ActionSpace.TOTAL_LOGITS);
			out.writeLong(totalSteps);
			out.writeLong(updates);
			actor.params.write(out);
			critic.params.write(out);
			scaler.write(out);
			// After everything an older version reads, which therefore loads the file as it did.
			if (tactics != null) {
				tactics.params.write(out);
				if (reader != null) {
					out.writeInt(READER_MARK);
					reader.params.write(out);
				}
			}
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Reads a checkpoint's header up to its step counts and returns how many inputs this version has
	 * that it lacks. Throws {@link IOException} with a readable reason when the file was written for
	 * another network layout.
	 */
	private static int readLayout(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("not a SwordAI checkpoint");
		}
		int format = in.readInt();
		if (format != FORMAT) {
			throw new IOException("checkpoint format " + format + " is not supported (expected " + FORMAT + ")");
		}
		int vec = in.readInt();
		int priv = in.readInt();
		int logits = in.readInt();
		// Newer versions only ever add scalars after the ones that existed, so an older checkpoint
		// differs by exactly that many inputs, all inserted at one place in the vector.
		int inserted = ObsSpec.VEC - vec;
		boolean current = inserted == 0;
		boolean older = false;
		for (int before : ObsSpec.SCALARS_BEFORE) {
			older |= inserted == ObsSpec.SCALARS - before;
		}
		if ((!current && !older) || priv != ObsSpec.PRIV || logits != ActionSpace.TOTAL_LOGITS) {
			throw new IOException("checkpoint was trained with a different observation/action layout");
		}
		return inserted;
	}

	/**
	 * Loads the actor of a checkpoint as the anchor training is held near ({@link Config#anchorWeight});
	 * it is never trained. An older layout is upgraded as in {@link #load}, so it chooses exactly as it
	 * did. Not while an update runs.
	 */
	public void loadAnchor(Path file) throws IOException {
		ActorNet a = newActor(cfg, 0L);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			int inserted = readLayout(in);
			in.readLong();
			in.readLong();
			a.params.read(in, ObsSpec.VEC_SCALARS + ObsSpec.SCALARS - inserted, inserted);
			// Saved before the network was grown is fine: the grown parts start out computing nothing.
			if (a.params.missing() > a.grown) {
				throw new IOException("the anchor is from before the network had all its parts");
			}
		}
		ppo.setAnchor(a);
	}

	/** True when an anchor was loaded ({@link #loadAnchor}). */
	public boolean hasAnchor() {
		return ppo.hasAnchor();
	}

	/** Throws {@link IOException} with a readable reason when the file was written for another network layout. */
	public void load(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			int inserted = readLayout(in);
			boolean current = inserted == 0;
			int insertAt = ObsSpec.VEC_SCALARS + ObsSpec.SCALARS - inserted;
			long steps = in.readLong();
			long ups = in.readLong();
			actor.params.read(in, insertAt, inserted);
			critic.params.read(in, insertAt, inserted);
			scaler.read(in);
			if (tactics != null) {
				// Saved without a tactics model, the file ends here and the model starts as it is made.
				try {
					tactics.params.read(in);
					tacticsSaved = true;
				} catch (java.io.EOFException e) {
					TacticNet fresh = new TacticNet(0L, reader != null);
					tactics.params.copyWeightsFrom(fresh.params);
					tacticsSaved = false;
				}
				readerSaved = false;
				if (reader != null && tacticsSaved) {
					// Saved without a reader, the file ends after the tactics model and the reader starts as it is made.
					try {
						if (in.readInt() == READER_MARK) {
							reader.params.read(in);
							readerSaved = true;
						}
					} catch (java.io.EOFException e) {
						readerSaved = false;
					}
				}
				if (reader != null && !readerSaved) {
					reader.params.copyWeightsFrom(new OpponentReader(0L).params);
				}
			}
			totalSteps = steps;
			updates = ups;
			// Also a network from before it had the two small models: an older version cannot read it back.
			upgraded = !current || actor.params.missing() > 0;
			loadedScalars = ObsSpec.SCALARS - inserted;
		}
		rollout.clear();
		hasPending = false;
	}

	/** True when the checkpoint just loaded came from an older version and was upgraded in memory. */
	public boolean upgraded() {
		return upgraded;
	}

	/** True when the checkpoint just loaded was trained before the camera had a hand model ({@link AimMotor}). */
	public boolean loadedWithoutAimMotor() {
		return upgraded && loadedScalars <= ObsSpec.SC_TURN_RATE_YAW;
	}

	@Override
	public void close() {
		ppo.close();
	}
}
