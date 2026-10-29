package dev.swordai.core.school;

import dev.swordai.core.nn.Conv2d;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.Policy;

import java.util.Arrays;
import java.util.Random;

/**
 * The acting network of the technique school (plan part 7): the network of the stages before, widened,
 * with the memory of the fight as an input and the keys that go together chosen together.
 *
 * <ul>
 * <li><b>Vision and body</b>: the two small CNNs over the opponent masks and the layer over the vector
 * input, exactly as before (their weights are taken over from the installed network, {@link #takeOver}).</li>
 * <li><b>Memory</b>: what this opponent has been doing this fight ({@link SchoolObs#MEM}) through a layer
 * of its own, and the {@link Predictor}'s guess of what it will do next.</li>
 * <li><b>Two towers</b> of {@link #OLD} and {@link #NEW} units. The old tower is the installed network's
 * trunk and its two residual blocks; it also reads the memory, through weights that start at zero. The
 * new tower reads everything, the prediction too, and has two blocks of its own. The heads read both;
 * their weights from the new tower start at zero. So the widened network first plays exactly as the
 * installed one's trunk did (Net2Net's function-preserving growth, Chen et al. 2016), and grows from there.</li>
 * <li><b>Keys chosen in order</b> (autoregressive heads; "Revisiting action factorization", 2026): the
 * camera, W/S, A/D and the sprint key first; then the jump knowing the W/S and sprint just chosen; then
 * the swing knowing those and the jump. A hop (W let go, then the jump), a jump reset (the jump with W
 * and the sprint) and a crit (the swing with the sprint off) are combinations; heads that cannot see each
 * other only find them by luck. These dependencies start at zero too.</li>
 *
 * <li><b>The technique it is doing</b> (the user's idea, 24 Oct: "a decision model that knows what these techniques are,
 * its output an input to the main model"): which technique, one-hot, and how long it has been doing it ({@link #INTENT},
 * after the prediction in the side input). In training it is mostly the technique teacher's own, so a moment no longer
 * comes with two answers the network cannot tell apart (an uppercut's S after its hit at the top, a lunge's W after its
 * hit falling); in a fight it is the {@link Chooser}'s. It goes into the new tower and straight into the heads, both
 * through weights that start at zero (options, Sutton, Precup and Singh 1999; FeUdal Networks, Vezhnevets et al. 2017;
 * neural probabilistic motor primitives, Merel et al. 2019).</li>
 * </ul>
 *
 * <p>The crit and escape sub-models of the network before are gone, and with them every key pressed by
 * a rule: what they did is taught by the installed network as a teacher whose pull fades out
 * ({@link SchoolPpo}), and then left to the reward.
 */
public final class SchoolNet {
	static final int BRANCH = 64;
	static final int MEM_UNITS = 96;
	/** The old tower: the installed network's trunk. */
	public static final int OLD = 128;
	public static final int NEW = 128;
	public static final int TOP = OLD + NEW;
	static final int BLOCK_HIDDEN = 256;
	static final int CAT = 3 * BRANCH + MEM_UNITS;
	/** What the jump head is told: W/S (3) and the sprint key (2), one-hot. */
	static final int AR_JUMP = 5;
	/** What the swing head is told: W/S, the sprint key and the jump (2), one-hot. */
	static final int AR_ATTACK = 7;
	private static final int JUMP_AT = ActionSpace.OFFSETS[ActionSpace.JUMP];
	private static final int ATTACK_AT = ActionSpace.OFFSETS[ActionSpace.ATTACK];
	/** The technique being done: one-hot over {@link Technique} (the ordinary fight its own), and the ticks into it. */
	public static final int INTENT = Technique.COUNT + 1;
	/** Where the ticks into it are, and the ticks that count as long. */
	public static final int INTENT_TICKS = Technique.COUNT;
	public static final float INTENT_LONG = 20f;
	/** The side input: the {@link Predictor}'s guess, then the technique being done. */
	public static final int SIDE = Predictor.OUT + INTENT;

