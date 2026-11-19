package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Unified recurrent policy network (periphery+fovea CNNs, vector branch, dual ResBlocks, 128-unit GRU, autoregressive heads). */
public final class SwordNet {
	static final int BRANCH = 64, BLOCK_HIDDEN = 256, AR_JUMP = 5, AR_ATTACK = 7;
	public static final int OLD = 128, NEW = 128, TOP = OLD + NEW, CAT = 3 * BRANCH;
	private static final int JUMP_AT = ActionSpace.OFFSETS[ActionSpace.JUMP];
	private static final int ATTACK_AT = ActionSpace.OFFSETS[ActionSpace.ATTACK];

	public final ParamSet params = new ParamSet();
	private final Conv2d periConv1, periConv2, foveaConv1, foveaConv2;
	private final Dense periDense, foveaDense, vecDense, oldTrunk, vecWideIn, vecWideOut, newTrunk;
	private final Dense histIn, histHeads, seenIn, seenHeads, gruNew, gruHeads, heads, arJump, arAttack;
	private final ResBlock[] oldBlocks = new ResBlock[2], newBlocks = new ResBlock[3];
	private final Gru gru;
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
		arAttack = new Dense(params, "ar.attack", AR_ATTACK, ActionSpace.SIZES[ActionSpace.ATTACK], rng, 0f);
		gru = new Gru(params, "gru", CAT, ObsMemory.GRU_HIDDEN, rng);
		gruNew = new Dense(params, "gru.new", ObsMemory.GRU_HIDDEN, NEW, rng, 0f);
		gruHeads = new Dense(params, "gru.heads", ObsMemory.GRU_HIDDEN, ActionSpace.TOTAL_LOGITS, rng, 0f);
	}

	public final class StepCache {
		final float[] p1 = new float[periConv1.outSize()], p2 = new float[periConv2.outSize()];
		final float[] f1 = new float[foveaConv1.outSize()], f2 = new float[foveaConv2.outSize()];
		final float[] cat = new float[CAT], wide = new float[64], oldT = new float[OLD], newT = new float[NEW];
		final float[] top = new float[TOP], hOut = new float[ObsMemory.GRU_HIDDEN];
		final Gru.StepCache gruCache = gru.newStepCache();
		final ResBlock.Cache[] oldCaches = new ResBlock.Cache[oldBlocks.length], newCaches = new ResBlock.Cache[newBlocks.length];
		final float[] base = new float[ActionSpace.TOTAL_LOGITS], arJumpIn = new float[AR_JUMP], arAttackIn = new float[AR_ATTACK];
		public final float[] logits = new float[ActionSpace.TOTAL_LOGITS];

		StepCache() {
			for (int b = 0; b < oldBlocks.length; b++) oldCaches[b] = oldBlocks[b].newCache();
			for (int b = 0; b < newBlocks.length; b++) newCaches[b] = newBlocks[b].newCache();
		}
	}

	public final class Workspace {
		public final StepCache step = new StepCache();
		final float[] widePre = new float[OLD], oldPre = new float[OLD], newPre = new float[NEW];
		final float[] histPre = new float[NEW], seenPre = new float[NEW], gruPre = new float[NEW];
		final float[] histLogits = new float[ActionSpace.TOTAL_LOGITS], seenLogits = new float[ActionSpace.TOTAL_LOGITS];
		final float[] gruLogits = new float[ActionSpace.TOTAL_LOGITS], arOut = new float[2], probs = new float[ActionSpace.TOTAL_LOGITS];
		final int[] act = new int[ActionSpace.HEADS];
		final float[] dTop = new float[TOP], dOld = new float[OLD], dNew = new float[NEW];
		final float[] dCat = new float[CAT], dCatPart = new float[CAT], dCatGru = new float[CAT];
		final float[] dH = new float[ObsMemory.GRU_HIDDEN], dHPart = new float[ObsMemory.GRU_HIDDEN], dWide = new float[64];
		final float[] dP1 = new float[step.p1.length], dP2 = new float[step.p2.length];
		final float[] dF1 = new float[step.f1.length], dF2 = new float[step.f2.length];
		final Gru.Scratch gruScratch = gru.newScratch();
		public final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];
		public final float[][] grads = params.newGrads();
	}

	public Workspace newWorkspace() { return new Workspace(); }
	public StepCache newStepCache() { return new StepCache(); }

	public void forward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] hist, float[] seen,
			float[] hPrev, float[] hOut) {
		periConv1.forwardBytes(peri, sc.p1); relu(sc.p1);
		periConv2.forward(sc.p1, sc.p2); relu(sc.p2);
		periDense.forward(sc.p2, 0, sc.cat, 0);
		foveaConv1.forwardBytes(fovea, sc.f1); relu(sc.f1);
		foveaConv2.forward(sc.f1, sc.f2); relu(sc.f2);
		foveaDense.forward(sc.f2, 0, sc.cat, BRANCH);
		vecDense.forward(vec, 0, sc.cat, 2 * BRANCH);
		relu(sc.cat);

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
		if (gruEnabled) gruNew.forward(sc.hOut, 0, ws.gruPre, 0); else Arrays.fill(ws.gruPre, 0f);
		for (int i = 0; i < NEW; i++) sc.newT[i] = Math.max(0f, ws.newPre[i] + ws.histPre[i] + ws.seenPre[i] + ws.gruPre[i]);

		System.arraycopy(sc.oldT, 0, sc.top, 0, OLD);
		for (int b = 0; b < oldBlocks.length; b++) oldBlocks[b].forward(sc.top, 0, sc.oldCaches[b]);
		System.arraycopy(sc.newT, 0, sc.top, OLD, NEW);
		for (int b = 0; b < newBlocks.length; b++) newBlocks[b].forward(sc.top, OLD, sc.newCaches[b]);

		heads.forward(sc.top, 0, sc.base, 0);
		histHeads.forward(hist, 0, ws.histLogits, 0);
		seenHeads.forward(seen, 0, ws.seenLogits, 0);
		if (gruEnabled) gruHeads.forward(sc.hOut, 0, ws.gruLogits, 0); else Arrays.fill(ws.gruLogits, 0f);
		for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) sc.base[i] += ws.histLogits[i] + ws.seenLogits[i] + ws.gruLogits[i];
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
		for (int h = 0; h < ActionSpace.HEADS; h++) ws.act[h] = action[h];
		conditionJump(ws, sc, ws.act);
		conditionAttack(ws, sc, ws.act);
	}

	public float sample(Workspace ws, StepCache sc, int headMask, boolean greedy, float temperature, Random rng, int[] actionOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (h == ActionSpace.JUMP) conditionJump(ws, sc, actionOut);
			else if (h == ActionSpace.ATTACK) conditionAttack(ws, sc, actionOut);
			if (!ActionSpace.unlocked(headMask, h)) { actionOut[h] = ActionSpace.DEFAULTS[h]; continue; }
			int off = ActionSpace.OFFSETS[h], n = ActionSpace.SIZES[h];
			if (temperature != 1f && temperature > 0f) {
				for (int i = 0; i < n; i++) ws.probs[off + i] = sc.logits[off + i] / temperature;
				Policy.softmax(ws.probs, off, n, ws.probs);
			} else Policy.softmax(sc.logits, off, n, ws.probs);
			int choice = n - 1;
			if (greedy) {
				float best = -1f;
				for (int i = 0; i < n; i++) if (ws.probs[off + i] > best) { best = ws.probs[off + i]; choice = i; }
			} else {
				float u = rng.nextFloat(), acc = 0f;
				for (int i = 0; i < n; i++) { acc += ws.probs[off + i]; if (u < acc) { choice = i; break; } }
			}
			actionOut[h] = choice;
			logp += (float) Math.log(Math.max(ws.probs[off + choice], 1e-12f));
		}
		return logp;
	}

	public void backward(Workspace ws, StepCache sc, byte[] peri, byte[] fovea, float[] vec, float[] hist, float[] seen,
			float[] dHNext, float[] dHPrevOut) {
		arJump.backward(sc.arJumpIn, 0, ws.dLogits, JUMP_AT, null, 0, ws.grads);
		arAttack.backward(sc.arAttackIn, 0, ws.dLogits, ATTACK_AT, null, 0, ws.grads);
		heads.backward(sc.top, 0, ws.dLogits, 0, ws.dTop, 0, ws.grads);
		histHeads.backward(hist, 0, ws.dLogits, 0, null, 0, ws.grads);
		seenHeads.backward(seen, 0, ws.dLogits, 0, null, 0, ws.grads);
		if (gruEnabled) gruHeads.backward(sc.hOut, 0, ws.dLogits, 0, ws.dH, 0, ws.grads);
		else Arrays.fill(ws.dH, 0f);

		for (int b = newBlocks.length - 1; b >= 0; b--) newBlocks[b].backward(ws.dTop, OLD, sc.newCaches[b], ws.grads);
		for (int b = oldBlocks.length - 1; b >= 0; b--) oldBlocks[b].backward(ws.dTop, 0, sc.oldCaches[b], ws.grads);
		System.arraycopy(ws.dTop, 0, ws.dOld, 0, OLD); gate(ws.dOld, sc.oldT);
		System.arraycopy(ws.dTop, OLD, ws.dNew, 0, NEW); gate(ws.dNew, sc.newT);

		histIn.backward(hist, 0, ws.dNew, 0, null, 0, ws.grads);
		seenIn.backward(seen, 0, ws.dNew, 0, null, 0, ws.grads);
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

	/** Net2Net weight surgery from a legacy distilled SWSC checkpoint into SwordNet. */
	public long takeOverFromSchool(Path schoolCheckpoint) throws IOException {
		Map<String, float[]> saved = new HashMap<>();
		int oldVec;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(schoolCheckpoint)))) {
			if (in.readInt() != 0x53575343) throw new IOException("not an SWSC checkpoint: " + schoolCheckpoint);
			in.readInt(); oldVec = in.readInt();
			in.readInt(); in.readInt(); in.readInt(); in.readInt();
			in.readLong(); in.readLong(); in.readLong();
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				String name = in.readUTF();
				int len = in.readInt();
				float[] w = new float[len];
				for (int k = 0; k < len; k++) w[k] = in.readFloat();
				int skip = 8 * len;
				while (skip > 0) { int s = in.skipBytes(skip); if (s <= 0) throw new IOException("EOF"); skip -= s; }
				saved.put(name, w);
			}
		}
		int inserted = ObsSpec.VEC - oldVec;
		int insertAt = ObsSpec.VEC_SCALARS + (ObsSpec.SCALARS - Math.max(0, inserted));
		long copied = 0;
		for (Param p : params.params()) {
			float[] from = saved.get(p.name);
			if (from == null) continue;
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
				System.arraycopy(from, 0, p.w, 0, p.w.length);
				copied += p.w.length;
			}
		}
		return copied;
	}

	public float gruWeightNorm() {
		double s = 0;
		for (float v : gruNew.weight.w) s += (double) v * v;
		for (float v : gruHeads.weight.w) s += (double) v * v;
		return (float) Math.sqrt(s);
	}

	private static void relu(float[] a) { for (int i = 0; i < a.length; i++) if (a[i] < 0f) a[i] = 0f; }
	private static void gate(float[] g, float[] a) { for (int i = 0; i < g.length; i++) if (a[i] <= 0f) g[i] = 0f; }
}
