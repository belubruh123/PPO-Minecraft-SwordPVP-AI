package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Unified generalist multi-kit recurrent policy network (Sword -> SMP/UHC Phases 1-3):
 * - Periphery + Fovea CNNs + Observable Vector branch (exact Net2Net from sword-master.bin)
 * - AlphaStar Slot-Attention Encoder + permutation-equivariant Pointer Head for hotbar keys 1..9
 * - ANYmal-style 5x5 egocentric terrain heightmap + hazard encoder
 * - 256-unit GRU memory (Net2Net block-expanded from 128)
 * - 4-Expert Soft-Gated Mixture of Experts (DeepMind ICML 2024 + Switch Transformer load balance)
 * - Factorised + autoregressive heads (11 heads, 54 logits)
 */
public final class SwordNet {
	static final int BRANCH = 64, BLOCK_HIDDEN = 256, AR_JUMP = 5, AR_USE = 4, AR_ATTACK = 11, LEGACY_AR_ATTACK = 7, TERRAIN_DIM = 32;
	public static final int OLD = 128, NEW = 128, TOP = OLD + NEW, CAT = 3 * BRANCH;
	private static final int JUMP_AT = ActionSpace.OFFSETS[ActionSpace.JUMP];
	private static final int USE_AT = ActionSpace.OFFSETS[ActionSpace.USE_ITEM];
	private static final int ATTACK_AT = ActionSpace.OFFSETS[ActionSpace.ATTACK];
	private static final int HOTBAR_AT = ActionSpace.OFFSETS[ActionSpace.HOTBAR_SLOT];
	private static final int HOTBAR_SZ = ActionSpace.SIZES[ActionSpace.HOTBAR_SLOT];
	private static final int MOE_ROUTER_IN = TOP + SlotEncoder.KIT_DIM;
	/** AlphaStar autoregressive sampling order: movement -> item switches -> USE_ITEM -> ATTACK (prevents switch+swing collisions). */
	private static final int[] SAMPLE_ORDER = {
			ActionSpace.YAW, ActionSpace.PITCH, ActionSpace.FORWARD, ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.JUMP,
			ActionSpace.HOTBAR_SLOT, ActionSpace.OFFHAND_SWAP, ActionSpace.USE_ITEM, ActionSpace.ATTACK, ActionSpace.INVENTORY
	};
	private static final float[] EMPTY_SLOTS = new float[ObsSpec.SLOTS_SIZE];
	private static final float[] EMPTY_TERRAIN = new float[ObsSpec.TERRAIN_SIZE];

	public final ParamSet params = new ParamSet();
	private final Conv2d periConv1, periConv2, foveaConv1, foveaConv2;
	private final Dense periDense, foveaDense, vecDense, oldTrunk, vecWideIn, vecWideOut, newTrunk;
	private final Dense histIn, histHeads, seenIn, seenHeads, gruNew, gruHeads, heads, arJump, arUse, arAttack;
	private final ResBlock[] oldBlocks = new ResBlock[2], newBlocks = new ResBlock[3];
	private final Gru gru;
	// Frontier AI-lab generalist additions (all zero-output at init for exact bit-for-bit Net2Net from sword-master.bin)
	public final SlotEncoder slotEncoder;
	private final Dense kitNew, kitHeads;
	private final Dense terrIn, terrNew, terrHeads;
	public final MoEBlock moe;
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
		for (int b = 0; b < oldBlocks.length; b++) oldBlocks[b] = new ResBlock(params, "block" + b, OLD, BLOCK_HIDDEN, rng);
		newTrunk = new Dense(params, "new.trunk", CAT, NEW, rng, relu);
		for (int b = 0; b < newBlocks.length; b++) newBlocks[b] = new ResBlock(params, "new.block" + b, NEW, BLOCK_HIDDEN, rng);
		histIn = new Dense(params, "new.hist", ObsMemory.HIST, NEW, rng, 0f);
		histHeads = new Dense(params, "hist.heads", ObsMemory.HIST, ActionSpace.TOTAL_LOGITS, rng, 0f);
		seenIn = new Dense(params, "new.seen", ObsMemory.SEEN, NEW, rng, 0f);
		seenHeads = new Dense(params, "seen.heads", ObsMemory.SEEN, ActionSpace.TOTAL_LOGITS, rng, 0f);
		heads = new Dense(params, "heads", TOP, ActionSpace.TOTAL_LOGITS, rng, 0.01f);
		arJump = new Dense(params, "ar.jump", AR_JUMP, ActionSpace.SIZES[ActionSpace.JUMP], rng, 0f);
		arUse = new Dense(params, "ar.use", AR_USE, ActionSpace.SIZES[ActionSpace.USE_ITEM], rng, 0f);
		arAttack = new Dense(params, "ar.attack", AR_ATTACK, ActionSpace.SIZES[ActionSpace.ATTACK], rng, 0f);
		gru = new Gru(params, "gru", CAT, ObsMemory.GRU_HIDDEN, rng);
		gruNew = new Dense(params, "gru.new", ObsMemory.GRU_HIDDEN, NEW, rng, 0f);
		gruHeads = new Dense(params, "gru.heads", ObsMemory.GRU_HIDDEN, ActionSpace.TOTAL_LOGITS, rng, 0f);

