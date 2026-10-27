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
		Random same = new Random(1);
		// Both from a new fight: what the chooser chose the tick before is an input.
		l.resetIntent();
		back.resetIntent();
		assertEquals(jumpChance(l, same), jumpChance(back, new Random(1)), 1e-5f);
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
