package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.ParamSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clipped-surrogate PPO with a separate critic. Each minibatch is split across a few low-priority
 * worker threads, each with its own activations and gradient buffers.
 */
public final class Ppo implements AutoCloseable {
	private final ActorNet actor;
	private final CriticNet critic;
	private final Config cfg;
	private final Worker[] workers;
	private final ExecutorService pool;
	private float actorLr;
	/** The entropy bonus per head for the update in progress: the configured one times the stage's scale. */
	private volatile float[] entropyCoef;
	/**
	 * The network training is held near ({@code Config.anchorWeight}), never trained; null is none.
	 * Its choices on each state of the update in progress, worked out once and kept for every epoch.
	 */
	private volatile ActorNet anchor;
	private volatile float[][] anchorChoices;
	/**
	 * Per kind of fight, the mean and spread its advantages are normalised by in the update in progress
	 * ({@code Config.taskAdvantageNorm}); null when all are normalised together. Index
	 * {@link CriticNet#TASKS} is for steps of no known kind.
	 */
	private volatile float[] taskAdvMean;
	private volatile float[] taskAdvStd;

	public Ppo(ActorNet actor, CriticNet critic, Config cfg, int threads) {
		this.actor = actor;
		this.critic = critic;
		this.cfg = cfg;
		this.actorLr = cfg.actorLr;
		this.entropyCoef = cfg.entropyCoef;
		this.workers = new Worker[Math.max(1, threads)];
		for (int i = 0; i < workers.length; i++) {
			workers[i] = new Worker();
		}
		if (workers.length > 1) {
			AtomicInteger n = new AtomicInteger();
			pool = Executors.newFixedThreadPool(workers.length, r -> {
				Thread t = new Thread(r, "swordai-trainer-" + n.incrementAndGet());
				t.setDaemon(true);
				// The game's render and server threads must always win the CPU.
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			});
		} else {
			pool = null;
		}
	}

	private final class Worker {
		final ActorNet.Workspace aw = actor.newWorkspace();
		final CriticNet.Workspace cw = critic.newWorkspace();
		final float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		final float[] entropy = new float[ActionSpace.HEADS];
		ActorNet.Workspace anchorWs;
		ActorNet anchorWsOf;

		double policyLoss;
		double valueLoss;
		double kl;
		double anchorKl;
		int anchorSeen;
		int clipped;
		final double[] entropySum = new double[ActionSpace.HEADS];
		final int[] entropyCount = new int[ActionSpace.HEADS];

		void resetStats() {
			policyLoss = 0;
			valueLoss = 0;
			kl = 0;
			anchorKl = 0;
			anchorSeen = 0;
			clipped = 0;
			java.util.Arrays.fill(entropySum, 0);
			java.util.Arrays.fill(entropyCount, 0);
		}

