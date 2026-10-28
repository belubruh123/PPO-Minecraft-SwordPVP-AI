package dev.swordai.core.school;

import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;

/**
 * The steps gathered for one update of the technique school, with generalised advantage estimation.
 * Beside what PPO needs, each step keeps what the teachers said (a target for some of the keys and how
 * hard it pulls; see {@link SchoolPpo}), whether its policy loss counts at all (data skipping), and what
 * the opponent bot did (its technique and keys), from which the {@link Predictor}'s labels are made.
 */
public final class SchoolRollout {
	public final int capacity;
	public int size;

	public final byte[][] peri;
	public final byte[][] fovea;
	public final float[][] vec;
	public final float[][] priv;
	public final float[][] mem;
	public final float[][] oppTruth;
	/** The side input the action was chosen with ({@link SchoolNet#SIDE}): the predictor's guess and the technique being done. */
	public final float[][] pred;
	/** The technique being done the tick before (the {@link Chooser}'s input), and the teacher's technique now (its label; -1 for none). */
	public final float[][] prevIntent;
	public final int[] intentLabel;
	/**
	 * The technique the chooser chose itself (drawn from its chances) where the network was told it, -1 where it was told
	 * the teacher's: the chooser learns from how the fight went after these ({@link Chooser#OUTCOME}).
	 */
	public final int[] choice;
	/** Whether the technique the network was told is the teacher's own: where not, the teacher's keys are not taught on it. */
	public final boolean[] intentAgrees;
	public final byte[][] action;
	public final int[] headMask;
	/** Heads a rule pressed (locked in {@link #headMask}) that the teacher still teaches: SchoolStages#PUNISH_SHADOW. */
	public final int[] shadowHeads;
	public final float[] logp;
	public final float[] value;
	public final float[] reward;
	public final boolean[] done;
	public final float[] advantage;
	public final float[] ret;
	/**
	 * The teachers' target over every head's choices: a technique teacher's on the heads in
	 * {@link #teacherHeads} (bits), the installed network's on the rest where {@link #teacherWeight} is
	 * above zero. How hard each pulls is the schedule's, at the update.
	 */
	public final float[][] teacher;
	public final int[] teacherHeads;
	public final float[] teacherWeight;
	/** The sprint let-go teacher's own pull on the W/S and sprint keys of the step (0: none); see SchoolPpo#teach. */
	public final float[] letGoPull;
	/** The file teacher's jump key taught at every moment, its own pull (0: none); see SchoolPpo#teach. */
	public final float[] jumpPull;
	/** The technique teacher that spoke (a {@link Technique} ordinal), or -1: for the logs. */
	public final int[] teacherTechnique;
	/** 1 when the step's policy loss counts, 0 when the step was one where nothing could be decided. */
	public final float[] policyWeight;
	/** What the bot did on the step: its technique (ordinal, -1 for no bot) and its keys ({@code TechniqueBot} bits). */
	public final int[] botTechnique;
	public final int[] botKeys;
	/** Made by {@link #labelPredictions}: the technique the bot is going into, -1 for no label; and the acts bits. */
	public final int[] predTechnique;
	public final int[] predActs;
	/** Which kind of fight the step was in ({@code Specialists} index, or -1), for the logs and the critic. */
	public final int[] kind;

	public SchoolRollout(int capacity) {
		this.capacity = capacity;
		peri = new byte[capacity][ObsSpec.PERI_SIZE];
		fovea = new byte[capacity][ObsSpec.FOVEA_SIZE];
		vec = new float[capacity][ObsSpec.VEC];
		priv = new float[capacity][ObsSpec.PRIV];
		mem = new float[capacity][SchoolObs.MEM];
		oppTruth = new float[capacity][SchoolObs.OPP_TRUTH];
		pred = new float[capacity][SchoolNet.SIDE];
		prevIntent = new float[capacity][SchoolNet.INTENT];
		intentLabel = new int[capacity];
		choice = new int[capacity];
		intentAgrees = new boolean[capacity];
		action = new byte[capacity][ActionSpace.HEADS];
		headMask = new int[capacity];
		shadowHeads = new int[capacity];
		logp = new float[capacity];
		value = new float[capacity];
		reward = new float[capacity];
		done = new boolean[capacity];
		advantage = new float[capacity];
		ret = new float[capacity];
		teacher = new float[capacity][ActionSpace.TOTAL_LOGITS];
		teacherHeads = new int[capacity];
		teacherWeight = new float[capacity];
		letGoPull = new float[capacity];
		jumpPull = new float[capacity];
		teacherTechnique = new int[capacity];
		policyWeight = new float[capacity];
		botTechnique = new int[capacity];
		botKeys = new int[capacity];
		predTechnique = new int[capacity];
		predActs = new int[capacity];
		kind = new int[capacity];
	}

