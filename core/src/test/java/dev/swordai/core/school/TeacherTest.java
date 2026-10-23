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
}
