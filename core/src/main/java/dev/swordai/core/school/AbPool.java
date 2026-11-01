package dev.swordai.core.school;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The live A/B pool (1 Nov 2026). The paired screens against scripted bots repeat only to about +-5 and have not moved the live
 * results: the user's duels against real players gave 1.25 hits dealt per hit taken with the champion of 13-23 Oct and 1.27 with
 * the school's network on 1 Nov (tools/duels.py), after eight days of lessons. So the networks are compared where it counts: for
 * each duel the mod draws one arm of the pool at random, that arm plays the duel, and the start line says which ("[A/B arm tag]").
 * The arms meet the same opponents in the same hours, so the order effect and the difference between sessions (+-0.15 in the
 * ratio from one evening to the next) drop out; tools/duels.py reads the game log and gives each arm's share of the hits with its
 * standard error.
 *
 * <p>The pool is the file {@code swordai/school/ab/pool.txt}, one arm a line, fields separated by {@code |}:
 * {@code tag | network | rules | temperatures | weight}. The network is a path relative to the {@code ab} folder (or absolute;
 * {@code ../checkpoint.bin} is the installed one); rules is {@code on} (the installed model's fight-time rules over the network) or
 * {@code off}; temperatures is empty for none, {@code default} for the school folder's {@code play-temperature}, or a file; weight
 * defaults to 1. Lines starting with {@code #} are comments. An arm whose network is missing is left out; with no valid arm there
 * is no pool and the installed network plays as before.
 */
public final class AbPool {
	public static final String FOLDER = "ab";
	public static final String FILE = "pool.txt";

	public static final class Arm {
		public final String tag;
		public final Path network;
		public final boolean rules;
		/** The per-head play temperatures file, or null for none. */
		public final Path temperatures;
		public final double weight;

		Arm(String tag, Path network, boolean rules, Path temperatures, double weight) {
			this.tag = tag;
			this.network = network;
			this.rules = rules;
			this.temperatures = temperatures;
			this.weight = weight;
		}
	}

	private AbPool() {
	}

	/** The pool's valid arms ({@code schoolDir} is {@code swordai/school}); empty when there is no pool file or no valid arm. */
	public static List<Arm> read(Path schoolDir) {
		List<Arm> arms = new ArrayList<>();
		Path base = schoolDir.resolve(FOLDER);
		Path file = base.resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return arms;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return arms;
		}
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] f = line.split("\\|", -1);
			String tag = f[0].trim().replaceAll("[^A-Za-z0-9._-]", "_");
			if (tag.isEmpty() || f.length < 3 || f[1].trim().isEmpty()) {
				continue;
			}
			Path network = base.resolve(f[1].trim()).normalize();
			if (!Files.isRegularFile(network)) {
				continue;
			}
			String r = f[2].trim().toLowerCase(Locale.ROOT);
			boolean rules = r.equals("on") || r.equals("yes") || r.equals("true");
			if (!rules && !(r.equals("off") || r.equals("no") || r.equals("false"))) {
				continue;
			}
			Path temps = null;
			String t = f.length > 3 ? f[3].trim() : "";
			if (t.equals("default")) {
				Path d = schoolDir.resolve("play-temperature");
				temps = Files.isRegularFile(d) ? d : null;
			} else if (!t.isEmpty() && !t.equals("-")) {
				Path p = base.resolve(t).normalize();
				temps = Files.isRegularFile(p) ? p : null;
			}
			double weight = 1.0;
			if (f.length > 4 && !f[4].trim().isEmpty()) {
				try {
					weight = Double.parseDouble(f[4].trim());
				} catch (NumberFormatException e) {
					weight = 1.0;
				}
			}
			if (!(weight > 0.0) || Double.isInfinite(weight)) {
				weight = 1.0;
			}
			arms.add(new Arm(tag, network, rules, temps, weight));
		}
		return arms;
	}

	/** One arm drawn by weight; null when there are none. */
	public static Arm draw(List<Arm> arms, Random rng) {
		if (arms.isEmpty()) {
			return null;
		}
		double total = 0.0;
		for (Arm a : arms) {
			total += a.weight;
		}
		double u = rng.nextDouble() * total;
		for (Arm a : arms) {
			u -= a.weight;
			if (u <= 0.0) {
				return a;
			}
		}
		return arms.get(arms.size() - 1);
	}
}