	public final ParamSet params = new ParamSet();
	private final Conv2d periConv1;
	private final Conv2d periConv2;
	private final Dense periDense;
	private final Conv2d foveaConv1;
	private final Conv2d foveaConv2;
	private final Dense foveaDense;
	private final Dense vecDense;
	private final Dense memDense;
	private final Dense oldTrunk;
	private final Dense vecWideIn;
	private final Dense vecWideOut;
	private final ResBlock[] oldBlocks = new ResBlock[2];
	private final Dense newTrunk;
	private final Dense predIn;
	private final ResBlock[] newBlocks = new ResBlock[2];
	private final Dense heads;
	private final Dense arJump;
	private final Dense arAttack;
	private final Dense intentIn;
	private final Dense intentHeads;
	/**
	 * The option layers (SchoolStages#GATED): for each technique, a linear map from the top to the logits of its gated keys,
	 * added only while the network is told that technique. Its lesson's pull goes into them alone (Workspace#dOption).
	 */
	private final Param optionW;
	private final Param optionB;

	public SchoolNet(long seed) {
		Random rng = new Random(seed);
		float relu = (float) Math.sqrt(2.0);
		// The names of the parts the installed network has are its names, so they can be taken over.
		periConv1 = new Conv2d(params, "peri.conv1", ObsSpec.PERI_H, ObsSpec.PERI_W, ObsSpec.CHANNELS, 4, 2, 8, rng);
		periConv2 = new Conv2d(params, "peri.conv2", periConv1.outH, periConv1.outW, 8, 3, 2, 16, rng);
		periDense = new Dense(params, "peri.dense", periConv2.outSize(), BRANCH, rng, relu);
		foveaConv1 = new Conv2d(params, "fovea.conv1", ObsSpec.FOVEA, ObsSpec.FOVEA, ObsSpec.CHANNELS, 4, 2, 8, rng);
		foveaConv2 = new Conv2d(params, "fovea.conv2", foveaConv1.outH, foveaConv1.outW, 8, 3, 2, 16, rng);
		foveaDense = new Dense(params, "fovea.dense", foveaConv2.outSize(), BRANCH, rng, relu);
		vecDense = new Dense(params, "vec.dense", ObsSpec.VEC, BRANCH, rng, relu);
		memDense = new Dense(params, "mem.dense", SchoolObs.MEM, MEM_UNITS, rng, relu);
		oldTrunk = new Dense(params, "trunk", CAT, OLD, rng, relu);
		vecWideIn = new Dense(params, "vec.wide", ObsSpec.VEC, 64, rng, relu);
		vecWideOut = new Dense(params, "vec.wide.out", 64, OLD, rng, 0f);
		for (int b = 0; b < oldBlocks.length; b++) {
			oldBlocks[b] = new ResBlock(params, "block" + b, OLD, BLOCK_HIDDEN, rng);
		}
		newTrunk = new Dense(params, "new.trunk", CAT, NEW, rng, relu);
		predIn = new Dense(params, "new.pred", Predictor.OUT, NEW, rng, 0f);
		for (int b = 0; b < newBlocks.length; b++) {
			newBlocks[b] = new ResBlock(params, "new.block" + b, NEW, BLOCK_HIDDEN, rng);
		}
		heads = new Dense(params, "heads", TOP, ActionSpace.TOTAL_LOGITS, rng, 0.01f);
		arJump = new Dense(params, "ar.jump", AR_JUMP, ActionSpace.SIZES[ActionSpace.JUMP], rng, 0f);
		arAttack = new Dense(params, "ar.attack", AR_ATTACK, ActionSpace.SIZES[ActionSpace.ATTACK], rng, 0f);
		// Last, so a network saved before them loads with them new (and at zero: it plays as it did).
		intentIn = new Dense(params, "new.intent", INTENT, NEW, rng, 0f);
		intentHeads = new Dense(params, "intent.heads", INTENT, ActionSpace.TOTAL_LOGITS, rng, 0f);
		optionW = params.add("option.w", Technique.COUNT * TOP * ActionSpace.TOTAL_LOGITS);
		optionB = params.add("option.b", Technique.COUNT * ActionSpace.TOTAL_LOGITS);
		// Rows of the old tower's trunk from the memory, and rows of the heads from the new tower: zero.
		zeroRows(oldTrunk, 3 * BRANCH, CAT);
		zeroRows(heads, OLD, TOP);
	}

	private static void zeroRows(Dense d, int from, int to) {
		Arrays.fill(d.weight.w, from * d.out, to * d.out, 0f);
	}

