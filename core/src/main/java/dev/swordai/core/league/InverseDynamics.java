package dev.swordai.core.league;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.RemoteView;
import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;
import dev.swordai.core.vision.PlayerPose;
import dev.swordai.core.vision.ViewGeometry;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** VPT-style lagged 5-tick inverse dynamics model predicting (W/S, A/D, sprint, jump, attack) from [t-2..t+2]. */
public final class InverseDynamics {
	private static final int MAGIC = 0x53574945, HIDDEN = 96, CAPACITY = 32_768;
	public static final int WINDOW = 5, TICK_DIM = 10, IN_DIM = WINDOW * TICK_DIM + 1, OUT_DIM = 12;
	public static final int[] HEADS = {ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.JUMP, ActionSpace.ATTACK};
	public static final int[] SIZES = {3, 3, 2, 2, 2}, OFFSETS = {0, 3, 6, 8, 10};

	public final ParamSet params = new ParamSet();
	private final Dense l1, l2, out;
	private final float[] h1 = new float[HIDDEN], h2 = new float[HIDDEN], dH1 = new float[HIDDEN], dH2 = new float[HIDDEN];
	private final float[] logits = new float[OUT_DIM], probs = new float[OUT_DIM], dLogits = new float[OUT_DIM];
	private final float[][] grads, bufX = new float[CAPACITY][IN_DIM];
	private final byte[][] bufY = new byte[CAPACITY][HEADS.length];
	private int bufSize, bufNext;
	private long trainedSteps;
	private float lastAcc;

	public InverseDynamics(long seed) {
		Random rng = new Random(seed); float relu = (float) Math.sqrt(2.0);
		l1 = new Dense(params, "idm.l1", IN_DIM, HIDDEN, rng, relu);
		l2 = new Dense(params, "idm.l2", HIDDEN, HIDDEN, rng, relu);
		out = new Dense(params, "idm.out", HIDDEN, OUT_DIM, rng, 0.01f);
		grads = params.newGrads();
	}

	public static void packTick(float dist, float toward, float sideS, float up, boolean ground, boolean sprint,
			float charge, boolean swing, float facingDeg, float pitchDeg, float[] dst, int off) {
		double rad = Math.toRadians(facingDeg), c = Math.cos(rad), s = Math.sin(rad);
		dst[off] = Math.min(2f, dist / 8f);
		dst[off + 1] = (float) (toward * c + sideS * s) * 5f;
		dst[off + 2] = (float) (-toward * s + sideS * c) * 5f;
		dst[off + 3] = up * 5f;
		dst[off + 4] = ground ? 1f : 0f;
		dst[off + 5] = sprint ? 1f : 0f;
		dst[off + 6] = charge;
		dst[off + 7] = swing ? 1f : 0f;
		dst[off + 8] = facingDeg / 90f;
		dst[off + 9] = pitchDeg / 90f;
	}

	public static void packWindow(float[] tM2, float[] tM1, float[] t0, float[] tP1, float[] tP2, boolean tookHit, float[] out) {
		System.arraycopy(tM2, 0, out, 0, TICK_DIM);
		System.arraycopy(tM1, 0, out, TICK_DIM, TICK_DIM);
		System.arraycopy(t0, 0, out, 2 * TICK_DIM, TICK_DIM);
		System.arraycopy(tP1, 0, out, 3 * TICK_DIM, TICK_DIM);
		System.arraycopy(tP2, 0, out, 4 * TICK_DIM, TICK_DIM);
		out[WINDOW * TICK_DIM] = tookHit ? 1f : 0f;
	}

	/** Client-matched 3-tick RemoteView glide recorder so IDM training matches RoundRecorder client trajectories. */
	public static final class LaggedRecorder {
		public InverseDynamics idm;
		private final RemoteView view = new RemoteView();
		private final BodyState swingState = new BodyState();
		private final float[][] ticks = new float[WINDOW][TICK_DIM];
		private final int[][] acts = new int[WINDOW][ActionSpace.HEADS];
		private final boolean[] hits = new boolean[WINDOW];
		private final float[] win = new float[IN_DIM];
		private int count;
		public boolean tookHit;