		void run(Rollout r, int[] order, int from, int to, float advMean, float advStd, boolean trainActor) {
			ParamSet.zero(aw.grads);
			ParamSet.zero(cw.grads);
			final float clip = cfg.clip;
			for (int n = from; n < to; n++) {
				int i = order[n];
				int mask = r.headMask[i];
				byte[] act = r.action[i];

				actor.forward(aw, r.peri[i], r.fovea[i], r.vec[i], r.tactic[i], r.opp[i], r.oppWindow[i]);
				// The policy that was played included these pushes; the one learned about must too.
				JumpNudge.apply(aw.logits, r.vec[i], r.nudge[i]);
				BackNudge.apply(aw.logits, r.vec[i], r.nudgeBack[i]);
				Policy.pushSwing(aw.logits, r.attackBias[i]);
				float logp = Policy.evaluate(aw.logits, mask, act, probs, entropy);
				float ratio = (float) Math.exp(logp - r.logp[i]);
				float[] tm = taskAdvMean;
				float adv;
				if (tm != null) {
					int t = r.task[i] >= 0 && r.task[i] < CriticNet.TASKS ? r.task[i] : CriticNet.TASKS;
					adv = (r.advantage[i] - tm[t]) / taskAdvStd[t];
				} else {
					adv = (r.advantage[i] - advMean) / advStd;
				}
				float surr1 = ratio * adv;
				float surr2 = Math.max(1f - clip, Math.min(1f + clip, ratio)) * adv;
				boolean inside = ratio >= 1f - clip && ratio <= 1f + clip;
				// The clipped branch has zero gradient once the ratio has left the trust region.
				float dLogp = (surr1 <= surr2 || inside) ? -adv * ratio : 0f;
				float[] q = anchorChoices(r, i);
				if (q != null) {
					anchorKl += Policy.kl(mask, q, probs);
					anchorSeen++;
				}
				if (trainActor) {
					Policy.gradient(mask, act, probs, entropy, dLogp, entropyCoef, aw.dLogits);
					float anchorW = anchorWeight(r.task[i]);
					if (cfg.anchorGainWeight >= 0f && r.advantage[i] > 0f) {
						// Better than expected: held back by the anchor no more than this; see Config.anchorGainWeight.
						anchorW = Math.min(anchorW, cfg.anchorGainWeight);
					}
					if (q != null && anchorW > 0f) {
						Policy.addKlGradient(mask, q, probs, anchorW, aw.dLogits);
					}
					actor.backward(aw, r.peri[i], r.fovea[i], r.vec[i]);
				}

				policyLoss -= Math.min(surr1, surr2);
				kl += r.logp[i] - logp;
				if (!inside) {
					clipped++;
				}
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					if (ActionSpace.unlocked(mask, h)) {
						entropySum[h] += entropy[h];
						entropyCount[h]++;
					}
				}

				float v = critic.forward(cw, r.vec[i], r.priv[i], r.task[i]);
				float dv = v - r.ret[i];
				valueLoss += 0.5 * dv * dv;
				critic.backward(cw, dv);
			}
		}

		/**
		 * The anchor's choices on step {@code i} of the update in progress, with the same pushes the
		 * played policy had; null without an anchor. Each step is worked out by one worker only, once.
		 */
		float[] anchorChoices(Rollout r, int i) {
			float[][] kept = anchorChoices;
			ActorNet a = anchor;
			if (kept == null || a == null) {
				return null;
			}
			float[] q = kept[i];
			if (q == null) {
				if (anchorWsOf != a) {
					anchorWs = a.newWorkspace();
					anchorWsOf = a;
				}
				a.forward(anchorWs, r.peri[i], r.fovea[i], r.vec[i], r.tactic[i], r.opp[i], r.oppWindow[i]);
				JumpNudge.apply(anchorWs.logits, r.vec[i], r.nudge[i]);
				BackNudge.apply(anchorWs.logits, r.vec[i], r.nudgeBack[i]);
				Policy.pushSwing(anchorWs.logits, r.attackBias[i]);
				q = new float[ActionSpace.TOTAL_LOGITS];
				Policy.allProbs(anchorWs.logits, q);
				kept[i] = q;
			}
			return q;
		}

