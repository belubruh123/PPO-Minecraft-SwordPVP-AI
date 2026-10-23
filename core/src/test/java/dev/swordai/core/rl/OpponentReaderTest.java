package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opponent reader (21 October 2026): its window, its learning to name the opponent, and that it is saved with the network. */
class OpponentReaderTest {
	private static final int SPACER = 12;
	private static final int NO_KNOCKBACK = 5;

	/** One tick against an opponent at about 3.5 blocks, in sight. */
	private static float[] tick(Random rng, float distanceBlocks) {
		float[] vec = new float[ObsSpec.VEC];
		vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		// JumpIn.distance reads the opponent's height on screen.
		float height = dev.swordai.core.vision.ViewGeometry.apparentSize(JumpIn.PLAYER_HEIGHT, 1f) / distanceBlocks;
		vec[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.5f + height / 2f;
		vec[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = 0.5f - height / 2f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = rng.nextFloat();
		return vec;
	}

	@Test
	void theWindowKeepsHitsAndTheKnockbackTheyGave() {
		OpponentReader.Window w = new OpponentReader.Window();
		Random rng = new Random(1L);
		float[] into = new float[OpponentReader.INPUTS];
		for (int t = 0; t < 2 * OpponentReader.SAMPLES * OpponentReader.STRIDE; t++) {
			float[] vec = tick(rng, 3f);
			if (t == 101) {
				// A hit landed on an odd tick: it is not averaged away by the sample it falls in.
				vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
			}
			if (t == 101 + OpponentReader.Window.KNOCKBACK_TICK) {
				// Four ticks after it the opponent is moving away at a quarter of a block a tick.
				vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.25f * BodyState.VELOCITY_SCALE;
			}
			w.push(vec);
		}
		w.write(into);
		int hits = 0;
		float lastKnockback = into[(OpponentReader.SAMPLES - 1) * OpponentReader.CHANNELS + 8];
		for (int s = 0; s < OpponentReader.SAMPLES; s++) {
			if (into[s * OpponentReader.CHANNELS + 6] > 0.99f) {
				hits++;
			}
		}
		assertEquals(1, hits, "the hit landed shows, whole, in one sample");
		assertEquals(1f, lastKnockback, 1e-4f, "and how far it sent the opponent is held after it (4 x 0.25)");
		assertEquals(3f / 8f, into[0], 0.05f, "the distance, in eighths of a block");
	}

	/** A fight's window against a spacer (stands at the edge, takes knockback) or the no-knockback bot (comes in, takes none). */
	private static float[] fight(int kind, Random rng, int ticks) {
		OpponentReader.Window w = new OpponentReader.Window();
		for (int t = 0; t < ticks; t++) {
			float[] vec = tick(rng, kind == SPACER ? 3.3f + rng.nextFloat() * 0.4f : 2.2f + rng.nextFloat() * 0.6f);
			if (t % 14 == 3) {
				vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_DEALT] = 1f;
			}
			if (t % 14 == 3 + OpponentReader.Window.KNOCKBACK_TICK && kind == SPACER) {
				vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.3f * BodyState.VELOCITY_SCALE;
			}
			w.push(vec);
		}
		float[] window = new float[OpponentReader.INPUTS];
		w.write(window);
		return window;
	}

	@Test
	void itLearnsToNameTheOpponentFromUpdatesOfOneKindEach() {
		OpponentReader reader = new OpponentReader(2L);
		OpponentReader.Memory memory = new OpponentReader.Memory(4000);
		Random rng = new Random(3L);
		Obs o = new Obs();
		int n = 256;
		OpponentReader.Stats last = null;
		for (int update = 0; update < 24; update++) {
			Rollout r = new Rollout(n);
			// As in training: each update's fights are against one kind of opponent.
			int kind = update % 2 == 0 ? SPACER : NO_KNOCKBACK;
			for (int i = 0; i < n; i++) {
				System.arraycopy(fight(kind, rng, 20 + rng.nextInt(60)), 0, o.oppWindow, 0, OpponentReader.INPUTS);
				o.task = kind;
				r.add(o, new int[ActionSpace.HEADS], 0, 0f, 0f, 0f, false);
			}
			last = reader.learn(r, memory, 1e-3f, 16, 32, 0.5f, rng);
		}
		assertTrue(last.right > 0.9f * last.labelled, "named right " + last.right + " of " + last.labelled + " (loss " + last.loss + ")");
	}

	@Test
	void personsReplaysAndDrillsAreNotTaught() {
		assertEquals(-1, OpponentReader.label(16), "a replay of a lost fight");
		assertEquals(-1, OpponentReader.label(11), "a person");
		assertEquals(-1, OpponentReader.label(18), "a drill");
		assertEquals(SPACER, OpponentReader.label(SPACER));
		assertEquals(-1, OpponentReader.label(-1));
	}

	@Test
	void theGradientIsTheLossesSlope() {
		OpponentReader reader = new OpponentReader(4L);
		Random rng = new Random(5L);
		OpponentReader.Workspace ws = reader.newWorkspace();
		float[] window = fight(SPACER, rng, 70);
		float[] c = new float[OpponentReader.CLASSES];
		for (int k = 0; k < c.length; k++) {
			c[k] = (float) rng.nextGaussian();
		}
		ParamSet.zero(ws.grads);
		reader.forward(ws, window);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		reader.backward(ws, window);
		for (Param p : reader.params.params()) {
			for (int k = 0; k < 10; k++) {
				int i = rng.nextInt(p.w.length);
				float keep = p.w[i];
				float h = 1e-2f;
				p.w[i] = keep + h;
				reader.forward(ws, window);
				double up = dot(c, ws.logits);
				p.w[i] = keep - h;
				reader.forward(ws, window);
				double down = dot(c, ws.logits);
				p.w[i] = keep;
				double numeric = (up - down) / (2.0 * h);
				assertEquals(numeric, ws.grads[p.id][i], 3e-2 * Math.max(1.0, Math.abs(numeric)), p.name + "[" + i + "]");
			}
		}
	}

	private static double dot(float[] a, float[] b) {
		double s = 0;
		for (int i = 0; i < a.length; i++) {
			s += a[i] * b[i];
		}
		return s;
	}

	private static Config config(boolean reader) {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.actorBlocks = 0;
		cfg.actorVecWide = 0;
		cfg.criticBlocks = 0;
		cfg.tacticModel = true;
		cfg.opponentReader = reader;
		return cfg;
	}

	@Test
	void itIsSavedWithTheNetworkAndALoadedTacticsNetworkGetsAFreshOne(@TempDir Path dir) throws Exception {
		Path withReader = dir.resolve("with.bin");
		Path tacticsOnly = dir.resolve("tactics.bin");
		Obs o = new Obs();
		System.arraycopy(fight(SPACER, new Random(6L), 70), 0, o.oppWindow, 0, OpponentReader.INPUTS);
		float[] read = new float[OpponentReader.Z];
		try (Learner a = new Learner(config(true), 7L, 1)) {
			assertTrue(a.hasReader());
			a.readOpponent(o, false, false);
			System.arraycopy(o.opp, 0, read, 0, read.length);
			for (float z : read) {
				assertTrue(z >= -1f && z <= 1f, "bounded");
			}
			a.save(withReader);
		}
		try (Learner b = new Learner(config(true), 8L, 1)) {
			b.load(withReader);
			assertTrue(b.playsReader());
			assertTrue(b.playsTactics());
			Obs p = new Obs();
			p.copyFrom(o);
			b.readOpponent(p, false, false);
			for (int i = 0; i < read.length; i++) {
				assertEquals(read[i], p.opp[i], 1e-6f);
			}
		}
		try (Learner t = new Learner(config(false), 9L, 1)) {
			t.save(tacticsOnly);
		}
		try (Learner c = new Learner(config(true), 10L, 1)) {
			c.load(tacticsOnly);
			assertTrue(c.playsTactics(), "the tactics model it was saved with");
			assertTrue(c.hasReader(), "training one starts its reader afresh");
			assertFalse(c.playsReader(), "and plays without one");
		}
	}
}
