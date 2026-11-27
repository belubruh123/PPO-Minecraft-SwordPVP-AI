package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Trainable recurrent generalist agent facade: owns SwordNet (MoE + 256-GRU + AlphaStar SlotPointer + Terrain),
 * SwordCritic, ObsMemory, LeagueRollout, LeaguePpo, and exact OpenAI-Five-style Net2Net SWLG surgery.
 */
public final class LeagueLearner {
	private static final int MAGIC = 0x53574C47, FORMAT = 2;

	public final SwordNet actor;
	public final SwordCritic critic;
	public final ObsMemory memory = new ObsMemory();
	public final LeagueRollout rollout;
	public final LeaguePpo ppo;
	private final SwordNet.Workspace actorWs;
	private final SwordCritic.Workspace criticWs;
	private final Random rng;
	private final float[] lastHistIn = new float[ObsMemory.HIST], lastSeenIn = new float[ObsMemory.SEEN], lastHIn = new float[ObsMemory.GRU_HIDDEN];
	private final float[] bootSeen = new float[ObsMemory.SEEN], bootHOut = new float[ObsMemory.GRU_HIDDEN];
	public long totalSteps, updates;

	public LeagueLearner(int rolloutSize, long seed) {
		this.actor = new SwordNet(seed);
		this.critic = new SwordCritic(seed ^ 0x9E3779B97F4A7C15L);
		this.rollout = new LeagueRollout(rolloutSize);
		this.ppo = new LeaguePpo(actor, critic, seed + 17L);
		this.actorWs = actor.newWorkspace();
		this.criticWs = critic.newWorkspace();
		this.rng = new Random(seed + 31L);
	}

	public void resetEpisode() { memory.reset(); }
	public void restoreMemory(float[] hist, float[] seen, float[] hidden) {
		if (hist != null) System.arraycopy(hist, 0, memory.hist, 0, ObsMemory.HIST);
		if (seen != null) System.arraycopy(seen, 0, memory.seen, 0, ObsMemory.SEEN);
		if (hidden != null) System.arraycopy(hidden, 0, memory.hidden, 0, ObsMemory.GRU_HIDDEN);
	}
	public boolean hasNonFiniteWeights() {
		for (Param p : actor.params.params()) for (float v : p.w) if (!Float.isFinite(v)) return true;
		return false;
	}
	public float[] lastHistIn() { return lastHistIn; }
	public float[] lastSeenIn() { return lastSeenIn; }
	public float[] lastHiddenIn() { return lastHIn; }

	public static final class Decision {
		public final int[] action = new int[ActionSpace.HEADS];
		public float logp, value;
	}

	public void decide(Obs obs, boolean greedy, float temperature, Decision out) {
		decide(obs, ActionSpace.PHASE1_HEADS, greedy, temperature, out);
	}

	public void decide(Obs obs, int headMask, boolean greedy, float temperature, Decision out) {
		memory.pushSeen(obs.vec);
		System.arraycopy(memory.hist, 0, lastHistIn, 0, ObsMemory.HIST);
		System.arraycopy(memory.seen, 0, lastSeenIn, 0, ObsMemory.SEEN);
		System.arraycopy(memory.hidden, 0, lastHIn, 0, ObsMemory.GRU_HIDDEN);
		actor.forward(actorWs, actorWs.step, obs.peri, obs.fovea, obs.vec, obs.slots, obs.terrain, lastHistIn, lastSeenIn, lastHIn, memory.hidden);
		out.logp = actor.sample(actorWs, actorWs.step, headMask, greedy, temperature, rng, out.action);
		out.value = critic.forward(criticWs, obs.vec, obs.priv, memory.hidden, actorWs.step.kitCtx(), obs.terrain);
		memory.pushAction(out.action);
	}

	public void setGruEnabled(boolean enabled) {
		actor.gruEnabled = enabled;
		if (enabled && actor.gruWeightNorm() == 0f) {
			critic.zeroGruInputWeights();
			actor.markGruBorn(actor.params.adamSteps);
		}
	}

