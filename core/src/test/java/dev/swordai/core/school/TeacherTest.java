package dev.swordai.core.school;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TeacherTest {
	private static float[] softmax(float[] z) {
		float max = Float.NEGATIVE_INFINITY;
		for (float v : z) {
			max = Math.max(max, v);
		}
		double sum = 0;
		float[] p = new float[z.length];
		for (int k = 0; k < z.length; k++) {
			p[k] = (float) Math.exp(z[k] - max);
			sum += p[k];
		}
		for (int k = 0; k < z.length; k++) {
			p[k] /= (float) sum;
		}
		return p;
	}

	@Test
	void oneKeyIsKeptAsItIs() {
		float[] target = {0.005f, 0.99f, 0.005f};
		float[] out = new float[3];
		Teacher.aim(target, softmax(new float[] {0.3f, -1f, 2f}), 0, 3, out);
		assertArrayEquals(target, out);
	}

	/** p - aim(...) is the gradient of minus the log of the keys' summed chance, for either of two keys. */
	@Test
	void eitherKeyPullsToTheirSummedChance() {
		float[] z = {0.4f, 1.3f, -0.7f};
		float[] target = {0.5f, 0f, 0.5f};
		float[] p = softmax(z);
		float[] out = new float[3];
		Teacher.aim(target, p, 0, 3, out);
		double eps = 1e-3;
		for (int k = 0; k < 3; k++) {
			float[] up = z.clone();
			float[] down = z.clone();
			up[k] += (float) eps;
			down[k] -= (float) eps;
			float[] pu = softmax(up);
			float[] pd = softmax(down);
			double numeric = (-Math.log(pu[0] + pu[2]) + Math.log(pd[0] + pd[2])) / (2 * eps);
			assertEquals(numeric, p[k] - out[k], 1e-3);
		}
		assertEquals(1f, out[0] + out[1] + out[2], 1e-5);
	}

	@Test
	void aPressOfTheJumpOrSwingWeighsMore() {
		float[] t = new float[dev.swordai.core.rl.ActionSpace.TOTAL_LOGITS];
		int jump = dev.swordai.core.rl.ActionSpace.OFFSETS[dev.swordai.core.rl.ActionSpace.JUMP];
		t[jump] = 0.05f;
		t[jump + 1] = 0.95f;
		float[] vec = new float[dev.swordai.core.rl.ObsSpec.VEC];
		assertEquals(Teacher.PRESS_WEIGHT, Teacher.pressWeight(t, dev.swordai.core.rl.ActionSpace.JUMP, vec));
		t[jump] = 0.95f;
		t[jump + 1] = 0.05f;
		assertEquals(1f, Teacher.pressWeight(t, dev.swordai.core.rl.ActionSpace.JUMP, vec));
		// W let go: weighs more only while it still sprints.
		int fwd = dev.swordai.core.rl.ActionSpace.OFFSETS[dev.swordai.core.rl.ActionSpace.FORWARD];
		t[fwd] = 0.9f;
		t[fwd + 1] = 0.05f;
		t[fwd + 2] = 0.05f;
		assertEquals(1f, Teacher.pressWeight(t, dev.swordai.core.rl.ActionSpace.FORWARD, vec));
		vec[dev.swordai.core.rl.ObsSpec.VEC_SCALARS + dev.swordai.core.rl.ObsSpec.SC_SPRINTING] = 1f;
		assertEquals(Teacher.PRESS_WEIGHT, Teacher.pressWeight(t, dev.swordai.core.rl.ActionSpace.FORWARD, vec));
		t[fwd] = 0.05f;
		t[fwd + 1] = 0.9f;
		assertEquals(1f, Teacher.pressWeight(t, dev.swordai.core.rl.ActionSpace.FORWARD, vec), "W held is not a let go");
	}

	/** In a combo, about a third of its jumps on the ground come a tick late; outside a combo none do. */
	@Test
	void comboJumpsAreSometimesLate() {
		FairJump f = new FairJump(1L);
		int jump = dev.swordai.core.rl.ActionSpace.JUMP;
		int late = 0;
		for (int i = 0; i < 1000; i++) {
			int[] a = new int[dev.swordai.core.rl.ActionSpace.HEADS];
			a[jump] = 1;
			f.apply(a, true, 3, 2);
			if (a[jump] == 0) {
				late++;
				int[] next = new int[dev.swordai.core.rl.ActionSpace.HEADS];
				f.apply(next, true, 4, 2);
				assertEquals(1, next[jump], "pressed the tick after");
			}
		}
		assertEquals(FairJump.MISS, late / 1000f, 0.05f);
		int[] a = new int[dev.swordai.core.rl.ActionSpace.HEADS];
		a[jump] = 1;
		f.apply(a, true, 100, 0);
		assertEquals(1, a[jump], "not in a combo: as it is");
	}
}
