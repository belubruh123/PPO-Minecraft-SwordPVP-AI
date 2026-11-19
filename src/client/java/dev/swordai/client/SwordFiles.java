package dev.swordai.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Disk paths and config/error I/O under {@code <game dir>/swordai}. */
final class SwordFiles {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private SwordFiles() {
	}

	static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("swordai");
	}

	static Path checkpoint() {
		return dir().resolve("checkpoint.bin");
	}

	static Path schoolDir() {
		return dir().resolve("school");
	}

	static Path schoolCheckpoint() {
		return schoolDir().resolve("checkpoint.bin");
	}

	static Path baselineCheckpoint() {
		Path b = schoolDir().resolve("baseline.bin");
		return Files.exists(b) ? b : schoolDir().resolve("distilled-baseline.bin");
	}

	static Path priorCheckpoint() {
		Path p = schoolDir().resolve("distilled-baseline.bin");
		return Files.exists(p) ? p : baselineCheckpoint();
	}

	static Path exploiterCheckpoint() {
		return schoolDir().resolve("exploiter.bin");
	}

	static Path idmCheckpoint() {
		return schoolDir().resolve("inverse-dynamics.bin");
	}

	/** Optional integer 2..5 selecting which staged league features are enabled (default 5). */
	static Path leagueStageFile() {
		return dir().resolve("league-stage.txt");
	}

	static Path realFightsDir() {
		Path rf = dir().resolve("real-fights");
		return Files.isDirectory(rf) ? rf : fightsDir();
	}

	static Path fightsDir() {
		return dir().resolve("fights");
	}

	static Path benchDir() {
		return dir().resolve("bench");
	}

	static Path inbox() {
		return dir().resolve("inbox.txt");
	}

	static Path config() {
		return dir().resolve("config.json");
	}

	static Path optionsBackup() {
		return dir().resolve("options-backup.properties");
	}

	static Path lastError() {
		return dir().resolve("last-error.txt");
	}

	static int readLeagueStage() {
		Path f = leagueStageFile();
		if (Files.exists(f)) {
			try {
				int v = Integer.parseInt(Files.readString(f, StandardCharsets.UTF_8).strip());
				return Math.max(2, Math.min(5, v));
			} catch (IOException | NumberFormatException ignored) {
			}
		}
		return 5;
	}

	static Config loadConfig() {
		Path file = config();
		if (Files.exists(file)) {
			try {
				Config c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (c != null) {
					return c;
				}
			} catch (IOException | JsonParseException e) {
				SwordAi.LOGGER.warn("could not read " + file + "; using defaults", e);
			}
		}
		Config fresh = new Config();
		try {
			saveConfig(fresh);
		} catch (IOException ignored) {
		}
		return fresh;
	}

	static void saveConfig(Config cfg) throws IOException {
		Files.createDirectories(dir());
		Path tmp = config().resolveSibling("config.json.tmp");
		Files.writeString(tmp, GSON.toJson(cfg), StandardCharsets.UTF_8);
		Files.move(tmp, config(), StandardCopyOption.REPLACE_EXISTING);
	}

	static void writeError(String summary, Throwable t) {
		try {
			Files.createDirectories(dir());
			StringWriter sw = new StringWriter();
			sw.append(summary).append('\n');
			if (t != null) {
				t.printStackTrace(new PrintWriter(sw));
			}
			Files.writeString(lastError(), sw.toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}

	static String modVersion() {
		return FabricLoader.getInstance().getModContainer(SwordAi.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}
}
