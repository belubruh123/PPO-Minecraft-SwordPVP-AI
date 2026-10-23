package dev.swordai.core.rl;

import dev.swordai.core.nn.Conv2d;
import dev.swordai.core.nn.Dense;
import dev.swordai.core.nn.ParamSet;
import dev.swordai.core.nn.ResBlock;

import java.util.Arrays;
import java.util.Random;

/**
 * The acting network: two small CNNs over the enemy masks and an MLP over the vector input, joined
 * into one trunk that feeds the action heads.
 *
 * <p>Two keys are not the trunk's. Each has a small model of its own, fed by the vector input only
 * (the fighter's own body, and where and how large the opponent is on screen):
 *
 * <ul>
 * <li>the <b>crit model</b> decides the jump key, always. And whenever a critical hit can be in the
 * making ({@link JumpNudge#critMoment}: in a jump of its own from take-off to landing, and coming
 * down after the opponent's hit has knocked it up, which is where a punish crit is made) it also
 * has the sprint key, and W/S unless the escape model has those. The trunk, which
 * is the sprint-hit fighter, has no say in those keys then. A critical hit is not
 * a jump: it is a swing made on the way down with the sprint off, and a fighter that has spent a
 * million steps learning never to let go of the sprint does not let go of it because it has
 * jumped. (0.11.3: forty jumps a minute, nine hits in ten still sprint hits, not one critical.)
 * So in those moments the crit model starts out leaving W alone and the sprint key up, which ends
 * the sprint.
 *
 * <p>The swing is the crit model's for as long as the fighter is off the ground at all
 * ({@link JumpNudge#inAir}), so also on the way up after a hit: it starts out swinging when it is
 * coming down with the weapon back and the sprint off, and not before. That is what keeps the
 * swing for the punish crit instead of spending it on the way up. But a swing in the air takes two:
 * the crit model's "now it would be critical", and the trunk's "there is a hit to be had". The
 * trunk has spent its whole training on whether the opponent is under the crosshair and near
 * enough; where it would not swing, the swing is held back ({@link #NO_HIT_MOST}). Up to 0.12.3 the
 * trunk was not asked, and the fighter swung on the way down of every jump whatever was in front
 * of it: at an opponent out of reach that is a miss, and the weapon is gone for the hit that was
 * to be had a moment later.</li>
 * <li>the <b>escape model</b> decides W/S in the twelve ticks after each hit of a combo the fighter
 * is taking ({@link BackNudge#applies}): the ticks in which it backs out or does not. The rest of
 * the time W/S is the trunk's, as before, and with it coming back in at a sprint for the hit.</li>
 * </ul>
 *
 * <p>Why: both are things the fighter learns late, on keys it has long since settled on never
 * pressing (jump) or always pressing one way (W). Taught inside the one network, the new use either
 * never got started or spread to everywhere (0.9.0 to 0.10.0: it held S all the time). A model of
 * its own starts undecided, so it tries the key from its first tick, and nothing it learns can
 * change what the rest of the network does: its gradient ends in it, and its output is only ever
 * used for its own key in its own moment.
 */
public final class ActorNet {
	private static final int BRANCH = 64;
	private static final int TRUNK = 128;
	/** Hidden units of each of the two small models. */
	private static final int SKILL = 32;
	/** A crit model that has learned nothing yet leaves the jump key alone on about nineteen ticks in twenty. */
	private static final float CRIT_REST = 3f;
	private static final int JUMP_AT = ActionSpace.OFFSETS[ActionSpace.JUMP];
	private static final int FORWARD_AT = ActionSpace.OFFSETS[ActionSpace.FORWARD];
	private static final int SPRINT_AT = ActionSpace.OFFSETS[ActionSpace.SPRINT];
	private static final int ATTACK_AT = ActionSpace.OFFSETS[ActionSpace.ATTACK];
	/** What the crit model says in the air: W/S (3), the sprint key (2), and a shift of its own swing (2). */
	private static final int AIR = 7;
	/** How firmly the crit model starts out swinging when a swing would be critical, and holding it otherwise: nineteen ticks in twenty. */
	private static final float SWING_REST = 3f;
	/**
	 * In the air the swing is held back by this much for every unit by which the trunk prefers not
	 * swinging, up to {@link #NO_HIT_MOST}: at the most, a swing that would be critical is left as
	 * unlikely as one that would not be. It is never made more likely by the trunk, and the crit model
	 * can learn its way round it from what it is shown itself (see {@code crit.air}).
	 */
	static final float NO_HIT_GAIN = 2f;
	static final float NO_HIT_MOST = 2f * SWING_REST;

	public final ParamSet params = new ParamSet();
	public final boolean usePeriphery;
	public final boolean useFovea;