		/** Self-imitation on the kept steps {@code idx[from..to)}; see {@link Ppo#selfImitate}. */
		void runSil(SilBuffer b, int[] idx, int from, int to, float weight, float valueWeight, float clip) {
			ParamSet.zero(aw.grads);
			ParamSet.zero(cw.grads);
			for (int k = from; k < to; k++) {
				int i = idx[k];
				float v = critic.forward(cw, b.vec[i], b.priv[i], b.task[i]);
				float gap = Math.min(clip, b.ret[i] - v);
				if (gap <= 0f) {
					continue;
				}
				critic.backward(cw, -valueWeight * gap);
				actor.forward(aw, b.peri[i], b.fovea[i], b.vec[i], b.tactic[i], b.opp[i], b.window[i]);
				JumpNudge.apply(aw.logits, b.vec[i], b.nudge[i]);
				BackNudge.apply(aw.logits, b.vec[i], b.nudgeBack[i]);
				Policy.pushSwing(aw.logits, b.attackBias[i]);
				Policy.evaluate(aw.logits, b.headMask[i], b.action[i], probs, entropy);
				// Loss -gap * log pi(a|s): the action that brought more than expected, made likelier.
				Policy.gradient(b.headMask[i], b.action[i], probs, entropy, -weight * gap, NO_ENTROPY, aw.dLogits);
				actor.backward(aw, b.peri[i], b.fovea[i], b.vec[i]);
			}
		}
	}

	public float actorLr() {
		return actorLr;
	}

	/** The anchor's weight on a step of this kind of fight; see {@code Config.anchorBoostWeight}. */
	float anchorWeight(int task) {
		if (cfg.anchorBoostWeight > 0f && cfg.anchorBoostTasks != null) {
			for (int t : cfg.anchorBoostTasks) {
				if (t == task) {
					return cfg.anchorBoostWeight;
				}
			}
		}
		return cfg.anchorWeight;
	}

	/** The network training is held near ({@code Config.anchorWeight}); null for none. Not while an update runs. */
	public void setAnchor(ActorNet a) {
		anchor = a;
	}

	public boolean hasAnchor() {
		return anchor != null;
	}

	/** What one self-imitation pass did; see {@link #selfImitate}. */
	public static final class SilStats {
		/** Steps kept in the buffer. */
		public int kept;
		/** Of those, the ones the critic still expects less of than they brought. */
		public int stillBetter;
		/** By how much on average (clipped). */
		public float meanGap;
		/** Samples learned from in this pass. */
		public int samples;
	}

	private static final float[] NO_ENTROPY = new float[ActionSpace.HEADS];

	/**
	 * Self-imitation (Oh et al., 2018), after a PPO update: of the kept steps that the critic still
	 * expects less of than they brought ({@code R - V > 0}), {@code batches} minibatches drawn at
	 * random, each step's action made likelier in proportion to that gap (clipped at {@code clip}),
	 * and the critic raised towards the return by {@code valueWeight} of it. Steps the critic has
	 * caught up with weigh nothing, so what is learned from them stops by itself.
	 */
	public SilStats selfImitate(SilBuffer buf, Random rng, int batches, float weight, float valueWeight, float clip) {
		SilStats s = new SilStats();
		s.kept = buf.size;
		if (buf.size == 0 || batches <= 0 || weight <= 0f) {
			return s;
		}
		int[] better = new int[buf.size];
		CriticNet.Workspace cw = workers[0].cw;
		double gaps = 0;
		for (int i = 0; i < buf.size; i++) {
			float gap = buf.ret[i] - critic.forward(cw, buf.vec[i], buf.priv[i], buf.task[i]);
			if (gap > 0f) {
				better[s.stillBetter++] = i;
				gaps += Math.min(clip, gap);
			}
		}
		if (s.stillBetter == 0) {
			return s;
		}
		s.meanGap = (float) (gaps / s.stillBetter);
		int batch = Math.min(cfg.minibatch, s.stillBetter);
		int[] idx = new int[batch];
		for (int b = 0; b < batches; b++) {
			for (int k = 0; k < batch; k++) {
				idx[k] = better[rng.nextInt(s.stillBetter)];
			}
			runSilWorkers(buf, idx, weight, valueWeight, clip);
			Worker first = workers[0];
			for (int w = 1; w < workers.length; w++) {
				ParamSet.accumulate(first.aw.grads, workers[w].aw.grads);
				ParamSet.accumulate(first.cw.grads, workers[w].cw.grads);
			}
			Adam.step(actor.params, first.aw.grads, actorLr, 1f / batch, cfg.maxGradNorm);
			if (valueWeight > 0f) {
				// Not with no gradient: Adam's momentum alone would still move the critic.
				Adam.step(critic.params, first.cw.grads, cfg.criticLr, 1f / batch, cfg.maxGradNorm);
			}
			s.samples += batch;
		}
		return s;
	}

	private void runSilWorkers(SilBuffer buf, int[] idx, float weight, float valueWeight, float clip) {
		if (pool == null) {
			workers[0].runSil(buf, idx, 0, idx.length, weight, valueWeight, clip);
			return;
		}
		int size = idx.length;
		int per = (size + workers.length - 1) / workers.length;
		List<Callable<Void>> jobs = new ArrayList<>(workers.length);
		for (int w = 0; w < workers.length; w++) {
			final Worker worker = workers[w];
			final int a = Math.min(size, w * per);
			final int b = Math.min(size, a + per);
			jobs.add(() -> {
				worker.runSil(buf, idx, a, b, weight, valueWeight, clip);
				return null;
			});
		}
		try {
			for (Future<Void> f : pool.invokeAll(jobs)) {
				f.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("self-imitation interrupted", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException("self-imitation worker failed", e.getCause());
		}
	}

	/** Lowers the actor's learning rate to at most this; see {@code Learner.capActorLr}. */
	public void capActorLr(float most) {
		actorLr = Math.min(actorLr, most);
	}

	/**
	 * Runs a full update on a rollout whose advantages have already been computed.
	 *
	 * @param trainActor false trains only the critic, e.g. while it catches up after a stage change
	 */
	public UpdateStats update(Rollout r, Random rng, boolean trainActor) {
		return update(r, rng, trainActor, 1f);
	}

	/** @param entropyScale multiplies the configured entropy bonus of every head for this update */
	public UpdateStats update(Rollout r, Random rng, boolean trainActor, float entropyScale) {
		return update(r, rng, trainActor, entropyScale, Float.NEGATIVE_INFINITY);
	}

	/**
	 * @param actorNeeds the least share of the batch's returns the critic has to explain for the actor
	 *                   to learn from it; below that only the critic is trained. What the actor learns
	 *                   is "better or worse than the critic expected", and from a critic that expected
	 *                   nothing like what happened that is noise.
	 */
	public UpdateStats update(Rollout r, Random rng, boolean trainActor, float entropyScale, float actorNeeds) {
		float[] coef = cfg.entropyCoef.clone();
		for (int h = 0; h < coef.length; h++) {
			coef[h] *= entropyScale;
		}
		this.entropyCoef = coef;
		long start = System.nanoTime();
		UpdateStats stats = new UpdateStats();
		int n = r.size;
		stats.samples = n;
		if (n == 0) {
			return stats;
		}

		double mean = 0;
		for (int i = 0; i < n; i++) {
			mean += r.advantage[i];
		}
		mean /= n;
		double var = 0;
		for (int i = 0; i < n; i++) {
			double d = r.advantage[i] - mean;
			var += d * d;
		}
		float advMean = (float) mean;
		float advStd = (float) Math.sqrt(var / n) + 1e-8f;
		stats.explainedVariance = explainedVariance(r);
		setTaskNormalisation(r, advMean, advStd);
		if (stats.explainedVariance < actorNeeds) {
			trainActor = false;
		}

		int[] order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		int batch = Math.min(cfg.minibatch, n);
		anchorChoices = anchor == null ? null : new float[n][];
		double anchorKl = 0;
		long anchorSeen = 0;

		double policyLoss = 0;
		double valueLoss = 0;
		double clipped = 0;
		double[] entropySum = new double[ActionSpace.HEADS];
		long[] entropyCount = new long[ActionSpace.HEADS];
		long seen = 0;

		for (int epoch = 0; epoch < cfg.epochs; epoch++) {
			shuffle(order, rng);
			double epochKl = 0;
			int epochSeen = 0;
			for (int from = 0; from < n; from += batch) {
				int to = Math.min(n, from + batch);
				int size = to - from;
				runWorkers(r, order, from, to, advMean, advStd, trainActor);

				Worker first = workers[0];
				for (int w = 1; w < workers.length; w++) {
					ParamSet.accumulate(first.aw.grads, workers[w].aw.grads);
					ParamSet.accumulate(first.cw.grads, workers[w].cw.grads);
				}
				if (trainActor) {
					stats.actorGradNorm = Adam.step(actor.params, first.aw.grads, actorLr, 1f / size, cfg.maxGradNorm);
				}
				stats.criticGradNorm = Adam.step(critic.params, first.cw.grads, cfg.criticLr, 1f / size, cfg.maxGradNorm);

				for (Worker w : workers) {
					policyLoss += w.policyLoss;
					valueLoss += w.valueLoss;
					clipped += w.clipped;
					epochKl += w.kl;
					anchorKl += w.anchorKl;
					anchorSeen += w.anchorSeen;
					for (int h = 0; h < ActionSpace.HEADS; h++) {
						entropySum[h] += w.entropySum[h];
						entropyCount[h] += w.entropyCount[h];
					}
				}
				epochSeen += size;
			}
			seen += epochSeen;
			stats.epochsRun = epoch + 1;
			stats.approxKl = (float) (epochKl / epochSeen);
			if (!trainActor) {
				continue;
			}
			if (cfg.adaptiveLr) {
				if (stats.approxKl > 2f * cfg.klTarget) {
					actorLr = Math.max(cfg.minActorLr, actorLr / 1.5f);
				} else if (stats.approxKl < 0.5f * cfg.klTarget) {
					actorLr = Math.min(cfg.maxActorLr, actorLr * 1.5f);
				}
			}
			if (cfg.klStop > 0f && stats.approxKl > cfg.klStop) {
				break;
			}
		}

		stats.policyLoss = (float) (policyLoss / seen);
		stats.valueLoss = (float) (valueLoss / seen);
		stats.clipFraction = (float) (clipped / seen);
		stats.anchorKl = anchorSeen == 0 ? Float.NaN : (float) (anchorKl / anchorSeen);
		anchorChoices = null;
		taskAdvMean = null;
		taskAdvStd = null;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			stats.entropy[h] = entropyCount[h] == 0 ? 0f : (float) (entropySum[h] / entropyCount[h]);
		}
		stats.actorLr = actorLr;
		stats.actorTrained = trainActor;
		stats.millis = (System.nanoTime() - start) / 1_000_000L;
		return stats;
	}

	private void runWorkers(Rollout r, int[] order, int from, int to, float advMean, float advStd, boolean trainActor) {
		for (Worker w : workers) {
			w.resetStats();
		}
		if (pool == null) {
			workers[0].run(r, order, from, to, advMean, advStd, trainActor);
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
				worker.run(r, order, a, b, advMean, advStd, trainActor);
				return null;
			});
		}
		try {
			for (Future<Void> f : pool.invokeAll(jobs)) {
				f.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("PPO update interrupted", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException("PPO worker failed", e.getCause());
		}
	}

	/**
	 * With {@code Config.taskAdvantageNorm}, each kind of fight's advantages are normalised by their own
	 * mean and spread (PopArt's point, Hessel et al. AAAI 2019: in one network learning many tasks, the
	 * tasks with the biggest rewards drown the rest; here the fights against the no-knockback bot and the
	 * punisher move two to three times the damage of a spacer fight). A kind with fewer than
	 * {@link #TASK_NORM_LEAST} steps in the batch is normalised with the whole batch.
	 */
	private void setTaskNormalisation(Rollout r, float advMean, float advStd) {
		if (!cfg.taskAdvantageNorm) {
			taskAdvMean = null;
			taskAdvStd = null;
			return;
		}
		int kinds = CriticNet.TASKS + 1;
		double[] sum = new double[kinds];
		double[] sumSq = new double[kinds];
		int[] count = new int[kinds];
		for (int i = 0; i < r.size; i++) {
			int t = r.task[i] >= 0 && r.task[i] < CriticNet.TASKS ? r.task[i] : CriticNet.TASKS;
			double a = r.advantage[i];
			sum[t] += a;
			sumSq[t] += a * a;
			count[t]++;
		}
		float[] mean = new float[kinds];
		float[] std = new float[kinds];
		for (int t = 0; t < kinds; t++) {
			if (count[t] >= TASK_NORM_LEAST) {
				double m = sum[t] / count[t];
				mean[t] = (float) m;
				std[t] = (float) Math.sqrt(Math.max(0.0, sumSq[t] / count[t] - m * m)) + 1e-8f;
			} else {
				mean[t] = advMean;
				std[t] = advStd;
			}
		}
		taskAdvStd = std;
		taskAdvMean = mean;
	}

	private static final int TASK_NORM_LEAST = 32;

	private static float explainedVariance(Rollout r) {
		int n = r.size;
		double meanRet = 0;
		for (int i = 0; i < n; i++) {
			meanRet += r.ret[i];
		}
		meanRet /= n;
		double varRet = 0;
		double varErr = 0;
		for (int i = 0; i < n; i++) {
			double d = r.ret[i] - meanRet;
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
