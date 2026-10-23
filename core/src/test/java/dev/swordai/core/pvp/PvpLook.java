package dev.swordai.core.pvp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Not a test: writes how the specialists fight to the file named by {@code PVP_LOOK}, for a look by hand. */
class PvpLook {
	@Test
	void look() throws IOException {
		String file = System.getenv("PVP_LOOK");
		if (file == null) {
			return;
		}
		StringBuilder out = new StringBuilder();
		Random r = new Random(1);
		int[][] pairs = {{Specialists.MIXER, Specialists.DOUBLE_CRITTER}, {Specialists.PUNISH_CRITTER, Specialists.DOUBLE_CRITTER},
				{Specialists.DOUBLE_CRITTER, Specialists.CRIT_BREAKER}, {Specialists.DEFLECTOR, Specialists.REVERSE_DEFLECTOR},
				{Specialists.STUN_ESCAPER, Specialists.LUNGER}, {Specialists.MIXER, Specialists.LUNGER}};
		for (int[] p : pairs) {
			for (int seed = 0; seed < 2; seed++) {
				Bout b = new Bout(Specialists.draw(p[0], r), Specialists.draw(p[1], r), 6.0, seed);
				b.run(1200);
				out.append(b.summary()).append(System.lineSeparator());
			}
		}
		String trace = System.getenv("PVP_TRACE");
		if (trace != null) {
			String[] two = trace.split(",");
			Bout b = new Bout(Specialists.draw(Integer.parseInt(two[0]), r), Specialists.draw(Integer.parseInt(two[1]), r), 6.0, 3);
			b.run(Integer.parseInt(two[2]));
			out.append(b.trace());
		}
		Files.writeString(Path.of(file), out);
	}
}
