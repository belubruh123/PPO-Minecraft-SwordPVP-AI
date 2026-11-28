package dev.swordai.core.nn;

import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;

import java.util.Arrays;
import java.util.Random;

/**
 * AlphaStar-style permutation-equivariant Inventory Slot Encoder + Attention Pooling + Pointer Head
 * (Vinyals et al., Nature 2019; Berner et al., 2019 OpenAI Five).
 *
 * <p>Every inventory slot (9 hotbar + 1 offhand + 27 backpack = 37) passes through the exact same
 * shared MLP. Attention + max pooling produces a {@link #KIT_DIM}-dim permutation-invariant kit summary,
 * and a dot-product Pointer Head scores hotbar keys 1..9 ({@code q · k_s / sqrt(D)}) so selecting an item
 * is mathematically invariant to hotbar slot order.
 */
public final class SlotEncoder {
	public static final int SLOT_DIM = 32;
	public static final int KIT_DIM = 2 * SLOT_DIM;
	/** AlphaStar invalid-action mask logit for empty or already-held hotbar slots (finite even after /PLAY_TEMP). */
	public static final float MASK_LOGIT = -1e6f;
	private static final float INV_SQRT_D = (float) (1.0 / Math.sqrt(SLOT_DIM));
	private static final int HOTBAR_OFF = ActionSpace.OFFSETS[ActionSpace.HOTBAR_SLOT];

	public final Dense slotIn;
	public final Dense slotKey;
	public final Dense slotAttn;
	public final Dense ptrQuery;
	public final Dense ptrKeep;

	public SlotEncoder(ParamSet ps, String name, int topWidth, Random rng) {
		float relu = (float) Math.sqrt(2.0);
		slotIn = new Dense(ps, name + ".in", ObsSpec.SLOT_FEATS, SLOT_DIM, rng, relu);
		// Small positive ReLU bias avoids dead-neuron kinks on all-zero empty hotbar slots while keeping Net2Net zero-output exact.
		Arrays.fill(slotIn.bias.w, 0.05f);
		slotKey = new Dense(ps, name + ".key", SLOT_DIM, SLOT_DIM, rng, 1f);
		slotAttn = new Dense(ps, name + ".attn", SLOT_DIM, 1, rng, 0.1f);
		// Zero-init pointer query & keep weights (+3.2f keep bias -> ~92% keep when 2 other items exist, 100% when only 1 item).
		ptrQuery = new Dense(ps, name + ".ptrQ", topWidth, SLOT_DIM, rng, 0f);
		ptrKeep = new Dense(ps, name + ".ptrKeep", topWidth, 1, rng, 0f);
		ptrKeep.bias.w[0] = 3.2f;
	}

	public final class Cache {
		final boolean[] encoded = new boolean[ObsSpec.TOTAL_SLOTS];
		final boolean[] present = new boolean[ObsSpec.TOTAL_SLOTS];
		public final boolean[] masked = new boolean[ObsSpec.HOTBAR_SLOTS];
		final float[][] emb = new float[ObsSpec.TOTAL_SLOTS][SLOT_DIM];
		final float[][] key = new float[ObsSpec.HOTBAR_SLOTS][SLOT_DIM];
		final float[] attnLogit = new float[ObsSpec.TOTAL_SLOTS];
		final float[] attnWeight = new float[ObsSpec.TOTAL_SLOTS];
		final int[] maxIdx = new int[SLOT_DIM];
		public final float[] kitCtx = new float[KIT_DIM];
		final float[] query = new float[SLOT_DIM];
		final float[] keepOut = new float[1];
		int presentCount;
	}

	public final class Scratch {
		final float[] dEmb = new float[SLOT_DIM];
		final float[] dEmbTmp = new float[SLOT_DIM];
		final float[] dKey = new float[SLOT_DIM];
		final float[] dQ = new float[SLOT_DIM];
		final float[] dKeep = new float[1];
		final float[] dTopTmp;
		final float[] dAttnW = new float[ObsSpec.TOTAL_SLOTS];
		final float[] dAttnL = new float[1];
		final float[] tmp1 = new float[1];

		Scratch(int topWidth) {
			this.dTopTmp = new float[topWidth];
		}
	}

