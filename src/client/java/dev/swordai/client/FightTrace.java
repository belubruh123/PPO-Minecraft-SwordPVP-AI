package dev.swordai.client;

import dev.swordai.SwordAi;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * A training fight against a scripted opponent, tick by tick, for reading afterwards how a fight was
 * lost: the distance, both swords (the opponent's as the server has it), who is on the ground and how
 * the opponent is moving, the keys and which rule pressed them, the tactic, and every hit either way
 * with whether it was critical. One small gzipped text file a fight in {@code swordai/traces}, named
 * with the time, the kind of opponent, the seed, W or L and the damage either way; only the newest
 * {@link #KEPT} are kept. On with {@code Config.traceFights}. The user, 22 October: "read the cases it
 * loses yourself and pretend if you were the player how do you counter it".
 */
final class FightTrace {
	static final int KEPT = 1500;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMdd-HHmmss");

	private final List<String> lines = new ArrayList<>();
	private String head;
	private boolean on;

	/** A fight begins: what it is against. */
	void begin(String kind, long seed, boolean replay, String setup) {
		lines.clear();
		head = String.format(Locale.ROOT, "%s seed %s%s; %s", kind, Long.toHexString(seed), replay ? " (replay of a lost fight)" : "", setup);
		on = true;
	}

	boolean on() {
		return on;
	}

	void tick(String line) {
		if (on && lines.size() < 4000) {
			lines.add(line);
		}
	}

	/** The fight is over: written out, the oldest traces beyond {@link #KEPT} deleted. */
	void end(Path dir, String kind, long seed, float dealt, float taken, int ticks) {
		if (!on) {
			return;
		}
		on = false;
		boolean won = dealt > taken;
		String name = String.format(Locale.ROOT, "%s-%s-%s-%s-%.0f-%.0f.txt.gz", LocalDateTime.now().format(STAMP), kind.replace(' ', '_'),
				Long.toHexString(seed), won ? "W" : "L", dealt, taken);
		try {
			Files.createDirectories(dir);
			try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dir.resolve(name))), StandardCharsets.UTF_8)) {
				w.write(String.format(Locale.ROOT, "%s%nresult %s: dealt %.1f, taken %.1f, %d ticks%n", head, won ? "won" : "lost", dealt, taken, ticks));
				w.write("columns: tick | dist (eye to hitbox, - out of sight) | my sword, its sword (server) | ground me/it | it: up speed, toward me speed"
						+ " | my keys (W/S, A/D, sprint, jump, attack) | rules | tactic | what happened since the last tick | dealt/taken so far\n");
				for (String l : lines) {
					w.write(l);
					w.write('\n');
				}
			}
			try (Stream<Path> files = Files.list(dir)) {
				List<Path> all = files.filter(p -> p.getFileName().toString().endsWith(".txt.gz"))
						.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
				for (int i = 0; i < all.size() - KEPT; i++) {
					Files.deleteIfExists(all.get(i));
				}
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not write the fight trace {}: {}", name, e.toString());
		}
		lines.clear();
	}
}