	private final Conv2d periConv1;
	private final Conv2d periConv2;
	private final Dense periDense;
	private final Conv2d foveaConv1;
	private final Conv2d foveaConv2;
	private final Dense foveaDense;
	private final Dense vecDense;
	private final Dense trunkDense;
	private final Dense heads;
	private final Dense critHidden;
	private final Dense critOut;
	private final Dense critAir;
	private final Dense critSwing;
	private final Dense escapeHidden;
	private final Dense escapeOut;
	/** Extra units reading the vector input straight into the trunk; null when there are none ({@code Config.actorVecWide}). */
	private final Dense vecWideIn;
	private final Dense vecWideOut;
	/** Residual blocks between the trunk and the heads ({@code Config.actorBlocks}); see {@link ResBlock}. */
	private final ResBlock[] blocks;
	/**
	 * The tactic in force ({@link Tactic}), one of {@link Tactic#COUNT}, into the trunk; null without
	 * the tactics model ({@code Config.tacticModel}). Starts at zero: told the tactic, the network
	 * fights as it did until it learns to fight each way.
	 */
	private final Dense tacticIn;
	/**
	 * The opponent reader's sixteen numbers ({@link OpponentReader}), into the trunk and, as a scale on
	 * the trunk's output (FiLM: Perez et al. AAAI 2018), just before the heads; null without the reader.
	 * Both start at zero.
	 */
	private final Dense oppIn;
	private final Dense oppFilm;
	/**
	 * What the trunk has made of the fight (what the heads read: after the blocks, the tactic and the
	 * reader) into the crit model and the escape model, and the reader's numbers into the crit model;
	 * null without {@code Config.critBridge}. Until then the two read the vector input alone and never
	 * knew which opponent they faced. They start at zero, and nothing they learn goes back into the
	 * trunk or the reader, which keep learning exactly as they did.
	 */
	private final Dense critTrunk;
	private final Dense critOpp;
	private final Dense escapeTrunk;
	/**
	 * The memory of the last 3.2 seconds ({@link RhythmNet}) and what it hands the trunk, the crit model
	 * and the escape model, all three starting at zero; null without {@code Config.rhythmNet}.
	 */
	private final RhythmNet rhythm;
	private final Dense rhythmTrunk;
	private final Dense rhythmCrit;
	private final Dense rhythmEscape;
	/**
	 * How many tensors at the end were added by growing the network (the wide vector units and the
	 * blocks). They all start out computing nothing, so a file saved without them loads as the same
	 * network.
	 */
	public final int grown;

	public ActorNet(long seed, boolean usePeriphery, boolean useFovea) {
		this(seed, usePeriphery, useFovea, 0, 0, 0);
	}