	public Cache newCache() { return new Cache(); }
	public Scratch newScratch() { return new Scratch(ptrQuery.in); }

	/** Encodes all active slots and writes the permutation-invariant {@link #KIT_DIM} kit context into {@code c.kitCtx}. */
	public void encodeSlots(float[] slots, Cache c, Scratch s) {
		Arrays.fill(c.kitCtx, 0f);
		Arrays.fill(c.attnWeight, 0f);
		Arrays.fill(c.maxIdx, -1);
		c.presentCount = 0;

		float maxA = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < ObsSpec.TOTAL_SLOTS; i++) {
			int off = i * ObsSpec.SLOT_FEATS;
			boolean pres = slots[off + ObsSpec.SF_PRESENT] >= 0.5f;
			c.present[i] = pres;
			if (i < ObsSpec.HOTBAR_SLOTS) {
				boolean selected = slots[off + ObsSpec.SF_SELECTED] >= 0.5f;
				// Mask empty bare-fist slots and the already-held slot so exploration only chooses real item switches.
				c.masked[i] = !pres || selected;
			}
			boolean need = pres;
			c.encoded[i] = need;
			if (!need) continue;

			float[] ei = c.emb[i];
			slotIn.forward(slots, off, ei, 0);
			for (int j = 0; j < SLOT_DIM; j++) if (ei[j] < 0f) ei[j] = 0f;
			if (i < ObsSpec.HOTBAR_SLOTS && !c.masked[i]) {
				slotKey.forward(ei, 0, c.key[i], 0);
			}
			if (pres) {
				slotAttn.forward(ei, 0, s.tmp1, 0);
				float a = s.tmp1[0];
				c.attnLogit[i] = a;
				if (a > maxA) maxA = a;
				if (c.presentCount == 0) {
					for (int j = 0; j < SLOT_DIM; j++) {
						c.kitCtx[SLOT_DIM + j] = ei[j];
						c.maxIdx[j] = i;
					}
				} else {
					for (int j = 0; j < SLOT_DIM; j++) {
						if (ei[j] > c.kitCtx[SLOT_DIM + j]) {
							c.kitCtx[SLOT_DIM + j] = ei[j];
							c.maxIdx[j] = i;
						}
					}
				}
				c.presentCount++;
			}
		}