		public void reset() { view.reset(); swingState.resetOpponentSwing(); count = 0; tookHit = false; }

		public void record(double bx, double by, double bz, float bodyYaw, float headYaw, float pitch,
				boolean onGround, boolean sprinting, boolean swinging, int swingTime,
				double ox, double oy, double oz, float dist, int[] action) {
			if (idm == null) { tookHit = false; return; }
			view.tick(bx, by, bz, bodyYaw, headYaw, pitch, swinging ? 0.5f : 0f, false, true, true);
			swingState.noteOpponentSwing(swinging, swingTime);
			PlayerPose p = view.now();
			double ux = p.x - ox, uz = p.z - oz, flat = Math.sqrt(ux * ux + uz * uz);
			ux = flat > 1e-6 ? ux / flat : 0; uz = flat > 1e-6 ? uz / flat : 0;
			double vx = view.movedX(), vz = view.movedZ();
			float toward = (float) -(vx * ux + vz * uz);
			float sideS = (float) (vx * uz - vz * ux);
			float up = (float) view.movedY();
			float facing = ViewGeometry.wrapDegrees(p.headYaw - ViewGeometry.yawTo(ox - p.x, oz - p.z));
			int slot = count % WINDOW;
			packTick(dist, toward, sideS, up, onGround, sprinting, swingState.opponentCharge(), swinging, facing, p.headPitch, ticks[slot], 0);
			System.arraycopy(action, 0, acts[slot], 0, ActionSpace.HEADS);
			hits[slot] = tookHit;
			tookHit = false;
			count++;
			if (count >= WINDOW) {
				int i0 = (count - WINDOW) % WINDOW, i1 = (i0 + 1) % WINDOW, i2 = (i0 + 2) % WINDOW, i3 = (i0 + 3) % WINDOW, i4 = (i0 + 4) % WINDOW;
				packWindow(ticks[i0], ticks[i1], ticks[i2], ticks[i3], ticks[i4], hits[i2] || hits[i1], win);
				idm.addSample(win, acts[i2]);
			}
		}
	}

	public synchronized void addSample(float[] features, int[] fullAction) {
		System.arraycopy(features, 0, bufX[bufNext], 0, IN_DIM);
		for (int i = 0; i < HEADS.length; i++) bufY[bufNext][i] = (byte) fullAction[HEADS[i]];
		bufNext = (bufNext + 1) % CAPACITY;
		bufSize = Math.min(bufSize + 1, CAPACITY);
	}

	public synchronized int sampleCount() { return bufSize; }
	public long trainedSteps() { return trainedSteps; }
	public float lastAccuracy() { return lastAcc; }

	public synchronized float train(int batches, int batchSize, float lr, Random rng) {
		if (bufSize < batchSize) return 0f;
		double sumCe = 0; long ok = 0, tot = 0;
		for (int b = 0; b < batches; b++) {
			ParamSet.zeroGrads(grads);
			for (int s = 0; s < batchSize; s++) {
				int idx = rng.nextInt(bufSize);
				l1.forward(bufX[idx], 0, h1, 0); relu(h1);
				l2.forward(h1, 0, h2, 0); relu(h2);
				out.forward(h2, 0, logits, 0);
				Arrays.fill(dLogits, 0f);
				for (int h = 0; h < HEADS.length; h++) {
					int off = OFFSETS[h], n = SIZES[h], target = bufY[idx][h], best = 0;
					Policy.softmax(logits, off, n, probs);
					sumCe -= Math.log(Math.max(probs[off + target], 1e-12f));
					for (int k = 0; k < n; k++) {
						if (probs[off + k] > probs[off + best]) best = k;
						dLogits[off + k] = probs[off + k] - (k == target ? 1f : 0f);
					}
					if (best == target) ok++;
					tot++;
				}
				out.backward(h2, 0, dLogits, 0, dH2, 0, grads); gate(dH2, h2);
				l2.backward(h1, 0, dH2, 0, dH1, 0, grads); gate(dH1, h1);
				l1.backward(bufX[idx], 0, dH1, 0, null, 0, grads);
			}
			Adam.step(params, grads, lr, 1f / batchSize, 1.0f);
			trainedSteps += batchSize;
		}
		lastAcc = tot > 0 ? (float) ok / tot : 0f;
		return tot > 0 ? (float) (sumCe / tot) : 0f;
	}

