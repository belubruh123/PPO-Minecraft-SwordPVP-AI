package dev.swordai.core.school;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ActorNet;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.Policy;
import dev.swordai.core.rl.RewardScaler;

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
 * Ties the technique school's networks together: the acting network, its critic, the predictor, the
 * buffer and the update; and the installed network as a teacher ({@link #loadOldTeacher}).
 *
 * <p>Threading: {@link #act}, {@link #complete} and {@link #value} on the game thread; {@link #update}
 * on a trainer thread while the fight waits.
 */
public final class SchoolLearner implements AutoCloseable {
	private static final int MAGIC = 0x53575343; // "SWSC"
	private static final int FORMAT = 1;

	public final SchoolNet actor;
	public final SchoolCritic critic;
	public final Predictor predictor;
	private final SchoolPpo ppo;
	private final SchoolRollout rollout;
	private final RewardScaler scaler;
	private final Random rng;
	private final Random sampleRng;
	private final SchoolNet.Workspace actWs;
	private final SchoolCritic.Workspace valueWs;
	private final Predictor.Workspace predWs;
	private final float gamma;
	private final float lambda;
	/** The installed network, a teacher; null without one. */
	private ActorNet oldTeacher;
	private ActorNet.Workspace oldWs;
	/** A network to compare with in a paired evaluation, or to play the opponent; null without one. */
	private SchoolNet base;
	private SchoolNet.Workspace baseWs;
	private final Random baseRng;

	private final SchoolRollout.Step pending = new SchoolRollout.Step();
	private boolean hasPending;
	private float lastValue;
	public long totalSteps;
	public long updates;
	private volatile String predictorLine = "not learning yet";
	/** The technique teachers' examples, learned from again after every update; see {@link TeacherReplay}. */
	private TeacherReplay replay;
	/** And the installed teacher's examples, so the network comes to play as the installed fighter does, quickly. */
	private TeacherReplay shadowReplay;
	private SchoolNet.Workspace replayWs;
	private volatile String shadowLine = "no examples yet";
	private volatile String replayLine = "no examples yet";

	public SchoolLearner(long seed, int capacity, float gamma, float lambda, int threads) {
		this.actor = new SchoolNet(seed);
		this.critic = new SchoolCritic(seed + 1);
		this.predictor = new Predictor(seed + 2);
		this.ppo = new SchoolPpo(actor, critic, threads);
		this.rollout = new SchoolRollout(capacity);
		this.scaler = new RewardScaler(gamma);
		this.rng = new Random(seed + 3);
		this.sampleRng = new Random(seed + 4);
		this.baseRng = new Random(seed + 5);
		this.actWs = actor.newWorkspace();
		this.valueWs = critic.newWorkspace();
		this.predWs = predictor.newWorkspace();
		this.gamma = gamma;
		this.lambda = lambda;
	}

	/**
	 * The installed network (an {@code ActorNet} of the shape installed on 19 October: wide vector layer
	 * 64, two blocks of 256) from a checkpoint of the stages before: as a teacher, and, with
	 * {@code takeOver}, its weights into the acting network.
	 */
	public void loadOldTeacher(Path checkpoint, boolean takeOver) throws IOException {
		ActorNet old = readOld(checkpoint);
		oldTeacher = old;
		oldWs = old.newWorkspace();
		if (takeOver) {
			actor.takeOver(old.params);
		}
	}

	/** An actor of the stages before, read from their checkpoint format. */
	public static ActorNet readOld(Path checkpoint) throws IOException {
		ActorNet old = new ActorNet(0L, true, true, 64, 2, 256, 0, 0, false, false);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(checkpoint)))) {
			if (in.readInt() != 0x53574149) {
				throw new IOException("not a SwordAI checkpoint of the stages before: " + checkpoint);
			}
			in.readInt();
			int vec = in.readInt();
			in.readInt();
			in.readInt();
			in.readLong();
			in.readLong();
			int inserted = ObsSpec.VEC - vec;
			old.params.read(in, ObsSpec.VEC_SCALARS + ObsSpec.SCALARS - inserted, inserted);
		}
		return old;
	}

	public boolean hasOldTeacher() {
		return oldTeacher != null;
	}

	/** The installed network's chances for every head on {@code obs}, into {@code out}. */
	public void oldChoices(SchoolObs obs, float[] out) {
		oldTeacher.forward(oldWs, obs.base.peri, obs.base.fovea, obs.base.vec);
		Policy.allProbs(oldWs.logits, out);
	}

	public float value(SchoolObs obs) {
		return critic.forward(valueWs, obs.base.vec, obs.base.priv, obs.mem, obs.oppTruth);
	}

	/** The predictor's guess on {@code obs}, into {@code out}. */
	public void predict(SchoolObs obs, float[] out) {
		predictor.forward(predWs, obs.base.vec, obs.mem);
		System.arraycopy(predWs.guess, 0, out, 0, Predictor.OUT);
	}

	/**
	 * Chooses an action. With {@code record} the step is held, with what {@code step} says of it (its
	 * teacher targets, its opponent's labels), until {@link #complete} gives its reward.
	 *
	 * @param step filled by the caller with the observation and the teachers' word; the action, its
	 *             chance and the value are written into it
	 */
	public void act(SchoolRollout.Step step, int headMask, boolean record, float temperature, int[] actionOut) {
		predict(step.obs, step.pred);
		actor.forward(actWs, step.obs.base.peri, step.obs.base.fovea, step.obs.base.vec, step.obs.mem, step.pred);
		float logp = actor.sample(actWs, headMask, false, record ? 1f : temperature, sampleRng, actionOut);
		if (!record) {
			return;
		}
		step.headMask = headMask;
		step.logp = logp;
		System.arraycopy(actionOut, 0, step.action, 0, ActionSpace.HEADS);
		step.value = value(step.obs);
		copyStep(step, pending);
		hasPending = true;
	}

	/** The network's chance of each choice of every head as it last chose ({@link #act}), for the logs. */
	public float[] lastLogits() {
		return actWs.logits;
	}

	private static void copyStep(SchoolRollout.Step from, SchoolRollout.Step to) {
		to.obs.copyFrom(from.obs);
		System.arraycopy(from.pred, 0, to.pred, 0, Predictor.OUT);
		System.arraycopy(from.action, 0, to.action, 0, ActionSpace.HEADS);
		to.headMask = from.headMask;
		to.logp = from.logp;
		to.value = from.value;
		System.arraycopy(from.teacher, 0, to.teacher, 0, ActionSpace.TOTAL_LOGITS);
		to.teacherHeads = from.teacherHeads;
		to.teacherWeight = from.teacherWeight;
		to.teacherTechnique = from.teacherTechnique;
		to.policyWeight = from.policyWeight;
		to.botTechnique = from.botTechnique;
		to.botKeys = from.botKeys;
		to.kind = from.kind;
	}

	/**
	 * Closes the pending step.
	 *
	 * @param truncated the fight was cut off here: bootstrap from {@code next}
	 */
	public void complete(float reward, boolean terminal, boolean truncated, SchoolObs next) {
		if (!hasPending) {
			return;
		}
		hasPending = false;
		boolean ended = terminal || truncated;
		float r = scaler.scale(reward, ended);
		if (truncated && !terminal) {
			r += gamma * value(next);
		}
		rollout.add(pending, r, ended);
		totalSteps++;
		lastValue = ended ? 0f : value(next);
	}

	/** The fight was broken off before the pending step was scored: the last stored step ends its fight. */
	public void interrupt() {
		int last = rollout.size - 1;
		if (hasPending && last >= 0 && !rollout.done[last]) {
			rollout.reward[last] += gamma * pending.value;
			rollout.done[last] = true;
		}
		hasPending = false;
		lastValue = 0f;
	}

	public int rolloutSize() {
		return rollout.size;
	}

	public int rolloutRoom() {
		return rollout.capacity - rollout.size;
	}

	/** One update over what was gathered; clears it. */
	public SchoolPpo.Stats update(SchoolPpo.Settings set, float predictorLr) {
		rollout.computeAdvantages(lastValue, gamma, lambda);
		rollout.labelPredictions();
		SchoolPpo.Stats s = ppo.update(rollout, rng, set);
		if (replay == null) {
			// Made on the first update only: a network that only plays (the user's fights) has no use for it.
			replay = new TeacherReplay(20_000);
			shadowReplay = new TeacherReplay(16_000, true);
			replayWs = actor.newWorkspace();
		}
		replay.addFrom(rollout);
		if (set.oldPull > 0f) {
			shadowReplay.addFrom(rollout);
		}
		if (!set.criticOnly && set.replayBatches > 0) {
			replayLine = replay.learn(actor, replayWs, set.techPulls, set.replayBatches, set.replayBatch, set.replayLr, set.maxGradNorm, rng)
					+ " of " + replay.size() + " kept";
			if (set.oldPull > 0f && set.shadowBatches > 0) {
				float[] pulls = new float[dev.swordai.core.pvp.Technique.COUNT];
				pulls[dev.swordai.core.pvp.Technique.BASE.ordinal()] = set.oldPull;
				shadowLine = shadowReplay.learn(actor, replayWs, pulls, set.shadowBatches, set.replayBatch, set.replayLr, set.maxGradNorm, rng)
						+ " of " + shadowReplay.size() + " kept";
			}
		}
		if (predictorLr > 0f) {
			Predictor.Stats p = predictor.learn(rollout, predictorLr, Math.max(1, rollout.size / 64), 64, set.maxGradNorm, rng);
			predictorLine = p.toString();
		}
		rollout.clear();
		hasPending = false;
		lastValue = 0f;
		updates++;
		return s;
	}

	public String predictorLine() {
		return predictorLine;
	}

	public String replayLine() {
		return replayLine + "; installed's examples: " + shadowLine;
	}

	public float actorLr() {
		return ppo.actorLr();
	}

	// ---- the network to compare with (paired evaluation) ------------------------------------------------

	/** Keeps the acting network as it is now as the one {@link #actBase} plays. */
	public void keepBase() {
		if (base == null) {
			base = new SchoolNet(0L);
			baseWs = base.newWorkspace();
		}
		base.params.copyWeightsFrom(actor.params);
	}

	/** Loads a saved school network as the one {@link #actBase} plays. */
	public void loadBase(Path file) throws IOException {
		SchoolLearner other = new SchoolLearner(0L, 1, gamma, lambda, 1);
		try {
			other.load(file);
			base = other.actor;
			baseWs = base.newWorkspace();
		} finally {
			other.close();
		}
	}

	public boolean hasBase() {
		return base != null;
	}

	public void actBase(SchoolRollout.Step step, int headMask, float temperature, int[] actionOut) {
		predict(step.obs, step.pred);
		base.forward(baseWs, step.obs.base.peri, step.obs.base.fovea, step.obs.base.vec, step.obs.mem, step.pred);
		base.sample(baseWs, headMask, false, temperature, baseRng, actionOut);
	}

	/** Same draws for both sides of a pair; see {@code Learner.seedSampling}. */
	public void seedSampling(long seed) {
		sampleRng.setSeed(seed);
		baseRng.setSeed(seed);
	}

	// ---- saving -------------------------------------------------------------------------------------------

	public void save(Path file) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(MAGIC);
			out.writeInt(FORMAT);
			out.writeInt(ObsSpec.VEC);
			out.writeInt(ObsSpec.PRIV);
			out.writeInt(SchoolObs.MEM);
			out.writeInt(SchoolObs.OPP_TRUTH);
			out.writeInt(ActionSpace.TOTAL_LOGITS);
			out.writeLong(totalSteps);
			out.writeLong(updates);
			actor.params.write(out);
			critic.params.write(out);
			predictor.params.write(out);
			scaler.write(out);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	public void load(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("not a technique school checkpoint: " + file);
			}
			int format = in.readInt();
			if (format != FORMAT) {
				throw new IOException("technique school checkpoint format " + format + " is not supported");
			}
			if (in.readInt() != ObsSpec.VEC || in.readInt() != ObsSpec.PRIV || in.readInt() != SchoolObs.MEM
					|| in.readInt() != SchoolObs.OPP_TRUTH || in.readInt() != ActionSpace.TOTAL_LOGITS) {
				throw new IOException("the technique school checkpoint has another input or action layout");
			}
			totalSteps = in.readLong();
			updates = in.readLong();
			actor.params.read(in);
			critic.params.read(in);
			predictor.params.read(in);
			scaler.read(in);
		}
		rollout.clear();
		hasPending = false;
	}

	/** Whether {@code file} is a technique school checkpoint. */
	public static boolean isSchool(Path file) {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			return in.readInt() == MAGIC;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public void close() {
		ppo.close();
	}
}