	public void clear() {
		size = 0;
	}

	/** Stores one step; {@code s} is the step's own record, filled by the learner. */
	public void add(Step s, float rew, boolean isDone) {
		int i = size++;
		System.arraycopy(s.obs.base.peri, 0, peri[i], 0, ObsSpec.PERI_SIZE);
		System.arraycopy(s.obs.base.fovea, 0, fovea[i], 0, ObsSpec.FOVEA_SIZE);
		System.arraycopy(s.obs.base.vec, 0, vec[i], 0, ObsSpec.VEC);
		System.arraycopy(s.obs.base.priv, 0, priv[i], 0, ObsSpec.PRIV);
		System.arraycopy(s.obs.mem, 0, mem[i], 0, SchoolObs.MEM);
		System.arraycopy(s.obs.oppTruth, 0, oppTruth[i], 0, SchoolObs.OPP_TRUTH);
		System.arraycopy(s.pred, 0, pred[i], 0, SchoolNet.SIDE);
		System.arraycopy(s.prevIntent, 0, prevIntent[i], 0, SchoolNet.INTENT);
		intentLabel[i] = s.intentLabel;
		choice[i] = s.choice;
		intentAgrees[i] = s.intentLabel < 0 || Chooser.techniqueOf(s.pred, Predictor.OUT) == s.intentLabel;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			action[i][h] = (byte) s.action[h];
		}
		headMask[i] = s.headMask;
		shadowHeads[i] = s.shadowHeads & ~s.headMask;
		logp[i] = s.logp;
		value[i] = s.value;
		reward[i] = rew;
		done[i] = isDone;
		System.arraycopy(s.teacher, 0, teacher[i], 0, ActionSpace.TOTAL_LOGITS);
		teacherHeads[i] = s.teacherHeads;
		teacherWeight[i] = s.teacherWeight;
		letGoPull[i] = s.letGoPull;
		jumpPull[i] = s.jumpPull;
		teacherTechnique[i] = s.teacherTechnique;
		policyWeight[i] = s.policyWeight;
		botTechnique[i] = s.botTechnique;
		botKeys[i] = s.botKeys;
		kind[i] = s.kind;
	}

	/** Everything about one step that is known when it is chosen. */
	public static final class Step {
		public final SchoolObs obs = new SchoolObs();
		public final float[] pred = new float[SchoolNet.SIDE];
		public final float[] prevIntent = new float[SchoolNet.INTENT];
		public int intentLabel = -1;
		public int choice = -1;
		public final int[] action = new int[ActionSpace.HEADS];
		public int headMask;
		public int shadowHeads;
		public float logp;
		public float value;
		public final float[] teacher = new float[ActionSpace.TOTAL_LOGITS];
		public int teacherHeads;
		public float teacherWeight;
		public float letGoPull;
		public float jumpPull;
		public int teacherTechnique = -1;
		public float policyWeight = 1f;
		public int botTechnique = -1;
		public int botKeys;
		public int kind = -1;
	}

	public void computeAdvantages(float lastValue, float gamma, float lambda) {
		float gae = 0f;
		for (int t = size - 1; t >= 0; t--) {
			float nextValue;
			if (done[t]) {
				nextValue = 0f;
				gae = 0f;
			} else {
				nextValue = t == size - 1 ? lastValue : value[t + 1];
			}
			float delta = reward[t] + gamma * nextValue - value[t];
			gae = delta + gamma * lambda * gae;
			advantage[t] = gae;
			ret[t] = gae + value[t];
		}
	}

	/**
	 * The predictor's labels, from what the bot did after each step: the first technique other than the
	 * ordinary fight it went into within {@link Predictor#TECHNIQUE_AHEAD} ticks (else the ordinary fight),
	 * and whether within {@link Predictor#AHEAD} ticks it jumped, held S, swung. Never across the end of a fight.
	 */
	public void labelPredictions() {
		int base = Technique.BASE.ordinal();
		for (int i = 0; i < size; i++) {
			if (botTechnique[i] < 0) {
				predTechnique[i] = -1;
				predActs[i] = 0;
				continue;
			}
			int label = base;
			int acts = 0;
			for (int j = done[i] ? size : i + 1; j < size && j <= i + Predictor.TECHNIQUE_AHEAD; j++) {
				if (botTechnique[j] < 0) {
					break;
				}
				if (label == base && botTechnique[j] != base) {
					label = botTechnique[j];
				}
				if (j <= i + Predictor.AHEAD) {
					int k = botKeys[j];
					acts |= (k & 32) != 0 ? 1 : 0;
					acts |= (k & 2) != 0 ? 2 : 0;
					acts |= (k & 64) != 0 ? 4 : 0;
				}
				if (done[j]) {
					break;
				}
			}
			predTechnique[i] = label;
			predActs[i] = acts;
		}
	}
}