	public float bootstrapValue(Obs obs) {
		if (!actor.gruEnabled) return critic.forward(criticWs, obs.vec, obs.priv, memory.hidden, actorWs.step.kitCtx(), obs.terrain);
		System.arraycopy(memory.seen, 0, bootSeen, ObsMemory.SEEN_PER, ObsMemory.SEEN - ObsMemory.SEEN_PER);
		int s = ObsSpec.VEC_STATS, c = ObsSpec.VEC_SCALARS;
		bootSeen[0] = obs.vec[s + ObsSpec.ST_HEAD_VISIBLE]; bootSeen[1] = obs.vec[s + ObsSpec.ST_HEAD_X];
		bootSeen[2] = obs.vec[s + ObsSpec.ST_HEAD_Y]; bootSeen[3] = obs.vec[s + ObsSpec.ST_HEAD_AREA];
		bootSeen[4] = obs.vec[s + ObsSpec.ST_ANY_AREA]; bootSeen[5] = obs.vec[c + ObsSpec.SC_REL_VEL_FORWARD];
		bootSeen[6] = obs.vec[c + ObsSpec.SC_REL_VEL_UP]; bootSeen[7] = obs.vec[c + ObsSpec.SC_OPP_CHARGE];
		bootSeen[8] = obs.vec[c + ObsSpec.SC_TURNED_YAW]; bootSeen[9] = obs.vec[c + ObsSpec.SC_TURNED_PITCH];
		actor.forward(actorWs, actorWs.step, obs.peri, obs.fovea, obs.vec, obs.slots, obs.terrain, memory.hist, bootSeen, memory.hidden, bootHOut);
		return critic.forward(criticWs, obs.vec, obs.priv, bootHOut, actorWs.step.kitCtx(), obs.terrain);
	}

	public void record(Obs obs, Decision dec, float reward, boolean done, int[] demoAction, float demoWeight) {
		record(obs, ActionSpace.PHASE1_HEADS, dec, reward, done, demoAction, demoWeight);
	}

	public void record(Obs obs, int headMask, Decision dec, float reward, boolean done, int[] demoAction, float demoWeight) {
		rollout.add(obs, lastHistIn, lastSeenIn, lastHIn, headMask, dec.action, dec.logp, dec.value, reward, done, demoAction, demoWeight);
		totalSteps++;
		if (done) memory.reset();
	}

	public void recordStyle(float[] styleProbs, float styleWeight) { rollout.setStyle(styleProbs, styleWeight); }

	public LeaguePpo.Stats update(float bootstrap, SwordNet priorNet, float priorWeight, float kickWeight) {
		rollout.computeGae(bootstrap, LeaguePpo.GAMMA, LeaguePpo.LAMBDA);
		LeaguePpo.Stats s = ppo.update(actor, critic, rollout, priorNet, priorWeight, kickWeight);
		rollout.clear(); updates++;
		return s;
	}

	public static void copyWeights(SwordNet src, SwordNet dst) {
		for (int i = 0; i < src.params.params().size(); i++) {
			Param sp = src.params.params().get(i), dp = dst.params.params().get(i);
			System.arraycopy(sp.w, 0, dp.w, 0, sp.w.length);
		}
	}

	public void resetForExploiter(SwordNet seed, boolean enableGru) {
		copyWeights(seed, actor);
		actor.params.adamSteps = 0;
		for (Param p : actor.params.params()) { Arrays.fill(p.m, 0f); Arrays.fill(p.v, 0f); p.bornAt = 0; }
		SwordCritic fresh = new SwordCritic(System.nanoTime());
		for (int i = 0; i < critic.params.params().size(); i++) {
			Param sp = fresh.params.params().get(i), dp = critic.params.params().get(i);
			System.arraycopy(sp.w, 0, dp.w, 0, sp.w.length);
			Arrays.fill(dp.m, 0f); Arrays.fill(dp.v, 0f); dp.bornAt = 0;
		}
		critic.params.adamSteps = 0;
		rollout.clear(); memory.reset();
		ppo.lr = 1.8e-4f;
		setGruEnabled(enableGru);
	}