	public synchronized void predict(float[] f, int[] actionOut) {
		if (trainedSteps < 512) {
			int cur = 2 * TICK_DIM, nxt = 3 * TICK_DIM, nxt2 = 4 * TICK_DIM;
			boolean hit = f[WINDOW * TICK_DIM] > 0.5f;
			float vFwd = 0.5f * (f[nxt + 1] + f[nxt2 + 1]), vSide = 0.5f * (f[nxt + 2] + f[nxt2 + 2]);
			actionOut[ActionSpace.FORWARD] = vFwd > 0.22f ? 1 : (vFwd < -0.22f && !hit ? 2 : 0);
			actionOut[ActionSpace.STRAFE] = vSide < -0.20f ? 1 : (vSide > 0.20f ? 2 : 0);
			actionOut[ActionSpace.SPRINT] = (f[cur + 5] > 0.5f || f[nxt + 5] > 0.5f) ? 1 : 0;
			actionOut[ActionSpace.JUMP] = (f[cur + 4] > 0.5f && (f[nxt + 4] < 0.5f || f[nxt2 + 4] < 0.5f) && Math.max(f[nxt + 3], f[nxt2 + 3]) > 0.9f && !hit) ? 1 : 0;
			actionOut[ActionSpace.ATTACK] = ((f[cur + 7] < 0.5f && f[nxt + 7] > 0.5f) || (f[nxt + 6] < f[cur + 6] - 0.15f)) ? 1 : 0;
			return;
		}
		l1.forward(f, 0, h1, 0); relu(h1);
		l2.forward(h1, 0, h2, 0); relu(h2);
		out.forward(h2, 0, logits, 0);
		for (int h = 0; h < HEADS.length; h++) {
			int off = OFFSETS[h], n = SIZES[h], best = 0;
			for (int k = 1; k < n; k++) if (logits[off + k] > logits[off + best]) best = k;
			actionOut[HEADS[h]] = best;
		}
	}

	public static int nearestBin(float deltaDeg, float[] bins) {
		int best = 0; float err = Math.abs(deltaDeg - bins[0]);
		for (int i = 1; i < bins.length; i++) { float e = Math.abs(deltaDeg - bins[i]); if (e < err) { err = e; best = i; } }
		return best;
	}

	public synchronized void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
			d.writeInt(MAGIC); d.writeLong(trainedSteps); d.writeFloat(lastAcc); d.writeLong(params.adamSteps);
			d.writeInt(params.params().size());
			for (Param p : params.params()) { d.writeUTF(p.name); d.writeInt(p.w.length); for (float v : p.w) d.writeFloat(v); }
		}
	}

	public synchronized boolean load(Path path) throws IOException {
		if (!Files.exists(path)) return false;
		try (DataInputStream d = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			if (d.readInt() != MAGIC) return false;
			trainedSteps = d.readLong(); lastAcc = d.readFloat(); params.adamSteps = d.readLong();
			int n = d.readInt();
			for (int i = 0; i < n; i++) {
				String name = d.readUTF(); int len = d.readInt(); float[] w = new float[len];
				for (int k = 0; k < len; k++) w[k] = d.readFloat();
				for (Param p : params.params()) if (p.name.equals(name) && p.w.length == len) System.arraycopy(w, 0, p.w, 0, len);
			}
		}
		return true;
	}

	private static void relu(float[] a) { for (int i = 0; i < a.length; i++) if (a[i] < 0f) a[i] = 0f; }
	private static void gate(float[] g, float[] a) { for (int i = 0; i < g.length; i++) if (a[i] <= 0f) g[i] = 0f; }
}
