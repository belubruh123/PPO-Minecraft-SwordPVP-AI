package dev.swordai.core.school;

import dev.swordai.core.pvp.Technique;
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
 * {@link Chooser} that decides the technique the acting network is told to do, the buffer and the update; and the
 * installed network as a teacher ({@link #loadOldTeacher}).
 *
 * <p>Threading: {@link #act}, {@link #complete} and {@link #value} on the game thread; {@link #update}
 * on a trainer thread while the fight waits.
 */
public final class SchoolLearner implements AutoCloseable {
	private static final int MAGIC = 0x53575343; // "SWSC"
	/** 2 has the chooser after the reward scaler; 1 (before 24 Oct 10:00) has none, and loads with a new one. */
	private static final int FORMAT = 2;
	private static final int FORMAT_NO_CHOOSER = 1;

	public final SchoolNet actor;
	public final SchoolCritic critic;
	public final Predictor predictor;
	public final Chooser chooser;
	private final SchoolPpo ppo;
	private final SchoolRollout rollout;
	private final RewardScaler scaler;
	private final Random rng;
	private final Random sampleRng;
	private final SchoolNet.Workspace actWs;
	private final SchoolCritic.Workspace valueWs;
	private final Predictor.Workspace predWs;
	private final Chooser.Workspace chooseWs;
	/**
	 * In training, the share of fights in which the acting network is told the teacher's own technique; in the rest,
	 * and in every fight that is not training, the chooser's.
	 */
	public float teacherForcing = 0.85f;
	private final Random intentRng;
	private final Intent mine = new Intent();
	private final Intent baseIntent = new Intent();
	private volatile String chooserLine = "not learning yet";
	private final float gamma;
	private final float lambda;
	/** The installed network, a teacher; null without one. */
	private ActorNet oldTeacher;
	private ActorNet.Workspace oldWs;
	/** A network to compare with in a paired evaluation, or to play the opponent; null without one. */
	private SchoolNet base;
	private SchoolNet.Workspace baseWs;
	private Chooser baseChooser;
	private Chooser.Workspace baseChooseWs;
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
	/** Its own best steps (TeacherReplay#addSelf), learned from again after every update with {@link #SELF_PULL}. */
	private TeacherReplay selfReplay;
	static final int SELF_CAPACITY = 4_000;
	static final int SELF_BATCHES = 12;
	/**
	 * Self-imitation (Oh et al. 2018): its own steps that went clearly better than expected (TeacherReplay#addSelf) learned from
	 * again after every update, so a win it found (a crit that landed, an exchange won) is learned from a few times, not once.
	 * Run 15 (EXPERIMENTS.md), with the copying fading: what it learns comes from its own best fights more than from the teacher.
	 */
	static final float SELF_PULL = 0f; // 0 from run 2 to run 14 (0.5 from 09:41 to run 2); 0.3 in runs 15-16b; 0 in run 17
	private volatile String selfLine = "none yet";
	private SchoolNet.Workspace replayWs;
	private volatile String shadowLine = "no examples yet";
	private volatile String replayLine = "no examples yet";

	public SchoolLearner(long seed, int capacity, float gamma, float lambda, int threads) {
		this.actor = new SchoolNet(seed);
		this.critic = new SchoolCritic(seed + 1);
		this.predictor = new Predictor(seed + 2);
		this.chooser = new Chooser(seed + 6);
		this.chooseWs = chooser.newWorkspace();
		this.intentRng = new Random(seed + 7);
		mine.reset();
		baseIntent.reset();
		// Told the teacher's techniques until the first fight draws.
		mine.forced = true;
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
		act(step, headMask, 0, record, temperature, actionOut);
	}

	/** The same, with the heads in {@code presetMask} (locked in {@code headMask}) set already in {@code actionOut}: see SchoolNet#sample. */
	public void act(SchoolRollout.Step step, int headMask, int presetMask, boolean record, float temperature, int[] actionOut) {
		predict(step.obs, step.pred);
		intend(mine, chooser, chooseWs, step, record);
		actor.forward(actWs, step.obs.base.peri, step.obs.base.fovea, step.obs.base.vec, step.obs.mem, step.pred);
		float logp = actor.sample(actWs, headMask, presetMask & ~headMask, false, record ? 1f : temperature, record ? null : playTemperatures,
				sampleRng, actionOut);
		if (!record) {
			return;
		}
		if (anchored && base != null && step.teacherWeight > 0f) {
			anchorTargets(step, actionOut);
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
		System.arraycopy(from.pred, 0, to.pred, 0, SchoolNet.SIDE);
		System.arraycopy(from.prevIntent, 0, to.prevIntent, 0, SchoolNet.INTENT);
		to.intentLabel = from.intentLabel;
		to.choice = from.choice;
		System.arraycopy(from.action, 0, to.action, 0, ActionSpace.HEADS);
		to.headMask = from.headMask;
		to.logp = from.logp;
		to.value = from.value;
		System.arraycopy(from.teacher, 0, to.teacher, 0, ActionSpace.TOTAL_LOGITS);
		to.teacherHeads = from.teacherHeads;
		to.teacherWeight = from.teacherWeight;
		to.letGoPull = from.letGoPull;
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
			replay.stratified = true;
			shadowReplay = new TeacherReplay(16_000, true);
			selfReplay = new TeacherReplay(SELF_CAPACITY);
			replayWs = actor.newWorkspace();
		}
		replay.addFrom(rollout);
		selfReplay.addSelf(rollout);
		if (set.oldPull > 0f) {
			shadowReplay.installedBody = set.installedBody;
			shadowReplay.addFrom(rollout);
		}
		if (!set.criticOnly && set.replayBatches > 0) {
			replayLine = replay.learn(actor, replayWs, set.techPulls, set.replayBatches, set.replayBatch, set.replayLr, set.maxGradNorm, rng)
					+ " of " + replay.size() + " kept";
			if (set.oldPull > 0f && set.shadowBatches > 0) {
				float[] pulls = new float[dev.swordai.core.pvp.Technique.COUNT];
				pulls[dev.swordai.core.pvp.Technique.BASE.ordinal()] = set.oldBodyPull >= 0f ? set.oldBodyPull : set.oldPull;
				shadowLine = shadowReplay.learn(actor, replayWs, pulls, set.shadowBatches, set.replayBatch, set.replayLr, set.maxGradNorm, rng)
						+ " of " + shadowReplay.size() + " kept";
			}
		}
		if (!set.criticOnly && SELF_PULL > 0f) {
			float[] pulls = new float[dev.swordai.core.pvp.Technique.COUNT];
			pulls[dev.swordai.core.pvp.Technique.BASE.ordinal()] = SELF_PULL;
			selfLine = selfReplay.learn(actor, replayWs, pulls, SELF_BATCHES, 64, set.replayLr, set.maxGradNorm, rng)
					+ " of " + selfReplay.size() + " kept";
		}
		if (predictorLr > 0f) {
			Predictor.Stats p = predictor.learn(rollout, predictorLr, Math.max(1, rollout.size / 64), 64, set.maxGradNorm, rng);
			predictorLine = p.toString();
			chooserLine = chooser.learn(rollout, predictorLr, Math.max(1, rollout.size / 32), 64, set.maxGradNorm, rng).toString();
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

	public String chooserLine() {
		return chooserLine;
	}

	// ---- the technique it is told to do -------------------------------------------------------------------

	/** What one side has been told: the technique, for how many ticks, as the vector it was told it with. */
	private static final class Intent {
		int last = Technique.BASE.ordinal();
		int ticks;
		int chosen = Technique.BASE.ordinal();
		boolean forced;
		final float[] before = new float[SchoolNet.INTENT];

		void reset() {
			last = Technique.BASE.ordinal();
			ticks = 0;
			chosen = last;
			java.util.Arrays.fill(before, 0f);
			before[last] = 1f;
		}
	}

	/**
	 * A new fight: nothing being done. In training, whether this fight's network is told the teacher's techniques
	 * ({@link #teacherForcing}) or the chooser's.
	 */
	public void resetIntent() {
		mine.reset();
		baseIntent.reset();
		mine.forced = intentRng.nextFloat() < teacherForcing;
	}

	/** The technique the chooser chose on the last {@link #act}, and the one the acting network was told. */
	public Technique chosen() {
		return Technique.values()[mine.chosen];
	}

	public Technique told() {
		return Technique.values()[mine.last];
	}

	public boolean toldTheTeachers() {
		return mine.forced;
	}

	/**
	 * Writes the technique being done into the side input of {@code step}: the chooser chooses on every tick; in a
	 * training fight told the teacher's techniques, the network is told the teacher's (and the chooser learns from it).
	 */
	private void intend(Intent st, Chooser ch, Chooser.Workspace cw, SchoolRollout.Step step, boolean record) {
		System.arraycopy(st.before, 0, step.prevIntent, 0, SchoolNet.INTENT);
		st.chosen = ch.forward(cw, step.obs.base.vec, step.obs.mem, step.pred, st.before);
		int label = step.teacherTechnique >= 0 ? step.teacherTechnique : Technique.BASE.ordinal();
		step.intentLabel = record ? label : -1;
		int intent = record && st.forced ? label : st.chosen;
		// Its own choice in training is drawn from its chances, so that it tries other techniques and learns which won.
		if (record && !st.forced && Chooser.OUTCOME) {
			intent = ch.draw(cw, intentRng.nextFloat());
		}
		step.choice = record && !st.forced ? intent : -1;
		st.ticks = intent == st.last ? st.ticks + 1 : 0;
		st.last = intent;
		int off = Predictor.OUT;
		java.util.Arrays.fill(step.pred, off, off + SchoolNet.INTENT, 0f);
		step.pred[off + intent] = 1f;
		step.pred[off + SchoolNet.INTENT_TICKS] = Math.min(1f, st.ticks / SchoolNet.INTENT_LONG);
		System.arraycopy(step.pred, off, st.before, 0, SchoolNet.INTENT);
	}

	public String replayLine() {
		return replayLine + "; installed's examples: " + shadowLine + "; its own best moments again: " + selfLine;
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
		if (baseChooser == null) {
			baseChooser = new Chooser(0L);
			baseChooseWs = baseChooser.newWorkspace();
		}
		baseChooser.params.copyWeightsFrom(chooser.params);
	}

	/** Loads a saved school network as the one {@link #actBase} plays. */
	public void loadBase(Path file) throws IOException {
		SchoolLearner other = new SchoolLearner(0L, 1, gamma, lambda, 1);
		try {
			other.load(file);
			base = other.actor;
			baseWs = base.newWorkspace();
			baseChooser = other.chooser;
			baseChooseWs = baseChooser.newWorkspace();
		} finally {
			other.close();
		}
	}

	public boolean hasBase() {
		return base != null;
	}

	/**
	 * Learning without forgetting (Li and Hoiem 2016): with a base loaded ({@link #loadBase}) and this on, the copying target
	 * of every step the installed model teaches is the base's own chances on the same inputs, on every key no technique
	 * teacher holds, instead of the installed model's one key. The base is the best fighter so far, frozen: what the
	 * techniques are learned on top of, kept. The user, 25 Oct 14:43: "When it started to learn skill it become dumber and
	 * loses against teacher overtime fix it" (run 7: -22.5 against the installed model, from +5.6).
	 */
	public void anchorToBase(boolean on) {
		anchored = on;
	}

	private boolean anchored;
	private final float[] anchorProbs = new float[ActionSpace.TOTAL_LOGITS];
	private final byte[] anchorAction = new byte[ActionSpace.HEADS];

	private void anchorTargets(SchoolRollout.Step step, int[] action) {
		base.forward(baseWs, step.obs.base.peri, step.obs.base.fovea, step.obs.base.vec, step.obs.mem, step.pred);
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			anchorAction[h] = (byte) action[h];
		}
		// The jump and the swing as the base would choose them after the keys really pressed, as the update conditions them.
		base.condition(baseWs, anchorAction);
		Policy.allProbs(baseWs.logits, anchorProbs);
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if ((step.teacherHeads & (1 << h)) == 0) {
				System.arraycopy(anchorProbs, ActionSpace.OFFSETS[h], step.teacher, ActionSpace.OFFSETS[h], ActionSpace.SIZES[h]);
			}
		}
	}

	public void actBase(SchoolRollout.Step step, int headMask, float temperature, int[] actionOut) {
		predict(step.obs, step.pred);
		if (baseChooser != null) {
			intend(baseIntent, baseChooser, baseChooseWs, step, false);
		} else {
			intend(baseIntent, chooser, chooseWs, step, false);
		}
		base.forward(baseWs, step.obs.base.peri, step.obs.base.fovea, step.obs.base.vec, step.obs.mem, step.pred);
		base.sample(baseWs, headMask, false, temperature, baseRng, actionOut);
	}

	/** Same draws for both sides of a pair; see {@code Learner.seedSampling}. */
	/**
	 * A temperature for each head (ActionSpace order; 0 = the most likely key) for the steps it does not learn from (a check, a
	 * fight), or null for the one temperature passed to {@link #act}. The research of 26 Oct (Jang et al. 2025): PPO agents
	 * often play better a little sharper than they were trained; the camera most of all.
	 */
	public void playTemperatures(float[] perHead) {
		playTemperatures = perHead == null ? null : perHead.clone();
	}

	private float[] playTemperatures;

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
			chooser.params.write(out);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	public void load(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("not a technique school checkpoint: " + file);
			}
			int format = in.readInt();
			if (format != FORMAT && format != FORMAT_NO_CHOOSER) {
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
			if (format == FORMAT) {
				chooser.params.read(in);
			}
		}
		rollout.clear();
		hasPending = false;
	}

	/**
	 * Saves to {@code out} the blend of the school networks in {@code files} (see Soup): every weight of the actor, the critic,
	 * the predictor and the chooser is the sum of theirs times {@code coefs}; the Adam moments, the reward scaler and the step
	 * counts are the first network's.
	 */
	public static void blend(Path out, Path[] files, float[] coefs) throws IOException {
		SchoolLearner first = new SchoolLearner(0L, 1, 0.99f, 0.95f, 1);
		try {
			first.load(files[0]);
			dev.swordai.core.nn.ParamSet[] sets = first.paramSets();
			float[][][] sum = new float[sets.length][][];
			for (int s = 0; s < sets.length; s++) {
				sum[s] = new float[sets[s].params().size()][];
				for (int i = 0; i < sum[s].length; i++) {
					sum[s][i] = new float[sets[s].params().get(i).w.length];
				}
			}
			for (int f = 0; f < files.length; f++) {
				SchoolLearner other = f == 0 ? first : new SchoolLearner(0L, 1, 0.99f, 0.95f, 1);
				try {
					if (f > 0) {
						other.load(files[f]);
					}
					dev.swordai.core.nn.ParamSet[] os = other.paramSets();
					for (int s = 0; s < sets.length; s++) {
						if (os[s].params().size() != sum[s].length) {
							throw new IOException(files[f] + " has another network shape");
						}
						for (int i = 0; i < sum[s].length; i++) {
							float[] w = os[s].params().get(i).w;
							if (w.length != sum[s][i].length) {
								throw new IOException(files[f] + ": tensor " + os[s].params().get(i).name + " has another size");
							}
							for (int k = 0; k < w.length; k++) {
								sum[s][i][k] += coefs[f] * w[k];
							}
						}
					}
				} finally {
					if (f > 0) {
						other.close();
					}
				}
			}
			for (int s = 0; s < sets.length; s++) {
				for (int i = 0; i < sum[s].length; i++) {
					System.arraycopy(sum[s][i], 0, sets[s].params().get(i).w, 0, sum[s][i].length);
				}
			}
			first.save(out);
		} finally {
			first.close();
		}
	}

	private dev.swordai.core.nn.ParamSet[] paramSets() {
		return new dev.swordai.core.nn.ParamSet[] {actor.params, critic.params, predictor.params, chooser.params};
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
