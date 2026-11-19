package dev.swordai.core.league;

import dev.swordai.core.nn.Adam;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Policy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * VPT-style inverse dynamics model (Baker et al. 2022) in pure Java:
 * given a fighter's observed kinematics at ticks {@code t-1, t, t+1} (the exact fields saved by
 * {@code RoundRecorder}), predicts the five body keys {@code (forward, strafe, sprint, jump, attack)}
 * that produced the transition.
 *
 * <p>Trained on arena fights where every bot's ground-truth keys are known, then used by
 * {@link HumanImitation} to label recorded real human rounds in {@code run/swordai/real-fights/}.
 */
public final class InverseDynamics {
	private static final int MAGIC = 0x53574944; // "SWID"
	/** Per-tick kinematic features: dist, fwdVel, rightVel, upVel, onGround, sprint, charge, swing, facing, pitch. */
	public static final int TICK_DIM = 10;
	/** Three consecutive ticks (t-1, t, t+1) + tookHit flag. */
	public static final int IN_DIM = 3 * TICK_DIM + 1;
	/** Five body heads: FORWARD(3), STRAFE(3), SPRINT(2), JUMP(2), ATTACK(2) = 12 logits. */
	public static final int[] HEADS = {
		ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.JUMP, ActionSpace.ATTACK
	};
	public static final int[] SIZES = {3, 3, 2, 2, 2};
	public static final int[] OFFSETS = {0, 3, 6, 8, 10};
	public static final int OUT_DIM = 12;
	private static final int HIDDEN = 96;

	public final ParamSet params = new ParamSet();
	private final Dense l1;
	private final Dense l2;
	private final Dense out;
	private final float[] h1 = new float[HIDDEN];
	private final float[] h2 = new float[HIDDEN];
	private final float[] logits = new float[OUT_DIM];
	private final float[] probs = new float[OUT_DIM];
	private final float[] dLogits = new float[OUT_DIM];
	private final float[] dH2 = new float[HIDDEN];
	private final float[] dH1 = new float[HIDDEN];
	private final float[][] grads;

	// Ring buffer of ground-truth (features, action) pairs collected from arena fights.
	private static final int CAPACITY = 32_768;
	private final float[][] bufX = new float[CAPACITY][IN_DIM];
	private final byte[][] bufY = new byte[CAPACITY][HEADS.length];
	private int bufSize;
	private int bufNext;
	private long trainedSteps;
	private float lastAcc;

	public InverseDynamics(long seed) {
		Random rng = new Random(seed);
		float relu = (float) Math.sqrt(2.0);
		l1 = new Dense(params, "idm.l1", IN_DIM, HIDDEN, rng, relu);
		l2 = new Dense(params, "idm.l2", HIDDEN, HIDDEN, rng, relu);
		out = new Dense(params, "idm.out", HIDDEN, OUT_DIM, rng, 0.01f);
		grads = params.newGrads();
	}

	/**
	 * Packs one tick's kinematics into {@code dst[off .. off+TICK_DIM)} in the fighter's ego heading
	 * frame by rotating {@code (toward, sideSigned)} by {@code facingDeg}.
	 */
	public static void packTick(float dist, float toward, float sideSigned, float up,
			boolean onGround, boolean sprinting, float swordCharge, boolean swinging,
			float facingDeg, float pitchDeg, float[] dst, int off) {
		double rad = Math.toRadians(facingDeg);
		double c = Math.cos(rad);
		double s = Math.sin(rad);
		float egoFwd = (float) (toward * c + sideSigned * s);
		float egoRight = (float) (-toward * s + sideSigned * c);
		dst[off] = Math.min(2f, dist / 8f);
		dst[off + 1] = egoFwd * 5f;
		dst[off + 2] = egoRight * 5f;
		dst[off + 3] = up * 5f;
		dst[off + 4] = onGround ? 1f : 0f;
		dst[off + 5] = sprinting ? 1f : 0f;
		dst[off + 6] = swordCharge;
		dst[off + 7] = swinging ? 1f : 0f;
		dst[off + 8] = facingDeg / 90f;
		dst[off + 9] = pitchDeg / 90f;
	}

