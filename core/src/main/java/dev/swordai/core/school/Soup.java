package dev.swordai.core.school;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Blends saved school networks into one (model soups, Wortsman et al. 2022; SWA, Izmailov et al. 2018; extrapolation, Zheng
 * et al. 2024): every weight becomes the sum of the networks' weights times their coefficients. Coefficients that sum to 1
 * average them; 1.5 and -0.5 extrapolate from the second past the first. The Adam moments, the reward scaler and the step
 * counts are the first network's. All school networks start from the installed model and move by small steps, so their
 * weights lie close enough to blend.
 *
 * <p>Usage: {@code Soup OUT.bin COEF1 NET1.bin COEF2 NET2.bin ...}
 */
public final class Soup {
	private Soup() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 3 || (args.length - 1) % 2 != 0) {
			System.err.println("usage: Soup OUT.bin COEF1 NET1.bin [COEF2 NET2.bin ...]");
			System.exit(2);
		}
		int n = (args.length - 1) / 2;
		Path[] nets = new Path[n];
		float[] coefs = new float[n];
		float sum = 0f;
		for (int i = 0; i < n; i++) {
			coefs[i] = Float.parseFloat(args[1 + 2 * i]);
			nets[i] = Path.of(args[2 + 2 * i]);
			sum += coefs[i];
		}
		SchoolLearner.blend(Path.of(args[0]), nets, coefs);
		System.out.println(String.format(Locale.ROOT, "blended %d networks (coefficients sum %.3f) into %s", n, sum, args[0]));
	}
}