	/**
	 * @param vecWide    extra hidden units from the vector input into the trunk, 0 for none
	 * @param blockCount residual blocks after the trunk, 0 for none
	 * @param blockWidth hidden units in each block
	 */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth) {
		this(seed, usePeriphery, useFovea, vecWide, blockCount, blockWidth, 0);
	}

	/** @param tactics how many tactics it can be told ({@link Tactic#COUNT}), 0 for none */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics) {
		this(seed, usePeriphery, useFovea, vecWide, blockCount, blockWidth, tactics, 0);
	}

	/** @param opp how many numbers the opponent reader hands it ({@link OpponentReader#Z}), 0 for none */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics, int opp) {
		this(seed, usePeriphery, useFovea, vecWide, blockCount, blockWidth, tactics, opp, false);
	}

	/** @param bridge the crit and escape models read the trunk and the reader too ({@code Config.critBridge}) */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics, int opp,
			boolean bridge) {
		this(seed, usePeriphery, useFovea, vecWide, blockCount, blockWidth, tactics, opp, bridge, false);
	}

	/** @param memory the memory of the last seconds ({@link RhythmNet}, {@code Config.rhythmNet}) */
	public ActorNet(long seed, boolean usePeriphery, boolean useFovea, int vecWide, int blockCount, int blockWidth, int tactics, int opp,
			boolean bridge, boolean memory) {
		Random rng = new Random(seed);
		this.usePeriphery = usePeriphery;
		this.useFovea = useFovea;
		float relu = (float) Math.sqrt(2.0);

		periConv1 = new Conv2d(params, "peri.conv1", ObsSpec.PERI_H, ObsSpec.PERI_W, ObsSpec.CHANNELS, 4, 2, 8, rng);
		periConv2 = new Conv2d(params, "peri.conv2", periConv1.outH, periConv1.outW, 8, 3, 2, 16, rng);
		periDense = new Dense(params, "peri.dense", periConv2.outSize(), BRANCH, rng, relu);

		foveaConv1 = new Conv2d(params, "fovea.conv1", ObsSpec.FOVEA, ObsSpec.FOVEA, ObsSpec.CHANNELS, 4, 2, 8, rng);
		foveaConv2 = new Conv2d(params, "fovea.conv2", foveaConv1.outH, foveaConv1.outW, 8, 3, 2, 16, rng);
		foveaDense = new Dense(params, "fovea.dense", foveaConv2.outSize(), BRANCH, rng, relu);

		vecDense = new Dense(params, "vec.dense", ObsSpec.VEC, BRANCH, rng, relu);
		trunkDense = new Dense(params, "trunk", 3 * BRANCH, TRUNK, rng, relu);
		// Near-zero head weights: the first policy is close to uniform, so exploration is unbiased.
		heads = new Dense(params, "heads", TRUNK, ActionSpace.TOTAL_LOGITS, rng, 0.01f);
		calmInitialPolicy(heads.bias.w);
		// Added after everything else, so a network saved before they existed loads as it was and
		// gets them new.
		critHidden = new Dense(params, "crit.hidden", ObsSpec.VEC, SKILL, rng, relu);
		critOut = new Dense(params, "crit.out", SKILL, ActionSpace.SIZES[ActionSpace.JUMP], rng, 0.01f);
		critOut.bias.w[ActionSpace.DEFAULTS[ActionSpace.JUMP]] = CRIT_REST;
		// Undecided between W, nothing and S: in a combo it tries all three from the start.
		escapeHidden = new Dense(params, "escape.hidden", ObsSpec.VEC, SKILL, rng, relu);
		escapeOut = new Dense(params, "escape.out", SKILL, ActionSpace.SIZES[ActionSpace.FORWARD], rng, 0.01f);
		// Added in 0.12.0, after the rest for the same reason. In a jump of its own: W left alone, the
		// sprint key up on most ticks, and a correction to its own swing that starts at nothing.
		critAir = new Dense(params, "crit.air", SKILL, AIR, rng, 0.01f);
		// Added in 0.12.3: when to swing in the air, from two plain facts (see restAir).
		critSwing = new Dense(params, "crit.swing", 2, ActionSpace.SIZES[ActionSpace.ATTACK], rng, 0f);
		restAir();
		// Added on 21 October 2026, last, so every network saved before loads as it was. The decision
		// part was one layer of 128 over 64 units of the vector input (6.7k of the 153k weights; the
		// rest read the two pictures). Both additions start at zero output, so a grown network plays
		// exactly as the one it was grown from until it learns to use them.
		int before = params.params().size();
		if (vecWide > 0) {
			vecWideIn = new Dense(params, "vec.wide", ObsSpec.VEC, vecWide, rng, relu);
			vecWideOut = new Dense(params, "vec.wide.out", vecWide, TRUNK, rng, 0f);
		} else {
			vecWideIn = null;
			vecWideOut = null;
		}
		blocks = new ResBlock[Math.max(0, blockCount)];
		for (int b = 0; b < blocks.length; b++) {
			blocks[b] = new ResBlock(params, "block" + b, TRUNK, blockWidth, rng);
		}
		// Added on 21 October 2026 evening, after the blocks: the tactic chosen by the tactics model.
		tacticIn = tactics > 0 ? new Dense(params, "tactic.in", tactics, TRUNK, rng, 0f) : null;
		// Added later the same evening: the opponent reader's numbers.
		oppIn = opp > 0 ? new Dense(params, "opp.in", opp, TRUNK, rng, 0f) : null;
		oppFilm = opp > 0 ? new Dense(params, "opp.film", opp, TRUNK, rng, 0f) : null;
		// Added on 23 October 2026, last again.
		critTrunk = bridge ? new Dense(params, "crit.trunk", TRUNK, SKILL, rng, 0f) : null;
		critOpp = bridge && opp > 0 ? new Dense(params, "crit.opp", opp, SKILL, rng, 0f) : null;
		escapeTrunk = bridge ? new Dense(params, "escape.trunk", TRUNK, SKILL, rng, 0f) : null;
		// Added on 23 October 2026 after the bridge, last.
		rhythm = memory ? new RhythmNet(params, "rhythm", rng) : null;
		rhythmTrunk = memory ? new Dense(params, "rhythm.trunk", RhythmNet.OUT, TRUNK, rng, 0f) : null;
		rhythmCrit = memory ? new Dense(params, "rhythm.crit", RhythmNet.OUT, SKILL, rng, 0f) : null;
		rhythmEscape = memory ? new Dense(params, "rhythm.escape", RhythmNet.OUT, SKILL, rng, 0f) : null;
		grown = params.params().size() - before;
	}

	/**
	 * How the crit model starts out in the air: no W and no S, and the sprint key up. A hit is only
	 * critical if the server does not have the fighter sprinting, and that is what kept critical hits
	 * at exactly none for as long as the trunk held the keys: it lets go of W after every hit and
	 * presses it again with the sprint key down, which starts the sprint again each time. Letting go
	 * of W ends a sprint. Pressing W twice within seven ticks starts one, sprint key or no (the game's
	 * double-tap), so W pressed now and then in the air starts it again too, which is what the first
	 * version of this (0.12.0) did. With W left alone and the sprint key up, the sprint ends on the
	 * first tick in the air and stays ended.
	 */
	private void restAir() {
		Arrays.fill(critAir.bias.w, 0f);
		critAir.bias.w[0] = 3f;
		critAir.bias.w[2] = -1f;
		critAir.bias.w[3] = 4f;
		// The swing in the air is the crit model's alone, and it starts out knowing the one thing a
		// critical hit turns on: swing while coming down with the weapon back, and not before. Up to
		// 0.12.2 the swing in the air was the trunk's with a correction on top. The trunk only knows
		// the air from being knocked into it, where it learned to swing back at once: so it swung on
		// the way up with the weapon half back, which is no critical hit and starts the weapon over,
		// and then again after it had landed. Both weights are learned on from here like any other.
		Arrays.fill(critSwing.weight.w, 0f);
		Arrays.fill(critSwing.weight.m, 0f);
		Arrays.fill(critSwing.weight.v, 0f);
		Arrays.fill(critSwing.bias.w, 0f);
		Arrays.fill(critSwing.bias.m, 0f);
		Arrays.fill(critSwing.bias.v, 0f);
		// Input 0: coming down with the weapon back. Input 1: anything else in the air.
		critSwing.weight.w[0 * critSwing.out + 1] = SWING_REST;
		critSwing.weight.w[1 * critSwing.out + 0] = SWING_REST;
	}

	/**
	 * Biases every head towards "do little": small camera moves, keys up. A head stays at this
	 * prior until its stage unlocks it, so a freshly unlocked control explores gently instead of
	 * thrashing the camera and wrecking the skills that were learned before it.
	 */
	private static void calmInitialPolicy(float[] bias) {
		int yaw = ActionSpace.OFFSETS[ActionSpace.YAW];
		for (int i = 0; i < ActionSpace.YAW_BINS.length; i++) {
			bias[yaw + i] = -Math.abs(ActionSpace.YAW_BINS[i]) / 12f;
		}
		int pitch = ActionSpace.OFFSETS[ActionSpace.PITCH];
		for (int i = 0; i < ActionSpace.PITCH_BINS.length; i++) {
			bias[pitch + i] = -Math.abs(ActionSpace.PITCH_BINS[i]) / 5f;
		}
		bias[ActionSpace.OFFSETS[ActionSpace.FORWARD]] = 1.0f;
		bias[ActionSpace.OFFSETS[ActionSpace.STRAFE]] = 1.0f;
		bias[ActionSpace.OFFSETS[ActionSpace.SPRINT]] = 1.5f;
		bias[ActionSpace.OFFSETS[ActionSpace.JUMP]] = 1.5f;
		bias[ActionSpace.OFFSETS[ActionSpace.ATTACK]] = 1.5f;
	}

	/**
	 * Puts one head back to how a head starts: no weight on anything the trunk says, and its default
	 * choice ahead of the others by {@code restBias}. The optimiser's memory of it is cleared too.
	 */
	public void reopenHead(int head, float restBias) {
		if (head == ActionSpace.JUMP) {
			// The jump is the crit model's: that is what starts over, and with it what it does in the air.
			restart(critOut, ActionSpace.DEFAULTS[head], restBias);
			restart(critAir, 0, 0f);
			restAir();
		}
		int off = ActionSpace.OFFSETS[head];
		int n = ActionSpace.SIZES[head];
		for (int i = 0; i < heads.in; i++) {
			for (int o = off; o < off + n; o++) {
				int k = i * heads.out + o;
				heads.weight.w[k] = 0f;
				heads.weight.m[k] = 0f;
				heads.weight.v[k] = 0f;
			}
		}
		for (int o = off; o < off + n; o++) {
			heads.bias.w[o] = o == off + ActionSpace.DEFAULTS[head] ? restBias : 0f;
			heads.bias.m[o] = 0f;
			heads.bias.v[o] = 0f;
		}
	}

	private static void restart(Dense out, int rest, float restBias) {
		Arrays.fill(out.weight.w, 0f);
		Arrays.fill(out.weight.m, 0f);
		Arrays.fill(out.weight.v, 0f);
		Arrays.fill(out.bias.w, 0f);
		Arrays.fill(out.bias.m, 0f);
		Arrays.fill(out.bias.v, 0f);
		out.bias.w[rest] = restBias;
	}

	/** Per-thread activations and gradient buffers. */
	public final class Workspace {
		final float[] p1 = new float[periConv1.outSize()];
		final float[] p2 = new float[periConv2.outSize()];
		final float[] f1 = new float[foveaConv1.outSize()];
		final float[] f2 = new float[foveaConv2.outSize()];
		final float[] cat = new float[3 * BRANCH];
		final float[] trunk = new float[TRUNK];
		final float[] wide = new float[vecWideIn == null ? 0 : vecWideIn.out];
		final float[] dWide = new float[wide.length];
		final float[] widePre = new float[vecWideIn == null ? 0 : TRUNK];
		/** The trunk after the blocks; the trunk itself when there are none. */
		final float[] top = blocks.length == 0 ? trunk : new float[TRUNK];
		final ResBlock.Cache[] caches = new ResBlock.Cache[blocks.length];
		final float[] tacticHot = new float[tacticIn == null ? 0 : tacticIn.in];
		final float[] tacticPre = new float[tacticIn == null ? 0 : TRUNK];
		/** The tactic of the last forward pass, -1 for none. */
		int tactic = -1;
		final float[] oppPre = new float[oppIn == null ? 0 : TRUNK];
		final float[] film = new float[oppFilm == null ? 0 : TRUNK];
		final float[] oppZ = new float[oppIn == null ? 0 : oppIn.in];
		/** Whether the last forward pass had the reader's numbers. */
		boolean oppUsed;
		/** What the heads read: the trunk after the blocks, scaled by the reader's numbers where there are any. */
		final float[] headIn = oppFilm == null ? top : new float[TRUNK];
		final float[] dHead = new float[oppFilm == null ? 0 : TRUNK];
		final float[] critH = new float[SKILL];
		final float[] dCritH = new float[SKILL];
		final float[] air = new float[AIR];
		final float[] dAir = new float[AIR];
		final float[] dCritAirH = new float[SKILL];
		final float[] swingIn = new float[2];
		final float[] swingOut = new float[2];
		final float[] escapeH = new float[SKILL];
		final float[] dEscapeH = new float[SKILL];
		/** What the bridge adds into the crit or the escape model's units, before they are added in. */
		final float[] bridged = new float[SKILL];
		final RhythmNet.Cache rhythmCache = rhythm == null ? null : rhythm.newCache();
		final float[] rhythmPre = new float[rhythm == null ? 0 : TRUNK];
		final float[] dRhythm = new float[RhythmNet.OUT];
		final float[] dRhythmPart = new float[RhythmNet.OUT];
		/** Whether the last forward pass had the window. */
		boolean rhythmUsed;
		public final float[] logits = new float[ActionSpace.TOTAL_LOGITS];

		final float[] dP1 = new float[p1.length];
		final float[] dP2 = new float[p2.length];
		final float[] dF1 = new float[f1.length];
		final float[] dF2 = new float[f2.length];
		final float[] dCat = new float[cat.length];
		final float[] dTrunk = new float[TRUNK];
		public final float[] dLogits = new float[ActionSpace.TOTAL_LOGITS];

		public final float[][] grads = params.newGrads();

		Workspace() {
			for (int b = 0; b < blocks.length; b++) {
				caches[b] = blocks[b].newCache();
			}
		}
	}

	public Workspace newWorkspace() {
		return new Workspace();
	}

	/** Fills {@code ws.logits}, with no tactic. */
	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec) {
		forward(ws, peri, fovea, vec, -1);
	}

	/**
	 * Fills {@code ws.logits}.
	 *
	 * @param tactic the tactic in force ({@link Tactic}), -1 for none; ignored by a network without the tactic input
	 */
	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, int tactic) {
		forward(ws, peri, fovea, vec, tactic, null);
	}

	/** @param opp the opponent reader's numbers ({@link OpponentReader}), null for none */
	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, int tactic, float[] opp) {
		forward(ws, peri, fovea, vec, tactic, opp, null);
	}

	/**
	 * @param window the opponent reader's window of the last seconds ({@link OpponentReader.Window}), null
	 *               for none; only a network with the memory ({@link RhythmNet}) reads it
	 */
	public void forward(Workspace ws, byte[] peri, byte[] fovea, float[] vec, int tactic, float[] opp, float[] window) {
		if (usePeriphery) {
			periConv1.forwardBytes(peri, ws.p1);
			relu(ws.p1);
			periConv2.forward(ws.p1, ws.p2);
			relu(ws.p2);
			periDense.forward(ws.p2, 0, ws.cat, 0);
		} else {
			Arrays.fill(ws.cat, 0, BRANCH, 0f);
		}
		if (useFovea) {
			foveaConv1.forwardBytes(fovea, ws.f1);
			relu(ws.f1);
			foveaConv2.forward(ws.f1, ws.f2);
			relu(ws.f2);
			foveaDense.forward(ws.f2, 0, ws.cat, BRANCH);
		} else {
			Arrays.fill(ws.cat, BRANCH, 2 * BRANCH, 0f);
		}
		vecDense.forward(vec, 0, ws.cat, 2 * BRANCH);
		relu(ws.cat);
		trunkDense.forward(ws.cat, 0, ws.trunk, 0);
		if (vecWideIn != null) {
			vecWideIn.forward(vec, 0, ws.wide, 0);
			relu(ws.wide);
			vecWideOut.forward(ws.wide, 0, ws.widePre, 0);
			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.widePre[i];
			}
		}
		ws.tactic = tacticIn != null && tactic >= 0 && tactic < tacticIn.in ? tactic : -1;
		if (ws.tactic >= 0) {
			Arrays.fill(ws.tacticHot, 0f);
			ws.tacticHot[ws.tactic] = 1f;
			tacticIn.forward(ws.tacticHot, 0, ws.tacticPre, 0);
			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.tacticPre[i];
			}
		}
		ws.oppUsed = oppIn != null && opp != null;
		if (ws.oppUsed) {
			System.arraycopy(opp, 0, ws.oppZ, 0, ws.oppZ.length);
			oppIn.forward(ws.oppZ, 0, ws.oppPre, 0);
			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.oppPre[i];
			}
		}
		ws.rhythmUsed = rhythm != null && window != null;
		if (ws.rhythmUsed) {
			rhythm.forward(window, ws.rhythmCache);
			rhythmTrunk.forward(ws.rhythmCache.feat, 0, ws.rhythmPre, 0);
			for (int i = 0; i < TRUNK; i++) {
				ws.trunk[i] += ws.rhythmPre[i];
			}
		}
		relu(ws.trunk);
		if (blocks.length > 0) {
			System.arraycopy(ws.trunk, 0, ws.top, 0, TRUNK);
			for (int b = 0; b < blocks.length; b++) {
				blocks[b].forward(ws.top, 0, ws.caches[b]);
			}
		}
		if (oppFilm != null) {
			if (ws.oppUsed) {
				oppFilm.forward(ws.oppZ, 0, ws.film, 0);
				for (int i = 0; i < TRUNK; i++) {
					ws.headIn[i] = ws.top[i] * (1f + ws.film[i]);
				}
			} else {
				System.arraycopy(ws.top, 0, ws.headIn, 0, TRUNK);
			}
		}
		heads.forward(ws.headIn, 0, ws.logits, 0);
		// The jump key is the crit model's; right after a hit of the opponent's combo, W/S is the escape model's.
		critHidden.forward(vec, 0, ws.critH, 0);
		if (critTrunk != null) {
			critTrunk.forward(ws.headIn, 0, ws.bridged, 0);
			add(ws.critH, ws.bridged);
			if (critOpp != null && ws.oppUsed) {
				critOpp.forward(ws.oppZ, 0, ws.bridged, 0);
				add(ws.critH, ws.bridged);
			}
		}
		if (ws.rhythmUsed) {
			rhythmCrit.forward(ws.rhythmCache.feat, 0, ws.bridged, 0);
			add(ws.critH, ws.bridged);
		}
		relu(ws.critH);
		critOut.forward(ws.critH, 0, ws.logits, JUMP_AT);
		boolean escaping = BackNudge.applies(vec);
		if (JumpNudge.inAir(vec)) {
			critAir.forward(ws.critH, 0, ws.air, 0);
			if (JumpNudge.critMoment(vec)) {
				if (!escaping) {
					System.arraycopy(ws.air, 0, ws.logits, FORWARD_AT, 3);
				}
				System.arraycopy(ws.air, 3, ws.logits, SPRINT_AT, 2);
			}
			// The swing is the crit model's own in the air. What is kept of the trunk's is whether it
			// sees a hit there at all: where it would rather not swing, the swing is held back.
			float noHit = noHit(ws.logits[ATTACK_AT + 1] - ws.logits[ATTACK_AT]);
			ws.swingIn[0] = JumpNudge.critSwingReady(vec) ? 1f : 0f;
			ws.swingIn[1] = 1f - ws.swingIn[0];
			critSwing.forward(ws.swingIn, 0, ws.swingOut, 0);
			ws.logits[ATTACK_AT] = ws.air[5] + ws.swingOut[0];
			ws.logits[ATTACK_AT + 1] = ws.air[6] + ws.swingOut[1] - noHit;
		}
		if (escaping) {
			escapeHidden.forward(vec, 0, ws.escapeH, 0);
			if (escapeTrunk != null) {
				escapeTrunk.forward(ws.headIn, 0, ws.bridged, 0);
				add(ws.escapeH, ws.bridged);
			}
			if (ws.rhythmUsed) {
				rhythmEscape.forward(ws.rhythmCache.feat, 0, ws.bridged, 0);
				add(ws.escapeH, ws.bridged);
			}
			relu(ws.escapeH);
			escapeOut.forward(ws.escapeH, 0, ws.logits, FORWARD_AT);
		}
	}

	/**
	 * Back-propagates {@code ws.dLogits} into {@code ws.grads}. Must follow a {@link #forward} call
	 * on the same workspace with the same inputs.
	 */
	public void backward(Workspace ws, byte[] peri, byte[] fovea, float[] vec) {
		// What was learned about a key goes to the model that pressed it and to nothing else: the
		// trunk hears nothing of the jump, and nothing of W/S while the escape model had them.
		Arrays.fill(ws.dRhythm, 0f);
		critOut.backward(ws.critH, 0, ws.dLogits, JUMP_AT, ws.dCritH, 0, ws.grads);
		boolean escaping = BackNudge.applies(vec);
		if (JumpNudge.inAir(vec)) {
			// W/S and the sprint key were the crit model's only where a critical hit could be in the
			// making, and W/S not while the escape model had it.
			Arrays.fill(ws.dAir, 0, 5, 0f);
			if (JumpNudge.critMoment(vec)) {
				if (!escaping) {
					System.arraycopy(ws.dLogits, FORWARD_AT, ws.dAir, 0, 3);
					Arrays.fill(ws.dLogits, FORWARD_AT, FORWARD_AT + 3, 0f);
				}
				System.arraycopy(ws.dLogits, SPRINT_AT, ws.dAir, 3, 2);
				Arrays.fill(ws.dLogits, SPRINT_AT, SPRINT_AT + 2, 0f);
			}
			// The swing was the crit model's. The trunk's part in it, whether it saw a hit, is taken as
			// given: nothing about a swing in the air is learned by the trunk.
			System.arraycopy(ws.dLogits, ATTACK_AT, ws.dAir, 5, 2);
			ws.swingIn[0] = JumpNudge.critSwingReady(vec) ? 1f : 0f;
			ws.swingIn[1] = 1f - ws.swingIn[0];
			critSwing.backward(ws.swingIn, 0, ws.dLogits, ATTACK_AT, null, 0, ws.grads);
			critAir.backward(ws.critH, 0, ws.dAir, 0, ws.dCritAirH, 0, ws.grads);
			for (int i = 0; i < SKILL; i++) {
				ws.dCritH[i] += ws.dCritAirH[i];
			}
			Arrays.fill(ws.dLogits, ATTACK_AT, ATTACK_AT + 2, 0f);
		}
		gate(ws.dCritH, ws.critH);
		critHidden.backward(vec, 0, ws.dCritH, 0, null, 0, ws.grads);
		// The bridge learns, but what it reads is taken as given (headIn is still as the forward pass left it).
		if (critTrunk != null) {
			critTrunk.backward(ws.headIn, 0, ws.dCritH, 0, null, 0, ws.grads);
			if (critOpp != null && ws.oppUsed) {
				critOpp.backward(ws.oppZ, 0, ws.dCritH, 0, null, 0, ws.grads);
			}
		}
		if (ws.rhythmUsed) {
			rhythmCrit.backward(ws.rhythmCache.feat, 0, ws.dCritH, 0, ws.dRhythmPart, 0, ws.grads);
			add(ws.dRhythm, ws.dRhythmPart);
		}
		Arrays.fill(ws.dLogits, JUMP_AT, JUMP_AT + ActionSpace.SIZES[ActionSpace.JUMP], 0f);
		if (escaping) {
			escapeOut.backward(ws.escapeH, 0, ws.dLogits, FORWARD_AT, ws.dEscapeH, 0, ws.grads);
			gate(ws.dEscapeH, ws.escapeH);
			escapeHidden.backward(vec, 0, ws.dEscapeH, 0, null, 0, ws.grads);
			if (escapeTrunk != null) {
				escapeTrunk.backward(ws.headIn, 0, ws.dEscapeH, 0, null, 0, ws.grads);
			}
			if (ws.rhythmUsed) {
				rhythmEscape.backward(ws.rhythmCache.feat, 0, ws.dEscapeH, 0, ws.dRhythmPart, 0, ws.grads);
				add(ws.dRhythm, ws.dRhythmPart);
			}
			Arrays.fill(ws.dLogits, FORWARD_AT, FORWARD_AT + ActionSpace.SIZES[ActionSpace.FORWARD], 0f);
		}
		if (oppFilm == null) {
			heads.backward(ws.top, 0, ws.dLogits, 0, ws.dTrunk, 0, ws.grads);
		} else {
			heads.backward(ws.headIn, 0, ws.dLogits, 0, ws.dHead, 0, ws.grads);
			if (ws.oppUsed) {
				for (int i = 0; i < TRUNK; i++) {
					ws.dTrunk[i] = ws.dHead[i] * (1f + ws.film[i]);
					// The scale's own gradient, put where the reader's numbers go in.
					ws.dHead[i] *= ws.top[i];
				}
				oppFilm.backward(ws.oppZ, 0, ws.dHead, 0, null, 0, ws.grads);
			} else {
				System.arraycopy(ws.dHead, 0, ws.dTrunk, 0, TRUNK);
			}
		}
		for (int b = blocks.length - 1; b >= 0; b--) {
			blocks[b].backward(ws.dTrunk, 0, ws.caches[b], ws.grads);
		}
		gate(ws.dTrunk, ws.trunk);
		if (ws.rhythmUsed) {
			rhythmTrunk.backward(ws.rhythmCache.feat, 0, ws.dTrunk, 0, ws.dRhythmPart, 0, ws.grads);
			add(ws.dRhythm, ws.dRhythmPart);
			rhythm.backward(ws.rhythmCache, ws.dRhythm, ws.grads);
		}
		if (rhythm != null) {
			// What the memory hands on carries no bias of its own: the trunk, the crit model and the escape model
			// each have theirs right where it is added, and a second one would only double their steps (Gemini, 23 October).
			Arrays.fill(ws.grads[rhythmTrunk.bias.id], 0f);
			Arrays.fill(ws.grads[rhythmCrit.bias.id], 0f);
			Arrays.fill(ws.grads[rhythmEscape.bias.id], 0f);
		}
		if (ws.tactic >= 0) {
			tacticIn.backward(ws.tacticHot, 0, ws.dTrunk, 0, null, 0, ws.grads);
		}
		if (ws.oppUsed) {
			oppIn.backward(ws.oppZ, 0, ws.dTrunk, 0, null, 0, ws.grads);
		}
		if (vecWideIn != null) {
			vecWideOut.backward(ws.wide, 0, ws.dTrunk, 0, ws.dWide, 0, ws.grads);
			gate(ws.dWide, ws.wide);
			vecWideIn.backward(vec, 0, ws.dWide, 0, null, 0, ws.grads);
		}
		trunkDense.backward(ws.cat, 0, ws.dTrunk, 0, ws.dCat, 0, ws.grads);
		gate(ws.dCat, ws.cat);

		if (usePeriphery) {
			periDense.backward(ws.p2, 0, ws.dCat, 0, ws.dP2, 0, ws.grads);
			gate(ws.dP2, ws.p2);
			Arrays.fill(ws.dP1, 0f);
			periConv2.backward(ws.p1, ws.dP2, ws.dP1, ws.grads);
			gate(ws.dP1, ws.p1);
			periConv1.backwardBytes(peri, ws.dP1, ws.grads);
		}
		if (useFovea) {
			foveaDense.backward(ws.f2, 0, ws.dCat, BRANCH, ws.dF2, 0, ws.grads);
			gate(ws.dF2, ws.f2);
			Arrays.fill(ws.dF1, 0f);
			foveaConv2.backward(ws.f1, ws.dF2, ws.dF1, ws.grads);
			gate(ws.dF1, ws.f1);
			foveaConv1.backwardBytes(fovea, ws.dF1, ws.grads);
		}
		vecDense.backward(vec, 0, ws.dCat, 2 * BRANCH, null, 0, ws.grads);
	}

	/**
	 * How much a swing in the air is held back for the trunk not seeing a hit there.
	 *
	 * @param swingOverHold how much the trunk prefers swinging to not swinging; below zero it would rather not
	 */
	static float noHit(float swingOverHold) {
		return Math.max(0f, Math.min(NO_HIT_MOST, -NO_HIT_GAIN * swingOverHold));
	}

	private static void add(float[] a, float[] b) {
		for (int i = 0; i < a.length; i++) {
			a[i] += b[i];
		}
	}

	private static void relu(float[] a) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] < 0f) {
				a[i] = 0f;
			}
		}
	}

	/** Zeroes the gradient wherever the (post-ReLU) activation was clamped. */
	private static void gate(float[] grad, float[] activation) {
		for (int i = 0; i < grad.length; i++) {
			if (activation[i] <= 0f) {
				grad[i] = 0f;
			}
		}
	}
}
