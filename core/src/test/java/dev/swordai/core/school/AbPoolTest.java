package dev.swordai.core.school;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbPoolTest {
	/** The pool's folder under a school folder, created. */
	private static Path ab(Path school) throws IOException {
		return Files.createDirectories(school.resolve(AbPool.FOLDER));
	}

	private static void pool(Path school, String text) throws IOException {
		Files.write(ab(school).resolve(AbPool.FILE), text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void noPoolFileMeansNoArms(@TempDir Path school) {
		assertTrue(AbPool.read(school).isEmpty());
		assertNull(AbPool.draw(AbPool.read(school), new Random(1)));
	}

	@Test
	void armsAreReadWithTheirRulesTemperaturesAndWeights(@TempDir Path school) throws IOException {
		Files.write(school.resolve("checkpoint.bin"), new byte[] {1});
		Files.write(school.resolve("play-temperature"), new byte[] {2});
		Files.write(ab(school).resolve("cand.bin"), new byte[] {3});
		pool(school, "# a comment\n"
				+ "installed | ../checkpoint.bin | on | | 1\n"
				+ "free-sharp | cand.bin | off | default | 2.5\n"
				+ "bad rules | cand.bin | maybe\n"
				+ "missing | gone.bin | on\n"
				+ "short | cand.bin\n"
				+ "weird tag! | cand.bin | no | - | -3\n");
		List<AbPool.Arm> arms = AbPool.read(school);
		assertEquals(3, arms.size());
		AbPool.Arm installed = arms.get(0);
		assertEquals("installed", installed.tag);
		assertEquals(school.resolve("checkpoint.bin").normalize(), installed.network);
		assertTrue(installed.rules);
		assertNull(installed.temperatures);
		assertEquals(1.0, installed.weight, 0.0);
		AbPool.Arm free = arms.get(1);
		assertFalse(free.rules);
		assertEquals(school.resolve("play-temperature"), free.temperatures);
		assertEquals(2.5, free.weight, 0.0);
		AbPool.Arm weird = arms.get(2);
		assertEquals("weird_tag_", weird.tag);
		assertFalse(weird.rules);
		assertNull(weird.temperatures);
		assertEquals(1.0, weird.weight, 0.0);
	}

	@Test
	void drawFollowsTheWeights(@TempDir Path school) throws IOException {
		Files.write(school.resolve("checkpoint.bin"), new byte[] {1});
		Files.write(ab(school).resolve("c.bin"), new byte[] {3});
		pool(school, "a | ../checkpoint.bin | on | | 1\nb | c.bin | on | | 3\n");
		List<AbPool.Arm> arms = AbPool.read(school);
		assertEquals(2, arms.size());
		Random rng = new Random(42);
		Map<String, Integer> seen = new HashMap<>();
		for (int i = 0; i < 4000; i++) {
			AbPool.Arm a = AbPool.draw(arms, rng);
			assertNotNull(a);
			seen.merge(a.tag, 1, Integer::sum);
		}
		double shareB = seen.get("b") / 4000.0;
		assertTrue(shareB > 0.71 && shareB < 0.79, "arm b drawn " + shareB);
	}

	@Test
	void theOptionsFieldSwitchesHitAndOutOn(@TempDir Path school) throws IOException {
		Files.write(school.resolve("checkpoint.bin"), new byte[] {1});
		Files.write(ab(school).resolve("c.bin"), new byte[] {3});
		pool(school, "plain | ../checkpoint.bin | on | | 1 |\n"
				+ "out | c.bin | on | | 1 | hitout\n"
				+ "both | c.bin | off | | 2 | foo, HITOUT\n");
		List<AbPool.Arm> arms = AbPool.read(school);
		assertEquals(3, arms.size());
		assertFalse(arms.get(0).hitAndOut);
		assertTrue(arms.get(1).hitAndOut);
		assertTrue(arms.get(2).hitAndOut);
		assertFalse(arms.get(2).rules);
	}
}