	/**
	 * Takes over the installed network's vision, body layer, trunk, blocks and heads (an {@code ActorNet}
	 * with a wide vector layer of 64 and two blocks, as installed on 19 October), by tensor name. What has
	 * no counterpart stays as made, so the network plays as that one's trunk did. Returns how many values
	 * were taken over.
	 */
	public long takeOver(ParamSet installed) {
		long n = 0;
		for (Param from : installed.params()) {
			Param to = find(from.name);
			if (to == null) {
				continue;
			}
			if (to.w.length == from.w.length) {
				System.arraycopy(from.w, 0, to.w, 0, to.w.length);
				n += to.w.length;
			} else if (from.w.length < to.w.length && (from.name.equals("trunk.w") || from.name.equals("heads.w"))) {
				// More rows (inputs) here: the first rows are the old ones, in the same layout; the rest stay zero.
				System.arraycopy(from.w, 0, to.w, 0, from.w.length);
				n += from.w.length;
			}
		}
		return n;
	}

	private Param find(String name) {
		for (Param p : params.params()) {
			if (p.name.equals(name)) {
				return p;
			}
		}
		return null;
	}

	/** Per-thread activations and gradient buffers. */
	public final class Workspace {
		final float[] p1 = new float[periConv1.outSize()];
		final float[] p2 = new float[periConv2.outSize()];
		final float[] f1 = new float[foveaConv1.outSize()];
		final float[] f2 = new float[foveaConv2.outSize()];
		final float[] cat = new float[CAT];
		final float[] wide = new float[64];
		final float[] widePre = new float[OLD];
		final float[] oldPre = new float[OLD];
		final float[] newPre = new float[NEW];
		final float[] predPre = new float[NEW];
		final float[] pred = new float[SIDE];
		final float[] intentPre = new float[NEW];
		final float[] intentLogits = new float[ActionSpace.TOTAL_LOGITS];
		/** The towers before their blocks (kept for the gates) and the top after them. */
		final float[] oldT = new float[OLD];
		final float[] newT = new float[NEW];
		final float[] top = new float[TOP];
		final ResBlock.Cache[] oldCaches = new ResBlock.Cache[oldBlocks.length];
		final ResBlock.Cache[] newCaches = new ResBlock.Cache[newBlocks.length];
		/** The heads' logits before the keys chosen earlier in the tick are added in. */
		final float[] base = new float[ActionSpace.TOTAL_LOGITS];
		final float[] arJumpIn = new float[AR_JUMP];
		final float[] arAttackIn = new float[AR_ATTACK];
		final float[] arOut = new float[2];
		public final float[] logits = new float[ActionSpace.TOTAL_LOGITS];

		final float[] dTop = new float[TOP];
		final float[] dOld = new float[OLD];
		final float[] dNew = new float[NEW];
		final float[] dCat = new float[CAT];
		final float[] dCatPart = new float[CAT];
		final float[] dWide = new float[64];
		final float[] dP1 = new float[p1.length];
		final float[] dP2 = new float[p2.length];
		final float[] dF1 = new float[f1.length];
		final float[] dF2 = new float[f2.length];
		final float[] dMem = new float[MEM_UNITS];
		public final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];
		/** A gated lesson's pull on the logits: into the told technique's option layer alone, cleared by {@link #backward}. */
		public final float[] dOption = new float[ActionSpace.TOTAL_LOGITS];
		/** The technique whose option layer the last forward added (-1: none). */
		int option = -1;
		public final float[][] grads = params.newGrads();
		final float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		final int[] act = new int[ActionSpace.HEADS];

