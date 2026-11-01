package dev.swordai.core.school;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PPO for the technique school, with kickstarting (Schmitt et al. 2018): beside the clipped surrogate
 * and the entropy bonus, each step's keys are pulled towards what a teacher said there, by the
 * cross-entropy between the teacher's choice and the network's, weighted by a pull that the schedule
 * lowers to nothing over a stage. The teacher never presses a key: the network acts, the pull only
 * shapes its chances, and once it is gone the reward alone decides when a technique is worth it.
 *
 * <p>Two teachers: a technique teacher on the keys its technique uses (pull {@code techPull}), and the
 * installed network on every other key (pull {@code oldPull}), so the widened network does not have to
 * learn again what the installed one does well.
 *
 * <p>Data skipping (Oh et al. 2019): a step whose {@code policyWeight} is zero does not count in the
 * policy loss; the critic still learns from it.
 */
public final class SchoolPpo implements AutoCloseable {
	/** Settings of one update. */
	public static final class Settings {
		public float clip = 0.2f;
		public int epochs = 4;
		// Large minibatches and a small step: a teacher's pull points the same way on every step, and Adam moves every
		// weight by the full step each time it does (the first school run, 23 Oct: 64 and 3e-4 changed the policy by a
		// KL of 1.4 in one epoch).
		public int minibatch = 256;
		public float actorLr = 1e-4f;
		public float criticLr = 5e-4f;
		public float maxGradNorm = 0.5f;
		// The teachers' pulls move the policy more than PPO alone does; a trust region of 0.01 kept the step at its least.
		public float klTarget = 0.02f;
		public float klStop = 0.05f;
		public float minActorLr = 2e-5f;
		public float maxActorLr = 3e-4f;
		public boolean adaptiveLr = true;
		// Less on the keys that are pressed or not (sprint, jump, swing): a technique needs them pressed on one tick and not
		// on the others, and a bonus for being unsure of them keeps a jump on every twentieth tick.
		public float[] entropy = {0.005f, 0.005f, 0.01f, 0.01f, 0.003f, 0.003f, 0.003f};
		/** The pulls of the teachers for this update: each technique's teacher's ({@code Technique} ordinal), and the installed network's. */
		public float[] techPulls = new float[dev.swordai.core.pvp.Technique.COUNT];
		public float oldPull;
		/** The copying's pull on the body's keys (the camera keeps oldPull), see SchoolStages#bodyFade; below 0: oldPull. */
		public float oldBodyPull = -1f;
		/** The installed model teaches every key where no technique teacher speaks (the overlay), not the camera only. */
		public boolean installedBody;
		/**
		 * The pull on the heads the installed model's rules press (SchoolRollout#ruledHeads), whether a rule locked them in the
		 * step or not: the network learns what the rules do wherever they speak, which the lock kept from it until run 30.
		 */
		public float ruledPull;
		/** Minibatches of the rules' keys kept (TeacherReplay#ruledOnly, drawn rule first), after the update. */
		public int ruledBatches = 12;
		/** Learn the critic only. */
		public boolean criticOnly;
		/** After the update, minibatches of the technique teachers' kept examples ({@link TeacherReplay}), and their step. */
		public int replayBatches = 24;
		/** Minibatches of the installed teacher's kept examples, while its pull lasts. */
		public int shadowBatches = 12;
		public int replayBatch = 256;
		public float replayLr = 5e-5f;
	}

	/** What one update did. */
	public static final class Stats {
		public int samples;
		public int epochs;
		public float policyLoss;
		public float valueLoss;
		public float kl;
		public float clipFraction;
		public float explained;
		public float actorLr;
		public final float[] entropy = new float[ActionSpace.HEADS];
		/** Cross-entropy to the technique teachers (where they spoke) and to the installed network; and how often the network's likeliest key was the technique teacher's. */
		public float techCe;
		public float oldCe;
		/** The same over the body's keys alone (not the camera): how far the body has gone from what it copies. */
		public float oldBodyCe;
		/** The keys the rules press (run 30): cross-entropy to the rule's key, how often the likeliest key was the rule's, and the head-ticks. */
		public float ruledCe;
		public float ruledAgree;
		public int ruledSteps;
		/** The same per head (ActionSpace order): which of the rules' keys the network still does not read. */
		public final float[] ruledCeHead = new float[ActionSpace.HEADS];
		public final float[] ruledAgreeHead = new float[ActionSpace.HEADS];
		public final int[] ruledNHead = new int[ActionSpace.HEADS];
		/** The same per rule of the installed model ({@link #RULE_NAMES}; a head-tick counts for every rule that had keys on it). */
		public final float[] ruleCe = new float[RULE_NAMES.length];
		public final float[] ruleAgree = new float[RULE_NAMES.length];
		public final int[] ruleN = new int[RULE_NAMES.length];