	/** Combines three consecutive packed ticks plus {@code tookHit} into an {@link #IN_DIM} vector. */
	public static void packWindow(float[] tPrev, float[] tCur, float[] tNext, boolean tookHit, float[] out) {
		System.arraycopy(tPrev, 0, out, 0, TICK_DIM);
		System.arraycopy(tCur, 0, out, TICK_DIM, TICK_DIM);
		System.arraycopy(tNext, 0, out, 2 * TICK_DIM, TICK_DIM);
		out[3 * TICK_DIM] = tookHit ? 1f : 0f;
	}

	/** Records one ground-truth transition from a bot/arena fight. */
	public synchronized void addSample(float[] features, int[] fullAction) {
		System.arraycopy(features, 0, bufX[bufNext], 0, IN_DIM);
		for (int i = 0; i < HEADS.length; i++) {
			bufY[bufNext][i] = (byte) fullAction[HEADS[i]];
		}
		bufNext = (bufNext + 1) % CAPACITY;
		bufSize = Math.min(bufSize + 1, CAPACITY);
	}

	public synchronized int sampleCount() {
		return bufSize;
	}

	public long trainedSteps() {
		return trainedSteps;
	}

	public float lastAccuracy() {
		return lastAcc;
	}

	/** Runs {@code batches} minibatch SGD steps over the stored arena transitions. Returns mean CE. */
	public synchronized float train(int batches, int batchSize, float lr, Random rng) {
		if (bufSize < batchSize) {
			return 0f;
		}
		double sumCe = 0;
		long correct = 0;
		long total = 0;
		for (int b = 0; b < batches; b++) {
			ParamSet.zeroGrads(grads);
			for (int s = 0; s < batchSize; s++) {
				int idx = rng.nextInt(bufSize);
				forward(bufX[idx]);
				Arrays.fill(dLogits, 0f);
				for (int h = 0; h < HEADS.length; h++) {
					int off = OFFSETS[h];
					int n = SIZES[h];
					Policy.softmax(logits, off, n, probs);
					int target = bufY[idx][h];
					sumCe -= Math.log(Math.max(probs[off + target], 1e-12f));
					int argmax = 0;
					for (int k = 0; k < n; k++) {
						if (probs[off + k] > probs[off + argmax]) {
							argmax = k;
						}
						dLogits[off + k] = probs[off + k] - (k == target ? 1f : 0f);
					}
					if (argmax == target) {
						correct++;
					}
					total++;
				}
				backward(bufX[idx]);
			}
			Adam.step(params, grads, lr, 1f / batchSize, 1.0f);
			trainedSteps += batchSize;
		}
		lastAcc = total > 0 ? (float) correct / total : 0f;
		return total > 0 ? (float) (sumCe / total) : 0f;
	}

	/**
	 * Predicts body keys into {@code actionOut[FORWARD..ATTACK]}. If the model has not yet been
	 * trained on arena data, falls back to a clean kinematic estimate so human cloning works from
	 * the very first minute and sharpens automatically once arena data arrives.
	 */
	public synchronized void predict(float[] features, int[] actionOut) {
		if (trainedSteps < 512) {
			kinematicFallback(features, actionOut);
			return;
		}
		forward(features);
		for (int h = 0; h < HEADS.length; h++) {
			int off = OFFSETS[h];
			int n = SIZES[h];
			int best = 0;
			for (int k = 1; k < n; k++) {
				if (logits[off + k] > logits[off + best]) {
					best = k;
				}
			}
			actionOut[HEADS[h]] = best;
		}
	}

