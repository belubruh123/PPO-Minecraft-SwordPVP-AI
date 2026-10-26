package dev.swordai.core.school;

import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChooserTest {
	private static final int T10 = Technique.T10.ordinal();
	private static final int BASE = Technique.BASE.ordinal();

	private static void intent(float[] side, int technique, float ticks) {
		java.util.Arrays.fill(side, Predictor.OUT, SchoolNet.SIDE, 0f);
		side[Predictor.OUT + technique] = 1f;
		side[Predictor.OUT + SchoolNet.INTENT_TICKS] = ticks;
	}

	/** A new network plays as it did whatever technique it is told: the weights from the technique start at zero. */
	@Test
	void theTechniqueToldChangesNothingAtFirst() {
		SchoolNet net = new SchoolNet(3L);
		SchoolNet.Workspace ws = net.newWorkspace();
		SchoolObs obs = new SchoolObs();
		Random rng = new Random(2);
		for (int i = 0; i < ObsSpec.VEC; i++) {
			obs.base.vec[i] = rng.nextFloat();
		}
		float[] side = new float[SchoolNet.SIDE];
		intent(side, BASE, 0f);
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, side);
		float[] ordinary = ws.logits.clone();
		intent(side, T10, 0.5f);
		net.forward(ws, obs.base.peri, obs.base.fovea, obs.base.vec, obs.mem, side);
		assertArrayEquals(ordinary, ws.logits, 0f);
	}

	/**
	 * The teacher starts an uppercut when one input is high and carries on while it was doing one: the chooser learns
	 * both, and does not start one where the teacher does not.
	 */
	@Test
	void chooserLearnsWhenTheTeacherStarts() {
		SchoolRollout r = new SchoolRollout(4000);
		Random rng = new Random(5);
		SchoolRollout.Step s = new SchoolRollout.Step();
		int doing = BASE;
		int ticks = 0;
		for (int t = 0; t < 4000; t++) {
			for (int i = 0; i < ObsSpec.VEC; i++) {
				s.obs.base.vec[i] = rng.nextFloat() * 0.4f;
			}
			boolean moment = rng.nextInt(10) == 0;
			if (moment) {
				s.obs.base.vec[3] = 1f;
			}
			intent(s.pred, doing, Math.min(1f, ticks / SchoolNet.INTENT_LONG));
			System.arraycopy(s.pred, Predictor.OUT, s.prevIntent, 0, SchoolNet.INTENT);
			// The uppercut lasts five ticks.
			int label = doing == T10 && ticks < 4 ? T10 : moment && doing == BASE ? T10 : BASE;
			ticks = label == doing ? ticks + 1 : 0;
			doing = label;
			s.intentLabel = label;
			intent(s.pred, label, Math.min(1f, ticks / SchoolNet.INTENT_LONG));
			r.add(s, 0f, false);
		}
		Chooser c = new Chooser(1L);
		Chooser.Stats st = null;
		for (int round = 0; round < 30; round++) {
			st = c.learn(r, 3e-3f, 60, 64, 1f, rng);
		}
		assertTrue(st.rightStart > 0.8 * st.starts, st.toString());
		assertTrue(st.rightTechnique > 0.8 * st.technique, st.toString());
		assertTrue(st.falseStarts < 0.1 * st.calm, st.toString());
	}

	/** In a training fight told the teacher's techniques the network is told the teacher's; outside training, the chooser's. */
	@Test
	void theNetworkIsToldTheTeachersOrTheChoosersTechnique() {
		SchoolLearner l = new SchoolLearner(4L, 64, 0.99f, 0.95f, 1);
		SchoolRollout.Step s = new SchoolRollout.Step();
		int[] act = new int[ActionSpace.HEADS];
		l.teacherForcing = 1f;
		l.resetIntent();
		s.teacherTechnique = T10;
		l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
		assertEquals(Technique.T10, l.told());
		assertEquals(T10, s.intentLabel);
		assertEquals(1f, s.pred[Predictor.OUT + T10]);
		assertEquals(0f, s.pred[Predictor.OUT + SchoolNet.INTENT_TICKS]);
		l.complete(0f, false, false, s.obs);
		l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
		assertEquals(1f / SchoolNet.INTENT_LONG, s.pred[Predictor.OUT + SchoolNet.INTENT_TICKS], 1e-6f);
		// What it was told the tick before is the chooser's input.
		assertEquals(1f, s.prevIntent[T10]);
		l.complete(0f, false, false, s.obs);

		assertEquals(-1, s.choice, "told the teacher's, so not its own choice");

		l.teacherForcing = 0f;
		l.resetIntent();
		l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
		// In training its own choice is drawn from its chances (Chooser.OUTCOME) and kept to learn from.
		assertEquals(l.told().ordinal(), s.choice);
		assertEquals(T10, s.intentLabel, "the chooser still learns from the teacher");
		l.complete(0f, false, false, s.obs);
		l.act(s, ActionSpace.ALL_HEADS, false, 1f, act);
		assertEquals(l.chosen(), l.told());
		assertEquals(-1, s.intentLabel);
		assertEquals(-1, s.choice);
		l.close();
	}

	/**
	 * The teacher never does an uppercut, but at the moments (one input high) the fight went better after its own
	 * uppercuts and worse after its ordinary fight: it learns to choose the uppercut there, and only there.
	 */
	@Test
	void chooserLearnsFromTheOutcomeOfItsOwnChoices() {
		SchoolRollout r = new SchoolRollout(4000);
		Random rng = new Random(9);
		SchoolRollout.Step s = new SchoolRollout.Step();
		boolean[] moment = new boolean[4000];
		for (int t = 0; t < 4000; t++) {
			for (int i = 0; i < ObsSpec.VEC; i++) {
				s.obs.base.vec[i] = rng.nextFloat() * 0.4f;
			}
			moment[t] = rng.nextInt(10) == 0;
			if (moment[t]) {
				s.obs.base.vec[3] = 1f;
			}
			intent(s.pred, BASE, 0f);
			System.arraycopy(s.pred, Predictor.OUT, s.prevIntent, 0, SchoolNet.INTENT);
			s.intentLabel = BASE;
			s.choice = moment[t] && rng.nextBoolean() ? T10 : BASE;
			r.add(s, 0f, false);
			r.advantage[t] = moment[t] ? (s.choice == T10 ? 2f : -2f) : 0f;
		}
		Chooser c = new Chooser(1L);
		Chooser.Stats st = null;
		for (int round = 0; round < 30; round++) {
			st = c.learn(r, 3e-3f, 60, 64, 1f, rng);
		}
		Chooser.Workspace ws = c.newWorkspace();
		int uppercutAtMoments = 0;
		int moments = 0;
		int uppercutElsewhere = 0;
		for (int t = 0; t < 4000; t++) {
			int best = c.forward(ws, r.vec[t], r.mem[t], r.pred[t], r.prevIntent[t]);
			if (moment[t]) {
				moments++;
				uppercutAtMoments += best == T10 ? 1 : 0;
			} else {
				uppercutElsewhere += best == T10 ? 1 : 0;
			}
		}
		assertTrue(uppercutAtMoments > 0.9 * moments, uppercutAtMoments + " of " + moments + "; " + st);
		assertTrue(uppercutElsewhere < 0.02 * (4000 - moments), uppercutElsewhere + " elsewhere; " + st);
	}

	@Test
	void techniqueOfReadsTheOneHot() {
		float[] side = new float[SchoolNet.SIDE];
		intent(side, T10, 1f);
		assertEquals(T10, Chooser.techniqueOf(side, Predictor.OUT));
		assertNotEquals(T10, Chooser.techniqueOf(new float[SchoolNet.INTENT], 0));
	}

	/** The running school's checkpoint (format 1, no chooser) loads: set SWORDAI_SCHOOL_CHECKPOINT to its path. */
	@Test
	void aCheckpointWithoutTheChooserLoads() throws Exception {
		String path = System.getenv("SWORDAI_SCHOOL_CHECKPOINT");
		Assumptions.assumeTrue(path != null && Files.exists(Path.of(path)), "no school checkpoint given");
		SchoolLearner l = new SchoolLearner(1L, 16, 0.99f, 0.95f, 1);
		l.load(Path.of(path));
		assertTrue(l.totalSteps > 0);
		assertEquals(4, l.actor.params.missing(), "the two layers from the technique are new");
		l.close();
	}
}
