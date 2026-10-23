package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learning from the user's own fights: their actions as the network's ({@link HumanAction}), recorded and read back ({@link DemoFile}). */
class DemoTest {
	private static Obs randomObs(Random rng) {
		Obs o = new Obs();
		for (int i = 0; i < ObsSpec.VEC; i++) {
			o.vec[i] = rng.nextFloat() * 2f - 1f;
		}
		for (int i = 0; i < ObsSpec.PERI_SIZE; i++) {
			o.peri[i] = (byte) rng.nextInt(256);
		}
		o.opp[3] = 0.5f;
		o.tactic = 2;
		o.task = 12;
		return o;
	}

	@Test
	void aPersonsSteadyTurnIsTheBinsThatMakeTheHandTurnAsFast() {
		AimMotor motor = new AimMotor(0.5f);
		float turned = 10f;
		for (int tick = 0; tick < 12; tick++) {
			int bin = HumanAction.turnBin(ActionSpace.YAW_BINS, motor.yawPush(), turned, 0.5f);
			motor.step(ActionSpace.YAW_BINS[bin], 0f);
		}
		assertEquals(turned, motor.yawRate(), 3f);
		// Holding still from rest is the bin for no turn.
		assertEquals(7, HumanAction.turnBin(ActionSpace.YAW_BINS, 0f, 0f, 0.5f));
		assertEquals(5, HumanAction.turnBin(ActionSpace.PITCH_BINS, 0f, 0f, 0.5f));
		// A flick to the left from rest wants the fastest bin that way.
		assertEquals(0, HumanAction.turnBin(ActionSpace.YAW_BINS, 0f, -30f, 0.5f));
	}

	@Test
	void keysAreTheHeadsTheyPress() {
		int[] action = new int[ActionSpace.HEADS];
		HumanAction.keys(action, true, false, false, true, true, false, true);
		assertEquals(1, action[ActionSpace.FORWARD]);
		assertEquals(2, action[ActionSpace.STRAFE]);
		assertEquals(1, action[ActionSpace.SPRINT]);
		assertEquals(0, action[ActionSpace.JUMP]);
		assertEquals(1, action[ActionSpace.ATTACK]);
		HumanAction.keys(action, false, true, true, false, false, true, false);
		assertEquals(2, action[ActionSpace.FORWARD]);
		assertEquals(1, action[ActionSpace.STRAFE]);
		assertEquals(1, action[ActionSpace.JUMP]);
		assertEquals(0, action[ActionSpace.ATTACK]);
	}

	@Test
	void aRecordedFightReadsBackAsItWasRecorded(@TempDir Path dir) throws Exception {
		Random rng = new Random(4);
		DemoFile.Recorder rec = new DemoFile.Recorder();
		Obs[] seen = new Obs[3];
		for (int i = 0; i < 3; i++) {
			seen[i] = randomObs(rng);
			rec.begin(seen[i], ActionSpace.ALL_HEADS & ~(1 << ActionSpace.ATTACK));
			rec.action()[ActionSpace.FORWARD] = 1;
			rec.action()[ActionSpace.YAW] = i;
			rec.complete(i == 2 ? 1f : 0.25f, i == 2);
		}
		// A step begun and never scored is not kept.
		rec.begin(randomObs(rng), ActionSpace.ALL_HEADS);
		rec.drop();
		Path file = dir.resolve("fight.bin.gz");
		rec.write(file, "spacer");
		assertEquals(0, rec.size());
		DemoFile.Fight f = DemoFile.read(file);
		assertEquals("spacer", f.kind);
		assertEquals(3, f.size);
		for (int i = 0; i < 3; i++) {
			assertArrayEquals(seen[i].peri, f.peri[i]);
			assertArrayEquals(seen[i].vec, f.vec[i]);
			assertArrayEquals(seen[i].opp, f.opp[i]);
			assertEquals(2, f.tactic[i]);
			assertEquals(12, f.task[i]);
			assertEquals(1, f.action[i][ActionSpace.FORWARD]);
			assertEquals(i, f.action[i][ActionSpace.YAW]);
			assertEquals(ActionSpace.DEFAULTS[ActionSpace.PITCH], f.action[i][ActionSpace.PITCH]);
			assertEquals(ActionSpace.ALL_HEADS & ~(1 << ActionSpace.ATTACK), f.headMask[i]);
		}
		assertEquals(0.25f, f.reward[0]);
		assertTrue(f.terminal[2]);
	}

	@Test
	void returnsAreTheLambdaReturnsOfTheFight() {
		float g = 0.9f;
		float l = 0.5f;
		float[] ret = DemoFile.returns(new float[]{0f, 0f, 1f}, new boolean[]{false, false, true}, new float[3], g, l);
		assertEquals(1f, ret[2], 1e-6f);
		assertEquals(g * l, ret[1], 1e-6f);
		assertEquals(g * l * g * l, ret[0], 1e-6f);
		// Not ended: it goes on from its last step as the critic expects of it.
		float[] cut = DemoFile.returns(new float[]{0f}, new boolean[]{false}, new float[]{2f}, g, l);
		assertEquals(g * 2f, cut[0], 1e-6f);
	}

