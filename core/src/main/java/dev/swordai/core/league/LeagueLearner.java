package dev.swordai.core.league;

import dev.swordai.core.nn.*;
import dev.swordai.core.rl.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Trainable recurrent agent facade: owns SwordNet, SwordCritic, ObsMemory, LeagueRollout, LeaguePpo, SWLG persistence. */
public final class LeagueLearner {
	private static final int MAGIC = 0x53574C47, FORMAT = 1;

	public final SwordNet actor;
	public final SwordCritic critic;
	public final ObsMemory memory = new ObsMemory();
	public final LeagueRollout rollout;
	public final LeaguePpo ppo;
	private final SwordNet.Workspace actorWs;
	private final SwordCritic.Workspace criticWs;
	private final Random rng;
	private final float[] lastHistIn = new float[ObsMemory.HIST], lastSeenIn = new float[ObsMemory.SEEN], lastHIn = new float[ObsMemory.GRU_HIDDEN];
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
	public float[] lastHistIn() { return lastHistIn; }
	public float[] lastSeenIn() { return lastSeenIn; }
	public float[] lastHiddenIn() { return lastHIn; }

	public static final class Decision {
		public final int[] action = new int[ActionSpace.HEADS];
		public float logp, value;
	}

	public void decide(Obs obs, boolean greedy, float temperature, Decision out) {
		memory.pushSeen(obs.vec);
		System.arraycopy(memory.hist, 0, lastHistIn, 0, ObsMemory.HIST);
		System.arraycopy(memory.seen, 0, lastSeenIn, 0, ObsMemory.SEEN);
		System.arraycopy(memory.hidden, 0, lastHIn, 0, ObsMemory.GRU_HIDDEN);
		actor.forward(actorWs, actorWs.step, obs.peri, obs.fovea, obs.vec, lastHistIn, lastSeenIn, lastHIn, memory.hidden);
		out.logp = actor.sample(actorWs, actorWs.step, ActionSpace.ALL_HEADS, greedy, temperature, rng, out.action);
		out.value = critic.forward(criticWs, obs.vec, obs.priv, memory.hidden);
		memory.pushAction(out.action);
	}

	public float bootstrapValue(Obs obs) { return critic.forward(criticWs, obs.vec, obs.priv, memory.hidden); }

	public void record(Obs obs, Decision dec, float reward, boolean done, int[] demoAction, float demoWeight) {
		rollout.add(obs, lastHistIn, lastSeenIn, lastHIn, ActionSpace.ALL_HEADS, dec.action, dec.logp, dec.value, reward, done, demoAction, demoWeight);
		totalSteps++;
		if (done) memory.reset();
	}

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
		int magic;
		try (DataInputStream p = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) { magic = p.readInt(); }
		if (magic == 0x53575343) { actor.takeOverFromSchool(path); return true; }
		if (magic != MAGIC) throw new IOException("unknown magic in " + path);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			in.readInt(); in.readInt(); int savedVec = in.readInt(); in.readInt(); in.readInt(); in.readInt();
			totalSteps = in.readLong(); updates = in.readLong(); ppo.lr = in.readFloat();
			readParams(in, actor.params, savedVec); readParams(in, critic.params, savedVec);
		}
		return true;
	}

	public static SwordNet loadActorOnly(Path path) throws IOException {
		SwordNet net = new SwordNet(1L);
		int magic;
		try (DataInputStream p = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) { magic = p.readInt(); }
		if (magic == 0x53575343) { net.takeOverFromSchool(path); return net; }
		if (magic != MAGIC) throw new IOException("unknown magic in " + path);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			in.readInt(); in.readInt(); int savedVec = in.readInt(); in.readInt(); in.readInt(); in.readInt();
			in.readLong(); in.readLong(); in.readFloat();
			readParams(in, net.params, savedVec);
		}
		return net;
	}

	private static void writeParams(DataOutputStream out, ParamSet ps) throws IOException {
		out.writeLong(ps.adamSteps); out.writeInt(ps.params().size());
		for (Param p : ps.params()) {
			out.writeUTF(p.name); out.writeInt(p.w.length);
			for (float v : p.w) out.writeFloat(v);
			for (float v : p.m) out.writeFloat(v);
			for (float v : p.v) out.writeFloat(v);
		}
	}

	private static void readParams(DataInputStream in, ParamSet ps, int savedVec) throws IOException {
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
		int inserted = ObsSpec.VEC - savedVec, insertAt = ObsSpec.VEC_SCALARS + (ObsSpec.SCALARS - Math.max(0, inserted));
		for (Param p : ps.params()) {
			float[][] a = loaded.get(p.name); if (a == null) continue;
			if (a[0].length == p.w.length) {
				System.arraycopy(a[0], 0, p.w, 0, p.w.length);
				System.arraycopy(a[1], 0, p.m, 0, p.m.length);
				System.arraycopy(a[2], 0, p.v, 0, p.v.length);
			} else if (inserted > 0 && (p.name.equals("vec.dense.w") || p.name.equals("vec.wide.w"))) {
				int cols = p.w.length / ObsSpec.VEC;
				if (a[0].length == savedVec * cols) {
					Arrays.fill(p.w, 0f); Arrays.fill(p.m, 0f); Arrays.fill(p.v, 0f);
					System.arraycopy(a[0], 0, p.w, 0, insertAt * cols);
					System.arraycopy(a[0], insertAt * cols, p.w, (insertAt + inserted) * cols, a[0].length - insertAt * cols);
				}
			}
		}
	}
}
