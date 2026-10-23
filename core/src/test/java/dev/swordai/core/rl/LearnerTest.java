package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.RewardModel;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.sim.ToyAimEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnerTest {
	private static Config smallConfig(boolean images) {
		Config cfg = new Config();
		cfg.usePeriphery = images;
		cfg.useFovea = images;
		cfg.rolloutSlack = 0;
		return cfg;
	}

	/**
	 * Plays the toy task until there is enough for an update, restarting whenever the target has
	 * been out of view too long (exactly what the mod does in the real game).
	 */
	private static Tally collect(Learner learner, ToyAimEnv env, StageSpec stage, RewardModel rewards, Obs[] obs) {
		Tally tally = new Tally();
		int[] action = new int[ActionSpace.HEADS];
		StepFacts facts = new StepFacts();
		while (!learner.rolloutReady()) {
			learner.act(obs[0], stage.headMask, false, true, action);
			env.step(action, facts);
			float reward = rewards.step(stage, facts, tally);
			boolean lost = rewards.lostTooLong(stage);
			if (lost) {
				rewards.endEpisode(tally, false, false);
				env.reset();
			}
			env.observe(obs[1]);
			learner.complete(reward, false, lost, obs[1]);
			Obs swap = obs[0];
			obs[0] = obs[1];
			obs[1] = swap;
		}
		return tally;
	}

	/**
	 * Trains one stage with an existing learner. Returns the share of time on the opponent in the first
	 * rollout and over the last four, and the share of that time spent at rest over the last four.
	 */
	private static float[] trainStage(Learner learner, Config cfg, int stageIndex, int updates, long seed, boolean freshStage) {
		StageSpec stage = cfg.stages.get(stageIndex);
		// As in the real game, the image the agent decides on is one tick old.
		ToyAimEnv env = new ToyAimEnv(stage, stageIndex, seed, 1, true);
		RewardModel rewards = new RewardModel();
		Obs[] obs = {new Obs(), new Obs()};
		env.observe(obs[0]);
		float first = 0f;
		Tally tail = new Tally();
		for (int u = 0; u < updates; u++) {
			Tally t = collect(learner, env, stage, rewards, obs);
			// Right after a stage change only the critic learns, as in the mod.
			boolean trainActor = !(freshStage && u < cfg.criticWarmupUpdates);
			UpdateStats stats = learner.update(trainActor);
			assertTrue(Float.isFinite(stats.policyLoss) && Float.isFinite(stats.valueLoss), "losses must stay finite");
			assertEquals(trainActor, stats.actorTrained);
			if (u == 0) {
				first = t.onTarget();
			}
			if (u >= updates - 4) {
				tail.add(t);
			}
		}
		return new float[] {first, tail.onTarget(), tail.still()};
	}

	/**
	 * The curriculum in miniature, with one network carried through: horizontal aim with only the yaw
	 * head, then finding and tracking a target anywhere around, then pitch unlocked as well.
	 */
	@Test
	void learnsTheAimStagesCumulatively() {
		Config cfg = smallConfig(false);
		try (Learner learner = new Learner(cfg, 7L, 1)) {
			float[] horizontal = trainStage(learner, cfg, 0, 24, 107L, false);
			System.out.printf("horizontal aim:      on target %5.1f%% -> %5.1f%%, at rest %5.1f%% of that%n",
					horizontal[0] * 100, horizontal[1] * 100, horizontal[2] * 100);
			assertTrue(horizontal[0] < 0.35f, "an untrained policy should rarely be on target, was " + horizontal[0]);
			assertTrue(horizontal[1] > 0.70f, "after training the crosshair should mostly be on the opponent, was " + horizontal[1]);
			assertTrue(horizontal[2] > 0.50f, "and it should rest there instead of fidgeting, was " + horizontal[2]);

			float[] tracking = trainStage(learner, cfg, 1, 40, 108L, true);
			System.out.printf("horizontal tracking: on target %5.1f%% -> %5.1f%%%n", tracking[0] * 100, tracking[1] * 100);
			assertTrue(tracking[1] > 0.35f, "should find and follow a target placed anywhere around, was " + tracking[1]);

			float[] vertical = trainStage(learner, cfg, 2, 44, 109L, true);
			System.out.printf("vertical aim:        on target %5.1f%% -> %5.1f%%%n", vertical[0] * 100, vertical[1] * 100);
			assertTrue(vertical[1] > 0.35f && vertical[1] > vertical[0],
					"aim should improve once pitch is unlocked, went " + vertical[0] + " -> " + vertical[1]);
		}
	}

	@Test
	void criticOnlyUpdateLeavesTheActorUntouched() {
		Config cfg = smallConfig(false);
		cfg.rolloutSteps = 128;
		try (Learner learner = new Learner(cfg, 13L, 1)) {
			StageSpec stage = cfg.stages.get(0);
			ToyAimEnv env = new ToyAimEnv(stage, 0, 5L);
			Obs[] obs = {new Obs(), new Obs()};
			env.observe(obs[0]);
			Obs probe = new Obs();
			probe.copyFrom(obs[0]);
			int[] before = new int[ActionSpace.HEADS];
			int[] after = new int[ActionSpace.HEADS];
			float valueBefore = learner.value(probe);

			collect(learner, env, stage, new RewardModel(), obs);
			learner.act(probe, stage.headMask, true, false, before);
			UpdateStats stats = learner.update(false);
			learner.act(probe, stage.headMask, true, false, after);

			assertFalse(stats.actorTrained);
			assertEquals(0f, stats.actorGradNorm);
			assertArrayEquals(before, after);
			assertTrue(learner.value(probe) != valueBefore, "the critic must still have learned");
		}
	}

	/**
	 * What the actor learns from a batch is "better or worse than the critic expected". From fights
	 * the critic made nothing of (on a server with lag its guesses came out worse than no guess at
	 * all) that is noise, and in 0.13.2 the actor moved a long way on it. When asked to, an update
	 * leaves the actor alone unless the critic explained enough of what happened; the critic learns
	 * either way, so it gets there.
	 */
	@Test
	void theActorIsLeftAloneWhenTheCriticDidNotSeeTheFightsComing() {
		Config cfg = smallConfig(false);
		cfg.rolloutSteps = 128;
		try (Learner learner = new Learner(cfg, 13L, 1)) {
			StageSpec stage = cfg.stages.get(0);
			ToyAimEnv env = new ToyAimEnv(stage, 0, 5L);
			Obs[] obs = {new Obs(), new Obs()};
			env.observe(obs[0]);
			Obs probe = new Obs();
			probe.copyFrom(obs[0]);
			int[] before = new int[ActionSpace.HEADS];
			int[] after = new int[ActionSpace.HEADS];
			float valueBefore = learner.value(probe);

			collect(learner, env, stage, new RewardModel(), obs);
			learner.act(probe, stage.headMask, true, false, before);
			// A critic that has learned nothing yet explains nothing: asked for a fifth, the actor waits.
			UpdateStats stats = learner.update(true, 1f, 0.2f);
			learner.act(probe, stage.headMask, true, false, after);
			assertTrue(stats.explainedVariance < 0.2f, "explained " + stats.explainedVariance);
			assertFalse(stats.actorTrained);
			assertEquals(0f, stats.actorGradNorm);
			assertArrayEquals(before, after);
			assertTrue(learner.value(probe) != valueBefore, "the critic must still have learned");

			// Not asked for anything, the same batch trains it, as in training against the bot.
			collect(learner, env, stage, new RewardModel(), obs);
			stats = learner.update(true, 1f, Float.NEGATIVE_INFINITY);
			assertTrue(stats.actorTrained);
			assertTrue(stats.actorGradNorm > 0f);
		}
	}

	/**
	 * A fight against a person cannot wait while the network learns: a copy of it plays on. The copy
	 * chooses as the network did when it was made, and an update of the network leaves it alone.
	 */
	@Test
	void theCopyKeptToPlayOnIsNotTouchedByAnUpdate() {
		Config cfg = smallConfig(false);
		cfg.rolloutSteps = 128;
		try (Learner learner = new Learner(cfg, 21L, 1)) {
			StageSpec stage = cfg.stages.get(0);
			int allHeads = (1 << ActionSpace.HEADS) - 1;
			Random rng = new Random(3L);
			Obs[] probes = new Obs[12];
			int[][] before = new int[probes.length][ActionSpace.HEADS];
			int[] chosen = new int[ActionSpace.HEADS];
			for (int i = 0; i < probes.length; i++) {
				probes[i] = new Obs();
				randomise(probes[i], rng);
				learner.act(probes[i], allHeads, true, false, before[i]);
			}
			assertThrows(IllegalStateException.class, () -> learner.actStandIn(probes[0], allHeads, true, chosen),
					"there is no copy to play with until one is kept");

			learner.keepStandIn();
			for (int i = 0; i < probes.length; i++) {
				learner.actStandIn(probes[i], allHeads, true, chosen);
				assertArrayEquals(before[i], chosen, "the copy chooses as the network did when it was made");
			}

			// The network learns; several updates, so that its choices have had every chance to move.
			ToyAimEnv env = new ToyAimEnv(stage, 0, 5L);
			Obs[] obs = {new Obs(), new Obs()};
			env.observe(obs[0]);
			RewardModel rewards = new RewardModel();
			for (int u = 0; u < 3; u++) {
				collect(learner, env, stage, rewards, obs);
				assertTrue(learner.update(true).actorTrained, "the network itself must have learned");
			}
			for (int i = 0; i < probes.length; i++) {
				learner.actStandIn(probes[i], allHeads, true, chosen);
				assertArrayEquals(before[i], chosen, "an update must not reach the copy");
			}

			// Kept again, it is the network as it is now.
			learner.keepStandIn();
			int[] now = new int[ActionSpace.HEADS];
			for (int i = 0; i < probes.length; i++) {
				learner.act(probes[i], allHeads, true, false, now);
				learner.actStandIn(probes[i], allHeads, true, chosen);
				assertArrayEquals(now, chosen);
			}
			assertEquals(0, learner.rolloutSize(), "playing the copy records nothing");
			assertFalse(learner.hasPending());
		}
	}

	@Test
	void aStageCanAskForALargerRolloutThanTheDefault() {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		int large = 0;
		for (StageSpec s : cfg.stages) {
			large = Math.max(large, s.rolloutSteps);
		}
		assertTrue(large > cfg.rolloutSteps, "the fighting stages collect more than the default");
		try (Learner learner = new Learner(cfg, 21L, 1)) {
			// Room for the largest rollout plus the longest episode that may still be running.
			assertTrue(learner.rolloutRoom() >= large + cfg.rolloutSlack);
			Obs obs = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			for (int i = 0; i < cfg.rolloutSteps; i++) {
				learner.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				learner.complete(0.1f, false, false, obs);
			}
			assertTrue(learner.rolloutReady(), "enough for a default update");
			assertFalse(learner.rolloutReady(large), "not yet enough for a fighting stage");
			for (int i = cfg.rolloutSteps; i < large; i++) {
				learner.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				learner.complete(0.1f, false, false, obs);
			}
			assertTrue(learner.rolloutReady(large));
			UpdateStats stats = learner.update(true, 0.3f);
			assertEquals(large, stats.samples);
			assertTrue(Float.isFinite(stats.policyLoss) && Float.isFinite(stats.valueLoss));
		}
	}

	@Test
	void lockedHeadsAlwaysTakeTheirDefault() {
		Config cfg = smallConfig(false);
		try (Learner learner = new Learner(cfg, 3L, 1)) {
			Obs obs = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			int mask = ActionSpace.mask(ActionSpace.YAW);
			boolean yawVaried = false;
			for (int i = 0; i < 200; i++) {
				learner.act(obs, mask, false, false, action);
				for (int h = 1; h < ActionSpace.HEADS; h++) {
					assertEquals(ActionSpace.DEFAULTS[h], action[h], ActionSpace.NAMES[h] + " is locked");
				}
				yawVaried |= action[ActionSpace.YAW] != ActionSpace.DEFAULTS[ActionSpace.YAW];
			}
			assertTrue(yawVaried, "the unlocked head must explore");
		}
	}

	@Test
	void fullNetworkWithImageBranchesTrainsWithoutBlowingUp() {
		Config cfg = smallConfig(true);
		cfg.rolloutSteps = 96;
		cfg.minibatch = 48;
		cfg.epochs = 2;
		Random rng = new Random(5);
		try (Learner learner = new Learner(cfg, 5L, 2)) {
			assertTrue(learner.actorParameters() > 100_000);
			Obs obs = new Obs();
			Obs next = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			randomise(obs, rng);
			while (!learner.rolloutReady()) {
				learner.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				randomise(next, rng);
				boolean truncated = rng.nextInt(40) == 0;
				learner.complete(rng.nextFloat() - 0.5f, false, truncated, next);
				obs.copyFrom(next);
			}
			UpdateStats s = learner.update();
			assertEquals(96, s.samples);
			assertTrue(Float.isFinite(s.policyLoss) && Float.isFinite(s.valueLoss) && Float.isFinite(s.approxKl));
			assertTrue(s.actorGradNorm > 0f, "gradients must reach the actor");
			assertEquals(0, learner.rolloutSize());
			assertFalse(learner.hasPending());
		}
	}

	private static void randomise(Obs o, Random rng) {
		for (int i = 0; i < o.peri.length; i++) {
			o.peri[i] = rng.nextInt(12) == 0 ? (byte) rng.nextInt(256) : 0;
		}
		for (int i = 0; i < o.fovea.length; i++) {
			o.fovea[i] = rng.nextInt(4) == 0 ? (byte) rng.nextInt(256) : 0;
		}
		for (int i = 0; i < o.vec.length; i++) {
			o.vec[i] = (float) rng.nextGaussian() * 0.5f;
		}
		for (int i = 0; i < o.priv.length; i++) {
			o.priv[i] = (float) rng.nextGaussian() * 0.5f;
		}
	}

	@Test
	void checkpointRestoresTheExactPolicy(@TempDir Path dir) throws IOException {
		Config cfg = smallConfig(true);
		cfg.rolloutSteps = 64;
		cfg.minibatch = 32;
		cfg.epochs = 1;
		Random rng = new Random(9);
		Path file = dir.resolve("checkpoint.bin");
		Obs probe = new Obs();
		randomise(probe, rng);
		int[] before = new int[ActionSpace.HEADS];
		float valueBefore;

		try (Learner a = new Learner(cfg, 31L, 1)) {
			Obs obs = new Obs();
			Obs next = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			randomise(obs, rng);
			while (!a.rolloutReady()) {
				a.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				randomise(next, rng);
				a.complete(rng.nextFloat(), false, false, next);
				obs.copyFrom(next);
			}
			a.update();
			a.act(probe, ActionSpace.ALL_HEADS, true, false, before);
			valueBefore = a.value(probe);
			a.save(file);
			assertEquals(64, a.totalSteps);
		}

		// A different seed gives different weights until the checkpoint is loaded.
		try (Learner b = new Learner(cfg, 999L, 1)) {
			b.load(file);
			int[] after = new int[ActionSpace.HEADS];
			b.act(probe, ActionSpace.ALL_HEADS, true, false, after);
			assertArrayEquals(before, after);
			assertEquals(valueBefore, b.value(probe), 0f);
			assertEquals(64, b.totalSteps);
			assertEquals(1, b.updates);
		}

		Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		try (Learner c = new Learner(cfg, 1L, 1)) {
			assertThrows(IOException.class, () -> c.load(file));
		}
	}

	/**
	 * Writes a saved checkpoint again the way a version before the newest scalar wrote it: one input
	 * fewer, and no weights for it in the two layers that read the vector.
	 */
	private static void writeAsBeforeTheNewestScalar(Path current, Path old) throws IOException {
		// The newest scalars, added together: all of them go.
		int n = ObsSpec.SCALARS - ObsSpec.SCALARS_BEFORE[ObsSpec.SCALARS_BEFORE.length - 1];
		int gone = ObsSpec.VEC_SCALARS + ObsSpec.SCALARS - n;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(current)));
				DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(old)))) {
			out.writeInt(in.readInt());
			out.writeInt(in.readInt());
			out.writeInt(in.readInt() - n);
			out.writeInt(in.readInt());
			out.writeInt(in.readInt());
			out.writeLong(in.readLong());
			out.writeLong(in.readLong());
			for (int net = 0; net < 2; net++) {
				out.writeLong(in.readLong());
				int tensors = in.readInt();
				out.writeInt(tensors);
				for (int i = 0; i < tensors; i++) {
					String name = in.readUTF();
					int len = in.readInt();
					int inputs = name.equals("vec.dense.w") ? ObsSpec.VEC : name.equals("critic.l1.w") ? ObsSpec.CRITIC_IN : 0;
					int cols = inputs > 0 ? len / inputs : 0;
					out.writeUTF(name);
					out.writeInt(len - cols * n);
					for (int part = 0; part < 3; part++) {
						for (int j = 0; j < len; j++) {
							float f = in.readFloat();
							if (cols == 0 || j / cols < gone || j / cols >= gone + n) {
								out.writeFloat(f);
							}
						}
					}
				}
			}
			out.write(in.readAllBytes());
		}
	}

	/**
	 * A network trained before an input existed has to play on exactly as it did: the new input
	 * starts with no say at all, and everything after it in the vector still lands on its old weights.
	 */
	@Test
	void aCheckpointFromBeforeTheNewestInputPlaysExactlyAsItDid(@TempDir Path dir) throws IOException {
		assertEquals(ObsSpec.SCALARS - 1, ObsSpec.SC_STYLE_DIST, "the newest scalars are the last ones");
		assertEquals(ObsSpec.SC_STYLE_AWAY, ObsSpec.SCALARS_BEFORE[ObsSpec.SCALARS_BEFORE.length - 1], "and the count before them is known to the loader");
		Config cfg = smallConfig(true);
		cfg.rolloutSteps = 64;
		cfg.minibatch = 32;
		cfg.epochs = 1;
		Random rng = new Random(21);
		int slot = ObsSpec.VEC_SCALARS + ObsSpec.SC_STYLE_AWAY;
		int slots = ObsSpec.SCALARS - ObsSpec.SC_STYLE_AWAY;
		Path file = dir.resolve("checkpoint.bin");
		Path old = dir.resolve("old.bin");
		Obs[] probes = new Obs[24];
		int[][] before = new int[probes.length][ActionSpace.HEADS];
		float[] valueBefore = new float[probes.length];
		for (int i = 0; i < probes.length; i++) {
			probes[i] = new Obs();
			randomise(probes[i], rng);
			// As the old version saw the world: it had no such inputs.
			java.util.Arrays.fill(probes[i].vec, slot, slot + slots, 0f);
		}

		try (Learner a = new Learner(cfg, 31L, 1)) {
			Obs obs = new Obs();
			Obs next = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			randomise(obs, rng);
			while (!a.rolloutReady()) {
				a.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				randomise(next, rng);
				a.complete(rng.nextFloat(), false, false, next);
				obs.copyFrom(next);
			}
			a.update();
			for (int i = 0; i < probes.length; i++) {
				a.act(probes[i], ActionSpace.ALL_HEADS, true, false, before[i]);
				valueBefore[i] = a.value(probes[i]);
			}
			a.save(file);
		}
		writeAsBeforeTheNewestScalar(file, old);
		assertTrue(Files.size(old) < Files.size(file));

		try (Learner b = new Learner(cfg, 999L, 1)) {
			b.load(old);
			assertTrue(b.upgraded());
			assertFalse(b.loadedWithoutAimMotor());
			assertEquals(64, b.totalSteps);
			int[] after = new int[ActionSpace.HEADS];
			for (int i = 0; i < probes.length; i++) {
				b.act(probes[i], ActionSpace.ALL_HEADS, true, false, after);
				assertArrayEquals(before[i], after, "probe " + i);
				assertEquals(valueBefore[i], b.value(probes[i]), 1e-5f, "probe " + i);
				// Whatever the new input says, nothing listens to it yet.
				java.util.Arrays.fill(probes[i].vec, slot, slot + slots, 1f);
				b.act(probes[i], ActionSpace.ALL_HEADS, true, false, after);
				assertArrayEquals(before[i], after, "probe " + i + " with the new inputs set");
				assertEquals(valueBefore[i], b.value(probes[i]), 1e-5f);
				java.util.Arrays.fill(probes[i].vec, slot, slot + slots, 0f);
			}
			// Saved again it is a checkpoint of this version.
			b.save(file);
		}
		try (Learner c = new Learner(cfg, 5L, 1)) {
			c.load(file);
			assertFalse(c.upgraded());
			int[] after = new int[ActionSpace.HEADS];
			c.act(probes[0], ActionSpace.ALL_HEADS, true, false, after);
			assertArrayEquals(before[0], after);
		}
	}

	/** An observation in which the push towards a jump applies, or one in which it does not. */
	private static void jumpMoment(Obs o, boolean justSwungOnTheGround) {
		o.vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = justSwungOnTheGround ? 1f : 0f;
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = justSwungOnTheGround ? 0.32f : 0.9f;
	}

	private static void comboMoment(Obs o, boolean justHitInACombo) {
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = justHitInACombo ? 2f / 3f : 0f;
		o.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = justHitInACombo ? 0.8f : 0f;
	}

	/**
	 * The push towards S is for the ticks right after a hit of a combo and nowhere else, and it goes
	 * to S and to nothing else. Away from those ticks the network's own choice is exactly what it was.
	 */
	@Test
	void thePushTowardsSIsForTheTicksAfterAComboHitOnly() {
		Obs probe = new Obs();
		comboMoment(probe, true);
		assertTrue(BackNudge.applies(probe.vec));
		probe.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f / 3f;
		assertFalse(BackNudge.applies(probe.vec), "one hit is not a combo");
		probe.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		probe.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 12f / 20f;
		assertFalse(BackNudge.applies(probe.vec), "twelve ticks on, the moment has passed");
		probe.vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - 11f / 20f;
		assertTrue(BackNudge.applies(probe.vec));

		float[] logits = new float[ActionSpace.TOTAL_LOGITS];
		Obs o = new Obs();
		comboMoment(o, true);
		BackNudge.apply(logits, o.vec, 5f);
		for (int i = 0; i < logits.length; i++) {
			assertEquals(i == ActionSpace.OFFSETS[ActionSpace.FORWARD] + BackNudge.BACK ? 5f : 0f, logits[i]);
		}
		float[] elsewhere = new float[ActionSpace.TOTAL_LOGITS];
		comboMoment(o, false);
		BackNudge.apply(elsewhere, o.vec, 5f);
		for (float l : elsewhere) {
			assertEquals(0f, l, "away from a combo nothing is pushed");
		}

		// Played with the push on, it holds S far more right after a combo hit than anywhere else,
		// and what is learned from those steps is the policy that played them.
		Config cfg = smallConfig(false);
		cfg.rolloutSteps = 256;
		cfg.minibatch = 64;
		cfg.epochs = 1;
		cfg.actorLr = 0f;
		cfg.minActorLr = 0f;
		cfg.maxActorLr = 0f;
		Random rng = new Random(9);
		try (Learner a = new Learner(cfg, 79L, 1)) {
			a.setBackNudge(6f);
			Obs obs = new Obs();
			Obs next = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			int[] held = new int[2];
			int[] seen = new int[2];
			boolean moment = true;
			randomise(obs, rng);
			comboMoment(obs, moment);
			while (!a.rolloutReady()) {
				a.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				seen[moment ? 1 : 0]++;
				held[moment ? 1 : 0] += action[ActionSpace.FORWARD] == BackNudge.BACK ? 1 : 0;
				moment = rng.nextBoolean();
				randomise(next, rng);
				comboMoment(next, moment);
				a.complete(rng.nextFloat(), false, false, next);
				obs.copyFrom(next);
			}
			assertTrue(held[1] > 0.9f * seen[1], held[1] + " of " + seen[1]);
			assertTrue(held[0] < 0.6f * seen[0], held[0] + " of " + seen[0]);
			a.setBackNudge(0f);
			UpdateStats stats = a.update();
			assertEquals(0f, stats.approxKl, 1e-6f);
		}
	}

	/**
	 * The push towards a jump right after a swing changes what is played, there and nowhere else, and
	 * what is learned from is the policy with the push in it: looked at again before anything has
	 * changed, every stored step is exactly as likely as it was when it was played.
	 */
	@Test
	void thePushTowardsAJumpIsPartOfThePolicyThatIsLearnedAbout() {
		Config cfg = smallConfig(false);
		cfg.rolloutSteps = 256;
		cfg.minibatch = 64;
		cfg.epochs = 1;
		// Nothing may move, so that the second look is at the very same network.
		cfg.actorLr = 0f;
		cfg.minActorLr = 0f;
		cfg.maxActorLr = 0f;
		Random rng = new Random(3);
		try (Learner a = new Learner(cfg, 77L, 1)) {
			assertEquals(0f, a.jumpNudge());
			a.setJumpNudge(6f);
			Obs obs = new Obs();
			Obs next = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			int[] jumps = new int[2];
			int[] seen = new int[2];
			boolean moment = false;
			randomise(obs, rng);
			jumpMoment(obs, moment);
			while (!a.rolloutReady()) {
				assertEquals(moment, JumpNudge.applies(obs.vec));
				a.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				seen[moment ? 1 : 0]++;
				jumps[moment ? 1 : 0] += action[ActionSpace.JUMP];
				moment = rng.nextBoolean();
				randomise(next, rng);
				jumpMoment(next, moment);
				a.complete(rng.nextFloat(), false, false, next);
				obs.copyFrom(next);
			}
			// A fresh network jumps on about one tick in six. With the push it nearly always does, there.
			assertTrue(jumps[1] > 0.9f * seen[1], jumps[1] + " of " + seen[1]);
			assertTrue(jumps[0] < 0.35f * seen[0], jumps[0] + " of " + seen[0]);
			// The push is switched off before the update: each step is still learned from with the one it was played with.
			a.setJumpNudge(0f);
			UpdateStats stats = a.update();
			assertEquals(0f, stats.approxKl, 1e-6f);
			assertEquals(0f, stats.clipFraction, 0f);
		}

		// The same steps looked at without the push would not be the policy that played them.
		float[] logits = new float[ActionSpace.TOTAL_LOGITS];
		Obs o = new Obs();
		jumpMoment(o, true);
		JumpNudge.apply(logits, o.vec, 6f);
		assertEquals(6f, logits[ActionSpace.OFFSETS[ActionSpace.JUMP] + 1]);
		for (int i = 0; i < logits.length; i++) {
			if (i != ActionSpace.OFFSETS[ActionSpace.JUMP] + 1) {
				assertEquals(0f, logits[i], "only the jump is pushed");
			}
		}
		jumpMoment(o, false);
		float[] untouched = new float[ActionSpace.TOTAL_LOGITS];
		JumpNudge.apply(untouched, o.vec, 6f);
		assertEquals(0f, untouched[ActionSpace.OFFSETS[ActionSpace.JUMP] + 1], "and only in that situation");
	}

	/** A control put back to how a control starts rests on its default again, and nothing else about the network changes. */
	@Test
	void aReopenedControlStartsOverAndTheRestIsUntouched() {
		Config cfg = smallConfig(false);
		cfg.rolloutSteps = 128;
		cfg.minibatch = 64;
		cfg.epochs = 2;
		Random rng = new Random(8);
		try (Learner a = new Learner(cfg, 5L, 1)) {
			// Some training first, so that the heads are no longer as they started.
			Obs obs = new Obs();
			Obs next = new Obs();
			int[] action = new int[ActionSpace.HEADS];
			randomise(obs, rng);
			while (!a.rolloutReady()) {
				a.act(obs, ActionSpace.ALL_HEADS, false, true, action);
				randomise(next, rng);
				// Jumping is punished: this is how the control was unlearned.
				a.complete(action[ActionSpace.JUMP] == 1 ? -1f : 1f, false, false, next);
				obs.copyFrom(next);
			}
			a.update();

			Obs[] probes = new Obs[40];
			int[][] before = new int[probes.length][ActionSpace.HEADS];
			for (int i = 0; i < probes.length; i++) {
				probes[i] = new Obs();
				randomise(probes[i], rng);
				a.act(probes[i], ActionSpace.ALL_HEADS, true, false, before[i]);
			}
			a.reopenHead(ActionSpace.JUMP, 3f);
			int[] after = new int[ActionSpace.HEADS];
			for (int i = 0; i < probes.length; i++) {
				a.act(probes[i], ActionSpace.ALL_HEADS, true, false, after);
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					if (h != ActionSpace.JUMP) {
						assertEquals(before[i][h], after[h], "probe " + i + ", " + ActionSpace.NAMES[h]);
					}
				}
				assertEquals(0, after[ActionSpace.JUMP], "it rests on not jumping");
			}
			// But it is not shut: about one tick in twenty it jumps, whatever it is shown.
			int jumps = 0;
			for (int i = 0; i < 4000; i++) {
				a.act(probes[i % probes.length], ActionSpace.ALL_HEADS, false, false, after);
				jumps += after[ActionSpace.JUMP];
			}
			assertTrue(jumps > 100 && jumps < 320, jumps + " jumps in 4000 ticks");
		}
	}
}