		Workspace() {
			for (int b = 0; b < oldBlocks.length; b++) {
				oldCaches[b] = oldBlocks[b].newCache();
			}
			for (int b = 0; b < newBlocks.length; b++) {
				newCaches[b] = newBlocks[b].newCache();
			}
		}
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	/**
	 * The trunk and the heads' own logits ({@code ws.logits}, before the keys chosen earlier in the tick
	 * have their say; {@link #condition} adds that).
	 *
	 * @param pred the side input ({@link #SIDE}): the {@link Predictor}'s guess and the technique being done, or null for none
	 */
	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, float[] mem, float[] pred) {
		periConv1.forwardBytes(peri, ws.p1);
		relu(ws.p1);
		periConv2.forward(ws.p1, ws.p2);
		relu(ws.p2);
		periDense.forward(ws.p2, 0, ws.cat, 0);
		foveaConv1.forwardBytes(fovea, ws.f1);
		relu(ws.f1);
		foveaConv2.forward(ws.f1, ws.f2);
		relu(ws.f2);
		foveaDense.forward(ws.f2, 0, ws.cat, BRANCH);
		vecDense.forward(vec, 0, ws.cat, 2 * BRANCH);
		memDense.forward(mem, 0, ws.cat, 3 * BRANCH);
		relu(ws.cat);

		oldTrunk.forward(ws.cat, 0, ws.oldPre, 0);
		vecWideIn.forward(vec, 0, ws.wide, 0);
		relu(ws.wide);
		vecWideOut.forward(ws.wide, 0, ws.widePre, 0);
		for (int i = 0; i < OLD; i++) {
			ws.oldT[i] = Math.max(0f, ws.oldPre[i] + ws.widePre[i]);
		}
		newTrunk.forward(ws.cat, 0, ws.newPre, 0);
		if (pred != null) {
			System.arraycopy(pred, 0, ws.pred, 0, SIDE);
		} else {
			Arrays.fill(ws.pred, 0f);
		}
		predIn.forward(ws.pred, 0, ws.predPre, 0);
		intentIn.forward(ws.pred, Predictor.OUT, ws.intentPre, 0);
		for (int i = 0; i < NEW; i++) {
			ws.newT[i] = Math.max(0f, ws.newPre[i] + ws.predPre[i] + ws.intentPre[i]);
		}
		System.arraycopy(ws.oldT, 0, ws.top, 0, OLD);
		for (int b = 0; b < oldBlocks.length; b++) {
			oldBlocks[b].forward(ws.top, 0, ws.oldCaches[b]);
		}
		System.arraycopy(ws.newT, 0, ws.top, OLD, NEW);
		for (int b = 0; b < newBlocks.length; b++) {
			newBlocks[b].forward(ws.top, OLD, ws.newCaches[b]);
		}
		heads.forward(ws.top, 0, ws.base, 0);
		intentHeads.forward(ws.pred, Predictor.OUT, ws.intentLogits, 0);
		for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
			ws.base[i] += ws.intentLogits[i];
		}
		ws.option = optionOf(ws.pred);
		if (ws.option >= 0) {
			int gated = SchoolStages.gatedHeads(ws.option);
			float[] w = optionW.w;
			int wt = ws.option * TOP * ActionSpace.TOTAL_LOGITS;
			int bt = ws.option * ActionSpace.TOTAL_LOGITS;
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if ((gated & (1 << h)) == 0) {
					continue;
				}
				for (int o = ActionSpace.OFFSETS[h]; o < ActionSpace.OFFSETS[h] + ActionSpace.SIZES[h]; o++) {
					float y = optionB.w[bt + o];
					for (int i = 0; i < TOP; i++) {
						y += ws.top[i] * w[wt + i * ActionSpace.TOTAL_LOGITS + o];
					}
					ws.base[o] += y;
				}
			}
		}
		System.arraycopy(ws.base, 0, ws.logits, 0, ActionSpace.TOTAL_LOGITS);
	}

	/** The technique the side input tells (its one-hot), if it has gated keys; else -1. */
	static int optionOf(float[] side) {
		int best = -1;
		float most = 0.5f;
		for (int t = 0; t < Technique.COUNT; t++) {
			if (side[Predictor.OUT + t] >= most) {
				most = side[Predictor.OUT + t];
				best = t;
			}
		}
		return best >= 0 && SchoolStages.gatedHeads(best) != 0 ? best : -1;
	}

	/**
	 * The jump's and the swing's logits given the keys chosen before them on this tick: W/S and the sprint
	 * for the jump, those and the jump for the swing. {@code action} needs those heads filled in.
	 */
	public void conditionJump(Workspace ws, int[] action) {
		Arrays.fill(ws.arJumpIn, 0f);
		ws.arJumpIn[action[ActionSpace.FORWARD]] = 1f;
		ws.arJumpIn[3 + action[ActionSpace.SPRINT]] = 1f;
		arJump.forward(ws.arJumpIn, 0, ws.arOut, 0);
		ws.logits[JUMP_AT] = ws.base[JUMP_AT] + ws.arOut[0];
		ws.logits[JUMP_AT + 1] = ws.base[JUMP_AT + 1] + ws.arOut[1];
	}

	public void conditionAttack(Workspace ws, int[] action) {
		Arrays.fill(ws.arAttackIn, 0f);
		ws.arAttackIn[action[ActionSpace.FORWARD]] = 1f;
		ws.arAttackIn[3 + action[ActionSpace.SPRINT]] = 1f;
		ws.arAttackIn[5 + action[ActionSpace.JUMP]] = 1f;
		arAttack.forward(ws.arAttackIn, 0, ws.arOut, 0);
		ws.logits[ATTACK_AT] = ws.base[ATTACK_AT] + ws.arOut[0];
		ws.logits[ATTACK_AT + 1] = ws.base[ATTACK_AT + 1] + ws.arOut[1];
	}

	/** Both, for a stored action. */
	public void condition(Workspace ws, byte[] action) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			ws.act[h] = action[h];
		}
		conditionJump(ws, ws.act);
		conditionAttack(ws, ws.act);
	}

	/**
	 * Chooses the keys in order: everything but the jump and the swing from the trunk, then the jump, then
	 * the swing. Locked heads take their default. Returns log pi(action) over the unlocked heads, and
	 * leaves {@code ws.logits} as the action was drawn from.
	 */
	public float sample(Workspace ws, int headMask, boolean greedy, float temperature, Random rng, int[] actionOut) {
		return sample(ws, headMask, 0, greedy, temperature, rng, actionOut);
	}

	/**
	 * The same, with the locked heads in {@code presetMask} keeping the keys already in {@code actionOut} (a rule's): the
	 * jump and the swing are then chosen knowing those keys. With the rule's W and sprint read as their defaults, a jump
	 * chosen under a rule that held W was the hop's jump in place, 185 jumps a minute to the installed model's 62 (25 Oct
	 * 09:11 check).
	 */
	public float sample(Workspace ws, int headMask, int presetMask, boolean greedy, float temperature, Random rng, int[] actionOut) {
		return sample(ws, headMask, presetMask, greedy, temperature, null, rng, actionOut);
	}

	/**
	 * The same with a temperature for each head ({@code headTemperatures[h]}, 0 or below = its most likely key), where
	 * {@code headTemperatures} is not null: the test-time sharpening of a fight or a check, never in training.
	 */
	public float sample(Workspace ws, int headMask, int presetMask, boolean greedy, float temperature, float[] headTemperatures,
			Random rng, int[] actionOut) {
		float logp = 0f;
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if (h == ActionSpace.JUMP) {
				conditionJump(ws, actionOut);
			} else if (h == ActionSpace.ATTACK) {
				conditionAttack(ws, actionOut);
			}
			if (!ActionSpace.unlocked(headMask, h)) {
				if ((presetMask & (1 << h)) == 0) {
					actionOut[h] = ActionSpace.DEFAULTS[h];
				}
				continue;
			}
			if (headTemperatures != null) {
				float t = headTemperatures[h];
				logp += pick(ws.logits, h, greedy || t <= 0f, t <= 0f ? 1f : t, rng, ws.probs, actionOut);
			} else {
				logp += pick(ws.logits, h, greedy, temperature, rng, ws.probs, actionOut);
			}
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
	 * Back-propagates {@code ws.dLogits} (with respect to the conditioned logits of the last forward and
	 * {@link #condition}) into {@code ws.grads}.
	 */
	public void backward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, float[] mem) {
		// The keys chosen earlier in the tick: what they add to the jump and the swing.
		arJump.backward(ws.arJumpIn, 0, ws.dLogits, JUMP_AT, null, 0, ws.grads);
		arAttack.backward(ws.arAttackIn, 0, ws.dLogits, ATTACK_AT, null, 0, ws.grads);
		heads.backward(ws.top, 0, ws.dLogits, 0, ws.dTop, 0, ws.grads);
		intentHeads.backward(ws.pred, Predictor.OUT, ws.dLogits, 0, null, 0, ws.grads);
		if (ws.option >= 0) {
			// Its weights learn from the policy's gradient and the gated lesson's; the layers under it from the policy's alone.
			int gated = SchoolStages.gatedHeads(ws.option);
			float[] w = optionW.w;
			float[] gw = ws.grads[optionW.id];
			float[] gb = ws.grads[optionB.id];
			int wt = ws.option * TOP * ActionSpace.TOTAL_LOGITS;
			int bt = ws.option * ActionSpace.TOTAL_LOGITS;
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if ((gated & (1 << h)) == 0) {
					continue;
				}
				for (int o = ActionSpace.OFFSETS[h]; o < ActionSpace.OFFSETS[h] + ActionSpace.SIZES[h]; o++) {
					float d = ws.dLogits[o] + ws.dOption[o];
					float policy = ws.dLogits[o];
					gb[bt + o] += d;
					for (int i = 0; i < TOP; i++) {
						int k = wt + i * ActionSpace.TOTAL_LOGITS + o;
						gw[k] += ws.top[i] * d;
						ws.dTop[i] += w[k] * policy;
					}
				}
			}
		}
		Arrays.fill(ws.dOption, 0f);
		for (int b = newBlocks.length - 1; b >= 0; b--) {
			newBlocks[b].backward(ws.dTop, OLD, ws.newCaches[b], ws.grads);
		}
		for (int b = oldBlocks.length - 1; b >= 0; b--) {
			oldBlocks[b].backward(ws.dTop, 0, ws.oldCaches[b], ws.grads);
		}
		System.arraycopy(ws.dTop, 0, ws.dOld, 0, OLD);
		System.arraycopy(ws.dTop, OLD, ws.dNew, 0, NEW);
		gate(ws.dOld, ws.oldT);
		gate(ws.dNew, ws.newT);
		predIn.backward(ws.pred, 0, ws.dNew, 0, null, 0, ws.grads);
		intentIn.backward(ws.pred, Predictor.OUT, ws.dNew, 0, null, 0, ws.grads);
		newTrunk.backward(ws.cat, 0, ws.dNew, 0, ws.dCat, 0, ws.grads);
		oldTrunk.backward(ws.cat, 0, ws.dOld, 0, ws.dCatPart, 0, ws.grads);
		for (int i = 0; i < CAT; i++) {
			ws.dCat[i] += ws.dCatPart[i];
		}
		vecWideOut.backward(ws.wide, 0, ws.dOld, 0, ws.dWide, 0, ws.grads);
		gate(ws.dWide, ws.wide);
		vecWideIn.backward(vec, 0, ws.dWide, 0, null, 0, ws.grads);
		gate(ws.dCat, ws.cat);
		memDense.backward(mem, 0, ws.dCat, 3 * BRANCH, null, 0, ws.grads);
		vecDense.backward(vec, 0, ws.dCat, 2 * BRANCH, null, 0, ws.grads);
		foveaDense.backward(ws.f2, 0, ws.dCat, BRANCH, ws.dF2, 0, ws.grads);
		gate(ws.dF2, ws.f2);
		Arrays.fill(ws.dF1, 0f);
		foveaConv2.backward(ws.f1, ws.dF2, ws.dF1, ws.grads);
		gate(ws.dF1, ws.f1);
		foveaConv1.backwardBytes(fovea, ws.dF1, ws.grads);
		periDense.backward(ws.p2, 0, ws.dCat, 0, ws.dP2, 0, ws.grads);
		gate(ws.dP2, ws.p2);
		Arrays.fill(ws.dP1, 0f);
		periConv2.backward(ws.p1, ws.dP2, ws.dP1, ws.grads);
		gate(ws.dP1, ws.p1);
		periConv1.backwardBytes(peri, ws.dP1, ws.grads);
	}

	/**
	 * Shrink and perturb (Ash and Adams 2020; the plasticity survey of 2024): every weight pulled towards
	 * zero by {@code shrink} and given a little noise, when learning has stalled. Not while an update runs.
	 */
	public void shrinkAndPerturb(float shrink, float noise, Random rng) {
		for (Param p : params.params()) {
			for (int i = 0; i < p.w.length; i++) {
				p.w[i] = shrink * p.w[i] + noise * (float) rng.nextGaussian();
			}
		}
	}

	private static void relu(float[] a) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] < 0f) {
				a[i] = 0f;
			}
		}
	}

	private static void gate(float[] grad, float[] activation) {
		for (int i = 0; i < grad.length; i++) {
			if (activation[i] <= 0f) {
				grad[i] = 0f;
			}
		}
	}
}