		slotEncoder = new SlotEncoder(params, "slot", TOP, rng);
		kitNew = new Dense(params, "kit.new", SlotEncoder.KIT_DIM, NEW, rng, 0f);
		kitHeads = new Dense(params, "kit.heads", SlotEncoder.KIT_DIM, ActionSpace.TOTAL_LOGITS, rng, 0f);
		terrIn = new Dense(params, "terr.in", ObsSpec.TERRAIN_SIZE, TERRAIN_DIM, rng, relu);
		terrNew = new Dense(params, "terr.new", TERRAIN_DIM, NEW, rng, 0f);
		terrHeads = new Dense(params, "terr.heads", TERRAIN_DIM, ActionSpace.TOTAL_LOGITS, rng, 0f);
		moe = new MoEBlock(params, "moe", TOP, BLOCK_HIDDEN, MOE_ROUTER_IN, rng);
		initNeutralSmpBiases();
	}

	/** Calm 20Hz initial biases on newly added SMP heads so tick-0 Net2Net surgery explores without spamming slots/F/use. */
	void initNeutralSmpBiases() {
		heads.bias.w[ActionSpace.OFFSETS[ActionSpace.USE_ITEM]] = 2.6f;
		heads.bias.w[ActionSpace.OFFSETS[ActionSpace.USE_ITEM] + 1] = 0f;
		heads.bias.w[ActionSpace.OFFSETS[ActionSpace.OFFHAND_SWAP]] = 4.5f;
		heads.bias.w[ActionSpace.OFFSETS[ActionSpace.OFFHAND_SWAP] + 1] = 0f;
		heads.bias.w[ActionSpace.OFFSETS[ActionSpace.INVENTORY]] = 4.5f;
		heads.bias.w[ActionSpace.OFFSETS[ActionSpace.INVENTORY] + 1] = 0f;
	}

	public final class StepCache {
		final float[] p1 = new float[periConv1.outSize()], p2 = new float[periConv2.outSize()];
		final float[] f1 = new float[foveaConv1.outSize()], f2 = new float[foveaConv2.outSize()];
		final float[] cat = new float[CAT], wide = new float[64], oldT = new float[OLD], newT = new float[NEW];
		final float[] terrH = new float[TERRAIN_DIM];
		final float[] topPre = new float[TOP], moeRouterIn = new float[MOE_ROUTER_IN], top = new float[TOP];
		final float[] hOut = new float[ObsMemory.GRU_HIDDEN];
		final Gru.StepCache gruCache = gru.newStepCache();
		final SlotEncoder.Cache slotCache = slotEncoder.newCache();
		public final MoEBlock.Cache moeCache = moe.newCache();
		final ResBlock.Cache[] oldCaches = new ResBlock.Cache[oldBlocks.length], newCaches = new ResBlock.Cache[newBlocks.length];
		final float[] base = new float[ActionSpace.TOTAL_LOGITS], arJumpIn = new float[AR_JUMP], arUseIn = new float[AR_USE], arAttackIn = new float[AR_ATTACK];
		public final float[] logits = new float[ActionSpace.TOTAL_LOGITS];

		StepCache() {
			for (int b = 0; b < oldBlocks.length; b++) oldCaches[b] = oldBlocks[b].newCache();
			for (int b = 0; b < newBlocks.length; b++) newCaches[b] = newBlocks[b].newCache();
		}

		public float[] kitCtx() { return slotCache.kitCtx; }
	}

	public final class Workspace {
		public final StepCache step = new StepCache();
		final float[] widePre = new float[OLD], oldPre = new float[OLD], newPre = new float[NEW];
		final float[] histPre = new float[NEW], seenPre = new float[NEW], gruPre = new float[NEW];
		final float[] kitPre = new float[NEW], terrPre = new float[NEW];
		final float[] histLogits = new float[ActionSpace.TOTAL_LOGITS], seenLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] gruLogits = new float[ActionSpace.TOTAL_LOGITS], kitLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] terrLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] arOut = new float[2], probs = new float[ActionSpace.TOTAL_LOGITS];
		final int[] act = new int[ActionSpace.HEADS];
		final float[] dBaseLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] dTop = new float[TOP], dTopPre = new float[TOP], dMoeRouterIn = new float[MOE_ROUTER_IN];
		final float[] dOld = new float[OLD], dNew = new float[NEW];
		final float[] dKitCtx = new float[SlotEncoder.KIT_DIM], dKitTmp = new float[SlotEncoder.KIT_DIM];
		final float[] dTerrH = new float[TERRAIN_DIM], dTerrTmp = new float[TERRAIN_DIM];
		final float[] dCat = new float[CAT], dCatPart = new float[CAT], dCatGru = new float[CAT];
		final float[] dH = new float[ObsMemory.GRU_HIDDEN], dHPart = new float[ObsMemory.GRU_HIDDEN], dWide = new float[64];
		final float[] dP1 = new float[step.p1.length], dP2 = new float[step.p2.length];
		final float[] dF1 = new float[step.f1.length], dF2 = new float[step.f2.length];
		final Gru.Scratch gruScratch = gru.newScratch();
		final SlotEncoder.Scratch slotScratch = slotEncoder.newScratch();
		final MoEBlock.Scratch moeScratch = moe.newScratch();
		public final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];
		public final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() { return new Workspace(); }
	public StepCache newStepCache() { return new StepCache(); }

	/** Convenience overload with empty slots/terrain (for legacy sword-only probes/tests). */
	public void forward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] hist, float[] seen,
			float[] hPrev, float[] hOut) {
		forward(ws, sc, peri, fovea, vec, EMPTY_SLOTS, EMPTY_TERRAIN, hist, seen, hPrev, hOut);
	}

	public void forward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] slots, float[] terrain,
			float[] hist, float[] seen, float[] hPrev, float[] hOut) {
		float[] sl = slots != null ? slots : EMPTY_SLOTS;
		float[] tr = terrain != null ? terrain : EMPTY_TERRAIN;

		periConv1.forwardBytes(peri, sc.p1); relu(sc.p1);
		periConv2.forward(sc.p1, sc.p2); relu(sc.p2);
		periDense.forward(sc.p2, 0, sc.cat, 0);
		foveaConv1.forwardBytes(fovea, sc.f1); relu(sc.f1);
		foveaConv2.forward(sc.f1, sc.f2); relu(sc.f2);
		foveaDense.forward(sc.f2, 0, sc.cat, BRANCH);
		vecDense.forward(vec, 0, sc.cat, 2 * BRANCH);
		relu(sc.cat);

		slotEncoder.encodeSlots(sl, sc.slotCache, ws.slotScratch);
		terrIn.forward(tr, 0, sc.terrH, 0); relu(sc.terrH);

		if (gruEnabled) gru.forward(sc.cat, 0, hPrev, sc.hOut, sc.gruCache);
		else Arrays.fill(sc.hOut, 0f);
		if (hOut != null) System.arraycopy(sc.hOut, 0, hOut, 0, ObsMemory.GRU_HIDDEN);

		oldTrunk.forward(sc.cat, 0, ws.oldPre, 0);
		vecWideIn.forward(vec, 0, sc.wide, 0); relu(sc.wide);
		vecWideOut.forward(sc.wide, 0, ws.widePre, 0);
		for (int i = 0; i < OLD; i++) sc.oldT[i] = Math.max(0f, ws.oldPre[i] + ws.widePre[i]);

		newTrunk.forward(sc.cat, 0, ws.newPre, 0);
		histIn.forward(hist, 0, ws.histPre, 0);
		seenIn.forward(seen, 0, ws.seenPre, 0);
		kitNew.forward(sc.slotCache.kitCtx, 0, ws.kitPre, 0);
		terrNew.forward(sc.terrH, 0, ws.terrPre, 0);
		if (gruEnabled) gruNew.forward(sc.hOut, 0, ws.gruPre, 0); else Arrays.fill(ws.gruPre, 0f);
		for (int i = 0; i < NEW; i++) {
			sc.newT[i] = Math.max(0f, ws.newPre[i] + ws.histPre[i] + ws.seenPre[i] + ws.gruPre[i] + ws.kitPre[i] + ws.terrPre[i]);
		}

		System.arraycopy(sc.oldT, 0, sc.topPre, 0, OLD);
		for (int b = 0; b < oldBlocks.length; b++) oldBlocks[b].forward(sc.topPre, 0, sc.oldCaches[b]);
		System.arraycopy(sc.newT, 0, sc.topPre, OLD, NEW);
		for (int b = 0; b < newBlocks.length; b++) newBlocks[b].forward(sc.topPre, OLD, sc.newCaches[b]);

		// Soft-Gated 4-Expert MoE reading [topPre, kitCtx] (zero-down init -> exact Net2Net identity on tick 0)
		System.arraycopy(sc.topPre, 0, sc.moeRouterIn, 0, TOP);
		System.arraycopy(sc.slotCache.kitCtx, 0, sc.moeRouterIn, TOP, SlotEncoder.KIT_DIM);
		moe.forward(sc.topPre, sc.moeRouterIn, sc.top, sc.moeCache);

		heads.forward(sc.top, 0, sc.base, 0);
		histHeads.forward(hist, 0, ws.histLogits, 0);
		seenHeads.forward(seen, 0, ws.seenLogits, 0);
		kitHeads.forward(sc.slotCache.kitCtx, 0, ws.kitLogits, 0);
		terrHeads.forward(sc.terrH, 0, ws.terrLogits, 0);
		if (gruEnabled) gruHeads.forward(sc.hOut, 0, ws.gruLogits, 0); else Arrays.fill(ws.gruLogits, 0f);
		for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
			sc.base[i] += ws.histLogits[i] + ws.seenLogits[i] + ws.gruLogits[i] + ws.kitLogits[i] + ws.terrLogits[i];
		}
		// AlphaStar Pointer Head EXCLUSIVELY owns HOTBAR_SLOT logits -> strictly permutation-equivariant to hotbar order!
		slotEncoder.writePointerLogits(sc.top, sc.base, sc.slotCache);
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

	public void conditionUse(Workspace ws, StepCache sc, int[] action) {
		Arrays.fill(sc.arUseIn, 0f);
		int switched = (action[ActionSpace.HOTBAR_SLOT] != 0 || action[ActionSpace.OFFHAND_SWAP] == 1) ? 1 : 0;
		sc.arUseIn[switched] = 1f;
		sc.arUseIn[2 + action[ActionSpace.OFFHAND_SWAP]] = 1f;
		arUse.forward(sc.arUseIn, 0, ws.arOut, 0);
		sc.logits[USE_AT] = sc.base[USE_AT] + ws.arOut[0];
		sc.logits[USE_AT + 1] = sc.base[USE_AT + 1] + ws.arOut[1];
	}

	public void conditionAttack(Workspace ws, StepCache sc, int[] action) {
		Arrays.fill(sc.arAttackIn, 0f);
		int switched = (action[ActionSpace.HOTBAR_SLOT] != 0 || action[ActionSpace.OFFHAND_SWAP] == 1) ? 1 : 0;
		sc.arAttackIn[action[ActionSpace.FORWARD]] = 1f;
		sc.arAttackIn[3 + action[ActionSpace.SPRINT]] = 1f;
		sc.arAttackIn[5 + action[ActionSpace.JUMP]] = 1f;
		sc.arAttackIn[7 + switched] = 1f;
		sc.arAttackIn[9 + action[ActionSpace.USE_ITEM]] = 1f;
		arAttack.forward(sc.arAttackIn, 0, ws.arOut, 0);
		sc.logits[ATTACK_AT] = sc.base[ATTACK_AT] + ws.arOut[0];
		sc.logits[ATTACK_AT + 1] = sc.base[ATTACK_AT + 1] + ws.arOut[1];
	}

	public void condition(Workspace ws, StepCache sc, byte[] action) {
		for (int h = 0; h < ActionSpace.HEADS; h++) ws.act[h] = action[h];
		conditionJump(ws, sc, ws.act);
		conditionUse(ws, sc, ws.act);
		conditionAttack(ws, sc, ws.act);
	}

	public static final float[] PLAY_TEMP = {0.20f, 0.20f, 0.60f, 0.60f, 0.60f, 0.50f, 0.15f, 0.25f, 0.25f, 0.25f, 0.25f};

	public float samplePlay(Workspace ws, StepCache sc, Random rng, int[] actionOut) {
		return samplePlay(ws, sc, ActionSpace.PHASE1_HEADS, rng, actionOut);
	}

	public float samplePlay(Workspace ws, StepCache sc, int headMask, Random rng, int[] actionOut) {
		float logp = 0f;
		for (int h : SAMPLE_ORDER) {
			if (h == ActionSpace.JUMP) conditionJump(ws, sc, actionOut);
			else if (h == ActionSpace.USE_ITEM) conditionUse(ws, sc, actionOut);
			else if (h == ActionSpace.ATTACK) conditionAttack(ws, sc, actionOut);
			if (!ActionSpace.unlocked(headMask, h)) { actionOut[h] = ActionSpace.DEFAULTS[h]; continue; }
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h];
			float invT = 1f / PLAY_TEMP[h];
			for (int i = 0; i < n; i++) ws.probs[off + i] = sc.logits[off + i] * invT;
			Policy.softmax(ws.probs, off, n, ws.probs);
			float u = rng.nextFloat(), acc = 0f;
			int choice = 0;
			for (int i = 0; i < n; i++) {
				if (ws.probs[off + i] > 0f) choice = i;
				acc += ws.probs[off + i];
				if (u < acc) { choice = i; break; }
			}
			actionOut[h] = choice;
			logp += (float) Math.log(Math.max(ws.probs[off + choice], 1e-12f));
		}
		return logp;
	}

	public float sample(Workspace ws, StepCache sc, int headMask, boolean greedy, float temperature, Random rng, int[] actionOut) {
		float logp = 0f;
		for (int h : SAMPLE_ORDER) {
			if (h == ActionSpace.JUMP) conditionJump(ws, sc, actionOut);
			else if (h == ActionSpace.USE_ITEM) conditionUse(ws, sc, actionOut);
			else if (h == ActionSpace.ATTACK) conditionAttack(ws, sc, actionOut);
			if (!ActionSpace.unlocked(headMask, h)) { actionOut[h] = ActionSpace.DEFAULTS[h]; continue; }
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h];
			if (temperature != 1f && temperature > 0f) {
				for (int i = 0; i < n; i++) ws.probs[off + i] = sc.logits[off + i] / temperature;
				Policy.softmax(ws.probs, off, n, ws.probs);
			} else Policy.softmax(sc.logits, off, n, ws.probs);
			int choice = 0;
			if (greedy) {
				float best = -1f;
				for (int i = 0; i < n; i++) if (ws.probs[off + i] > best) { best = ws.probs[off + i]; choice = i; }
			} else {
				float u = rng.nextFloat(), acc = 0f;
				for (int i = 0; i < n; i++) {
					if (ws.probs[off + i] > 0f) choice = i;
					acc += ws.probs[off + i];
					if (u < acc) { choice = i; break; }
				}
			}
			actionOut[h] = choice;
			logp += (float) Math.log(Math.max(ws.probs[off + choice], 1e-12f));
		}
		return logp;
	}

	public void backward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] slots, float[] terrain,
			float[] hist, float[] seen, float[] dHNext, float[] dHPrevOut, float[] dMoeGateBalance) {
		float[] sl = slots != null ? slots : EMPTY_SLOTS;
		float[] tr = terrain != null ? terrain : EMPTY_TERRAIN;

		arJump.backward(sc.arJumpIn, 0, ws.dLogits, JUMP_AT, null, 0, ws.grads);
		arUse.backward(sc.arUseIn, 0, ws.dLogits, USE_AT, null, 0, ws.grads);
		arAttack.backward(sc.arAttackIn, 0, ws.dLogits, ATTACK_AT, null, 0, ws.grads);

		// Non-pointer heads ignore HOTBAR_SLOT (owned exclusively by SlotEncoder's Pointer Head).
		System.arraycopy(ws.dLogits, 0, ws.dBaseLogits, 0, ActionSpace.TOTAL_LOGITS);
		Arrays.fill(ws.dBaseLogits, HOTBAR_AT, HOTBAR_AT + HOTBAR_SZ, 0f);

		heads.backward(sc.top, 0, ws.dBaseLogits, 0, ws.dTop, 0, ws.grads);
		histHeads.backward(hist, 0, ws.dBaseLogits, 0, null, 0, ws.grads);
		seenHeads.backward(seen, 0, ws.dBaseLogits, 0, null, 0, ws.grads);
		kitHeads.backward(sc.slotCache.kitCtx, 0, ws.dBaseLogits, 0, ws.dKitCtx, 0, ws.grads);
		terrHeads.backward(sc.terrH, 0, ws.dBaseLogits, 0, ws.dTerrH, 0, ws.grads);
		if (gruEnabled) gruHeads.backward(sc.hOut, 0, ws.dBaseLogits, 0, ws.dH, 0, ws.grads);
		else Arrays.fill(ws.dH, 0f);

		// Pointer head contributes directly into dTop (must happen BEFORE MoE backward!), while kitCtx backward happens AFTER MoE+kitNew accumulate into dKitCtx!
		// Split Pointer-query backward vs SlotEncoder-kitCtx backward cleanly by running MoE -> trunks -> kitNew FIRST?
		// WAIT: SlotEncoder.backward reads dTop (accumulates into it) AND dKitCtx! So run ptrKeep+ptrQuery into dTop first?
		// No — MoE backward needs COMPLETE dTop (from BOTH heads AND ptrKeep/ptrQuery), and SlotEncoder needs COMPLETE dKitCtx (from kitHeads + MoE router + kitNew)!
		// Let's handle that cleanly: compute ptrKeep/ptrQuery -> dTop BEFORE MoE, OR pass dTop through MoE after adding pointer's dTop!
		// Wait: ptrKeep & ptrQuery depend on sc.top (post-MoE), while kitCtx feeds INTO MoE router and kitNew (pre-MoE)!
		// So the EXACT topological reverse order is:
		//   1. ptrKeep & ptrQuery -> accumulate into ws.dTop (and grads)
		//   2. MoE.backward(ws.dTop) -> ws.dTopPre + ws.dMoeRouterIn ([dTopPre, dKitCtx])
		//   3. oldBlocks / newBlocks / kitNew / terrNew -> accumulate into ws.dKitCtx, ws.dTerrH
		//   4. SlotEncoder slot-body backward (from ws.dLogits[HOTBAR] + complete ws.dKitCtx) -> grads!
		//   5. terrIn backward (from complete ws.dTerrH) -> grads!
		backwardPointerTop(ws, sc);

		Arrays.fill(ws.dTopPre, 0f);
		moe.backward(sc.moeRouterIn, ws.dTop, dMoeGateBalance, sc.moeCache, ws.moeScratch, ws.dTopPre, ws.dMoeRouterIn, ws.grads);
		for (int i = 0; i < TOP; i++) ws.dTopPre[i] += ws.dMoeRouterIn[i];
		for (int i = 0; i < SlotEncoder.KIT_DIM; i++) ws.dKitCtx[i] += ws.dMoeRouterIn[TOP + i];

		for (int b = newBlocks.length - 1; b >= 0; b--) newBlocks[b].backward(ws.dTopPre, OLD, sc.newCaches[b], ws.grads);
		for (int b = oldBlocks.length - 1; b >= 0; b--) oldBlocks[b].backward(ws.dTopPre, 0, sc.oldCaches[b], ws.grads);
		System.arraycopy(ws.dTopPre, 0, ws.dOld, 0, OLD); gate(ws.dOld, sc.oldT);
		System.arraycopy(ws.dTopPre, OLD, ws.dNew, 0, NEW); gate(ws.dNew, sc.newT);

		histIn.backward(hist, 0, ws.dNew, 0, null, 0, ws.grads);
		seenIn.backward(seen, 0, ws.dNew, 0, null, 0, ws.grads);
		kitNew.backward(sc.slotCache.kitCtx, 0, ws.dNew, 0, ws.dKitTmp, 0, ws.grads);
		for (int i = 0; i < SlotEncoder.KIT_DIM; i++) ws.dKitCtx[i] += ws.dKitTmp[i];
		terrNew.backward(sc.terrH, 0, ws.dNew, 0, ws.dTerrTmp, 0, ws.grads);
		for (int i = 0; i < TERRAIN_DIM; i++) ws.dTerrH[i] += ws.dTerrTmp[i];

		// Now dKitCtx and dTerrH are 100% complete! Finish SlotEncoder slot-body and terrIn backward:
		backwardSlotBody(ws, sc, sl);
		gate(ws.dTerrH, sc.terrH);
		terrIn.backward(tr, 0, ws.dTerrH, 0, null, 0, ws.grads);

		if (gruEnabled) {
			gruNew.backward(sc.hOut, 0, ws.dNew, 0, ws.dHPart, 0, ws.grads);
			for (int i = 0; i < ObsMemory.GRU_HIDDEN; i++) ws.dH[i] += ws.dHPart[i] + (dHNext != null ? dHNext[i] : 0f);
		}
		newTrunk.backward(sc.cat, 0, ws.dNew, 0, ws.dCat, 0, ws.grads);
		oldTrunk.backward(sc.cat, 0, ws.dOld, 0, ws.dCatPart, 0, ws.grads);
		float[] dhPrev = dHPrevOut != null ? dHPrevOut : ws.dHPart;
		if (gruEnabled) {
			gru.backward(sc.cat, 0, ws.dH, sc.gruCache, ws.gruScratch, ws.dCatGru, 0, dhPrev, ws.grads);
			for (int i = 0; i < CAT; i++) ws.dCat[i] += ws.dCatPart[i] + ws.dCatGru[i];
		} else {
			Arrays.fill(dhPrev, 0f);
			for (int i = 0; i < CAT; i++) ws.dCat[i] += ws.dCatPart[i];
		}

		vecWideOut.backward(sc.wide, 0, ws.dOld, 0, ws.dWide, 0, ws.grads); gate(ws.dWide, sc.wide);
		vecWideIn.backward(vec, 0, ws.dWide, 0, null, 0, ws.grads);

		gate(ws.dCat, sc.cat);
		vecDense.backward(vec, 0, ws.dCat, 2 * BRANCH, null, 0, ws.grads);
		foveaDense.backward(sc.f2, 0, ws.dCat, BRANCH, ws.dF2, 0, ws.grads); gate(ws.dF2, sc.f2);
		Arrays.fill(ws.dF1, 0f);
		foveaConv2.backward(sc.f1, ws.dF2, ws.dF1, ws.grads); gate(ws.dF1, sc.f1);
		foveaConv1.backwardBytes(fovea, ws.dF1, ws.grads);

		periDense.backward(sc.p2, 0, ws.dCat, 0, ws.dP2, 0, ws.grads); gate(ws.dP2, sc.p2);
		Arrays.fill(ws.dP1, 0f);
		periConv2.backward(sc.p1, ws.dP2, ws.dP1, ws.grads); gate(ws.dP1, sc.p1);
		periConv1.backwardBytes(peri, ws.dP1, ws.grads);
	}

	private void backwardPointerTop(Workspace ws, StepCache sc) {
		slotEncoder.backwardPointerTop(sc.top, ws.dLogits, sc.slotCache, ws.slotScratch, ws.dTop, ws.grads);
	}

	private void backwardSlotBody(Workspace ws, StepCache sc, float[] sl) {
		slotEncoder.backwardSlots(sl, ws.dLogits, ws.dKitCtx, sc.slotCache, ws.slotScratch, ws.grads);
	}

	public float gruWeightNorm() {
		double s = 0;
		for (float v : gruNew.weight.w) s += (double) v * v;
		for (float v : gruHeads.weight.w) s += (double) v * v;
		return (float) Math.sqrt(s);
	}

	/** Marks untrained GRU parameters as born at {@code adamStep} so Adam bias-correction avoids a cold-start jump. */
	public void markGruBorn(long adamStep) {
		for (Param p : params.params()) {
			if (p.name.startsWith("gru.")) p.bornAt = adamStep;
		}
	}

	/** Marks all newly added SMP/MoE/Slot/Terrain parameters as born at {@code adamStep} after Net2Net surgery. */
	public void markSmpBorn(long adamStep) {
		for (Param p : params.params()) {
			if (p.name.startsWith("slot.") || p.name.startsWith("kit.")
					|| p.name.startsWith("terr.") || p.name.startsWith("moe.")
					|| p.name.startsWith("ar.use.")) {
				p.bornAt = adamStep;
			}
		}
	}

	private static void relu(float[] a) { for (int i = 0; i < a.length; i++) if (a[i] < 0f) a[i] = 0f; }
	private static void gate(float[] g, float[] a) { for (int i = 0; i < g.length; i++) if (a[i] <= 0f) g[i] = 0f; }
}