		if (c.presentCount > 0) {
			float sum = 0f;
			for (int i = 0; i < ObsSpec.TOTAL_SLOTS; i++) {
				if (!c.present[i]) continue;
				float e = (float) Math.exp(c.attnLogit[i] - maxA);
				c.attnWeight[i] = e;
				sum += e;
			}
			float inv = 1f / sum;
			for (int i = 0; i < ObsSpec.TOTAL_SLOTS; i++) {
				if (!c.present[i]) continue;
				float w = c.attnWeight[i] * inv;
				c.attnWeight[i] = w;
				float[] ei = c.emb[i];
				for (int j = 0; j < SLOT_DIM; j++) c.kitCtx[j] += w * ei[j];
			}
		}
	}

	/** Writes the permutation-equivariant {@code HOTBAR_SLOT} logits (0=keep, 1..9=hotbar slots 0..8, empty/selected masked). */
	public void writePointerLogits(float[] top, float[] logitsOut, Cache c) {
		ptrQuery.forward(top, 0, c.query, 0);
		ptrKeep.forward(top, 0, c.keepOut, 0);
		logitsOut[HOTBAR_OFF] = c.keepOut[0];
		for (int sl = 0; sl < ObsSpec.HOTBAR_SLOTS; sl++) {
			if (c.masked[sl]) {
				logitsOut[HOTBAR_OFF + 1 + sl] = MASK_LOGIT;
				continue;
			}
			float dot = 0f;
			float[] ks = c.key[sl];
			for (int j = 0; j < SLOT_DIM; j++) dot += c.query[j] * ks[j];
			logitsOut[HOTBAR_OFF + 1 + sl] = dot * INV_SQRT_D;
		}
	}

	/**
	 * Step 1 of backward (BEFORE MoE backward): backpropagates {@code dLogits[HOTBAR_OFF..HOTBAR_OFF+10)}
	 * through {@code ptrKeep} and {@code ptrQuery}, ACCUMULATING into {@code dTop[0..topWidth)} and {@code grads}.
	 */
	public void backwardPointerTop(float[] top, float[] dLogits, Cache c, Scratch s, float[] dTop, float[][] grads) {
		s.dKeep[0] = dLogits[HOTBAR_OFF];
		ptrKeep.backward(top, 0, s.dKeep, 0, s.dTopTmp, 0, grads);
		for (int i = 0; i < dTop.length; i++) dTop[i] += s.dTopTmp[i];

		Arrays.fill(s.dQ, 0f);
		for (int sl = 0; sl < ObsSpec.HOTBAR_SLOTS; sl++) {
			if (c.masked[sl]) continue;
			float dl = dLogits[HOTBAR_OFF + 1 + sl] * INV_SQRT_D;
			float[] ks = c.key[sl];
			for (int j = 0; j < SLOT_DIM; j++) s.dQ[j] += dl * ks[j];
		}
		ptrQuery.backward(top, 0, s.dQ, 0, s.dTopTmp, 0, grads);
		for (int i = 0; i < dTop.length; i++) dTop[i] += s.dTopTmp[i];
	}

	/**
	 * Step 2 of backward (AFTER MoE + kitNew + kitHeads have finished accumulating {@code dKitCtx}):
	 * backpropagates both {@code dLogits[HOTBAR_OFF+1..+10)} (through slotKey) and {@code dKitCtx[0..KIT_DIM)}
	 * (through attention + max pool) into {@code slotKey}, {@code slotAttn}, and {@code slotIn}.
	 */
	public void backwardSlots(float[] slots, float[] dLogits, float[] dKitCtx, Cache c, Scratch s, float[][] grads) {
		float attnDot = 0f;
		if (c.presentCount > 0) {
			for (int i = 0; i < ObsSpec.TOTAL_SLOTS; i++) {
				if (!c.present[i]) continue;
				float dw = 0f;
				float[] ei = c.emb[i];
				for (int j = 0; j < SLOT_DIM; j++) dw += dKitCtx[j] * ei[j];
				s.dAttnW[i] = dw;
				attnDot += c.attnWeight[i] * dw;
			}
		}

		for (int i = 0; i < ObsSpec.TOTAL_SLOTS; i++) {
			if (!c.encoded[i]) continue;
			Arrays.fill(s.dEmb, 0f);
			float[] ei = c.emb[i];

			if (i < ObsSpec.HOTBAR_SLOTS && !c.masked[i]) {
				float dl = dLogits[HOTBAR_OFF + 1 + i] * INV_SQRT_D;
				for (int j = 0; j < SLOT_DIM; j++) s.dKey[j] = dl * c.query[j];
				slotKey.backward(ei, 0, s.dKey, 0, s.dEmbTmp, 0, grads);
				for (int j = 0; j < SLOT_DIM; j++) s.dEmb[j] += s.dEmbTmp[j];
			}
			if (c.present[i]) {
				float w = c.attnWeight[i];
				for (int j = 0; j < SLOT_DIM; j++) {
					s.dEmb[j] += w * dKitCtx[j];
					if (c.maxIdx[j] == i) s.dEmb[j] += dKitCtx[SLOT_DIM + j];
				}
				s.dAttnL[0] = w * (s.dAttnW[i] - attnDot);
				slotAttn.backward(ei, 0, s.dAttnL, 0, s.dEmbTmp, 0, grads);
				for (int j = 0; j < SLOT_DIM; j++) s.dEmb[j] += s.dEmbTmp[j];
			}
			for (int j = 0; j < SLOT_DIM; j++) if (ei[j] <= 0f) s.dEmb[j] = 0f;
			slotIn.backward(slots, i * ObsSpec.SLOT_FEATS, s.dEmb, 0, null, 0, grads);
		}
	}

	/** Convenience combined backward for standalone unit tests. */
	public void backward(float[] slots, float[] top, float[] dLogits, float[] dKitCtx,
			Cache c, Scratch s, float[] dTop, float[][] grads) {
		backwardPointerTop(top, dLogits, c, s, dTop, grads);
		backwardSlots(slots, dLogits, dKitCtx, c, s, grads);
	}
}
