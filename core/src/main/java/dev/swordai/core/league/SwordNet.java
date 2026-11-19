package dev.swordai.core.league;

import dev.swordai.core.nn.Conv2d;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.Gru;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.Policy;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The unified recurrent policy network for SwordAI:
 * - Two lightweight CNNs over periphery (48x27x2) and fovea (32x32x2) opponent masks.
 * - Vector branch over self + opponent scalars (including Step D vertical velocity, falling,
 *   sword cooldown, combo streak, exchange hit counts, and crit-chain counters).
 * - Short-term key/seen history plus a 128-unit GRU recurrent layer ({@link Gru}) for unbounded
 *   fight timing (stuns, combos, cooldowns, crit-back chains), trained with truncated BPTT.
 * - Dual residual towers (128 + 128) and autoregressive action heads (jump conditioned on W/S and
 *   sprint; attack conditioned on W/S, sprint, and jump).
 * - Net2Net function-preserving weight surgery ({@link #takeOverFromSchool}) from distilled
 *   legacy checkpoints so no skill is lost when adding memory or observations.
 */
public final class SwordNet {
	static final int BRANCH = 64;
	public static final int OLD = 128;
	public static final int NEW = 128;
	public static final int TOP = OLD + NEW;
	static final int BLOCK_HIDDEN = 256;
	/** Vision (peri + fovea) + vector branch: 3 * 64 = 192. */
	public static final int CAT = 3 * BRANCH;
	static final int AR_JUMP = 5;
	static final int AR_ATTACK = 7;
	private static final int JUMP_AT = ActionSpace.OFFSETS[ActionSpace.JUMP];
	private static final int ATTACK_AT = ActionSpace.OFFSETS[ActionSpace.ATTACK];

	public final ParamSet params = new ParamSet();
	private final Conv2d periConv1;
	private final Conv2d periConv2;
	private final Dense periDense;
	private final Conv2d foveaConv1;
	private final Conv2d foveaConv2;
	private final Dense foveaDense;
	private final Dense vecDense;
	private final Dense oldTrunk;
	private final Dense vecWideIn;
	private final Dense vecWideOut;
	private final ResBlock[] oldBlocks = new ResBlock[2];
	private final Dense newTrunk;
	private final ResBlock[] newBlocks = new ResBlock[3];
	private final Dense histIn;
	private final Dense histHeads;
	private final Dense seenIn;
	private final Dense seenHeads;
	private final Gru gru;
	private final Dense gruNew;
	private final Dense gruHeads;
	private final Dense heads;
	private final Dense arJump;
	private final Dense arAttack;
	/** Stage 2 keeps GRU bypassed (weights stay 0); Stage 3+ enables full GRU recurrent BPTT. */
	public boolean gruEnabled = true;

	public SwordNet(long seed) {
		Random rng = new Random(seed);
		float relu = (float) Math.sqrt(2.0);
		periConv1 = new Conv2d(params, "peri.conv1", ObsSpec.PERI_H, ObsSpec.PERI_W, ObsSpec.CHANNELS, 4, 2, 8, rng);
		periConv2 = new Conv2d(params, "peri.conv2", periConv1.outH, periConv1.outW, 8, 3, 2, 16, rng);
		periDense = new Dense(params, "peri.dense", periConv2.outSize(), BRANCH, rng, relu);
		foveaConv1 = new Conv2d(params, "fovea.conv1", ObsSpec.FOVEA, ObsSpec.FOVEA, ObsSpec.CHANNELS, 4, 2, 8, rng);
		foveaConv2 = new Conv2d(params, "fovea.conv2", foveaConv1.outH, foveaConv1.outW, 8, 3, 2, 16, rng);
		foveaDense = new Dense(params, "fovea.dense", foveaConv2.outSize(), BRANCH, rng, relu);
		vecDense = new Dense(params, "vec.dense", ObsSpec.VEC, BRANCH, rng, relu);
		oldTrunk = new Dense(params, "trunk", CAT, OLD, rng, relu);
		vecWideIn = new Dense(params, "vec.wide", ObsSpec.VEC, 64, rng, relu);
		vecWideOut = new Dense(params, "vec.wide.out", 64, OLD, rng, 0f);
		for (int b = 0; b < oldBlocks.length; b++) {
			oldBlocks[b] = new ResBlock(params, "block" + b, OLD, BLOCK_HIDDEN, rng);
		}
		newTrunk = new Dense(params, "new.trunk", CAT, NEW, rng, relu);
		for (int b = 0; b < newBlocks.length; b++) {
			newBlocks[b] = new ResBlock(params, "new.block" + b, NEW, BLOCK_HIDDEN, rng);
		}
		histIn = new Dense(params, "new.hist", ObsMemory.HIST, NEW, rng, 0f);
		histHeads = new Dense(params, "hist.heads", ObsMemory.HIST, ActionSpace.TOTAL_LOGITS, rng, 0f);
		seenIn = new Dense(params, "new.seen", ObsMemory.SEEN, NEW, rng, 0f);
		seenHeads = new Dense(params, "seen.heads", ObsMemory.SEEN, ActionSpace.TOTAL_LOGITS, rng, 0f);
		heads = new Dense(params, "heads", TOP, ActionSpace.TOTAL_LOGITS, rng, 0.01f);
		arJump = new Dense(params, "ar.jump", AR_JUMP, ActionSpace.SIZES[ActionSpace.JUMP], rng, 0f);
		arAttack = new Dense(params, "ar.attack", AR_ATTACK, ActionSpace.SIZES[ActionSpace.ATTACK], rng, 0f);
		// Recurrent GRU layer added last; projections into the network start at zero (Net2Net identity).
		gru = new Gru(params, "gru", CAT, ObsMemory.GRU_HIDDEN, rng);
		gruNew = new Dense(params, "gru.new", ObsMemory.GRU_HIDDEN, NEW, rng, 0f);
		gruHeads = new Dense(params, "gru.heads", ObsMemory.GRU_HIDDEN, ActionSpace.TOTAL_LOGITS, rng, 0f);
	}

	/** Per-step saved activations for truncated BPTT. */
	public final class StepCache {
		final float[] p1 = new float[periConv1.outSize()];
		final float[] p2 = new float[periConv2.outSize()];
		final float[] f1 = new float[foveaConv1.outSize()];
		final float[] f2 = new float[foveaConv2.outSize()];
		final float[] cat = new float[CAT];
		final float[] wide = new float[64];
		final float[] oldT = new float[OLD];
		final float[] newT = new float[NEW];
		final float[] top = new float[TOP];
		final float[] hOut = new float[ObsMemory.GRU_HIDDEN];
		final Gru.StepCache gruCache = gru.newStepCache();
		final ResBlock.Cache[] oldCaches = new ResBlock.Cache[oldBlocks.length];
		final ResBlock.Cache[] newCaches = new ResBlock.Cache[newBlocks.length];
		final float[] base = new float[ActionSpace.TOTAL_LOGITS];
		final float[] arJumpIn = new float[AR_JUMP];
		final float[] arAttackIn = new float[AR_ATTACK];
		public final float[] logits = new float[ActionSpace.TOTAL_LOGITS];

		StepCache() {
			for (int b = 0; b < oldBlocks.length; b++) {
				oldCaches[b] = oldBlocks[b].newCache();
			}
			for (int b = 0; b < newBlocks.length; b++) {
				newCaches[b] = newBlocks[b].newCache();
			}
		}
	}

	/** Per-thread scratch buffers and gradient accumulators. */
	public final class Workspace {
		public final StepCache step = new StepCache();
		final float[] widePre = new float[OLD];
		final float[] oldPre = new float[OLD];
		final float[] newPre = new float[NEW];
		final float[] histPre = new float[NEW];
		final float[] seenPre = new float[NEW];
		final float[] gruPre = new float[NEW];
		final float[] histLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] seenLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] gruLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] arOut = new float[2];
		final float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		final int[] act = new int[ActionSpace.HEADS];

		final float[] dTop = new float[TOP];
		final float[] dOld = new float[OLD];
		final float[] dNew = new float[NEW];
		final float[] dCat = new float[CAT];
		final float[] dCatPart = new float[CAT];
		final float[] dCatGru = new float[CAT];
		final float[] dH = new float[ObsMemory.GRU_HIDDEN];
		final float[] dHPart = new float[ObsMemory.GRU_HIDDEN];
		final float[] dWide = new float[64];
		final float[] dP1 = new float[step.p1.length];
		final float[] dP2 = new float[step.p2.length];
		final float[] dF1 = new float[step.f1.length];
		final float[] dF2 = new float[step.f2.length];
		final Gru.Scratch gruScratch = gru.newScratch();
		public final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];
		public final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	public StepCache newStepCache() {
		return new StepCache();
	}

	/** Forward pass for one tick, writing new GRU state to {@code hOut} and logits to {@code sc.logits}. */
	public void forward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] hist, float[] seen,
			float[] hPrev, float[] hOut) {
		periConv1.forwardBytes(peri, sc.p1);
		relu(sc.p1);
		periConv2.forward(sc.p1, sc.p2);
		relu(sc.p2);
		periDense.forward(sc.p2, 0, sc.cat, 0);
		foveaConv1.forwardBytes(fovea, sc.f1);
		relu(sc.f1);
		foveaConv2.forward(sc.f1, sc.f2);
		relu(sc.f2);
		foveaDense.forward(sc.f2, 0, sc.cat, BRANCH);
		vecDense.forward(vec, 0, sc.cat, 2 * BRANCH);
		relu(sc.cat);

		if (gruEnabled) {
			gru.forward(sc.cat, 0, hPrev, sc.hOut, sc.gruCache);
		} else {
			Arrays.fill(sc.hOut, 0f);
		}
		if (hOut != null) {
			System.arraycopy(sc.hOut, 0, hOut, 0, ObsMemory.GRU_HIDDEN);
		}

		oldTrunk.forward(sc.cat, 0, ws.oldPre, 0);
		vecWideIn.forward(vec, 0, sc.wide, 0);
		relu(sc.wide);
		vecWideOut.forward(sc.wide, 0, ws.widePre, 0);
		for (int i = 0; i < OLD; i++) {
			sc.oldT[i] = Math.max(0f, ws.oldPre[i] + ws.widePre[i]);
		}

		newTrunk.forward(sc.cat, 0, ws.newPre, 0);
		histIn.forward(hist, 0, ws.histPre, 0);
		seenIn.forward(seen, 0, ws.seenPre, 0);
		gruNew.forward(sc.hOut, 0, ws.gruPre, 0);
		for (int i = 0; i < NEW; i++) {
			sc.newT[i] = Math.max(0f, ws.newPre[i] + ws.histPre[i] + ws.seenPre[i] + ws.gruPre[i]);
		}

		System.arraycopy(sc.oldT, 0, sc.top, 0, OLD);
		for (int b = 0; b < oldBlocks.length; b++) {
			oldBlocks[b].forward(sc.top, 0, sc.oldCaches[b]);
		}
		System.arraycopy(sc.newT, 0, sc.top, OLD, NEW);
		for (int b = 0; b < newBlocks.length; b++) {
			newBlocks[b].forward(sc.top, OLD, sc.newCaches[b]);
		}

		heads.forward(sc.top, 0, sc.base, 0);
		histHeads.forward(hist, 0, ws.histLogits, 0);
		seenHeads.forward(seen, 0, ws.seenLogits, 0);
		gruHeads.forward(sc.hOut, 0, ws.gruLogits, 0);
		for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
			sc.base[i] += ws.histLogits[i] + ws.seenLogits[i] + ws.gruLogits[i];
		}
		System.arraycopy(sc.base, 0, sc.logits, 0, ActionSpace.TOTAL_LOGITS);
	}

	public void conditionJump(Workspace ws, StepCache sc, int[] action) {
		Arrays.fill(sc.arJumpIn, 0f);
		sc.arJumpIn[action[ActionSpace.FORWARD]] = 1f;
		sc.arJumpIn[3 + action[ActionSpace.SPRINT]] = 1f;
		arJump.forward(sc.arJumpIn, 0, ws.arOut, 0);
		sc.logits[JUMP_AT] = sc.base[JUMP_AT] + ws.arOut[0];
		sc.logits[JUMP_AT + 1] = sc.base[JUMP_AT + 1] + ws.arOut[1];
	}

	public void conditionAttack(Workspace ws, StepCache sc, int[] action) {
		Arrays.fill(sc.arAttackIn, 0f);
		sc.arAttackIn[action[ActionSpace.FORWARD]] = 1f;
		sc.arAttackIn[3 + action[ActionSpace.SPRINT]] = 1f;
		sc.arAttackIn[5 + action[ActionSpace.JUMP]] = 1f;
		arAttack.forward(sc.arAttackIn, 0, ws.arOut, 0);
		sc.logits[ATTACK_AT] = sc.base[ATTACK_AT] + ws.arOut[0];
		sc.logits[ATTACK_AT + 1] = sc.base[ATTACK_AT + 1] + ws.arOut[1];
	}

	public void condition(Workspace ws, StepCache sc, byte[] action) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			ws.act[h] = action[h];
		}
		conditionJump(ws, sc, ws.act);
		conditionAttack(ws, sc, ws.act);
	}

	/** Autoregressively samples all unlocked heads; returns log pi(action). */
	public float sample(Workspace ws, StepCache sc, int headMask, boolean greedy, float temperature, Random rng, int[] actionOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (h == ActionSpace.JUMP) {
				conditionJump(ws, sc, actionOut);
			} else if (h == ActionSpace.ATTACK) {
				conditionAttack(ws, sc, actionOut);
			}
			if (!ActionSpace.unlocked(headMask, h)) {
				actionOut[h] = ActionSpace.DEFAULTS[h];
				continue;
			}
			logp += pick(sc.logits, h, greedy, temperature, rng, ws.probs, actionOut);
		}
		return logp;
	}

	private static float pick(float[] logits, int h, boolean greedy, float temperature, Random rng, float[] probs, int[] actionOut) {
		int off = ActionSpace.OFFSETS[h];
		int n = ActionSpace.SIZES[h];
		if (temperature != 1f && temperature > 0f) {
			for (int i = 0; i < n; i++) {
				probs[off + i] = logits[off + i] / temperature;
			}
			Policy.softmax(probs, off, n, probs);
		} else {
			Policy.softmax(logits, off, n, probs);
		}
		int choice = n - 1;
		if (greedy) {
			float best = -1f;
			for (int i = 0; i < n; i++) {
				if (probs[off + i] > best) {
					best = probs[off + i];
					choice = i;
				}
			}
		} else {
			float u = rng.nextFloat();
			float acc = 0f;
			for (int i = 0; i < n; i++) {
				acc += probs[off + i];
				if (u < acc) {
					choice = i;
					break;
				}
			}
		}
		actionOut[h] = choice;
		return (float) Math.log(Math.max(probs[off + choice], 1e-12f));
	}

	/**
	 * Back-propagates {@code ws.dLogits} and {@code dHNext} (gradient from later steps in the BPTT chunk;
	 * may be null) through one step, accumulating parameter gradients into {@code ws.grads} and writing
	 * gradient w.r.t. h_{t-1} into {@code dHPrevOut} (if non-null; safe when {@code dHPrevOut == dHNext}).
	 */
	public void backward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] hist, float[] seen,
			float[] dHNext, float[] dHPrevOut) {
		arJump.backward(sc.arJumpIn, 0, ws.dLogits, JUMP_AT, null, 0, ws.grads);
		arAttack.backward(sc.arAttackIn, 0, ws.dLogits, ATTACK_AT, null, 0, ws.grads);
		heads.backward(sc.top, 0, ws.dLogits, 0, ws.dTop, 0, ws.grads);
		histHeads.backward(hist, 0, ws.dLogits, 0, null, 0, ws.grads);
		seenHeads.backward(seen, 0, ws.dLogits, 0, null, 0, ws.grads);
		if (gruEnabled) {
			gruHeads.backward(sc.hOut, 0, ws.dLogits, 0, ws.dH, 0, ws.grads);
		} else {
			Arrays.fill(ws.dH, 0f);
		}

		for (int b = newBlocks.length - 1; b >= 0; b--) {
			newBlocks[b].backward(ws.dTop, OLD, sc.newCaches[b], ws.grads);
		}
		for (int b = oldBlocks.length - 1; b >= 0; b--) {
			oldBlocks[b].backward(ws.dTop, 0, sc.oldCaches[b], ws.grads);
		}
		System.arraycopy(ws.dTop, 0, ws.dOld, 0, OLD);
		System.arraycopy(ws.dTop, OLD, ws.dNew, 0, NEW);
		gate(ws.dOld, sc.oldT);
		gate(ws.dNew, sc.newT);

		histIn.backward(hist, 0, ws.dNew, 0, null, 0, ws.grads);
		seenIn.backward(seen, 0, ws.dNew, 0, null, 0, ws.grads);
		if (gruEnabled) {
			gruNew.backward(sc.hOut, 0, ws.dNew, 0, ws.dHPart, 0, ws.grads);
			for (int i = 0; i < ObsMemory.GRU_HIDDEN; i++) {
				ws.dH[i] += ws.dHPart[i] + (dHNext != null ? dHNext[i] : 0f);
			}
		}

		newTrunk.backward(sc.cat, 0, ws.dNew, 0, ws.dCat, 0, ws.grads);
		oldTrunk.backward(sc.cat, 0, ws.dOld, 0, ws.dCatPart, 0, ws.grads);
		float[] dhPrev = dHPrevOut != null ? dHPrevOut : ws.dHPart;
		if (gruEnabled) {
			gru.backward(sc.cat, 0, ws.dH, sc.gruCache, ws.gruScratch, ws.dCatGru, 0, dhPrev, ws.grads);
			for (int i = 0; i < CAT; i++) {
				ws.dCat[i] += ws.dCatPart[i] + ws.dCatGru[i];
			}
		} else {
			Arrays.fill(dhPrev, 0f);
			for (int i = 0; i < CAT; i++) {
				ws.dCat[i] += ws.dCatPart[i];
			}
		}

		vecWideOut.backward(sc.wide, 0, ws.dOld, 0, ws.dWide, 0, ws.grads);
		gate(ws.dWide, sc.wide);
		vecWideIn.backward(vec, 0, ws.dWide, 0, null, 0, ws.grads);

		gate(ws.dCat, sc.cat);
		vecDense.backward(vec, 0, ws.dCat, 2 * BRANCH, null, 0, ws.grads);
		foveaDense.backward(sc.f2, 0, ws.dCat, BRANCH, ws.dF2, 0, ws.grads);
		gate(ws.dF2, sc.f2);
		Arrays.fill(ws.dF1, 0f);
		foveaConv2.backward(sc.f1, ws.dF2, ws.dF1, ws.grads);
		gate(ws.dF1, sc.f1);
		foveaConv1.backwardBytes(fovea, ws.dF1, ws.grads);

		periDense.backward(sc.p2, 0, ws.dCat, 0, ws.dP2, 0, ws.grads);
		gate(ws.dP2, sc.p2);
		Arrays.fill(ws.dP1, 0f);
		periConv2.backward(sc.p1, ws.dP2, ws.dP1, ws.grads);
		gate(ws.dP1, sc.p1);
		periConv1.backwardBytes(peri, ws.dP1, ws.grads);
	}

	/**
	 * Weight surgery: carries over all matching weights from a distilled legacy school checkpoint
	 * ({@code SWSC} format) into this clean recurrent {@link SwordNet}. Inserts zero rows into
	 * {@code vec.dense.w} and {@code vec.wide.w} for any newly added Step D scalars, trims the old
	 * Habits rows from {@code trunk.w}/{@code new.trunk.w}, and leaves GRU projections at zero.
	 *
	 * @return number of float parameters copied
	 */
	public long takeOverFromSchool(Path schoolCheckpoint) throws IOException {
		Map<String, float[]> saved = new HashMap<>();
		int oldVec;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(schoolCheckpoint)))) {
			int magic = in.readInt();
			if (magic != 0x53575343) {
				throw new IOException("not a legacy SWSC checkpoint: " + schoolCheckpoint);
			}
			in.readInt(); // format
			oldVec = in.readInt();
			in.readInt(); // priv
			in.readInt(); // mem
			in.readInt(); // oppTruth
			in.readInt(); // totalLogits
			in.readLong(); // totalSteps
			in.readLong(); // updates
			in.readLong(); // actor adamSteps
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				String name = in.readUTF();
				int len = in.readInt();
				float[] w = new float[len];
				for (int k = 0; k < len; k++) {
					w[k] = in.readFloat();
				}
				// Skip Adam m and v arrays (2 * len floats).
				int skip = 8 * len;
				while (skip > 0) {
					int s = in.skipBytes(skip);
					if (s <= 0) {
						throw new IOException("unexpected EOF in " + schoolCheckpoint);
					}
					skip -= s;
				}
				saved.put(name, w);
			}
		}
		int inserted = ObsSpec.VEC - oldVec;
		int insertAt = ObsSpec.VEC_SCALARS + (ObsSpec.SCALARS - Math.max(0, inserted));
		long copied = 0;
		for (Param p : params.params()) {
			float[] from = saved.get(p.name);
			if (from == null) {
				continue;
			}
			if (from.length == p.w.length) {
				System.arraycopy(from, 0, p.w, 0, from.length);
				copied += from.length;
			} else if (inserted > 0 && (p.name.equals("vec.dense.w") || p.name.equals("vec.wide.w"))) {
				int cols = p.w.length / ObsSpec.VEC;
				if (from.length == oldVec * cols) {
					Arrays.fill(p.w, 0f);
					System.arraycopy(from, 0, p.w, 0, insertAt * cols);
					System.arraycopy(from, insertAt * cols, p.w, (insertAt + inserted) * cols, from.length - insertAt * cols);
					copied += from.length;
				}
			} else if ((p.name.equals("trunk.w") || p.name.equals("new.trunk.w")) && from.length > p.w.length) {
				// First CAT (192) rows correspond to peri + fovea + vec; old tail rows were Habits.
				System.arraycopy(from, 0, p.w, 0, p.w.length);
				copied += p.w.length;
			}
		}
		return copied;
	}

	/** L2 norm of GRU output projection weights (0 at init, grows as recurrent memory is used). */
	public float gruWeightNorm() {
		double s = 0;
		for (float v : gruNew.weight.w) {
			s += (double) v * v;
		}
		for (float v : gruHeads.weight.w) {
			s += (double) v * v;
		}
		return (float) Math.sqrt(s);
	}

	private static void relu(float[] a) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] < 0f) {
				a[i] = 0f;
			}
		}
	}

	private static void gate(float[] grad, float[] act) {
		for (int i = 0; i < grad.length; i++) {
			if (act[i] <= 0f) {
				grad[i] = 0f;
			}
		}
	}
}
