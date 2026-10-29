package dev.swordai.core.school;

import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchoolLearnerTest {
	private static void fill(Random rng, SchoolRollout.Step s) {
		for (int i = 0; i < ObsSpec.VEC; i++) {
			s.obs.base.vec[i] = rng.nextFloat();
		}
		for (int i = 0; i < ObsSpec.PRIV; i++) {
			s.obs.base.priv[i] = rng.nextFloat();
		}
		for (int i = 0; i < SchoolObs.MEM; i++) {
			s.obs.mem[i] = rng.nextFloat();
		}
		s.obs.setOpponent(rng.nextInt(Technique.COUNT), null, rng.nextInt(Specialists.KINDS));
		s.botTechnique = rng.nextInt(Technique.COUNT);
		s.botKeys = rng.nextInt(128);
		s.policyWeight = rng.nextInt(5) == 0 ? 0f : 1f;
		s.teacherHeads = 0;
		s.teacherWeight = 0f;
		if (rng.nextBoolean()) {
			// The jump taught: always jump here.
			s.teacherHeads = 1 << ActionSpace.JUMP;
			s.teacherTechnique = Technique.T9.ordinal();
			int off = ActionSpace.OFFSETS[ActionSpace.JUMP];
			s.teacher[off] = 0.05f;
			s.teacher[off + 1] = 0.95f;
		}
	}

	/** A few updates where a teacher says "jump": the network's chance of the jump there goes up; and it saves and loads. */
	@Test
	void teacherPullTeachesAndCheckpointRoundTrips(@TempDir Path dir) throws Exception {
		SchoolLearner l = new SchoolLearner(4L, 600, 0.99f, 0.95f, 2);
		Random rng = new Random(8);
		SchoolRollout.Step s = new SchoolRollout.Step();
		int[] act = new int[ActionSpace.HEADS];
		float before = jumpChance(l, rng);
		SchoolPpo.Stats last = null;
		for (int u = 0; u < 4; u++) {
			for (int t = 0; t < 512; t++) {
				fill(rng, s);
				l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
				fill(rng, s);
				l.complete(rng.nextFloat() - 0.5f, t % 100 == 99, false, s.obs);
			}
			SchoolPpo.Settings set = new SchoolPpo.Settings();
			java.util.Arrays.fill(set.techPulls, 1f);
			last = l.update(set, 1e-3f);
			assertTrue(Float.isFinite(last.policyLoss) && Float.isFinite(last.valueLoss), last.toString());
		}
		assertTrue(last.techSteps > 0, last.toString());
		float after = jumpChance(l, rng);
		assertTrue(after > before + 0.2f, "jump chance " + before + " -> " + after);

		Path file = dir.resolve("school.bin");
		l.save(file);
		assertTrue(SchoolLearner.isSchool(file));
		SchoolLearner back = new SchoolLearner(99L, 16, 0.99f, 0.95f, 1);
		back.load(file);
		assertEquals(l.totalSteps, back.totalSteps);
		// The jump's logits in act read the W/S and sprint just drawn from each learner's own draws: compared as the network
		// gives them, told the jump reset (its option layer) and the ordinary fight.
		for (Technique t : new Technique[] {Technique.T9, Technique.BASE}) {
			assertEquals(keyChance(l, t, ActionSpace.JUMP, 1, new Random(1)), keyChance(back, t, ActionSpace.JUMP, 1, new Random(1)), 1e-6f);
		}
		l.close();
		back.close();
	}

	/**
	 * The punish crit's swing under a rule (SchoolStages#PUNISH_SHADOW): where the rule presses the swing key (locked, held
	 * here), the punish crit teacher's "swing" is learned on it only when the step marks it shadowed.
	 */
	@Test
	void aSwingTheRulePressesIsLearnedOnlyWhereShadowed() {
		float[] gain = new float[2];
		int locked = ActionSpace.ALL_HEADS & ~ActionSpace.mask(ActionSpace.ATTACK);
		for (int shadowed = 0; shadowed < 2; shadowed++) {
			SchoolLearner l = new SchoolLearner(4L, 600, 0.99f, 0.95f, 2);
			Random rng = new Random(8);
			SchoolRollout.Step s = new SchoolRollout.Step();
			int[] act = new int[ActionSpace.HEADS];
			float before = swingChance(l, rng);
			SchoolPpo.Stats last = null;
			for (int u = 0; u < 4; u++) {
				for (int t = 0; t < 512; t++) {
					fillSwing(rng, s, shadowed == 1);
					act[ActionSpace.ATTACK] = 0;
					l.act(s, locked, ActionSpace.mask(ActionSpace.ATTACK), true, 1f, act);
					fillSwing(rng, s, shadowed == 1);
					l.complete(rng.nextFloat() - 0.5f, t % 100 == 99, false, s.obs);
				}
				SchoolPpo.Settings set = new SchoolPpo.Settings();
				java.util.Arrays.fill(set.techPulls, 1f);
				last = l.update(set, 1e-3f);
				assertTrue(Float.isFinite(last.policyLoss), last.toString());
			}
			assertEquals(shadowed == 1, last.shadowSwings > 0, last.toString());
			gain[shadowed] = swingChance(l, rng) - before;
			l.close();
		}
		assertTrue(gain[1] > 0.2f && gain[1] > gain[0] + 0.15f, "swing chance gained " + gain[0] + " unshadowed, " + gain[1] + " shadowed");
	}

	/**
	 * The jump at every moment (SchoolStages#JUMP_EVERYWHERE): on the ordinary fight's steps (no technique pull) the teacher's
	 * "no jump" is learned only with the step's own jump pull.
	 */
	@Test
	void theJumpHeldBackIsLearnedWithItsOwnPull() {
		float[] change = new float[2];
		for (int own = 0; own < 2; own++) {
			SchoolLearner l = new SchoolLearner(4L, 600, 0.99f, 0.95f, 2);
			Random rng = new Random(8);
			SchoolRollout.Step s = new SchoolRollout.Step();
			int[] act = new int[ActionSpace.HEADS];
			float before = jumpChance(l, rng);
			for (int u = 0; u < 4; u++) {
				for (int t = 0; t < 512; t++) {
					fillNoJump(rng, s, own == 1);
					l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
					fillNoJump(rng, s, own == 1);
					l.complete(rng.nextFloat() - 0.5f, t % 100 == 99, false, s.obs);
				}
				SchoolPpo.Settings set = new SchoolPpo.Settings();
				java.util.Arrays.fill(set.techPulls, 0f);
				assertTrue(Float.isFinite(l.update(set, 1e-3f).policyLoss));
			}
			change[own] = jumpChance(l, rng) - before;
			l.close();
		}
		assertTrue(change[1] < -0.2f && change[1] < change[0] - 0.15f, "jump chance changed " + change[0] + " without its pull, " + change[1] + " with it");
	}

	/**
	 * A gated lesson (SchoolStages#GATED): the combo escape's S, taught into its option layer, is learned where the network is
	 * told the combo escape and leaves its S where it is told the ordinary fight; the same S taught for a technique with no
	 * option layer (C2) spreads there.
	 */
	@Test
	void aGatedLessonStaysWithItsTechnique() {
		float[][] change = new float[2][];
		Technique[] taught = {Technique.C3, Technique.C2};
		for (int g = 0; g < 2; g++) {
			SchoolLearner l = new SchoolLearner(4L, 600, 0.99f, 0.95f, 2);
			l.teacherForcing = 1f;
			l.resetIntent();
			Random rng = new Random(8);
			SchoolRollout.Step s = new SchoolRollout.Step();
			int[] act = new int[ActionSpace.HEADS];
			float[] before = {backChance(l, taught[g], new Random(3)), backChance(l, Technique.BASE, new Random(3))};
			for (int u = 0; u < 4; u++) {
				for (int t = 0; t < 512; t++) {
					fillBack(rng, s, taught[g]);
					l.act(s, ActionSpace.ALL_HEADS, true, 1f, act);
					fillBack(rng, s, taught[g]);
					l.complete(rng.nextFloat() - 0.5f, t % 100 == 99, false, s.obs);
				}
				SchoolPpo.Settings set = new SchoolPpo.Settings();
				java.util.Arrays.fill(set.techPulls, 1f);
				assertTrue(Float.isFinite(l.update(set, 1e-3f).policyLoss));
			}
			change[g] = new float[] {backChance(l, taught[g], new Random(3)) - before[0], backChance(l, Technique.BASE, new Random(3)) - before[1]};
			l.close();
		}
		String says = "S chance told it / told the ordinary fight: gated " + change[0][0] + " / " + change[0][1] + ", not gated "
				+ change[1][0] + " / " + change[1][1];
		assertTrue(change[0][0] > 0.2f, says);
		assertTrue(change[1][1] > 0.2f, says);
		assertTrue(Math.abs(change[0][1]) < 0.05f, says);
	}

	private static void fillBack(Random rng, SchoolRollout.Step s, Technique t) {
		fill(rng, s);
		s.teacherHeads = 1 << ActionSpace.FORWARD;
		s.teacherTechnique = t.ordinal();
		int off = ActionSpace.OFFSETS[ActionSpace.FORWARD];
		s.teacher[off] = 0.03f;
		s.teacher[off + 1] = 0.02f;
		s.teacher[off + 2] = 0.95f;
	}

	/** The network's chance of S, told technique {@code t}. */
	private static float backChance(SchoolLearner l, Technique t, Random rng) {
		return keyChance(l, t, ActionSpace.FORWARD, 2, rng);
	}

	/** The network's chance of key {@code k} of head {@code head}, told technique {@code t}. */
	private static float keyChance(SchoolLearner l, Technique t, int head, int k, Random rng) {
		SchoolRollout.Step s = new SchoolRollout.Step();
		SchoolNet.Workspace ws = l.actor.newWorkspace();
		float[] side = new float[SchoolNet.SIDE];
		side[Predictor.OUT + t.ordinal()] = 1f;
		float[] probs = new float[ActionSpace.TOTAL_LOGITS];
		int off = ActionSpace.OFFSETS[head];
		float sum = 0f;
		for (int i = 0; i < 50; i++) {
			fill(rng, s);
			l.actor.forward(ws, s.obs.base.peri, s.obs.base.fovea, s.obs.base.vec, s.obs.mem, side);
			dev.swordai.core.rl.Policy.softmax(ws.logits, off, ActionSpace.SIZES[head], probs);
			sum += probs[off + k];
		}
		return sum / 50;
	}

	private static void fillNoJump(Random rng, SchoolRollout.Step s, boolean own) {
		fill(rng, s);
		s.teacherHeads = 1 << ActionSpace.JUMP;
		s.teacherTechnique = Technique.BASE.ordinal();
		int off = ActionSpace.OFFSETS[ActionSpace.JUMP];
		s.teacher[off] = 1f;
		s.teacher[off + 1] = 0f;
		s.jumpPull = own ? 1f : 0f;
	}

	private static void fillSwing(Random rng, SchoolRollout.Step s, boolean shadowed) {
		fill(rng, s);
		s.teacherHeads = 1 << ActionSpace.ATTACK;
		s.teacherTechnique = Technique.B1.ordinal();
		int off = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		s.teacher[off] = 0.05f;
		s.teacher[off + 1] = 0.95f;
		s.shadowHeads = shadowed ? ActionSpace.mask(ActionSpace.ATTACK) : 0;
	}

	private static float swingChance(SchoolLearner l, Random rng) {
		SchoolRollout.Step s = new SchoolRollout.Step();
		float sum = 0f;
		int[] act = new int[ActionSpace.HEADS];
		for (int i = 0; i < 50; i++) {
			fill(rng, s);
			l.act(s, ActionSpace.ALL_HEADS, false, 1f, act);
			float[] logits = l.lastLogits();
			int off = ActionSpace.OFFSETS[ActionSpace.ATTACK];
			sum += (float) (1.0 / (1.0 + Math.exp(logits[off] - logits[off + 1])));
		}
		return sum / 50;
	}

	private static float jumpChance(SchoolLearner l, Random rng) {
		SchoolRollout.Step s = new SchoolRollout.Step();
		float sum = 0f;
		int[] act = new int[ActionSpace.HEADS];
		for (int i = 0; i < 50; i++) {
			fill(rng, s);
			l.act(s, ActionSpace.ALL_HEADS, false, 1f, act);
			float[] logits = l.lastLogits();
			int off = ActionSpace.OFFSETS[ActionSpace.JUMP];
			sum += (float) (1.0 / (1.0 + Math.exp(logits[off] - logits[off + 1])));
		}
		return sum / 50;
	}

	@Test
	void stagesSaveAndLoad(@TempDir Path dir) throws Exception {
		SchoolStages st = new SchoolStages();
		st.addSteps(1234);
		for (int i = 0; i < 20; i++) {
			st.fight(Specialists.SWINGER, 10f + i, 5f, 600);
		}
		Path f = dir.resolve("stages.properties");
		st.save(f);
		SchoolStages back = new SchoolStages();
		back.load(f);
		assertEquals(st.describe(), back.describe());
		for (int i = 0; i < 30; i++) {
			st.moment(Technique.T7.ordinal(), i % 10 != 0, i % 2 == 0);
		}
		st.adapt();
		assertTrue(st.techPulls()[Technique.T7.ordinal()] < SchoolStages.TECH_PULL, "90% done: the pull fades");
		assertEquals(0.9f, st.doneRate(Technique.T7.ordinal()), 1e-6f);
		st.save(f);
		SchoolStages again = new SchoolStages();
		again.load(f);
		assertEquals(st.masteryLine(), again.masteryLine());
	}

	/** A weak technique's fights go to the bots its moments came against. */
	@Test
	void weakTechniquesFightTheBotsTheirMomentsComeAgainst() {
		SchoolStages st = new SchoolStages();
		st.reached = SchoolStages.FAMILIES - 1;
		java.util.Random rng = new java.util.Random(3);
		assertEquals(-1, st.botFor(Technique.C6.ordinal(), rng), "no moments seen yet");
		for (int i = 0; i < 5; i++) {
			st.sawMoment(Specialists.JUMP_RESETTER, Technique.C6.ordinal());
		}
		for (int i = 0; i < 20; i++) {
			assertEquals(Specialists.JUMP_RESETTER, st.botFor(Technique.C6.ordinal(), rng));
		}
		for (int i = 0; i < 50; i++) {
			int b = st.weakestBot(rng);
			assertTrue(b >= 0 && b < Specialists.KINDS);
		}
	}
}
