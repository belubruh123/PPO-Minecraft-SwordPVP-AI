package dev.swordai.core.rl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The user's own fights, step by step, for the network to learn from ({@code /swordai demo}; the user,
 * 22 October: "I can do later but make it so I can start when I want"). Each step holds what the
 * network would have seen there (picture, numbers, its reading of the opponent, the tactic), the
 * action the person took as the network would have had to choose it ({@link HumanAction}), which
 * heads were the network's to choose there (the rules take some), and the reward the training gives
 * for it. One gzipped file a fight.
 *
 * <p>Loaded ({@code Learner.loadDemos}), the steps go into a {@link SilBuffer} with their returns and
 * are imitated the way self-imitation imitates the network's own best steps: only where the person
 * did better than the critic expects of the network (Oh et al., 2018; demonstrations kept and learned
 * from beside the agent's own play as in Hester et al., DQfD, 2018). A person's mistakes are not learned.
 */
public final class DemoFile {
	static final int MAGIC = 0x53444D4F;
	static final int VERSION = 1;
	/** Step flags. */
	static final int TERMINAL = 1;

	private DemoFile() {
	}

	/** One step as recorded. */
	private static final class Step {
		final byte[] peri;
		final byte[] fovea;
		final float[] vec;
		final float[] priv;
		final float[] opp;
		final int tactic;
		final int task;
		final int headMask;
		final int[] action = new int[ActionSpace.HEADS];
		float reward;
		int flags;

		Step(Obs obs, int headMask) {
			peri = obs.peri.clone();
			fovea = obs.fovea.clone();
			vec = obs.vec.clone();
			priv = obs.priv.clone();
			opp = obs.opp.clone();
			tactic = obs.tactic;
			task = obs.task;
			this.headMask = headMask;
			System.arraycopy(ActionSpace.DEFAULTS, 0, action, 0, ActionSpace.HEADS);
		}
	}

	/**
	 * A fight being recorded. A step is begun when the network would have chosen its action, its action
	 * is filled in as the person's keys, click and turn become known, and it is closed with the reward
	 * the next tick scores it with.
	 */
	public static final class Recorder {
		private final List<Step> steps = new ArrayList<>();
		private Step pending;

		/** Begins the step taken from {@code obs}, with the heads in {@code headMask} the network's to choose. */
		public void begin(Obs obs, int headMask) {
			pending = new Step(obs, headMask);
		}

		public boolean pending() {
			return pending != null;
		}

		/** The open step's action, to be filled in; see {@link HumanAction}. */
		public int[] action() {
			return pending.action;
		}

		/** Closes the open step with what it brought; {@code terminal}: nothing comes after it. */
		public void complete(float reward, boolean terminal) {
			if (pending == null) {
				return;
			}
			pending.reward = reward;
			pending.flags = terminal ? TERMINAL : 0;
			steps.add(pending);
			pending = null;
		}

		/** Forgets the open step: it was not scored (the fight was interrupted). */
		public void drop() {
			pending = null;
		}

		public int size() {
			return steps.size();
		}

		/** Writes the closed steps as one fight against {@code kind} and starts over. Nothing is written for none. */
		public void write(Path file, String kind) throws IOException {
			if (steps.isEmpty()) {
				return;
			}
			Files.createDirectories(file.toAbsolutePath().getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				out.writeInt(ObsSpec.PERI_SIZE);
				out.writeInt(ObsSpec.FOVEA_SIZE);
				out.writeInt(ObsSpec.VEC);
				out.writeInt(ObsSpec.PRIV);
				out.writeInt(OpponentReader.Z);
				out.writeInt(ActionSpace.HEADS);
				out.writeUTF(kind);
				out.writeInt(steps.size());
				for (Step s : steps) {
					out.write(s.peri);
					out.write(s.fovea);
					writeFloats(out, s.vec);
					writeFloats(out, s.priv);
					writeFloats(out, s.opp);
					for (int h = 0; h < ActionSpace.HEADS; h++) {
						out.writeByte(s.action[h]);
					}
					out.writeInt(s.headMask);
					out.writeInt(s.tactic);
					out.writeInt(s.task);
					out.writeFloat(s.reward);
					out.writeByte(s.flags);
				}
			}
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			steps.clear();
		}

		public void clear() {
			steps.clear();
			pending = null;
		}
	}

	/** One recorded fight, read back. */
	public static final class Fight {
		public final String kind;
		public final int size;
		public final byte[][] peri;
		public final byte[][] fovea;
		public final float[][] vec;
		public final float[][] priv;
		public final float[][] opp;
		public final byte[][] action;
		public final int[] headMask;
		public final int[] tactic;
		public final int[] task;
		public final float[] reward;
		public final boolean[] terminal;

		Fight(String kind, int size) {
			this.kind = kind;
			this.size = size;
			peri = new byte[size][ObsSpec.PERI_SIZE];
			fovea = new byte[size][ObsSpec.FOVEA_SIZE];
			vec = new float[size][ObsSpec.VEC];
			priv = new float[size][ObsSpec.PRIV];
			opp = new float[size][OpponentReader.Z];
			action = new byte[size][ActionSpace.HEADS];
			headMask = new int[size];
			tactic = new int[size];
			task = new int[size];
			reward = new float[size];
			terminal = new boolean[size];
		}
	}

	/** Reads a fight written by {@link Recorder#write}; one recorded with other input sizes cannot be used and is refused. */
	public static Fight read(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION) {
				throw new IOException(file.getFileName() + " is not a recorded fight of this version");
			}
			int[] sizes = {in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()};
			int[] ours = {ObsSpec.PERI_SIZE, ObsSpec.FOVEA_SIZE, ObsSpec.VEC, ObsSpec.PRIV, OpponentReader.Z, ActionSpace.HEADS};
			if (!java.util.Arrays.equals(sizes, ours)) {
				throw new IOException(file.getFileName() + " was recorded with other inputs");
			}
			String kind = in.readUTF();
			Fight f = new Fight(kind, in.readInt());
			for (int i = 0; i < f.size; i++) {
				in.readFully(f.peri[i]);
				in.readFully(f.fovea[i]);
				readFloats(in, f.vec[i]);
				readFloats(in, f.priv[i]);
				readFloats(in, f.opp[i]);
				in.readFully(f.action[i]);
				f.headMask[i] = in.readInt();
				f.tactic[i] = in.readInt();
				f.task[i] = in.readInt();
				f.reward[i] = in.readFloat();
				f.terminal[i] = (in.readByte() & TERMINAL) != 0;
			}
			return f;
		}
	}

	/**
	 * Lambda-returns of a fight, as {@code Rollout.computeAdvantages} makes them: {@code reward} already
	 * scaled, {@code value} the critic's for each step. A fight that was not ended by a knockout or the
	 * clock goes on from its last step as the critic expects of it.
	 */
	public static float[] returns(float[] reward, boolean[] terminal, float[] value, float gamma, float lambda) {
		int n = reward.length;
		float[] ret = new float[n];
		float gae = 0f;
		for (int t = n - 1; t >= 0; t--) {
			float next;
			if (terminal[t]) {
				next = 0f;
				gae = 0f;
			} else {
				next = t == n - 1 ? value[t] : value[t + 1];
			}
			float delta = reward[t] + gamma * next - value[t];
			gae = delta + gamma * lambda * gae;
			ret[t] = gae + value[t];
		}
		return ret;
	}

	/**
	 * Which steps were followed by a stretch that went the person's way: the reward of the {@code window} ticks
	 * from the step on (as far as the fight goes) adds up to more than nothing. All of them for a window of 0.
	 */
	public static boolean[] wentTheirWay(float[] reward, int window) {
		return wentTheirWay(reward, window, 0f);
	}

	/** The same, with the stretch's reward to come to more than {@code least}. */
	public static boolean[] wentTheirWay(float[] reward, int window, float least) {
		int n = reward.length;
		boolean[] keep = new boolean[n];
		if (window <= 0) {
			java.util.Arrays.fill(keep, true);
			return keep;
		}
		double sum = 0;
		int end = 0;
		for (int t = 0; t < n; t++) {
			while (end < n && end < t + window) {
				sum += reward[end++];
			}
			keep[t] = sum > least;
			sum -= reward[t];
		}
		return keep;
	}

	private static void writeFloats(DataOutputStream out, float[] xs) throws IOException {
		for (float x : xs) {
			out.writeFloat(x);
		}
	}

	private static void readFloats(DataInputStream in, float[] xs) throws IOException {
		for (int i = 0; i < xs.length; i++) {
			xs[i] = in.readFloat();
		}
	}
}
