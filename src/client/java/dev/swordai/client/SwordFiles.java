package dev.swordai.client;

import com.google.gson.*;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Disk paths and config/error I/O under {@code <game dir>/swordai}. */
final class SwordFiles {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private SwordFiles() {}

	static Path dir() { return FabricLoader.getInstance().getGameDir().resolve("swordai"); }
	static Path checkpoint() { return dir().resolve("checkpoint.bin"); }
	static Path schoolDir() { return dir().resolve("school"); }
	static Path schoolCheckpoint() { return schoolDir().resolve("checkpoint.bin"); }
	static Path baselineCheckpoint() { Path b = schoolDir().resolve("baseline.bin"); return Files.exists(b) ? b : schoolDir().resolve("distilled-baseline.bin"); }
	static Path priorCheckpoint() { Path p = schoolDir().resolve("distilled-baseline.bin"); return Files.exists(p) ? p : baselineCheckpoint(); }
	static Path exploiterCheckpoint() { return schoolDir().resolve("exploiter.bin"); }
	static Path humanChallengerCheckpoint() { return schoolDir().resolve("human-challenger.bin"); }
	static Path idmCheckpoint() { return schoolDir().resolve("inverse-dynamics.bin"); }
	static Path leaguePoolDir() { return schoolDir().resolve("league-pool"); }
	static Path milestonesDir() { return schoolDir().resolve("milestones"); }
	static Path livePriorCheckpoint() { return schoolDir().resolve("prior-live.bin"); }
	static Path duelSyncConfig() { return dir().resolve("duel-sync-dir.txt"); }
	static Path leagueStageFile() { return dir().resolve("league-stage.txt"); }
	static Path realFightsDir() { Path rf = dir().resolve("real-fights"); return Files.isDirectory(rf) ? rf : fightsDir(); }
	static Path fightsDir() { return dir().resolve("fights"); }
	static Path benchDir() { return dir().resolve("bench"); }
	static Path anchorsDir() { return dir().resolve("anchors"); }
	static Path inbox() { return dir().resolve("inbox.txt"); }
	static Path config() { return dir().resolve("config.json"); }
	static Path optionsBackup() { return dir().resolve("options-backup.properties"); }
	static Path lastError() { return dir().resolve("last-error.txt"); }

	/** Copies completed new .txt.gz duels from the folder listed in duel-sync-dir.txt into real-fights/. */
	static int syncExternalDuels() {
		Path cfgFile = duelSyncConfig();
		if (!Files.exists(cfgFile)) return 0;
		int copied = 0;
		try {
			String raw = Files.readString(cfgFile, StandardCharsets.UTF_8).strip();
			if (raw.isEmpty()) return 0;
			Path src = Path.of(raw);
			if (!Files.isDirectory(src)) return 0;
			Path dstDir = dir().resolve("real-fights");
			Files.createDirectories(dstDir);
			long cutoff = System.currentTimeMillis() - 60_000L;
			try (var stream = Files.list(src)) {
				for (Path p : stream.toList()) {
					String name = p.getFileName().toString();
					if (!name.endsWith(".txt.gz")) continue;
					Path dst = dstDir.resolve(name);
					if (Files.exists(dst)) continue;
					if (Files.size(p) < 256L || Files.getLastModifiedTime(p).toMillis() > cutoff) continue;
					Files.copy(p, dst);
					copied++;
				}
			}
		} catch (IOException | InvalidPathException ignored) {}
		return copied;
	}

	static int readLeagueStage() {
		Path f = leagueStageFile();
		if (Files.exists(f)) {
			try { return Math.max(2, Math.min(6, Integer.parseInt(Files.readString(f, StandardCharsets.UTF_8).strip()))); }
			catch (IOException | NumberFormatException ignored) {}
		}
		return 6;
	}

	static Config loadConfig() {
		Path file = config();
		if (Files.exists(file)) {
			try { Config c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class); if (c != null) return c; }
			catch (IOException | JsonParseException e) { SwordAi.LOGGER.warn("bad config; using defaults", e); }
		}
		Config fresh = new Config();
		try { saveConfig(fresh); } catch (IOException ignored) {}
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
			StringWriter sw = new StringWriter().append(summary).append('\n');
			if (t != null) t.printStackTrace(new PrintWriter(sw));
			Files.writeString(lastError(), sw.toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {}
	}

	static String modVersion() {
		return FabricLoader.getInstance().getModContainer(SwordAi.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}
}