	public void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeInt(ObsSpec.VEC); out.writeInt(ObsSpec.PRIV);
			out.writeInt(ObsMemory.GRU_HIDDEN); out.writeInt(ActionSpace.TOTAL_LOGITS);
			out.writeLong(totalSteps); out.writeLong(updates); out.writeFloat(ppo.lr);
			writeParams(out, actor.params); writeParams(out, critic.params);
		}
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public boolean load(Path path) throws IOException {
		if (!Files.exists(path)) return false;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			int magic = in.readInt();
			if (magic != MAGIC) throw new IOException("unknown magic in " + path);
			in.readInt(); // format
			int savedVec = in.readInt(), savedPriv = in.readInt(), savedGru = in.readInt(), savedLogits = in.readInt();
			totalSteps = in.readLong(); updates = in.readLong(); ppo.lr = in.readFloat();
			boolean didSurgery = readActorParams(in, actor, savedVec, savedGru, savedLogits);
			readCriticParams(in, critic, savedVec, savedPriv, savedGru);
			if (didSurgery) {
				actor.initNeutralSmpBiases();
				actor.markSmpBorn(actor.params.adamSteps);
			}
		}
		return true;
	}

	public static void saveActorOnly(SwordNet net, Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeInt(ObsSpec.VEC); out.writeInt(ObsSpec.PRIV);
			out.writeInt(ObsMemory.GRU_HIDDEN); out.writeInt(ActionSpace.TOTAL_LOGITS);
			out.writeLong(0L); out.writeLong(0L); out.writeFloat(0f);
			writeParams(out, net.params);
		}
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static SwordNet loadActorOnly(Path path) throws IOException {
		SwordNet net = new SwordNet(1L);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			int magic = in.readInt();
			if (magic != MAGIC) throw new IOException("unknown magic in " + path);
			in.readInt();
			int savedVec = in.readInt(); in.readInt(); int savedGru = in.readInt(); int savedLogits = in.readInt();
			in.readLong(); in.readLong(); in.readFloat();
			boolean didSurgery = readActorParams(in, net, savedVec, savedGru, savedLogits);
			if (didSurgery) net.initNeutralSmpBiases();
		}
		net.params.dropOptimizerState();
		return net;
	}

	/** Whether this actor has non-zero SMP/MoE/Pointer weights (false on freshly surgeried Stage 5 sword-master.bin). */
	public static boolean hasSmpWeights(SwordNet net) {
		for (Param p : net.params.params()) {
			if (p.name.equals("slot.ptrQ.w") || p.name.equals("moe.e0.down.w") || p.name.equals("kit.heads.w")) {
				for (float v : p.w) if (Math.abs(v) > 1e-6f) return true;
			}
		}
		return false;
	}

	private static void writeParams(DataOutputStream out, ParamSet ps) throws IOException {
		out.writeLong(ps.adamSteps); out.writeInt(ps.params().size());
		for (Param p : ps.params()) {
			out.writeUTF(p.name); out.writeInt(p.w.length);
			for (float v : p.w) out.writeFloat(v);
			if (p.m != null) for (float v : p.m) out.writeFloat(v); else for (int k = 0; k < p.w.length; k++) out.writeFloat(0f);
			if (p.v != null) for (float v : p.v) out.writeFloat(v); else for (int k = 0; k < p.w.length; k++) out.writeFloat(0f);
		}
	}

	private static Map<String, float[][]> readParamMap(DataInputStream in, ParamSet ps) throws IOException {
		ps.adamSteps = in.readLong(); int n = in.readInt();
		Map<String, float[][]> loaded = new HashMap<>();
		for (int i = 0; i < n; i++) {
			String name = in.readUTF(); int len = in.readInt();
			float[] w = new float[len], m = new float[len], v = new float[len];
			for (int k = 0; k < len; k++) w[k] = in.readFloat();
			for (int k = 0; k < len; k++) m[k] = in.readFloat();
			for (int k = 0; k < len; k++) v[k] = in.readFloat();
			loaded.put(name, new float[][] {w, m, v});
		}
		return loaded;
	}

	/**
	 * Exact OpenAI-Five / Net2Net function-preserving weight surgery from Stage 5 Sword checkpoints
	 * ({@code vec=115, gru=128, logits=38}) into the SMP Generalist network ({@code vec=148, gru=256, logits=54}).
	 */
	private static boolean readActorParams(DataInputStream in, SwordNet actor, int savedVec, int savedGru, int savedLogits) throws IOException {
		Map<String, float[][]> loaded = readParamMap(in, actor.params);
		boolean surgery = (savedVec != ObsSpec.VEC || savedGru != ObsMemory.GRU_HIDDEN || savedLogits != ActionSpace.TOTAL_LOGITS);
		int newH = ObsMemory.GRU_HIDDEN;

		for (Param p : actor.params.params()) {
			float[][] a = loaded.get(p.name);
			if (a == null) continue;
			float[] sw = a[0], sm = a[1], sv = a[2];
			if (sw.length == p.w.length) {
				System.arraycopy(sw, 0, p.w, 0, p.w.length);
				System.arraycopy(sm, 0, p.m, 0, p.m.length);
				System.arraycopy(sv, 0, p.v, 0, p.v.length);
				continue;
			}
			// 1. Vec input layers (vec.dense.w, vec.wide.w): map [stats(32), oldScalars(38), oldLastAct(38), oldMask(7)] -> new 148 layout
			if ((p.name.equals("vec.dense.w") || p.name.equals("vec.wide.w")) && savedVec == ObsSpec.LEGACY_VEC) {
				int cols = p.w.length / ObsSpec.VEC;
				if (sw.length == savedVec * cols) {
					zeroParam(p);
					// stats(32) + old scalars(38) -> [0 .. 70)
					copyRows(sw, sm, sv, p, 0, 0, ObsSpec.VEC_SCALARS + ObsSpec.LEGACY_SCALARS, cols);
					// old lastAction(38) -> [VEC_LAST_ACTION .. VEC_LAST_ACTION+38)
					copyRows(sw, sm, sv, p, ObsSpec.LEGACY_VEC_LAST_ACTION, ObsSpec.VEC_LAST_ACTION, ActionSpace.LEGACY_TOTAL_LOGITS, cols);
					// old headMask(7) -> [VEC_HEAD_MASK .. VEC_HEAD_MASK+7)
					copyRows(sw, sm, sv, p, ObsSpec.LEGACY_VEC_HEAD_MASK, ObsSpec.VEC_HEAD_MASK, ActionSpace.LEGACY_HEADS, cols);
				}
			}
			// 2. GRU 128 -> 256 block-preserving surgery (old units 0..127 keep exact dynamics; new units 128..255 decoupled at tick 0)
			else if (p.name.equals("gru.in.w") && savedGru < newH && sw.length == SwordNet.CAT * 3 * savedGru) {
				zeroMoments(p);
				for (int row = 0; row < SwordNet.CAT; row++) {
					for (int g = 0; g < 3; g++) {
						int srcOff = row * (3 * savedGru) + g * savedGru;
						int dstOff = row * (3 * newH) + g * newH;
						System.arraycopy(sw, srcOff, p.w, dstOff, savedGru);
						System.arraycopy(sm, srcOff, p.m, dstOff, savedGru);
						System.arraycopy(sv, srcOff, p.v, dstOff, savedGru);
					}
				}
			} else if (p.name.equals("gru.in.b") && savedGru < newH && sw.length == 3 * savedGru) {
				zeroMoments(p);
				for (int g = 0; g < 3; g++) {
					System.arraycopy(sw, g * savedGru, p.w, g * newH, savedGru);
					System.arraycopy(sm, g * savedGru, p.m, g * newH, savedGru);
					System.arraycopy(sv, g * savedGru, p.v, g * newH, savedGru);
				}
			} else if (p.name.equals("gru.hid.w") && savedGru < newH && sw.length == savedGru * 3 * savedGru) {
				zeroMoments(p);
				// Copy old 128x128 gate sub-blocks and ZERO cross-weights from new units (128..255) into old units (0..127)
				for (int row = 0; row < savedGru; row++) {
					for (int g = 0; g < 3; g++) {
						int srcOff = row * (3 * savedGru) + g * savedGru;
						int dstOff = row * (3 * newH) + g * newH;
						System.arraycopy(sw, srcOff, p.w, dstOff, savedGru);
						System.arraycopy(sm, srcOff, p.m, dstOff, savedGru);
						System.arraycopy(sv, srcOff, p.v, dstOff, savedGru);
					}
				}
				for (int row = savedGru; row < newH; row++) {
					for (int g = 0; g < 3; g++) {
						Arrays.fill(p.w, row * (3 * newH) + g * newH, row * (3 * newH) + g * newH + savedGru, 0f);
					}
				}
			} else if (p.name.equals("gru.hid.b") && savedGru < newH && sw.length == 3 * savedGru) {
				zeroMoments(p);
				for (int g = 0; g < 3; g++) {
					System.arraycopy(sw, g * savedGru, p.w, g * newH, savedGru);
					System.arraycopy(sm, g * savedGru, p.m, g * newH, savedGru);
					System.arraycopy(sv, g * savedGru, p.v, g * newH, savedGru);
				}
			} else if (p.name.equals("gru.new.w") && savedGru < newH && sw.length == savedGru * SwordNet.NEW) {
				zeroParam(p);
				copyRows(sw, sm, sv, p, 0, 0, savedGru, SwordNet.NEW);
			}
			// 3. Policy output heads (38 -> 54 logits; plus gru.heads 128x38 -> 256x54)
			else if (p.name.equals("gru.heads.w") && sw.length == savedGru * savedLogits) {
				zeroParam(p);
				int rows = Math.min(savedGru, newH), cols = Math.min(savedLogits, ActionSpace.TOTAL_LOGITS);
				for (int r = 0; r < rows; r++) {
					System.arraycopy(sw, r * savedLogits, p.w, r * ActionSpace.TOTAL_LOGITS, cols);
					System.arraycopy(sm, r * savedLogits, p.m, r * ActionSpace.TOTAL_LOGITS, cols);
					System.arraycopy(sv, r * savedLogits, p.v, r * ActionSpace.TOTAL_LOGITS, cols);
				}
			} else if ((p.name.equals("heads.w") || p.name.equals("hist.heads.w") || p.name.equals("seen.heads.w"))
					&& savedLogits < ActionSpace.TOTAL_LOGITS) {
				int rows = p.w.length / ActionSpace.TOTAL_LOGITS;
				if (sw.length == rows * savedLogits) {
					zeroParam(p);
					for (int r = 0; r < rows; r++) {
						System.arraycopy(sw, r * savedLogits, p.w, r * ActionSpace.TOTAL_LOGITS, savedLogits);
						System.arraycopy(sm, r * savedLogits, p.m, r * ActionSpace.TOTAL_LOGITS, savedLogits);
						System.arraycopy(sv, r * savedLogits, p.v, r * ActionSpace.TOTAL_LOGITS, savedLogits);
					}
				}
			} else if ((p.name.equals("heads.b") || p.name.equals("hist.heads.b") || p.name.equals("seen.heads.b") || p.name.equals("gru.heads.b"))
					&& savedLogits < ActionSpace.TOTAL_LOGITS && sw.length == savedLogits) {
				zeroParam(p);
				System.arraycopy(sw, 0, p.w, 0, savedLogits);
				System.arraycopy(sm, 0, p.m, 0, savedLogits);
				System.arraycopy(sv, 0, p.v, 0, savedLogits);
			}
		}
		return surgery;
	}

	private static void readCriticParams(DataInputStream in, SwordCritic critic, int savedVec, int savedPriv, int savedGru) throws IOException {
		Map<String, float[][]> loaded = readParamMap(in, critic.params);
		boolean exact = (savedVec == ObsSpec.VEC && savedPriv == ObsSpec.PRIV && savedGru == ObsMemory.GRU_HIDDEN);
		if (!exact) {
			// OpenAI Five surgery protocol: when critic architecture widens (256 -> 384 + kit + terrain), keep fresh critic & warm up with high prior KL.
			critic.params.adamSteps = 0;
			return;
		}
		for (Param p : critic.params.params()) {
			float[][] a = loaded.get(p.name);
			if (a != null && a[0].length == p.w.length) {
				System.arraycopy(a[0], 0, p.w, 0, p.w.length);
				System.arraycopy(a[1], 0, p.m, 0, p.m.length);
				System.arraycopy(a[2], 0, p.v, 0, p.v.length);
			}
		}
	}

	private static void zeroParam(Param p) { Arrays.fill(p.w, 0f); Arrays.fill(p.m, 0f); Arrays.fill(p.v, 0f); }
	private static void zeroMoments(Param p) { Arrays.fill(p.m, 0f); Arrays.fill(p.v, 0f); }
	private static void copyRows(float[] sw, float[] sm, float[] sv, Param dst, int srcRow, int dstRow, int nRows, int cols) {
		System.arraycopy(sw, srcRow * cols, dst.w, dstRow * cols, nRows * cols);
		System.arraycopy(sm, srcRow * cols, dst.m, dstRow * cols, nRows * cols);
		System.arraycopy(sv, srcRow * cols, dst.v, dstRow * cols, nRows * cols);
	}
}