		/** Which rules the network reads: cross-entropy, agreement, head-ticks, for the rules that had some. */
		public String ruleLine() {
			StringBuilder b = new StringBuilder();
			for (int k = 0; k < RULE_NAMES.length; k++) {
				if (ruleN[k] > 0) {
					b.append(b.length() == 0 ? "" : "; ").append(RULE_NAMES[k]).append(String.format(java.util.Locale.ROOT, " %.2f %.0f%% on %d", ruleCe[k], 100f * ruleAgree[k], ruleN[k]));
				}
			}
			return b.toString();
		}

		/** The rules' keys head by head: cross-entropy, agreement, head-ticks, for the heads that had some. */
		public String ruledHeadsLine() {
			StringBuilder b = new StringBuilder();
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if (ruledNHead[h] > 0) {
					b.append(b.length() == 0 ? "" : "; ").append(ActionSpace.NAMES[h]).append(String.format(java.util.Locale.ROOT, " %.2f %.0f%% on %d", ruledCeHead[h], 100f * ruledAgreeHead[h], ruledNHead[h]));
				}
			}
			return b.toString();
		}
		public float techAgree;
		public int techSteps;
		/** The punish crit's swing under the rules (SchoolStages#PUNISH_SHADOW): how often the network's likelier choice was the teacher's, where it swung and where it held. */
		public float shadowSwingAgree;
		public int shadowSwings;
		public float shadowHoldAgree;
		public int shadowHolds;
		public long millis;

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT,
					"%d steps, %d epochs, policy %.4f, value %.4f, kl %.4f, clipped %.2f, explained %.2f, lr %.1e, entropy W/S %.2f jump %.2f swing %.2f, "
							+ "teacher: technique ce %.3f agree %.0f%% on %d, installed ce %.3f (body %.3f), "
							+ "punish crit swing under the rules: swung %.0f%% of %d, held %.0f%% of %d, rules' keys: ce %.3f agree %.0f%% on %d (by head: %s) (by rule: %s), %d ms",
					samples, epochs, policyLoss, valueLoss, kl, clipFraction, explained, actorLr, entropy[ActionSpace.FORWARD],
					entropy[ActionSpace.JUMP], entropy[ActionSpace.ATTACK], techCe, 100f * techAgree, techSteps, oldCe, oldBodyCe,
					100f * shadowSwingAgree, shadowSwings, 100f * shadowHoldAgree, shadowHolds, ruledCe, 100f * ruledAgree, ruledSteps, ruledHeadsLine(), ruleLine(), millis);
		}
	}

	/** The installed model's rules, by the bit of {@code Fighter#rules}. */
	public static final String[] RULE_NAMES = {"answer", "keep", "meet", "reset", "backOut", "stepBack", "jumpIn", "hitAim", "lost", "straight", "dodge", "hold", "holdCrit", "punish"};

	private final SchoolNet actor;
	private final SchoolCritic critic;
	private final Worker[] workers;
	private final ExecutorService pool;
	private float actorLr = Float.NaN;

	public SchoolPpo(SchoolNet actor, SchoolCritic critic, int threads) {
		this.actor = actor;
		this.critic = critic;
		this.workers = new Worker[Math.max(1, threads)];
		for (int i = 0; i < workers.length; i++) {
			workers[i] = new Worker();
		}
		if (workers.length > 1) {
			AtomicInteger n = new AtomicInteger();
			pool = Executors.newFixedThreadPool(workers.length, r -> {
				Thread t = new Thread(r, "swordai-school-" + n.incrementAndGet());
				t.setDaemon(true);
				// The game's render and server threads must always win the CPU.
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			});
		} else {
			pool = null;
		}
	}

	public float actorLr() {
		return actorLr;
	}

	private final class Worker {
		final SchoolNet.Workspace aw = actor.newWorkspace();
		final SchoolCritic.Workspace cw = critic.newWorkspace();
		final float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		final float[] ent = new float[ActionSpace.HEADS];
		double policyLoss;
		double valueLoss;
		double kl;
		int clipped;
		final double[] entSum = new double[ActionSpace.HEADS];
		int entCount;
		double techCe;
		double oldCe;
		double oldBodyCe;
		int techAgree;
		int techSteps;
		int shadowSwingAgree;
		int shadowSwings;
		int shadowHoldAgree;
		int shadowHolds;
		int oldSteps;
		int oldBodySteps;
		double ruledCe;
		int ruledAgree;
		int ruledSteps;
		final double[] ruledCeHead = new double[ActionSpace.HEADS];
		final int[] ruledAgreeHead = new int[ActionSpace.HEADS];
		final int[] ruledNHead = new int[ActionSpace.HEADS];
		final double[] ruleCe = new double[RULE_NAMES.length];
		final int[] ruleAgree = new int[RULE_NAMES.length];
		final int[] ruleN = new int[RULE_NAMES.length];

		void reset() {
			policyLoss = 0;
			valueLoss = 0;
			kl = 0;
			clipped = 0;
			java.util.Arrays.fill(entSum, 0);
			entCount = 0;
			techCe = 0;
			oldCe = 0;
			oldBodyCe = 0;
			techAgree = 0;
			techSteps = 0;
			shadowSwingAgree = 0;
			shadowSwings = 0;
			shadowHoldAgree = 0;
			shadowHolds = 0;
			oldSteps = 0;
			oldBodySteps = 0;
			ruledCe = 0;
			ruledAgree = 0;
			ruledSteps = 0;
			java.util.Arrays.fill(ruledCeHead, 0);
			java.util.Arrays.fill(ruledAgreeHead, 0);
			java.util.Arrays.fill(ruledNHead, 0);
			java.util.Arrays.fill(ruleCe, 0);
			java.util.Arrays.fill(ruleAgree, 0);
			java.util.Arrays.fill(ruleN, 0);
		}

		void run(SchoolRollout r, int[] order, int from, int to, float advMean, float advStd, Settings set) {
			ParamSet.zero(aw.grads);
			ParamSet.zero(cw.grads);
			for (int n = from; n < to; n++) {
				int i = order[n];
				if (!set.criticOnly) {
					actor.forward(aw, r.peri[i], r.fovea[i], r.vec[i], r.mem[i], r.pred[i]);
					actor.condition(aw, r.action[i]);
					int mask = r.headMask[i];
					float logp = Policy.evaluate(aw.logits, mask, r.action[i], probs, ent);
					float ratio = (float) Math.exp(logp - r.logp[i]);
					float adv = (r.advantage[i] - advMean) / advStd;
					float w = r.policyWeight[i];
					float surr1 = ratio * adv;
					float surr2 = Math.max(1f - set.clip, Math.min(1f + set.clip, ratio)) * adv;
					boolean inside = ratio >= 1f - set.clip && ratio <= 1f + set.clip;
					float dLogp = (surr1 <= surr2 || inside) ? -adv * ratio * w : 0f;
					Policy.gradient(mask, r.action[i], probs, ent, dLogp, w > 0f ? set.entropy : ZERO, aw.dLogits);
					teach(r, i, mask, set, adv);
					actor.backward(aw, r.peri[i], r.fovea[i], r.vec[i], r.mem[i]);
					policyLoss -= Math.min(surr1, surr2) * w;
					kl += r.logp[i] - logp;
					if (!inside) {
						clipped++;
					}
					for (int h = 0; h < ActionSpace.HEADS; h++) {
						entSum[h] += ent[h];
					}
					entCount++;
				}
				float v = critic.forward(cw, r.vec[i], r.priv[i], r.mem[i], r.oppTruth[i]);
				float dv = v - r.ret[i];
				valueLoss += 0.5 * dv * dv;
				critic.backward(cw, dv);
			}
		}

		/** The teachers' pull: weight * (p - q) on each head they hold for. */
		private final float[] aimed = new float[ActionSpace.TOTAL_LOGITS];

		private void teach(SchoolRollout r, int i, int mask, Settings set, float adv) {
			float[] stored = r.teacher[i];
			float[] q = aimed;
			int tech = r.teacherHeads[i];
			boolean old = r.teacherWeight[i] > 0f;
			// The keys the rules press (locked in the step, or open in a fight played without them): the rule's key is the target,
			// unless a technique teacher speaks on the head.
			int ruledBits = set.ruledPull > 0f ? r.ruledHeads[i] & ~tech : 0;
			if (tech == 0 && !old && ruledBits == 0) {
				return;
			}
			int shadow = r.shadowHeads[i];
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				// A head a rule pressed is taught only where the teacher's lesson goes on under the rule (SchoolStages#PUNISH_SHADOW).
				boolean shadowed = (shadow & (1 << h)) != 0;
				boolean ruledHere = (ruledBits & (1 << h)) != 0;
				if (!ActionSpace.unlocked(mask, h) && !shadowed && !ruledHere) {
					continue;
				}
				boolean byTech = (tech & (1 << h)) != 0;
				int which = r.teacherTechnique[i];
				// The installed model teaches the camera (yaw and pitch); in the overlay every key between the techniques too.
				boolean camera = h == ActionSpace.YAW || h == ActionSpace.PITCH;
				// Between techniques (no teacher, or the ordinary fight's: the basics teacher holds the swing there) every key
				// the basics teacher does not hold.
				// (The jump taught at every moment, SchoolStages#JUMP_EVERYWHERE, leaves the rest of the step as it was.)
				int techOther = r.jumpPull[i] > 0f ? tech & ~(1 << ActionSpace.JUMP) : tech;
				boolean between = techOther == 0 || which == dev.swordai.core.pvp.Technique.BASE.ordinal();
				boolean installedHere = old && (camera || set.installedBody && between && !byTech);
				float pull = byTech ? (which >= 0 ? set.techPulls[which] : 0f)
						: installedHere ? (camera || set.oldBodyPull < 0f ? set.oldPull : set.oldBodyPull) : 0f;
				// A technique's keys (not the camera, not the ordinary fight's) by how the fight went after them: see TEACH_AWR_MOST.
				if (byTech && !camera && which >= 0 && which != dev.swordai.core.pvp.Technique.BASE.ordinal()) {
					pull *= (float) Math.min(TEACH_AWR_MOST, Math.exp(adv / TEACH_AWR_BETA));
				}
				// The sprint let-go teacher's W/S and sprint, at its own pull and by the outcome, whatever the step's label.
				if (byTech && r.letGoPull[i] > 0f && (h == ActionSpace.FORWARD || h == ActionSpace.SPRINT)) {
					pull = r.letGoPull[i] * (float) Math.min(TEACH_AWR_MOST, Math.exp(adv / TEACH_AWR_BETA));
				}
				// The file teacher's jump key at every moment, at its own pull and by the outcome (SchoolStages#JUMP_EVERYWHERE).
				boolean everyJump = byTech && h == ActionSpace.JUMP && r.jumpPull[i] > 0f;
				if (everyJump) {
					pull = r.jumpPull[i] * (float) Math.min(TEACH_AWR_MOST, Math.exp(adv / TEACH_AWR_BETA));
				}
				// Learned from the fight (SchoolStages#FROM_THE_FIGHT): a technique's keys only where the moment went better than expected.
				boolean b1Swing = SchoolStages.B1_SWING_ALWAYS && h == ActionSpace.ATTACK && which == dev.swordai.core.pvp.Technique.B1.ordinal();
				if (SchoolStages.FROM_THE_FIGHT && byTech && !camera && adv <= 0f && !b1Swing && !everyJump
						&& (which != dev.swordai.core.pvp.Technique.BASE.ordinal() || r.letGoPull[i] > 0f)) {
					pull = 0f;
				}
				if (ruledHere) {
					// Not weighed by presses (Teacher.pressWeight) as a technique teacher's keys are: the first updates of run 30b brought the
					// body keys' ce down fast (jump 0.57 to 0.26 in 6 updates), and a heavier positive would spread the jump (runs 22, 24).
					pull = set.ruledPull;
				} else if (!byTech && !installedHere || shadowed && !byTech) {
					continue;
				}
				// Told another technique than the teacher's (the chooser's, in training a step in a few): only the camera is taught.
				if (!camera && !r.intentAgrees[i]) {
					continue;
				}
				int off = ActionSpace.OFFSETS[h];
				int size = ActionSpace.SIZES[h];
				// A key gated under the told technique is taught into its option layer alone; a gated key of another technique
				// than the told one, not at all (SchoolStages#GATED).
				boolean gatedHere = aw.option >= 0 && (SchoolStages.gatedHeads(aw.option) & (1 << h)) != 0;
				if (!gatedHere && byTech && which >= 0 && (SchoolStages.gatedHeads(which) & (1 << h)) != 0) {
					continue;
				}
				float[] into = gatedHere ? aw.dOption : aw.dLogits;
				if (shadowed || ruledHere && !ActionSpace.unlocked(mask, h)) {
					// Policy#evaluate left a locked head out.
					Policy.softmax(aw.logits, off, size, probs);
				}
				Teacher.aim(stored, probs, off, size, q);
				double ce = 0;
				int best = 0;
				for (int k = 0; k < size; k++) {
					float p = probs[off + k];
					float t = q[off + k];
					if (t > 0f) {
						ce -= t * Math.log(Math.max(p, 1e-9f));
					}
					if (p > probs[off + best]) {
						best = k;
					}
					if (pull > 0f) {
						into[off + k] += (byTech ? pull * Teacher.pressWeight(stored, h, r.vec[i], which) : pull) * (p - t);
					}
				}
				if (ruledHere) {
					ruledCe += ce;
					ruledSteps++;
					ruledAgree += stored[off + best] >= Teacher.ANY ? 1 : 0;
					ruledCeHead[h] += ce;
					ruledNHead[h]++;
					ruledAgreeHead[h] += stored[off + best] >= Teacher.ANY ? 1 : 0;
					for (int k = 0; k < RULE_NAMES.length; k++) {
						if ((r.ruleBits[i] & (1 << k)) != 0) {
							ruleCe[k] += ce;
							ruleN[k]++;
							ruleAgree[k] += stored[off + best] >= Teacher.ANY ? 1 : 0;
						}
					}
				} else if (shadowed) {
					if (stored[off + 1] >= Teacher.ANY) {
						shadowSwings++;
						shadowSwingAgree += best == 1 ? 1 : 0;
					} else {
						shadowHolds++;
						shadowHoldAgree += best == 0 ? 1 : 0;
					}
				} else if (byTech) {
					techCe += ce;
					techSteps++;
					techAgree += stored[off + best] >= Teacher.ANY ? 1 : 0;
				} else if (old) {
					oldCe += ce;
					oldSteps++;
					if (!camera) {
						oldBodyCe += ce;
						oldBodySteps++;
					}
				}
			}
		}
	}

	private static final float[] ZERO = new float[ActionSpace.HEADS];
	/**
	 * A technique teacher's pull on a step, times exp(advantage / TEACH_AWR_BETA) (the advantage in deviations), at most
	 * TEACH_AWR_MOST: a technique done where the fight then went better than the critic expected is taught harder, one where
	 * it went worse hardly at all. 25 Oct 09:35, the user: damage first, "it should learn when to"; at a flat pull the
	 * techniques cost the school 10 to 20 a fight against the installed model (06:16, 09:29 checks).
	 */
	static final float TEACH_AWR_BETA = 1f;
	static final float TEACH_AWR_MOST = 1.5f; // 3 for the first hour: with the full pulls it taught the escapes' S up to six times over

	/** One update over a rollout whose advantages are computed. */
	public Stats update(SchoolRollout r, Random rng, Settings set) {
		long start = System.nanoTime();
		if (Float.isNaN(actorLr)) {
			actorLr = set.actorLr;
		}
		Stats st = new Stats();
		int n = r.size;
		st.samples = n;
		if (n == 0) {
			return st;
		}
		double mean = 0;
		double weight = 0;
		for (int i = 0; i < n; i++) {
			mean += r.advantage[i] * r.policyWeight[i];
			weight += r.policyWeight[i];
		}
		mean /= Math.max(1e-9, weight);
		double var = 0;
		for (int i = 0; i < n; i++) {
			double d = r.advantage[i] - mean;
			var += d * d * r.policyWeight[i];
		}
		float advMean = (float) mean;
		float advStd = (float) Math.sqrt(var / Math.max(1e-9, weight)) + 1e-8f;
		st.explained = explained(r);
		int[] order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		int batch = Math.min(set.minibatch, n);
		double policy = 0;
		double value = 0;
		double clipped = 0;
		double[] ent = new double[ActionSpace.HEADS];
		long entCount = 0;
		double techCe = 0;
		double oldCe = 0;
		double oldBodyCe = 0;
		long techAgree = 0;
		long techSteps = 0;
		long shadowSwingAgree = 0;
		long shadowSwings = 0;
		long shadowHoldAgree = 0;
		long shadowHolds = 0;
		long oldSteps = 0;
		long oldBodySteps = 0;
		double ruledCe = 0;
		long ruledAgree = 0;
		long ruledSteps = 0;
		double[] ruledCeHeads = new double[ActionSpace.HEADS];
		long[] ruledAgreeHeads = new long[ActionSpace.HEADS];
		long[] ruledNHeads = new long[ActionSpace.HEADS];
		double[] ruleCes = new double[RULE_NAMES.length];
		long[] ruleAgrees = new long[RULE_NAMES.length];
		long[] ruleNs = new long[RULE_NAMES.length];
		long seen = 0;
		for (int epoch = 0; epoch < set.epochs; epoch++) {
			shuffle(order, rng);
			double epochKl = 0;
			int epochSeen = 0;
			for (int from = 0; from < n; from += batch) {
				int to = Math.min(n, from + batch);
				runWorkers(r, order, from, to, advMean, advStd, set);
				Worker first = workers[0];
				for (int w = 1; w < workers.length; w++) {
					ParamSet.accumulate(first.aw.grads, workers[w].aw.grads);
					ParamSet.accumulate(first.cw.grads, workers[w].cw.grads);
				}
				if (!set.criticOnly) {
					Adam.step(actor.params, first.aw.grads, actorLr, 1f / (to - from), set.maxGradNorm);
				}
				Adam.step(critic.params, first.cw.grads, set.criticLr, 1f / (to - from), set.maxGradNorm);
				for (Worker w : workers) {
					policy += w.policyLoss;
					value += w.valueLoss;
					clipped += w.clipped;
					epochKl += w.kl;
					for (int h = 0; h < ActionSpace.HEADS; h++) {
						ent[h] += w.entSum[h];
					}
					entCount += w.entCount;
					techCe += w.techCe;
					oldCe += w.oldCe;
					oldBodyCe += w.oldBodyCe;
					techAgree += w.techAgree;
					techSteps += w.techSteps;
					shadowSwingAgree += w.shadowSwingAgree;
					shadowSwings += w.shadowSwings;
					shadowHoldAgree += w.shadowHoldAgree;
					shadowHolds += w.shadowHolds;
					oldSteps += w.oldSteps;
					oldBodySteps += w.oldBodySteps;
					ruledCe += w.ruledCe;
					ruledAgree += w.ruledAgree;
					ruledSteps += w.ruledSteps;
					for (int h = 0; h < ActionSpace.HEADS; h++) {
						ruledCeHeads[h] += w.ruledCeHead[h];
						ruledAgreeHeads[h] += w.ruledAgreeHead[h];
						ruledNHeads[h] += w.ruledNHead[h];
					}
					for (int k = 0; k < RULE_NAMES.length; k++) {
						ruleCes[k] += w.ruleCe[k];
						ruleAgrees[k] += w.ruleAgree[k];
						ruleNs[k] += w.ruleN[k];
					}
				}
				epochSeen += to - from;
			}
			seen += epochSeen;
			st.epochs = epoch + 1;
			st.kl = (float) (epochKl / epochSeen);
			if (set.criticOnly) {
				continue;
			}
			if (set.adaptiveLr) {
				if (st.kl > 2f * set.klTarget) {
					actorLr = Math.max(set.minActorLr, actorLr / 1.5f);
				} else if (st.kl < 0.5f * set.klTarget) {
					actorLr = Math.min(set.maxActorLr, actorLr * 1.5f);
				}
			}
			if (set.klStop > 0f && st.kl > set.klStop) {
				break;
			}
		}
		st.policyLoss = (float) (policy / seen);
		st.valueLoss = (float) (value / seen);
		st.clipFraction = (float) (clipped / seen);
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			st.entropy[h] = entCount == 0 ? 0f : (float) (ent[h] / entCount);
		}
		st.techCe = techSteps == 0 ? 0f : (float) (techCe / techSteps);
		st.techAgree = techSteps == 0 ? 0f : (float) techAgree / techSteps;
		st.techSteps = (int) (techSteps / Math.max(1, st.epochs));
		st.shadowSwingAgree = shadowSwings == 0 ? 0f : (float) shadowSwingAgree / shadowSwings;
		st.shadowSwings = (int) (shadowSwings / Math.max(1, st.epochs));
		st.shadowHoldAgree = shadowHolds == 0 ? 0f : (float) shadowHoldAgree / shadowHolds;
		st.shadowHolds = (int) (shadowHolds / Math.max(1, st.epochs));
		st.oldCe = oldSteps == 0 ? 0f : (float) (oldCe / oldSteps);
		st.oldBodyCe = oldBodySteps == 0 ? 0f : (float) (oldBodyCe / oldBodySteps);
		st.ruledCe = ruledSteps == 0 ? 0f : (float) (ruledCe / ruledSteps);
		st.ruledAgree = ruledSteps == 0 ? 0f : (float) ruledAgree / ruledSteps;
		st.ruledSteps = (int) (ruledSteps / Math.max(1, st.epochs));
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			st.ruledCeHead[h] = ruledNHeads[h] == 0 ? 0f : (float) (ruledCeHeads[h] / ruledNHeads[h]);
			st.ruledAgreeHead[h] = ruledNHeads[h] == 0 ? 0f : (float) ruledAgreeHeads[h] / ruledNHeads[h];
			st.ruledNHead[h] = (int) (ruledNHeads[h] / Math.max(1, st.epochs));
		}
		for (int k = 0; k < RULE_NAMES.length; k++) {
			st.ruleCe[k] = ruleNs[k] == 0 ? 0f : (float) (ruleCes[k] / ruleNs[k]);
			st.ruleAgree[k] = ruleNs[k] == 0 ? 0f : (float) ruleAgrees[k] / ruleNs[k];
			st.ruleN[k] = (int) (ruleNs[k] / Math.max(1, st.epochs));
		}
		st.actorLr = actorLr;
		st.millis = (System.nanoTime() - start) / 1_000_000L;
		return st;
	}

	private void runWorkers(SchoolRollout r, int[] order, int from, int to, float advMean, float advStd, Settings set) {
		for (Worker w : workers) {
			w.reset();
		}
		if (pool == null) {
			workers[0].run(r, order, from, to, advMean, advStd, set);
			return;
		}
		int size = to - from;
		int per = (size + workers.length - 1) / workers.length;
		List<Callable<Void>> jobs = new ArrayList<>(workers.length);
		for (int w = 0; w < workers.length; w++) {
			final Worker worker = workers[w];
			final int a = Math.min(to, from + w * per);
			final int b = Math.min(to, a + per);
			jobs.add(() -> {
				worker.run(r, order, a, b, advMean, advStd, set);
				return null;
			});
		}
		try {
			for (Future<Void> f : pool.invokeAll(jobs)) {
				f.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("update interrupted", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException("update worker failed", e.getCause());
		}
	}

	private static float explained(SchoolRollout r) {
		double mean = 0;
		for (int i = 0; i < r.size; i++) {
			mean += r.ret[i];
		}
		mean /= r.size;
		double varRet = 0;
		double varErr = 0;
		for (int i = 0; i < r.size; i++) {
			double d = r.ret[i] - mean;
			varRet += d * d;
			double e = r.ret[i] - r.value[i];
			varErr += e * e;
		}
		return varRet < 1e-12 ? 0f : (float) (1.0 - varErr / varRet);
	}

	private static void shuffle(int[] a, Random rng) {
		for (int i = a.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int t = a[i];
			a[i] = a[j];
			a[j] = t;
		}
	}

	@Override
	public void close() {
		if (pool != null) {
			pool.shutdownNow();
		}
	}
}
