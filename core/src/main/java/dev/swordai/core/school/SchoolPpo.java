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
		/** The installed model teaches every key where no technique teacher speaks (the overlay), not the camera only. */
		public boolean installedBody;
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
		public float techAgree;
		public int techSteps;
		public long millis;

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT,
					"%d steps, %d epochs, policy %.4f, value %.4f, kl %.4f, clipped %.2f, explained %.2f, lr %.1e, entropy W/S %.2f jump %.2f swing %.2f, "
							+ "teacher: technique ce %.3f agree %.0f%% on %d, installed ce %.3f, %d ms",
					samples, epochs, policyLoss, valueLoss, kl, clipFraction, explained, actorLr, entropy[ActionSpace.FORWARD],
					entropy[ActionSpace.JUMP], entropy[ActionSpace.ATTACK], techCe, 100f * techAgree, techSteps, oldCe, millis);
		}
	}

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
		int techAgree;
		int techSteps;
		int oldSteps;

		void reset() {
			policyLoss = 0;
			valueLoss = 0;
			kl = 0;
			clipped = 0;
			java.util.Arrays.fill(entSum, 0);
			entCount = 0;
			techCe = 0;
			oldCe = 0;
			techAgree = 0;
			techSteps = 0;
			oldSteps = 0;
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
					teach(r, i, mask, set);
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

		private void teach(SchoolRollout r, int i, int mask, Settings set) {
			float[] stored = r.teacher[i];
			float[] q = aimed;
			int tech = r.teacherHeads[i];
			boolean old = r.teacherWeight[i] > 0f;
			if (tech == 0 && !old) {
				return;
			}
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if (!ActionSpace.unlocked(mask, h)) {
					continue;
				}
				boolean byTech = (tech & (1 << h)) != 0;
				int which = r.teacherTechnique[i];
				// The installed model teaches the camera (yaw and pitch); in the overlay every key between the techniques too.
				boolean camera = h == ActionSpace.YAW || h == ActionSpace.PITCH;
				// Between techniques (no teacher, or the ordinary fight's: the basics teacher holds the swing there) every key
				// the basics teacher does not hold.
				boolean between = tech == 0 || which == dev.swordai.core.pvp.Technique.BASE.ordinal();
				boolean installedHere = old && (camera || set.installedBody && between && !byTech);
				float pull = byTech ? (which >= 0 ? set.techPulls[which] : 0f) : installedHere ? set.oldPull : 0f;
				if (!byTech && !installedHere) {
					continue;
				}
				// Told another technique than the teacher's (the chooser's, in training a step in a few): only the camera is taught.
				if (!camera && !r.intentAgrees[i]) {
					continue;
				}
				int off = ActionSpace.OFFSETS[h];
				int size = ActionSpace.SIZES[h];
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
						aw.dLogits[off + k] += (byTech ? pull * Teacher.pressWeight(stored, h, r.vec[i]) : pull) * (p - t);
					}
				}
				if (byTech) {
					techCe += ce;
					techSteps++;
					techAgree += stored[off + best] >= Teacher.ANY ? 1 : 0;
				} else if (old) {
					oldCe += ce;
					oldSteps++;
				}
			}
		}
	}

	private static final float[] ZERO = new float[ActionSpace.HEADS];

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
		long techAgree = 0;
		long techSteps = 0;
		long oldSteps = 0;
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
					techAgree += w.techAgree;
					techSteps += w.techSteps;
					oldSteps += w.oldSteps;
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
		st.oldCe = oldSteps == 0 ? 0f : (float) (oldCe / oldSteps);
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