	/** Quantises a measured angle change (degrees/tick) into the nearest discrete camera bin. */
	public static int nearestBin(float deltaDeg, float[] bins) {
		int best = 0;
		float bestErr = Math.abs(deltaDeg - bins[0]);
		for (int i = 1; i < bins.length; i++) {
			float err = Math.abs(deltaDeg - bins[i]);
			if (err < bestErr) {
				bestErr = err;
				best = i;
			}
		}
		return best;
	}

	private static void kinematicFallback(float[] f, int[] actionOut) {
		// Features at t+1 (offset 2*TICK_DIM) show the velocity & arm swing produced by tick t's keys.
		int cur = TICK_DIM;
		int nxt = 2 * TICK_DIM;
		boolean tookHit = f[3 * TICK_DIM] > 0.5f;
		float fwdNext = f[nxt + 1];
		float rightNext = f[nxt + 2];
		float upNext = f[nxt + 3];
		boolean groundCur = f[cur + 4] > 0.5f;
		boolean groundNext = f[nxt + 4] > 0.5f;
		boolean sprintNext = f[nxt + 5] > 0.5f;
		boolean swingCur = f[cur + 7] > 0.5f;
		boolean swingNext = f[nxt + 7] > 0.5f;
		float chargeCur = f[cur + 6];
		float chargeNext = f[nxt + 6];

		actionOut[ActionSpace.FORWARD] = fwdNext > 0.25f ? 1 : (fwdNext < -0.25f && !tookHit ? 2 : 0);
		actionOut[ActionSpace.STRAFE] = rightNext < -0.22f ? 1 : (rightNext > 0.22f ? 2 : 0);
		actionOut[ActionSpace.SPRINT] = sprintNext ? 1 : 0;
		actionOut[ActionSpace.JUMP] = (groundCur && !groundNext && upNext > 1.2f && !tookHit) ? 1 : 0;
		actionOut[ActionSpace.ATTACK] = ((!swingCur && swingNext) || (chargeNext < chargeCur - 0.15f)) ? 1 : 0;
	}

	private void forward(float[] x) {
		l1.forward(x, 0, h1, 0);
		relu(h1);
		l2.forward(h1, 0, h2, 0);
		relu(h2);
		out.forward(h2, 0, logits, 0);
	}

	private void backward(float[] x) {
		out.backward(h2, 0, dLogits, 0, dH2, 0, grads);
		gate(dH2, h2);
		l2.backward(h1, 0, dH2, 0, dH1, 0, grads);
		gate(dH1, h1);
		l1.backward(x, 0, dH1, 0, null, 0, grads);
	}

	public synchronized void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
			dos.writeInt(MAGIC);
			dos.writeLong(trainedSteps);
			dos.writeFloat(lastAcc);
			dos.writeLong(params.adamSteps);
			dos.writeInt(params.params().size());
			for (Param p : params.params()) {
				dos.writeUTF(p.name);
				dos.writeInt(p.w.length);
				for (float v : p.w) {
					dos.writeFloat(v);
				}
			}
		}
	}

	public synchronized boolean load(Path path) throws IOException {
		if (!Files.exists(path)) {
			return false;
		}
		try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			if (dis.readInt() != MAGIC) {
				return false;
			}
			trainedSteps = dis.readLong();
			lastAcc = dis.readFloat();
			params.adamSteps = dis.readLong();
			int n = dis.readInt();
			for (int i = 0; i < n; i++) {
				String name = dis.readUTF();
				int len = dis.readInt();
				float[] w = new float[len];
				for (int k = 0; k < len; k++) {
					w[k] = dis.readFloat();
				}
				for (Param p : params.params()) {
					if (p.name.equals(name) && p.w.length == len) {
						System.arraycopy(w, 0, p.w, 0, len);
					}
				}
			}
		}
		return true;
	}

	private static void relu(float[] a) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] < 0f) {
				a[i] = 0f;
			}
		}
	}

	private static void gate(float[] g, float[] a) {
		for (int i = 0; i < g.length; i++) {
			if (a[i] <= 0f) {
				g[i] = 0f;
			}
		}
	}
}