	@Test
	void aPersonsActionThatDidBetterThanExpectedIsMadeLikelier(@TempDir Path dir) throws Exception {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		Random rng = new Random(5);
		DemoFile.Recorder rec = new DemoFile.Recorder();
		Obs obs = randomObs(rng);
		// What the untrained network is unlikely to do: the last choice of every head.
		byte[] a = new byte[ActionSpace.HEADS];
		for (int i = 0; i < 20; i++) {
			rec.begin(obs, ActionSpace.ALL_HEADS);
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				rec.action()[h] = ActionSpace.SIZES[h] - 1;
				a[h] = (byte) (ActionSpace.SIZES[h] - 1);
			}
			rec.complete(i == 19 ? 50f : 1f, i == 19);
		}
		Path file = dir.resolve("f.bin.gz");
		rec.write(file, "spacer");
		ActorNet actor = new ActorNet(7L, false, false);
		CriticNet critic = new CriticNet(8L, cfg.privilegedCritic);
		Ppo ppo = new Ppo(actor, critic, cfg, 1);
		DemoFile.Fight f = DemoFile.read(file);
		float[] ret = DemoFile.returns(f.reward, f.terminal, new float[f.size], cfg.gamma, cfg.lambda);
		SilBuffer buf = new SilBuffer(f.size);
		for (int i = 0; i < f.size; i++) {
			buf.put(f, i, ret[i]);
		}
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		float[] ent = new float[ActionSpace.HEADS];
		ActorNet.Workspace ws = actor.newWorkspace();
		actor.forward(ws, obs.peri, obs.fovea, obs.vec);
		float before = Policy.evaluate(ws.logits, ActionSpace.ALL_HEADS, a, probs, ent);
		Ppo.SilStats s = ppo.selfImitate(buf, rng, 50, 1f, 0f, 1f);
		assertTrue(s.stillBetter > 0);
		actor.forward(ws, obs.peri, obs.fovea, obs.vec);
		float after = Policy.evaluate(ws.logits, ActionSpace.ALL_HEADS, a, probs, ent);
		assertTrue(after > before + 0.05f, "log-probability " + before + " -> " + after);
		ppo.close();
	}

	@Test
	void onlyStepsFollowedByAStretchThatWentTheirWayAreKept() {
		// Hit at tick 3 (-2), a hit landed at tick 6 (+1): with a window of 3, the steps whose next three ticks hold the
		// hit taken are left out, the ones leading to the landed hit are kept.
		float[] reward = {0f, 0f, 0f, -2f, 0f, 0f, 1f, 0f};
		boolean[] keep = DemoFile.wentTheirWay(reward, 3);
		assertArrayEquals(new boolean[]{false, false, false, false, true, true, true, false}, keep);
		// A crit combo (4.5) passes a bar of 3.5; a single hit (2) does not.
		assertArrayEquals(new boolean[]{true, false, false}, DemoFile.wentTheirWay(new float[]{4.5f, 0f, 2f}, 2, 3.5f));
		boolean[] all = DemoFile.wentTheirWay(reward, 0);
		for (boolean b : all) {
			assertTrue(b);
		}
	}

	@Test
	void theLearnerReadsTheRecordedFightsUpToItsLimit(@TempDir Path dir) throws Exception {
		Random rng = new Random(6);
		for (int k = 0; k < 3; k++) {
			DemoFile.Recorder rec = new DemoFile.Recorder();
			for (int i = 0; i < 10; i++) {
				rec.begin(randomObs(rng), ActionSpace.ALL_HEADS);
				rec.complete(0.1f, i == 9);
			}
			rec.write(dir.resolve("f" + k + ".bin.gz"), k == 0 ? "stander" : "spacer");
		}
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.demoMaxSteps = 25;
		try (Learner learner = new Learner(cfg, 7, 1)) {
			String line = learner.loadDemos(List.of(dir.resolve("f0.bin.gz"), dir.resolve("f1.bin.gz"), dir.resolve("f2.bin.gz"), dir.resolve("missing.bin.gz")));
			assertTrue(line.startsWith("20 steps from 2 recorded fights"), line);
		}
	}

	/** With the memory ({@code Config.rhythmNet}) every recorded step is kept with the reader's window, made again from the fight's ticks. */
	@Test
	void theRecordedStepsGetTheirWindowBack(@TempDir Path dir) throws Exception {
		Random rng = new Random(8);
		DemoFile.Recorder rec = new DemoFile.Recorder();
		OpponentReader.Window played = new OpponentReader.Window();
		float[] last = new float[OpponentReader.INPUTS];
		for (int i = 0; i < 12; i++) {
			Obs o = randomObs(rng);
			played.push(o.vec);
			played.write(last);
			rec.begin(o, ActionSpace.ALL_HEADS);
			rec.complete(0.1f, i == 11);
		}
		rec.write(dir.resolve("f.bin.gz"), "spacer");
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.tacticModel = true;
		cfg.opponentReader = true;
		cfg.rhythmNet = true;
		try (Learner learner = new Learner(cfg, 9, 1)) {
			learner.loadDemos(List.of(dir.resolve("f.bin.gz")));
			assertEquals(12, learner.demos.size);
			for (int j = 0; j < learner.demos.size; j++) {
				assertTrue(learner.demos.window[j] != null, "step " + j);
			}
			assertArrayEquals(last, learner.demos.window[11], 0f, "the last step's window as it was while fighting");
		}
	}
}
